package patience.meshchat;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ConversationsFragment — the hub screen showing all active chat threads.
 *
 * Lists:
 *  1. Group Chat — always present at the top
 *  2. Private conversations — one per peer the user has messaged
 *
 * Data lives in MainActivity (conversations map). This fragment reads it
 * and opens ChatFragment when the user taps a thread.
 */
public class ConversationsFragment extends Fragment {

    private RecyclerView recyclerConversations;
    private ConversationAdapter conversationAdapter;
    private final List<Conversation> displayList = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_conversations, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        recyclerConversations = view.findViewById(R.id.recyclerConversations);
        conversationAdapter = new ConversationAdapter(displayList,
                conv -> ((MainActivity) requireActivity()).openChat(conv));
        recyclerConversations.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerConversations.setAdapter(conversationAdapter);
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshList();
    }

    /** Called by MainActivity when a new message arrives so unread counts update */
    public void refreshList() {
        if (getActivity() == null) return;
        MainActivity activity = (MainActivity) requireActivity();
        Map<String, Conversation> convs = activity.getConversations();

        displayList.clear();

        // Group chat first
        Conversation group = convs.get(Conversation.GROUP_ID);
        if (group != null) displayList.add(group);

        // Private conversations sorted newest-first
        convs.values().stream()
                .filter(c -> !c.isGroup)
                .sorted((a, b) -> Long.compare(b.lastTimestamp, a.lastTimestamp))
                .forEach(displayList::add);

        if (conversationAdapter != null) {
            conversationAdapter.notifyDataSetChanged();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // INNER ADAPTER
    // ═══════════════════════════════════════════════════════════════════

    interface OnConversationClickListener {
        void onConversationClicked(Conversation conversation);
    }

    static class ConversationAdapter
            extends RecyclerView.Adapter<ConversationAdapter.VH> {

        private final List<Conversation> items;
        private final OnConversationClickListener listener;

        ConversationAdapter(List<Conversation> items,
                            OnConversationClickListener listener) {
            this.items = items;
            this.listener = listener;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_conversation, parent, false);
            return new VH(v, listener);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            holder.bind(items.get(position));
        }

        @Override
        public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView avatarText, convName, lastMsg, timeLabel, unreadBadge;
            OnConversationClickListener listener;

            VH(View v, OnConversationClickListener listener) {
                super(v);
                this.listener = listener;
                avatarText = v.findViewById(R.id.convAvatarText);
                convName = v.findViewById(R.id.convName);
                lastMsg = v.findViewById(R.id.convLastMessage);
                timeLabel = v.findViewById(R.id.convTime);
                unreadBadge = v.findViewById(R.id.unreadBadge);
            }

            void bind(Conversation conv) {
                convName.setText(conv.name);
                lastMsg.setText(conv.lastMessage.isEmpty() ? "No messages yet" : conv.lastMessage);

                // Avatar: first letter or group icon character
                String initial = conv.isGroup ? "#" : (conv.name.isEmpty() ? "?" :
                        String.valueOf(conv.name.charAt(0)).toUpperCase());
                avatarText.setText(initial);

                // Timestamp
                if (conv.lastTimestamp > 0) {
                    SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
                    timeLabel.setText(sdf.format(new Date(conv.lastTimestamp)));
                } else {
                    timeLabel.setText("");
                }

                // Unread badge
                if (conv.unreadCount > 0) {
                    unreadBadge.setVisibility(View.VISIBLE);
                    unreadBadge.setText(conv.unreadCount > 99 ? "99+" :
                            String.valueOf(conv.unreadCount));
                } else {
                    unreadBadge.setVisibility(View.GONE);
                }

                itemView.setOnClickListener(v -> listener.onConversationClicked(conv));
            }
        }
    }
}
