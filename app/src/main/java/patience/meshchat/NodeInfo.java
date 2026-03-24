package patience.meshchat;

/**
 * NodeInfo — Represents a node (peer device) in the mesh network.
 *
 * Contains identity, display name, signal strength, and connection status.
 * Used by PeersListFragment and NetworkTopologyFragment to show online peers.
 */
public class NodeInfo {

    /** Persistent UUID that uniquely identifies this device across sessions */
    public final String nodeId;

    /** Bluetooth MAC address (used internally for routing) */
    public final String address;

    /** User's chosen display name */
    public final String username;

    /**
     * Last known RSSI (Received Signal Strength Indicator) in dBm.
     * Typical range: -30 (excellent) to -100 (very weak).
     * Integer.MIN_VALUE means unknown.
     */
    public final int rssi;

    /** Whether this node is currently directly connected to us via Bluetooth */
    public final boolean isDirectlyConnected;

    public NodeInfo(String nodeId, String address, String username,
                    int rssi, boolean isDirectlyConnected) {
        this.nodeId = nodeId;
        this.address = address;
        this.username = username;
        this.rssi = rssi;
        this.isDirectlyConnected = isDirectlyConnected;
    }

    /** Human-readable signal quality label */
    public String getSignalLabel() {
        if (rssi == Integer.MIN_VALUE) return "Unknown";
        if (rssi >= -60) return "Excellent";
        if (rssi >= -70) return "Good";
        if (rssi >= -80) return "Fair";
        return "Weak";
    }

    /**
     * Number of signal bars (0–4) for display.
     * 4 = excellent, 0 = no signal / unknown.
     */
    public int getSignalBars() {
        if (rssi >= -60) return 4;
        if (rssi >= -70) return 3;
        if (rssi >= -80) return 2;
        if (rssi > Integer.MIN_VALUE) return 1;
        return 0;
    }

    /** First letter of username, upper-cased — used for avatar circles */
    public String getInitial() {
        if (username == null || username.isEmpty()) return "?";
        return String.valueOf(username.charAt(0)).toUpperCase();
    }
}
