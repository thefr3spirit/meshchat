package patience.meshchat.ui

/**
 * MessageStatus — Sealed class representing every possible delivery state
 * for a message in the Compose-based message list.
 *
 * Used with Crossfade to animate status icon transitions (e.g. Sending → Sent → Delivered).
 */
sealed class MessageStatus {
    /** Message is being transmitted over the mesh */
    data object Sending : MessageStatus()

    /** Message has been sent (single tick ✓) */
    data object Sent : MessageStatus()

    /** Recipient ACK received (double tick ✓✓) */
    data object Delivered : MessageStatus()

    /** Delivery failed — queued for retry via store-and-forward */
    data object Failed : MessageStatus()

    /** Message is in the offline queue awaiting a route */
    data object Queued : MessageStatus()
}
