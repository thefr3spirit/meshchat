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
import android.net.wifi.p2p.WifiP2pDeviceList;
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
import java.net.InetSocketAddress;
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
 * MeshManager — Bluetooth + WiFi Direct mesh networking engine.
 *
 * Responsibilities:
 *  1. PEER DISCOVERY    — Scans for Bluetooth devices and WiFi Direct peers
 *  2. CONNECTIONS       — RFCOMM (Bluetooth) + TCP (WiFi Direct) connections
 *  3. MESSAGE ROUTING   — Broadcast and private (addressed) message routing
 *  4. ENCRYPTION        — AES-256-GCM via CryptoManager
 *  5. OFFLINE QUEUING   — Per-recipient queues, flushed on reconnect
 *  6. IDENTITY          — Handshake protocol to exchange usernames and UUIDs
 *  7. DISTANCE CONTROL  — RSSI threshold determines whether to auto-connect
 *  8. WIFI DIRECT       — Group Owner discovery and group formation
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
 *
 * WIFI DIRECT GROUP OWNER (GO) ASYMMETRY:
 * ────────────────────────────────────────
 * WiFi Direct uses WifiP2pManager to discover peers and form groups.
 * One device is elected Group Owner (GO) — it acts as a soft access point.
 * All other peers connect to the GO via TCP sockets.
 *
 * KEY ASYMMETRY: Non-GO peers CANNOT communicate directly with each other.
 * They must route all messages through the GO. The GO acts as a relay hub:
 *   - When the GO receives a message from peer A, it forwards to all other
 *     WiFi Direct peers (B, C, …) and also to any Bluetooth-connected nodes.
 *   - When peer A wants to reach peer B, the path is: A → GO → B.
 *
 * This is handled transparently by sendToAllPeers(), which iterates both
 * Bluetooth and WiFi Direct output streams. The deduplication layer
 * (processedMessages) prevents the same message from being processed twice
 * even if it arrives via both transports.
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

    // ─── Heartbeat & stale peer eviction ────────────────────────────────

    /** Heartbeat broadcast interval in seconds */
    private static final int HEARTBEAT_INTERVAL_SEC = 20;

    /**
     * If no heartbeat (or any message) is received from a directly-connected
     * peer within this many milliseconds, the peer is considered stale and
     * is automatically disconnected.
     */
    private static final long STALE_PEER_TIMEOUT_MS = 90_000; // 90 seconds

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

    // ─── WiFi Direct ────────────────────────────────────────────────────

    private WifiP2pManager wifiP2pManager;
    private WifiP2pManager.Channel wifiP2pChannel;

    /**
     * Whether this device is the WiFi Direct Group Owner (GO).
     * The GO acts as a soft AP — all peers connect to it via TCP.
     * Non-GO peers cannot communicate directly with each other;
     * they must route through the GO.
     */
    private volatile boolean isGroupOwner = false;

    /** IP address of the Group Owner (non-null after group formation) */
    private volatile InetAddress groupOwnerAddress;

    /** TCP server thread — runs only on the Group Owner */
    private WifiDirectServerThread wifiDirectServerThread;

    /** Port used for WiFi Direct TCP connections */
    private static final int WIFI_DIRECT_PORT = 8988;

    /** WiFi Direct enabled flag (updated by P2P state broadcasts) */
    private volatile boolean wifiP2pEnabled = false;

    /** Output streams for WiFi Direct peers (keyed by IP address string) */
    private final Map<String, ObjectOutputStream> wifiDirectOutputStreams =
            new ConcurrentHashMap<>();

    /** Active WiFi Direct TCP sockets (keyed by IP address string) */
    private final Map<String, Socket> wifiDirectSockets = new ConcurrentHashMap<>();

    /** IP addresses of peers connected via WiFi Direct */
    private final Set<String> wifiDirectConnectedNodes = ConcurrentHashMap.newKeySet();

    /** Discovered WiFi Direct devices (device address → WifiP2pDevice) */
    private final Map<String, WifiP2pDevice> discoveredWifiDirectDevices =
            new ConcurrentHashMap<>();

    // ─── Connection tracking ────────────────────────────────────────────

    /** BT MAC addresses of currently connected peers */
    private final Set<String> connectedNodes = ConcurrentHashMap.newKeySet();

    /**
     * BT MAC addresses for which a BluetoothClientThread is currently in progress.
     * Guards against launching duplicate outbound connections to the same device
     * during overlapping scan cycles or before the handshake has completed.
     */
    private final Set<String> connectingNodes = ConcurrentHashMap.newKeySet();

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

    // ─── Heartbeat & liveness tracking ──────────────────────────────────

    /** Scheduler for periodic heartbeat broadcasts and stale peer checks */
    private ScheduledExecutorService heartbeatScheduler;

    /**
     * Tracks when we last received ANY message (including heartbeats) from
     * each directly-connected peer.  Key = address (BT MAC or WiFi Direct IP).
     */
    private final Map<String, Long> lastSeenTimestamps = new ConcurrentHashMap<>();

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
        initializeWifiDirect();
        registerReceivers();
        startPeriodicDiscovery();
        startHeartbeatScheduler();
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

    // ─── WiFi Direct setup ──────────────────────────────────────────────

    /**
     * Initialises the WiFi Direct (P2P) subsystem.
     *
     * WiFi Direct allows devices to connect without a traditional WiFi network.
     * One device is elected as the Group Owner (GO), which acts as a soft AP.
     * All other peers connect to the GO via TCP sockets.
     *
     * Key APIs used:
     *   - WifiP2pManager: manages WiFi P2P connections
     *   - WifiP2pManager.Channel: communication channel with the framework
     */
    private void initializeWifiDirect() {
        wifiP2pManager = (WifiP2pManager)
                context.getSystemService(Context.WIFI_P2P_SERVICE);
        if (wifiP2pManager != null) {
            wifiP2pChannel = wifiP2pManager.initialize(
                    context, Looper.getMainLooper(), null);
            Log.d(TAG, "WiFi Direct initialised");
        } else {
            Log.w(TAG, "WiFi Direct not supported on this device");
        }
    }

    // ─── Broadcast receivers ────────────────────────────────────────────

    private void registerReceivers() {
        IntentFilter btFilter = new IntentFilter();
        btFilter.addAction(BluetoothDevice.ACTION_FOUND);
        context.registerReceiver(bluetoothReceiver, btFilter);

        // WiFi Direct events
        if (wifiP2pManager != null) {
            IntentFilter wdFilter = new IntentFilter();
            wdFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
            wdFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
            wdFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
            wdFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
            context.registerReceiver(wifiDirectReceiver, wdFilter);
        }
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

                // Auto-connect if we're in the same network, signal is strong enough,
                // and we're not already connected or in the middle of connecting.
                boolean alreadyConnecting = connectingNodes.contains(address);
                if (currentNetworkName != null
                        && networkName.equals(currentNetworkName)
                        && !alreadyConnected
                        && !alreadyConnecting
                        && rssi >= rssiThreshold) {
                    new BluetoothClientThread(device).start();
                }
            } catch (SecurityException e) {
                Log.w(TAG, "BT receiver permission error: " + e.getMessage());
            }
        }
    };

    // ─── WiFi Direct receiver ───────────────────────────────────────────

    /**
     * Handles WiFi Direct system events.
     *
     * WIFI_P2P_STATE_CHANGED_ACTION
     *   → Tells us if WiFi P2P is enabled or disabled on the device.
     *
     * WIFI_P2P_PEERS_CHANGED_ACTION
     *   → Fired after discoverPeers() finds results.
     *     We call requestPeers() to get the actual WifiP2pDeviceList.
     *
     * WIFI_P2P_CONNECTION_CHANGED_ACTION
     *   → Fired when a WiFi Direct group is formed or torn down.
     *     We call requestConnectionInfo() to learn if we are the Group Owner
     *     (GO) or a peer, and get the GO's IP address.
     *
     *     GROUP OWNER ASYMMETRY:
     *       - GO starts a TCP ServerSocket to accept peer connections.
     *       - Non-GO peers connect a TCP Socket to the GO's IP.
     *       - Non-GO peers can only reach each other through the GO.
     *
     * WIFI_P2P_THIS_DEVICE_CHANGED_ACTION
     *   → Fired when this device's P2P details change (name, status, etc.).
     */
    private final BroadcastReceiver wifiDirectReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            switch (action) {
                case WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION: {
                    int state = intent.getIntExtra(
                            WifiP2pManager.EXTRA_WIFI_STATE, -1);
                    wifiP2pEnabled =
                            (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED);
                    Log.d(TAG, "WiFi P2P enabled: " + wifiP2pEnabled);
                    break;
                }

                case WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION: {
                    // Peer list updated — request the current list
                    if (wifiP2pManager == null || wifiP2pChannel == null) break;
                    try {
                        wifiP2pManager.requestPeers(wifiP2pChannel,
                                MeshManager.this::onWifiDirectPeersAvailable);
                    } catch (SecurityException e) {
                        Log.w(TAG, "requestPeers permission denied: "
                                + e.getMessage());
                    }
                    break;
                }

                case WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION: {
                    if (wifiP2pManager == null || wifiP2pChannel == null) break;
                    NetworkInfo networkInfo = intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_NETWORK_INFO);
                    if (networkInfo != null && networkInfo.isConnected()) {
                        // Group formed — find out if we are the GO or a peer
                        wifiP2pManager.requestConnectionInfo(wifiP2pChannel,
                                MeshManager.this::onWifiDirectConnectionInfoAvailable);
                    } else {
                        // Group lost — clean up WiFi Direct connections
                        Log.d(TAG, "WiFi Direct group lost");
                        handleWifiDirectGroupLost();
                    }
                    break;
                }

                case WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION: {
                    WifiP2pDevice device = intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                    if (device != null) {
                        Log.d(TAG, "This device P2P status: "
                                + deviceStatusToString(device.status));
                    }
                    break;
                }
            }
        }
    };

    /**
     * Callback for WifiP2pManager.requestPeers().
     * Populates discoveredWifiDirectDevices with nearby WiFi Direct devices.
     */
    private void onWifiDirectPeersAvailable(WifiP2pDeviceList peerList) {
        discoveredWifiDirectDevices.clear();
        for (WifiP2pDevice device : peerList.getDeviceList()) {
            discoveredWifiDirectDevices.put(device.deviceAddress, device);
            Log.d(TAG, "WiFi Direct peer: " + device.deviceName
                    + " (" + device.deviceAddress + ") status="
                    + deviceStatusToString(device.status));
        }
        // Notify UI so it can show WiFi Direct peers alongside BT peers
        notifyNodeInfoUpdated();
    }

    /**
     * Callback for WifiP2pManager.requestConnectionInfo().
     *
     * Called after a WiFi Direct group is formed. Determines our role:
     *   - Group Owner (GO): starts a TCP ServerSocket on WIFI_DIRECT_PORT.
     *     All peers connect to us. We relay messages between them.
     *   - Peer (non-GO): connects a TCP Socket to the GO's IP address.
     *     All our WiFi Direct traffic routes through the GO.
     *
     * This is the core of the GO/peer asymmetry handling.
     */
    private void onWifiDirectConnectionInfoAvailable(WifiP2pInfo info) {
        if (info == null) return;

        groupOwnerAddress = info.groupOwnerAddress;
        isGroupOwner = info.isGroupOwner;

        Log.d(TAG, "WiFi Direct connection info: GO=" + isGroupOwner
                + " GO_addr=" + (groupOwnerAddress != null
                        ? groupOwnerAddress.getHostAddress() : "null")
                + " groupFormed=" + info.groupFormed);

        if (!info.groupFormed) return;

        if (isGroupOwner) {
            // ── WE ARE THE GROUP OWNER ──────────────────────────────────
            // Start a TCP server. All WiFi Direct peers connect to us.
            // We act as a relay hub: messages from one peer are forwarded
            // to all other peers (both WiFi Direct and Bluetooth).
            if (wifiDirectServerThread == null || !wifiDirectServerThread.isAlive()) {
                wifiDirectServerThread = new WifiDirectServerThread();
                wifiDirectServerThread.start();
                Log.d(TAG, "GO: Started WiFi Direct TCP server on port "
                        + WIFI_DIRECT_PORT);
            }
        } else {
            // ── WE ARE A PEER (non-GO) ──────────────────────────────────
            // Connect to the GO's TCP server. All our WiFi Direct messages
            // go through the GO — we cannot reach other peers directly.
            if (groupOwnerAddress != null) {
                String goIp = groupOwnerAddress.getHostAddress();
                if (!wifiDirectConnectedNodes.contains(goIp)) {
                    new WifiDirectClientThread(groupOwnerAddress).start();
                    Log.d(TAG, "Peer: Connecting to GO at " + goIp);
                }
            }
        }
    }

    /** Cleans up all WiFi Direct connections when the P2P group is lost. */
    private void handleWifiDirectGroupLost() {
        isGroupOwner = false;
        groupOwnerAddress = null;
        if (wifiDirectServerThread != null) {
            wifiDirectServerThread.interrupt();
            wifiDirectServerThread = null;
        }
        // Disconnect all WiFi Direct peers
        for (String addr : new ArrayList<>(wifiDirectConnectedNodes)) {
            notifyNodeDisconnected(addr);
        }
    }

    /** Converts a WifiP2pDevice status int to a readable string. */
    private static String deviceStatusToString(int status) {
        switch (status) {
            case WifiP2pDevice.AVAILABLE:   return "Available";
            case WifiP2pDevice.INVITED:     return "Invited";
            case WifiP2pDevice.CONNECTED:   return "Connected";
            case WifiP2pDevice.FAILED:      return "Failed";
            case WifiP2pDevice.UNAVAILABLE: return "Unavailable";
            default: return "Unknown(" + status + ")";
        }
    }

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

        // Remove WiFi Direct group if we formed one
        if (wifiP2pManager != null && wifiP2pChannel != null) {
            wifiP2pManager.removeGroup(wifiP2pChannel, null);
        }
        isGroupOwner = false;
        groupOwnerAddress = null;

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

    /**
     * Starts a Bluetooth + WiFi Direct scan.
     * Called by the UI and periodically by the scheduler.
     */
    public void startDiscovery() {
        discoveredPeers.clear();

        // ── Bluetooth discovery ─────────────────────────────────────────
        try {
            if (bluetoothAdapter != null && !bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.startDiscovery();
            }
        } catch (SecurityException e) {
            Log.w(TAG, "BT discovery permission denied: " + e.getMessage());
        }

        // ── WiFi Direct discovery ───────────────────────────────────────
        // Uses WifiP2pManager.discoverPeers() to find nearby WiFi Direct
        // devices. Results arrive asynchronously via the
        // WIFI_P2P_PEERS_CHANGED_ACTION broadcast, which triggers
        // requestPeers() → onWifiDirectPeersAvailable().
        startWifiDirectDiscovery();
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

    // ═══════════════════════════════════════════════════════════════════
    // HEARTBEAT & STALE PEER EVICTION
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Starts two periodic tasks on a dedicated scheduler:
     *   1. HEARTBEAT BROADCAST — every HEARTBEAT_INTERVAL_SEC seconds,
     *      broadcasts a lightweight heartbeat message to all connected peers.
     *      Each heartbeat carries our username and node UUID so that receivers
     *      can update their last-seen timestamps.
     *   2. STALE PEER CHECK — every HEARTBEAT_INTERVAL_SEC seconds (offset
     *      by half the interval for even spacing), iterates all directly-
     *      connected peers and evicts any that haven't been heard from
     *      within STALE_PEER_TIMEOUT_MS.
     */
    private void startHeartbeatScheduler() {
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();

        // ── Periodic heartbeat broadcast ────────────────────────────────
        heartbeatScheduler.scheduleAtFixedRate(
                this::broadcastHeartbeat,
                HEARTBEAT_INTERVAL_SEC,
                HEARTBEAT_INTERVAL_SEC,
                TimeUnit.SECONDS
        );

        // ── Periodic stale peer eviction ────────────────────────────────
        heartbeatScheduler.scheduleAtFixedRate(
                this::evictStalePeers,
                HEARTBEAT_INTERVAL_SEC + (HEARTBEAT_INTERVAL_SEC / 2),
                HEARTBEAT_INTERVAL_SEC,
                TimeUnit.SECONDS
        );
    }

    /**
     * Broadcasts a heartbeat message to all connected peers.
     * Heartbeats are short-lived control frames (5-minute TTL) that are
     * forwarded across the mesh like any broadcast, allowing multi-hop
     * liveness detection.
     */
    private void broadcastHeartbeat() {
        if (connectedNodes.isEmpty() && wifiDirectConnectedNodes.isEmpty()) return;

        Message heartbeat = Message.createHeartbeat(username, myNodeId);
        processedMessages.add(heartbeat.getId());
        sendToAllPeers(heartbeat, null);
        Log.d(TAG, "Heartbeat broadcast sent");
    }

    /**
     * Checks all directly-connected peers and disconnects any that haven't
     * sent a message (including heartbeats) within STALE_PEER_TIMEOUT_MS.
     *
     * This handles node churn: when a peer silently drops off the network
     * (e.g. battery dies, walks out of range), the stale peer timeout
     * ensures we clean up its resources and stop trying to route through it.
     */
    private void evictStalePeers() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : lastSeenTimestamps.entrySet()) {
            String address = entry.getKey();
            long lastSeen = entry.getValue();

            // Only evict if the peer is still tracked as connected
            if (!connectedNodes.contains(address)
                    && !wifiDirectConnectedNodes.contains(address)) {
                lastSeenTimestamps.remove(address);
                continue;
            }

            if (now - lastSeen > STALE_PEER_TIMEOUT_MS) {
                Log.w(TAG, "Evicting stale peer " + address
                        + " (last seen " + ((now - lastSeen) / 1000) + "s ago)");
                lastSeenTimestamps.remove(address);
                notifyNodeDisconnected(address);
            }
        }
    }

    /**
     * Records a liveness timestamp for a peer.
     * Called whenever ANY message (chat, heartbeat, handshake, ACK) is received
     * from a directly-connected peer.
     */
    private void updateLastSeen(String address) {
        lastSeenTimestamps.put(address, System.currentTimeMillis());
    }

    /** Returns the current list of discovered mesh networks */
    public List<Network> getDiscoveredNetworks() {
        return new ArrayList<>(discoveredNetworks.values());
    }

    // ═══════════════════════════════════════════════════════════════════
    // WIFI DIRECT DISCOVERY & GROUP FORMATION
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Starts WiFi Direct peer discovery using WifiP2pManager.discoverPeers().
     *
     * This is an asynchronous call. When peers are found, the system fires
     * WIFI_P2P_PEERS_CHANGED_ACTION, which triggers requestPeers() in our
     * BroadcastReceiver, which calls onWifiDirectPeersAvailable().
     */
    private void startWifiDirectDiscovery() {
        if (wifiP2pManager == null || wifiP2pChannel == null || !wifiP2pEnabled) return;
        try {
            wifiP2pManager.discoverPeers(wifiP2pChannel,
                    new WifiP2pManager.ActionListener() {
                        @Override
                        public void onSuccess() {
                            Log.d(TAG, "WiFi Direct peer discovery started");
                        }
                        @Override
                        public void onFailure(int reason) {
                            Log.w(TAG, "WiFi Direct discovery failed: reason=" + reason);
                        }
                    });
        } catch (SecurityException e) {
            Log.w(TAG, "WiFi Direct discovery permission denied: " + e.getMessage());
        }
    }

    /**
     * Initiates a WiFi Direct connection to a discovered peer device.
     *
     * Uses WifiP2pManager.connect() with a WifiP2pConfig targeting the
     * specified device address. The system negotiates who becomes the
     * Group Owner (GO):
     *   - The GO acts as a soft access point (soft AP).
     *   - All peers connect to the GO.
     *   - Non-GO peers CANNOT talk directly to each other — they route
     *     through the GO.
     *
     * After the group is formed, WIFI_P2P_CONNECTION_CHANGED_ACTION fires
     * and onWifiDirectConnectionInfoAvailable() is called to set up the
     * TCP data channel.
     *
     * @param deviceAddress  WiFi Direct MAC address of the target device
     *                       (from WifiP2pDevice.deviceAddress)
     */
    public void connectWifiDirectPeer(String deviceAddress) {
        if (wifiP2pManager == null || wifiP2pChannel == null) {
            Log.w(TAG, "WiFi Direct not available");
            return;
        }

        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = deviceAddress;
        // groupOwnerIntent: 0 = don't care, 15 = strongly prefer to be GO
        // We leave it at default (0) and let the system decide.

        try {
            wifiP2pManager.connect(wifiP2pChannel, config,
                    new WifiP2pManager.ActionListener() {
                        @Override
                        public void onSuccess() {
                            Log.d(TAG, "WiFi Direct connect initiated to "
                                    + deviceAddress);
                        }
                        @Override
                        public void onFailure(int reason) {
                            Log.w(TAG, "WiFi Direct connect failed to "
                                    + deviceAddress + ": reason=" + reason);
                        }
                    });
        } catch (SecurityException e) {
            Log.w(TAG, "WiFi Direct connect permission denied: " + e.getMessage());
        }
    }

    /** Returns the list of discovered WiFi Direct devices */
    public List<WifiP2pDevice> getDiscoveredWifiDirectDevices() {
        return new ArrayList<>(discoveredWifiDirectDevices.values());
    }

    /** Whether this device is currently the WiFi Direct Group Owner */
    public boolean isWifiDirectGroupOwner() { return isGroupOwner; }

    /** Whether WiFi P2P is enabled on this device */
    public boolean isWifiP2pEnabled() { return wifiP2pEnabled; }

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

        if (bluetoothOutputStreams.isEmpty() && wifiDirectOutputStreams.isEmpty()) {
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
        } else if (!bluetoothOutputStreams.isEmpty()
                || !wifiDirectOutputStreams.isEmpty()) {
            // Relay through mesh (Bluetooth + WiFi Direct)
            sendToAllPeers(networkCopy, null);
        } else {
            // No peers — queue for when they connect
            perRecipientQueue
                    .computeIfAbsent(recipientId, k -> new ConcurrentLinkedQueue<>())
                    .add(networkCopy);
        }
    }

    /**
     * Sends a message to all connected peers via BOTH transports, optionally
     * excluding the source address.
     *
     * This is how GO/peer asymmetry is handled transparently:
     *   - The GO has WiFi Direct streams to each peer → message reaches all.
     *   - A non-GO peer has only one WD stream (to the GO) → GO relays.
     *   - Bluetooth peers are reached directly regardless of GO status.
     *
     * @param excludeAddress address to skip (BT MAC or WiFi Direct IP of the
     *                       peer we received this message from), or null.
     */
    private void sendToAllPeers(Message message, String excludeAddress) {
        // Bluetooth transport
        for (Map.Entry<String, ObjectOutputStream> entry : bluetoothOutputStreams.entrySet()) {
            String mac = entry.getKey();
            if (mac.equals(excludeAddress)) continue;
            writeToStream(entry.getValue(), message, mac);
        }
        // WiFi Direct transport
        for (Map.Entry<String, ObjectOutputStream> entry : wifiDirectOutputStreams.entrySet()) {
            String addr = entry.getKey();
            if (addr.equals(excludeAddress)) continue;
            writeToStream(entry.getValue(), message, addr);
        }
    }

    /** Sends a message to a single specific peer (checks BT, then WiFi Direct) */
    private void sendToNode(String mac, Message message) {
        ObjectOutputStream out = bluetoothOutputStreams.get(mac);
        if (out == null) out = wifiDirectOutputStreams.get(mac);
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
        // ── Update liveness timestamp for the direct peer ──
        updateLastSeen(sourceAddress);

        // ── Control frames (handshake / ACK / heartbeat) ──
        if (message.getSubType() == Message.SUBTYPE_HANDSHAKE) {
            handleHandshake(message, sourceAddress);
            return;
        }
        if (message.getSubType() == Message.SUBTYPE_ACK) {
            handleAck(message);
            return;
        }
        if (message.getSubType() == Message.SUBTYPE_HEARTBEAT) {
            handleHeartbeat(message, sourceAddress);
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
        String fcmToken = FcmTokenManager.getOwnToken(context);
        Message handshake = Message.createHandshake(username, myNodeId, fcmToken);
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
        // Format: "username|nodeUUID" (legacy) or "username|nodeUUID|fcmToken"
        String[] parts = content.split("\\|", 3);
        if (parts.length < 2) return;

        String peerUsername = parts[0];
        String peerNodeId   = parts[1];
        String peerFcmToken = (parts.length >= 3 && !parts[2].isEmpty()) ? parts[2] : null;

        // Store bidirectional UUID ↔ MAC mapping
        nodeIdToAddress.put(peerNodeId, sourceMac);
        addressToNodeId.put(sourceMac, peerNodeId);
        nodeNames.put(peerNodeId, peerUsername);

        // Persist FCM token for this peer so we can notify them later
        if (peerFcmToken != null) {
            FcmTokenManager.savePeerToken(context, peerNodeId, peerFcmToken);
        }

        Log.d(TAG, "Handshake from " + peerUsername + " (" + peerNodeId + ") @ " + sourceMac);
        notifyNodeInfoUpdated();

        // Notify all OTHER connected peers that a new node joined
        new FcmNotificationSender(context).notifyPeersOfNewNode(peerUsername, peerNodeId);

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

    // ─── Heartbeat ──────────────────────────────────────────────────────

    /**
     * Processes an incoming heartbeat message.
     *
     * Heartbeats serve two purposes:
     *   1. LIVENESS — updateLastSeen() was already called above, so the
     *      peer's stale timer is refreshed.
     *   2. MULTI-HOP FORWARDING — heartbeats are forwarded like broadcasts
     *      so that nodes several hops away can still detect liveness of
     *      distant peers.
     *
     * Heartbeats are NOT delivered to the UI (no onMessageReceived callback).
     */
    private void handleHeartbeat(Message message, String sourceAddress) {
        // ── Deduplication (same LRU set as chat messages) ──
        if (processedMessages.contains(message.getId())) return;
        processedMessages.add(message.getId());

        // ── TTL check ──
        if (message.isExpired()) return;

        // ── Forward to other peers (multi-hop liveness propagation) ──
        if (message.canForward()) {
            message.incrementHopCount();
            sendToAllPeers(message, sourceAddress);
        }

        Log.d(TAG, "Heartbeat received from " + message.getSenderName()
                + " (hop " + message.getHopCount() + ")");
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
        updateLastSeen(mac); // initial liveness timestamp
        notifyNodeInfoUpdated();
        flushBroadcastQueue();

        // Kick off a fresh scan shortly after each new connection so that
        // already-connected nodes quickly discover any additional peers that
        // may have joined the network around the same time.
        mainHandler.postDelayed(this::startDiscovery, 2_000);
    }

    private void notifyNodeDisconnected(String mac) {
        connectedNodes.remove(mac);
        connectingNodes.remove(mac); // allow reconnection attempts after a drop
        lastSeenTimestamps.remove(mac); // clean up liveness tracking

        // Bluetooth cleanup
        ObjectOutputStream out = bluetoothOutputStreams.remove(mac);
        if (out != null) { try { out.close(); } catch (IOException ignored) {} }

        BluetoothSocket sock = bluetoothSockets.remove(mac);
        if (sock != null) { try { sock.close(); } catch (IOException ignored) {} }

        // WiFi Direct cleanup (address may be an IP string)
        ObjectOutputStream wdOut = wifiDirectOutputStreams.remove(mac);
        if (wdOut != null) { try { wdOut.close(); } catch (IOException ignored) {} }

        Socket wdSock = wifiDirectSockets.remove(mac);
        if (wdSock != null) { try { wdSock.close(); } catch (IOException ignored) {} }
        wifiDirectConnectedNodes.remove(mac);

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
        if (heartbeatScheduler != null && !heartbeatScheduler.isShutdown()) {
            heartbeatScheduler.shutdownNow();
        }
        try { context.unregisterReceiver(bluetoothReceiver); } catch (Exception ignored) {}
        try { context.unregisterReceiver(wifiDirectReceiver); } catch (Exception ignored) {}
        if (bluetoothServerThread != null) bluetoothServerThread.interrupt();

        for (ObjectOutputStream out : bluetoothOutputStreams.values()) {
            try { out.close(); } catch (IOException ignored) {}
        }
        for (BluetoothSocket s : bluetoothSockets.values()) {
            try { s.close(); } catch (IOException ignored) {}
        }

        // WiFi Direct cleanup
        if (wifiDirectServerThread != null) wifiDirectServerThread.interrupt();
        for (ObjectOutputStream out : wifiDirectOutputStreams.values()) {
            try { out.close(); } catch (IOException ignored) {}
        }
        for (Socket s : wifiDirectSockets.values()) {
            try { s.close(); } catch (IOException ignored) {}
        }
        if (wifiP2pManager != null && wifiP2pChannel != null) {
            wifiP2pManager.removeGroup(wifiP2pChannel, null);
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

                    // Cross-connect guard: if our BluetoothClientThread already
                    // established a connection to this MAC, close the duplicate.
                    if (connectedNodes.contains(mac) || connectingNodes.contains(mac)) {
                        Log.d(TAG, "Duplicate incoming connection from " + mac + ", closing.");
                        try { socket.close(); } catch (IOException ignored) {}
                        continue;
                    }

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
     *
     * IMPORTANT: Bluetooth discovery MUST be cancelled before calling
     * socket.connect() — keeping discovery running causes connect() to
     * time out on most Android devices.
     */
    private class BluetoothClientThread extends Thread {
        private final BluetoothDevice device;

        BluetoothClientThread(BluetoothDevice device) {
            this.device = device;
            setName("BT-Client-" + device.getAddress());
        }

        @Override
        public void run() {
            String address = device.getAddress();

            // Claim the slot; bail if another thread is already connecting to this MAC.
            if (!connectingNodes.add(address)) {
                Log.d(TAG, "Already connecting to " + address + ", skipping.");
                return;
            }

            BluetoothSocket socket = null;
            try {
                // ── Step 1: stop discovery ─────────────────────────────────
                // Android requires this before socket.connect(); skipping it
                // causes connect() to time out on nearly every device.
                try {
                    if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
                        bluetoothAdapter.cancelDiscovery();
                    }
                } catch (SecurityException ignored) {}

                // ── Step 2: connect ────────────────────────────────────────
                socket = device.createRfcommSocketToServiceRecord(MY_UUID);
                Log.d(TAG, "Connecting to: " + address);
                socket.connect();

                // ── Step 3: cross-connect guard ────────────────────────────
                // The remote device may have simultaneously connected to us as
                // a server. Accept whichever socket arrived first; close this one.
                if (connectedNodes.contains(address)) {
                    Log.d(TAG, "Cross-connect detected for " + address + ", closing client socket.");
                    socket.close();
                    return;
                }

                bluetoothSockets.put(address, socket);
                new MessageReceiverThread(socket).start();

            } catch (IOException e) {
                Log.e(TAG, "BT connect failed to " + address + ": " + e.getMessage());
                if (socket != null) { try { socket.close(); } catch (IOException ignored) {} }
            } catch (SecurityException e) {
                Log.e(TAG, "BT connect permission denied: " + e.getMessage());
            } finally {
                connectingNodes.remove(address);
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

    // ═══════════════════════════════════════════════════════════════════
    // WIFI DIRECT NETWORKING THREADS
    // ═══════════════════════════════════════════════════════════════════

    /**
     * TCP server that runs ONLY on the Group Owner (GO).
     *
     * GROUP OWNER ASYMMETRY:
     * ──────────────────────
     * The GO acts as a relay hub for all WiFi Direct peers:
     *   - Each peer connects a TCP socket to the GO on WIFI_DIRECT_PORT.
     *   - The GO accepts these connections and spawns a
     *     WifiDirectReceiverThread for each one.
     *   - When the GO receives a message from peer A, sendToAllPeers()
     *     forwards it to all other peers (B, C, …) and also to any
     *     Bluetooth-connected nodes.
     *   - Non-GO peers CANNOT reach each other directly — all traffic
     *     between them must pass through the GO.
     *
     * This thread blocks on ServerSocket.accept() in a loop, handling
     * new peer connections as they arrive.
     */
    private class WifiDirectServerThread extends Thread {

        WifiDirectServerThread() { setName("WD-Server"); }

        @Override
        public void run() {
            try (ServerSocket serverSocket = new ServerSocket(WIFI_DIRECT_PORT)) {
                serverSocket.setReuseAddress(true);
                Log.d(TAG, "WiFi Direct TCP server listening on port "
                        + WIFI_DIRECT_PORT);

                while (!isInterrupted()) {
                    Socket client = serverSocket.accept();
                    String peerIp = client.getInetAddress().getHostAddress();
                    Log.d(TAG, "GO: Accepted WiFi Direct connection from " + peerIp);

                    wifiDirectSockets.put(peerIp, client);
                    new WifiDirectReceiverThread(client, peerIp).start();
                }
            } catch (IOException e) {
                if (!isInterrupted()) {
                    Log.e(TAG, "WiFi Direct server error: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Connects to the Group Owner's TCP server (runs on non-GO peers).
     *
     * NON-GO PEER BEHAVIOUR:
     * ──────────────────────
     * A non-GO peer has exactly ONE WiFi Direct connection — to the GO.
     * All messages sent by this peer go to the GO, which relays them to
     * the appropriate recipients (other WiFi Direct peers or Bluetooth nodes).
     *
     * This asymmetry means:
     *   - Peer A sends msg → GO receives → GO forwards to Peer B
     *   - Peer A CANNOT send directly to Peer B
     *   - The GO is always the intermediary for WiFi Direct traffic
     */
    private class WifiDirectClientThread extends Thread {
        private final InetAddress goAddress;

        WifiDirectClientThread(InetAddress goAddress) {
            this.goAddress = goAddress;
            setName("WD-Client-" + goAddress.getHostAddress());
        }

        @Override
        public void run() {
            String goIp = goAddress.getHostAddress();
            Socket socket = new Socket();
            try {
                Log.d(TAG, "Peer: Connecting to GO at " + goIp + ":" + WIFI_DIRECT_PORT);
                socket.connect(new InetSocketAddress(goAddress, WIFI_DIRECT_PORT), 10_000);

                wifiDirectSockets.put(goIp, socket);
                new WifiDirectReceiverThread(socket, goIp).start();

            } catch (IOException e) {
                Log.e(TAG, "WiFi Direct connect to GO failed: " + e.getMessage());
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Manages bidirectional messaging on an established WiFi Direct TCP socket.
     *
     * Works identically to the Bluetooth MessageReceiverThread:
     *   1. Creates ObjectOutputStream (flush header first to avoid deadlock).
     *   2. Creates ObjectInputStream.
     *   3. Sends our handshake (username + UUID + FCM token).
     *   4. Enters a read loop, dispatching incoming messages to
     *      handleIncomingMessage() with the peer's IP as sourceAddress.
     *
     * When the GO's handleIncomingMessage() receives a message from a peer,
     * it relays the message to ALL other connected nodes (excluding the source)
     * via sendToAllPeers(). This is how the GO bridges WiFi Direct peers that
     * cannot talk directly to each other.
     */
    private class WifiDirectReceiverThread extends Thread {
        private final Socket socket;
        private final String peerAddress;

        WifiDirectReceiverThread(Socket socket, String peerAddress) {
            this.socket = socket;
            this.peerAddress = peerAddress;
            setName("WD-Receiver-" + peerAddress);
        }

        @Override
        public void run() {
            try {
                // Output stream MUST be created and flushed before input stream
                // to prevent deadlock (same as Bluetooth — both sides write their
                // stream header first, then read).
                ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                out.flush();
                wifiDirectOutputStreams.put(peerAddress, out);

                ObjectInputStream in = new ObjectInputStream(socket.getInputStream());

                // Send handshake immediately
                sendHandshake(out);

                // Mark this peer as connected
                wifiDirectConnectedNodes.add(peerAddress);
                notifyNodeConnected(peerAddress);

                // Read loop — blocks until data or disconnection
                while (!isInterrupted()) {
                    Object received = in.readObject();
                    if (received instanceof Message) {
                        handleIncomingMessage((Message) received, peerAddress);
                    }
                }
            } catch (IOException e) {
                Log.d(TAG, "WiFi Direct connection lost: " + peerAddress);
            } catch (ClassNotFoundException e) {
                Log.e(TAG, "Unknown object from WiFi Direct peer: " + peerAddress);
            } finally {
                notifyNodeDisconnected(peerAddress);
            }
        }
    }
}
