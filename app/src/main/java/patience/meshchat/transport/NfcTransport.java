package patience.meshchat.transport;

import android.content.Context;
import android.content.pm.PackageManager;
import android.nfc.NfcAdapter;
import android.util.Log;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * ============================================================================
 * NfcTransport — Stub NFC Implementation of MeshTransport
 * ============================================================================
 *
 * NFC (Near Field Communication) is a future transport for MeshChat.
 * This stub follows the MeshTransport interface so that CompositeTransport
 * can include it in its preference list.  When hardware and OS support are
 * detected, isAvailable() returns true, but actual data transfer is not
 * yet implemented.
 *
 * FUTURE IMPLEMENTATION PLAN:
 *   - Android Beam / NDEF push (deprecated in API 29+)
 *   - Host Card Emulation (HCE) for bidirectional exchange
 *   - NFC-F / NFC-V for faster transfers
 *
 * ============================================================================
 */
@Singleton
public class NfcTransport implements MeshTransport {

    private static final String TAG = "NfcTransport";

    private final Context context;
    private final NfcAdapter nfcAdapter;

    // ═══════════════════════════════════════════════════════════════════
    // CONSTRUCTOR (Hilt-injected)
    // ═══════════════════════════════════════════════════════════════════

    @Inject
    public NfcTransport(@ApplicationContext Context context) {
        this.context = context;
        this.nfcAdapter = NfcAdapter.getDefaultAdapter(context);
    }

    // ═══════════════════════════════════════════════════════════════════
    // MeshTransport — stub implementations
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public boolean send(String peerId, byte[] data) {
        Log.d(TAG, "NFC send not yet implemented");
        return false;
    }

    @Override
    public void discover(DiscoveryCallback callback) {
        Log.d(TAG, "NFC discovery not yet implemented");
    }

    @Override
    public void stopDiscovery() {
        // no-op
    }

    @Override
    public void listen(TransportCallback callback) {
        Log.d(TAG, "NFC listen not yet implemented");
    }

    @Override
    public void stopListening() {
        // no-op
    }

    @Override
    public boolean isAvailable() {
        return nfcAdapter != null && nfcAdapter.isEnabled()
                && context.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_NFC);
    }

    @Override
    public String getName() {
        return "NFC";
    }

    @Override
    public void cleanup() {
        // nothing to clean up
    }
}
