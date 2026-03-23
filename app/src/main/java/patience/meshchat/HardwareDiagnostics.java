package patience.meshchat;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * LECTURE 1 — Hardware Diagnostic Utility
 *
 * This module detects and reports hardware availability on the device.
 * It queries the Android system for information about:
 *   - CPU (processor count, architecture)
 *   - RAM (total / available memory)
 *   - Internal storage (total / available space)
 *   - Battery (level, charging status)
 *   - Display (resolution, density)
 *   - Sensors (accelerometer, gyroscope, etc.)
 *   - Bluetooth (availability and state)
 *   - WiFi (availability and state)
 *   - Device info (manufacturer, model, OS version)
 *
 * Architecture note:
 *   This is a pure utility class with static methods — no state is held.
 *   Each method takes a Context parameter so it can access system services.
 *   The results are returned as simple DiagnosticItem objects that the UI
 *   layer (SettingsFragment) can display directly.
 */
public class HardwareDiagnostics {

    /**
     * Simple data class that holds one diagnostic result.
     * 'label' is the human-readable name (e.g. "CPU"),
     * 'value' is the info string (e.g. "8 cores — arm64-v8a"),
     * 'colorResId' is the accent color resource for the UI tile.
     */
    public static class DiagnosticItem {
        public final String label;
        public final String value;
        public final int colorResId;

        public DiagnosticItem(String label, String value, int colorResId) {
            this.label = label;
            this.value = value;
            this.colorResId = colorResId;
        }
    }

    // ───────────────────────────────────────────────────────────
    //  Public API — call this to get all diagnostics at once
    // ───────────────────────────────────────────────────────────

    /**
     * Runs every diagnostic check and returns an ordered list of results.
     * Safe to call from the UI thread — all reads are fast, non-blocking queries.
     */
    public static List<DiagnosticItem> runAll(Context context) {
        List<DiagnosticItem> items = new ArrayList<>();
        items.add(getCpuInfo(context));
        items.add(getRamInfo(context));
        items.add(getStorageInfo(context));
        items.add(getBatteryInfo(context));
        items.add(getDisplayInfo(context));
        items.add(getSensorInfo(context));
        items.add(getBluetoothInfo(context));
        items.add(getWifiInfo(context));
        items.add(getDeviceInfo(context));
        return items;
    }

    // ───────────────────────────────────────────────────────────
    //  Individual diagnostic checks
    // ───────────────────────────────────────────────────────────

    /** CPU — number of cores and supported ABI (architecture). */
    public static DiagnosticItem getCpuInfo(Context context) {
        int cores = Runtime.getRuntime().availableProcessors();
        // Build.SUPPORTED_ABIS gives the list of ABIs this device supports
        String abi = Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS[0] : "Unknown";
        String value = cores + " cores — " + abi;
        return new DiagnosticItem("CPU", value, R.color.diagCpu);
    }

    /**
     * RAM — reports total and currently available memory.
     * Uses ActivityManager.MemoryInfo which is the standard way
     * to query memory on Android.
     */
    public static DiagnosticItem getRamInfo(Context context) {
        ActivityManager am = (ActivityManager)
                context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);

        String total = formatBytes(mi.totalMem);
        String avail = formatBytes(mi.availMem);
        String value = avail + " free / " + total + " total";
        return new DiagnosticItem("RAM", value, R.color.diagRam);
    }

    /**
     * Internal Storage — uses StatFs to read block-level storage info.
     * StatFs provides the number of blocks and block size so we can
     * calculate total and available space in human-readable form.
     */
    public static DiagnosticItem getStorageInfo(Context context) {
        StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
        long total = stat.getTotalBytes();
        long avail = stat.getAvailableBytes();
        String value = formatBytes(avail) + " free / " + formatBytes(total) + " total";
        return new DiagnosticItem("Storage", value, R.color.diagStorage);
    }

    /**
     * Battery — current level and charging state.
     * We register a null receiver with ACTION_BATTERY_CHANGED to get
     * the latest sticky broadcast (a special Intent that sticks around).
     */
    public static DiagnosticItem getBatteryInfo(Context context) {
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, filter);

        String value;
        if (batteryStatus != null) {
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int pct = (int) ((level / (float) scale) * 100);

            int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
            value = pct + "%" + (charging ? " (Charging)" : " (Discharging)");
        } else {
            value = "Unavailable";
        }
        return new DiagnosticItem("Battery", value, R.color.diagBattery);
    }

    /**
     * Display — screen resolution and pixel density.
     * WindowManager provides the current display metrics for the
     * default display (the screen the user is looking at).
     */
    public static DiagnosticItem getDisplayInfo(Context context) {
        WindowManager wm = (WindowManager)
                context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(dm);

        String res = dm.widthPixels + " × " + dm.heightPixels;
        String density = dm.densityDpi + " dpi";
        String value = res + " — " + density;
        return new DiagnosticItem("Display", value, R.color.diagDisplay);
    }

    /**
     * Sensors — counts all available sensors and lists key ones.
     * SensorManager.getSensorList(TYPE_ALL) enumerates every sensor
     * the hardware reports. We highlight the most useful ones.
     */
    public static DiagnosticItem getSensorInfo(Context context) {
        SensorManager sm = (SensorManager)
                context.getSystemService(Context.SENSOR_SERVICE);
        List<Sensor> all = sm.getSensorList(Sensor.TYPE_ALL);

        // Check for the most common / useful sensors
        boolean hasAccel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null;
        boolean hasGyro  = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null;
        boolean hasLight = sm.getDefaultSensor(Sensor.TYPE_LIGHT) != null;
        boolean hasProx  = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null;

        StringBuilder sb = new StringBuilder();
        sb.append(all.size()).append(" sensors — ");
        List<String> found = new ArrayList<>();
        if (hasAccel) found.add("Accel");
        if (hasGyro)  found.add("Gyro");
        if (hasLight) found.add("Light");
        if (hasProx)  found.add("Prox");
        sb.append(found.isEmpty() ? "none detected" : String.join(", ", found));

        return new DiagnosticItem("Sensors", sb.toString(), R.color.diagSensor);
    }

    /**
     * Bluetooth — checks if the adapter exists and whether it is currently enabled.
     * Uses BluetoothManager (the modern approach for API 31+).
     */
    public static DiagnosticItem getBluetoothInfo(Context context) {
        BluetoothManager btManager = (BluetoothManager)
                context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = btManager != null ? btManager.getAdapter() : null;

        String value;
        if (adapter == null) {
            value = "Not available";
        } else {
            value = adapter.isEnabled() ? "Enabled" : "Disabled";
        }
        return new DiagnosticItem("Bluetooth", value, R.color.diagBluetooth);
    }

    /**
     * WiFi — checks adapter state.
     * WifiManager.getWifiState() returns the current radio state
     * (enabled, disabled, enabling, disabling, unknown).
     */
    public static DiagnosticItem getWifiInfo(Context context) {
        WifiManager wm = (WifiManager)
                context.getApplicationContext()
                       .getSystemService(Context.WIFI_SERVICE);
        String value;
        if (wm == null) {
            value = "Not available";
        } else {
            int state = wm.getWifiState();
            switch (state) {
                case WifiManager.WIFI_STATE_ENABLED:    value = "Enabled";    break;
                case WifiManager.WIFI_STATE_DISABLED:   value = "Disabled";   break;
                case WifiManager.WIFI_STATE_ENABLING:   value = "Enabling…";  break;
                case WifiManager.WIFI_STATE_DISABLING:  value = "Disabling…"; break;
                default:                                 value = "Unknown";    break;
            }
        }
        return new DiagnosticItem("WiFi", value, R.color.diagWifi);
    }

    /**
     * Device — manufacturer, model, and Android version.
     * All pulled from the android.os.Build class which holds
     * system property values populated at boot time.
     */
    public static DiagnosticItem getDeviceInfo(Context context) {
        String value = Build.MANUFACTURER + " " + Build.MODEL
                + " — Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")";
        return new DiagnosticItem("Device", value, R.color.diagDevice);
    }

    // ───────────────────────────────────────────────────────────
    //  Helpers
    // ───────────────────────────────────────────────────────────

    /** Converts a byte count into a human-readable string (e.g. 3.7 GB). */
    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format(Locale.US, "%.1f GB", gb);
    }
}
