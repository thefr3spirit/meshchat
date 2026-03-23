package patience.meshchat;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * ============================================================================
 * MessageAdapter - Bridges message data with the chat UI
 * ============================================================================
 *
 * WHAT IS A RECYCLERVIEW ADAPTER? (for beginners)
 * ───────────────────────────────────────────────
 * RecyclerView is Android's efficient scrollable list. Instead of creating
 * a View for every single message (which wastes memory), it "recycles"
 * views that scroll off-screen and reuses them for new messages.
 *
 * The Adapter is the bridge between your DATA (List<Message>) and the
 * VIEWS (what the user sees). It tells RecyclerView:
 *  - How many items are there?  → getItemCount()
 *  - What does item #N look like?  → onCreateViewHolder() + onBindViewHolder()
 *  - Is this a sent or received message?  → getItemViewType()
 *
 * Two different layouts are used:
 *  - item_message_sent.xml     → Right-aligned, colored bubble (our messages)
 *  - item_message_received.xml → Left-aligned, gray bubble (others' messages)
 *
 * ============================================================================
 */
public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.MessageViewHolder> {

    /** The list of messages to display in the chat */
    private List<Message> messages;

    public MessageAdapter(List<Message> messages) {
        this.messages = messages;
    }

    /**
     * Creates a new ViewHolder for a message bubble.
     * Called when RecyclerView needs a brand new view (not recycled).
     *
     * @param viewType TYPE_SENT (1) or TYPE_RECEIVED (0) — determines which layout to inflate
     */
    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view;
        if (viewType == Message.TYPE_SENT) {
            // Our message → right-aligned bubble
            view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_message_sent, parent, false);
        } else {
            // Someone else's message → left-aligned bubble
            view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_message_received, parent, false);
        }
        return new MessageViewHolder(view);
    }

    /**
     * Fills an existing ViewHolder with data from a specific message.
     * Called when RecyclerView wants to display or recycle a view.
     */
    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        Message message = messages.get(position);
        holder.bind(message);
    }

    /** Returns the total number of messages in the chat */
    @Override
    public int getItemCount() {
        return messages.size();
    }

    /**
     * Returns the view type for the message at this position.
     * RecyclerView uses this to pick the correct layout (sent vs received).
     */
    @Override
    public int getItemViewType(int position) {
        return messages.get(position).getType();
    }

    /**
     * ViewHolder - Holds references to the views inside a message bubble.
     *
     * WHY VIEWHOLDERS? (for beginners)
     * findViewById() is expensive — it searches the entire view tree.
     * ViewHolder stores references to the views so we only call
     * findViewById() once per view, not every time it's recycled.
     * This makes scrolling smooth even with thousands of messages.
     */
    static class MessageViewHolder extends RecyclerView.ViewHolder {
        TextView messageText;  // The actual message content
        TextView timeText;     // Timestamp (e.g., "14:30")
        TextView senderInfo;   // Sender name + hop count (received messages only)

        MessageViewHolder(View itemView) {
            super(itemView);
            messageText = itemView.findViewById(R.id.messageText);
            timeText = itemView.findViewById(R.id.timeText);
            senderInfo = itemView.findViewById(R.id.senderInfo);
        }

        /**
         * Binds a Message object's data to the UI views.
         * For received messages, also shows the sender's device name
         * and how many hops the message traveled through the mesh.
         */
        void bind(Message message) {
            messageText.setText(message.getContent());
            timeText.setText(message.getFormattedTime());

            // For received messages, show sender info and hop count
            if (message.getType() == Message.TYPE_RECEIVED && senderInfo != null) {
                // Example: "Pixel 8 · 2 hops · 🔒"
                String info = message.getSenderName();
                if (message.getHopCount() > 0) {
                    info += " \u00b7 " + message.getHopCount() + " hop(s)";
                }
                info += " \u00b7 \ud83d\udd12"; // Lock emoji = encrypted
                senderInfo.setText(info);
                senderInfo.setVisibility(View.VISIBLE);
            } else if (senderInfo != null) {
                senderInfo.setVisibility(View.GONE);
            }
        }
    }
}
