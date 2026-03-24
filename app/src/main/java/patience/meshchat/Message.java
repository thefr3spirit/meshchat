package patience.meshchat;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * Message — A single message in the MeshChat network.
 *
 * Extended from the original to support:
 *  - Private (addressed) vs broadcast messaging
 *  - Delivery status (sent / delivered via ACK)
 *  - Message TTL to expire stale messages
 *  - Sub-types for handshake and ACK control frames
 */
public class Message implements Serializable {

    private static final long serialVersionUID = 3L;

    // ─── Sub-types ──────────────────────────────────────────────────────

    /** Regular user chat message */
    public static final int SUBTYPE_CHAT = 0;

    /**
     * Handshake — sent immediately after a connection is established.
     * Content format: "username|nodeUUID"
     * Used to exchange display names and UUIDs between newly connected nodes.
     */
    public static final int SUBTYPE_HANDSHAKE = 1;

    /**
     * Acknowledgment — sent by the recipient of a private message to confirm delivery.
     * Content: the original message's UUID.
     */
    public static final int SUBTYPE_ACK = 2;

    // ─── Channel types ──────────────────────────────────────────────────

    /** Message is broadcast to everyone in the network */
    public static final int CHANNEL_BROADCAST = 0;

    /** Message is addressed to a specific node */
    public static final int CHANNEL_PRIVATE = 1;

    // ─── Delivery status ────────────────────────────────────────────────

    /** Message has been sent (or queued) */
    public static final int DELIVERY_SENT = 0;

    /** Recipient has acknowledged receipt of a private message */
    public static final int DELIVERY_DELIVERED = 1;

    // ─── Direction ──────────────────────────────────────────────────────

    /** Message was received from another device */
    public static final int TYPE_RECEIVED = 0;

    /** Message was sent by the current user */
    public static final int TYPE_SENT = 1;

    // ─── Hop limit ──────────────────────────────────────────────────────

    public static final int MAX_HOPS = 10;

    // ─── Default TTL: 24 hours ──────────────────────────────────────────
    private static final long DEFAULT_TTL_MS = 24L * 60 * 60 * 1000;

    // ─── Fields ─────────────────────────────────────────────────────────

    private String id;
    private String content;
    private String senderId;        // Sender's persistent node UUID
    private String senderName;      // Sender's display name
    private long timestamp;
    private int type;               // TYPE_SENT or TYPE_RECEIVED
    private int hopCount;
    private String originalSenderId;
    private boolean encrypted;

    /** Recipient's node UUID. Null means broadcast to everyone. */
    private String recipientId;

    /** CHANNEL_BROADCAST or CHANNEL_PRIVATE */
    private int channelType;

    /** DELIVERY_SENT or DELIVERY_DELIVERED */
    private int deliveryStatus;

    /**
     * Absolute expiry time in ms since epoch.
     * Messages arriving after this time are silently discarded.
     */
    private long ttlMs;

    /** SUBTYPE_CHAT, SUBTYPE_HANDSHAKE, or SUBTYPE_ACK */
    private int subType;

    // ─── Constructors ───────────────────────────────────────────────────

    /** Creates a new outgoing broadcast chat message */
    public Message(String content, int type) {
        this.id = UUID.randomUUID().toString();
        this.content = content;
        this.type = type;
        this.timestamp = System.currentTimeMillis();
        this.hopCount = 0;
        this.senderId = android.os.Build.ID;
        this.senderName = android.os.Build.MODEL;
        this.originalSenderId = this.senderId;
        this.encrypted = false;
        this.recipientId = null;
        this.channelType = CHANNEL_BROADCAST;
        this.deliveryStatus = DELIVERY_SENT;
        this.ttlMs = this.timestamp + DEFAULT_TTL_MS;
        this.subType = SUBTYPE_CHAT;
    }

    public Message(String content, int type, String senderName) {
        this(content, type);
        this.senderName = senderName;
    }

    /** Full constructor for received messages */
    public Message(String content, String senderId, String senderName, int hopCount) {
        this.id = UUID.randomUUID().toString();
        this.content = content;
        this.senderId = senderId;
        this.senderName = senderName;
        this.timestamp = System.currentTimeMillis();
        this.hopCount = hopCount;
        this.type = TYPE_RECEIVED;
        this.originalSenderId = senderId;
        this.encrypted = false;
        this.recipientId = null;
        this.channelType = CHANNEL_BROADCAST;
        this.deliveryStatus = DELIVERY_SENT;
        this.ttlMs = this.timestamp + DEFAULT_TTL_MS;
        this.subType = SUBTYPE_CHAT;
    }

    // ─── Factory methods ────────────────────────────────────────────────

    /**
     * Creates a handshake message to be sent immediately on connection.
     * Content: "username|nodeUUID"
     */
    public static Message createHandshake(String username, String nodeId) {
        Message m = new Message("", SUBTYPE_HANDSHAKE);
        m.id = UUID.randomUUID().toString();
        m.subType = SUBTYPE_HANDSHAKE;
        m.content = username + "|" + nodeId;
        m.senderId = nodeId;
        m.senderName = username;
        m.channelType = CHANNEL_BROADCAST;
        m.encrypted = false;
        m.ttlMs = m.timestamp + DEFAULT_TTL_MS;
        return m;
    }

    /**
     * Creates an ACK message confirming delivery of a private message.
     *
     * @param originalMsgId  The ID of the message being acknowledged
     * @param myNodeId       Our own node UUID (to set as senderId)
     * @param senderNodeId   The original sender's UUID (to route the ACK back)
     */
    public static Message createAck(String originalMsgId, String myNodeId,
                                    String senderNodeId) {
        Message m = new Message("", TYPE_SENT);
        m.id = UUID.randomUUID().toString();
        m.subType = SUBTYPE_ACK;
        m.content = originalMsgId;
        m.senderId = myNodeId;
        m.recipientId = senderNodeId;
        m.channelType = CHANNEL_PRIVATE;
        m.encrypted = false;
        m.ttlMs = m.timestamp + DEFAULT_TTL_MS;
        return m;
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
    public String getRecipientId() { return recipientId; }
    public int getChannelType() { return channelType; }
    public int getDeliveryStatus() { return deliveryStatus; }
    public long getTtlMs() { return ttlMs; }
    public int getSubType() { return subType; }

    // ─── Setters ────────────────────────────────────────────────────────

    public void setContent(String content) { this.content = content; }
    public void setType(int type) { this.type = type; }
    public void setEncrypted(boolean encrypted) { this.encrypted = encrypted; }
    public void setSenderName(String senderName) { this.senderName = senderName; }
    public void setSenderId(String senderId) { this.senderId = senderId; }
    public void setOriginalSenderId(String id) { this.originalSenderId = id; }
    public void setRecipientId(String recipientId) { this.recipientId = recipientId; }
    public void setChannelType(int channelType) { this.channelType = channelType; }
    public void setDeliveryStatus(int status) { this.deliveryStatus = status; }

    // ─── Mesh helpers ───────────────────────────────────────────────────

    public void incrementHopCount() { this.hopCount++; }
    public boolean canForward() { return hopCount < MAX_HOPS; }
    public boolean isExpired() { return System.currentTimeMillis() > ttlMs; }

    /** Whether this is a control frame (handshake or ACK) rather than user content */
    public boolean isControlFrame() {
        return subType == SUBTYPE_HANDSHAKE || subType == SUBTYPE_ACK;
    }

    public String getFormattedTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }

    public Message copy() {
        Message clone = new Message(this.content, this.type);
        clone.id = this.id;
        clone.senderId = this.senderId;
        clone.senderName = this.senderName;
        clone.timestamp = this.timestamp;
        clone.hopCount = this.hopCount;
        clone.originalSenderId = this.originalSenderId;
        clone.encrypted = this.encrypted;
        clone.recipientId = this.recipientId;
        clone.channelType = this.channelType;
        clone.deliveryStatus = this.deliveryStatus;
        clone.ttlMs = this.ttlMs;
        clone.subType = this.subType;
        return clone;
    }
}
