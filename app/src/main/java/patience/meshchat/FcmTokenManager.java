package patience.meshchat;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/**
 * FcmTokenManager — persists FCM tokens to SharedPreferences.
 *
 * Own token:  one entry, updated whenever Firebase rotates it.
 * Peer tokens: keyed by peer node UUID (the same UUID used in handshakes).
 */
public class FcmTokenManager {

    private static final String PREFS_NAME    = "fcm_tokens";
    private static final String KEY_OWN_TOKEN = "own_token";
    private static final String KEY_PEER_PREFIX = "peer_";

    // ─── Own token ───────────────────────────────────────────────────────

    public static void saveOwnToken(Context ctx, String token) {
        prefs(ctx).edit().putString(KEY_OWN_TOKEN, token).apply();
    }

    public static String getOwnToken(Context ctx) {
        return prefs(ctx).getString(KEY_OWN_TOKEN, null);
    }

    // ─── Peer tokens ─────────────────────────────────────────────────────

    public static void savePeerToken(Context ctx, String peerNodeId, String token) {
        if (peerNodeId == null || token == null) return;
        prefs(ctx).edit().putString(KEY_PEER_PREFIX + peerNodeId, token).apply();
    }

    public static String getPeerToken(Context ctx, String peerNodeId) {
        return prefs(ctx).getString(KEY_PEER_PREFIX + peerNodeId, null);
    }

    public static void removePeerToken(Context ctx, String peerNodeId) {
        prefs(ctx).edit().remove(KEY_PEER_PREFIX + peerNodeId).apply();
    }

    /** Returns all stored peer tokens as a nodeId → token map. */
    public static Map<String, String> getAllPeerTokens(Context ctx) {
        Map<String, String> result = new HashMap<>();
        Map<String, ?> all = prefs(ctx).getAll();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            if (entry.getKey().startsWith(KEY_PEER_PREFIX) && entry.getValue() instanceof String) {
                String nodeId = entry.getKey().substring(KEY_PEER_PREFIX.length());
                result.put(nodeId, (String) entry.getValue());
            }
        }
        return result;
    }

    // ─── Helper ──────────────────────────────────────────────────────────

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
