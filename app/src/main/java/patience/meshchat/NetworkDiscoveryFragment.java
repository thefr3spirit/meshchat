package patience.meshchat;

import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;

/**
 * NetworkDiscoveryFragment — shown when the user hasn't joined any network yet.
 *
 * Automatically starts scanning for nearby Bluetooth devices advertising
 * a "MC_<networkName>" name. Discovered networks are displayed in a list
 * so the user can tap to join one immediately.
 *
 * The "Create Network" button lets the user invent a new name and become
 * the first node in a fresh network.
 */
public class NetworkDiscoveryFragment extends Fragment {

    private RecyclerView recyclerNetworks;
    private ProgressBar scanProgress;
    private TextView statusText;
    private ExtendedFloatingActionButton createFab;

    private NetworkAdapter networkAdapter;
    private final List<Network> networks = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_network_discovery, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        recyclerNetworks = view.findViewById(R.id.recyclerNetworks);
        scanProgress = view.findViewById(R.id.scanProgress);
        statusText = view.findViewById(R.id.scanStatusText);
        createFab = view.findViewById(R.id.fabCreateNetwork);

        networkAdapter = new NetworkAdapter(networks, this::onNetworkTapped);
        recyclerNetworks.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerNetworks.setAdapter(networkAdapter);

        createFab.setOnClickListener(v -> showCreateNetworkDialog());
        startScanning();
    }

    @Override
    public void onResume() {
        super.onResume();
        registerListener();
    }

    @Override
    public void onPause() {
        super.onPause();
        MeshService svc = getService();
        if (svc != null && svc.getMeshManager() != null) {
            svc.getMeshManager().setMessageListener(null);
        }
    }

    // ─── Scanning ───────────────────────────────────────────────────────

    private void startScanning() {
        scanProgress.setVisibility(View.VISIBLE);
        statusText.setText(R.string.scanning_for_networks);

        MeshService svc = getService();
        if (svc != null && svc.getMeshManager() != null) {
            svc.getMeshManager().startDiscovery();
        }

        // Hide progress after scan duration
        if (getView() != null) {
            getView().postDelayed(() -> {
                if (getView() == null) return;
                scanProgress.setVisibility(View.GONE);
                if (networks.isEmpty()) {
                    statusText.setText(R.string.no_networks_found);
                } else {
                    statusText.setText(R.string.tap_to_join);
                }
            }, 13_000);
        }
    }

    private void registerListener() {
        MeshService svc = getService();
        if (svc == null || svc.getMeshManager() == null) return;

        svc.getMeshManager().setMessageListener(new MeshManager.MessageListener() {
            @Override
            public void onMessageReceived(Message message) {}

            @Override
            public void onNodeInfoUpdated(List<NodeInfo> nodes) {}

            @Override
            public void onNetworkDiscovered(List<Network> discovered) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    networks.clear();
                    networks.addAll(discovered);
                    networkAdapter.notifyDataSetChanged();
                    if (!networks.isEmpty()) {
                        statusText.setText(R.string.tap_to_join);
                    }
                });
            }

            @Override
            public void onQueueFlushed(int count) {}

            @Override
            public void onDeliveryStatusChanged(String messageId) {}

            @Override
            public void onNetworkJoined(String networkName) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() ->
                            ((MainActivity) requireActivity()).showMainApp());
                }
            }

            @Override
            public void onNetworkLeft() {}
        });

        // Load any already-discovered networks immediately
        List<Network> existing = svc.getMeshManager().getDiscoveredNetworks();
        if (!existing.isEmpty()) {
            networks.clear();
            networks.addAll(existing);
            networkAdapter.notifyDataSetChanged();
            statusText.setText(R.string.tap_to_join);
        }
    }

    // ─── Join network ────────────────────────────────────────────────────

    private void onNetworkTapped(Network network) {
        MeshService svc = getService();
        if (svc == null || svc.getMeshManager() == null) return;
        svc.getMeshManager().joinNetwork(network.name);
        // onNetworkJoined callback will trigger showMainApp()
    }

    // ─── Create network dialog ───────────────────────────────────────────

    private void showCreateNetworkDialog() {
        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.network_name_hint);
        input.setSingleLine(true);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.create_network_title)
                .setMessage(R.string.create_network_message)
                .setView(input)
                .setPositiveButton(R.string.create, (dialog, which) -> {
                    String name = input.getText() != null
                            ? input.getText().toString().trim() : "";
                    if (TextUtils.isEmpty(name)) {
                        showSnackbar(getString(R.string.network_name_empty));
                        return;
                    }
                    // Sanitize: remove MC_ prefix if user accidentally typed it
                    if (name.startsWith("MC_")) name = name.substring(3);
                    if (name.isEmpty()) {
                        showSnackbar(getString(R.string.network_name_empty));
                        return;
                    }
                    MeshService svc = getService();
                    if (svc != null && svc.getMeshManager() != null) {
                        svc.getMeshManager().createNetwork(name);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
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

    // ═══════════════════════════════════════════════════════════════════
    // INNER ADAPTER
    // ═══════════════════════════════════════════════════════════════════

    interface OnNetworkClickListener {
        void onNetworkClicked(Network network);
    }

    static class NetworkAdapter extends RecyclerView.Adapter<NetworkAdapter.VH> {
        private final List<Network> items;
        private final OnNetworkClickListener listener;

        NetworkAdapter(List<Network> items, OnNetworkClickListener listener) {
            this.items = items;
            this.listener = listener;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_network, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            holder.bind(items.get(position));
        }

        @Override
        public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView networkName, nodeCount;
            MaterialButton joinButton;

            VH(View v) {
                super(v);
                networkName = v.findViewById(R.id.networkName);
                nodeCount = v.findViewById(R.id.networkNodeCount);
                joinButton = v.findViewById(R.id.joinButton);
            }

            void bind(Network net) {
                networkName.setText(net.name);
                nodeCount.setText(net.getNodeCountText());
                joinButton.setOnClickListener(v -> listener.onNetworkClicked(net));
                itemView.setOnClickListener(v -> listener.onNetworkClicked(net));
            }
        }
    }
}
