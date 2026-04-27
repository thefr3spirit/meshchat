package patience.meshchat;

import android.Manifest;
import android.app.Dialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * NetworkDiscoveryFragment — shown when the user hasn't joined any network yet.
 *
 * Shows:
 *  1. A live OSMDroid map centered on the user's GPS location.
 *  2. A list of discovered nearby Bluetooth mesh networks.
 *
 * Requests ACCESS_FINE_LOCATION at runtime; handles provider-disabled gracefully.
 */
public class NetworkDiscoveryFragment extends Fragment {

    // ─── Map / Location ─────────────────────────────────────────────────

    private MapView mapView;
    private Marker locationMarker;
    private LinearLayout gpsUnavailableView;
    private TextView gpsStatusText;
    private TextView locationAccuracyLabel;

    private LocationManager locationManager;
    private boolean firstLocationFix = true;
    private boolean locationPermissionGranted = false;

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            updateMapLocation(location);
        }

        @Override
        public void onProviderDisabled(@NonNull String provider) {
            // Check if ALL providers are now disabled
            if (locationManager != null
                    && !locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    && !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() ->
                            showLocationOverlay("Location services disabled.\nEnable GPS in Settings."));
                }
            }
        }

        @Override
        public void onProviderEnabled(@NonNull String provider) {
            if (getActivity() != null) {
                getActivity().runOnUiThread(() ->
                        showLocationOverlay("Locating you\u2026"));
            }
        }
    };

    /**
     * Registered before onStart — must be a field, not inside a callback.
     * Handles the result of the runtime location permission dialog.
     */
    private final ActivityResultLauncher<String[]> locationPermLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        Boolean fine = result.getOrDefault(
                                Manifest.permission.ACCESS_FINE_LOCATION, false);
                        Boolean coarse = result.getOrDefault(
                                Manifest.permission.ACCESS_COARSE_LOCATION, false);
                        if (Boolean.TRUE.equals(fine) || Boolean.TRUE.equals(coarse)) {
                            locationPermissionGranted = true;
                            startLocationUpdates();
                        } else {
                            showLocationOverlay("Location permission denied.\nCannot show your position.");
                        }
                    });

    // ─── Network discovery ──────────────────────────────────────────────

    private RecyclerView recyclerNetworks;
    private ProgressBar scanProgress;
    private TextView statusText;
    private ExtendedFloatingActionButton createFab;

    private NetworkAdapter networkAdapter;
    private final List<Network> networks = new ArrayList<>();

    // ─── Connection progress dialog ─────────────────────────────────────

    /** Non-null while a join/create attempt is in progress. */
    private Dialog connectionProgressDialog;

    // Stepper dot views inside the dialog
    private View dotScan, dotDetect, dotConnect, dotHandshake, dotDone;
    private View lineScanDetect, lineDetectConnect, lineConnectHandshake, lineHandshakeDone;
    private TextView tvProgressTitle, tvProgressStatus;
    private LinearLayout peerInfoRow;
    private TextView tvPeerName, tvTransportBadge;
    private View rssiBar1, rssiBar2, rssiBar3;

    // ─── Lifecycle ───────────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // Must be called before the MapView is inflated
        Configuration.getInstance().load(
                requireContext().getApplicationContext(),
                requireContext().getSharedPreferences("osmdroid", Context.MODE_PRIVATE));
        Configuration.getInstance().setUserAgentValue(requireContext().getPackageName());
        return inflater.inflate(R.layout.fragment_network_discovery, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // ─── Map views ───────────────────────────────────────────────────
        mapView              = view.findViewById(R.id.mapView);
        gpsUnavailableView   = view.findViewById(R.id.gpsUnavailableView);
        gpsStatusText        = view.findViewById(R.id.gpsStatusText);
        locationAccuracyLabel = view.findViewById(R.id.locationAccuracyLabel);

        initMap();
        checkAndRequestLocationPermission();

        // ─── Network discovery ───────────────────────────────────────────
        recyclerNetworks = view.findViewById(R.id.recyclerNetworks);
        scanProgress     = view.findViewById(R.id.scanProgress);
        statusText       = view.findViewById(R.id.scanStatusText);
        createFab        = view.findViewById(R.id.fabCreateNetwork);

        networkAdapter = new NetworkAdapter(networks, this::onNetworkTapped);
        recyclerNetworks.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerNetworks.setAdapter(networkAdapter);

        createFab.setOnClickListener(v -> showCreateNetworkDialog());
        startScanning();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mapView != null) mapView.onResume();
        if (locationPermissionGranted) startLocationUpdates();
        registerMeshListener();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mapView != null) mapView.onPause();
        stopLocationUpdates();
        MeshService svc = getService();
        if (svc != null && svc.getMeshManager() != null) {
            svc.getMeshManager().setMessageListener(null);
            svc.getMeshManager().setConnectionProgressListener(null);
        }
        // Dismiss dialog if fragment is paused mid-connection (e.g. screen rotation)
        if (connectionProgressDialog != null && connectionProgressDialog.isShowing()) {
            connectionProgressDialog.dismiss();
            connectionProgressDialog = null;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopLocationUpdates();
        if (mapView != null) {
            mapView.onDetach();
            mapView = null;
        }
        locationMarker = null;
    }

    // ─── Map setup ───────────────────────────────────────────────────────

    private void initMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.setBuiltInZoomControls(false);
        mapView.getController().setZoom(15.0);
        // Default center: 0,0 — will animate to real location on first fix
        mapView.getController().setCenter(new GeoPoint(0.0, 0.0));
    }

    // ─── Permission ──────────────────────────────────────────────────────

    private void checkAndRequestLocationPermission() {
        boolean hasFine = ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean hasCoarse = ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (hasFine || hasCoarse) {
            locationPermissionGranted = true;
            // startLocationUpdates() is called by onResume() — do not call here to avoid
            // double-registration when onViewCreated is followed immediately by onResume.
        } else {
            locationPermLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
        }
    }

    // ─── Location updates ────────────────────────────────────────────────

    private void startLocationUpdates() {
        if (!locationPermissionGranted || getContext() == null) return;

        locationManager = (LocationManager) requireContext()
                .getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            showLocationOverlay("Location services unavailable.");
            return;
        }

        // Show the best cached fix immediately — check every provider so we get
        // something on screen without waiting for a fresh GPS cold-start.
        Location bestLast = getBestLastKnownLocation();
        if (bestLast != null) updateMapLocation(bestLast);

        boolean netEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);

        if (!netEnabled && !gpsEnabled) {
            if (bestLast == null) {
                showLocationOverlay("Location services disabled.\nEnable GPS in Settings.");
            }
            return;
        }

        // Register NETWORK first — gives a coarse fix in milliseconds.
        // GPS follows and will update the marker once it gets a satellite fix.
        if (netEnabled) {
            try {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, 5_000, 5f, locationListener);
            } catch (SecurityException ignored) {}
        }
        if (gpsEnabled) {
            try {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 5_000, 5f, locationListener);
            } catch (SecurityException ignored) {}
        }
    }

    /** Returns the most recently recorded location across all available providers. */
    private Location getBestLastKnownLocation() {
        if (locationManager == null) return null;
        Location best = null;
        for (String provider : locationManager.getAllProviders()) {
            try {
                Location loc = locationManager.getLastKnownLocation(provider);
                if (loc == null) continue;
                if (best == null || loc.getTime() > best.getTime()) best = loc;
            } catch (SecurityException ignored) {}
        }
        return best;
    }

    private void stopLocationUpdates() {
        if (locationManager != null) {
            locationManager.removeUpdates(locationListener);
        }
    }

    // ─── Map update ──────────────────────────────────────────────────────

    private void updateMapLocation(Location location) {
        if (mapView == null || getActivity() == null) return;
        GeoPoint point = new GeoPoint(location.getLatitude(), location.getLongitude());

        getActivity().runOnUiThread(() -> {
            if (mapView == null) return;

            // Always hide the overlay and show the accuracy chip whenever we have
            // a valid fix — this also handles the "restored after loss" case.
            gpsUnavailableView.setVisibility(View.GONE);
            locationAccuracyLabel.setVisibility(View.VISIBLE);

            // On the very first fix, animate the map to the position.
            if (firstLocationFix) {
                firstLocationFix = false;
                mapView.getController().animateTo(point);
                mapView.getController().setZoom(16.0);
            }

            // Create the marker once, then just move it.
            if (locationMarker == null) {
                locationMarker = new Marker(mapView);
                locationMarker.setTitle("My Location");
                locationMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
                mapView.getOverlays().add(locationMarker);
            }
            locationMarker.setPosition(point);

            if (location.hasAccuracy()) {
                locationAccuracyLabel.setText("\u00B1 " + Math.round(location.getAccuracy()) + " m");
            }

            mapView.invalidate();
        });
    }

    // ─── GPS unavailable overlay ─────────────────────────────────────────

    private void showLocationOverlay(String message) {
        if (gpsUnavailableView == null) return;
        gpsUnavailableView.setVisibility(View.VISIBLE);
        gpsStatusText.setText(message);
        locationAccuracyLabel.setVisibility(View.GONE);
    }

    // ─── Network scanning ────────────────────────────────────────────────

    private void startScanning() {
        scanProgress.setVisibility(View.VISIBLE);
        statusText.setText(R.string.scanning_for_networks);

        MeshService svc = getService();
        if (svc != null && svc.getMeshManager() != null) {
            svc.getMeshManager().startDiscovery();
        }

        // Hide progress after scan window
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

    private void registerMeshListener() {
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
                    if (!networks.isEmpty()) statusText.setText(R.string.tap_to_join);
                });
            }

            @Override
            public void onQueueFlushed(int count) {}

            @Override
            public void onDeliveryStatusChanged(String messageId) {}

            @Override
            public void onNetworkJoined(String networkName) {
                // Do NOT navigate immediately — wait for the first CONNECTED event
                // so the user sees the full stepper animation before the screen changes.
            }

            @Override
            public void onNetworkLeft() {}
        });

        // Register the connection progress listener so the stepper updates live
        svc.getMeshManager().setConnectionProgressListener(
                (peerAddress, peerName, phase, transport, rssi) -> {
                    if (getActivity() == null) return;
                    getActivity().runOnUiThread(() -> {
                        applyPhase(phase, peerName, transport, rssi);

                        // Navigate to the main chat screen only after the first full handshake
                        if (phase == ConnectionPhase.CONNECTED) {
                            // Brief pause so the user sees the green "Connected" state
                            if (getView() != null) {
                                getView().postDelayed(() -> {
                                    dismissConnectionProgress();
                                    if (getActivity() != null) {
                                        ((MainActivity) requireActivity()).showMainApp();
                                    }
                                }, 800);
                            }
                        }
                    });
                });

        // Populate any already-discovered networks immediately
        List<Network> existing = svc.getMeshManager().getDiscoveredNetworks();
        if (!existing.isEmpty()) {
            networks.clear();
            networks.addAll(existing);
            networkAdapter.notifyDataSetChanged();
            statusText.setText(R.string.tap_to_join);
        }
    }

    // ─── Join / Create ───────────────────────────────────────────────────

    private void onNetworkTapped(Network network) {
        MeshService svc = getService();
        if (svc == null || svc.getMeshManager() == null) return;
        showConnectionProgress(network.name);
        svc.getMeshManager().joinNetwork(network.name);
    }

    // ─── Connection Progress Dialog ──────────────────────────────────────

    /**
     * Shows a non-dismissable dialog with a live 5-step connection stepper.
     * Navigation to the main chat screen happens inside the MessageListener
     * callback (onNetworkJoined) only after the first handshake succeeds.
     */
    private void showConnectionProgress(String networkName) {
        if (getContext() == null) return;

        // Inflate the custom dialog layout
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_connection_progress, null);

        // Bind views
        tvProgressTitle       = dialogView.findViewById(R.id.tvProgressTitle);
        tvProgressStatus      = dialogView.findViewById(R.id.tvProgressStatus);
        peerInfoRow           = dialogView.findViewById(R.id.peerInfoRow);
        tvPeerName            = dialogView.findViewById(R.id.tvPeerName);
        tvTransportBadge      = dialogView.findViewById(R.id.tvTransportBadge);
        rssiBar1              = dialogView.findViewById(R.id.rssiBar1);
        rssiBar2              = dialogView.findViewById(R.id.rssiBar2);
        rssiBar3              = dialogView.findViewById(R.id.rssiBar3);
        dotScan               = dialogView.findViewById(R.id.dotScan);
        dotDetect             = dialogView.findViewById(R.id.dotDetect);
        dotConnect            = dialogView.findViewById(R.id.dotConnect);
        dotHandshake          = dialogView.findViewById(R.id.dotHandshake);
        dotDone               = dialogView.findViewById(R.id.dotDone);
        lineScanDetect        = dialogView.findViewById(R.id.lineScanDetect);
        lineDetectConnect     = dialogView.findViewById(R.id.lineDetectConnect);
        lineConnectHandshake  = dialogView.findViewById(R.id.lineConnectHandshake);
        lineHandshakeDone     = dialogView.findViewById(R.id.lineHandshakeDone);

        tvProgressTitle.setText("Joining \"" + networkName + "\"");

        connectionProgressDialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(dialogView)
                .setCancelable(false) // user must use Cancel button or wait for success
                .setNegativeButton("Cancel", (d, w) -> {
                    dismissConnectionProgress();
                    MeshService svc = getService();
                    if (svc != null && svc.getMeshManager() != null) {
                        svc.getMeshManager().leaveNetwork();
                    }
                })
                .create();
        connectionProgressDialog.show();

        // Immediately reflect SCANNING state
        applyPhase(ConnectionPhase.SCANNING, "", "", 0);
    }

    private void dismissConnectionProgress() {
        if (connectionProgressDialog != null) {
            connectionProgressDialog.dismiss();
            connectionProgressDialog = null;
        }
        // Detach the progress listener so we don't leak
        MeshService svc = getService();
        if (svc != null && svc.getMeshManager() != null) {
            svc.getMeshManager().setConnectionProgressListener(null);
        }
    }

    /**
     * Updates the stepper to reflect the current {@link ConnectionPhase}.
     * Must be called on the main thread (already ensured by MeshManager.fireProgress).
     */
    private void applyPhase(ConnectionPhase phase, String peerName,
                             String transport, int rssi) {
        if (connectionProgressDialog == null || !connectionProgressDialog.isShowing()) return;

        Drawable active   = ContextCompat.getDrawable(requireContext(),
                R.drawable.shape_step_dot_active);
        Drawable inactive = ContextCompat.getDrawable(requireContext(),
                R.drawable.shape_step_dot);
        int activeLineColor = ContextCompat.getColor(requireContext(), R.color.colorPrimary);
        int inactiveLineColor = ContextCompat.getColor(requireContext(), R.color.statusOffline);

        // Activate dots up to and including the current phase
        boolean pastScan      = phase.ordinal() >= ConnectionPhase.SCANNING.ordinal();
        boolean pastDetect    = phase.ordinal() >= ConnectionPhase.PEER_DETECTED.ordinal();
        boolean pastConnect   = phase.ordinal() >= ConnectionPhase.TRANSPORT_CONNECTING.ordinal();
        boolean pastHandshake = phase.ordinal() >= ConnectionPhase.HANDSHAKING.ordinal();
        boolean done          = phase == ConnectionPhase.CONNECTED;

        dotScan.setBackground(pastScan ? active : inactive);
        dotDetect.setBackground(pastDetect ? active : inactive);
        dotConnect.setBackground(pastConnect ? active : inactive);
        dotHandshake.setBackground(pastHandshake ? active : inactive);
        dotDone.setBackground(done ? active : inactive);

        lineScanDetect.setBackgroundColor(pastDetect ? activeLineColor : inactiveLineColor);
        lineDetectConnect.setBackgroundColor(pastConnect ? activeLineColor : inactiveLineColor);
        lineConnectHandshake.setBackgroundColor(pastHandshake ? activeLineColor : inactiveLineColor);
        lineHandshakeDone.setBackgroundColor(done ? activeLineColor : inactiveLineColor);

        // Show peer info row once we've detected a device
        if (pastDetect && !peerName.isEmpty()) {
            peerInfoRow.setVisibility(View.VISIBLE);
            tvPeerName.setText(peerName);
            String badge = "wifi_direct".equals(transport) ? "WiFi Direct"
                    : "ble".equals(transport) ? "BLE" : "Bluetooth";
            tvTransportBadge.setText(badge);
            // RSSI bars: 3 bars for strong (> -60), 2 for medium (> -75), 1 for weak
            int bars = rssi == 0 ? 0 : (rssi > -60 ? 3 : rssi > -75 ? 2 : 1);
            int barOn  = ContextCompat.getColor(requireContext(), R.color.colorPrimary);
            int barOff = ContextCompat.getColor(requireContext(), R.color.statusOffline);
            rssiBar1.setBackgroundColor(bars >= 1 ? barOn : barOff);
            rssiBar2.setBackgroundColor(bars >= 2 ? barOn : barOff);
            rssiBar3.setBackgroundColor(bars >= 3 ? barOn : barOff);
        }

        // Status message for each phase
        switch (phase) {
            case SCANNING:
                tvProgressStatus.setText("Scanning for nearby MeshChat devices…");
                break;
            case PEER_DETECTED:
                tvProgressStatus.setText("Found \"" + peerName + "\" nearby");
                break;
            case TRANSPORT_CONNECTING:
                tvProgressStatus.setText("Establishing radio link with \"" + peerName + "\"...");
                break;
            case HANDSHAKING:
                tvProgressStatus.setText("Exchanging identity with \"" + peerName + "\"...");
                break;
            case CONNECTED:
                tvProgressStatus.setText("Secured — joining the mesh!");
                break;
            case FAILED:
                tvProgressStatus.setText("Connection attempt failed. Still scanning…");
                // Reset dots back to SCANNING level so user sees ongoing attempts
                applyPhase(ConnectionPhase.SCANNING, "", "", 0);
                return;
            default:
                break;
        }
    }

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
                    if (name.startsWith("MC_")) name = name.substring(3);
                    if (name.isEmpty()) {
                        showSnackbar(getString(R.string.network_name_empty));
                        return;
                    }
                    MeshService svc = getService();
                    if (svc != null && svc.getMeshManager() != null) {
                        showConnectionProgress(name);
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
            return new VH(v, listener);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            holder.bind(items.get(position));
        }

        @Override
        public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final TextView networkName, nodeCount;
            final MaterialButton joinButton;
            final OnNetworkClickListener listener;

            VH(View v, OnNetworkClickListener listener) {
                super(v);
                this.listener = listener;
                networkName = v.findViewById(R.id.networkName);
                nodeCount   = v.findViewById(R.id.networkNodeCount);
                joinButton  = v.findViewById(R.id.joinButton);
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
