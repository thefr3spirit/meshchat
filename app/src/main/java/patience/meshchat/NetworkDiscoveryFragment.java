package patience.meshchat;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
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
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() ->
                            ((MainActivity) requireActivity()).showMainApp());
                }
            }

            @Override
            public void onNetworkLeft() {}
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
        svc.getMeshManager().joinNetwork(network.name);
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
