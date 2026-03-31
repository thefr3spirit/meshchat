package patience.meshchat;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * ============================================================================
 * QueuedMessage — Room Entity for the Store-and-Forward Queue
 * ============================================================================
 *
 * WHY PERSIST QUEUED MESSAGES? (for beginners)
 * ─────────────────────────────────────────────
 * The in-memory ConcurrentLinkedQueue in MeshManager loses data when the
 * process is killed. By persisting queued messages in a Room (SQLite)
 * table, messages survive app restarts, device reboots, and process death.
 *
 * Each row represents a message that could NOT be delivered because the
 * next-hop peer was temporarily unreachable. When that peer comes back
 * online (detected via BLE scan, Bluetooth discovery, or WiFi Direct),
 * the Store-and-Forward manager reads the QUEUED rows for that hop and
 * attempts redelivery.
 *
 * STATUS LIFECYCLE:
 * ─────────────────
 *   QUEUED → (peer comes online) → delivery attempt
 *     ├── success → DELIVERED
 *     └── failure → FAILED  (will be retried on next peer availability)
 *
 *   Messages older than 24 hours are automatically expired by a periodic
 *   WorkManager task (MessageExpiryWorker).
 *
 * ============================================================================
 */
@Entity(tableName = "queued_messages")
public class QueuedMessage {

    // ─── Status constants ───────────────────────────────────────────────

    /** Message is waiting for the next-hop peer to come online */
    public static final int STATUS_QUEUED = 0;

    /** Message was successfully delivered to the next-hop peer */
    public static final int STATUS_DELIVERED = 1;

    /** Delivery attempt failed (will be retried on next availability) */
    public static final int STATUS_FAILED = 2;

    // ─── Columns ────────────────────────────────────────────────────────

    /**
     * Primary key — matches the Message's UUID so we can correlate
     * queued entries with the original Message objects.
     */
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "message_id")
    public String messageId;

    /**
     * The address (BT MAC or WiFi Direct IP) of the next peer that should
     * receive this message. When that address appears in a BLE scan or
     * Bluetooth discovery, we know the peer is reachable and attempt delivery.
     */
    @NonNull
    @ColumnInfo(name = "next_hop_address")
    public String nextHopAddress;

    /**
     * The recipient's mesh node UUID (for private messages).
     * Null or empty for broadcast messages.
     */
    @ColumnInfo(name = "recipient_id")
    public String recipientId;

    /**
     * The serialised Message payload (JSON or the object's toString).
     * We store the full encrypted content so we can reconstruct the
     * Message object for redelivery without re-encrypting.
     */
    @NonNull
    @ColumnInfo(name = "payload")
    public String payload;

    /**
     * Current delivery status: QUEUED, DELIVERED, or FAILED.
     * Updated atomically by the DAO's @Update methods.
     */
    @ColumnInfo(name = "status")
    public int status;

    /**
     * Epoch millis when the message was first queued.
     * Used by MessageExpiryWorker to expire messages older than 24 hours.
     */
    @ColumnInfo(name = "created_at")
    public long createdAt;

    /**
     * Number of delivery attempts made so far.
     * Useful for debugging and could be used for a max-retry policy.
     */
    @ColumnInfo(name = "retry_count")
    public int retryCount;

    // ─── Constructors ───────────────────────────────────────────────────

    /** Required no-arg constructor for Room */
    public QueuedMessage() {}

    /**
     * Convenience constructor for creating a new queued entry.
     *
     * @param messageId      UUID of the original Message
     * @param nextHopAddress BT MAC or WiFi Direct IP of the target peer
     * @param recipientId    Mesh node UUID of the final recipient (nullable)
     * @param payload        Full serialised message content
     */
    public QueuedMessage(@NonNull String messageId,
                         @NonNull String nextHopAddress,
                         String recipientId,
                         @NonNull String payload) {
        this.messageId = messageId;
        this.nextHopAddress = nextHopAddress;
        this.recipientId = recipientId;
        this.payload = payload;
        this.status = STATUS_QUEUED;
        this.createdAt = System.currentTimeMillis();
        this.retryCount = 0;
    }
}
