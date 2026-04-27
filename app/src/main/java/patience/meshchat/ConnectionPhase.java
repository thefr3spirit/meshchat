package patience.meshchat;

/**
 * Represents the discrete stages of establishing a mesh peer connection.
 *
 * Emitted by MeshManager via ConnectionProgressListener so the UI can
 * show a live step-by-step progress indicator to the user.
 *
 * FLOW:
 *   IDLE → SCANNING → PEER_DETECTED → TRANSPORT_CONNECTING
 *        → HANDSHAKING → CONNECTED
 *
 * FAILED can be reached from any non-IDLE state.
 */
public enum ConnectionPhase {

    /** No connection attempt is in progress */
    IDLE,

    /** BT Classic / WiFi Direct / BLE scan has started */
    SCANNING,

    /**
     * A device advertising the target network name has been found.
     * The peerName and rssi fields are populated at this point.
     */
    PEER_DETECTED,

    /**
     * An RFCOMM / TCP socket connection is being established.
     * Radio link is being negotiated.
     */
    TRANSPORT_CONNECTING,

    /**
     * Socket is open; identity exchange (username, UUID, public key,
     * network name) is in progress.
     */
    HANDSHAKING,

    /**
     * Handshake is complete; the peer is fully authenticated and
     * the connection is ready for messaging.
     */
    CONNECTED,

    /** The connection attempt failed (timeout, rejection, or radio error). */
    FAILED
}
