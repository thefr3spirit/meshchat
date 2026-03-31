package patience.meshchat.transport;

/**
 * ============================================================================
 * MeshTransport — Interface Segregation for Transport-Agnostic Messaging
 * ============================================================================
 *
 * DESIGN PROBLEM:
 * ───────────────
 * MeshChat currently supports three radio technologies:
 *   - Bluetooth Classic RFCOMM (reliable, medium range)
 *   - WiFi Direct / P2P TCP (high bandwidth, requires group formation)
 *   - BLE advertising/scanning (low power, discovery only)
 *
 * Without abstraction, the routing logic in MeshManager is tightly coupled
 * to each transport's API (BluetoothSocket, WifiP2pManager, BLE GATT, etc.).
 * Adding a new transport (e.g. NFC, LoRa) would require changes throughout
 * the routing code.
 *
 * SOLUTION — Interface Segregation:
 * ──────────────────────────────────
 * Define a single MeshTransport interface that every transport implements.
 * The routing layer (MeshManager) only depends on this interface, never on
 * concrete transport classes. New transports can be added by implementing
 * MeshTransport and registering via Hilt dependency injection.
 *
 * The interface exposes three core operations:
 *   1. send(peerId, data)      — transmit bytes to a specific peer
 *   2. discover(callback)      — scan for nearby peers
 *   3. listen(callback)        — accept incoming connections and data
 *
 * KEY PRINCIPLE: Interface Segregation (SOLID "I")
 * ─────────────────────────────────────────────────
 * Each transport only needs to implement what it can do. A BLE transport
 * might only support discovery (not direct send), while NFC might only
 * support short-range send. The CompositeTransport handles fallback logic.
 *
 * ============================================================================
 */
public interface MeshTransport {

    // ─── Sending ────────────────────────────────────────────────────────

    /**
     * Sends raw bytes to a specific peer.
     *
     * @param peerId The target peer's identifier (MAC address, IP, etc.)
     * @param data   The serialised message bytes to transmit
     * @return true if the data was successfully written to the transport
     */
    boolean send(String peerId, byte[] data);

    // ─── Discovery ──────────────────────────────────────────────────────

    /**
     * Starts scanning for nearby peers over this transport.
     *
     * Discovered peers are reported via the DiscoveryCallback.
     * Implementations should de-duplicate discoveries internally.
     *
     * @param callback Receives peer discovery events
     */
    void discover(DiscoveryCallback callback);

    /**
     * Stops any ongoing peer discovery.
     */
    void stopDiscovery();

    // ─── Listening ──────────────────────────────────────────────────────

    /**
     * Starts listening for incoming connections and data from peers.
     *
     * When data arrives from a peer, it is delivered via the
     * TransportCallback. Implementations manage their own accept
     * threads or GATT server registrations internally.
     *
     * @param callback Receives incoming data and connection events
     */
    void listen(TransportCallback callback);

    /**
     * Stops listening for incoming connections.
     */
    void stopListening();

    // ─── Lifecycle ──────────────────────────────────────────────────────

    /**
     * Returns whether this transport is currently available on the device.
     * For example, BLE returns false if the device lacks BLE hardware,
     * NFC returns false if NFC is disabled in system settings.
     */
    boolean isAvailable();

    /**
     * Returns a human-readable name for this transport (e.g. "WiFi Direct",
     * "BLE", "NFC"). Used for logging and debugging.
     */
    String getName();

    /**
     * Releases all resources held by this transport.
     * Called during service shutdown.
     */
    void cleanup();

    // ═══════════════════════════════════════════════════════════════════
    // CALLBACK INTERFACES
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Callback for peer discovery events.
     *
     * Implementations report each newly discovered peer with an identifier
     * and signal strength (if available).
     */
    interface DiscoveryCallback {
        /**
         * Called when a new peer is discovered over this transport.
         *
         * @param peerId        Peer identifier (MAC, IP, etc.)
         * @param displayName   Human-readable name (if available, else null)
         * @param rssi          Signal strength in dBm (-127 if unavailable)
         * @param transportName Which transport found this peer
         */
        void onPeerDiscovered(String peerId, String displayName,
                              int rssi, String transportName);

        /** Called when a previously discovered peer is no longer reachable */
        void onPeerLost(String peerId, String transportName);
    }

    /**
     * Callback for incoming data from connected peers.
     *
     * The routing layer registers a single TransportCallback on the
     * CompositeTransport and receives data from ALL transports through
     * this single funnel — it never needs to know which radio delivered it.
     */
    interface TransportCallback {
        /**
         * Called when raw bytes arrive from a connected peer.
         *
         * @param peerId        The sender's identifier
         * @param data          The received bytes (serialised Message)
         * @param transportName Which transport delivered this data
         */
        void onDataReceived(String peerId, byte[] data, String transportName);

        /**
         * Called when a new peer connection is established.
         *
         * @param peerId        The connected peer's identifier
         * @param transportName Which transport established the connection
         */
        void onPeerConnected(String peerId, String transportName);

        /**
         * Called when a peer connection is lost.
         *
         * @param peerId        The disconnected peer's identifier
         * @param transportName Which transport lost the connection
         */
        void onPeerDisconnected(String peerId, String transportName);
    }
}
