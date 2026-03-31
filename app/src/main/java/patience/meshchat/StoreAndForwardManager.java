package patience.meshchat;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ============================================================================
 * StoreAndForwardManager — Persistent Message Queue with Retry Logic
 * ============================================================================
 *
 * WHY STORE-AND-FORWARD? (for beginners)
 * ───────────────────────────────────────
 * In a mesh network, peers frequently go offline (out of range, battery dead,
 * app killed). When a message can't be delivered because the next-hop peer is
 * unreachable, this manager persists it to a Room (SQLite) database.
 *
 * When the peer later comes back online (detected via BLE scan, Bluetooth
 * discovery, or WiFi Direct), this manager retrieves all queued messages for
 * that peer and attempts redelivery.
 *
 * KEY DESIGN DECISIONS:
 * ─────────────────────
 * 1. PERSISTENCE — Messages survive app crashes and device restarts (Room/SQLite).
 * 2. PER-HOP QUEUING — Messages are keyed by next_hop_address, not just recipient.
 *    This allows the mesh to resume forwarding from the exact point of failure.
 * 3. SERIALISATION — Message objects are serialised to Base64 strings for storage.
 *    We reuse Java's ObjectOutputStream since Message implements Serializable.
 * 4. THREAD SAFETY — All database operations run on a single-threaded executor
 *    to avoid SQLite contention.
 *
 * INTEGRATION POINTS:
 * ───────────────────
 * - MeshManager.sendToNode()       → calls enqueue() on write failure
 * - MeshManager.sendToAllPeers()   → calls enqueue() for unreachable peers
 * - BLE scan callback              → calls attemptDelivery() when peer detected
 * - MeshService                    → schedules periodic expiry via WorkManager
 *
 * ============================================================================
 */
public class StoreAndForwardManager {

    private static final String TAG = "StoreForward";

    /** Messages older than this are expired (24 hours in milliseconds) */
    static final long MESSAGE_TTL_MS = 24L * 60 * 60 * 1000;

    /** The Room DAO for queued message persistence */
    private final QueuedMessageDao dao;

    /**
     * Single-threaded executor for all database operations.
     * Room does not allow database access on the main thread.
     */
    private final ExecutorService dbExecutor;

    /**
     * Callback interface for delivery attempts.
     * MeshManager implements this to perform the actual socket write.
     */
    public interface DeliveryCallback {
        /**
         * Attempt to deliver a message to the specified address.
         *
         * @param message The deserialized Message object
         * @param address The next-hop address (BT MAC or WiFi Direct IP)
         * @return true if the message was successfully written to the stream
         */
        boolean deliverToAddress(Message message, String address);
    }

    private DeliveryCallback deliveryCallback;

    // ═══════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Creates a StoreAndForwardManager backed by the Room database.
     *
     * @param context Application or Service context
     */
    public StoreAndForwardManager(Context context) {
        MeshChatDatabase db = MeshChatDatabase.getInstance(context);
        this.dao = db.queuedMessageDao();
        this.dbExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "SAF-DB");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Sets the delivery callback used when attempting to redeliver queued messages.
     *
     * @param callback MeshManager's implementation that writes to OutputStreams
     */
    public void setDeliveryCallback(DeliveryCallback callback) {
        this.deliveryCallback = callback;
    }

    // ═══════════════════════════════════════════════════════════════════
    // ENQUEUE
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Persists a message for later delivery when the next-hop peer is unreachable.
     *
     * The Message object is serialised to a Base64 string and stored in Room.
     * This survives process death, unlike the in-memory ConcurrentLinkedQueue.
     *
     * @param message       The Message to queue
     * @param nextHopAddress BT MAC or WiFi Direct IP of the unreachable peer
     * @param recipientId   Final recipient's node UUID (null for broadcasts)
     */
    public void enqueue(Message message, String nextHopAddress, String recipientId) {
        dbExecutor.execute(() -> {
            try {
                String payload = serializeMessage(message);
                QueuedMessage queued = new QueuedMessage(
                        message.getId(), nextHopAddress, recipientId, payload);
                dao.insert(queued);
                Log.d(TAG, "Enqueued message " + message.getId()
                        + " for next-hop " + nextHopAddress);
            } catch (Exception e) {
                Log.e(TAG, "Failed to enqueue message: " + e.getMessage());
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════
    // DELIVERY ATTEMPT
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Attempts to deliver all queued messages for a specific next-hop peer.
     *
     * Called when BLE scan, Bluetooth discovery, or WiFi Direct detects that
     * a previously unreachable peer is now available.
     *
     * For each queued message:
     *   1. Deserialize the Message from the stored payload
     *   2. Call deliveryCallback.deliverToAddress() to write to the stream
     *   3. On success → mark as DELIVERED in Room
     *   4. On failure → mark as FAILED (will be retried next time)
     *
     * @param peerAddress BT MAC or WiFi Direct IP of the now-available peer
     */
    public void attemptDelivery(String peerAddress) {
        if (deliveryCallback == null) {
            Log.w(TAG, "No delivery callback set — cannot deliver");
            return;
        }

        dbExecutor.execute(() -> {
            try {
                List<QueuedMessage> pending = dao.getQueuedForHop(peerAddress);
                if (pending.isEmpty()) return;

                Log.d(TAG, "Attempting delivery of " + pending.size()
                        + " queued messages to " + peerAddress);

                int delivered = 0;
                for (QueuedMessage qm : pending) {
                    Message message = deserializeMessage(qm.payload);
                    if (message == null) {
                        // Corrupt payload — remove it
                        dao.deleteById(qm.messageId);
                        continue;
                    }

                    // Check if the message has expired (>24h)
                    if (message.isExpired()) {
                        dao.deleteById(qm.messageId);
                        Log.d(TAG, "Expired queued message " + qm.messageId);
                        continue;
                    }

                    boolean success = deliveryCallback.deliverToAddress(
                            message, peerAddress);

                    if (success) {
                        dao.markDelivered(qm.messageId);
                        delivered++;
                        Log.d(TAG, "Delivered queued message " + qm.messageId);
                    } else {
                        dao.markFailed(qm.messageId);
                        Log.w(TAG, "Failed to deliver message " + qm.messageId);
                    }
                }

                Log.d(TAG, "Delivery batch complete: " + delivered + "/"
                        + pending.size() + " delivered to " + peerAddress);
            } catch (Exception e) {
                Log.e(TAG, "Error during delivery attempt: " + e.getMessage());
            }
        });
    }

    /**
     * Attempts delivery for ALL queued messages across all next-hop addresses.
     *
     * Called when a significant connectivity event occurs (e.g. multiple
     * peers join simultaneously after a mesh reformation).
     *
     * @param connectedAddresses Set of currently connected peer addresses
     */
    public void attemptDeliveryForAll(java.util.Set<String> connectedAddresses) {
        if (deliveryCallback == null || connectedAddresses.isEmpty()) return;

        dbExecutor.execute(() -> {
            try {
                List<QueuedMessage> allQueued = dao.getAllQueued();
                if (allQueued.isEmpty()) return;

                int delivered = 0;
                for (QueuedMessage qm : allQueued) {
                    // Only try if that hop is currently connected
                    if (!connectedAddresses.contains(qm.nextHopAddress)) continue;

                    Message message = deserializeMessage(qm.payload);
                    if (message == null || message.isExpired()) {
                        dao.deleteById(qm.messageId);
                        continue;
                    }

                    boolean success = deliveryCallback.deliverToAddress(
                            message, qm.nextHopAddress);

                    if (success) {
                        dao.markDelivered(qm.messageId);
                        delivered++;
                    } else {
                        dao.markFailed(qm.messageId);
                    }
                }

                if (delivered > 0) {
                    Log.d(TAG, "Bulk delivery: " + delivered + " messages delivered");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error during bulk delivery: " + e.getMessage());
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════
    // EXPIRY & CLEANUP
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Deletes all messages older than 24 hours and all delivered messages.
     *
     * Called by MessageExpiryWorker (WorkManager) on a periodic schedule
     * and also during service startup.
     *
     * @return total number of expired + delivered rows deleted
     */
    public int expireOldMessages() {
        try {
            long cutoff = System.currentTimeMillis() - MESSAGE_TTL_MS;
            int expired = dao.deleteExpired(cutoff);
            int delivered = dao.deleteDelivered();
            int total = expired + delivered;
            if (total > 0) {
                Log.d(TAG, "Cleaned up " + expired + " expired + "
                        + delivered + " delivered = " + total + " messages");
            }
            return total;
        } catch (Exception e) {
            Log.e(TAG, "Expiry error: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Resets all FAILED messages back to QUEUED for retry.
     * Called periodically or after network topology changes.
     */
    public void resetFailedMessages() {
        dbExecutor.execute(() -> {
            try {
                dao.resetFailedToQueued();
                Log.d(TAG, "Reset FAILED messages to QUEUED for retry");
            } catch (Exception e) {
                Log.e(TAG, "Reset failed: " + e.getMessage());
            }
        });
    }

    /**
     * Returns the count of pending (QUEUED) messages.
     * Safe to call from any thread — runs synchronously on the calling thread.
     * Only use from a background thread.
     */
    public int getQueuedCount() {
        try {
            return dao.getQueuedCount();
        } catch (Exception e) {
            Log.e(TAG, "Count error: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Shuts down the database executor. Call during service/manager cleanup.
     */
    public void shutdown() {
        dbExecutor.shutdownNow();
    }

    // ═══════════════════════════════════════════════════════════════════
    // SERIALIZATION HELPERS
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Serialises a Message object to a Base64-encoded string.
     *
     * Uses Java's ObjectOutputStream since Message implements Serializable.
     * Base64 encoding makes the binary data safe for SQLite TEXT columns.
     *
     * @param message The Message to serialise
     * @return Base64-encoded string of the serialised object
     */
    static String serializeMessage(Message message) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(message);
            oos.close();
            return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "Serialise failed: " + e.getMessage());
            return "";
        }
    }

    /**
     * Deserialises a Message object from a Base64-encoded string.
     *
     * @param payload Base64-encoded serialised Message
     * @return The reconstructed Message, or null on failure
     */
    static Message deserializeMessage(String payload) {
        try {
            byte[] data = Base64.decode(payload, Base64.NO_WRAP);
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            ObjectInputStream ois = new ObjectInputStream(bais);
            Message msg = (Message) ois.readObject();
            ois.close();
            return msg;
        } catch (Exception e) {
            Log.e(TAG, "Deserialise failed: " + e.getMessage());
            return null;
        }
    }
}
