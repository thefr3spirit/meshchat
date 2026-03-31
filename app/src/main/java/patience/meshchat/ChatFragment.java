package patience.meshchat;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;

/**
 * ChatFragment — the actual messaging screen for a single conversation.
 *
 * Works for both the group/broadcast channel and private one-on-one threads.
 * Receives a {@link Conversation} via Bundle arguments.
 *
 * KEY CHANGES from original:
 *  - No scan button or visibility toggle (auto-managed)
 *  - Header shows conversation name (peer username or "Group Chat")
 *  - Routes via broadcastMessage (group) or sendPrivateMessage (private)
 *  - Delivery receipts (✓ sent / ✓✓ delivered) for private messages
 *  - Reads only messages belonging to this conversation
 */
public class ChatFragment extends Fragment {

    // Bundle argument keys
    public static final String ARG_CONV_ID = "conv_id";
    public static final String ARG_CONV_NAME = "conv_name";
    public static final String ARG_IS_GROUP = "is_group";
    public static final String ARG_PEER_ID = "peer_id";

    private RecyclerView recyclerMessages;
    private EditText messageInput;
    private ImageButton sendButton;
    private TextView convTitle, statusText;
    private View backButton;

    private MessageAdapter messageAdapter;
    private final List<Message> messages = new ArrayList<>();

    private String conversationId;
    private String conversationName;
    private boolean isGroup;
    private String peerId; // peer's node UUID (null for group)

    // ─── Factory ────────────────────────────────────────────────────────

    public static ChatFragment newInstance(Conversation conv) {
        ChatFragment f = new ChatFragment();
        Bundle args = new Bundle();
        args.putString(ARG_CONV_ID, conv.id);
        args.putString(ARG_CONV_NAME, conv.name);
        args.putBoolean(ARG_IS_GROUP, conv.isGroup);
        args.putString(ARG_PEER_ID, conv.peerId);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            conversationId = getArguments().getString(ARG_CONV_ID);
            conversationName = getArguments().getString(ARG_CONV_NAME, "Chat");
            isGroup = getArguments().getBoolean(ARG_IS_GROUP, true);
            peerId = getArguments().getString(ARG_PEER_ID);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_chat, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        recyclerMessages = view.findViewById(R.id.recyclerMessages);
        messageInput = view.findViewById(R.id.messageInput);
        sendButton = view.findViewById(R.id.sendButton);
        convTitle = view.findViewById(R.id.convTitle);
        statusText = view.findViewById(R.id.connectionStatus);
        backButton = view.findViewById(R.id.backButton);

        convTitle.setText(conversationName);

        // Push the input bar above the system navigation bar
        View inputBar = view.findViewById(R.id.inputBar);
        final int originalBottomPadding = inputBar.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(inputBar, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(),
                    v.getPaddingRight(), originalBottomPadding + bars.bottom);
            return insets;
        });

        LinearLayoutManager lm = new LinearLayoutManager(requireContext());
        lm.setStackFromEnd(true);
        messageAdapter = new MessageAdapter(messages);
        recyclerMessages.setLayoutManager(lm);
        recyclerMessages.setAdapter(messageAdapter);

        sendButton.setOnClickListener(v -> sendMessage());
        if (backButton != null) {
            backButton.setOnClickListener(v -> requireActivity().onBackPressed());
        }

        // Load existing messages from MainActivity's store
        loadExistingMessages();

        updateStatus();
    }

    @Override
    public void onResume() {
        super.onResume();
        setupMeshListener();
    }

    // ─── Load existing messages ──────────────────────────────────────────

    private void loadExistingMessages() {
        if (getActivity() instanceof MainActivity) {
            List<Message> existing =
                    ((MainActivity) requireActivity()).getMessagesForConversation(conversationId);
            messages.clear();
            messages.addAll(existing);
            messageAdapter.notifyDataSetChanged();
            if (!messages.isEmpty()) {
                recyclerMessages.scrollToPosition(messages.size() - 1);
            }
        }
    }

    // ─── Mesh listener ──────────────────────────────────────────────────

    public void setupMeshListener() {
        MeshService svc = getService();
        if (svc == null || svc.getMeshManager() == null) return;

        String myUsername = requireActivity()
                .getSharedPreferences(RegistrationActivity.PREFS_NAME,
                        requireContext().MODE_PRIVATE)
                .getString(RegistrationActivity.KEY_USERNAME, "Unknown");

        svc.getMeshManager().setMessageListener(new MeshManager.MessageListener() {
            @Override
            public void onMessageReceived(Message message) {
                if (getActivity() == null) return;
                // Only show messages relevant to this conversation
                if (!isRelevant(message)) {
                    // Still dispatch to MainActivity for other conversations
                    ((MainActivity) requireActivity()).dispatchMessage(message);
                    return;
                }
                getActivity().runOnUiThread(() -> addMessage(message));
            }

            @Override
            public void onNodeInfoUpdated(List<NodeInfo> nodes) {
                if (getActivity() != null) getActivity().runOnUiThread(() -> updateStatus());
            }

            @Override
            public void onNetworkDiscovered(List<Network> networks) {}

            @Override
            public void onQueueFlushed(int count) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() ->
                            showSnackbar(count + " queued message(s) delivered"));
                }
            }

            @Override
            public void onDeliveryStatusChanged(String messageId) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    ((MainActivity) requireActivity()).handleDeliveryStatus(messageId);
                    // Update delivery indicator in this chat
                    for (int i = 0; i < messages.size(); i++) {
                        if (messages.get(i).getId().equals(messageId)) {
                            messages.get(i).setDeliveryStatus(Message.DELIVERY_DELIVERED);
                            messageAdapter.notifyItemChanged(i);
                            break;
                        }
                    }
                });
            }

            @Override
            public void onNetworkJoined(String networkName) {}

            @Override
            public void onNetworkLeft() {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() ->
                            ((MainActivity) requireActivity()).showDiscoveryScreen());
                }
            }
        });

        updateStatus();
    }

    /**
     * Returns true if this message belongs to the currently displayed conversation.
     *
     * For group: message must be a broadcast
     * For private: message must be from or to the peer in this conversation
     */
    private boolean isRelevant(Message msg) {
        if (isGroup) {
            return msg.getChannelType() == Message.CHANNEL_BROADCAST;
        } else {
            // Private: from peer to us, or control frames for this conversation
            return peerId != null && peerId.equals(msg.getSenderId());
        }
    }

    private void addMessage(Message message) {
        messages.add(message);
        messageAdapter.notifyItemInserted(messages.size() - 1);
        recyclerMessages.smoothScrollToPosition(messages.size() - 1);
        // Also store in MainActivity
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) requireActivity()).storeMessage(conversationId, message);
        }
    }

    // ─── Sending ────────────────────────────────────────────────────────

    private void sendMessage() {
        String text = messageInput.getText().toString().trim();
        if (text.isEmpty()) return;

        MeshService svc = getService();
        if (svc == null || svc.getMeshManager() == null) {
            showSnackbar("Mesh service not ready");
            return;
        }

        String username = requireActivity()
                .getSharedPreferences(RegistrationActivity.PREFS_NAME,
                        requireContext().MODE_PRIVATE)
                .getString(RegistrationActivity.KEY_USERNAME, "Unknown");

        Message message = new Message(text, Message.TYPE_SENT, username);

        // Add to local display immediately
        messages.add(message);
        messageAdapter.notifyItemInserted(messages.size() - 1);
        recyclerMessages.smoothScrollToPosition(messages.size() - 1);
        messageInput.setText("");

        // Store in MainActivity
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) requireActivity()).storeMessage(conversationId, message);
            ((MainActivity) requireActivity()).updateConversationPreview(
                    conversationId, conversationName, isGroup, text);
        }

        // Route appropriately
        if (isGroup) {
            svc.getMeshManager().broadcastMessage(message);
        } else {
            svc.getMeshManager().sendPrivateMessage(message, peerId);
        }

        updateStatus();
    }

    // ─── Status bar ─────────────────────────────────────────────────────

    private void updateStatus() {
        if (getActivity() == null || statusText == null) return;
        getActivity().runOnUiThread(() -> {
            MeshService svc = getService();
            if (svc == null || svc.getMeshManager() == null) return;
            int nodes = svc.getMeshManager().getConnectedNodeCount();
            int queued = svc.getMeshManager().getQueuedMessageCount();
            if (queued > 0) {
                statusText.setText(nodes + " peer(s) · " + queued + " queued");
            } else if (nodes > 0) {
                statusText.setText(nodes + " peer(s) connected");
            } else {
                statusText.setText(R.string.no_devices_found);
            }
        });
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private MeshService getService() {
        if (getActivity() instanceof MainActivity) {
            return ((MainActivity) getActivity()).getMeshService();
        }
        return null;
    }

    private void showSnackbar(String msg) {
        View v = getView();
        if (v != null) Snackbar.make(v, msg, Snackbar.LENGTH_SHORT).show();
    }
}
