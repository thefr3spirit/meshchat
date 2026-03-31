package patience.meshchat.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import patience.meshchat.ChatMessageEntity
import patience.meshchat.MeshChatDatabase
import patience.meshchat.Message

/**
 * ChatViewModel — Bridges Room persistence with the Compose message list.
 *
 * Exposes a Flow<List<ChatUiMessage>> that the Compose UI observes via
 * collectAsState(). Any database change (new message inserted, delivery
 * status updated) automatically triggers a recomposition.
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val db = MeshChatDatabase.getInstance(application)
    private val dao = db.chatMessageDao()

    /**
     * Returns a Flow of UI-ready messages for the given conversation.
     * The Compose screen collects this with collectAsState().
     */
    fun observeMessages(conversationId: String): Flow<List<ChatUiMessage>> {
        return dao.observeMessages(conversationId).map { entities ->
            entities.map { it.toUiMessage() }
        }
    }

    /** Insert a new message into the database (runs on IO dispatcher) */
    fun insertMessage(message: Message, conversationId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.insert(ChatMessageEntity.fromMessage(message, conversationId))
        }
    }

    /** Update delivery status (e.g. Sent → Delivered after ACK) */
    fun updateDeliveryStatus(messageId: String, status: MessageStatus) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateDeliveryStatus(messageId, status.toEntityStatus())
        }
    }
}

/**
 * ChatUiMessage — Immutable data class consumed by Compose composables.
 */
data class ChatUiMessage(
    val id: String,
    val content: String,
    val senderName: String,
    val timestamp: Long,
    val isSent: Boolean,
    val hopCount: Int,
    val channelType: Int,
    val status: MessageStatus
)

/** Map a ChatMessageEntity to a ChatUiMessage for the Compose layer */
private fun ChatMessageEntity.toUiMessage(): ChatUiMessage {
    return ChatUiMessage(
        id = messageId,
        content = content,
        senderName = senderName ?: "Unknown",
        timestamp = timestamp,
        isSent = type == Message.TYPE_SENT,
        hopCount = hopCount,
        channelType = channelType,
        status = when (deliveryStatus) {
            ChatMessageEntity.STATUS_SENDING -> MessageStatus.Sending
            ChatMessageEntity.STATUS_DELIVERED -> MessageStatus.Delivered
            ChatMessageEntity.STATUS_FAILED -> MessageStatus.Failed
            ChatMessageEntity.STATUS_QUEUED -> MessageStatus.Queued
            else -> MessageStatus.Sent
        }
    )
}

/** Map a MessageStatus sealed class value to the entity int constant */
private fun MessageStatus.toEntityStatus(): Int = when (this) {
    MessageStatus.Sending -> ChatMessageEntity.STATUS_SENDING
    MessageStatus.Sent -> ChatMessageEntity.STATUS_SENT
    MessageStatus.Delivered -> ChatMessageEntity.STATUS_DELIVERED
    MessageStatus.Failed -> ChatMessageEntity.STATUS_FAILED
    MessageStatus.Queued -> ChatMessageEntity.STATUS_QUEUED
}
