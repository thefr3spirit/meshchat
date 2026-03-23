package patience.meshchat;

import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;

import java.util.List;

/**
 * LECTURE 1 — Settings Fragment with Hardware Diagnostics
 *
 * This fragment implements the Lecture 1 requirement:
 *   "A module that detects and displays hardware availability
 *    (CPU, RAM, sensors, etc.)"
 *
 * Layout sections:
 * 1) **Profile** — shows the current username with an option to change it.
 *    Username is stored in SharedPreferences and used as the sender name
 *    for all mesh messages.
 *
 * 2) **Hardware Diagnostics** — a tile/card for each hardware component:
 *    CPU, RAM, Storage, Battery, Display, Sensors, Bluetooth, WiFi, Device.
 *    Data is fetched from the HardwareDiagnostics utility class, which
 *    queries Android system services for real hardware information.
 *
 * 3) **About** — app version and description.
 *
 * The diagnostics are refreshed every time the user navigates to this tab
 * (onResume), so values like battery level and free RAM stay up to date.
 */
public class SettingsFragment extends Fragment {

    // ─── UI Elements ────────────────────────────────────────
    private TextView userAvatar;
    private TextView currentUsername;
    private MaterialButton changeUsernameButton;
    private LinearLayout usernameEditSection;
    private TextInputEditText newUsernameInput;
    private MaterialButton saveUsernameButton;
    private LinearLayout hardwareContainer;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initializeViews(view);
        setupUsernameSection();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Refresh username display
        loadUsername();
        // Run hardware diagnostics and populate tiles — this refreshes
        // dynamic values like battery level and available RAM.
        populateHardwareDiagnostics();
    }

    // ─── View initialization ────────────────────────────────

    private void initializeViews(View view) {
        userAvatar          = view.findViewById(R.id.userAvatar);
        currentUsername      = view.findViewById(R.id.currentUsername);
        changeUsernameButton = view.findViewById(R.id.changeUsernameButton);
        usernameEditSection  = view.findViewById(R.id.usernameEditSection);
        newUsernameInput     = view.findViewById(R.id.newUsernameInput);
        saveUsernameButton   = view.findViewById(R.id.saveUsernameButton);
        hardwareContainer    = view.findViewById(R.id.hardwareContainer);
    }

    // ═══════════════════════════════════════════════════════════
    //  Username management
    // ═══════════════════════════════════════════════════════════

    private void setupUsernameSection() {
        // Toggle the edit section when "Edit" is tapped
        changeUsernameButton.setOnClickListener(v -> {
            boolean isHidden = usernameEditSection.getVisibility() == View.GONE;
            usernameEditSection.setVisibility(isHidden ? View.VISIBLE : View.GONE);
            changeUsernameButton.setText(isHidden ? "Cancel" : "Edit");
            if (isHidden) {
                newUsernameInput.setText(getStoredUsername());
                newUsernameInput.requestFocus();
            }
        });

        // Save the new username
        saveUsernameButton.setOnClickListener(v -> saveUsername());
    }

    /** Reads the username from SharedPreferences and updates the UI. */
    private void loadUsername() {
        String name = getStoredUsername();
        currentUsername.setText(name);
        // Show first letter as avatar
        if (name != null && !name.isEmpty()) {
            userAvatar.setText(String.valueOf(name.charAt(0)).toUpperCase());
        }
    }

    /** Validates and saves a new username. */
    private void saveUsername() {
        String newName = newUsernameInput.getText() != null
                ? newUsernameInput.getText().toString().trim() : "";

        if (newName.isEmpty()) {
            showSnackbar(getString(R.string.username_empty));
            return;
        }
        if (newName.length() < 2) {
            showSnackbar(getString(R.string.username_too_short));
            return;
        }

        // Write to SharedPreferences
        SharedPreferences prefs = requireActivity().getSharedPreferences(
                RegistrationActivity.PREFS_NAME,
                requireContext().MODE_PRIVATE);
        prefs.edit().putString(RegistrationActivity.KEY_USERNAME, newName).apply();

        // Update UI
        loadUsername();
        usernameEditSection.setVisibility(View.GONE);
        changeUsernameButton.setText("Edit");

        showSnackbar(String.format(getString(R.string.username_updated), newName));
    }

    private String getStoredUsername() {
        SharedPreferences prefs = requireActivity().getSharedPreferences(
                RegistrationActivity.PREFS_NAME,
                requireContext().MODE_PRIVATE);
        return prefs.getString(RegistrationActivity.KEY_USERNAME, "Unknown");
    }

    // ═══════════════════════════════════════════════════════════
    //  Hardware Diagnostics (Lecture 1 core feature)
    // ═══════════════════════════════════════════════════════════

    /**
     * Runs all hardware diagnostic checks and displays the results
     * as colored tiles in the hardware container.
     *
     * Each tile shows:
     *  - A colored accent bar on the left (using the diagnostic-specific color)
     *  - The component label (e.g. "CPU", "RAM")
     *  - The detected value (e.g. "8 cores — arm64-v8a")
     *
     * This data is fetched from HardwareDiagnostics.runAll(), which queries
     * real Android system services — nothing is hardcoded or faked.
     */
    private void populateHardwareDiagnostics() {
        hardwareContainer.removeAllViews();

        // Run all diagnostics — returns a list of DiagnosticItem objects
        List<HardwareDiagnostics.DiagnosticItem> items =
                HardwareDiagnostics.runAll(requireContext());

        for (HardwareDiagnostics.DiagnosticItem item : items) {
            View tile = createDiagnosticTile(item);
            hardwareContainer.addView(tile);
        }
    }

    /**
     * Creates a single diagnostic tile view programmatically.
     * We build the layout in code rather than inflating from XML because
     * the number of tiles is dynamic and the accent color varies per item.
     */
    private View createDiagnosticTile(HardwareDiagnostics.DiagnosticItem item) {
        // Outer horizontal container
        LinearLayout tile = new LinearLayout(requireContext());
        tile.setOrientation(LinearLayout.HORIZONTAL);
        tile.setGravity(android.view.Gravity.CENTER_VERTICAL);
        tile.setPadding(0, dp(8), 0, dp(8));

        // Colored accent bar (left side)
        View accentBar = new View(requireContext());
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(dp(4), dp(40));
        barParams.setMarginEnd(dp(12));
        accentBar.setLayoutParams(barParams);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setCornerRadius(dp(2));
        barBg.setColor(ContextCompat.getColor(requireContext(), item.colorResId));
        accentBar.setBackground(barBg);
        tile.addView(accentBar);

        // Text content (label + value)
        LinearLayout textContainer = new LinearLayout(requireContext());
        textContainer.setOrientation(LinearLayout.VERTICAL);
        textContainer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // Label (e.g. "CPU")
        TextView label = new TextView(requireContext());
        label.setText(item.label);
        label.setTextSize(14);
        label.setTextColor(ContextCompat.getColor(requireContext(), item.colorResId));
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        textContainer.addView(label);

        // Value (e.g. "8 cores — arm64-v8a")
        TextView value = new TextView(requireContext());
        value.setText(item.value);
        value.setTextSize(12);
        value.setTextColor(0xFF757575);
        textContainer.addView(value);

        tile.addView(textContainer);
        return tile;
    }

    /** Converts dp to pixels. */
    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    // ─── Snackbar ───────────────────────────────────────────

    private void showSnackbar(String text) {
        View view = getView();
        if (view != null) {
            Snackbar.make(view, text, Snackbar.LENGTH_SHORT).show();
        }
    }
}
