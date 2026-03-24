package patience.meshchat;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * MeshManager — Bluetooth-only mesh networking engine.
 *
 * Responsibilities:
 *  1. PEER DISCOVERY    — Scans for Bluetooth devices advertising our network name
 *  2. CONNECTIONS       — RFCOMM connections to nearby peers
 *  3. MESSAGE ROUTING   — Broadcast and private (addressed) message routing
 *  4. ENCRYPTION        — AES-256-GCM via CryptoManager
 *  5. OFFLINE QUEUING   — Per-recipient queues, flushed on reconnect
 *  6. IDENTITY          — Handshake protocol to exchange usernames and UUIDs
 *  7. DISTANCE CONTROL  — RSSI threshold determines whether to auto-connect
 *
 * NETWORK NAMING:
 * ──────────────
 * Each mesh network has a human-readable name. Devices in the same network
 * advertise a Bluetooth device name of "MC_<networkName>". This lets devices
 * find each other during scanning by matching the prefix.
 *
 * PRIVATE MESSAGING ROUTING:
 * ──────────────────────────
 * Messages have an optional recipientId (UUID). If set:
 *   - If recipient is directly connected → send directly to that socket
 *   - If not → flood to all peers (they route it forward toward the recipient)
 *   - On receive: if I'm the recipient → show in UI + send ACK
 *                 if not me → forward only, don't show in UI
 *
 * RSSI THRESHOLD:
 * ───────────────
 * When a BT device is discovered, its RSSI is compared to rssiThreshold.
 * Devices above the threshold are auto-connected (direct link).
 * Devices below it are tracked but not auto-connected (rely on relay nodes).
 * Users can adjust this threshold in Settings.
 */
public class MeshManager {

    private static final String TAG = "MeshManager";

    // ─── BT Service Identity ────────────────────────────────────────────

    /** RFCOMM service UUID — all MeshChat devices use this to find each other */
    private static final UUID MY_UUID =
            UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66");

    private static final String SERVICE_NAME = "MeshChat";

    // ─── Network name format ────────────────────────────────────────────

    /** BT device name prefix for all MeshChat nodes */
    private static final String BT_PREFIX = "MC_";

    /** Maximum length for Bluetooth device names */
    private static final int BT_NAME_MAX = 30;

    // ─── SharedPreferences keys ─────────────────────────────────────────

    private static final String PREFS_NAME = RegistrationActivity.PREFS_NAME;
    private static final String KEY_NODE_ID = "node_id";
    private static final String KEY_NETWORK_NAME = "current_network_name";
    public static final String KEY_RSSI_THRESHOLD = "rssi_threshold";
    public static final int DEFAULT_RSSI_THRESHOLD = -80;

    // ─── Limits ─────────────────────────────────────────────────────────

    private static final int MAX_CACHED_MSG_IDS = 10_000;
    private static final int DISCOVERY_INTERVAL_SEC = 30;
    private static final String DEFAULT_PASSPHRASE = "MeshChat";

    // ─── Core state ─────────────────────────────────────────────────────

    private final Context context;
    private final SharedPreferences prefs;
    private final CryptoManager cryptoManager;

    /** This device's persistent UUID (generated once on install) */
    private final String myNodeId;

    /** User's display name */
    private String username;

    /** Name of the mesh network we've joined (null = not in any network) */
    private volatile String currentNetworkName;

    /**
     * RSSI threshold in dBm. Devices with signal >= this value are
     * auto-connected. Devices below it are relayed through other nodes.
     */
    private int rssiThreshold;

    // ─── Bluetooth ──────────────────────────────────────────────────────

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothServerThread bluetoothServerThread;

    // ─── Connection tracking ────────────────────────────────────────────

    /** BT MAC addresses of currently connected peers */
    private final Set<String> connectedNodes = ConcurrentHashMap.newKeySet();

    /** Output streams keyed by peer's BT MAC address */
    private final Map<String, ObjectOutputStream> bluetoothOutputStreams =
            new ConcurrentHashMap<>();

    /** Active Bluetooth sockets keyed by BT MAC address */
    private final Map<String, BluetoothSocket> bluetoothSockets =
            new ConcurrentHashMap<>();

    // ─── Node identity mapping ──────────────────────────────────────────

    /** UUID → BT MAC: used to find the socket for a private message recipient */
    private final Map<String, String> nodeIdToAddress = new ConcurrentHashMap<>();

    /** BT MAC → UUID: used to learn a peer's UUID from their socket address */
    private final Map<String, String> addressToNodeId = new ConcurrentHashMap<>();

    /** UUID → display name: populated during handshake */
    private final Map<String, String> nodeNames = new ConcurrentHashMap<>();

    /** BT MAC → last known RSSI (dBm) */
    private final Map<String, Integer> rssiValues = new ConcurrentHashMap<>();

    // ─── Discovery ──────────────────────────────────────────────────────

    /** Recently discovered peers (for the peers list UI) */
    private final Map<String, PeerAdapter.PeerInfo> discoveredPeers =
            new ConcurrentHashMap<>();

    /** Unique network names found during scanning, keyed by name */
    private final Map<String, Network> discoveredNetworks = new ConcurrentHashMap<>();

    // ─── Message deduplication (LRU) ────────────────────────────────────

    private final Set<String> processedMessages = Collections.newSetFromMap(
            Collections.synchronizedMap(
                    new LinkedHashMap<String, Boolean>(MAX_CACHED_MSG_IDS + 1, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                            return size() > MAX_CACHED_MSG_IDS;
                        }
                    }
            )
    );

    // ─── Offline queues ─────────────────────────────────────────────────

    /** Queue for broadcast messages when no peers are connected */
    private final ConcurrentLinkedQueue<Message> broadcastOfflineQueue =
            new ConcurrentLinkedQueue<>();

    /** Per-recipient queues for private messages when recipient isn't reachable */
    private final Map<String, ConcurrentLinkedQueue<Message>> perRecipientQueue =
            new ConcurrentHashMap<>();

    // ─── Visibility ─────────────────────────────────────────────────────

    private volatile boolean visible = true;

    // ─── Callbacks & threading ──────────────────────────────────────────

    private MessageListener messageListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ScheduledExecutorService discoveryScheduler;

    // ═══════════════════════════════════════════════════════════════════
    // CALLBACK INTERFACE
    // ═══════════════════════════════════════════════════════════════════

    public interface MessageListener {
        /** A user chat message arrived (broadcast or private addressed to us) */
        void onMessageReceived(Message message);

        /** Connected node list changed — use to refresh Peers and Topology screens */
        void onNodeInfoUpdated(List<NodeInfo> nodes);

        /** Scan found new mesh networks — use to refresh Network Discovery screen */
        void onNetworkDiscovered(List<Network> networks);

        /** Offline queue was flushed after a new connection */
        void onQueueFlushed(int messageCount);

        /** ACK received: a private message was delivered to the recipient */
        void onDeliveryStatusChanged(String messageId);

        /** We successfully joined or created a network */
        void onNetworkJoined(String networkName);

        /** We left the current network */
        void onNetworkLeft();
    }

    // ═══════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════

    public MeshManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // Load or generate persistent node UUID
        String storedId = prefs.getString(KEY_NODE_ID, null);
        if (storedId == null) {
            storedId = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_NODE_ID, storedId).apply();
        }
        this.myNodeId = storedId;

        this.username = prefs.getString(RegistrationActivity.KEY_USERNAME, Build.MODEL);
        this.currentNetworkName = prefs.getString(KEY_NETWORK_NAME, null);
        this.rssiThreshold = prefs.getInt(KEY_RSSI_THRESHOLD, DEFAULT_RSSI_THRESHOLD);
        this.cryptoManager = new CryptoManager(DEFAULT_PASSPHRASE);

        initialize();
    }

    private void initialize() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter != null) {
            setupBluetooth();
        }
        registerReceivers();
        startPeriodicDiscovery();
    }

    // ─── Bluetooth setup ────────────────────────────────────────────────

    private void setupBluetooth() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                    || hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                String btName = currentNetworkName != null
                        ? buildBtName(currentNetworkName)
                        : "MeshChat_" + username;
                bluetoothAdapter.setName(btName);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "BT name set failed: " + e.getMessage());
        }
        bluetoothServerThread = new BluetoothServerThread();
        bluetoothServerThread.start();
    }

    private String buildBtName(String networkName) {
        String full = BT_PREFIX + networkName;
        return full.length() > BT_NAME_MAX ? full.substring(0, BT_NAME_MAX) : full;
    }

    // ─── Broadcast receivers ────────────────────────────────────────────

    private void registerReceivers() {
        IntentFilter btFilter = new IntentFilter();
        btFilter.addAction(BluetoothDevice.ACTION_FOUND);
        context.registerReceiver(bluetoothReceiver, btFilter);
    }

    /**
     * Handles discovered Bluetooth devices.
     *
     * Only processes devices whose name starts with "MC_" (MeshChat prefix).
     * Tracks their network name (for the discovery screen) and RSSI.
     * If they're in our current network and have good enough signal, connects.
     */
    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) return;

            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device == null) return;

            try {
                String name = device.getName();
                String address = device.getAddress();
                if (name == null || !name.startsWith(BT_PREFIX)) return;

                String networkName = name.substring(BT_PREFIX.length());
                int rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                rssiValues.put(address, rssi);

                // Update discovered networks list
                Network net = discoveredNetworks.get(networkName);
                if (net == null) {
                    discoveredNetworks.put(networkName, new Network(networkName));
                } else {
                    net.nodeCount++;
                }
                notifyNetworksUpdated();

                // Track as discovered peer
                boolean alreadyConnected = connectedNodes.contains(address);
                discoveredPeers.put(address, new PeerAdapter.PeerInfo(
                        name, address, alreadyConnected, "bluetooth", rssi));

                // Auto-connect if we're in the same network and signal is strong enough
                if (currentNetworkName != null
                        && networkName.equals(currentNetworkName)
                        && !alreadyConnected
                        && rssi >= rssiThreshold) {
                    new BluetoothClientThread(device).start();
                }
            } catch (SecurityException e) {
                Log.w(TAG, "BT receiver permission error: " + e.getMessage());
            }
        }
    };

    // ═══════════════════════════════════════════════════════════════════
    // NETWORK MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Joins an existing mesh network by name.
     * Updates our BT device name to "MC_<name>" so peers can discover us.
     */
    public void joinNetwork(String networkName) {
        this.currentNetworkName = networkName;
        prefs.edit().putString(KEY_NETWORK_NAME, networkName).apply();
        updateBtName();
        startDiscovery();
        if (messageListener != null) {
            mainHandler.post(() -> messageListener.onNetworkJoined(networkName));
        }
        Log.d(TAG, "Joined network: " + networkName);
    }

    /**
     * Creates a new mesh network with the given name and joins it immediately.
     */
    public void createNetwork(String networkName) {
        joinNetwork(networkName); // creating and joining are the same operation
    }

    /**
     * Leaves the current network. Disconnects all peers and resets BT name.
     */
    public void leaveNetwork() {
        this.currentNetworkName = null;
        prefs.edit().remove(KEY_NETWORK_NAME).apply();

        // Disconnect all peers
        List<String> snapshot = new ArrayList<>(connectedNodes);
        for (String mac : snapshot) {
            notifyNodeDisconnected(mac);
        }

        // Reset BT name
        try {
            if (bluetoothAdapter != null
                    && (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                    || hasPermission(Manifest.permission.BLUETOOTH_CONNECT))) {
                bluetoothAdapter.setName("Android_" + Build.MODEL);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "BT name reset failed: " + e.getMessage());
        }

        discoveredPeers.clear();
        if (messageListener != null) {
            mainHandler.post(() -> messageListener.onNetworkLeft());
        }
    }

    private void updateBtName() {
        try {
            if (bluetoothAdapter != null
                    && (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                    || hasPermission(Manifest.permission.BLUETOOTH_CONNECT))) {
                bluetoothAdapter.setName(currentNetworkName != null
                        ? buildBtName(currentNetworkName)
                        : "MeshChat_" + username);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "BT name update failed: " + e.getMessage());
        }
    }

    /** Returns the name of the currently joined network, or null if none */
    public String getCurrentNetworkName() { return currentNetworkName; }

    /** Returns our persistent node UUID */
    public String getMyNodeId() { return myNodeId; }

    // ═══════════════════════════════════════════════════════════════════
    // DISCOVERY
    // ═══════════════════════════════════════════════════════════════════

    /** Starts a Bluetooth scan. Called by the UI and periodically by the scheduler. */
    public void startDiscovery() {
        discoveredPeers.clear();
        try {
            if (bluetoothAdapter != null && !bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.startDiscovery();
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Discovery permission denied: " + e.getMessage());
        }
        // Notify UI of scan completion after ~12s (standard BT discovery duration)
        mainHandler.postDelayed(() -> {
            if (messageListener != null) {
                messageListener.onNetworkDiscovered(getDiscoveredNetworks());
            }
        }, 12_000);
    }

    private void startPeriodicDiscovery() {
        discoveryScheduler = Executors.newSingleThreadScheduledExecutor();
        discoveryScheduler.scheduleAtFixedRate(
                this::startDiscovery,
                DISCOVERY_INTERVAL_SEC,
                DISCOVERY_INTERVAL_SEC,
                TimeUnit.SECONDS
        );
    }

    /** Returns the current list of discovered mesh networks */
    public List<Network> getDiscoveredNetworks() {
        return new ArrayList<>(discoveredNetworks.values());
    }

    // ═══════════════════════════════════════════════════════════════════
    // MESSAGE SENDING
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Broadcasts a message to everyone in the network.
     * If no peers are connected, queues it for delivery when a peer joins.
     */
    public void broadcastMessage(Message message) {
        // Stamp with our proper node UUID
        message.setSenderId(myNodeId);
        message.setOriginalSenderId(myNodeId);
        message.setChannelType(Message.CHANNEL_BROADCAST);

        processedMessages.add(message.getId());

        Message networkCopy = message.copy();
        networkCopy.setContent(cryptoManager.encrypt(message.getContent()));
        networkCopy.setEncrypted(true);

        if (bluetoothOutputStreams.isEmpty()) {
            broadcastOfflineQueue.add(networkCopy);
        } else {
            sendToAllPeers(networkCopy, null);
        }
    }

    /**
     * Sends a private message to a specific peer identified by their node UUID.
     *
     * If the peer is directly connected, the message goes straight to them.
     * If not, we flood it to all connected peers so the mesh can relay it.
     * If nobody is connected, it's queued per-recipient.
     */
    public void sendPrivateMessage(Message message, String recipientId) {
        message.setSenderId(myNodeId);
        message.setOriginalSenderId(myNodeId);
        message.setRecipientId(recipientId);
        message.setChannelType(Message.CHANNEL_PRIVATE);

        processedMessages.add(message.getId());

        Message networkCopy = message.copy();
        networkCopy.setContent(cryptoManager.encrypt(message.getContent()));
        networkCopy.setEncrypted(true);

        String recipientMac = nodeIdToAddress.get(recipientId);
        if (recipientMac != null && connectedNodes.contains(recipientMac)) {
            // Direct link — send only to the recipient
            sendToNode(recipientMac, networkCopy);
        } else if (!bluetoothOutputStreams.isEmpty()) {
            // Relay through mesh
            sendToAllPeers(networkCopy, null);
        } else {
            // No peers — queue for when they connect
            perRecipientQueue
                    .computeIfAbsent(recipientId, k -> new ConcurrentLinkedQueue<>())
                    .add(networkCopy);
        }
    }

    /**
     * Sends a message to all connected peers, optionally excluding the source.
     *
     * @param excludeAddress BT MAC to skip (the peer we received this message from),
     *                       or null to send to everyone.
     */
    private void sendToAllPeers(Message message, String excludeAddress) {
        for (Map.Entry<String, ObjectOutputStream> entry : bluetoothOutputStreams.entrySet()) {
            String mac = entry.getKey();
            if (mac.equals(excludeAddress)) continue;
            writeToStream(entry.getValue(), message, mac);
        }
    }

    /** Sends a message to a single specific peer by BT MAC address */
    private void sendToNode(String mac, Message message) {
        ObjectOutputStream out = bluetoothOutputStreams.get(mac);
        if (out != null) {
            writeToStream(out, message, mac);
        }
    }

    private void writeToStream(ObjectOutputStream out, Message message, String nodeId) {
        try {
            synchronized (out) {
                out.writeObject(message);
                out.flush();
                out.reset();
            }
        } catch (IOException e) {
            Log.w(TAG, "Write to " + nodeId + " failed: " + e.getMessage());
            notifyNodeDisconnected(nodeId);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // MESSAGE RECEIVING & ROUTING
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Processes every incoming message from a peer socket.
     *
     * @param message     The incoming message object
     * @param sourceAddress BT MAC of the peer who sent it (for relay exclusion)
     */
    private void handleIncomingMessage(Message message, String sourceAddress) {
        // ── Control frames (handshake / ACK) ──
        if (message.getSubType() == Message.SUBTYPE_HANDSHAKE) {
            handleHandshake(message, sourceAddress);
            return;
        }
        if (message.getSubType() == Message.SUBTYPE_ACK) {
            handleAck(message);
            return;
        }

        // ── Deduplication ──
        if (processedMessages.contains(message.getId())) return;
        processedMessages.add(message.getId());

        // ── TTL check ──
        if (message.isExpired()) {
            Log.d(TAG, "Dropped expired message: " + message.getId());
            return;
        }

        String recipientId = message.getRecipientId();

        // ── Private message NOT addressed to us → relay only ──
        if (recipientId != null && !recipientId.equals(myNodeId)) {
            if (!message.canForward()) return;
            message.incrementHopCount();
            // Try to find the recipient's MAC for a direct hop
            String recipientMac = nodeIdToAddress.get(recipientId);
            if (recipientMac != null && connectedNodes.contains(recipientMac)) {
                sendToNode(recipientMac, message); // final hop!
            } else {
                sendToAllPeers(message, sourceAddress); // flood forward
            }
            return; // Do NOT show in UI
        }

        // ── Broadcast messages → forward to other peers ──
        if (recipientId == null && message.canForward()) {
            message.incrementHopCount();
            sendToAllPeers(message, sourceAddress);
        }

        // ── Private message FOR US → send ACK ──
        if (recipientId != null && recipientId.equals(myNodeId)) {
            sendAck(message.getId(), message.getSenderId(), sourceAddress);
        }

        // ── Decrypt and deliver to UI ──
        if (message.isEncrypted()) {
            String decrypted = cryptoManager.decrypt(message.getContent());
            message.setContent(decrypted);
            message.setEncrypted(false);
        }
        message.setType(Message.TYPE_RECEIVED);

        if (messageListener != null) {
            mainHandler.post(() -> messageListener.onMessageReceived(message));
        }
    }

    // ─── Handshake ──────────────────────────────────────────────────────

    /**
     * Sends our identity to a newly connected peer.
     * Must be called immediately after the ObjectOutputStream is ready.
     */
    private void sendHandshake(ObjectOutputStream out) {
        Message handshake = Message.createHandshake(username, myNodeId);
        try {
            synchronized (out) {
                out.writeObject(handshake);
                out.flush();
                out.reset();
            }
        } catch (IOException e) {
            Log.w(TAG, "Handshake send failed: " + e.getMessage());
        }
    }

    /** Processes an incoming handshake, mapping the peer's UUID to their BT MAC */
    private void handleHandshake(Message message, String sourceMac) {
        String content = message.getContent();
        String[] parts = content.split("\\|", 2);
        if (parts.length < 2) return;

        String peerUsername = parts[0];
        String peerNodeId = parts[1];

        // Store bidirectional UUID ↔ MAC mapping
        nodeIdToAddress.put(peerNodeId, sourceMac);
        addressToNodeId.put(sourceMac, peerNodeId);
        nodeNames.put(peerNodeId, peerUsername);

        Log.d(TAG, "Handshake from " + peerUsername + " (" + peerNodeId + ") @ " + sourceMac);
        notifyNodeInfoUpdated();

        // Flush any queued private messages for this peer
        flushPerRecipientQueue(peerNodeId, sourceMac);
    }

    // ─── ACK ────────────────────────────────────────────────────────────

    private void sendAck(String originalMsgId, String originalSenderId, String sourceMac) {
        Message ack = Message.createAck(originalMsgId, myNodeId, originalSenderId);
        sendToNode(sourceMac, ack); // ACK goes directly back to sender
    }

    private void handleAck(Message ack) {
        String originalMsgId = ack.getContent();
        if (messageListener != null) {
            mainHandler.post(() -> messageListener.onDeliveryStatusChanged(originalMsgId));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // OFFLINE QUEUES
    // ═══════════════════════════════════════════════════════════════════

    private void flushBroadcastQueue() {
        if (broadcastOfflineQueue.isEmpty()) return;
        int count = 0;
        Message msg;
        while ((msg = broadcastOfflineQueue.poll()) != null) {
            sendToAllPeers(msg, null);
            count++;
        }
        if (messageListener != null && count > 0) {
            int total = count;
            mainHandler.post(() -> messageListener.onQueueFlushed(total));
        }
    }

    private void flushPerRecipientQueue(String recipientId, String recipientMac) {
        ConcurrentLinkedQueue<Message> queue = perRecipientQueue.get(recipientId);
        if (queue == null || queue.isEmpty()) return;
        Message msg;
        while ((msg = queue.poll()) != null) {
            sendToNode(recipientMac, msg);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // NODE CONNECTION MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════

    private void notifyNodeConnected(String mac) {
        connectedNodes.add(mac);
        notifyNodeInfoUpdated();
        flushBroadcastQueue();
    }

    private void notifyNodeDisconnected(String mac) {
        connectedNodes.remove(mac);

        ObjectOutputStream out = bluetoothOutputStreams.remove(mac);
        if (out != null) { try { out.close(); } catch (IOException ignored) {} }

        BluetoothSocket sock = bluetoothSockets.remove(mac);
        if (sock != null) { try { sock.close(); } catch (IOException ignored) {} }

        // Clean up identity maps
        String uuid = addressToNodeId.remove(mac);
        if (uuid != null) nodeIdToAddress.remove(uuid);

        notifyNodeInfoUpdated();
    }

    // ═══════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════

    public void setMessageListener(MessageListener listener) {
        this.messageListener = listener;
    }

    public int getConnectedNodeCount() { return connectedNodes.size(); }

    public int getQueuedMessageCount() {
        int count = broadcastOfflineQueue.size();
        for (ConcurrentLinkedQueue<Message> q : perRecipientQueue.values()) {
            count += q.size();
        }
        return count;
    }

    /** Returns live info about all currently connected peer nodes */
    public List<NodeInfo> getConnectedNodeInfos() {
        List<NodeInfo> result = new ArrayList<>();
        for (String mac : connectedNodes) {
            String uuid = addressToNodeId.getOrDefault(mac, mac);
            String name = nodeNames.getOrDefault(uuid, "Unknown");
            int rssi = rssiValues.getOrDefault(mac, Integer.MIN_VALUE);
            result.add(new NodeInfo(uuid, mac, name, rssi, true));
        }
        return result;
    }

    /** Returns the display name associated with a peer node UUID */
    public String getPeerName(String nodeId) {
        return nodeNames.getOrDefault(nodeId, "Unknown");
    }

    public String getUsername() { return username; }
    public boolean isVisible() { return visible; }
    public CryptoManager getCryptoManager() { return cryptoManager; }

    /**
     * Updates the RSSI threshold used for auto-connection decisions.
     * Persists the value to SharedPreferences.
     *
     * @param threshold RSSI value in dBm (e.g. -80). Devices with signal
     *                  >= this are auto-connected; others are relay-only.
     */
    public void setRssiThreshold(int threshold) {
        this.rssiThreshold = threshold;
        prefs.edit().putInt(KEY_RSSI_THRESHOLD, threshold).apply();
    }

    public int getRssiThreshold() { return rssiThreshold; }

    public void setVisible(boolean visible) {
        this.visible = visible;
        try {
            if (bluetoothAdapter != null
                    && (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                    || hasPermission(Manifest.permission.BLUETOOTH_CONNECT))) {
                if (visible) {
                    updateBtName();
                } else {
                    bluetoothAdapter.setName("Android_" + Build.MODEL);
                    bluetoothAdapter.cancelDiscovery();
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Visibility change failed: " + e.getMessage());
        }
    }

    // ─── Listener notifications ─────────────────────────────────────────

    private void notifyNodeInfoUpdated() {
        if (messageListener == null) return;
        List<NodeInfo> nodes = getConnectedNodeInfos();
        mainHandler.post(() -> messageListener.onNodeInfoUpdated(nodes));
    }

    private void notifyNetworksUpdated() {
        if (messageListener == null) return;
        List<Network> nets = getDiscoveredNetworks();
        mainHandler.post(() -> messageListener.onNetworkDiscovered(nets));
    }

    // ─── Permission helper ──────────────────────────────────────────────

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(context, permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ═══════════════════════════════════════════════════════════════════
    // CLEANUP
    // ═══════════════════════════════════════════════════════════════════

    public void cleanup() {
        if (discoveryScheduler != null && !discoveryScheduler.isShutdown()) {
            discoveryScheduler.shutdownNow();
        }
        try { context.unregisterReceiver(bluetoothReceiver); } catch (Exception ignored) {}
        if (bluetoothServerThread != null) bluetoothServerThread.interrupt();

        for (ObjectOutputStream out : bluetoothOutputStreams.values()) {
            try { out.close(); } catch (IOException ignored) {}
        }
        for (BluetoothSocket s : bluetoothSockets.values()) {
            try { s.close(); } catch (IOException ignored) {}
        }
        Log.d(TAG, "MeshManager cleaned up.");
    }

    // ═══════════════════════════════════════════════════════════════════
    // NETWORKING THREADS
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Listens for incoming Bluetooth RFCOMM connections.
     * Runs in a background thread to avoid blocking the UI.
     */
    private class BluetoothServerThread extends Thread {

        BluetoothServerThread() { setName("BT-Server"); }

        @Override
        public void run() {
            try (BluetoothServerSocket serverSocket =
                         bluetoothAdapter.listenUsingRfcommWithServiceRecord(
                                 SERVICE_NAME, MY_UUID)) {
                Log.d(TAG, "BT server listening...");
                while (!isInterrupted()) {
                    BluetoothSocket socket = serverSocket.accept();
                    String mac = socket.getRemoteDevice().getAddress();
                    bluetoothSockets.put(mac, socket);
                    new MessageReceiverThread(socket).start();
                }
            } catch (IOException e) {
                if (!isInterrupted()) Log.e(TAG, "BT server error: " + e.getMessage());
            } catch (SecurityException e) {
                Log.e(TAG, "BT server permission denied: " + e.getMessage());
            }
        }
    }

    /**
     * Initiates an outgoing Bluetooth connection to a discovered peer.
     */
    private class BluetoothClientThread extends Thread {
        private final BluetoothDevice device;

        BluetoothClientThread(BluetoothDevice device) {
            this.device = device;
            setName("BT-Client-" + device.getAddress());
        }

        @Override
        public void run() {
            try {
                BluetoothSocket socket =
                        device.createRfcommSocketToServiceRecord(MY_UUID);
                Log.d(TAG, "Connecting to: " + device.getAddress());
                socket.connect();
                bluetoothSockets.put(device.getAddress(), socket);
                new MessageReceiverThread(socket).start();
            } catch (IOException e) {
                Log.e(TAG, "BT connect failed: " + e.getMessage());
            } catch (SecurityException e) {
                Log.e(TAG, "BT connect permission denied: " + e.getMessage());
            }
        }
    }

    /**
     * Manages bidirectional messaging on an established Bluetooth connection.
     *
     * DEADLOCK PREVENTION: ObjectOutputStream MUST be created and flushed
     * before ObjectInputStream. Both sides do this — they write their stream
     * header first, then read the remote header. This prevents both sides
     * from blocking forever waiting for the other's header.
     */
    private class MessageReceiverThread extends Thread {
        private final BluetoothSocket socket;

        MessageReceiverThread(BluetoothSocket socket) {
            this.socket = socket;
            setName("MsgReceiver-" + socket.hashCode());
        }

        @Override
        public void run() {
            String mac = "";
            try {
                mac = socket.getRemoteDevice().getAddress();

                // Output stream first (writes header), then flush, then input stream
                ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                out.flush();
                bluetoothOutputStreams.put(mac, out);

                ObjectInputStream in = new ObjectInputStream(socket.getInputStream());

                // Send our handshake immediately after streams are ready
                sendHandshake(out);

                notifyNodeConnected(mac);

                // Read loop — blocks until a message arrives or connection drops
                while (!isInterrupted()) {
                    Object received = in.readObject();
                    if (received instanceof Message) {
                        handleIncomingMessage((Message) received, mac);
                    }
                }
            } catch (IOException e) {
                Log.d(TAG, "Connection lost: " + mac);
            } catch (ClassNotFoundException e) {
                Log.e(TAG, "Unknown object from: " + mac);
            } finally {
                if (!mac.isEmpty()) notifyNodeDisconnected(mac);
            }
        }
    }
}
