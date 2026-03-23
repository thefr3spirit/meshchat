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
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
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
 * ============================================================================
 * MeshManager - The Heart of MeshChat's Peer-to-Peer Network
 * ============================================================================
 *
 * This class manages all mesh networking operations. Think of it as a
 * "traffic controller" that handles:
 *
 *  1. PEER DISCOVERY   → Finding nearby devices (WiFi Direct + Bluetooth)
 *  2. CONNECTIONS       → Establishing and maintaining links to peers
 *  3. MESSAGE ROUTING   → Sending, receiving, and forwarding messages
 *  4. ENCRYPTION        → Securing messages with AES-256-GCM encryption
 *  5. OFFLINE QUEUING   → Storing messages when no peers are connected
 *
 * ═══════════════════════════════════════════════════════════════════════
 * HOW MESH NETWORKING WORKS (for beginners):
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Imagine you're in a basement or disaster zone with NO cell service.
 * Your phone can still use Bluetooth (~10m range) and WiFi Direct (~60m)
 * to talk directly to nearby phones WITHOUT a cell tower or WiFi router.
 *
 * Each phone becomes a "node" in the mesh network. When you send a message:
 *
 *   Step 1: Your phone sends the message to all directly connected phones
 *   Step 2: Those phones forward it to THEIR connected phones
 *   Step 3: This continues until the message has "hopped" 10 times (MAX_HOPS)
 *
 * Example — 3 phones in a chain:
 *   ┌─────────┐    Bluetooth    ┌─────────┐    WiFi Direct    ┌─────────┐
 *   │ Phone A │ ──────────────> │ Phone B │ ─────────────────> │ Phone C │
 *   │  (You)  │                 │ (Relay) │                    │ (Friend)│
 *   └─────────┘                 └─────────┘                    └─────────┘
 *      hop 0                       hop 1                          hop 2
 *
 *   Phone A sends → Phone B receives & forwards → Phone C receives.
 *   Even though A can't directly reach C, the message arrives!
 *
 * DUAL TRANSPORT:
 * ───────────────
 * We use BOTH Bluetooth AND WiFi Direct simultaneously:
 *
 *  • BLUETOOTH: Short range (~10m), low power, great for close proximity.
 *    Uses RFCOMM sockets (like a serial port over wireless).
 *    Devices identified by MAC address (e.g., "AA:BB:CC:DD:EE:FF").
 *
 *  • WIFI DIRECT: Longer range (~60m), higher bandwidth.
 *    Devices connect peer-to-peer WITHOUT needing a WiFi router.
 *    One device becomes "Group Owner" (acts like a temporary access point).
 *
 * MESSAGE DEDUPLICATION:
 * ─────────────────────
 * In a mesh, the same message might arrive through multiple paths:
 *
 *              ┌─── Phone B ───┐
 *   Phone A ──>│               │──> Phone D (receives message TWICE!)
 *              └─── Phone C ───┘
 *
 * We solve this by tracking message IDs in an LRU cache. Each message has
 * a unique UUID. If we've already seen that UUID, we ignore the duplicate.
 *
 * ============================================================================
 */
public class MeshManager implements WifiP2pManager.ConnectionInfoListener {
    private static final String TAG = "MeshManager";

    // ─── Constants ──────────────────────────────────────────────────────

    /** Unique identifier for the MeshChat Bluetooth service.
     *  All MeshChat devices use this same UUID to find each other. */
    private static final UUID MY_UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66");

    /** Human-readable service name shown during Bluetooth discovery */
    private static final String SERVICE_NAME = "MeshChat";

    /** TCP port for WiFi Direct communication (both server and client use this) */
    private static final int WIFI_PORT = 8888;

    /**
     * Maximum number of message IDs to remember for deduplication.
     * Uses an LRU (Least Recently Used) cache so old IDs are automatically
     * evicted, preventing unbounded memory growth over long sessions.
     * 10,000 messages ≈ 400KB of memory (just UUID strings).
     */
    private static final int MAX_CACHED_MESSAGE_IDS = 10000;

    /** How often (in seconds) to automatically scan for new mesh peers */
    private static final int DISCOVERY_INTERVAL_SECONDS = 30;

    /** Default encryption passphrase — all mesh devices must share this */
    private static final String DEFAULT_PASSPHRASE = "MeshChat";

    // ─── Core Components ────────────────────────────────────────────────

    /** Android application context for accessing system services */
    private final Context context;

    /** WiFi P2P (WiFi Direct) system service manager */
    private WifiP2pManager wifiP2pManager;

    /** Communication channel with the WiFi P2P framework */
    private WifiP2pManager.Channel channel;

    /** System Bluetooth adapter for managing BT connections */
    private BluetoothAdapter bluetoothAdapter;

    /** Handles AES-256 encryption/decryption of message content */
    private final CryptoManager cryptoManager;

    // ─── Connection Tracking ────────────────────────────────────────────
    // We use ConcurrentHashMap-based collections because multiple threads
    // (Bluetooth thread, WiFi thread, main thread) access these simultaneously.
    // ConcurrentHashMap is thread-safe — it won't crash or corrupt data when
    // multiple threads read/write at the same time.

    /** Set of currently connected node IDs (MAC addresses or IP addresses) */
    private final Set<String> connectedNodes = ConcurrentHashMap.newKeySet();

    /**
     * LRU (Least Recently Used) cache of processed message IDs.
     *
     * Thread-safe via Collections.synchronizedMap + synchronizedSet wrappers.
     * When the cache exceeds MAX_CACHED_MESSAGE_IDS, the oldest entries
     * are automatically removed, preventing memory from growing forever.
     *
     * WHY LRU? In a long-running mesh session, you might process thousands
     * of messages. Without a limit, the set would consume ever more memory.
     * LRU keeps only the most recent IDs — old messages won't be re-received.
     */
    private final Set<String> processedMessages = Collections.newSetFromMap(
            Collections.synchronizedMap(
                    new LinkedHashMap<String, Boolean>(MAX_CACHED_MESSAGE_IDS + 1, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                            return size() > MAX_CACHED_MESSAGE_IDS;
                        }
                    }
            )
    );

    // ─── Stream Management ──────────────────────────────────────────────
    // ObjectOutputStream is used to send serialized Message objects over the network.
    // We keep persistent streams open for each connection to avoid the overhead
    // of creating new streams (and re-sending the stream header) for every message.
    //
    // IMPORTANT: ObjectOutputStream writes a "stream header" when created.
    // The corresponding ObjectInputStream expects to read that header exactly once.
    // Creating a new ObjectOutputStream per message would cause the reader to
    // see unexpected duplicate headers → StreamCorruptedException!

    /** Output streams for Bluetooth connections, keyed by device MAC address */
    private final Map<String, ObjectOutputStream> bluetoothOutputStreams = new ConcurrentHashMap<>();

    /** Output streams for WiFi connections, keyed by IP address */
    private final Map<String, ObjectOutputStream> wifiOutputStreams = new ConcurrentHashMap<>();

    /** Active Bluetooth socket connections, keyed by device MAC address */
    private final Map<String, BluetoothSocket> bluetoothSockets = new ConcurrentHashMap<>();

    /** Active WiFi socket connections, keyed by IP address */
    private final Map<String, Socket> wifiSockets = new ConcurrentHashMap<>();

    // ─── Discovered Peers (for the scan UI) ─────────────────────────────
    /** Discovered Bluetooth devices that have "MeshChat" in their name */
    private final Map<String, PeerAdapter.PeerInfo> discoveredPeers = new ConcurrentHashMap<>();

    // ─── Visibility Control ─────────────────────────────────────────────
    /** Whether this device is visible (discoverable) to other mesh nodes */
    private volatile boolean visible = true;

    // ─── Username ───────────────────────────────────────────────────────
    /** User-chosen display name, used in messages and BT advertising */
    private String username;

    // ─── Offline Message Queue ──────────────────────────────────────────
    /**
     * Queue for messages that couldn't be sent because no peers were connected.
     *
     * USE CASE: You type messages while alone in a dead zone. When you walk
     * into range of another MeshChat device, all queued messages are
     * automatically delivered — no manual resend needed!
     *
     * ConcurrentLinkedQueue is thread-safe and non-blocking, meaning:
     * - The UI thread can add messages without waiting
     * - The network thread can drain messages without blocking the UI
     */
    private final ConcurrentLinkedQueue<Message> offlineMessageQueue = new ConcurrentLinkedQueue<>();

    // ─── Callbacks and Threading ────────────────────────────────────────

    /** Listener that receives callbacks for messages, connections, etc. */
    private MessageListener messageListener;

    /**
     * Handler for posting callbacks to the main (UI) thread.
     * Android requires all UI updates to happen on the main thread.
     * If you try to update a TextView from a background thread, the app crashes.
     */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Reference to the WiFi server thread for cleanup */
    private WifiServerThread wifiServerThread;

    /** Reference to the Bluetooth server thread for cleanup */
    private BluetoothServerThread bluetoothServerThread;

    /**
     * Scheduler for periodic peer discovery.
     * Runs startDiscovery() every DISCOVERY_INTERVAL_SECONDS so we
     * continuously find new devices joining the mesh.
     */
    private ScheduledExecutorService discoveryScheduler;

    // ═══════════════════════════════════════════════════════════════════
    // CALLBACK INTERFACE
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Interface for receiving mesh network events.
     *
     * The Activity or Service implements this to react when:
     * - A new message arrives from another device
     * - A new device connects or disconnects
     * - Queued offline messages are delivered
     */
    public interface MessageListener {
        /** Called when a message is received from another device in the mesh */
        void onMessageReceived(Message message);

        /** Called when a new device joins the mesh network */
        void onNodeConnected(String nodeId);

        /** Called when a device leaves the mesh network */
        void onNodeDisconnected(String nodeId);

        /** Called when offline queued messages are flushed to a newly connected peer */
        void onQueueFlushed(int messageCount);

        /** Called when a new peer is discovered during scanning */
        void onPeerDiscovered(List<PeerAdapter.PeerInfo> peers);

        /** Called when scan completes and no new devices were found */
        void onScanComplete(int peerCount);
    }

    // ═══════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Creates a new MeshManager and initializes all networking components.
     *
     * @param context The Android context (usually the MeshService)
     */
    public MeshManager(Context context) {
        this.context = context;
        // Load username from SharedPreferences
        SharedPreferences prefs = context.getSharedPreferences(
                RegistrationActivity.PREFS_NAME, Context.MODE_PRIVATE);
        this.username = prefs.getString(RegistrationActivity.KEY_USERNAME, Build.MODEL);
        // Initialize encryption with the default mesh passphrase
        this.cryptoManager = new CryptoManager(DEFAULT_PASSPHRASE);
        initialize();
    }

    /**
     * Sets up WiFi Direct, Bluetooth, broadcast receivers, and periodic discovery.
     * Called once during construction.
     */
    private void initialize() {
        // ── Step 1: Initialize WiFi Direct ──
        // WiFi P2P (Peer-to-Peer) allows devices to connect WITHOUT a WiFi router
        wifiP2pManager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        if (wifiP2pManager != null) {
            // Create a "channel" — our communication link with the WiFi P2P framework
            channel = wifiP2pManager.initialize(context, Looper.getMainLooper(), null);
            if (channel != null) {
                // Start listening for incoming WiFi Direct connections
                wifiServerThread = new WifiServerThread();
                wifiServerThread.start();
            }
        }

        // ── Step 2: Initialize Bluetooth ──
        // Returns null if the device has no Bluetooth hardware
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter != null) {
            setupBluetooth();
        }

        // ── Step 3: Register broadcast receivers ──
        // Listen for system events like "new device found"
        registerReceivers();

        // ── Step 4: Start periodic discovery ──
        // Automatically scan for new devices every DISCOVERY_INTERVAL_SECONDS
        startPeriodicDiscovery();
    }

    // ═══════════════════════════════════════════════════════════════════
    // PERMISSION HELPERS
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Checks if a specific Android runtime permission has been granted.
     * Android 6.0+ requires apps to ask the user for "dangerous" permissions.
     *
     * @param permission The permission string (e.g., Manifest.permission.BLUETOOTH_CONNECT)
     * @return true if granted, false otherwise
     */
    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
    }

    // ═══════════════════════════════════════════════════════════════════
    // BLUETOOTH SETUP
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Configures the Bluetooth adapter for mesh networking.
     * Sets our device name to "MeshChat_<model>" so other MeshChat devices
     * can recognize us during discovery (e.g., "MeshChat_Pixel 8").
     */
    private void setupBluetooth() {
        try {
            // Android 12+ (API 31) requires BLUETOOTH_CONNECT for name changes
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                bluetoothAdapter.setName("MeshChat_" + username);
            }
            // Start the Bluetooth server to accept incoming connections
            bluetoothServerThread = new BluetoothServerThread();
            bluetoothServerThread.start();
        } catch (SecurityException e) {
            Log.e(TAG, "Bluetooth setup failed — permission denied: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // BROADCAST RECEIVERS
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Registers broadcast receivers to listen for WiFi P2P and Bluetooth events.
     *
     * BROADCAST RECEIVERS explained (for beginners):
     * ──────────────────────────────────────────────
     * Android uses a "publish-subscribe" pattern for system events.
     * When something happens (e.g., a Bluetooth device is found), the system
     * "broadcasts" an Intent. Our BroadcastReceivers "catch" these broadcasts
     * and call our code in response.
     *
     * It's like subscribing to push notifications — you tell Android
     * "let me know when X happens" and it calls your onReceive() method.
     */
    private void registerReceivers() {
        // ── WiFi Direct events ──
        IntentFilter wifiFilter = new IntentFilter();
        wifiFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);     // WiFi P2P on/off
        wifiFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);     // Peer list changed
        wifiFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION); // Connection changed
        context.registerReceiver(wifiP2pReceiver, wifiFilter);

        // ── Bluetooth events ──
        IntentFilter btFilter = new IntentFilter();
        btFilter.addAction(BluetoothDevice.ACTION_FOUND); // A new BT device was discovered
        context.registerReceiver(bluetoothReceiver, btFilter);
    }

    /**
     * Receives WiFi P2P (WiFi Direct) system broadcasts.
     *
     * WIFI_P2P_PEERS_CHANGED_ACTION → The peer list updated; request it
     * WIFI_P2P_CONNECTION_CHANGED_ACTION → Connection status changed; check if connected
     */
    private final BroadcastReceiver wifiP2pReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                // Nearby WiFi Direct peer list changed — refresh it
                requestPeers();
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                // Connection state changed — check if we're now connected
                NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                if (networkInfo != null && networkInfo.isConnected()) {
                    // Connected! Get connection details (group owner IP, etc.)
                    wifiP2pManager.requestConnectionInfo(channel, MeshManager.this);
                }
            }
        }
    };

    /**
     * Called when WiFi Direct connection info is available.
     *
     * In WiFi Direct, one device becomes the "Group Owner" (GO) — like a
     * temporary WiFi access point. The other device connects to the GO.
     *
     * If we're NOT the group owner → we connect to the GO's server socket.
     * If we ARE the group owner → the other device connects to our WifiServerThread.
     */
    @Override
    public void onConnectionInfoAvailable(WifiP2pInfo info) {
        if (info.groupFormed && !info.isGroupOwner && info.groupOwnerAddress != null) {
            // We're the client — initiate TCP connection to the group owner
            new WifiClientThread(info.groupOwnerAddress).start();
        }
        // If we're the group owner, our WifiServerThread handles incoming connections
    }

    /**
     * Receives Bluetooth discovery broadcasts.
     * When a nearby Bluetooth device is found, we check if it's a MeshChat
     * device (name contains "MeshChat") and connect if we're not already linked.
     */
    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    try {
                        String name = device.getName();
                        String address = device.getAddress();
                        // Track all MeshChat devices for the peer list UI
                        if (name != null && name.contains("MeshChat")) {
                            boolean alreadyConnected = connectedNodes.contains(address);
                            discoveredPeers.put(address,
                                    new PeerAdapter.PeerInfo(name, address, alreadyConnected, "bluetooth"));
                            notifyPeersUpdated();
                        }
                    } catch (SecurityException e) {
                        Log.w(TAG, "Missing Bluetooth permission for device name check");
                    }
                }
            }
        }
    };

    // ═══════════════════════════════════════════════════════════════════
    // PEER DISCOVERY
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Requests the current list of available WiFi Direct peers.
     * When results arrive, we attempt to connect to the first available device.
     *
     * NOTE: We connect one at a time to prevent WiFi Direct conflicts.
     * Once connected, that device can bridge us to the rest of the mesh.
     */
    private void requestPeers() {
        if (wifiP2pManager != null && channel != null) {
            try {
                wifiP2pManager.requestPeers(channel, peers -> {
                    for (WifiP2pDevice device : peers.getDeviceList()) {
                        String address = device.deviceAddress;
                        boolean alreadyConnected = connectedNodes.contains(address);
                        discoveredPeers.put(address,
                                new PeerAdapter.PeerInfo(device.deviceName, address, alreadyConnected, "wifi"));
                    }
                    notifyPeersUpdated();
                });
            } catch (SecurityException e) {
                Log.w(TAG, "Missing WiFi P2P permission for peer request");
            }
        }
    }

    /**
     * Initiates a WiFi Direct connection to a discovered device.
     *
     * @param device The WiFi P2P device to connect to
     */
    private void connectToWifiP2pDevice(WifiP2pDevice device) {
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        try {
            wifiP2pManager.connect(channel, config, null);
        } catch (SecurityException e) {
            Log.w(TAG, "Missing WiFi P2P permission for connection");
        }
    }

    /**
     * Starts scanning for nearby devices on both WiFi Direct and Bluetooth.
     * Called by the UI "Scan" button and by the periodic discovery timer.
     */
    public void startDiscovery() {
        // Clear old discovered peers on each fresh scan
        discoveredPeers.clear();

        try {
            if (wifiP2pManager != null && channel != null) {
                wifiP2pManager.discoverPeers(channel, null);
            }
            // Only start BT discovery if not already scanning
            if (bluetoothAdapter != null && !bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.startDiscovery();
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Missing permission for discovery: " + e.getMessage());
        }

        // After a delay, notify UI of scan completion
        mainHandler.postDelayed(() -> {
            if (messageListener != null) {
                messageListener.onScanComplete(discoveredPeers.size());
            }
        }, 12000); // BT discovery typically takes ~12 seconds
    }

    /**
     * Starts a repeating timer that scans for peers every DISCOVERY_INTERVAL_SECONDS.
     * This ensures we keep finding new devices even without the user pressing "Scan".
     *
     * ScheduledExecutorService works like a repeating alarm — it runs our code
     * on a background thread at regular intervals.
     */
    private void startPeriodicDiscovery() {
        discoveryScheduler = Executors.newSingleThreadScheduledExecutor();
        discoveryScheduler.scheduleAtFixedRate(
                this::startDiscovery,             // What to run
                DISCOVERY_INTERVAL_SECONDS,       // Initial delay
                DISCOVERY_INTERVAL_SECONDS,       // Repeat interval
                TimeUnit.SECONDS                  // Time unit
        );
    }

    // ═══════════════════════════════════════════════════════════════════
    // MESSAGE SENDING
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Broadcasts a message to all connected peers in the mesh.
     * This is the main method called when the user sends a new message.
     *
     * Message flow:
     *   1. Add message ID to "seen" set (prevents our own message from echoing back)
     *   2. Create an encrypted COPY for network transmission
     *   3. If peers connected → send immediately
     *   4. If no peers       → queue for later delivery
     *
     * BUG FIX: Previously this method took a String and created a NEW Message
     * internally. That meant the UI message and network message had DIFFERENT
     * UUIDs, breaking deduplication. Now we accept the same Message object
     * that the UI displays, ensuring consistent IDs.
     *
     * @param message The Message object to broadcast (same one shown in UI)
     */
    public void broadcastMessage(Message message) {
        // Mark this message as "already seen" so we ignore it if it echoes back
        processedMessages.add(message.getId());

        // Create an encrypted COPY for network transmission.
        // We copy because we don't want to modify the original Message
        // that the UI is displaying (it should show readable text, not gibberish)
        Message networkCopy = message.copy();
        networkCopy.setContent(cryptoManager.encrypt(message.getContent()));
        networkCopy.setEncrypted(true);

        // Check if we have any peers to send to
        if (bluetoothOutputStreams.isEmpty() && wifiOutputStreams.isEmpty()) {
            // No peers connected — queue the encrypted message for later
            offlineMessageQueue.add(networkCopy);
            Log.d(TAG, "No peers connected. Message queued (queue size: "
                    + offlineMessageQueue.size() + ")");
        } else {
            // Peers are connected! Send to all of them
            sendToAllPeers(networkCopy);
        }
    }

    /**
     * Sends a message to ALL currently connected peers via both Bluetooth and WiFi.
     *
     * THREAD SAFETY NOTE:
     * ObjectOutputStream is NOT thread-safe. If two threads write to the same
     * stream simultaneously, the data stream gets corrupted. We synchronize
     * on each individual stream to prevent this.
     *
     * @param message The (encrypted) message to send over the network
     */
    private void sendToAllPeers(Message message) {
        // ── Send via all Bluetooth connections ──
        for (Map.Entry<String, ObjectOutputStream> entry : bluetoothOutputStreams.entrySet()) {
            String nodeId = entry.getKey();
            ObjectOutputStream out = entry.getValue();
            try {
                // Synchronize to prevent concurrent writes corrupting the stream
                synchronized (out) {
                    out.writeObject(message);  // Serialize and send the Message object
                    out.flush();               // Ensure all bytes are pushed out immediately
                    out.reset();               // Clear ObjectOutputStream's internal object cache
                    // reset() is CRUCIAL — without it, OOS caches previously sent objects
                    // and sends references instead of full copies on subsequent writes.
                    // This would cause receivers to see stale/duplicate data.
                }
            } catch (IOException e) {
                Log.w(TAG, "Bluetooth send failed to " + nodeId + ": " + e.getMessage());
                notifyNodeDisconnected(nodeId);
            }
        }

        // ── Send via all WiFi Direct connections ──
        for (Map.Entry<String, ObjectOutputStream> entry : wifiOutputStreams.entrySet()) {
            String nodeId = entry.getKey();
            ObjectOutputStream out = entry.getValue();
            try {
                synchronized (out) {
                    out.writeObject(message);
                    out.flush();
                    out.reset();
                }
            } catch (IOException e) {
                Log.w(TAG, "WiFi send failed to " + nodeId + ": " + e.getMessage());
                notifyNodeDisconnected(nodeId);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // MESSAGE RECEIVING & FORWARDING
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Processes an incoming message from another device in the mesh.
     *
     * Three responsibilities:
     *   1. DEDUPLICATION → Skip messages we've already seen
     *   2. FORWARDING    → Relay the message to other peers (if hop limit allows)
     *   3. DELIVERY      → Decrypt and notify the UI
     *
     * FORWARDING happens BEFORE decryption because:
     * - We forward the encrypted version (more secure — relay nodes
     *   don't need to decrypt just to forward)
     * - The hop count check prevents infinite chains
     *
     * @param message The incoming Message from a connected peer
     */
    private void handleIncomingMessage(Message message) {
        // ── Step 1: Deduplication ──
        // Have we already seen this message? (could arrive via multiple mesh paths)
        if (processedMessages.contains(message.getId())) {
            return; // Already processed — discard this duplicate
        }
        processedMessages.add(message.getId());

        // ── Step 2: Forward to other peers (this is what makes it a MESH!) ──
        // Each node acts as a relay, passing messages further into the network
        if (message.canForward()) {
            message.incrementHopCount();  // Record that we're one more hop along
            sendToAllPeers(message);      // Forward the still-encrypted message
        }

        // ── Step 3: Decrypt and deliver to the UI ──
        if (message.isEncrypted()) {
            String decryptedContent = cryptoManager.decrypt(message.getContent());
            message.setContent(decryptedContent);
            message.setEncrypted(false);
        }

        // Mark as "received" for the UI (shows on the left side of the chat)
        message.setType(Message.TYPE_RECEIVED);

        // Notify the UI on the main thread (Android requires UI updates on main thread)
        if (messageListener != null) {
            mainHandler.post(() -> messageListener.onMessageReceived(message));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // OFFLINE MESSAGE QUEUE
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Sends all queued offline messages to the newly connected peer.
     * Called automatically whenever a new node joins the mesh.
     *
     * USE CASE: You type messages while alone in a dead zone. When someone
     * walks into Bluetooth/WiFi range, all your queued messages are
     * delivered automatically — no manual action needed!
     */
    private void flushOfflineQueue() {
        if (offlineMessageQueue.isEmpty()) return;

        int count = 0;
        Message queuedMsg;
        // poll() retrieves AND removes the head of the queue (thread-safe)
        while ((queuedMsg = offlineMessageQueue.poll()) != null) {
            sendToAllPeers(queuedMsg);
            count++;
        }

        // Notify the UI that queued messages were delivered
        if (messageListener != null && count > 0) {
            int flushedCount = count;
            mainHandler.post(() -> messageListener.onQueueFlushed(flushedCount));
        }
        Log.d(TAG, "Flushed " + count + " offline messages to mesh");
    }

    /**
     * Returns how many messages are waiting in the offline queue.
     * Useful for showing "X messages pending" in the UI.
     */
    public int getQueuedMessageCount() {
        return offlineMessageQueue.size();
    }

    // ═══════════════════════════════════════════════════════════════════
    // NODE CONNECTION MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Called when a new device successfully connects to our mesh.
     * Tracks the node, notifies the UI, and flushes any queued messages.
     *
     * @param nodeId Unique ID of the connected node (MAC or IP address)
     */
    private void notifyNodeConnected(String nodeId) {
        connectedNodes.add(nodeId);

        // Notify UI about the new connection
        if (messageListener != null) {
            mainHandler.post(() -> messageListener.onNodeConnected(nodeId));
        }

        // A new peer is here! Send any messages queued while we were offline
        flushOfflineQueue();
    }

    /**
     * Called when a device disconnects from our mesh.
     * Cleans up ALL resources for that node: streams, sockets, tracking data.
     *
     * @param nodeId Unique ID of the disconnected node
     */
    private void notifyNodeDisconnected(String nodeId) {
        connectedNodes.remove(nodeId);

        // Close and remove output streams (must close explicitly to flush buffers)
        ObjectOutputStream btOut = bluetoothOutputStreams.remove(nodeId);
        if (btOut != null) { try { btOut.close(); } catch (IOException e) { /* OK */ } }

        ObjectOutputStream wifiOut = wifiOutputStreams.remove(nodeId);
        if (wifiOut != null) { try { wifiOut.close(); } catch (IOException e) { /* OK */ } }

        // Close the underlying sockets
        BluetoothSocket btSocket = bluetoothSockets.remove(nodeId);
        if (btSocket != null) { try { btSocket.close(); } catch (IOException e) { /* OK */ } }

        Socket wifiSocket = wifiSockets.remove(nodeId);
        if (wifiSocket != null) { try { wifiSocket.close(); } catch (IOException e) { /* OK */ } }

        // Notify UI about the disconnection
        if (messageListener != null) {
            mainHandler.post(() -> messageListener.onNodeDisconnected(nodeId));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════

    /** Sets the listener that receives mesh network event callbacks */
    public void setMessageListener(MessageListener listener) {
        this.messageListener = listener;
    }

    /** Returns the count of currently connected nodes in the mesh */
    public int getConnectedNodeCount() {
        return connectedNodes.size();
    }

    /** Returns the CryptoManager for changing the encryption passphrase */
    public CryptoManager getCryptoManager() {
        return cryptoManager;
    }

    /** Returns the user's chosen display name */
    public String getUsername() {
        return username;
    }

    /** Returns whether this device is currently visible to others */
    public boolean isVisible() {
        return visible;
    }

    /**
     * Toggles visibility on/off. When hidden, we stop Bluetooth advertising
     * and WiFi Direct group creation so other devices can't discover us.
     * We can still send/receive messages on existing connections.
     */
    public void setVisible(boolean visible) {
        this.visible = visible;
        if (visible) {
            // Re-advertise by resetting BT name and restarting servers if needed
            try {
                if (bluetoothAdapter != null &&
                        (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                                hasPermission(Manifest.permission.BLUETOOTH_CONNECT))) {
                    bluetoothAdapter.setName("MeshChat_" + username);
                }
            } catch (SecurityException e) {
                Log.w(TAG, "Cannot set BT name: " + e.getMessage());
            }
            Log.d(TAG, "Visibility ON — device is discoverable");
        } else {
            // Stop advertising by cancelling discovery and changing BT name
            try {
                if (bluetoothAdapter != null) {
                    bluetoothAdapter.cancelDiscovery();
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                        // Set a non-MeshChat name so others won't try to connect
                        bluetoothAdapter.setName("Android_" + Build.MODEL);
                    }
                }
            } catch (SecurityException e) {
                Log.w(TAG, "Cannot update BT name: " + e.getMessage());
            }
            Log.d(TAG, "Visibility OFF — device is hidden");
        }
    }

    /** Returns the current list of discovered peers */
    public List<PeerAdapter.PeerInfo> getDiscoveredPeers() {
        // Update connected status before returning
        List<PeerAdapter.PeerInfo> peerList = new ArrayList<>();
        for (Map.Entry<String, PeerAdapter.PeerInfo> entry : discoveredPeers.entrySet()) {
            PeerAdapter.PeerInfo p = entry.getValue();
            boolean connected = connectedNodes.contains(p.address);
            peerList.add(new PeerAdapter.PeerInfo(p.name, p.address, connected, p.type));
        }
        return peerList;
    }

    /**
     * Connects to a specific peer by address. Called from the peer list UI
     * when the user taps "Connect" on a discovered device.
     */
    public void connectToPeer(PeerAdapter.PeerInfo peer) {
        if (connectedNodes.contains(peer.address)) {
            Log.d(TAG, "Already connected to " + peer.address);
            return;
        }

        if ("bluetooth".equals(peer.type)) {
            // Look up the BluetoothDevice and connect
            if (bluetoothAdapter != null) {
                try {
                    BluetoothDevice device = bluetoothAdapter.getRemoteDevice(peer.address);
                    new BluetoothClientThread(device).start();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to connect to BT device: " + e.getMessage());
                }
            }
        } else if ("wifi".equals(peer.type)) {
            // WiFi Direct connection
            WifiP2pConfig config = new WifiP2pConfig();
            config.deviceAddress = peer.address;
            try {
                if (wifiP2pManager != null && channel != null) {
                    wifiP2pManager.connect(channel, config, null);
                }
            } catch (SecurityException e) {
                Log.w(TAG, "WiFi P2P connect permission denied: " + e.getMessage());
            }
        }
    }

    /** Notifies the listener about updated peer list */
    private void notifyPeersUpdated() {
        if (messageListener != null) {
            List<PeerAdapter.PeerInfo> peerList = getDiscoveredPeers();
            mainHandler.post(() -> messageListener.onPeerDiscovered(peerList));
        }
    }

    /**
     * Shuts down the mesh manager and releases ALL resources.
     * Called when the foreground service stops or the app is closing.
     *
     * ORDER MATTERS: We stop discovery first, then unregister receivers,
     * then stop server threads, then close streams, then close sockets.
     */
    public void cleanup() {
        // ── Stop periodic discovery scheduler ──
        if (discoveryScheduler != null && !discoveryScheduler.isShutdown()) {
            discoveryScheduler.shutdownNow();
        }

        // ── Unregister broadcast receivers ──
        // try-catch because unregistering an already-unregistered receiver crashes
        try { context.unregisterReceiver(wifiP2pReceiver); } catch (Exception e) { /* OK */ }
        try { context.unregisterReceiver(bluetoothReceiver); } catch (Exception e) { /* OK */ }

        // ── Stop server threads ──
        if (wifiServerThread != null) wifiServerThread.interrupt();
        if (bluetoothServerThread != null) bluetoothServerThread.interrupt();

        // ── Close all output streams ──
        for (ObjectOutputStream out : bluetoothOutputStreams.values()) {
            try { out.close(); } catch (IOException e) { /* OK */ }
        }
        for (ObjectOutputStream out : wifiOutputStreams.values()) {
            try { out.close(); } catch (IOException e) { /* OK */ }
        }

        // ── Close all sockets ──
        for (BluetoothSocket s : bluetoothSockets.values()) {
            try { s.close(); } catch (IOException e) { /* OK */ }
        }
        for (Socket s : wifiSockets.values()) {
            try { s.close(); } catch (IOException e) { /* OK */ }
        }

        Log.d(TAG, "MeshManager cleaned up — all resources released.");
    }

    // ═══════════════════════════════════════════════════════════════════
    // NETWORKING THREADS
    // ═══════════════════════════════════════════════════════════════════
    //
    // WHY THREADS? (for beginners)
    // ────────────────────────────
    // Network operations (connecting, sending, receiving) can take a long
    // time. If we did these on the main (UI) thread, the app would freeze
    // and Android would show "App Not Responding" (ANR) and crash.
    //
    // So we use background threads — they run independently while the UI
    // stays smooth. Think of threads as workers doing heavy lifting in the
    // background while you continue chatting.
    //
    // Thread types:
    // 1. BluetoothServerThread  → Listens for incoming BT connections
    // 2. BluetoothClientThread  → Connects to a discovered BT device
    // 3. WifiServerThread       → Listens for incoming WiFi connections
    // 4. WifiClientThread       → Connects to a WiFi Direct group owner
    // 5. MessageReceiverThread  → Reads messages from ANY connected socket
    //

    /**
     * ─── BluetoothServerThread ─────────────────────────────────────────
     * Listens for incoming Bluetooth connections from other MeshChat devices.
     *
     * How it works (like a restaurant host):
     *   1. Creates a "server socket" — like opening the restaurant doors
     *   2. Calls accept() — waits for a guest to arrive (blocks until one does)
     *   3. When a guest arrives — hands them off to a MessageReceiverThread
     *   4. Goes back to waiting for the next guest
     *   5. Repeats forever until interrupted
     */
    private class BluetoothServerThread extends Thread {
        BluetoothServerThread() {
            setName("BT-Server"); // Name threads for easier debugging in logs
        }

        @Override
        public void run() {
            try (BluetoothServerSocket serverSocket =
                         bluetoothAdapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, MY_UUID)) {
                Log.d(TAG, "Bluetooth server started — waiting for connections...");

                while (!isInterrupted()) {
                    // accept() BLOCKS here until another MeshChat device connects
                    BluetoothSocket socket = serverSocket.accept();
                    String address = socket.getRemoteDevice().getAddress();
                    Log.d(TAG, "Bluetooth connection accepted from: " + address);

                    // Track the socket for cleanup
                    bluetoothSockets.put(address, socket);

                    // Start handling messages on this connection
                    new MessageReceiverThread(socket).start();
                }
            } catch (IOException e) {
                Log.e(TAG, "Bluetooth server error: " + e.getMessage());
            } catch (SecurityException e) {
                Log.e(TAG, "Bluetooth server — permission denied: " + e.getMessage());
            }
        }
    }

    /**
     * ─── BluetoothClientThread ─────────────────────────────────────────
     * Initiates a Bluetooth connection to a discovered MeshChat device.
     *
     * When our bluetoothReceiver finds a device named "MeshChat_*",
     * this thread connects to its Bluetooth server.
     *
     * Uses RFCOMM (Radio Frequency Communication) — a Bluetooth protocol
     * that provides a reliable data stream, like a serial cable but wireless.
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
                // Create a socket targeting the remote device's MeshChat service (UUID)
                BluetoothSocket socket = device.createRfcommSocketToServiceRecord(MY_UUID);
                Log.d(TAG, "Connecting to Bluetooth device: " + device.getAddress());

                socket.connect(); // Blocks until connected or fails

                // Track and start receiving
                bluetoothSockets.put(device.getAddress(), socket);
                new MessageReceiverThread(socket).start();

                Log.d(TAG, "Bluetooth connected to: " + device.getAddress());
            } catch (IOException e) {
                Log.e(TAG, "Bluetooth connection failed: " + e.getMessage());
            } catch (SecurityException e) {
                Log.e(TAG, "Bluetooth client — permission denied: " + e.getMessage());
            }
        }
    }

    /**
     * ─── WifiServerThread ──────────────────────────────────────────────
     * Listens for incoming WiFi Direct TCP connections on WIFI_PORT (8888).
     *
     * When this device is the WiFi Direct "Group Owner" (like a temporary
     * access point), other devices connect to us through this server.
     *
     * Uses standard Java TCP sockets — the same networking code used
     * for web servers, game servers, etc.
     */
    private class WifiServerThread extends Thread {
        WifiServerThread() {
            setName("WiFi-Server");
        }

        @Override
        public void run() {
            try (ServerSocket serverSocket = new ServerSocket(WIFI_PORT)) {
                serverSocket.setReuseAddress(true); // Allow quick restart if port was recently used
                Log.d(TAG, "WiFi server started on port " + WIFI_PORT);

                while (!isInterrupted()) {
                    // Wait for a WiFi Direct client to connect
                    Socket socket = serverSocket.accept();
                    String address = socket.getInetAddress().getHostAddress();
                    Log.d(TAG, "WiFi client connected: " + address);

                    // Track and handle
                    wifiSockets.put(address, socket);
                    new MessageReceiverThread(socket).start();
                }
            } catch (IOException e) {
                if (!isInterrupted()) {
                    Log.e(TAG, "WiFi server error: " + e.getMessage());
                }
            }
        }
    }

    /**
     * ─── WifiClientThread ──────────────────────────────────────────────
     * Connects to the WiFi Direct Group Owner's TCP server.
     *
     * When we join a WiFi Direct group and we're NOT the group owner,
     * we connect to the owner's IP address to exchange messages.
     */
    private class WifiClientThread extends Thread {
        private final InetAddress host;

        WifiClientThread(InetAddress host) {
            this.host = host;
            setName("WiFi-Client-" + host.getHostAddress());
        }

        @Override
        public void run() {
            try {
                Socket socket = new Socket(host, WIFI_PORT);
                String address = host.getHostAddress();
                Log.d(TAG, "WiFi connected to group owner: " + address);

                wifiSockets.put(address, socket);
                new MessageReceiverThread(socket).start();
            } catch (IOException e) {
                Log.e(TAG, "WiFi client connection failed: " + e.getMessage());
            }
        }
    }

    /**
     * ─── MessageReceiverThread ─────────────────────────────────────────
     * Handles communication on an established connection (Bluetooth OR WiFi).
     * Sets up serialization streams and continuously reads incoming messages.
     *
     * ★ CRITICAL: STREAM INITIALIZATION ORDER MATTERS! ★
     * ──────────────────────────────────────────────────
     * ObjectOutputStream MUST be created and flushed BEFORE ObjectInputStream.
     *
     * Why? ObjectOutputStream's constructor writes a "stream header" to the
     * output. ObjectInputStream's constructor tries to READ a header from
     * the input. If both sides create ObjectInputStream first, they'll BOTH
     * block waiting for a header that never comes → DEADLOCK!
     *
     * Correct order (both devices follow this):
     *   1. new ObjectOutputStream(...)  → writes our header to the output
     *   2. out.flush()                  → pushes the header to the other side
     *   3. new ObjectInputStream(...)   → reads the OTHER side's header
     *
     * Since both sides create the output stream first and flush, each side
     * sends its header before trying to read the other's. No deadlock!
     */
    private class MessageReceiverThread extends Thread {
        private final Object socket; // Can be BluetoothSocket or Socket (WiFi)

        MessageReceiverThread(Object socket) {
            this.socket = socket;
            setName("MsgReceiver-" + socket.hashCode());
        }

        @Override
        public void run() {
            String nodeId = "";
            try {
                ObjectOutputStream out;
                ObjectInputStream in;

                if (socket instanceof BluetoothSocket) {
                    // ── Bluetooth connection setup ──
                    BluetoothSocket btSocket = (BluetoothSocket) socket;
                    nodeId = btSocket.getRemoteDevice().getAddress();

                    // MUST: Create output stream FIRST and flush (see deadlock note above)
                    out = new ObjectOutputStream(btSocket.getOutputStream());
                    out.flush();
                    bluetoothOutputStreams.put(nodeId, out);

                    // Now safe to create input stream (the other side already sent their header)
                    in = new ObjectInputStream(btSocket.getInputStream());
                } else {
                    // ── WiFi connection setup ──
                    Socket wifiSocket = (Socket) socket;
                    nodeId = wifiSocket.getInetAddress().getHostAddress();

                    out = new ObjectOutputStream(wifiSocket.getOutputStream());
                    out.flush();
                    wifiOutputStreams.put(nodeId, out);

                    in = new ObjectInputStream(wifiSocket.getInputStream());
                }

                // Connection fully established — notify UI and flush offline queue
                notifyNodeConnected(nodeId);

                // ── Message receiving loop ──
                // Continuously read Message objects from the stream.
                // readObject() blocks until a message arrives or the connection drops.
                while (!isInterrupted()) {
                    Object received = in.readObject();
                    if (received instanceof Message) {
                        handleIncomingMessage((Message) received);
                    }
                }
            } catch (IOException e) {
                // Connection broken (device out of range, app closed, etc.)
                Log.d(TAG, "Connection lost with node: " + nodeId);
                if (!nodeId.isEmpty()) {
                    notifyNodeDisconnected(nodeId);
                }
            } catch (ClassNotFoundException e) {
                // Received an object we don't recognize (version mismatch?)
                Log.e(TAG, "Unknown object type from node: " + nodeId);
                if (!nodeId.isEmpty()) {
                    notifyNodeDisconnected(nodeId);
                }
            }
        }
    }
}