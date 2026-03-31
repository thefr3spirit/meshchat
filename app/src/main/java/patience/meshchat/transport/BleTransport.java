package patience.meshchat.transport;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * ============================================================================
 * BleTransport — BLE GATT Implementation of MeshTransport
 * ============================================================================
 *
 * HOW BLE GATT TRANSPORT WORKS:
 * ──────────────────────────────
 * Unlike the existing BleAdvertiser (which uses BLE only for discovery and
 * hands off to WiFi Direct for data), BleTransport implements full
 * bidirectional messaging over BLE GATT characteristics.
 *
 * BLE GATT Architecture:
 *   - GATT Server: each node runs a GATT server exposing a custom service
 *     with a writable characteristic. Peers write message data to this
 *     characteristic to send us messages.
 *   - GATT Client: to send a message, we connect to the peer's GATT
 *     server and write data to their characteristic.
 *
 * KEY APIs:
 *   - BluetoothGattServer: accepts incoming GATT connections
 *   - BluetoothGattServerCallback: receives write requests from peers
 *   - BluetoothGatt: client-side GATT connection to a peer's server
 *   - BluetoothGattCharacteristic: the data channel within the service
 *   - BluetoothLeScanner + ScanFilter: discover peers advertising our UUID
 *   - BluetoothLeAdvertiser: advertise our presence
 *
 * LIMITATIONS:
 *   - BLE has a maximum MTU of ~512 bytes (negotiated)
 *   - Throughput is much lower than WiFi Direct or Bluetooth Classic
 *   - Best suited for short messages and discovery
 *
 * ============================================================================
 */
@Singleton
public class BleTransport implements MeshTransport {

    private static final String TAG = "BleTransport";

    /** Custom GATT service UUID for MeshChat messaging */
    private static final UUID SERVICE_UUID =
            UUID.fromString("d7e5f010-bead-4e9a-b9f5-0c6a8e3f1b2c");

    /** Writable characteristic for sending message data */
    private static final UUID CHARACTERISTIC_UUID =
            UUID.fromString("d7e5f011-bead-4e9a-b9f5-0c6a8e3f1b2c");

    private static final ParcelUuid SERVICE_PARCEL_UUID = new ParcelUuid(SERVICE_UUID);

    private final Context context;
    private final BluetoothManager bluetoothManager;
    private final BluetoothAdapter bluetoothAdapter;

    // ─── State ──────────────────────────────────────────────────────────

    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private boolean isScanning = false;
    private boolean isAdvertising = false;

    private TransportCallback transportCallback;
    private DiscoveryCallback discoveryCallback;

    /** Active GATT client connections to peers */
    private final Map<String, BluetoothGatt> gattClients = new ConcurrentHashMap<>();

    /** De-duplication: suppress repeated discoveries within 30s */
    private final Map<String, Long> recentDiscoveries = new ConcurrentHashMap<>();
    private static final long DEDUP_WINDOW_MS = 30_000;

    // ═══════════════════════════════════════════════════════════════════
    // CONSTRUCTOR (Hilt-injected)
    // ═══════════════════════════════════════════════════════════════════

    @Inject
    public BleTransport(@ApplicationContext Context context) {
        this.context = context;
        this.bluetoothManager = (BluetoothManager)
                context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.bluetoothAdapter = (bluetoothManager != null)
                ? bluetoothManager.getAdapter() : null;

        if (bluetoothAdapter != null) {
            this.advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
            this.scanner = bluetoothAdapter.getBluetoothLeScanner();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // MeshTransport — send() via GATT client write
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public boolean send(String peerId, byte[] data) {
        BluetoothGatt gatt = gattClients.get(peerId);
        if (gatt == null) {
            // Attempt to connect first, then send
            connectAndSend(peerId, data);
            return true; // optimistic — data will be sent on connect
        }
        return writeCharacteristic(gatt, data);
    }

    private boolean writeCharacteristic(BluetoothGatt gatt, byte[] data) {
        try {
            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            if (service == null) return false;

            BluetoothGattCharacteristic characteristic =
                    service.getCharacteristic(CHARACTERISTIC_UUID);
            if (characteristic == null) return false;

            characteristic.setValue(data);
            return gatt.writeCharacteristic(characteristic);
        } catch (SecurityException e) {
            Log.w(TAG, "GATT write permission denied: " + e.getMessage());
            return false;
        }
    }

    private void connectAndSend(String peerId, byte[] data) {
        if (bluetoothAdapter == null) return;
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return;

        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(peerId);
            device.connectGatt(context, false, new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt gatt,
                                                    int status, int newState) {
                    try {
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            gattClients.put(peerId, gatt);
                            gatt.discoverServices();
                            if (transportCallback != null) {
                                transportCallback.onPeerConnected(peerId, getName());
                            }
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            gattClients.remove(peerId);
                            gatt.close();
                            if (transportCallback != null) {
                                transportCallback.onPeerDisconnected(peerId, getName());
                            }
                        }
                    } catch (SecurityException e) {
                        Log.w(TAG, "GATT callback permission denied: " + e.getMessage());
                    }
                }

                @Override
                public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        writeCharacteristic(gatt, data);
                    }
                }
            });
        } catch (SecurityException e) {
            Log.w(TAG, "GATT connect permission denied: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // MeshTransport — discover() via BLE scanning
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void discover(DiscoveryCallback callback) {
        this.discoveryCallback = callback;
        if (scanner == null) return;
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) return;
        if (isScanning) return;

        try {
            ScanFilter filter = new ScanFilter.Builder()
                    .setServiceUuid(SERVICE_PARCEL_UUID)
                    .build();
            List<ScanFilter> filters = new ArrayList<>();
            filters.add(filter);

            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    .setReportDelay(0)
                    .build();

            scanner.startScan(filters, settings, scanCallback);
            isScanning = true;
            Log.d(TAG, "BLE scanning started");
        } catch (SecurityException e) {
            Log.w(TAG, "BLE scan permission denied: " + e.getMessage());
        }
    }

    @Override
    public void stopDiscovery() {
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

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (discoveryCallback == null) return;
            try {
                String address = result.getDevice().getAddress();
                String name = result.getDevice().getName();

                // De-duplicate
                long now = System.currentTimeMillis();
                Long lastSeen = recentDiscoveries.get(address);
                if (lastSeen != null && (now - lastSeen) < DEDUP_WINDOW_MS) return;
                recentDiscoveries.put(address, now);

                discoveryCallback.onPeerDiscovered(
                        address, name, result.getRssi(), getName());
            } catch (SecurityException e) {
                Log.w(TAG, "Scan result permission denied: " + e.getMessage());
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            isScanning = false;
            Log.e(TAG, "BLE scan failed: errorCode=" + errorCode);
        }
    };

    // ═══════════════════════════════════════════════════════════════════
    // MeshTransport — listen() via GATT server + BLE advertising
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void listen(TransportCallback callback) {
        this.transportCallback = callback;
        startGattServer();
        startAdvertising();
    }

    @Override
    public void stopListening() {
        stopAdvertising();
        if (gattServer != null) {
            try {
                gattServer.close();
            } catch (SecurityException e) {
                Log.w(TAG, "GATT server close permission denied: " + e.getMessage());
            }
            gattServer = null;
        }
    }

    private void startGattServer() {
        if (bluetoothManager == null) return;
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return;

        try {
            gattServer = bluetoothManager.openGattServer(context, gattServerCallback);
            if (gattServer == null) return;

            BluetoothGattService service = new BluetoothGattService(
                    SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);

            BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
                    CHARACTERISTIC_UUID,
                    BluetoothGattCharacteristic.PROPERTY_WRITE
                            | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE);
            service.addCharacteristic(characteristic);

            gattServer.addService(service);
            Log.d(TAG, "GATT server started");
        } catch (SecurityException e) {
            Log.w(TAG, "GATT server permission denied: " + e.getMessage());
        }
    }

    private final BluetoothGattServerCallback gattServerCallback =
            new BluetoothGattServerCallback() {
                @Override
                public void onConnectionStateChange(BluetoothDevice device,
                                                    int status, int newState) {
                    try {
                        String address = device.getAddress();
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            if (transportCallback != null) {
                                transportCallback.onPeerConnected(address, getName());
                            }
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            gattClients.remove(address);
                            if (transportCallback != null) {
                                transportCallback.onPeerDisconnected(address, getName());
                            }
                        }
                    } catch (SecurityException e) {
                        Log.w(TAG, "GATT server callback permission denied: " + e.getMessage());
                    }
                }

                @Override
                public void onCharacteristicWriteRequest(BluetoothDevice device,
                                                         int requestId,
                                                         BluetoothGattCharacteristic characteristic,
                                                         boolean preparedWrite,
                                                         boolean responseNeeded,
                                                         int offset,
                                                         byte[] value) {
                    try {
                        if (responseNeeded && gattServer != null) {
                            gattServer.sendResponse(device, requestId,
                                    BluetoothGatt.GATT_SUCCESS, 0, null);
                        }
                        if (transportCallback != null && value != null) {
                            transportCallback.onDataReceived(
                                    device.getAddress(), value, getName());
                        }
                    } catch (SecurityException e) {
                        Log.w(TAG, "GATT write response permission denied: " + e.getMessage());
                    }
                }
            };

    private void startAdvertising() {
        if (advertiser == null || isAdvertising) return;
        if (!hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) return;

        try {
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                    .setConnectable(true) // GATT server requires connectable ads
                    .setTimeout(0)
                    .build();

            AdvertiseData data = new AdvertiseData.Builder()
                    .addServiceUuid(SERVICE_PARCEL_UUID)
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .build();

            advertiser.startAdvertising(settings, data, advertiseCallback);
        } catch (SecurityException e) {
            Log.w(TAG, "BLE advertise permission denied: " + e.getMessage());
        }
    }

    private void stopAdvertising() {
        if (advertiser != null && isAdvertising) {
            try {
                advertiser.stopAdvertising(advertiseCallback);
                isAdvertising = false;
            } catch (SecurityException e) {
                Log.w(TAG, "Stop advertising permission denied: " + e.getMessage());
            }
        }
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            isAdvertising = true;
            Log.d(TAG, "BLE advertising started (GATT connectable)");
        }

        @Override
        public void onStartFailure(int errorCode) {
            isAdvertising = false;
            Log.e(TAG, "BLE advertise failed: errorCode=" + errorCode);
        }
    };

    // ═══════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public boolean isAvailable() {
        return bluetoothAdapter != null
                && bluetoothAdapter.isEnabled()
                && context.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
    }

    @Override
    public String getName() {
        return "BLE";
    }

    @Override
    public void cleanup() {
        stopDiscovery();
        stopListening();
        for (BluetoothGatt gatt : gattClients.values()) {
            try { gatt.close(); } catch (SecurityException ignored) { }
        }
        gattClients.clear();
        recentDiscoveries.clear();
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(context, permission)
                == PackageManager.PERMISSION_GRANTED;
    }
}
