package patience.meshchat;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;

/**
 * NetworkTopologyFragment — visual mesh map and network controls.
 *
 * Shows:
 *  - A canvas-drawn topology view: your node in the centre, peers
 *    arranged around it with lines showing direct Bluetooth links.
 *  - Live stats: connected node count, queued messages, RSSI threshold.
 *  - RSSI threshold slider to tune auto-connection distance.
 *  - "Leave Network" button.
 */
public class NetworkTopologyFragment extends Fragment {

    private TopologyView topologyView;
    private TextView statNodes, statQueued, networkNameText, rssiLabel;
    private Slider rssiSlider;
    private MaterialButton leaveButton;

    private final List<NodeInfo> connectedNodes = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_network_topology, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        android.widget.FrameLayout topologyContainer =
                view.findViewById(R.id.topologyContainer);
        topologyView = new TopologyView(requireContext());
        topologyContainer.addView(topologyView);
        statNodes = view.findViewById(R.id.statNodes);
        statQueued = view.findViewById(R.id.statQueued);
        networkNameText = view.findViewById(R.id.networkNameLabel);
        rssiLabel = view.findViewById(R.id.rssiValueLabel);
        rssiSlider = view.findViewById(R.id.rssiSlider);
        leaveButton = view.findViewById(R.id.leaveNetworkButton);

        MeshService svc = getService();
        if (svc != null && svc.getMeshManager() != null) {
            // Load current network name
            String netName = svc.getMeshManager().getCurrentNetworkName();
            networkNameText.setText(netName != null ? netName : "—");

            // Initialise slider with current threshold
            int threshold = svc.getMeshManager().getRssiThreshold();
            rssiSlider.setValue(threshold);
            rssiLabel.setText(threshold + " dBm");
        }

        rssiSlider.addOnChangeListener((slider, value, fromUser) -> {
            int v = (int) value;
            rssiLabel.setText(v + " dBm");
            MeshService s = getService();
            if (fromUser && s != null && s.getMeshManager() != null) {
                s.getMeshManager().setRssiThreshold(v);
            }
        });

        leaveButton.setOnClickListener(v -> {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.leave_network_title)
                    .setMessage(R.string.leave_network_message)
                    .setPositiveButton(R.string.leave, (d, w) -> {
                        MeshService s = getService();
                        if (s != null && s.getMeshManager() != null) {
                            s.getMeshManager().leaveNetwork();
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshStats();
        registerListener();
    }

    private void refreshStats() {
        MeshService svc = getService();
        if (svc == null || svc.getMeshManager() == null) return;

        connectedNodes.clear();
        connectedNodes.addAll(svc.getMeshManager().getConnectedNodeInfos());
        topologyView.setNodes(connectedNodes,
                svc.getMeshManager().getUsername() != null
                        ? svc.getMeshManager().getUsername() : "Me");
        updateStats(svc);
    }

    private void updateStats(MeshService svc) {
        if (getActivity() == null) return;
        statNodes.setText(String.valueOf(svc.getMeshManager().getConnectedNodeCount()));
        statQueued.setText(String.valueOf(svc.getMeshManager().getQueuedMessageCount()));
    }

    private void registerListener() {
        MeshService svc = getService();
        if (svc == null || svc.getMeshManager() == null) return;

        svc.getMeshManager().setMessageListener(new MeshManager.MessageListener() {
            @Override public void onMessageReceived(Message message) {
                if (getActivity() != null)
                    getActivity().runOnUiThread(() ->
                            ((MainActivity) requireActivity()).dispatchMessage(message));
            }
            @Override public void onNodeInfoUpdated(List<NodeInfo> nodes) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    connectedNodes.clear();
                    connectedNodes.addAll(nodes);
                    MeshService s = getService();
                    if (s != null) {
                        topologyView.setNodes(connectedNodes,
                                s.getMeshManager().getUsername());
                        updateStats(s);
                    }
                });
            }
            @Override public void onNetworkDiscovered(List<Network> networks) {}
            @Override public void onQueueFlushed(int count) {
                if (getActivity() != null)
                    getActivity().runOnUiThread(() -> updateStats(getService()));
            }
            @Override public void onDeliveryStatusChanged(String messageId) {
                if (getActivity() != null)
                    getActivity().runOnUiThread(() ->
                            ((MainActivity) requireActivity()).handleDeliveryStatus(messageId));
            }
            @Override public void onNetworkJoined(String name) {}
            @Override public void onNetworkLeft() {
                if (getActivity() != null)
                    getActivity().runOnUiThread(() ->
                            ((MainActivity) requireActivity()).showDiscoveryScreen());
            }
        });
    }

    private MeshService getService() {
        if (getActivity() instanceof MainActivity) {
            return ((MainActivity) getActivity()).getMeshService();
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════
    // TOPOLOGY VIEW — Custom Canvas drawing
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Draws the mesh topology:
     *  - Local node (YOU) in the centre as a filled teal circle
     *  - Peer nodes arranged in a circle around the centre
     *  - Lines connecting each peer to the local node (direct BT links)
     *  - Username label beneath each circle
     *  - Signal strength shown by varying line opacity
     */
    public static class TopologyView extends View {

        private static final int NODE_RADIUS_DP = 28;
        private static final int LABEL_SIZE_SP = 12;

        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint nodePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint selfPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint avatarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private List<NodeInfo> nodes = new ArrayList<>();
        private String myUsername = "Me";
        private float density;

        public TopologyView(Context ctx) {
            super(ctx);
            init(ctx);
        }

        public TopologyView(Context ctx, @Nullable AttributeSet attrs) {
            super(ctx, attrs);
            init(ctx);
        }

        private void init(Context ctx) {
            density = ctx.getResources().getDisplayMetrics().density;

            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeWidth(2 * density);
            linePaint.setColor(Color.parseColor("#80B2DFDB"));

            nodePaint.setStyle(Paint.Style.FILL);
            nodePaint.setColor(Color.parseColor("#4DB6AC")); // teal light

            selfPaint.setStyle(Paint.Style.FILL);
            selfPaint.setColor(Color.parseColor("#00897B")); // teal dark

            textPaint.setTextSize(LABEL_SIZE_SP * density);
            textPaint.setColor(Color.parseColor("#212121"));
            textPaint.setTextAlign(Paint.Align.CENTER);

            avatarPaint.setTextSize(14 * density);
            avatarPaint.setColor(Color.WHITE);
            avatarPaint.setTextAlign(Paint.Align.CENTER);
            avatarPaint.setFakeBoldText(true);
        }

        public void setNodes(List<NodeInfo> nodes, String myUsername) {
            this.nodes = new ArrayList<>(nodes);
            this.myUsername = myUsername != null ? myUsername : "Me";
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);

            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float nodeRadius = NODE_RADIUS_DP * density;
            float orbitRadius = Math.min(cx, cy) * 0.62f;

            // ── Draw connection lines first (behind nodes) ──
            int n = nodes.size();
            for (int i = 0; i < n; i++) {
                NodeInfo peer = nodes.get(i);
                double angle = (2 * Math.PI * i / Math.max(n, 1)) - Math.PI / 2;
                float px = cx + (float) (orbitRadius * Math.cos(angle));
                float py = cy + (float) (orbitRadius * Math.sin(angle));

                // Line opacity based on signal strength
                int bars = peer.getSignalBars();
                int alpha = 80 + bars * 40; // 80–240
                linePaint.setAlpha(alpha);
                canvas.drawLine(cx, cy, px, py, linePaint);
            }

            // ── Draw peer nodes ──
            for (int i = 0; i < n; i++) {
                NodeInfo peer = nodes.get(i);
                double angle = (2 * Math.PI * i / Math.max(n, 1)) - Math.PI / 2;
                float px = cx + (float) (orbitRadius * Math.cos(angle));
                float py = cy + (float) (orbitRadius * Math.sin(angle));

                canvas.drawCircle(px, py, nodeRadius, nodePaint);
                canvas.drawText(peer.getInitial(), px, py + avatarPaint.getTextSize() / 3f, avatarPaint);
                canvas.drawText(peer.username, px, py + nodeRadius + textPaint.getTextSize() + 4 * density, textPaint);
            }

            // ── Draw local node (YOU) in centre ──
            canvas.drawCircle(cx, cy, nodeRadius * 1.2f, selfPaint);
            String initial = myUsername.isEmpty() ? "?" :
                    String.valueOf(myUsername.charAt(0)).toUpperCase();
            canvas.drawText(initial, cx, cy + avatarPaint.getTextSize() / 3f, avatarPaint);
            canvas.drawText(myUsername + " (you)",
                    cx, cy + nodeRadius * 1.2f + textPaint.getTextSize() + 4 * density, textPaint);

            // ── Empty state ──
            if (n == 0) {
                Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                emptyPaint.setTextSize(14 * density);
                emptyPaint.setColor(Color.parseColor("#9E9E9E"));
                emptyPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("No peers connected", cx, cy + nodeRadius * 2f, emptyPaint);
            }
        }
    }
}
