package patience.meshchat;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/**
 * MeshChatMessagingService — handles incoming FCM messages and token refresh.
 *
 * - onNewToken: persists the fresh token via FcmTokenManager
 * - onMessageReceived: shows a system notification; tapping opens MainActivity
 */
public class MeshChatMessagingService extends FirebaseMessagingService {

    private static final String TAG = "MeshChatMsgService";
    private static final String CHANNEL_ID   = "meshchat_push";
    private static final String CHANNEL_NAME = "MeshChat Notifications";

    // ─── Token refresh ───────────────────────────────────────────────────

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        Log.d(TAG, "FCM token refreshed");
        FcmTokenManager.saveOwnToken(getApplicationContext(), token);
    }

    // ─── Incoming push ───────────────────────────────────────────────────

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        RemoteMessage.Notification notification = remoteMessage.getNotification();
        if (notification == null) return;

        String title = notification.getTitle() != null ? notification.getTitle() : "MeshChat";
        String body  = notification.getBody()  != null ? notification.getBody()  : "";

        showNotification(title, body);
    }

    // ─── Helper ──────────────────────────────────────────────────────────

    private void showNotification(String title, String body) {
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        // Create channel (no-op on second call)
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(channel);

        // Tap action — open MainActivity
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        nm.notify((int) System.currentTimeMillis(), builder.build());
    }
}
