package patience.meshchat;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * LECTURE 2 — Network Monitor Fragment
 *
 * This fragment is the visual component for both lecture concepts:
 *
 * 1) **Connectivity Manager (Observer Pattern)**
 *    - Shows the current network state: IDLE, CONNECTED, or ACTIVE
 *    - Displays the transport type (WiFi, Cellular, Bluetooth, etc.)
 *    - ConnectivityObserver registers a NetworkCallback with the system;
 *      whenever the network state changes, our listener is invoked and
 *      we update the UI in real-time.
 *
 * 2) **BroadcastReceiver (System-wide events)**
 *    - Shows a scrolling event log of system broadcasts we receive.
 *    - Events include: Airplane mode toggled, Bluetooth on/off, WiFi on/off.
 *    - Each event is timestamped and color-coded.
 *    - The BroadcastReceiver concept comes from early radio broadcasting —
 *      a single signal sent to all receivers simultaneously.
 *
 * Additionally shows mesh network stats (connected nodes, queued messages)
 * for context alongside the system connectivity data.
 */
public class NetworkFragment extends Fragment implements ConnectivityObserver.Listener {

    // ─── UI Elements ────────────────────────────────────────
    private View networkStateDot;
    private TextView networkStateText;
    private TextView transportText;
    private TextView connectedNodesCount;
    private TextView queuedMessagesCount;
    private TextView visibilityStatus;
    private TextView emptyLogText;
    private RecyclerView eventLogRecycler;

    // ─── Data ───────────────────────────────────────────────
    private ConnectivityObserver connectivityObserver;
    private final List<ConnectivityObserver.Event> eventLog = new ArrayList<>();
    private EventLogAdapter eventLogAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_network, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initializeViews(view);
        setupEventLog();

        // Create the ConnectivityObserver — this is the Lecture 2 core component.
        // We pass 'this' as the listener because this fragment implements the interface.
        connectivityObserver = new ConnectivityObserver(requireContext(), this);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Start monitoring — registers NetworkCallback + BroadcastReceiver
        connectivityObserver.start();
        updateMeshStats();
    }

    @Override
    public void onPause() {
        super.onPause();
        // Stop monitoring — unregisters everything to prevent leaks
        connectivityObserver.stop();
    }

    // ─── View initialization ────────────────────────────────

    private void initializeViews(View view) {
        networkStateDot    = view.findViewById(R.id.networkStateDot);
        networkStateText   = view.findViewById(R.id.networkStateText);
        transportText      = view.findViewById(R.id.transportText);
        connectedNodesCount = view.findViewById(R.id.connectedNodesCount);
        queuedMessagesCount = view.findViewById(R.id.queuedMessagesCount);
        visibilityStatus   = view.findViewById(R.id.visibilityStatus);
        emptyLogText       = view.findViewById(R.id.emptyLogText);
        eventLogRecycler   = view.findViewById(R.id.eventLogRecycler);
    }

    private void setupEventLog() {
        eventLogAdapter = new EventLogAdapter(eventLog);
        eventLogRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        eventLogRecycler.setAdapter(eventLogAdapter);
    }

    // ═══════════════════════════════════════════════════════════
    //  ConnectivityObserver.Listener callbacks
    // ═══════════════════════════════════════════════════════════

    /**
     * Called when the network state changes (Idle / Connected / Active).
     * This is the Observer Pattern in action — ConnectivityObserver is
     * the subject, and this fragment is the observer.
     */
    @Override
    public void onNetworkStateChanged(ConnectivityObserver.NetworkState state, String transport) {
        // Update the big state indicator
        switch (state) {
            case IDLE:
                networkStateText.setText(R.string.state_idle);
                setDotColor(networkStateDot, R.color.statusIdle);
                break;
            case CONNECTED:
                networkStateText.setText(R.string.state_connected);
                setDotColor(networkStateDot, R.color.statusActive);
                break;
            case ACTIVE:
                networkStateText.setText(R.string.state_active);
                setDotColor(networkStateDot, R.color.statusOnline);
                break;
        }

        // Update transport label
        transportText.setText(transport);
    }

    /**
     * Called when a broadcast system event is received.
     * This demonstrates the BroadcastReceiver concept — we receive
     * system-wide events and display them in a chronological log.
     */
    @Override
    public void onEventLogged(ConnectivityObserver.Event event) {
        eventLog.add(0, event); // newest first
        eventLogAdapter.notifyItemInserted(0);
        eventLogRecycler.scrollToPosition(0);

        // Hide "no events" placeholder when we have data
        emptyLogText.setVisibility(View.GONE);
        eventLogRecycler.setVisibility(View.VISIBLE);
    }

    // ─── Mesh stats (reads from MeshService) ────────────────

    /**
     * Updates the mesh network statistics panel.
     * Reads connected node count and queued messages from MeshManager.
     */
    private void updateMeshStats() {
        MainActivity activity = (MainActivity) requireActivity();
        if (!activity.isServiceBound()) return;

        MeshService service = activity.getMeshService();
        if (service == null || service.getMeshManager() == null) return;

        int nodes = service.getMeshManager().getConnectedNodeCount();
        int queued = service.getMeshManager().getQueuedMessageCount();

        connectedNodesCount.setText(String.valueOf(nodes));
        queuedMessagesCount.setText(String.valueOf(queued));

        // Show visibility state
        visibilityStatus.setText(
                service.getMeshManager().isVisible() ? "ON" : "OFF"
        );
    }

    // ─── Helper ─────────────────────────────────────────────

    /** Sets the color of a circular dot view (GradientDrawable background). */
    private void setDotColor(View dot, int colorRes) {
        GradientDrawable bg = (GradientDrawable) dot.getBackground();
        bg.setColor(ContextCompat.getColor(requireContext(), colorRes));
    }
}
