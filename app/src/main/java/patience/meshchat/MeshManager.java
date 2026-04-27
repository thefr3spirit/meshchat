package patience.meshchat;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.ScanSettings;
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
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import patience.meshchat.gossip.GossipManager;
import patience.meshchat.transport.CompositeTransport;
import patience.meshchat.transport.MeshTransport;

import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
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
 * MeshManager — Bluetooth + WiFi Direct + BLE mesh networking engine.
 *
 * Responsibilities:
 *  1. PEER DISCOVERY    — Scans via BT Classic, WiFi Direct, and BLE advertisements
 *  2. CONNECTIONS       — RFCOMM (Bluetooth) + TCP (WiFi Direct) connections
 *  3. MESSAGE ROUTING   — Broadcast and private (addressed) message routing
 *  4. ENCRYPTION        — AES-256-GCM (shared) + ECIES/X25519 (E2E)
 *  5. OFFLINE QUEUING   — Per-recipient queues, flushed on reconnect
 *  6. IDENTITY          — Handshake protocol to exchange usernames and UUIDs
 *  7. DISTANCE CONTROL  — RSSI threshold determines whether to auto-connect
 *  8. WIFI DIRECT       — Group Owner discovery and group formation
 *  9. BLE ADVERTISING   — Dead-zone discovery via BLE ads + WiFi Direct handoff
 * 10. STORE & FORWARD   — Room-persisted queue with BLE-triggered redelivery
 * 11. POWER MANAGEMENT  — Duty-cycle scanning + battery-aware scan mode switching
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

    // ─── Duty-cycle BLE scanning ────────────────────────────────────

    /** BLE scan window: scan actively for this many milliseconds */
    private static final long BLE_SCAN_WINDOW_MS = 2_000;   // 2 seconds

    /** BLE scan pause: sleep between scan windows */
    private static final long BLE_SCAN_PAUSE_MS = 8_000;    // 8 seconds

    /** Battery level (%) below which we switch to aggressive power saving */
    private static final int LOW_BATTERY_THRESHOLD = 20;

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
    private final E2ECryptoManager e2eCryptoManager;

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

    // ─── BLE Advertising (dead-zone discovery) ──────────────────────

    /** BLE advertiser/scanner for dead-zone peer discovery */
    private BleAdvertiser bleAdvertiser;

    // ─── Transport abstraction layer ────────────────────────────────

    /**
     * The unified transport provided by Hilt DI.
     * CompositeTransport wraps WiFi Direct, BLE, and NFC transports
     * behind a single MeshTransport interface so that the routing logic
     * (broadcastMessage, sendPrivateMessage, etc.) never needs to know
     * which physical transport is in use.
     *
     * Set via setCompositeTransport() from the @AndroidEntryPoint Service.
     */
    private CompositeTransport compositeTransport;

    // ─── Store & Forward (Room-persisted queue) ─────────────────────

    /** Persistent message queue — survives app restarts and process death */
    private StoreAndForwardManager storeAndForwardManager;

    /**
     * Gossip anti-entropy manager — ensures eventual consistency.
     * Periodically broadcasts Bloom filters of received message IDs;
     * peers compare and push missing messages.
     */
    private GossipManager gossipManager;

    // ─── Power management state ───────────────────────────────────

    /**
     * Whether the app's Activity is in the foreground.
     * Controls BLE scan mode: LOW_LATENCY (foreground) vs LOW_POWER (background).
     */
    private volatile boolean appInForeground = true;

    /** Handler used for duty-cycle BLE scanning (scan 2s / pause 8s) */
    private final Handler dutyCycleHandler = new Handler(Looper.getMainLooper());

    /** Our WiFi Direct MAC address (learned when this device info changes) */
    private volatile String myWifiDirectMac;

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

    /** JSON writers for WiFi Direct peers (keyed by IP address string) */
    private final Map<String, BufferedWriter> wifiDirectOutputStreams =
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

    /** JSON writers keyed by peer's BT MAC address */
    private final Map<String, BufferedWriter> bluetoothOutputStreams =
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

    // ─── Serialization ──────────────────────────────────────────────────

    /** Gson instance for JSON wire format (replaces Java object serialization) */
    private final Gson gson = new Gson();

    // ─── Callbacks & threading ──────────────────────────────────────────

    private MessageListener messageListener;
    private ConnectionProgressListener connectionProgressListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ScheduledExecutorService discoveryScheduler;

    /** Holds the pending postDelayed onNetworkDiscovered runnable so it can be cancelled. */
    private Runnable discoveryNotifyRunnable;

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

    /**
     * Delivers live updates on the connection establishment process so the UI
     * can show a step-by-step progress indicator (Scanning → Detected →
     * Connecting → Handshaking → Connected / Failed).
     */
    public interface ConnectionProgressListener {
        /**
         * @param peerAddress  BT MAC or WiFi Direct IP of the peer (may be empty
         *                     during SCANNING before any peer is found)
         * @param peerName     Human-readable peer name (from BT device name or handshake)
         * @param phase        The current connection phase
         * @param transport    Transport in use: "bluetooth", "wifi_direct", or "ble"
         * @param rssi         Signal strength in dBm (0 if unknown)
         */
        void onProgressChanged(String peerAddress, String peerName,
                               ConnectionPhase phase, String transport, int rssi);
    }

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
        this.e2eCryptoManager = new E2ECryptoManager(context);
        this.storeAndForwardManager = new StoreAndForwardManager(context);
        this.storeAndForwardManager.setDeliveryCallback(this::deliverStoredMessage);

        // Gossip protocol for eventual consistency
        this.gossipManager = new GossipManager(context, myNodeId);
        this.gossipManager.start(new GossipManager.GossipCallback() {
            @Override
            public void sendToAllPeers(Message message, String excludeAddress) {
                MeshManager.this.sendToAllPeers(message, excludeAddress);
            }

            @Override
            public void sendToPeer(String peerAddress, Message message) {
                MeshManager.this.sendToNode(peerAddress, message);
            }

            @Override
            public String getUsername() {
                return username;
            }
        });

        initialize();
    }

    private void initialize() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter != null) {
            setupBluetooth();
            setupBleAdvertiser();
        }
        initializeWifiDirect();
        registerReceivers();
        startPeriodicDiscovery();
        startHeartbeatScheduler();

        // Start the transport abstraction layer (if injected)
        if (compositeTransport != null) {
            compositeTransport.listen(transportCallback);
            compositeTransport.discover(discoveryTransportCallback);
        }
    }

    /**
     * Injects the Hilt-provided CompositeTransport.
     * Called from MeshService after dependency injection.
     */
    public void setCompositeTransport(CompositeTransport transport) {
        this.compositeTransport = transport;
        // If already initialized, start listening/discovering immediately
        compositeTransport.listen(transportCallback);
        compositeTransport.discover(discoveryTransportCallback);
    }

    /** Returns the CompositeTransport for diagnostics or direct use */
    public CompositeTransport getCompositeTransport() {
        return compositeTransport;
    }

    /**
     * Transport callback: receives data and connection events from ALL
     * transports (WiFi Direct, BLE, NFC) via the CompositeTransport.
     * Routes incoming data to handleIncomingMessage() just like the
     * legacy thread-based receivers.
     */
    private final MeshTransport.TransportCallback transportCallback =
            new MeshTransport.TransportCallback() {
                @Override
                public void onDataReceived(String peerId, byte[] data,
                                           String transportName) {
                    Log.d(TAG, "Transport data from " + peerId
                            + " via " + transportName
                            + " (" + data.length + " bytes)");
                    // Deserialise JSON and route through the existing message pipeline
                    try {
                        String json = new String(data, StandardCharsets.UTF_8);
                        Message msg = gson.fromJson(json, Message.class);
                        if (msg != null) {
                            handleIncomingMessage(msg, peerId);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Transport JSON parse failed: " + e.getMessage());
                    }
                }

                @Override
                public void onPeerConnected(String peerId, String transportName) {
                    Log.d(TAG, "Transport peer connected: " + peerId
                            + " via " + transportName);
                    lastSeenTimestamps.put(peerId, System.currentTimeMillis());
                }

                @Override
                public void onPeerDisconnected(String peerId, String transportName) {
                    Log.d(TAG, "Transport peer disconnected: " + peerId
                            + " via " + transportName);
                    lastSeenTimestamps.remove(peerId);
                }
            };

    /**
     * Discovery callback: receives peer-found/lost events from ALL
     * transports via the CompositeTransport.
     */
    private final MeshTransport.DiscoveryCallback discoveryTransportCallback =
            new MeshTransport.DiscoveryCallback() {
                @Override
                public void onPeerDiscovered(String peerId, String displayName,
                                             int rssi, String transportName) {
                    Log.d(TAG, "Transport discovered: " + peerId
                            + " (" + displayName + ") via " + transportName);
                    if (rssi != 0) {
                        rssiValues.put(peerId, rssi);
                    }
                }

                @Override
                public void onPeerLost(String peerId, String transportName) {
                    Log.d(TAG, "Transport peer lost: " + peerId
                            + " via " + transportName);
                }
            };

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

    // ─── BLE advertiser setup ───────────────────────────────────────────

    /**
     * Initialises the BLE advertiser/scanner for dead-zone peer discovery.
     *
     * In dead zones (no WiFi AP, no cellular), BLE advertisements allow
     * nearby devices to discover each other and exchange WiFi Direct MAC
     * addresses. The scanning device then uses the MAC to initiate a
     * WiFi Direct connection — handing off from BLE (discovery) to
     * WiFi Direct (data transfer).
     *
     * The BLE advertisement payload contains:
     *   - Our WiFi Direct MAC address (so peers can connect to us)
     *   - Our mesh Node ID prefix (so peers can identify us)
     */
    private void setupBleAdvertiser() {
        bleAdvertiser = new BleAdvertiser(context, bluetoothAdapter);
        bleAdvertiser.setListener(this::onPeerDiscoveredViaBle);

        // Start duty-cycle BLE scanning (scan 2s, pause 8s)
        startDutyCycleScanning();

        // Advertising starts once we learn our WiFi Direct MAC (see
        // WIFI_P2P_THIS_DEVICE_CHANGED_ACTION in wifiDirectReceiver)
    }

    /**
     * Starts duty-cycle BLE scanning to balance discovery speed vs battery.
     *
     * DUTY-CYCLE PATTERN:
     *   1. Scan for BLE_SCAN_WINDOW_MS (2 seconds) — actively looking for peers
     *   2. Pause for BLE_SCAN_PAUSE_MS (8 seconds) — radio off, saving power
     *   3. Repeat indefinitely
     *
     * This gives a 20% duty cycle (2s out of every 10s), which is a good
     * compromise between finding peers quickly and preserving battery.
     *
     * SCAN MODE depends on foreground state:
     *   - Foreground: SCAN_MODE_LOW_LATENCY (fastest discovery, higher drain)
     *   - Background: SCAN_MODE_LOW_POWER   (slower discovery, minimal drain)
     *
     * On LOW BATTERY (&lt;20%): scan mode is always LOW_POWER regardless of
     * foreground state, and we respect the duty cycle strictly.
     */
    private void startDutyCycleScanning() {
        if (bleAdvertiser == null) return;

        int scanMode = chooseScanMode();
        bleAdvertiser.startScanning(scanMode);

        // Schedule stop after the scan window (2s)
        dutyCycleHandler.postDelayed(this::dutyCyclePause, BLE_SCAN_WINDOW_MS);
    }

    /**
     * Pauses BLE scanning during the duty-cycle sleep phase.
     * After the pause period, restarts scanning automatically.
     */
    private void dutyCyclePause() {
        if (bleAdvertiser != null) {
            bleAdvertiser.stopScanning();
        }
        // Schedule next scan window after the pause (8s)
        dutyCycleHandler.postDelayed(this::startDutyCycleScanning, BLE_SCAN_PAUSE_MS);
    }

    /**
     * Selects the BLE scan mode based on foreground state and battery level.
     *
     * Policy:
     *   - Low battery (&lt;20%): always SCAN_MODE_LOW_POWER
     *   - Foreground + sufficient battery: SCAN_MODE_LOW_LATENCY
     *   - Background + sufficient battery: SCAN_MODE_LOW_POWER
     *
     * @return ScanSettings.SCAN_MODE_* constant
     */
    private int chooseScanMode() {
        if (isBatteryLow()) {
            return ScanSettings.SCAN_MODE_LOW_POWER;
        }
        return appInForeground
                ? ScanSettings.SCAN_MODE_LOW_LATENCY
                : ScanSettings.SCAN_MODE_LOW_POWER;
    }

    /**
     * Checks if the device battery is below the low threshold (20%).
     *
     * Uses BatteryManager.getIntProperty() which is available from API 21+.
     * Returns false if the battery level can't be determined.
     */
    private boolean isBatteryLow() {
        BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        if (bm == null) return false;
        int level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        return level > 0 && level < LOW_BATTERY_THRESHOLD;
    }

    /**
     * Notifies MeshManager that the app has entered or left the foreground.
     *
     * When the Activity resumes (foreground), BLE scanning switches to
     * SCAN_MODE_LOW_LATENCY for faster peer discovery. When the Activity
     * pauses (background), scanning drops to SCAN_MODE_LOW_POWER.
     *
     * The duty-cycle (scan 2s / pause 8s) runs in both modes, but the
     * scan mode during the active window changes.
     *
     * @param foreground true when the Activity is visible, false when backgrounded
     */
    public void setAppInForeground(boolean foreground) {
        boolean changed = (this.appInForeground != foreground);
        this.appInForeground = foreground;

        if (changed && bleAdvertiser != null) {
            // Restart the duty-cycle immediately with the new scan mode
            dutyCycleHandler.removeCallbacksAndMessages(null);
            if (bleAdvertiser.isScanning()) {
                bleAdvertiser.stopScanning();
            }
            startDutyCycleScanning();

            Log.d(TAG, "App foreground state changed: " + foreground
                    + " → BLE scan mode: "
                    + (chooseScanMode() == ScanSettings.SCAN_MODE_LOW_LATENCY
                    ? "LOW_LATENCY" : "LOW_POWER"));
        }
    }

    /**
     * Called by BleAdvertiser when a MeshChat peer is discovered via BLE.
     *
     * Extracts the WiFi Direct MAC address from the BLE advertisement
     * and initiates a WiFi Direct connection to the discovered peer.
     * This is the "BLE → WiFi Direct handoff" that enables dead-zone
     * mesh formation.
     *
     * @param wifiDirectMac The peer's WiFi Direct MAC address
     * @param nodeIdPrefix  First 8 bytes of the peer's mesh node UUID (hex)
     * @param rssi          BLE signal strength in dBm
     */
    private void onPeerDiscoveredViaBle(String wifiDirectMac, String nodeIdPrefix, int rssi) {
        Log.d(TAG, "BLE discovery → WiFi Direct handoff: MAC=" + wifiDirectMac
                + " nodeId=" + nodeIdPrefix + " RSSI=" + rssi);

        // Skip if it's our own advertisement or already connected
        if (wifiDirectMac.equals(myWifiDirectMac)) return;
        if (wifiDirectConnectedNodes.contains(wifiDirectMac)) return;

        // Check if we have queued messages for this peer — attempt delivery
        // once the WiFi Direct connection is established (via notifyNodeConnected)
        storeAndForwardManager.attemptDelivery(wifiDirectMac);

        // Initiate WiFi Direct connection to the BLE-discovered peer
        connectWifiDirectPeer(wifiDirectMac);
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
            // On Android 14+, WIFI_P2P_THIS_DEVICE_CHANGED_ACTION is not delivered.
            // Use requestDeviceInfo() proactively to learn our own WiFi Direct MAC.
            fetchOwnWifiDirectInfo();
        } else {
            Log.w(TAG, "WiFi Direct not supported on this device");
        }
    }

    /**
     * Requests this device's own WiFi Direct info (MAC address) via the
     * non-deprecated API. Falls back gracefully if permissions are missing.
     * Called at init time and whenever WiFi P2P is re-enabled.
     */
    private void fetchOwnWifiDirectInfo() {
        if (wifiP2pManager == null || wifiP2pChannel == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                wifiP2pManager.requestDeviceInfo(wifiP2pChannel, device -> {
                    if (device == null) return;
                    String wdMac = device.deviceAddress;
                    if (wdMac != null && !wdMac.isEmpty()) {
                        myWifiDirectMac = wdMac;
                        if (bleAdvertiser != null) {
                            bleAdvertiser.startAdvertising(wdMac, myNodeId);
                        }
                        Log.d(TAG, "WiFi Direct MAC (requestDeviceInfo): " + wdMac);
                    }
                });
            }
        } catch (SecurityException e) {
            Log.w(TAG, "requestDeviceInfo permission denied: " + e.getMessage());
        }
    }

    // ─── Broadcast receivers ────────────────────────────────────────────

    private void registerReceivers() {
        IntentFilter btFilter = new IntentFilter();
        btFilter.addAction(BluetoothDevice.ACTION_FOUND);
        // Android 14+ requires explicit RECEIVER_NOT_EXPORTED for dynamic receivers.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(bluetoothReceiver, btFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(bluetoothReceiver, btFilter);
        }

        // WiFi Direct events
        if (wifiP2pManager != null) {
            IntentFilter wdFilter = new IntentFilter();
            wdFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
            wdFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
            wdFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
            wdFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(wifiDirectReceiver, wdFilter,
                        Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(wifiDirectReceiver, wdFilter);
            }
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

                // Guard: BT name exactly "MC_" yields empty network name
                String networkName = name.substring(BT_PREFIX.length());
                if (networkName.isEmpty()) return;

                int rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                rssiValues.put(address, rssi);

                // Update discovered networks list — refresh lastSeenMs on each sighting
                Network net = discoveredNetworks.get(networkName);
                if (net == null) {
                    discoveredNetworks.put(networkName, new Network(networkName));
                } else {
                    net.nodeCount++;
                    net.lastSeenMs = System.currentTimeMillis();
                }
                notifyNetworksUpdated();

                // Track as discovered peer
                boolean alreadyConnected = connectedNodes.contains(address);
                discoveredPeers.put(address, new PeerAdapter.PeerInfo(
                        name, address, alreadyConnected, "bluetooth", rssi));

                // Auto-connect: same network (case-insensitive), strong enough signal,
                // not already connected. connectingNodes.add() is atomic — returns false
                // if another thread already claimed this address, eliminating the race.
                if (currentNetworkName != null
                        && networkName.equalsIgnoreCase(currentNetworkName)
                        && !alreadyConnected
                        && rssi >= rssiThreshold) {

                    // Notify UI: we found a peer before the socket even opens
                    fireProgress(address, name, ConnectionPhase.PEER_DETECTED,
                            "bluetooth", rssi);

                    if (connectingNodes.add(address)) {
                        new BluetoothClientThread(device).start();
                    }
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
                    // Re-fetch our own WiFi Direct MAC whenever P2P toggles back on.
                    if (wifiP2pEnabled) fetchOwnWifiDirectInfo();
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

                        // Capture our WiFi Direct MAC address and start BLE
                        // advertising so nearby devices (in dead zones) can
                        // discover our WiFi Direct address via BLE and initiate
                        // a WiFi Direct connection to us.
                        String wdMac = device.deviceAddress;
                        if (wdMac != null && !wdMac.isEmpty()) {
                            myWifiDirectMac = wdMac;
                            if (bleAdvertiser != null) {
                                bleAdvertiser.startAdvertising(wdMac, myNodeId);
                            }
                        }
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
     *
     * Steps:
     *  1. Sanitize the name (strip pipe chars that break handshake parsing,
     *     truncate to the max length that fits in a BT device name).
     *  2. Disconnect all peers from any previously joined network.
     *  3. Derive a per-network AES key so each network's messages are isolated.
     *  4. Update the BT device name so peers can discover us.
     *  5. Kick off a new discovery scan.
     */
    public void joinNetwork(String networkName) {
        // ── 1. Sanitize ────────────────────────────────────────────────
        // Strip pipe chars (handshake delimiter) and control characters
        String cleaned = networkName.replace("|", "").trim();
        if (cleaned.isEmpty()) {
            Log.w(TAG, "joinNetwork: sanitized name is empty, aborting");
            return;
        }
        // Truncate so "MC_" + name fits within BT_NAME_MAX (30 chars)
        int maxLen = BT_NAME_MAX - BT_PREFIX.length();
        if (cleaned.length() > maxLen) cleaned = cleaned.substring(0, maxLen);

        // ── 2. Disconnect all peers from the previous network ──────────
        if (currentNetworkName != null && !currentNetworkName.equals(cleaned)) {
            List<String> btPeers = new ArrayList<>(connectedNodes);
            for (String mac : btPeers) notifyNodeDisconnected(mac);
            List<String> wdPeers = new ArrayList<>(wifiDirectConnectedNodes);
            for (String ip : wdPeers) notifyNodeDisconnected(ip);
        }

        // ── 3. Per-network AES key ─────────────────────────────────────
        // Each network gets its own key derived from the network name so that
        // devices on different networks cannot decrypt each other's broadcasts.
        cryptoManager.updatePassphrase(cleaned);

        this.currentNetworkName = cleaned;
        prefs.edit().putString(KEY_NETWORK_NAME, cleaned).apply();

        // ── 4. Update BT name + start discovery ───────────────────────
        updateBtName();
        startDiscovery();

        final String joinedName = cleaned;
        if (messageListener != null) {
            mainHandler.post(() -> messageListener.onNetworkJoined(joinedName));
        }
        Log.d(TAG, "Joined network: " + joinedName);
    }

    /**
     * Creates a new mesh network with the given name and joins it immediately.
     */
    public void createNetwork(String networkName) {
        joinNetwork(networkName);
    }

    /**
     * Leaves the current network. Stops all radios, disconnects all peers,
     * and resets BT name. Safe to call multiple times.
     */
    public void leaveNetwork() {
        this.currentNetworkName = null;
        prefs.edit().remove(KEY_NETWORK_NAME).apply();

        // ── 1. Stop the duty-cycle BLE scan loop immediately ────────────
        dutyCycleHandler.removeCallbacksAndMessages(null);

        // ── 2. Stop BLE advertising and scanning ─────────────────────────
        if (bleAdvertiser != null) {
            bleAdvertiser.stopAdvertising();
            bleAdvertiser.stopScanning();
        }

        // ── 3. Cancel any in-progress BT Classic scan ────────────────────
        try {
            if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }
        } catch (SecurityException ignored) {}

        // ── 4. Disconnect all currently connected peers ───────────────────
        List<String> btSnapshot = new ArrayList<>(connectedNodes);
        for (String mac : btSnapshot) notifyNodeDisconnected(mac);
        List<String> wdSnapshot = new ArrayList<>(wifiDirectConnectedNodes);
        for (String ip : wdSnapshot) notifyNodeDisconnected(ip);

        // ── 5. Remove WiFi Direct group with proper listener ─────────────
        if (wifiP2pManager != null && wifiP2pChannel != null) {
            try {
                wifiP2pManager.removeGroup(wifiP2pChannel,
                        new WifiP2pManager.ActionListener() {
                            @Override public void onSuccess() {
                                Log.d(TAG, "WiFi Direct group removed");
                            }
                            @Override public void onFailure(int reason) {
                                Log.w(TAG, "WiFi Direct group remove failed: " + reason);
                            }
                        });
            } catch (SecurityException e) {
                Log.w(TAG, "removeGroup permission denied: " + e.getMessage());
            }
        }
        isGroupOwner = false;
        groupOwnerAddress = null;

        // ── 6. Reset BT device name to generic ───────────────────────────
        try {
            if (bluetoothAdapter != null
                    && (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                    || hasPermission(Manifest.permission.BLUETOOTH_CONNECT))) {
                bluetoothAdapter.setName("Android_" + Build.MODEL);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "BT name reset failed: " + e.getMessage());
        }

        // ── 7. Flush stale discovery state ───────────────────────────────
        discoveredPeers.clear();
        discoveredNetworks.clear();

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
     * Starts a Bluetooth + WiFi Direct + BLE scan.
     * Called by the UI and periodically by the scheduler.
     *
     * In dead zones (no WiFi AP, no cellular), the BLE scan is the primary
     * discovery mechanism: it finds nearby MeshChat peers via BLE advertisements,
     * extracts their WiFi Direct MAC addresses, and initiates WiFi Direct
     * connections automatically.
     */
    public void startDiscovery() {
        // Only clear the peer cache if BT isn't actively scanning.
        // Clearing mid-scan races with incoming ACTION_FOUND callbacks.
        try {
            if (bluetoothAdapter == null || !bluetoothAdapter.isDiscovering()) {
                discoveredPeers.clear();
            }
        } catch (SecurityException ignored) {
            discoveredPeers.clear();
        }

        // Fire SCANNING progress so the UI can show feedback immediately
        fireProgress("", "", ConnectionPhase.SCANNING, "", 0);

        // ── Bluetooth Classic discovery ─────────────────────────────────
        try {
            if (bluetoothAdapter != null && !bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.startDiscovery();
            }
        } catch (SecurityException e) {
            Log.w(TAG, "BT discovery permission denied: " + e.getMessage());
        }

        // ── BLE scanning (dead-zone discovery) ──────────────────────────
        if (bleAdvertiser != null && !bleAdvertiser.isScanning()) {
            dutyCycleHandler.removeCallbacksAndMessages(null);
            startDutyCycleScanning();
        }

        // ── WiFi Direct discovery ───────────────────────────────────────
        startWifiDirectDiscovery();

        // Cancel any previous pending notify to avoid stacking up delayed callbacks
        if (discoveryNotifyRunnable != null) {
            mainHandler.removeCallbacks(discoveryNotifyRunnable);
        }
        discoveryNotifyRunnable = () -> {
            if (messageListener != null) {
                messageListener.onNetworkDiscovered(getDiscoveredNetworks());
            }
            discoveryNotifyRunnable = null;
        };
        mainHandler.postDelayed(discoveryNotifyRunnable, 12_000);
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

    /** Returns discovered mesh networks seen within the last 60 seconds. */
    public List<Network> getDiscoveredNetworks() {
        long cutoff = System.currentTimeMillis() - 60_000;
        List<Network> live = new ArrayList<>();
        for (Network n : discoveredNetworks.values()) {
            if (n.lastSeenMs >= cutoff) live.add(n);
        }
        return live;
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

        // Record in gossip seen-set for anti-entropy
        gossipManager.recordMessage(message.getId(), message);

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
     * ENCRYPTION STRATEGY:
     *   - If we have the recipient's E2E public key → use ECIES (X25519 + AES-GCM).
     *     Only the recipient can decrypt. Relay nodes see only ciphertext.
     *   - If we DON'T have their public key yet → fall back to shared-passphrase
     *     encryption (CryptoManager). Less secure but still encrypted.
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

        // E2E encryption (preferred): use recipient's X25519 public key
        if (e2eCryptoManager.hasPeerKey(recipientId)) {
            String e2eEncrypted = e2eCryptoManager.encryptForRecipient(
                    message.getContent(), recipientId);
            if (e2eEncrypted != null) {
                networkCopy.setContent(e2eEncrypted);
            } else {
                // E2E failed — fall back to shared passphrase
                networkCopy.setContent(cryptoManager.encrypt(message.getContent()));
            }
        } else {
            // No E2E key available yet — use shared passphrase
            networkCopy.setContent(cryptoManager.encrypt(message.getContent()));
        }
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
            // No peers — queue in-memory and persist to Room for redelivery
            perRecipientQueue
                    .computeIfAbsent(recipientId, k -> new ConcurrentLinkedQueue<>())
                    .add(networkCopy);
            // Also persist to Room (survives process death)
            storeAndForwardManager.enqueue(networkCopy, recipientId, recipientId);
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
        for (Map.Entry<String, BufferedWriter> entry : bluetoothOutputStreams.entrySet()) {
            String mac = entry.getKey();
            if (mac.equals(excludeAddress)) continue;
            if (!writeToStream(entry.getValue(), message, mac)) {
                queueForStoreAndForward(message, mac);
            }
        }
        // WiFi Direct transport
        for (Map.Entry<String, BufferedWriter> entry : wifiDirectOutputStreams.entrySet()) {
            String addr = entry.getKey();
            if (addr.equals(excludeAddress)) continue;
            if (!writeToStream(entry.getValue(), message, addr)) {
                queueForStoreAndForward(message, addr);
            }
        }
    }

    /** Sends a message to a single specific peer (checks BT, then WiFi Direct) */
    private void sendToNode(String mac, Message message) {
        BufferedWriter out = bluetoothOutputStreams.get(mac);
        if (out == null) out = wifiDirectOutputStreams.get(mac);
        if (out != null) {
            if (!writeToStream(out, message, mac)) {
                // Write failed — queue for store-and-forward redelivery
                queueForStoreAndForward(message, mac);
            }
        } else {
            // No stream available — queue for store-and-forward redelivery
            queueForStoreAndForward(message, mac);
        }
    }

    /**
     * Serialises {@code message} to JSON and writes it as a single line to
     * the peer's {@link BufferedWriter}.  One JSON object per line is the
     * framing contract; the receiver reads line-by-line.
     *
     * @return true if the write succeeded, false if it failed
     */
    private boolean writeToStream(BufferedWriter out, Message message, String nodeId) {
        try {
            synchronized (out) {
                out.write(gson.toJson(message));
                out.newLine();
                out.flush();
            }
            return true;
        } catch (IOException e) {
            Log.w(TAG, "Write to " + nodeId + " failed: " + e.getMessage());
            notifyNodeDisconnected(nodeId);
            return false;
        }
    }

    /**
     * Queues a message for store-and-forward delivery via Room persistence.
     * Only queues user chat messages — control frames are not persisted.
     */
    private void queueForStoreAndForward(Message message, String nextHopAddress) {
        if (message.isControlFrame()) return; // don't persist heartbeats/handshakes
        storeAndForwardManager.enqueue(message, nextHopAddress, message.getRecipientId());
        Log.d(TAG, "Queued message " + message.getId() + " for SAF to " + nextHopAddress);
    }

    /**
     * Delivery callback for StoreAndForwardManager.
     * Attempts to write a stored message to the peer's output stream.
     *
     * @param message The deserialized Message from the Room database
     * @param address The next-hop address (BT MAC or WiFi Direct IP)
     * @return true if delivery succeeded
     */
    private boolean deliverStoredMessage(Message message, String address) {
        BufferedWriter out = bluetoothOutputStreams.get(address);
        if (out == null) out = wifiDirectOutputStreams.get(address);
        if (out == null) return false;
        return writeToStream(out, message, address);
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
        if (message.getSubType() == Message.SUBTYPE_KEY_ANNOUNCE) {
            handleKeyAnnounce(message, sourceAddress);
            return;
        }

        // ── Gossip anti-entropy: Bloom filter & message request ──
        if (message.getSubType() == Message.SUBTYPE_BLOOM_FILTER) {
            gossipManager.handleBloomFilter(message, sourceAddress);
            return;
        }

        // ── Deduplication ──
        if (processedMessages.contains(message.getId())) return;
        processedMessages.add(message.getId());

        // ── Record in gossip seen-set for anti-entropy ──
        gossipManager.recordMessage(message.getId(), message);

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
        // Try E2E decryption first (for private messages encrypted with our
        // public key), then fall back to shared-passphrase decryption.
        if (message.isEncrypted()) {
            String content = message.getContent();
            if (E2ECryptoManager.isE2EEncrypted(content)) {
                String decrypted = e2eCryptoManager.decrypt(content);
                message.setContent(decrypted != null ? decrypted : "[E2E decryption failed]");
            } else {
                message.setContent(cryptoManager.decrypt(content));
            }
            message.setEncrypted(false);
        }
        message.setType(Message.TYPE_RECEIVED);

        if (messageListener != null) {
            mainHandler.post(() -> messageListener.onMessageReceived(message));
        }
    }

    // ─── Handshake ──────────────────────────────────────────────────────

    /**
     * Sends our identity to a newly connected peer via a JSON line.
     *
     * Handshake content format (pipe-delimited, 5 fields):
     *   "username|nodeUUID|fcmToken|e2ePublicKey|networkName"
     *
     * Field 5 (networkName) lets the receiver reject mismatched network peers.
     * Legacy peers (< 5 fields) are accepted but will not have network filtering.
     */
    private void sendHandshake(BufferedWriter out) {
        String fcmToken = FcmTokenManager.getOwnToken(context);
        Message handshake = Message.createHandshake(username, myNodeId, fcmToken);
        String e2ePubKey = e2eCryptoManager.getPublicKeyBase64();
        // Sanitize username so it cannot contain the pipe delimiter
        String safeUsername = username.replace("|", "");
        String networkField = currentNetworkName != null ? currentNetworkName : "";
        handshake.setContent(safeUsername + "|" + myNodeId + "|"
                + (fcmToken != null ? fcmToken : "") + "|"
                + e2ePubKey + "|" + networkField);
        try {
            synchronized (out) {
                out.write(gson.toJson(handshake));
                out.newLine();
                out.flush();
            }
        } catch (IOException e) {
            Log.w(TAG, "Handshake send failed: " + e.getMessage());
        }
    }

    /** Processes an incoming handshake, mapping the peer's UUID to their BT MAC. */
    private void handleHandshake(Message message, String sourceMac) {
        String content = message.getContent();
        // Format: "username|nodeUUID|fcmToken|e2ePublicKey|networkName" (5 fields)
        //   or:   "username|nodeUUID|fcmToken|e2ePublicKey" (legacy 4 fields)
        //   or:   "username|nodeUUID|fcmToken" (legacy 3 fields)
        //   or:   "username|nodeUUID" (legacy 2 fields)
        String[] parts = content.split("\\|", 5);
        if (parts.length < 2) return;

        String peerUsername  = parts[0];
        String peerNodeId    = parts[1];
        String peerFcmToken  = (parts.length >= 3 && !parts[2].isEmpty()) ? parts[2] : null;
        String peerE2ePubKey = (parts.length >= 4 && !parts[3].isEmpty()) ? parts[3] : null;
        String peerNetwork   = (parts.length >= 5 && !parts[4].isEmpty()) ? parts[4] : null;

        // Network membership check: if both sides declare a network and they differ,
        // reject the connection to prevent cross-network leakage.
        if (currentNetworkName != null && peerNetwork != null
                && !peerNetwork.equalsIgnoreCase(currentNetworkName)) {
            Log.w(TAG, "Rejecting peer " + peerUsername
                    + " from different network: " + peerNetwork);
            notifyNodeDisconnected(sourceMac);
            return;
        }

        // Store bidirectional UUID ↔ MAC mapping
        nodeIdToAddress.put(peerNodeId, sourceMac);
        addressToNodeId.put(sourceMac, peerNodeId);
        nodeNames.put(peerNodeId, peerUsername);

        // Persist FCM token for this peer so we can notify them later
        if (peerFcmToken != null) {
            FcmTokenManager.savePeerToken(context, peerNodeId, peerFcmToken);
        }

        // Store peer's E2E public key for end-to-end encrypted private messages
        if (peerE2ePubKey != null) {
            e2eCryptoManager.storePeerPublicKey(peerNodeId, peerE2ePubKey);
        }

        Log.d(TAG, "Handshake from " + peerUsername + " (" + peerNodeId + ") @ " + sourceMac);
        notifyNodeInfoUpdated();

        // Fire CONNECTED progress event — the peer is now fully identified
        int rssi = rssiValues.getOrDefault(sourceMac, 0);
        String transport = wifiDirectConnectedNodes.contains(sourceMac) ? "wifi_direct" : "bluetooth";
        fireProgress(sourceMac, peerUsername, ConnectionPhase.CONNECTED, transport, rssi);

        // Notify all OTHER connected peers that a new node joined
        new FcmNotificationSender(context).notifyPeersOfNewNode(peerUsername, peerNodeId);

        // Gossip our own public key to the new peer's network region
        broadcastKeyAnnounce();

        // Gossip the new peer's public key so nodes beyond us learn it too
        if (peerE2ePubKey != null) {
            Message keyAnnounce = Message.createKeyAnnounce(
                    peerUsername, peerNodeId, peerE2ePubKey);
            processedMessages.add(keyAnnounce.getId());
            sendToAllPeers(keyAnnounce, sourceMac);
        }

        // Flush any queued private messages for this peer
        flushPerRecipientQueue(peerNodeId, sourceMac);
    }

    // ─── ACK ────────────────────────────────────────────────────────────

    private void sendAck(String originalMsgId, String originalSenderId, String sourceMac) {
        Message ack = Message.createAck(originalMsgId, myNodeId, originalSenderId);
        // Set recipientId so relay nodes can route the ACK toward the original sender.
        ack.setRecipientId(originalSenderId);

        // Try a direct hop first; if the sender isn't directly connected, flood
        // the ACK so the mesh can relay it back along any available path.
        String senderMac = nodeIdToAddress.get(originalSenderId);
        if (senderMac != null && (connectedNodes.contains(senderMac)
                || wifiDirectConnectedNodes.contains(senderMac))) {
            sendToNode(senderMac, ack);
        } else {
            sendToAllPeers(ack, sourceMac); // flood — relay nodes will forward
        }
    }

    private void handleAck(Message ack) {
        String originalMsgId = ack.getContent();
        String recipientId = ack.getRecipientId();

        // If this ACK is addressed to us, deliver it to the UI
        if (recipientId == null || recipientId.equals(myNodeId)) {
            if (messageListener != null) {
                mainHandler.post(() -> messageListener.onDeliveryStatusChanged(originalMsgId));
            }
            return;
        }

        // Otherwise relay it toward the intended recipient (multi-hop ACK forwarding)
        if (!ack.canForward()) return;
        ack.incrementHopCount();
        String recipientMac = nodeIdToAddress.get(recipientId);
        if (recipientMac != null && (connectedNodes.contains(recipientMac)
                || wifiDirectConnectedNodes.contains(recipientMac))) {
            sendToNode(recipientMac, ack);
        }
        // If no direct path, the ACK is dropped — best-effort only for relay ACKs
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

    // ─── Key Announce (E2E public key gossip) ───────────────────────────

    /**
     * Broadcasts our E2E public key to the mesh so that distant nodes
     * (multiple hops away) can learn it and send us E2E-encrypted messages.
     *
     * Called after each handshake and can be triggered manually.
     * The KEY_ANNOUNCE message is forwarded by all nodes (like a broadcast),
     * with the same dedup/TTL/hop-count rules that prevent loops.
     */
    private void broadcastKeyAnnounce() {
        String pubKey = e2eCryptoManager.getPublicKeyBase64();
        if (pubKey.isEmpty()) return;

        Message announce = Message.createKeyAnnounce(username, myNodeId, pubKey);
        processedMessages.add(announce.getId());
        sendToAllPeers(announce, null);
        Log.d(TAG, "Broadcast own E2E public key to mesh");
    }

    /**
     * Handles an incoming KEY_ANNOUNCE message.
     *
     * Stores the announced public key so we can send E2E-encrypted private
     * messages to that node, even though they may be many hops away.
     * Then forwards the announcement to other peers (gossip propagation).
     */
    private void handleKeyAnnounce(Message message, String sourceAddress) {
        // ── Deduplication ──
        if (processedMessages.contains(message.getId())) return;
        processedMessages.add(message.getId());

        // ── TTL check ──
        if (message.isExpired()) return;

        // ── Parse: "nodeUUID|publicKeyBase64" ──
        String content = message.getContent();
        String[] parts = content.split("\\|", 2);
        if (parts.length < 2) return;

        String peerNodeId = parts[0];
        String peerPubKey = parts[1];

        // Store the peer's E2E public key
        e2eCryptoManager.storePeerPublicKey(peerNodeId, peerPubKey);

        // ── Forward (gossip propagation) ──
        if (message.canForward()) {
            message.incrementHopCount();
            sendToAllPeers(message, sourceAddress);
        }

        Log.d(TAG, "KEY_ANNOUNCE from " + message.getSenderName()
                + " (" + peerNodeId + "), hop " + message.getHopCount());
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

        // Attempt delivery of any Room-persisted messages for this peer
        storeAndForwardManager.attemptDelivery(mac);

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
        BufferedWriter out = bluetoothOutputStreams.remove(mac);
        if (out != null) { try { out.close(); } catch (IOException ignored) {} }

        BluetoothSocket sock = bluetoothSockets.remove(mac);
        if (sock != null) { try { sock.close(); } catch (IOException ignored) {} }

        // WiFi Direct cleanup (address may be an IP string)
        BufferedWriter wdOut = wifiDirectOutputStreams.remove(mac);
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
        // Cancel any pending discovery notification if the listener is detached
        if (listener == null && discoveryNotifyRunnable != null) {
            mainHandler.removeCallbacks(discoveryNotifyRunnable);
            discoveryNotifyRunnable = null;
        }
    }

    public void setConnectionProgressListener(ConnectionProgressListener listener) {
        this.connectionProgressListener = listener;
    }

    /**
     * Dispatches a connection progress event on the main thread.
     * Safe to call from any thread.
     */
    private void fireProgress(String peerAddress, String peerName,
                               ConnectionPhase phase, String transport, int rssi) {
        if (connectionProgressListener == null) return;
        mainHandler.post(() -> {
            if (connectionProgressListener != null) {
                connectionProgressListener.onProgressChanged(
                        peerAddress, peerName, phase, transport, rssi);
            }
        });
    }

    public StoreAndForwardManager getStoreAndForwardManager() {
        return storeAndForwardManager;
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
    public E2ECryptoManager getE2ECryptoManager() { return e2eCryptoManager; }

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

        for (BufferedWriter out : bluetoothOutputStreams.values()) {
            try { out.close(); } catch (IOException ignored) {}
        }
        for (BluetoothSocket s : bluetoothSockets.values()) {
            try { s.close(); } catch (IOException ignored) {}
        }

        // WiFi Direct cleanup
        if (wifiDirectServerThread != null) wifiDirectServerThread.interrupt();
        for (BufferedWriter out : wifiDirectOutputStreams.values()) {
            try { out.close(); } catch (IOException ignored) {}
        }
        for (Socket s : wifiDirectSockets.values()) {
            try { s.close(); } catch (IOException ignored) {}
        }
        if (wifiP2pManager != null && wifiP2pChannel != null) {
            wifiP2pManager.removeGroup(wifiP2pChannel, null);
        }

        // BLE cleanup — stop duty-cycle handler and scanner/advertiser
        dutyCycleHandler.removeCallbacksAndMessages(null);
        if (bleAdvertiser != null) {
            bleAdvertiser.cleanup();
        }

        // Transport abstraction layer cleanup
        if (compositeTransport != null) {
            compositeTransport.cleanup();
        }

        // Store & Forward cleanup
        if (storeAndForwardManager != null) {
            storeAndForwardManager.shutdown();
        }

        // Gossip anti-entropy cleanup
        if (gossipManager != null) {
            gossipManager.shutdown();
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
     * Manages bidirectional messaging on an established Bluetooth RFCOMM connection.
     *
     * Wire format: UTF-8 JSON, one Message object per line.
     * This replaces Java object serialisation and eliminates the deserialization
     * RCE attack surface while also being human-readable for debugging.
     *
     * Progress events emitted: TRANSPORT_CONNECTING → HANDSHAKING → CONNECTED.
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
                int rssi = rssiValues.getOrDefault(mac, 0);
                fireProgress(mac, mac, ConnectionPhase.TRANSPORT_CONNECTING, "bluetooth", rssi);

                BufferedWriter out = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

                bluetoothOutputStreams.put(mac, out);

                // Send our identity immediately — the peer will do the same
                fireProgress(mac, mac, ConnectionPhase.HANDSHAKING, "bluetooth", rssi);
                sendHandshake(out);

                notifyNodeConnected(mac);

                // Read loop — blocks until a full JSON line arrives or socket closes
                String line;
                while (!isInterrupted() && (line = in.readLine()) != null) {
                    try {
                        Message received = gson.fromJson(line, Message.class);
                        if (received != null) {
                            handleIncomingMessage(received, mac);
                        }
                    } catch (Exception parseEx) {
                        Log.w(TAG, "JSON parse error from " + mac + ": " + parseEx.getMessage());
                    }
                }
            } catch (IOException e) {
                Log.d(TAG, "BT connection lost: " + mac);
            } finally {
                if (!mac.isEmpty()) {
                    fireProgress(mac, mac, ConnectionPhase.FAILED, "bluetooth", 0);
                    notifyNodeDisconnected(mac);
                }
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
     *   1. Creates a BufferedWriter (UTF-8 JSON, one message per line).
     *   2. Creates a BufferedReader to receive JSON lines from the peer.
     *   3. Sends our handshake (username + UUID + FCM token).
     *   4. Enters a read loop, dispatching incoming messages to
     *      handleIncomingMessage() with the peer's IP as sourceAddress.
     *
     * When the GO's handleIncomingMessage() receives a message from a peer,
     * it relays the message to ALL other connected nodes (excluding the source)
     * via sendToAllPeers(). This is how the GO bridges WiFi Direct peers that
     * cannot talk directly to each other.
     */
    /**
     * Manages bidirectional messaging on an established WiFi Direct TCP socket.
     *
     * Wire format: UTF-8 JSON, one Message object per line — same as
     * {@link MessageReceiverThread} for Bluetooth, keeping both transports
     * consistent and eliminating Java deserialization as an attack surface.
     *
     * Progress events emitted: TRANSPORT_CONNECTING → HANDSHAKING → CONNECTED.
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
            fireProgress(peerAddress, peerAddress,
                    ConnectionPhase.TRANSPORT_CONNECTING, "wifi_direct", 0);
            try {
                BufferedWriter out = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

                wifiDirectOutputStreams.put(peerAddress, out);

                fireProgress(peerAddress, peerAddress,
                        ConnectionPhase.HANDSHAKING, "wifi_direct", 0);
                sendHandshake(out);

                wifiDirectConnectedNodes.add(peerAddress);
                notifyNodeConnected(peerAddress);

                String line;
                while (!isInterrupted() && (line = in.readLine()) != null) {
                    try {
                        Message received = gson.fromJson(line, Message.class);
                        if (received != null) {
                            handleIncomingMessage(received, peerAddress);
                        }
                    } catch (Exception parseEx) {
                        Log.w(TAG, "JSON parse error from WD peer " + peerAddress
                                + ": " + parseEx.getMessage());
                    }
                }
            } catch (IOException e) {
                Log.d(TAG, "WiFi Direct connection lost: " + peerAddress);
            } finally {
                fireProgress(peerAddress, peerAddress,
                        ConnectionPhase.FAILED, "wifi_direct", 0);
                notifyNodeDisconnected(peerAddress);
            }
        }
    }
}
