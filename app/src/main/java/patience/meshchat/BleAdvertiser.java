package patience.meshchat;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ============================================================================
 * BleAdvertiser — BLE-based Peer Discovery for Dead Zones
 * ============================================================================
 *
 * PROBLEM:
 * ────────
 * In a "dead zone" (no WiFi AP, no cellular), standard WiFi Direct peer
 * discovery requires both devices to actively scan at the same time, which
 * is unreliable and power-hungry. Bluetooth Classic discovery has a 12s
 * inquiry window and requires explicit discoverability mode.
 *
 * SOLUTION — BLE Advertising:
 * ───────────────────────────
 * BLE (Bluetooth Low Energy) advertising is a one-to-many broadcast
 * mechanism that works WITHOUT pairing and WITHOUT an internet connection.
 * It's the same technology used by iBeacons and contact tracing apps.
 *
 * HOW IT WORKS:
 * ─────────────
 *   1. ADVERTISING: Each MeshChat device broadcasts a BLE advertisement
 *      containing:
 *        - A custom 128-bit SERVICE UUID that identifies MeshChat nodes
 *        - MANUFACTURER DATA payload containing:
 *          • WiFi Direct MAC address (6 bytes) — so scanners know WHERE
 *            to send a WiFi Direct connection request
 *          • Mesh Node ID (truncated UUID, 8 bytes) — so scanners can
 *            identify WHO this peer is before connecting
 *
 *   2. SCANNING: Nearby MeshChat devices scan for advertisements matching
 *      the MeshChat service UUID (using a ScanFilter for efficiency).
 *      When a matching advertisement is found:
 *        - Extract the WiFi Direct MAC address from manufacturer data
 *        - Extract the mesh node ID
 *        - If not already connected, initiate a WiFi Direct connection
 *          to the extracted MAC address
 *
 *   3. WIFI DIRECT HANDOFF: Once the WiFi Direct connection is established,
 *      the normal TCP messaging layer takes over (WifiDirectServerThread /
 *      WifiDirectClientThread / WifiDirectReceiverThread in MeshManager).
 *
 * WHY BLE + WIFI DIRECT (not BLE alone)?
 * ──────────────────────────────────────
 * BLE has very limited throughput (~236 bytes/packet in BLE 4.2).
 * WiFi Direct provides high-bandwidth TCP connections (hundreds of Mbps).
 * So we use BLE ONLY for discovery (finding nearby peers and their
 * WiFi Direct addresses), then hand off to WiFi Direct for actual
 * messaging. This gives us the best of both worlds:
 *   - BLE: low-power, always-on, works in dead zones
 *   - WiFi Direct: high-bandwidth, reliable TCP data channel
 *
 * MANUFACTURER DATA FORMAT (14 bytes total):
 * ──────────────────────────────────────────
 *   Bytes 0–5:   WiFi Direct MAC address (e.g. AA:BB:CC:DD:EE:FF)
 *   Bytes 6–13:  Mesh Node ID (first 8 bytes of the node's UUID)
 *
 * The manufacturer data uses company ID 0xFFFF (reserved for testing/
 * development in the Bluetooth SIG specification).
 *
 * KEY APIs:
 * ─────────
 *   - BluetoothLeAdvertiser: broadcasts our presence via BLE advertisements
 *   - AdvertiseData: contains the service UUID and manufacturer data payload
 *   - AdvertiseSettings: controls power, mode, and timeout
 *   - BluetoothLeScanner: discovers nearby BLE advertisers
 *   - ScanFilter: filters scan results to only MeshChat service UUID
 *   - ScanSettings: controls scan mode (low power vs low latency)
 *   - ManufacturerData: custom payload embedded in the advertisement
 *
 * ============================================================================
 */
public class BleAdvertiser {

    private static final String TAG = "BleAdvertiser";

    // ─── BLE Service UUID ───────────────────────────────────────────────

    /**
     * Custom 128-bit service UUID that uniquely identifies MeshChat nodes.
     * All MeshChat devices advertise and scan for this UUID.
     * This is different from the RFCOMM UUID (MY_UUID in MeshManager) —
     * this one is only used for BLE advertisement discovery.
     */
    private static final UUID MESHCHAT_BLE_SERVICE_UUID =
            UUID.fromString("d7e5f010-bead-4e9a-b9f5-0c6a8e3f1b2c");

    private static final ParcelUuid SERVICE_PARCEL_UUID =
            new ParcelUuid(MESHCHAT_BLE_SERVICE_UUID);

    // ─── Manufacturer data ──────────────────────────────────────────────

    /**
     * Bluetooth SIG company ID for testing/development.
     * In a production app, you'd register your own company ID with the
     * Bluetooth SIG. 0xFFFF is reserved for internal use and testing.
     */
    private static final int MANUFACTURER_ID = 0xFFFF;

    /** Total manufacturer data payload: 6 (MAC) + 8 (node ID) = 14 bytes */
    private static final int PAYLOAD_LENGTH = 14;

    // ─── State ──────────────────────────────────────────────────────────

    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private boolean isAdvertising = false;
    private boolean isScanning = false;

    /**
     * Callback interface for notifying MeshManager when a BLE scan
     * discovers a new MeshChat peer with its WiFi Direct address.
     */
    public interface BleDiscoveryListener {
        /**
         * Called when a MeshChat peer is discovered via BLE.
         *
         * @param wifiDirectMac The peer's WiFi Direct MAC address
         *                      (use with WifiP2pManager.connect())
         * @param nodeIdPrefix  First 8 bytes of the peer's mesh node UUID
         * @param rssi          BLE signal strength in dBm
         */
        void onPeerDiscoveredViaBle(String wifiDirectMac, String nodeIdPrefix, int rssi);
    }

    private BleDiscoveryListener listener;

    /**
     * Tracks WiFi Direct MACs we've already reported to avoid flooding
     * the listener with duplicate discoveries during a single scan cycle.
     */
    private final Map<String, Long> recentlyDiscovered = new ConcurrentHashMap<>();

    /** Suppress duplicate discoveries within this window */
    private static final long DISCOVERY_DEDUP_MS = 30_000; // 30 seconds

    // ═══════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════

    public BleAdvertiser(Context context, BluetoothAdapter adapter) {
        this.context = context;
        this.bluetoothAdapter = adapter;

        if (adapter != null) {
            this.advertiser = adapter.getBluetoothLeAdvertiser();
            this.scanner = adapter.getBluetoothLeScanner();

            if (advertiser == null) {
                Log.w(TAG, "BLE advertising not supported on this device");
            }
            if (scanner == null) {
                Log.w(TAG, "BLE scanning not supported on this device");
            }
        }
    }

    public void setListener(BleDiscoveryListener listener) {
        this.listener = listener;
    }

    // ═══════════════════════════════════════════════════════════════════
    // BLE ADVERTISING
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Starts BLE advertising with our WiFi Direct MAC address and mesh
     * node ID embedded in the manufacturer data payload.
     *
     * The advertisement is configured for:
     *   - LOW_POWER mode: balances discoverability with battery life
     *   - Connectable: false (we only use BLE for discovery, not data transfer)
     *   - Timeout: 0 (advertise indefinitely until stopAdvertising() is called)
     *
     * @param wifiDirectMac Our WiFi Direct MAC address (from WifiP2pDevice)
     * @param nodeId        Our mesh network node UUID
     */
    public void startAdvertising(String wifiDirectMac, String nodeId) {
        if (advertiser == null) {
            Log.w(TAG, "Cannot advertise: BluetoothLeAdvertiser not available");
            return;
        }
        if (isAdvertising) {
            Log.d(TAG, "Already advertising, skipping");
            return;
        }
        if (!hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
            Log.w(TAG, "BLUETOOTH_ADVERTISE permission not granted");
            return;
        }

        try {
            // ── Build advertisement settings ────────────────────────────────
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                    .setConnectable(false) // BLE is discovery-only; WiFi Direct carries data
                    .setTimeout(0)         // Advertise indefinitely
                    .build();

            // ── Build manufacturer data payload ─────────────────────────────
            byte[] payload = buildManufacturerData(wifiDirectMac, nodeId);

            // ── Build advertisement data ────────────────────────────────────
            AdvertiseData data = new AdvertiseData.Builder()
                    .addServiceUuid(SERVICE_PARCEL_UUID)
                    .addManufacturerData(MANUFACTURER_ID, payload)
                    .setIncludeDeviceName(false)  // Save space in the 31-byte ad
                    .setIncludeTxPowerLevel(false) // Save space
                    .build();

            advertiser.startAdvertising(settings, data, advertiseCallback);
            Log.d(TAG, "BLE advertising started (WiFi Direct MAC: " + wifiDirectMac + ")");

        } catch (SecurityException e) {
            Log.e(TAG, "BLE advertise permission denied: " + e.getMessage());
        }
    }

    /**
     * Stops BLE advertising.
     */
    public void stopAdvertising() {
        if (advertiser != null && isAdvertising) {
            try {
                advertiser.stopAdvertising(advertiseCallback);
                isAdvertising = false;
                Log.d(TAG, "BLE advertising stopped");
            } catch (SecurityException e) {
                Log.w(TAG, "Stop advertising permission denied: " + e.getMessage());
            }
        }
    }

    /**
     * Callback for BLE advertising lifecycle events.
     */
    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            isAdvertising = true;
            Log.d(TAG, "BLE advertising started successfully");
        }

        @Override
        public void onStartFailure(int errorCode) {
            isAdvertising = false;
            String reason;
            switch (errorCode) {
                case ADVERTISE_FAILED_DATA_TOO_LARGE:
                    reason = "data too large";
                    break;
                case ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                    reason = "too many advertisers";
                    break;
                case ADVERTISE_FAILED_ALREADY_STARTED:
                    reason = "already started";
                    isAdvertising = true;
                    break;
                case ADVERTISE_FAILED_INTERNAL_ERROR:
                    reason = "internal error";
                    break;
                case ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                    reason = "feature unsupported";
                    break;
                default:
                    reason = "unknown (" + errorCode + ")";
            }
            Log.e(TAG, "BLE advertising failed: " + reason);
        }
    };

    // ═══════════════════════════════════════════════════════════════════
    // BLE SCANNING
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Starts scanning for nearby MeshChat BLE advertisements.
     *
     * Uses a ScanFilter to match ONLY advertisements containing our
     * custom service UUID, which dramatically reduces battery usage
     * compared to unfiltered scanning (the Bluetooth chipset can filter
     * in hardware on most devices).
     *
     * Default scan mode is LOW_POWER — balances battery life against
     * discovery latency. Use startScanning(int) to override.
     */
    public void startScanning() {
        startScanning(ScanSettings.SCAN_MODE_LOW_POWER);
    }

    /**
     * Starts BLE scanning with a specific scan mode.
     *
     * SCAN MODES (battery vs responsiveness trade-off):
     *   - SCAN_MODE_LOW_POWER:   ~5s latency, minimal battery drain.
     *     Use when the app is in the BACKGROUND.  (duty-cycle ~0.5%)
     *   - SCAN_MODE_LOW_LATENCY: ~0.5s latency, highest battery drain.
     *     Use when the app is in the FOREGROUND.  (continuous scan)
     *   - SCAN_MODE_BALANCED:    ~2–3s latency, moderate drain.
     *
     * @param scanMode One of ScanSettings.SCAN_MODE_* constants
     */
    public void startScanning(int scanMode) {
        if (scanner == null) {
            Log.w(TAG, "Cannot scan: BluetoothLeScanner not available");
            return;
        }
        if (isScanning) {
            // If already scanning, stop and restart with the new mode
            stopScanning();
        }
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            Log.w(TAG, "BLUETOOTH_SCAN permission not granted");
            return;
        }

        try {
            // ── ScanFilter: only match MeshChat service UUID ────────────────
            ScanFilter filter = new ScanFilter.Builder()
                    .setServiceUuid(SERVICE_PARCEL_UUID)
                    .build();
            List<ScanFilter> filters = new ArrayList<>();
            filters.add(filter);

            // ── ScanSettings: mode determined by caller ─────────────────────
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(scanMode)
                    .setReportDelay(0) // Report results immediately
                    .build();

            scanner.startScan(filters, settings, scanCallback);
            isScanning = true;

            String modeName;
            switch (scanMode) {
                case ScanSettings.SCAN_MODE_LOW_LATENCY: modeName = "LOW_LATENCY"; break;
                case ScanSettings.SCAN_MODE_BALANCED:    modeName = "BALANCED";     break;
                default:                                 modeName = "LOW_POWER";    break;
            }
            Log.d(TAG, "BLE scanning started (mode=" + modeName + ")");

        } catch (SecurityException e) {
            Log.e(TAG, "BLE scan permission denied: " + e.getMessage());
        }
    }

    /**
     * Stops BLE scanning.
     */
    public void stopScanning() {
        if (scanner != null && isScanning) {
            try {
                scanner.stopScan(scanCallback);
                isScanning = false;
                Log.d(TAG, "BLE scanning stopped");
            } catch (SecurityException e) {
                Log.w(TAG, "Stop scan permission denied: " + e.getMessage());
            }
        }
    }

    /**
     * Callback for BLE scan results.
     *
     * When a MeshChat advertisement is detected:
     *   1. Extract the manufacturer data from the ScanRecord
     *   2. Parse the WiFi Direct MAC address (bytes 0–5)
     *   3. Parse the mesh node ID prefix (bytes 6–13)
     *   4. Notify the listener (MeshManager) so it can initiate a
     *      WiFi Direct connection to the discovered peer
     */
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            handleScanResult(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) {
                handleScanResult(result);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            isScanning = false;
            Log.e(TAG, "BLE scan failed: errorCode=" + errorCode);
        }
    };

    /**
     * Processes a single BLE scan result.
     *
     * Extracts the WiFi Direct MAC and mesh node ID from the manufacturer
     * data, deduplicates, and notifies the listener.
     */
    private void handleScanResult(ScanResult result) {
        ScanRecord record = result.getScanRecord();
        if (record == null) return;

        byte[] mfgData = record.getManufacturerSpecificData(MANUFACTURER_ID);
        if (mfgData == null || mfgData.length < PAYLOAD_LENGTH) return;

        // ── Parse WiFi Direct MAC address (bytes 0–5) ───────────────────
        String wifiDirectMac = parseMacAddress(mfgData, 0);

        // ── Parse mesh node ID prefix (bytes 6–13) ──────────────────────
        String nodeIdPrefix = parseNodeIdPrefix(mfgData, 6);

        // ── Dedup: suppress repeated discoveries within 30s ─────────────
        long now = System.currentTimeMillis();
        Long lastSeen = recentlyDiscovered.get(wifiDirectMac);
        if (lastSeen != null && (now - lastSeen) < DISCOVERY_DEDUP_MS) return;
        recentlyDiscovered.put(wifiDirectMac, now);

        int rssi = result.getRssi();
        Log.d(TAG, "BLE discovered MeshChat peer: WiFi Direct MAC=" + wifiDirectMac
                + " nodeId=" + nodeIdPrefix + " RSSI=" + rssi);

        // ── Notify MeshManager to initiate WiFi Direct connection ───────
        if (listener != null) {
            listener.onPeerDiscoveredViaBle(wifiDirectMac, nodeIdPrefix, rssi);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // MANUFACTURER DATA ENCODING / DECODING
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Builds the 14-byte manufacturer data payload.
     *
     * Layout:
     *   [0..5]  WiFi Direct MAC address (6 bytes)
     *   [6..13] First 8 bytes of the mesh node UUID
     *
     * @param wifiDirectMac  MAC address string "AA:BB:CC:DD:EE:FF"
     * @param nodeId         Full UUID string of the mesh node
     * @return 14-byte payload
     */
    static byte[] buildManufacturerData(String wifiDirectMac, String nodeId) {
        byte[] payload = new byte[PAYLOAD_LENGTH];

        // ── Encode WiFi Direct MAC address ──────────────────────────────
        byte[] macBytes = macStringToBytes(wifiDirectMac);
        System.arraycopy(macBytes, 0, payload, 0, 6);

        // ── Encode node ID (first 8 bytes of UUID) ─────────────────────
        UUID uuid = UUID.fromString(nodeId);
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        System.arraycopy(bb.array(), 0, payload, 6, 8);

        return payload;
    }

    /**
     * Converts a MAC address string "AA:BB:CC:DD:EE:FF" to 6 bytes.
     */
    private static byte[] macStringToBytes(String mac) {
        byte[] bytes = new byte[6];
        if (mac == null || mac.isEmpty()) return bytes;
        String[] parts = mac.split(":");
        for (int i = 0; i < Math.min(parts.length, 6); i++) {
            bytes[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return bytes;
    }

    /**
     * Parses a 6-byte MAC address from a byte array at the given offset.
     * Returns a string in "AA:BB:CC:DD:EE:FF" format.
     */
    private static String parseMacAddress(byte[] data, int offset) {
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 6; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02x", data[offset + i] & 0xFF));
        }
        return sb.toString();
    }

    /**
     * Parses the 8-byte node ID prefix from a byte array and returns
     * it as a hex string for identification purposes.
     */
    private static String parseNodeIdPrefix(byte[] data, int offset) {
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 8; i++) {
            sb.append(String.format("%02x", data[offset + i] & 0xFF));
        }
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════
    // CLEANUP
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Stops all BLE operations (advertising and scanning).
     * Called from MeshManager.cleanup().
     */
    public void cleanup() {
        stopAdvertising();
        stopScanning();
        recentlyDiscovered.clear();
    }

    // ─── Utility ────────────────────────────────────────────────────────

    public boolean isAdvertising() { return isAdvertising; }
    public boolean isScanning() { return isScanning; }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(context, permission)
                == PackageManager.PERMISSION_GRANTED;
    }
}
