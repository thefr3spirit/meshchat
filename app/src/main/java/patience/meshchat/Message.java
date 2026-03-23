package patience.meshchat;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * ============================================================================
 * Message - Represents a single chat message in the MeshChat network
 * ============================================================================
 *
 * Each message travels through the mesh network from device to device.
 * Think of it like passing a note in class — the note has:
 *   - A unique ID (so we don't process the same note twice)
 *   - The message content (what was written)
 *   - Who sent it (sender info)
 *   - A timestamp (when it was written)
 *   - A hop count (how many devices have relayed it)
 *   - An encryption flag (whether the content is currently encrypted)
 *
 * IMPORTANT: This class implements Serializable because we need to
 * send Message objects over Bluetooth and WiFi streams. Serializable
 * converts the object into a stream of bytes for network transmission.
 *
 * HOP COUNT EXPLAINED:
 * ────────────────────
 * When you send a message, hopCount starts at 0.
 * When Device B receives and forwards it, hopCount becomes 1.
 * When Device C gets the forwarded message, hopCount becomes 2.
 * This continues up to MAX_HOPS (10) to prevent messages from
 * bouncing around the mesh network forever.
 *
 *   You ──→ Phone B ──→ Phone C ──→ ... ──→ Phone J (hop 9 → STOP)
 *  hop 0     hop 1       hop 2              hop 9 = MAX, won't forward
 *
 * ============================================================================
 */
public class Message implements Serializable {

    /**
     * Serialization version ID.
     * When a Message object is sent over the network, Java converts it
     * to bytes (serialization). The receiving device converts it back
     * (deserialization). Both sides must have the same serialVersionUID
     * or deserialization will fail.
     *
     * Changed from 1 → 2 because we added the 'encrypted' field.
     */
    private static final long serialVersionUID = 2L;

    // ─── Message Fields ─────────────────────────────────────────────────

    /**
     * A globally unique identifier for this message (UUID format).
     * Example: "550e8400-e29b-41d4-a716-446655440000"
     *
     * WHY? In a mesh network, the same message might reach you through
     * multiple paths (e.g., via Phone B AND via Phone C). The UUID lets
     * us detect and ignore duplicates — if we've seen this ID before,
     * we skip it.
     */
    private String id;

    /**
     * The actual text content of the message.
     * During transit over the network, this will be AES-256 encrypted.
     * After receiving, MeshManager decrypts it back to readable text.
     */
    private String content;

    /** Unique identifier of the sending device (Build.ID) */
    private String senderId;

    /** Human-readable name of the sender's device (e.g., "Pixel 8", "Galaxy S24") */
    private String senderName;

    /** When the message was created, in milliseconds since Jan 1, 1970 (Unix epoch) */
    private long timestamp;

    /**
     * Message direction: TYPE_SENT (1) for messages WE sent,
     * TYPE_RECEIVED (0) for messages from OTHER devices.
     * Used by the UI to show sent messages on the right and received on the left.
     */
    private int type;

    /**
     * How many times this message has been forwarded between devices.
     * Starts at 0, incremented each time a relay node forwards it.
     * Once it reaches MAX_HOPS, the message stops being forwarded.
     */
    private int hopCount;

    /**
     * The original sender's ID — stays the same even after forwarding.
     * If Phone A sends a message and Phone B forwards it, the originalSenderId
     * still points to Phone A.
     */
    private String originalSenderId;

    /**
     * Whether the message content is currently encrypted.
     * true = content is AES-256-GCM encrypted (during network transit)
     * false = content is readable plaintext (after decryption / before encryption)
     */
    private boolean encrypted;

    // ─── Constants ──────────────────────────────────────────────────────

    /** Message was received from another device in the mesh */
    public static final int TYPE_RECEIVED = 0;

    /** Message was sent by the current user */
    public static final int TYPE_SENT = 1;

    /**
     * Maximum number of hops before a message is dropped.
     * 10 hops means a message can travel through up to 10 relay devices.
     * This prevents messages from circling forever in the mesh.
     */
    public static final int MAX_HOPS = 10;

    // ─── Constructors ───────────────────────────────────────────────────

    /**
     * Creates a new message for sending.
     * Used when the current user types and sends a message.
     *
     * @param content The text message to send
     * @param type    TYPE_SENT (1) for outgoing messages
     */
    public Message(String content, int type) {
        this.id = UUID.randomUUID().toString();     // Generate a globally unique ID
        this.content = content;
        this.type = type;
        this.timestamp = System.currentTimeMillis();
        this.hopCount = 0;                           // Brand new message = zero hops
        this.senderId = android.os.Build.ID;         // Device's build ID
        this.senderName = android.os.Build.MODEL;    // Default; overridden by MainActivity
        this.originalSenderId = this.senderId;
        this.encrypted = false;                      // Not yet encrypted
    }

    /**
     * Creates a new message with a custom sender name (username).
     * Used when the user has registered a username via RegistrationActivity.
     */
    public Message(String content, int type, String senderName) {
        this(content, type);
        this.senderName = senderName;
    }

    /**
     * Creates a message with full details.
     * Used when constructing a message received from another device.
     *
     * @param content    The message text
     * @param senderId   The sender's device ID
     * @param senderName The sender's device model name
     * @param hopCount   How many hops this message has already taken
     */
    public Message(String content, String senderId, String senderName, int hopCount) {
        this.id = UUID.randomUUID().toString();
        this.content = content;
        this.senderId = senderId;
        this.senderName = senderName;
        this.timestamp = System.currentTimeMillis();
        this.hopCount = hopCount;
        this.type = TYPE_RECEIVED;  // Messages from others are always "received"
        this.originalSenderId = senderId;
        this.encrypted = false;
    }

    // ─── Getters ────────────────────────────────────────────────────────

    public String getId() { return id; }
    public String getContent() { return content; }
    public String getSenderId() { return senderId; }
    public String getSenderName() { return senderName; }
    public long getTimestamp() { return timestamp; }
    public int getType() { return type; }
    public int getHopCount() { return hopCount; }
    public String getOriginalSenderId() { return originalSenderId; }
    public boolean isEncrypted() { return encrypted; }

    // ─── Setters ────────────────────────────────────────────────────────

    /**
     * Updates the message content text.
     * Used by MeshManager after decrypting to replace encrypted gibberish
     * with the original readable text.
     *
     * @param content The new content (usually the decrypted plaintext)
     */
    public void setContent(String content) {
        this.content = content;
    }

    /**
     * Sets the message direction type.
     * Used when a relayed message arrives — we set it to TYPE_RECEIVED
     * so the UI displays it on the left side (incoming message style).
     *
     * @param type TYPE_SENT (1) or TYPE_RECEIVED (0)
     */
    public void setType(int type) {
        this.type = type;
    }

    /**
     * Sets whether the content is currently encrypted.
     *
     * @param encrypted true if content is encrypted, false if plaintext
     */
    public void setEncrypted(boolean encrypted) {
        this.encrypted = encrypted;
    }

    public void setSenderName(String senderName) {
        this.senderName = senderName;
    }

    // ─── Mesh Networking Methods ────────────────────────────────────────

    /**
     * Increments the hop count by 1.
     * Called each time a relay node forwards this message to the next device.
     */
    public void incrementHopCount() {
        this.hopCount++;
    }

    /**
     * Checks if this message is allowed to be forwarded further.
     *
     * Returns false once the hop count reaches MAX_HOPS, which prevents
     * messages from bouncing endlessly around the mesh network.
     *
     * @return true if hopCount < MAX_HOPS (message can still travel further)
     */
    public boolean canForward() {
        return hopCount < MAX_HOPS;
    }

    /**
     * Formats the timestamp into a human-readable time (e.g., "14:30").
     * Used in the chat UI next to each message bubble.
     *
     * @return Time string in HH:mm format
     */
    public String getFormattedTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }

    /**
     * Creates an independent copy of this message.
     *
     * WHY? When we encrypt a message for network transmission, we don't
     * want to modify the original message that's displayed in the UI.
     * So we create a copy, encrypt the copy, and send the copy over
     * the network — the original stays readable on screen.
     *
     * @return A new Message object with identical field values
     */
    public Message copy() {
        Message clone = new Message(this.content, this.type);
        clone.id = this.id;                           // Same ID for deduplication
        clone.senderId = this.senderId;
        clone.senderName = this.senderName;
        clone.timestamp = this.timestamp;
        clone.hopCount = this.hopCount;
        clone.originalSenderId = this.originalSenderId;
        clone.encrypted = this.encrypted;
        return clone;
    }
}