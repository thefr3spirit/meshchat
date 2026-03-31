package patience.meshchat.transport;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * ============================================================================
 * WifiDirectTransport — WiFi Direct (P2P) Implementation of MeshTransport
 * ============================================================================
 *
 * HOW WIFI DIRECT TRANSPORT WORKS:
 * ─────────────────────────────────
 * WiFi Direct forms a peer-to-peer group where one device becomes the
 * Group Owner (GO) and the others become clients. The GO runs a TCP
 * server and all clients connect to it. Messages are relayed through
 * the GO to reach other peers in the group.
 *
 * KEY APIs:
 *   - WifiP2pManager: Android's WiFi Direct framework API
 *   - WifiP2pManager.Channel: communication channel with the WiFi Direct framework
 *   - WifiP2pConfig: connection configuration for peer-to-peer connections
 *   - ServerSocket/Socket: TCP transport over the WiFi Direct link
 *
 * INTERFACE SEGREGATION:
 * ──────────────────────
 * This class implements MeshTransport, meaning the routing layer in
 * MeshManager can send(), discover(), and listen() without knowing
 * anything about WifiP2pManager or TCP sockets.
 *
 * ============================================================================
 */
@Singleton
public class WifiDirectTransport implements MeshTransport {

    private static final String TAG = "WifiDirectTransport";
    private static final int PORT = 8988;

    private final Context context;
    private WifiP2pManager wifiP2pManager;
    private WifiP2pManager.Channel channel;

    // ─── Connection state ───────────────────────────────────────────────

    private final Map<String, Socket> connectedSockets = new ConcurrentHashMap<>();
    private final Map<String, OutputStream> outputStreams = new ConcurrentHashMap<>();
    private volatile boolean isGroupOwner = false;
    private volatile InetAddress groupOwnerAddress;
    private volatile ServerSocket serverSocket;
    private volatile boolean listening = false;

    private TransportCallback transportCallback;
    private DiscoveryCallback discoveryCallback;
    private BroadcastReceiver wifiDirectReceiver;

    // ═══════════════════════════════════════════════════════════════════
    // CONSTRUCTOR (Hilt-injected)
    // ═══════════════════════════════════════════════════════════════════

    @Inject
    public WifiDirectTransport(@ApplicationContext Context context) {
        this.context = context;
        wifiP2pManager = (WifiP2pManager)
                context.getSystemService(Context.WIFI_P2P_SERVICE);
        if (wifiP2pManager != null) {
            channel = wifiP2pManager.initialize(context, Looper.getMainLooper(), null);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // MeshTransport — send()
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public boolean send(String peerId, byte[] data) {
        OutputStream out = outputStreams.get(peerId);
        if (out == null) return false;
        try {
            synchronized (out) {
                // Length-prefixed framing: 4 bytes length + payload
                out.write(intToBytes(data.length));
                out.write(data);
                out.flush();
            }
            return true;
        } catch (IOException e) {
            Log.w(TAG, "Send to " + peerId + " failed: " + e.getMessage());
            handleDisconnection(peerId);
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // MeshTransport — discover()
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void discover(DiscoveryCallback callback) {
        this.discoveryCallback = callback;
        if (wifiP2pManager == null || channel == null) return;
        if (!hasPermission(Manifest.permission.NEARBY_WIFI_DEVICES)) return;

        // Register broadcast receiver for WiFi Direct events
        registerReceiver();

        try {
            wifiP2pManager.discoverPeers(channel,
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

    @Override
    public void stopDiscovery() {
        if (wifiP2pManager != null && channel != null) {
            try {
                wifiP2pManager.stopPeerDiscovery(channel, null);
            } catch (SecurityException e) {
                Log.w(TAG, "Stop discovery permission denied: " + e.getMessage());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // MeshTransport — listen()
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void listen(TransportCallback callback) {
        this.transportCallback = callback;
        registerReceiver();
        // The TCP server is started dynamically when we become Group Owner
        // (see onConnectionInfoAvailable)
    }

    @Override
    public void stopListening() {
        listening = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                Log.w(TAG, "Error closing server socket: " + e.getMessage());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // MeshTransport — lifecycle
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public boolean isAvailable() {
        return wifiP2pManager != null && channel != null
                && hasPermission(Manifest.permission.NEARBY_WIFI_DEVICES);
    }

    @Override
    public String getName() {
        return "WiFi Direct";
    }

    @Override
    public void cleanup() {
        stopDiscovery();
        stopListening();
        unregisterReceiver();

        for (Socket socket : connectedSockets.values()) {
            try { socket.close(); } catch (IOException ignored) { }
        }
        connectedSockets.clear();
        outputStreams.clear();

        if (wifiP2pManager != null && channel != null) {
            try {
                wifiP2pManager.removeGroup(channel, null);
            } catch (Exception ignored) { }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // WiFi Direct Event Handling
    // ═══════════════════════════════════════════════════════════════════

    private void registerReceiver() {
        if (wifiDirectReceiver != null) return; // already registered

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);

        wifiDirectReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                    requestPeerList();
                } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                    requestConnectionInfo();
                }
            }
        };

        context.registerReceiver(wifiDirectReceiver, filter,
                Context.RECEIVER_NOT_EXPORTED);
    }

    private void unregisterReceiver() {
        if (wifiDirectReceiver != null) {
            try {
                context.unregisterReceiver(wifiDirectReceiver);
            } catch (IllegalArgumentException ignored) { }
            wifiDirectReceiver = null;
        }
    }

    private void requestPeerList() {
        if (wifiP2pManager == null || channel == null) return;
        try {
            wifiP2pManager.requestPeers(channel, peers -> {
                if (discoveryCallback == null) return;
                for (WifiP2pDevice device : peers.getDeviceList()) {
                    discoveryCallback.onPeerDiscovered(
                            device.deviceAddress,
                            device.deviceName,
                            -127, // WiFi Direct doesn't expose RSSI
                            getName());
                }
            });
        } catch (SecurityException e) {
            Log.w(TAG, "Request peers permission denied: " + e.getMessage());
        }
    }

    private void requestConnectionInfo() {
        if (wifiP2pManager == null || channel == null) return;
        try {
            wifiP2pManager.requestConnectionInfo(channel, this::onConnectionInfoAvailable);
        } catch (SecurityException e) {
            Log.w(TAG, "Request connection info permission denied: " + e.getMessage());
        }
    }

    /**
     * Called when WiFi Direct group formation completes.
     * Starts TCP server (if GO) or connects to GO (if peer).
     */
    private void onConnectionInfoAvailable(WifiP2pInfo info) {
        if (info == null || !info.groupFormed) return;

        groupOwnerAddress = info.groupOwnerAddress;
        isGroupOwner = info.isGroupOwner;

        if (isGroupOwner) {
            // Start TCP server to accept peer connections
            startServer();
        } else if (groupOwnerAddress != null) {
            // Connect to the Group Owner's TCP server
            String goIp = groupOwnerAddress.getHostAddress();
            if (!connectedSockets.containsKey(goIp)) {
                connectToGo(groupOwnerAddress);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // TCP Server & Client
    // ═══════════════════════════════════════════════════════════════════

    private void startServer() {
        if (listening) return;
        listening = true;

        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                serverSocket.setReuseAddress(true);
                Log.d(TAG, "TCP server listening on port " + PORT);

                while (listening) {
                    Socket client = serverSocket.accept();
                    String peerIp = client.getInetAddress().getHostAddress();
                    setupConnection(client, peerIp);
                }
            } catch (IOException e) {
                if (listening) {
                    Log.e(TAG, "TCP server error: " + e.getMessage());
                }
            }
        }, "WD-Server").start();
    }

    private void connectToGo(InetAddress goAddress) {
        new Thread(() -> {
            String goIp = goAddress.getHostAddress();
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(goAddress, PORT), 10_000);
                setupConnection(socket, goIp);
            } catch (IOException e) {
                Log.e(TAG, "Connect to GO failed: " + e.getMessage());
                try { socket.close(); } catch (IOException ignored) { }
            }
        }, "WD-Client-" + goAddress.getHostAddress()).start();
    }

    private void setupConnection(Socket socket, String peerId) {
        try {
            OutputStream out = socket.getOutputStream();
            connectedSockets.put(peerId, socket);
            outputStreams.put(peerId, out);

            if (transportCallback != null) {
                transportCallback.onPeerConnected(peerId, getName());
            }

            // Start receiving data on a background thread
            startReceiving(socket, peerId);

        } catch (IOException e) {
            Log.e(TAG, "Setup connection failed for " + peerId + ": " + e.getMessage());
            handleDisconnection(peerId);
        }
    }

    private void startReceiving(Socket socket, String peerId) {
        new Thread(() -> {
            try {
                InputStream in = socket.getInputStream();
                byte[] lenBuf = new byte[4];

                while (!socket.isClosed()) {
                    // Read length-prefixed frame
                    if (readFully(in, lenBuf) < 0) break;
                    int len = bytesToInt(lenBuf);
                    if (len <= 0 || len > 1_000_000) break; // sanity check

                    byte[] data = new byte[len];
                    if (readFully(in, data) < 0) break;

                    if (transportCallback != null) {
                        transportCallback.onDataReceived(peerId, data, getName());
                    }
                }
            } catch (IOException e) {
                Log.d(TAG, "Connection lost: " + peerId);
            } finally {
                handleDisconnection(peerId);
            }
        }, "WD-Receiver-" + peerId).start();
    }

    private void handleDisconnection(String peerId) {
        outputStreams.remove(peerId);
        Socket socket = connectedSockets.remove(peerId);
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) { }
        }
        if (transportCallback != null) {
            transportCallback.onPeerDisconnected(peerId, getName());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Utilities
    // ═══════════════════════════════════════════════════════════════════

    /** Connect to a discovered WiFi Direct peer (used by CompositeTransport) */
    public void connectToPeer(String deviceAddress) {
        if (wifiP2pManager == null || channel == null) return;
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = deviceAddress;
        try {
            wifiP2pManager.connect(channel, config,
                    new WifiP2pManager.ActionListener() {
                        @Override
                        public void onSuccess() {
                            Log.d(TAG, "WiFi Direct connect initiated to " + deviceAddress);
                        }

                        @Override
                        public void onFailure(int reason) {
                            Log.w(TAG, "WiFi Direct connect failed: reason=" + reason);
                        }
                    });
        } catch (SecurityException e) {
            Log.w(TAG, "Connect permission denied: " + e.getMessage());
        }
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(context, permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static byte[] intToBytes(int value) {
        return new byte[]{
                (byte) (value >> 24), (byte) (value >> 16),
                (byte) (value >> 8), (byte) value
        };
    }

    private static int bytesToInt(byte[] bytes) {
        return ((bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16)
                | ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int offset = 0;
        while (offset < buf.length) {
            int read = in.read(buf, offset, buf.length - offset);
            if (read < 0) return -1;
            offset += read;
        }
        return offset;
    }
}
