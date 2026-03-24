package patience.meshchat;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * MessageAdapter — RecyclerView adapter for the chat message timeline.
 *
 * Two view types:
 *  - TYPE_SENT (1): right-aligned teal bubble
 *  - TYPE_RECEIVED (0): left-aligned grey bubble with sender name above
 *
 * Delivery status indicator on sent private messages:
 *  - "✓"  = DELIVERY_SENT (transmitted)
 *  - "✓✓" = DELIVERY_DELIVERED (recipient ACK received)
 */
public class MessageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private final List<Message> messages;

    public MessageAdapter(List<Message> messages) {
        this.messages = messages;
    }

    @Override
    public int getItemViewType(int position) {
        return messages.get(position).getType();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == Message.TYPE_SENT) {
            return new SentVH(inf.inflate(R.layout.item_message_sent, parent, false));
        } else {
            return new ReceivedVH(inf.inflate(R.layout.item_message_received, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Message msg = messages.get(position);
        if (holder instanceof SentVH) ((SentVH) holder).bind(msg);
        else ((ReceivedVH) holder).bind(msg);
    }

    @Override
    public int getItemCount() { return messages.size(); }

    // ─── Sent ViewHolder ─────────────────────────────────────────────────

    static class SentVH extends RecyclerView.ViewHolder {
        final TextView messageText, timeText, deliveryStatus;

        SentVH(View v) {
            super(v);
            messageText = v.findViewById(R.id.messageText);
            timeText = v.findViewById(R.id.timeText);
            deliveryStatus = v.findViewById(R.id.deliveryStatus);
        }

        void bind(Message msg) {
            messageText.setText(msg.getContent());
            timeText.setText(msg.getFormattedTime());

            if (deliveryStatus != null) {
                if (msg.getChannelType() == Message.CHANNEL_PRIVATE) {
                    deliveryStatus.setVisibility(View.VISIBLE);
                    deliveryStatus.setText(
                            msg.getDeliveryStatus() == Message.DELIVERY_DELIVERED ? "✓✓" : "✓");
                } else {
                    deliveryStatus.setVisibility(View.GONE);
                }
            }
        }
    }

    // ─── Received ViewHolder ─────────────────────────────────────────────

    static class ReceivedVH extends RecyclerView.ViewHolder {
        final TextView messageText, timeText, senderInfo;

        ReceivedVH(View v) {
            super(v);
            messageText = v.findViewById(R.id.messageText);
            timeText = v.findViewById(R.id.timeText);
            senderInfo = v.findViewById(R.id.senderInfo);
        }

        void bind(Message msg) {
            messageText.setText(msg.getContent());
            timeText.setText(msg.getFormattedTime());

            if (senderInfo != null) {
                String hops = msg.getHopCount() > 0
                        ? " · " + msg.getHopCount() + " hop(s)" : "";
                senderInfo.setText(msg.getSenderName() + hops);
                senderInfo.setVisibility(View.VISIBLE);
            }
        }
    }
}
