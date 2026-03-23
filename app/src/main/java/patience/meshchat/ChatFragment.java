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

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;

/**
 * ChatFragment — the main messaging screen.
 *
 * Extracted from the original monolithic MainActivity so the app can
 * use a bottom navigation bar with multiple tabs.
 *
 * This fragment handles:
 *  - Displaying the message list (RecyclerView)
 *  - Sending messages through MeshManager
 *  - Visibility toggle (Visible / Hidden)
 *  - Peer discovery (Scan → BottomSheetDialog with peer list)
 *  - Status bar updates (connected node count, queued messages)
 *
 * It accesses MeshService through the parent Activity:
 *   ((MainActivity) requireActivity()).getMeshService()
 */
public class ChatFragment extends Fragment {

    private MessageAdapter messageAdapter;
    private ArrayList<Message> messages = new ArrayList<>();

    // ─── UI Elements ────────────────────────────────────────
    private RecyclerView recyclerView;
    private android.widget.EditText messageInput;
    private View sendButton;
    private TextView connectionStatus;
    private View statusDot;
    private MaterialButton scanButton;
    private MaterialButton visibilityToggle;

    /** Whether user is currently visible to mesh */
    private boolean isVisible = true;

    /** Peer list for the scan bottom sheet */
    private List<PeerAdapter.PeerInfo> discoveredPeers = new ArrayList<>();
    private PeerAdapter peerAdapter;
    private BottomSheetDialog peerDialog;

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
        initializeViews(view);
        setupRecyclerView();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Re-register the mesh listener whenever we become the active tab
        setupMeshListener();
    }

    // ─── View initialization ────────────────────────────────

    private void initializeViews(View view) {
        recyclerView = view.findViewById(R.id.recyclerMessages);
        messageInput = view.findViewById(R.id.messageInput);
        sendButton = view.findViewById(R.id.sendButton);
        connectionStatus = view.findViewById(R.id.connectionStatus);
        statusDot = view.findViewById(R.id.statusDot);
        scanButton = view.findViewById(R.id.scanButton);
        visibilityToggle = view.findViewById(R.id.visibilityToggle);

        sendButton.setOnClickListener(v -> sendMessage());
        scanButton.setOnClickListener(v -> startScanning());
        visibilityToggle.setOnClickListener(v -> toggleVisibility());
    }

    private void setupRecyclerView() {
        messageAdapter = new MessageAdapter(messages);
        LinearLayoutManager lm = new LinearLayoutManager(requireContext());
        lm.setStackFromEnd(true);
        recyclerView.setLayoutManager(lm);
        recyclerView.setAdapter(messageAdapter);
    }

    // ─── Access the MeshService through parent Activity ─────

    private MeshService getMeshService() {
        MainActivity activity = (MainActivity) requireActivity();
        return activity.getMeshService();
    }

    private boolean isServiceBound() {
        MainActivity activity = (MainActivity) requireActivity();
        return activity.isServiceBound();
    }

    // ─── Visibility Toggle ──────────────────────────────────

    private void toggleVisibility() {
        isVisible = !isVisible;

        MeshService service = getMeshService();
        if (isServiceBound() && service != null && service.getMeshManager() != null) {
            service.getMeshManager().setVisible(isVisible);
        }

        if (isVisible) {
            visibilityToggle.setText(R.string.visible);
            visibilityToggle.setIconResource(android.R.drawable.presence_online);
            visibilityToggle.setIconTintResource(R.color.statusOnline);
            showSnackbar(getString(R.string.visibility_on));
        } else {
            visibilityToggle.setText(R.string.hidden);
            visibilityToggle.setIconResource(android.R.drawable.presence_invisible);
            visibilityToggle.setIconTintResource(R.color.statusOffline);
            showSnackbar(getString(R.string.visibility_off));
        }
    }

    // ─── Peer Discovery / Scan ──────────────────────────────

    private void startScanning() {
        MeshService service = getMeshService();
        if (!isServiceBound() || service == null || service.getMeshManager() == null) {
            showSnackbar("Mesh service not ready. Please wait...");
            return;
        }

        service.getMeshManager().startDiscovery();
        showPeerDialog();
    }

    private void showPeerDialog() {
        peerDialog = new BottomSheetDialog(requireContext());
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_peer_list, null);

        RecyclerView peerRecycler = dialogView.findViewById(R.id.peerRecycler);
        View scanningIndicator = dialogView.findViewById(R.id.scanningIndicator);
        TextView emptyState = dialogView.findViewById(R.id.emptyState);

        discoveredPeers = new ArrayList<>();
        MeshService service = getMeshService();
        if (isServiceBound() && service != null && service.getMeshManager() != null) {
            discoveredPeers.addAll(service.getMeshManager().getDiscoveredPeers());
        }

        peerAdapter = new PeerAdapter(discoveredPeers, peer -> {
            if (isServiceBound() && getMeshService() != null
                    && getMeshService().getMeshManager() != null) {
                getMeshService().getMeshManager().connectToPeer(peer);
                showSnackbar(String.format(getString(R.string.connecting_to), peer.name));
            }
        });

        peerRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        peerRecycler.setAdapter(peerAdapter);

        if (discoveredPeers.isEmpty()) {
            emptyState.setVisibility(View.GONE);
        }

        // Hide scanning indicator after scan completes (~12s)
        peerRecycler.postDelayed(() -> {
            scanningIndicator.setVisibility(View.GONE);
            if (discoveredPeers.isEmpty()) {
                emptyState.setVisibility(View.VISIBLE);
            }
        }, 13000);

        peerDialog.setContentView(dialogView);
        peerDialog.show();
    }

    // ─── Mesh Listener ──────────────────────────────────────

    /**
     * Registers this fragment as the MeshManager's message listener.
     * When user switches to a different tab, onPause() fires but we
     * don't remove the listener — messages still accumulate in the list.
     */
    public void setupMeshListener() {
        MeshService service = getMeshService();
        if (service == null || service.getMeshManager() == null) return;

        String username = requireActivity().getSharedPreferences(
                RegistrationActivity.PREFS_NAME,
                requireContext().MODE_PRIVATE
        ).getString(RegistrationActivity.KEY_USERNAME, "Unknown");

        service.getMeshManager().setMessageListener(new MeshManager.MessageListener() {
            @Override
            public void onMessageReceived(Message message) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    messages.add(message);
                    messageAdapter.notifyItemInserted(messages.size() - 1);
                    recyclerView.smoothScrollToPosition(messages.size() - 1);
                });
            }

            @Override
            public void onNodeConnected(String nodeId) {
                updateStatus();
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() ->
                        showSnackbar(String.format(getString(R.string.connected_to), nodeId)));
                }
            }

            @Override
            public void onNodeDisconnected(String nodeId) {
                updateStatus();
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() ->
                        showSnackbar(String.format(getString(R.string.disconnected_from), nodeId)));
                }
            }

            @Override
            public void onQueueFlushed(int messageCount) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() ->
                        showSnackbar(String.format(getString(R.string.queue_flushed), messageCount)));
                }
            }

            @Override
            public void onPeerDiscovered(List<PeerAdapter.PeerInfo> peers) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    if (peerAdapter != null && peerDialog != null && peerDialog.isShowing()) {
                        discoveredPeers.clear();
                        discoveredPeers.addAll(peers);
                        peerAdapter.notifyDataSetChanged();

                        View dialogView = peerDialog.findViewById(R.id.emptyState);
                        if (dialogView != null) {
                            dialogView.setVisibility(
                                    peers.isEmpty() ? View.VISIBLE : View.GONE);
                        }
                    }
                });
            }

            @Override
            public void onScanComplete(int peerCount) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    if (peerCount == 0 && peerDialog != null && peerDialog.isShowing()) {
                        View empty = peerDialog.findViewById(R.id.emptyState);
                        View scanning = peerDialog.findViewById(R.id.scanningIndicator);
                        if (empty != null) empty.setVisibility(View.VISIBLE);
                        if (scanning != null) scanning.setVisibility(View.GONE);
                    }
                });
            }
        });
        updateStatus();
    }

    // ─── Status Updates ─────────────────────────────────────

    private void updateStatus() {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            MeshService service = getMeshService();
            if (service == null || service.getMeshManager() == null) return;

            int nodeCount = service.getMeshManager().getConnectedNodeCount();
            int queuedCount = service.getMeshManager().getQueuedMessageCount();

            String status;
            if (queuedCount > 0) {
                status = String.format(getString(R.string.mesh_status_queued),
                        nodeCount, queuedCount);
            } else {
                status = String.format(getString(R.string.mesh_status), nodeCount);
            }
            connectionStatus.setText(status);

            // Update status dot color
            GradientDrawable dot = (GradientDrawable) statusDot.getBackground();
            if (nodeCount > 0) {
                dot.setColor(requireContext().getColor(R.color.statusOnline));
            } else {
                dot.setColor(requireContext().getColor(R.color.statusOffline));
            }
        });
    }

    // ─── Messaging ──────────────────────────────────────────

    private void sendMessage() {
        String content = messageInput.getText().toString().trim();
        if (content.isEmpty()) return;

        MeshService service = getMeshService();
        if (!isServiceBound() || service == null || service.getMeshManager() == null) {
            showSnackbar("Mesh service not ready. Please wait...");
            return;
        }

        // Get current username from SharedPreferences
        String username = requireActivity().getSharedPreferences(
                RegistrationActivity.PREFS_NAME,
                requireContext().MODE_PRIVATE
        ).getString(RegistrationActivity.KEY_USERNAME, "Unknown");

        Message message = new Message(content, Message.TYPE_SENT, username);

        messages.add(message);
        messageAdapter.notifyItemInserted(messages.size() - 1);
        recyclerView.smoothScrollToPosition(messages.size() - 1);

        service.getMeshManager().broadcastMessage(message);
        messageInput.setText("");

        if (service.getMeshManager().getConnectedNodeCount() == 0) {
            showSnackbar(getString(R.string.no_peers));
        }

        updateStatus();
    }

    // ─── Snackbar ───────────────────────────────────────────

    private void showSnackbar(String text) {
        View view = getView();
        if (view != null) {
            Snackbar.make(view, text, Snackbar.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (peerDialog != null && peerDialog.isShowing()) {
            peerDialog.dismiss();
        }
    }
}
