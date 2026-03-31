package patience.meshchat.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import kotlinx.coroutines.flow.Flow

/**
 * Helper that wires [MessageListScreen] into a [ComposeView] from Java code.
 *
 * Java cannot call `@Composable` functions directly (they require a Composer
 * argument injected by the Kotlin Compose compiler plugin). This bridge
 * exposes a plain function that Java/Fragment code can call safely.
 */
object ComposeMessageListHelper {

    /**
     * Sets the [ComposeView] content to a [MessageListScreen] driven by
     * the given [messagesFlow].
     *
     * Call from Java:
     * ```java
     * ComposeMessageListHelper.INSTANCE.bind(composeView, viewModel.observeMessages(id));
     * ```
     */
    @JvmStatic
    fun bind(composeView: ComposeView, messagesFlow: Flow<List<ChatUiMessage>>) {
        composeView.setContent {
            MessageListScreen(messagesFlow = messagesFlow, modifier = Modifier)
        }
    }
}
