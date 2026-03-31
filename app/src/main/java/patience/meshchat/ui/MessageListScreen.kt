package patience.meshchat.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.Flow
import patience.meshchat.Message
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─── Colours matching the existing XML theme ────────────────────────────
private val SentBubbleColor = Color(0xFF009688)       // Teal
private val ReceivedBubbleColor = Color(0xFF424242)   // Dark grey
private val HopBadgeColor = Color(0xFF80CBC4)         // Light teal accent
private val StatusSending = Color(0xFFBDBDBD)         // Grey
private val StatusSent = Color(0xFFFFFFFF)            // White
private val StatusDelivered = Color(0xFF4CAF50)       // Green
private val StatusFailed = Color(0xFFF44336)          // Red
private val StatusQueued = Color(0xFFFFC107)          // Amber

/**
 * MessageListScreen — Compose-based chat message timeline.
 *
 * Key APIs used:
 *  - collectAsState() on Room Flow for live updates
 *  - Crossfade for delivery-status icon transitions
 *  - AnimatedContent for hop-count badge number changes
 *  - animateContentSize() for smooth bubble size transitions
 */
@Composable
fun MessageListScreen(
    messagesFlow: Flow<List<ChatUiMessage>>,
    modifier: Modifier = Modifier
) {
    val messages by messagesFlow.collectAsState(initial = emptyList())
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(items = messages, key = { it.id }) { message ->
            if (message.isSent) {
                SentMessageBubble(message)
            } else {
                ReceivedMessageBubble(message)
            }
        }
    }
}

// ─── Sent Message Bubble ────────────────────────────────────────────────

@Composable
private fun SentMessageBubble(message: ChatUiMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp),
            color = SentBubbleColor,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .animateContentSize()                  // ← smooth resize
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = message.content,
                    color = Color.White,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = formatTime(message.timestamp),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp
                    )

                    // Delivery status with Crossfade animation
                    if (message.channelType == Message.CHANNEL_PRIVATE) {
                        Spacer(modifier = Modifier.width(6.dp))
                        DeliveryStatusIndicator(status = message.status)
                    }
                }
            }
        }
    }
}

// ─── Received Message Bubble ────────────────────────────────────────────

@Composable
private fun ReceivedMessageBubble(message: ChatUiMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
            color = ReceivedBubbleColor,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .animateContentSize()                  // ← smooth resize
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                // Sender name + animated hop count badge
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = message.senderName,
                        color = HopBadgeColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )

                    if (message.hopCount > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        HopCountBadge(hopCount = message.hopCount)
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = message.content,
                    color = Color.White,
                    fontSize = 15.sp
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = formatTime(message.timestamp),
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

// ─── Delivery Status Indicator (Crossfade) ──────────────────────────────

/**
 * Animated delivery status using Crossfade.
 *
 * Crossfade smoothly transitions between different status states
 * (e.g. Sending → Sent → Delivered) instead of an abrupt swap.
 */
@Composable
private fun DeliveryStatusIndicator(status: MessageStatus) {
    Crossfade(targetState = status, label = "deliveryStatus") { currentStatus ->
        val (text, color) = when (currentStatus) {
            MessageStatus.Sending -> "⏳" to StatusSending
            MessageStatus.Sent -> "✓" to StatusSent
            MessageStatus.Delivered -> "✓✓" to StatusDelivered
            MessageStatus.Failed -> "✗" to StatusFailed
            MessageStatus.Queued -> "◷" to StatusQueued
        }
        Text(
            text = text,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ─── Hop Count Badge (AnimatedContent) ──────────────────────────────────

/**
 * Animated hop count badge using AnimatedContent.
 *
 * AnimatedContent animates between different hop count values,
 * providing a smooth numerical transition when the hop count changes
 * (e.g. when a forwarded message's hop count is updated).
 */
@Composable
private fun HopCountBadge(hopCount: Int) {
    AnimatedContent(targetState = hopCount, label = "hopCount") { count ->
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(HopBadgeColor.copy(alpha = 0.3f))
                .padding(horizontal = 6.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$count hop${if (count != 1) "s" else ""}",
                color = HopBadgeColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ─── Time formatter ─────────────────────────────────────────────────────

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun formatTime(timestamp: Long): String {
    return timeFormat.format(Date(timestamp))
}
