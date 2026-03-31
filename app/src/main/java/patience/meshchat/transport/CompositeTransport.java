package patience.meshchat.transport;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * ============================================================================
 * CompositeTransport — Multi-Transport Façade
 * ============================================================================
 *
 * Aggregates every available MeshTransport behind one unified interface.
 * The routing layer (MeshManager) only ever sees CompositeTransport; it
 * never interacts with WiFi Direct / BLE / NFC directly.
 *
 * PREFERENCE ORDER:
 *   1. WiFi Direct  — highest throughput, largest range
 *   2. BLE          — low-power, shorter range, lower throughput
 *   3. NFC          — contact-range, future expansion
 *
 * STRATEGY:
 *   - discover() / listen()  — start on ALL available transports.
 *   - send()                 — try each transport in preference order;
 *                              return true as soon as one succeeds.
 *   - cleanup()              — stop all transports.
 *
 * KEY DESIGN NOTES:
 *   - Interface segregation: the routing layer only depends on MeshTransport
 *   - Open / Closed principle: add a new transport by simply registering it
 *     in transportList — zero changes to the routing logic
 *   - Dependency Injection (Hilt): the list of transports is injected
 *
 * ============================================================================
 */
@Singleton
public class CompositeTransport implements MeshTransport {

    private static final String TAG = "CompositeTransport";

    /** Ordered list — first = highest preference */
    private final List<MeshTransport> transports;

    private DiscoveryCallback discoveryCallback;
    private TransportCallback transportCallback;

    // ═══════════════════════════════════════════════════════════════════
    // CONSTRUCTOR (Hilt-injected)
    // ═══════════════════════════════════════════════════════════════════

    @Inject
    public CompositeTransport(
            WifiDirectTransport wifiDirectTransport,
            BleTransport bleTransport,
            NfcTransport nfcTransport
    ) {
        transports = new ArrayList<>();
        transports.add(wifiDirectTransport); // preference 1
        transports.add(bleTransport);        // preference 2
        transports.add(nfcTransport);        // preference 3
    }

    // ═══════════════════════════════════════════════════════════════════
    // send — try each transport in preference order
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public boolean send(String peerId, byte[] data) {
        for (MeshTransport transport : transports) {
            if (transport.isAvailable()) {
                boolean sent = transport.send(peerId, data);
                if (sent) {
                    Log.d(TAG, "Sent via " + transport.getName()
                            + " to " + peerId);
                    return true;
                }
            }
        }
        Log.w(TAG, "All transports failed for peerId=" + peerId);
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════
    // discover — start on ALL available transports
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void discover(DiscoveryCallback callback) {
        this.discoveryCallback = callback;
        for (MeshTransport transport : transports) {
            if (transport.isAvailable()) {
                transport.discover(callback);
                Log.d(TAG, "Discovery started on " + transport.getName());
            }
        }
    }

    @Override
    public void stopDiscovery() {
        for (MeshTransport transport : transports) {
            transport.stopDiscovery();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // listen — start listening on ALL available transports
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void listen(TransportCallback callback) {
        this.transportCallback = callback;
        for (MeshTransport transport : transports) {
            if (transport.isAvailable()) {
                transport.listen(callback);
                Log.d(TAG, "Listening on " + transport.getName());
            }
        }
    }

    @Override
    public void stopListening() {
        for (MeshTransport transport : transports) {
            transport.stopListening();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Meta
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public boolean isAvailable() {
        for (MeshTransport transport : transports) {
            if (transport.isAvailable()) return true;
        }
        return false;
    }

    @Override
    public String getName() {
        return "Composite";
    }

    @Override
    public void cleanup() {
        for (MeshTransport transport : transports) {
            transport.cleanup();
        }
    }

    /** Returns the list of all registered transports (for diagnostics). */
    public List<MeshTransport> getTransports() {
        return transports;
    }
}
