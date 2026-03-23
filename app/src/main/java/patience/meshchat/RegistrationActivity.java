package patience.meshchat;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/**
 * RegistrationActivity - First-launch screen for choosing a username.
 *
 * Shown only once on first launch. The username is stored in SharedPreferences
 * and used as the sender name for all mesh messages. Other devices see this
 * name when they receive your messages and in the peer discovery list.
 */
public class RegistrationActivity extends AppCompatActivity {

    /** SharedPreferences file name for MeshChat settings */
    public static final String PREFS_NAME = "MeshChatPrefs";

    /** Key for the stored username */
    public static final String KEY_USERNAME = "username";

    /** Minimum username length */
    private static final int MIN_USERNAME_LENGTH = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // If user already registered, skip straight to chat
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String existingUsername = prefs.getString(KEY_USERNAME, null);
        if (existingUsername != null && !existingUsername.isEmpty()) {
            launchMainActivity();
            return;
        }

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_registration);

        TextInputLayout usernameLayout = findViewById(R.id.usernameInputLayout);
        TextInputEditText usernameInput = findViewById(R.id.usernameInput);
        MaterialButton joinButton = findViewById(R.id.joinButton);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        joinButton.setOnClickListener(v -> {
            String username = usernameInput.getText() != null
                    ? usernameInput.getText().toString().trim() : "";

            if (TextUtils.isEmpty(username)) {
                usernameLayout.setError(getString(R.string.username_empty));
                return;
            }
            if (username.length() < MIN_USERNAME_LENGTH) {
                usernameLayout.setError(getString(R.string.username_too_short));
                return;
            }

            // Clear any previous error
            usernameLayout.setError(null);

            // Save username
            prefs.edit().putString(KEY_USERNAME, username).apply();

            Snackbar.make(v, "Welcome, " + username + "!", Snackbar.LENGTH_SHORT).show();

            // Small delay so user sees the snackbar
            v.postDelayed(this::launchMainActivity, 600);
        });

        // Clear error when user starts typing
        usernameInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) usernameLayout.setError(null);
        });
    }

    private void launchMainActivity() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
