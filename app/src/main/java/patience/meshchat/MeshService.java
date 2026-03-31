package patience.meshchat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * ============================================================================
 * MeshService - Background Service that Keeps the Mesh Running
 * ============================================================================
 *
 * WHY A FOREGROUND SERVICE? (for beginners)
 * ────────────────────────────────────────
 * Android aggressively kills background apps to save battery. If MeshChat
 * only ran as a regular Activity, it would stop relaying messages the
 * moment the user switched to another app or locked their screen.
 *
 * A Foreground Service solves this:
 * - It shows a persistent notification ("MeshChat is active")
 * - Android knows the user expects this app to keep running
 * - The mesh stays alive even with the screen off
 * - It survives Activity recreation (screen rotation, etc.)
 *
 * This is essential for a mesh relay — your phone needs to keep
 * forwarding messages even when you're not looking at the chat.
 *
 * LIFECYCLE:
 * ──────────
 * 1. MainActivity calls startService() + bindService()
 * 2. onCreate() → creates MeshManager (starts networking)
 * 3. onStartCommand() → promotes to foreground with notification
 * 4. MeshManager runs discovery and relays messages in the background
 * 5. onDestroy() → cleans up MeshManager when service is stopped
 *
 * ============================================================================
 */
public class MeshService extends Service {
    /** Notification channel ID (required for Android 8.0+) */
    private static final String CHANNEL_ID = "MeshChatServiceChannel";

    /** Binder for Activity-to-Service communication */
    private final IBinder binder = new MeshBinder();

    /** The core mesh networking manager */
    private MeshManager meshManager;

    /**
     * Binder class that allows MainActivity to get a reference to this Service.
     *
     * WHAT IS A BINDER? (for beginners)
     * A Binder is the "USB cable" between an Activity and a Service.
     * When MainActivity calls bindService(), Android returns this Binder.
     * MainActivity then calls getService() to get the actual MeshService object,
     * which gives it access to getMeshManager() and everything else.
     */
    public class MeshBinder extends Binder {
        MeshService getService() {
            return MeshService.this;
        }
    }

    /**
     * Called when the service is first created.
     * Sets up the notification channel and creates the MeshManager.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        // Create the mesh networking manager — this starts BT/WiFi servers
        meshManager = new MeshManager(this);
    }

    /**
     * Called when startService() is invoked.
     * Promotes this service to "foreground" with a persistent notification
     * and begins mesh peer discovery.
     *
     * START_STICKY means: if Android kills this service to free memory,
     * it will automatically restart it later. Essential for a mesh relay.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Create the persistent notification
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("MeshChat is active")
                .setContentText("Relaying messages in the mesh network")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .build();

        // Promote to foreground (prevents Android from killing us)
        // FOREGROUND_SERVICE_TYPE_DATA_SYNC indicates this service performs
        // data synchronisation (WiFi Direct server socket + mesh relay).
        // Combined with connectedDevice for BLE/Bluetooth operations.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(1, notification);
        }

        // Begin scanning for nearby mesh devices
        meshManager.startDiscovery();

        // Schedule periodic message expiry (every 6 hours)
        scheduleMessageExpiry();

        return START_STICKY; // Auto-restart if killed
    }

    /** Returns the MeshManager for the Activity to interact with */
    public MeshManager getMeshManager() {
        return meshManager;
    }

    /** Clean up mesh resources when the service is destroyed */
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (meshManager != null) {
            meshManager.cleanup();
        }
    }

    /** Returns the binder for Activity binding */
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    /**
     * Schedules a periodic WorkManager task that cleans up expired messages.
     *
     * Runs every 6 hours to:
     *   1. Delete queued messages older than 24 hours
     *   2. Remove already-delivered messages
     *   3. Reset FAILED messages for retry
     *
     * KEEP policy means: if the work is already scheduled, don't create
     * a duplicate. This makes it safe to call on every service start.
     */
    private void scheduleMessageExpiry() {
        PeriodicWorkRequest expiryWork = new PeriodicWorkRequest.Builder(
                MessageExpiryWorker.class, 6, TimeUnit.HOURS)
                .build();

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                MessageExpiryWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                expiryWork);
    }

    /**
     * Creates a notification channel (required for Android 8.0+ / API 26+).
     *
     * Notification channels let users control notification types per-app.
     * We use IMPORTANCE_LOW so the notification doesn't make sounds or
     * pop up — it just quietly sits in the notification bar.
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "MeshChat Service Channel",
                    NotificationManager.IMPORTANCE_LOW  // Quiet — no sound or popup
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }
}