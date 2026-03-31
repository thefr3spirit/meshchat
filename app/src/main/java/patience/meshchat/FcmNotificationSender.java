package patience.meshchat;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FcmNotificationSender — sends FCM push notifications via the HTTP V1 API.
 *
 * Uses the service-account JSON in assets/ to build a signed JWT, exchanges it
 * for a short-lived Bearer token at Google's token endpoint, then POSTs to the
 * FCM HTTP V1 endpoint.
 *
 * Implemented entirely with standard Android/Java APIs — no external libraries.
 * All network work is dispatched to a background thread via an ExecutorService.
 */
public class FcmNotificationSender {

    private static final String TAG = "FcmNotificationSender";

    private static final String SERVICE_ACCOUNT_ASSET = "service-account.json";
    private static final String FCM_SCOPE =
            "https://www.googleapis.com/auth/firebase.messaging";
    private static final String TOKEN_ENDPOINT =
            "https://oauth2.googleapis.com/token";
    private static final String GRANT_TYPE =
            "urn:ietf:params:oauth:grant-type:jwt-bearer";
    private static final String FCM_ENDPOINT =
            "https://fcm.googleapis.com/v1/projects/meshchat-56aaf/messages:send";

    private final Context context;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // Simple in-memory token cache — avoids fetching a new token every time
    private String cachedToken;
    private long tokenExpiryMs;

    public FcmNotificationSender(Context context) {
        this.context = context.getApplicationContext();
    }

    // ─── Public API ──────────────────────────────────────────────────────

    /**
     * Sends a "new node joined" notification to every stored peer token,
     * excluding the token that belongs to the joining node itself.
     */
    public void notifyPeersOfNewNode(String joiningUsername, String joiningNodeId) {
        Map<String, String> peerTokens = FcmTokenManager.getAllPeerTokens(context);
        if (peerTokens.isEmpty()) return;

        String title = "New node joined";
        String body  = joiningUsername + " has joined the mesh";

        for (Map.Entry<String, String> entry : peerTokens.entrySet()) {
            if (entry.getKey().equals(joiningNodeId)) continue;
            sendToToken(entry.getValue(), title, body);
        }
    }

    /** Sends a single FCM notification to one device token. */
    public void sendToToken(String token, String title, String body) {
        executor.execute(() -> {
            try {
                String accessToken = getAccessToken();
                postNotification(accessToken, token, title, body);
            } catch (Exception e) {
                Log.e(TAG, "Failed to send FCM notification: " + e.getMessage());
            }
        });
    }

    // ─── OAuth2 — manual JWT-bearer exchange ─────────────────────────────

    private synchronized String getAccessToken() throws Exception {
        long now = System.currentTimeMillis();
        if (cachedToken != null && now < tokenExpiryMs - 60_000) {
            return cachedToken; // reuse until 60 s before expiry
        }

        // Parse service-account JSON from assets
        JSONObject sa;
        try (InputStream is = context.getAssets().open(SERVICE_ACCOUNT_ASSET)) {
            sa = new JSONObject(new String(readAll(is), StandardCharsets.UTF_8));
        }
        String clientEmail = sa.getString("client_email");
        String privateKeyPem = sa.getString("private_key");

        // Build signed JWT
        String jwt = buildJwt(clientEmail, privateKeyPem, now / 1000L);

        // Exchange JWT for access token
        String formBody = "grant_type=" + URLEncoder.encode(GRANT_TYPE, "UTF-8")
                + "&assertion=" + URLEncoder.encode(jwt, "UTF-8");

        URL url = new URL(TOKEN_ENDPOINT);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(formBody.getBytes(StandardCharsets.UTF_8));
        }

        byte[] responseBytes = readAll(conn.getInputStream());
        conn.disconnect();

        JSONObject tokenJson = new JSONObject(
                new String(responseBytes, StandardCharsets.UTF_8));
        String accessToken = tokenJson.getString("access_token");
        int expiresIn = tokenJson.optInt("expires_in", 3600);

        cachedToken    = accessToken;
        tokenExpiryMs  = System.currentTimeMillis() + (expiresIn * 1000L);
        return accessToken;
    }

    // ─── JWT construction ────────────────────────────────────────────────

    private String buildJwt(String clientEmail, String privateKeyPem, long nowSec)
            throws Exception {

        String headerJson  = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
        String payloadJson = "{"
                + "\"iss\":\"" + clientEmail + "\","
                + "\"scope\":\"" + FCM_SCOPE + "\","
                + "\"aud\":\"" + TOKEN_ENDPOINT + "\","
                + "\"iat\":" + nowSec + ","
                + "\"exp\":" + (nowSec + 3600)
                + "}";

        String header  = base64Url(headerJson.getBytes(StandardCharsets.UTF_8));
        String payload = base64Url(payloadJson.getBytes(StandardCharsets.UTF_8));
        String input   = header + "." + payload;

        PrivateKey key = loadPrivateKey(privateKeyPem);
        Signature sig  = Signature.getInstance("SHA256withRSA");
        sig.initSign(key);
        sig.update(input.getBytes(StandardCharsets.UTF_8));
        String signature = base64Url(sig.sign());

        return input + "." + signature;
    }

    private PrivateKey loadPrivateKey(String pem) throws Exception {
        String stripped = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = Base64.decode(stripped, Base64.DEFAULT);
        return KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
    }

    private static String base64Url(byte[] data) {
        return Base64.encodeToString(data,
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    // ─── FCM HTTP V1 POST ────────────────────────────────────────────────

    private void postNotification(String accessToken, String token,
                                  String title, String body) throws Exception {
        JSONObject notification = new JSONObject();
        notification.put("title", title);
        notification.put("body", body);

        JSONObject message = new JSONObject();
        message.put("token", token);
        message.put("notification", notification);

        JSONObject payload = new JSONObject();
        payload.put("message", message);

        byte[] payloadBytes = payload.toString().getBytes(StandardCharsets.UTF_8);

        URL url = new URL(FCM_ENDPOINT);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setRequestProperty("Content-Type", "application/json; UTF-8");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payloadBytes);
        }

        int code = conn.getResponseCode();
        if (code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_NO_CONTENT) {
            Log.d(TAG, "Notification sent to ..."+token.substring(Math.max(0, token.length()-8)));
        } else {
            Log.w(TAG, "FCM returned HTTP " + code);
        }
        conn.disconnect();
    }

    // ─── Utility ─────────────────────────────────────────────────────────

    private static byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = is.read(tmp)) != -1) buf.write(tmp, 0, n);
        return buf.toByteArray();
    }
}
