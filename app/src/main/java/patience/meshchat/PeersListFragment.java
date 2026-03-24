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

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/**
 * PeersListFragment — shows all currently connected mesh nodes.
 *
 * Each item displays:
 *  - Avatar circle with the peer's first initial
 *  - Username and BT MAC address
 *  - RSSI signal bars and label (e.g. "Good · -65 dBm")
 *  - "Message" button to start a private conversation
 *
 * Signal bars indicate whether this is a strong direct link or a weaker one.
 * All connected peers are reachable regardless of signal strength.
 */
public class PeersListFragment extends Fragment {

    private RecyclerView recyclerPeers;
    private TextView emptyState;
    private NodeAdapter nodeAdapter;
    private final List<NodeInfo> peers = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_peers_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        recyclerPeers = view.findViewById(R.id.recyclerPeers);
        emptyState = view.findViewById(R.id.emptyPeersState);

        nodeAdapter = new NodeAdapter(peers, this::onMessagePeer);
        recyclerPeers.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerPeers.setAdapter(nodeAdapter);
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshPeers();
        registerListener();
    }

    @Override
    public void onPause() {
        super.onPause();
        // Don't null the listener here — MainActivity re-registers its own listener
    }

    /** Loads current node list from MeshManager */
    public void refreshPeers() {
        MeshService svc = getService();
        if (svc == null || svc.getMeshManager() == null) return;

        peers.clear();
        peers.addAll(svc.getMeshManager().getConnectedNodeInfos());
        updateEmptyState();
        if (nodeAdapter != null) nodeAdapter.notifyDataSetChanged();
    }

    private void registerListener() {
        MeshService svc = getService();
        if (svc == null || svc.getMeshManager() == null) return;

        svc.getMeshManager().setMessageListener(new MeshManager.MessageListener() {
            @Override
            public void onMessageReceived(Message message) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() ->
                            ((MainActivity) requireActivity()).dispatchMessage(message));
                }
            }

            @Override
            public void onNodeInfoUpdated(List<NodeInfo> nodes) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    peers.clear();
                    peers.addAll(nodes);
                    updateEmptyState();
                    if (nodeAdapter != null) nodeAdapter.notifyDataSetChanged();
                });
            }

            @Override
            public void onNetworkDiscovered(List<Network> networks) {}

            @Override
            public void onQueueFlushed(int count) {}

            @Override
            public void onDeliveryStatusChanged(String messageId) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() ->
                            ((MainActivity) requireActivity()).handleDeliveryStatus(messageId));
                }
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
    }

    private void onMessagePeer(NodeInfo node) {
        // Create or find existing private conversation for this peer
        String peerName = node.username;
        Conversation conv = Conversation.createPrivate(node.nodeId, peerName);
        ((MainActivity) requireActivity()).openChat(conv);
    }

    private void updateEmptyState() {
        if (peers.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            recyclerPeers.setVisibility(View.GONE);
        } else {
            emptyState.setVisibility(View.GONE);
            recyclerPeers.setVisibility(View.VISIBLE);
        }
    }

    private MeshService getService() {
        if (getActivity() instanceof MainActivity) {
            return ((MainActivity) getActivity()).getMeshService();
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════
    // INNER ADAPTER
    // ═══════════════════════════════════════════════════════════════════

    interface OnMessageClickListener {
        void onMessageClicked(NodeInfo node);
    }

    static class NodeAdapter extends RecyclerView.Adapter<NodeAdapter.VH> {

        private final List<NodeInfo> items;
        private final OnMessageClickListener listener;

        NodeAdapter(List<NodeInfo> items, OnMessageClickListener listener) {
            this.items = items;
            this.listener = listener;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_peer_node, parent, false);
            return new VH(v, listener);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            holder.bind(items.get(position));
        }

        @Override
        public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView avatarText, username, signalText;
            View signalBar1, signalBar2, signalBar3, signalBar4;
            MaterialButton messageButton;
            OnMessageClickListener listener;

            VH(View v, OnMessageClickListener listener) {
                super(v);
                this.listener = listener;
                avatarText = v.findViewById(R.id.nodeAvatarText);
                username = v.findViewById(R.id.nodeUsername);
                signalText = v.findViewById(R.id.nodeSignalText);
                signalBar1 = v.findViewById(R.id.signalBar1);
                signalBar2 = v.findViewById(R.id.signalBar2);
                signalBar3 = v.findViewById(R.id.signalBar3);
                signalBar4 = v.findViewById(R.id.signalBar4);
                messageButton = v.findViewById(R.id.messageButton);
            }

            void bind(NodeInfo node) {
                avatarText.setText(node.getInitial());
                username.setText(node.username);

                String sigText = node.getSignalLabel();
                if (node.rssi != Integer.MIN_VALUE) {
                    sigText += " · " + node.rssi + " dBm";
                }
                signalText.setText(sigText);

                // Signal bars: filled vs hollow
                int bars = node.getSignalBars();
                int activeColor = itemView.getContext().getColor(R.color.statusOnline);
                int inactiveColor = itemView.getContext().getColor(R.color.statusOffline);
                setBarColor(signalBar1, bars >= 1, activeColor, inactiveColor);
                setBarColor(signalBar2, bars >= 2, activeColor, inactiveColor);
                setBarColor(signalBar3, bars >= 3, activeColor, inactiveColor);
                setBarColor(signalBar4, bars >= 4, activeColor, inactiveColor);

                messageButton.setOnClickListener(v -> listener.onMessageClicked(node));
            }

            private void setBarColor(View bar, boolean active, int on, int off) {
                if (bar.getBackground() instanceof GradientDrawable) {
                    ((GradientDrawable) bar.getBackground()).setColor(active ? on : off);
                }
            }
        }
    }
}
