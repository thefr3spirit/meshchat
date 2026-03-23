package patience.meshchat;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * LECTURE 2 — Connectivity Observer & BroadcastReceiver
 *
 * This class demonstrates two fundamental Android concepts:
 *
 * 1) **Observer Pattern (ConnectivityManager.NetworkCallback)**
 *    - We register a callback with the system ConnectivityManager.
 *    - Android invokes our callback methods whenever the network state
 *      transitions between Idle → Connected → Active (or back).
 *    - This is the modern, recommended way to monitor connectivity
 *      (replaces the deprecated CONNECTIVITY_ACTION broadcast).
 *
 * 2) **BroadcastReceiver**
 *    - A system component that listens for system-wide events broadcast
 *      by the Android OS — conceptually rooted in early radio broadcast
 *      where a single signal is sent to all receivers simultaneously.
 *    - We listen for three system events here:
 *        • Airplane mode toggled (ACTION_AIRPLANE_MODE_CHANGED)
 *        • Bluetooth state changed (ACTION_STATE_CHANGED)
 *        • WiFi state changed (WIFI_STATE_CHANGED_ACTION)
 *    - Each event is logged with a timestamp so the user can see a
 *      history of connectivity-related system events.
 *
 * Usage:
 *    ConnectivityObserver observer = new ConnectivityObserver(context, listener);
 *    observer.start();   // begin monitoring
 *    observer.stop();    // stop monitoring (call in onPause / onDestroy)
 */
public class ConnectivityObserver {

    // ───────────────────────────────────────────────────────────
    //  Network state enum — the three states from the lecture
    // ───────────────────────────────────────────────────────────

    /**
     * Represents the three network states described in the lecture:
     *  IDLE        — no network connection available
     *  CONNECTED   — a network is available but may not be validated
     *  ACTIVE      — network is available AND validated (internet reachable)
     */
    public enum NetworkState {
        IDLE,
        CONNECTED,
        ACTIVE
    }

    // ───────────────────────────────────────────────────────────
    //  Event log entry
    // ───────────────────────────────────────────────────────────

    /**
     * One entry in the event history log.
     * Stores the human-readable description and the exact time.
     */
    public static class Event {
        public final String description;
        public final long timestamp;

        public Event(String description) {
            this.description = description;
            this.timestamp = System.currentTimeMillis();
        }

        /** Returns the time formatted as "HH:mm:ss". */
        public String getFormattedTime() {
            return new SimpleDateFormat("HH:mm:ss", Locale.US)
                    .format(new Date(timestamp));
        }
    }

    // ───────────────────────────────────────────────────────────
    //  Callback interface — UI listens through this
    // ───────────────────────────────────────────────────────────

    /**
     * Observer callback that the UI fragment implements.
     * All methods are called on the main (UI) thread.
     */
    public interface Listener {
        /** Called when the overall network state changes. */
        void onNetworkStateChanged(NetworkState state, String transport);

        /** Called when a system broadcast event is received. */
        void onEventLogged(Event event);
    }

    // ───────────────────────────────────────────────────────────
    //  Fields
    // ───────────────────────────────────────────────────────────

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** History of all broadcast events captured during this session. */
    private final List<Event> eventHistory = new ArrayList<>();

    /** The latest observed network state. */
    private NetworkState currentState = NetworkState.IDLE;
    private String currentTransport = "None";

    // Android system components
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private BroadcastReceiver systemEventReceiver;

    // ───────────────────────────────────────────────────────────
    //  Constructor
    // ───────────────────────────────────────────────────────────

    public ConnectivityObserver(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    // ───────────────────────────────────────────────────────────
    //  Lifecycle — start / stop monitoring
    // ───────────────────────────────────────────────────────────

    /**
     * Begin monitoring network state AND system broadcast events.
     * Call this in onResume() or when the fragment becomes active.
     */
    public void start() {
        registerNetworkCallback();
        registerBroadcastReceiver();
    }

    /**
     * Stop all monitoring and release resources.
     * Call this in onPause() or when the fragment is destroyed.
     */
    public void stop() {
        unregisterNetworkCallback();
        unregisterBroadcastReceiver();
    }

    // ───────────────────────────────────────────────────────────
    //  Getters
    // ───────────────────────────────────────────────────────────

    public NetworkState getCurrentState()     { return currentState; }
    public String getCurrentTransport()       { return currentTransport; }
    public List<Event> getEventHistory()      { return new ArrayList<>(eventHistory); }

    // ═══════════════════════════════════════════════════════════
    //  Part 1: NetworkCallback (Observer Pattern)
    // ═══════════════════════════════════════════════════════════

    /**
     * Registers a NetworkCallback with the system ConnectivityManager.
     *
     * NetworkCallback is Android's implementation of the Observer pattern:
     *   Subject   = ConnectivityManager (the thing being observed)
     *   Observer  = our NetworkCallback  (gets notified of changes)
     *
     * We request to observe ALL networks so we catch WiFi, cellular, etc.
     */
    private void registerNetworkCallback() {
        connectivityManager = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);

        // Build a request that matches any network with internet capability
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {

            /**
             * Called when a network becomes available.
             * Transition: IDLE → CONNECTED
             */
            @Override
            public void onAvailable(@NonNull Network network) {
                updateState(NetworkState.CONNECTED, detectTransport(network));
                logEvent("Network available");
            }

            /**
             * Called when the network is lost entirely.
             * Transition: CONNECTED/ACTIVE → IDLE
             */
            @Override
            public void onLost(@NonNull Network network) {
                updateState(NetworkState.IDLE, "None");
                logEvent("Network lost");
            }

            /**
             * Called when network capabilities change.
             * If the network is validated (can reach the internet),
             * we consider it ACTIVE; otherwise just CONNECTED.
             */
            @Override
            public void onCapabilitiesChanged(@NonNull Network network,
                                              @NonNull NetworkCapabilities caps) {
                String transport = detectTransportFromCaps(caps);
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    updateState(NetworkState.ACTIVE, transport);
                } else {
                    updateState(NetworkState.CONNECTED, transport);
                }
            }
        };

        connectivityManager.registerNetworkCallback(request, networkCallback);
    }

    private void unregisterNetworkCallback() {
        if (connectivityManager != null && networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
            networkCallback = null;
        }
    }

    /** Extracts the transport type (WiFi, Cellular, etc.) from a Network object. */
    private String detectTransport(@NonNull Network network) {
        if (connectivityManager == null) return "Unknown";
        NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);
        return caps != null ? detectTransportFromCaps(caps) : "Unknown";
    }

    /** Extracts the transport type from NetworkCapabilities. */
    private String detectTransportFromCaps(@NonNull NetworkCapabilities caps) {
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))      return "WiFi";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))  return "Cellular";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) return "Bluetooth";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))  return "Ethernet";
        return "Other";
    }

    // ═══════════════════════════════════════════════════════════
    //  Part 2: BroadcastReceiver (System-wide events)
    // ═══════════════════════════════════════════════════════════

    /**
     * Registers a BroadcastReceiver that listens for system-wide events.
     *
     * BroadcastReceiver concept (from the lecture):
     *   Rooted in early radio broadcast — a single signal is sent to
     *   all potential receivers simultaneously without a direct 1-to-1
     *   connection. In Android, the OS broadcasts Intents and any
     *   registered receiver can pick them up.
     *
     * We listen for three events that relate to connectivity:
     *   1. Airplane mode toggled     — disables all radios
     *   2. Bluetooth state changed   — on/off transitions
     *   3. WiFi state changed        — on/off transitions
     */
    private void registerBroadcastReceiver() {
        systemEventReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent.getAction() == null) return;

                switch (intent.getAction()) {
                    case Intent.ACTION_AIRPLANE_MODE_CHANGED:
                        handleAirplaneMode(intent);
                        break;
                    case BluetoothAdapter.ACTION_STATE_CHANGED:
                        handleBluetoothState(intent);
                        break;
                    case WifiManager.WIFI_STATE_CHANGED_ACTION:
                        handleWifiState(intent);
                        break;
                }
            }
        };

        // Create an IntentFilter that specifies which broadcasts we want
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);

        // Register — Android will now deliver matching broadcasts to us
        context.registerReceiver(systemEventReceiver, filter);
    }

    private void unregisterBroadcastReceiver() {
        if (systemEventReceiver != null) {
            context.unregisterReceiver(systemEventReceiver);
            systemEventReceiver = null;
        }
    }

    // ── Individual event handlers ──

    private void handleAirplaneMode(Intent intent) {
        boolean isOn = intent.getBooleanExtra("state", false);
        String msg = isOn
                ? context.getString(R.string.airplane_mode_on)
                : context.getString(R.string.airplane_mode_off);
        logEvent(msg);
    }

    private void handleBluetoothState(Intent intent) {
        int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                                       BluetoothAdapter.ERROR);
        String msg;
        switch (state) {
            case BluetoothAdapter.STATE_ON:
                msg = context.getString(R.string.bluetooth_turned_on);
                break;
            case BluetoothAdapter.STATE_OFF:
                msg = context.getString(R.string.bluetooth_turned_off);
                break;
            default:
                return; // ignore transitional states
        }
        logEvent(msg);
    }

    private void handleWifiState(Intent intent) {
        int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                                       WifiManager.WIFI_STATE_UNKNOWN);
        String msg;
        switch (state) {
            case WifiManager.WIFI_STATE_ENABLED:
                msg = context.getString(R.string.wifi_turned_on);
                break;
            case WifiManager.WIFI_STATE_DISABLED:
                msg = context.getString(R.string.wifi_turned_off);
                break;
            default:
                return; // ignore enabling/disabling transitions
        }
        logEvent(msg);
    }

    // ───────────────────────────────────────────────────────────
    //  State management — updates state and notifies the listener
    // ───────────────────────────────────────────────────────────

    /**
     * Updates the current state and notifies the UI listener on the main thread.
     * Only fires the callback if the state actually changed (avoids redundant
     * UI updates when Android sends duplicate capabilities events).
     */
    private void updateState(NetworkState newState, String transport) {
        if (newState == currentState && transport.equals(currentTransport)) return;
        currentState = newState;
        currentTransport = transport;
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onNetworkStateChanged(currentState, currentTransport);
            }
        });
    }

    /** Adds an event to the history and notifies the UI listener. */
    private void logEvent(String description) {
        Event event = new Event(description);
        eventHistory.add(event);
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onEventLogged(event);
            }
        });
    }
}
