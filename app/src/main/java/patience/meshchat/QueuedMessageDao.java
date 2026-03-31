package patience.meshchat;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * ============================================================================
 * QueuedMessageDao — Data Access Object for the Store-and-Forward Queue
 * ============================================================================
 *
 * WHAT IS A DAO? (for beginners)
 * ──────────────────────────────
 * A DAO (Data Access Object) is an interface that defines how to interact
 * with a database table. Room generates the actual SQL implementation at
 * compile time based on the annotations (@Insert, @Query, @Update).
 *
 * This DAO provides all the operations needed for the store-and-forward
 * mechanism:
 *   - INSERT: queue a message when the next-hop peer is unreachable
 *   - QUERY:  find queued messages for a specific peer address
 *   - UPDATE: mark messages as DELIVERED or FAILED after a delivery attempt
 *   - DELETE: expire old messages (>24h) or clean up delivered entries
 *
 * KEY QUERIES EXPLAINED:
 * ──────────────────────
 * 1. getQueuedForHop()  → "Which messages need to go to this peer?"
 *    Used when BLE scan detects a peer coming back online.
 *
 * 2. deleteExpired()    → "Remove messages older than 24 hours"
 *    Called by MessageExpiryWorker (WorkManager) periodically.
 *
 * 3. markDelivered()    → "This message reached its next hop successfully"
 *    Called after a successful write to the peer's output stream.
 *
 * ============================================================================
 */
@Dao
public interface QueuedMessageDao {

    // ─── Insert ─────────────────────────────────────────────────────────

    /**
     * Enqueue a message for store-and-forward delivery.
     *
     * REPLACE strategy: if the same message ID is queued again (e.g. after
     * a retry), the old row is replaced rather than creating a duplicate.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(QueuedMessage queuedMessage);

    // ─── Query ──────────────────────────────────────────────────────────

    /**
     * Returns all QUEUED (undelivered) messages for a specific next-hop address.
     *
     * Called when a peer is detected via BLE scan, Bluetooth discovery, or
     * WiFi Direct — we know that peer is reachable, so we attempt delivery
     * of all their pending messages.
     *
     * @param nextHopAddress BT MAC or WiFi Direct IP of the peer
     * @return List of queued messages targeting that peer, ordered oldest first
     */
    @Query("SELECT * FROM queued_messages WHERE next_hop_address = :nextHopAddress "
            + "AND status = 0 ORDER BY created_at ASC")
    List<QueuedMessage> getQueuedForHop(String nextHopAddress);

    /**
     * Returns ALL queued (undelivered) messages regardless of next-hop.
     * Used for bulk retry when multiple peers come online simultaneously.
     */
    @Query("SELECT * FROM queued_messages WHERE status = 0 ORDER BY created_at ASC")
    List<QueuedMessage> getAllQueued();

    /**
     * Returns the total count of queued (undelivered) messages.
     * Useful for UI indicators showing pending message count.
     */
    @Query("SELECT COUNT(*) FROM queued_messages WHERE status = 0")
    int getQueuedCount();

    // ─── Update ─────────────────────────────────────────────────────────

    /**
     * Updates a queued message (typically to change its status).
     *
     * Room's @Update uses the primary key (messageId) to identify which
     * row to update. The entire entity is written back to the database.
     */
    @Update
    void update(QueuedMessage queuedMessage);

    /**
     * Marks a specific message as DELIVERED (status = 1).
     *
     * Called after the message is successfully written to the peer's
     * output stream (ObjectOutputStream).
     *
     * @param messageId UUID of the delivered message
     */
    @Query("UPDATE queued_messages SET status = 1 WHERE message_id = :messageId")
    void markDelivered(String messageId);

    /**
     * Marks a specific message as FAILED (status = 2) and increments retry count.
     *
     * Called when a delivery attempt fails (e.g. stream write error).
     * The message remains in the table for future retry attempts.
     *
     * @param messageId UUID of the failed message
     */
    @Query("UPDATE queued_messages SET status = 2, retry_count = retry_count + 1 "
            + "WHERE message_id = :messageId")
    void markFailed(String messageId);

    /**
     * Resets all FAILED messages back to QUEUED so they can be retried.
     *
     * Called periodically or when a significant connectivity event occurs
     * (e.g. a new peer joins the mesh).
     */
    @Query("UPDATE queued_messages SET status = 0 WHERE status = 2")
    void resetFailedToQueued();

    // ─── Delete ─────────────────────────────────────────────────────────

    /**
     * Deletes all messages older than the given cutoff timestamp.
     *
     * This is the "expire messages older than 24 hours" operation.
     * Called by MessageExpiryWorker via WorkManager on a periodic schedule.
     *
     * @param cutoffTimestamp epoch millis; messages created before this are deleted
     * @return number of rows deleted
     */
    @Query("DELETE FROM queued_messages WHERE created_at < :cutoffTimestamp")
    int deleteExpired(long cutoffTimestamp);

    /**
     * Deletes all DELIVERED messages to reclaim storage space.
     * Delivered messages are kept briefly for audit/debugging but can
     * be cleaned up safely.
     *
     * @return number of rows deleted
     */
    @Query("DELETE FROM queued_messages WHERE status = 1")
    int deleteDelivered();

    /**
     * Deletes a specific message by its ID.
     *
     * @param messageId UUID of the message to delete
     */
    @Query("DELETE FROM queued_messages WHERE message_id = :messageId")
    void deleteById(String messageId);

    /**
     * Deletes ALL entries. Used for a full queue reset (e.g. when leaving
     * a network).
     */
    @Query("DELETE FROM queued_messages")
    void deleteAll();
}
