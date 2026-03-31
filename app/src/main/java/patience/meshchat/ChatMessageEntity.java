package patience.meshchat;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * ChatMessageEntity — Room entity for persisted chat messages.
 *
 * Stores every message displayed in the chat UI so that the Compose
 * message list can observe changes via Room's Flow-returning DAO queries
 * and re-compose automatically when a new message arrives or a delivery
 * status changes.
 */
@Entity(tableName = "chat_messages")
public class ChatMessageEntity {

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "message_id")
    public String messageId;

    @ColumnInfo(name = "conversation_id")
    public String conversationId;

    @ColumnInfo(name = "content")
    public String content;

    @ColumnInfo(name = "sender_id")
    public String senderId;

    @ColumnInfo(name = "sender_name")
    public String senderName;

    @ColumnInfo(name = "timestamp")
    public long timestamp;

    /** Message.TYPE_SENT (1) or Message.TYPE_RECEIVED (0) */
    @ColumnInfo(name = "type")
    public int type;

    /** Number of hops this message traversed */
    @ColumnInfo(name = "hop_count")
    public int hopCount;

    /** Message.CHANNEL_BROADCAST (0) or Message.CHANNEL_PRIVATE (1) */
    @ColumnInfo(name = "channel_type")
    public int channelType;

    /**
     * Delivery status mapped to MessageStatus sealed class values:
     *  0 = Sending, 1 = Sent, 2 = Delivered, 3 = Failed, 4 = Queued
     */
    @ColumnInfo(name = "delivery_status")
    public int deliveryStatus;

    // Status constants matching the MessageStatus sealed class
    public static final int STATUS_SENDING = 0;
    public static final int STATUS_SENT = 1;
    public static final int STATUS_DELIVERED = 2;
    public static final int STATUS_FAILED = 3;
    public static final int STATUS_QUEUED = 4;

    /** Factory: convert a Message into a ChatMessageEntity */
    public static ChatMessageEntity fromMessage(Message msg, String conversationId) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.messageId = msg.getId();
        entity.conversationId = conversationId;
        entity.content = msg.getContent();
        entity.senderId = msg.getSenderId();
        entity.senderName = msg.getSenderName();
        entity.timestamp = msg.getTimestamp();
        entity.type = msg.getType();
        entity.hopCount = msg.getHopCount();
        entity.channelType = msg.getChannelType();
        // Map existing delivery status
        if (msg.getDeliveryStatus() == Message.DELIVERY_DELIVERED) {
            entity.deliveryStatus = STATUS_DELIVERED;
        } else if (msg.getType() == Message.TYPE_SENT) {
            entity.deliveryStatus = STATUS_SENT;
        } else {
            entity.deliveryStatus = STATUS_SENT;
        }
        return entity;
    }
}
