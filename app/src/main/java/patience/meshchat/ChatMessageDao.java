package patience.meshchat;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

import kotlinx.coroutines.flow.Flow;

/**
 * ChatMessageDao — Room DAO exposing Flow-based queries for the Compose UI.
 *
 * The Flow return type allows the Compose message list to use
 * collectAsState() for automatic live updates whenever a row is
 * inserted or a delivery status changes.
 */
@Dao
public interface ChatMessageDao {

    /** Insert or replace a chat message */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(ChatMessageEntity message);

    /** Observe all messages for a conversation, ordered by timestamp */
    @Query("SELECT * FROM chat_messages WHERE conversation_id = :conversationId ORDER BY timestamp ASC")
    Flow<List<ChatMessageEntity>> observeMessages(String conversationId);

    /** Update the delivery status of a specific message */
    @Query("UPDATE chat_messages SET delivery_status = :status WHERE message_id = :messageId")
    void updateDeliveryStatus(String messageId, int status);

    /** Get all messages for a conversation (non-reactive, for migration) */
    @Query("SELECT * FROM chat_messages WHERE conversation_id = :conversationId ORDER BY timestamp ASC")
    List<ChatMessageEntity> getMessages(String conversationId);

    /** Delete all messages for a conversation */
    @Query("DELETE FROM chat_messages WHERE conversation_id = :conversationId")
    void deleteConversation(String conversationId);
}
