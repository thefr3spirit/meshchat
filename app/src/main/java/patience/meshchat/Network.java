package patience.meshchat;

/**
 * Network — Represents a discovered mesh network.
 *
 * MeshChat devices advertise themselves with a Bluetooth name in the format
 * "MC_<networkName>". When scanning, we collect unique network names and
 * present them to the user in NetworkDiscoveryFragment.
 */
public class Network {

    /** The human-readable name entered by the network creator */
    public final String name;

    /** Number of devices found advertising this network name */
    public int nodeCount;

    /** Timestamp when this network was first discovered (ms since epoch) */
    public long discoveredAt;

    public Network(String name) {
        this.name = name;
        this.nodeCount = 1;
        this.discoveredAt = System.currentTimeMillis();
    }

    /** Friendly description of how many devices are visible */
    public String getNodeCountText() {
        return nodeCount == 1 ? "1 device nearby" : nodeCount + " devices nearby";
    }
}
