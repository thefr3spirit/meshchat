package patience.meshchat;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * ============================================================================
 * MessageExpiryWorker — Periodic Cleanup of Expired Queued Messages
 * ============================================================================
 *
 * WHY USE WORKMANAGER? (for beginners)
 * ─────────────────────────────────────
 * WorkManager is Android's recommended solution for deferrable, guaranteed
 * background work. Unlike a simple Handler or Timer:
 *   - It survives app restarts and device reboots
 *   - It respects battery optimisation constraints
 *   - It guarantees the work will eventually run, even if the app is killed
 *
 * This worker runs periodically (every 6 hours by default) and:
 *   1. Deletes queued messages older than 24 hours (they're stale)
 *   2. Deletes messages that were already delivered (cleanup)
 *   3. Resets FAILED messages back to QUEUED so they get retried
 *
 * SCHEDULING:
 * ───────────
 * The worker is scheduled by MeshService using:
 *   PeriodicWorkRequest.Builder(MessageExpiryWorker.class, 6, HOURS)
 *
 * WorkManager handles retries, backoff, and device wake-locks automatically.
 *
 * ============================================================================
 */
public class MessageExpiryWorker extends Worker {

    private static final String TAG = "MsgExpiryWorker";

    /** Unique name for the periodic work request (prevents duplicate scheduling) */
    public static final String WORK_NAME = "meshchat_message_expiry";

    public MessageExpiryWorker(@NonNull Context context,
                               @NonNull WorkerParameters params) {
        super(context, params);
    }

    /**
     * Performs the expiry and cleanup work.
     *
     * Called by WorkManager on a background thread. We create a fresh
     * StoreAndForwardManager instance (which gets the singleton Room DB)
     * and run expiry + reset.
     *
     * @return Result.success() if cleanup completed without errors,
     *         Result.retry() if something went wrong (WorkManager will retry)
     */
    @NonNull
    @Override
    public Result doWork() {
        try {
            Log.d(TAG, "Running message expiry worker...");

            StoreAndForwardManager safManager =
                    new StoreAndForwardManager(getApplicationContext());

            // Delete expired (>24h) and delivered messages
            int cleaned = safManager.expireOldMessages();

            // Reset any FAILED messages back to QUEUED for fresh attempts
            safManager.resetFailedMessages();

            Log.d(TAG, "Expiry worker complete. Cleaned " + cleaned + " messages.");
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Expiry worker failed: " + e.getMessage());
            return Result.retry();
        }
    }
}
