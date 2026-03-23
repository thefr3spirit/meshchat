package patience.meshchat;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;

/**
 * MainActivity — Navigation Host with Bottom Navigation Bar
 *
 * This Activity has been refactored from a monolithic chat screen into a
 * lightweight navigation host. It manages:
 *
 *  1) **Bottom Navigation** — three tabs: Chat, Network, Settings
 *  2) **MeshService binding** — starts and binds to the foreground service
 *  3) **Permission handling** — requests BT/WiFi/location permissions
 *  4) **Fragment switching** — swaps fragments when user taps a tab
 *
 * The actual UI logic lives in the fragments:
 *  - ChatFragment: messaging, peer discovery, visibility toggle
 *  - NetworkFragment: connectivity observer + BroadcastReceiver (Lecture 2)
 *  - SettingsFragment: hardware diagnostics + username change (Lecture 1)
 *
 * Fragments access MeshService through getMeshService() and isServiceBound().
 */
public class MainActivity extends AppCompatActivity {

    private static final int PERMISSIONS_REQUEST_CODE = 100;

    // ─── Service binding ────────────────────────────────────
    private MeshService meshService;
    private boolean isBound = false;

    // ─── Fragments ──────────────────────────────────────────
    private ChatFragment chatFragment;
    private NetworkFragment networkFragment;
    private SettingsFragment settingsFragment;
    private Fragment activeFragment;

    private BottomNavigationView bottomNav;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            MeshService.MeshBinder binder = (MeshService.MeshBinder) service;
            meshService = binder.getService();
            isBound = true;
            // Notify the chat fragment that the service is ready
            if (chatFragment != null) {
                chatFragment.setupMeshListener();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            isBound = false;
        }
    };

    // ─── Public API for fragments ───────────────────────────

    /** Returns the bound MeshService, or null if not yet bound. */
    public MeshService getMeshService() {
        return meshService;
    }

    /** Returns true if the MeshService is currently bound. */
    public boolean isServiceBound() {
        return isBound;
    }

    // ─── Lifecycle ──────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Check registration — if user hasn't registered, redirect
        SharedPreferences prefs = getSharedPreferences(
                RegistrationActivity.PREFS_NAME, MODE_PRIVATE);
        String username = prefs.getString(RegistrationActivity.KEY_USERNAME, null);
        if (username == null || username.isEmpty()) {
            startActivity(new Intent(this, RegistrationActivity.class));
            finish();
            return;
        }

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        setupBottomNavigation();
        checkPermissions();

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0);
            return insets;
        });
    }

    // ─── Bottom Navigation ──────────────────────────────────

    /**
     * Sets up the bottom navigation bar and creates the three fragments.
     *
     * All fragments are created once and kept alive — when the user switches
     * tabs, we hide the old fragment and show the new one rather than
     * recreating them. This preserves chat messages, event logs, etc.
     */
    private void setupBottomNavigation() {
        bottomNav = findViewById(R.id.bottomNav);

        chatFragment = new ChatFragment();
        networkFragment = new NetworkFragment();
        settingsFragment = new SettingsFragment();

        // Add all fragments but only show the chat tab initially
        getSupportFragmentManager().beginTransaction()
                .add(R.id.fragmentContainer, settingsFragment, "settings").hide(settingsFragment)
                .add(R.id.fragmentContainer, networkFragment, "network").hide(networkFragment)
                .add(R.id.fragmentContainer, chatFragment, "chat")
                .commit();
        activeFragment = chatFragment;

        bottomNav.setOnItemSelectedListener(item -> {
            Fragment selected;
            int id = item.getItemId();
            if (id == R.id.nav_chat) {
                selected = chatFragment;
            } else if (id == R.id.nav_network) {
                selected = networkFragment;
            } else if (id == R.id.nav_settings) {
                selected = settingsFragment;
            } else {
                return false;
            }

            if (selected != activeFragment) {
                getSupportFragmentManager().beginTransaction()
                        .hide(activeFragment)
                        .show(selected)
                        .commit();
                activeFragment = selected;
            }
            return true;
        });
    }

    // ─── Permissions ────────────────────────────────────────

    private void checkPermissions() {
        ArrayList<String> permissionsNeeded = new ArrayList<>();

        String[] permissions = {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.CHANGE_WIFI_MULTICAST_STATE
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN);
            permissionsNeeded.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(permission);
            }
        }

        if (permissionsNeeded.isEmpty()) {
            startMeshService();
        } else {
            ActivityCompat.requestPermissions(this,
                    permissionsNeeded.toArray(new String[0]),
                    PERMISSIONS_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                startMeshService();
            } else {
                Snackbar.make(findViewById(R.id.main),
                        getString(R.string.permissions_required),
                        Snackbar.LENGTH_SHORT).show();
            }
        }
    }

    private void startMeshService() {
        Intent intent = new Intent(this, MeshService.class);
        startService(intent);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            unbindService(connection);
            isBound = false;
        }
    }
}