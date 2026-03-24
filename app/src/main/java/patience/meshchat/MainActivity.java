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
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MainActivity — Navigation host and message dispatch hub.
 *
 * Responsibilities:
 *  1. Checks registration; redirects to RegistrationActivity if needed
 *  2. Requests Bluetooth permissions
 *  3. Binds to MeshService
 *  4. Shows NetworkDiscoveryFragment when not in a network (no bottom nav)
 *  5. Shows 4-tab main app when in a network (Chats, Peers, Network, Settings)
 *  6. Holds the conversations map and per-conversation message lists
 *  7. Opens ChatFragment for a specific conversation (adds to back stack)
 *  8. Dispatches incoming messages to the right conversation
 */
public class MainActivity extends AppCompatActivity {

    private static final int PERMISSIONS_REQUEST_CODE = 100;

    // ─── Service ────────────────────────────────────────────────────────
    private MeshService meshService;
    private boolean isBound = false;

    // ─── Navigation ─────────────────────────────────────────────────────
    private BottomNavigationView bottomNav;
    private ConversationsFragment conversationsFragment;
    private PeersListFragment peersListFragment;
    private NetworkTopologyFragment topologyFragment;
    private SettingsFragment settingsFragment;
    private Fragment activeMainFragment;

    // ─── Conversation data ──────────────────────────────────────────────

    /**
     * All conversations keyed by conversation ID.
     * GROUP_ID → group conversation
     * UUID → private conversation per peer
     */
    private final Map<String, Conversation> conversations = new LinkedHashMap<>();

    /**
     * Messages per conversation, keyed by conversation ID.
     * Kept in memory for the session lifetime.
     */
    private final Map<String, List<Message>> conversationMessages = new LinkedHashMap<>();

    // ─── Service connection ─────────────────────────────────────────────

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MeshService.MeshBinder binder = (MeshService.MeshBinder) service;
            meshService = binder.getService();
            isBound = true;
            onServiceReady();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
        }
    };

    // ─── Lifecycle ──────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Check registration
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

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, 0);
            return insets;
        });

        setupBottomNavigation();

        // Ensure the group conversation always exists
        conversations.put(Conversation.GROUP_ID, Conversation.createGroup());
        conversationMessages.put(Conversation.GROUP_ID, new ArrayList<>());

        checkPermissions();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            unbindService(connection);
            isBound = false;
        }
    }

    @Override
    public void onBackPressed() {
        // If ChatFragment is on back stack, pop it and restore bottom nav
        if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
            getSupportFragmentManager().popBackStack();
            bottomNav.setVisibility(android.view.View.VISIBLE);
        } else {
            super.onBackPressed();
        }
    }

    // ─── Service ────────────────────────────────────────────────────────

    public MeshService getMeshService() { return meshService; }
    public boolean isServiceBound() { return isBound; }

    private void onServiceReady() {
        // Decide which screen to show based on current network membership
        String networkName = meshService.getMeshManager() != null
                ? meshService.getMeshManager().getCurrentNetworkName() : null;
        if (networkName != null && !networkName.isEmpty()) {
            showMainApp();
        } else {
            showDiscoveryScreen();
        }
    }

    // ─── Navigation ─────────────────────────────────────────────────────

    private void setupBottomNavigation() {
        bottomNav = findViewById(R.id.bottomNav);

        conversationsFragment = new ConversationsFragment();
        peersListFragment = new PeersListFragment();
        topologyFragment = new NetworkTopologyFragment();
        settingsFragment = new SettingsFragment();

        getSupportFragmentManager().beginTransaction()
                .add(R.id.fragmentContainer, settingsFragment, "settings").hide(settingsFragment)
                .add(R.id.fragmentContainer, topologyFragment, "topology").hide(topologyFragment)
                .add(R.id.fragmentContainer, peersListFragment, "peers").hide(peersListFragment)
                .add(R.id.fragmentContainer, conversationsFragment, "chats")
                .commit();
        activeMainFragment = conversationsFragment;

        bottomNav.setOnItemSelectedListener(item -> {
            Fragment selected;
            int id = item.getItemId();
            if (id == R.id.nav_chats) selected = conversationsFragment;
            else if (id == R.id.nav_peers) selected = peersListFragment;
            else if (id == R.id.nav_topology) selected = topologyFragment;
            else if (id == R.id.nav_settings) selected = settingsFragment;
            else return false;

            if (selected != activeMainFragment) {
                getSupportFragmentManager().beginTransaction()
                        .hide(activeMainFragment)
                        .show(selected)
                        .commit();
                activeMainFragment = selected;
            }
            return true;
        });
    }

    /**
     * Transitions to the Network Discovery screen (no bottom nav).
     * Called when the user is not in any network, or after leaving one.
     */
    public void showDiscoveryScreen() {
        bottomNav.setVisibility(android.view.View.GONE);
        // Pop any chat screens
        getSupportFragmentManager().popBackStack(null,
                androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);

        Fragment existing = getSupportFragmentManager()
                .findFragmentByTag("discovery");
        if (existing == null) {
            getSupportFragmentManager().beginTransaction()
                    .add(R.id.fragmentContainer, new NetworkDiscoveryFragment(), "discovery")
                    .commit();
        } else {
            // Hide all main tabs, show discovery
            getSupportFragmentManager().beginTransaction()
                    .hide(conversationsFragment).hide(peersListFragment)
                    .hide(topologyFragment).hide(settingsFragment)
                    .show(existing)
                    .commit();
        }
    }

    /**
     * Transitions to the main 4-tab app after joining/creating a network.
     */
    public void showMainApp() {
        // Remove discovery fragment if present
        Fragment discovery = getSupportFragmentManager()
                .findFragmentByTag("discovery");
        if (discovery != null) {
            getSupportFragmentManager().beginTransaction()
                    .remove(discovery).commit();
        }

        bottomNav.setVisibility(android.view.View.VISIBLE);
        getSupportFragmentManager().beginTransaction()
                .hide(peersListFragment)
                .hide(topologyFragment)
                .hide(settingsFragment)
                .show(conversationsFragment)
                .commit();
        activeMainFragment = conversationsFragment;
        bottomNav.setSelectedItemId(R.id.nav_chats);

        // Register mesh listener on the main tabs
        registerGlobalMeshListener();
    }

    /**
     * Opens a ChatFragment for the given conversation.
     * Hides the bottom nav and adds the chat to the back stack.
     */
    public void openChat(Conversation conv) {
        // Ensure the conversation exists in our map
        if (!conversations.containsKey(conv.id)) {
            conversations.put(conv.id, conv);
            conversationMessages.put(conv.id, new ArrayList<>());
        } else {
            // Reset unread count when opening
            conversations.get(conv.id).unreadCount = 0;
        }

        bottomNav.setVisibility(android.view.View.GONE);

        ChatFragment chatFragment = ChatFragment.newInstance(conv);
        getSupportFragmentManager().beginTransaction()
                .hide(activeMainFragment)
                .add(R.id.fragmentContainer, chatFragment, "chat_" + conv.id)
                .addToBackStack("chat")
                .commit();
    }

    // ─── Conversation data API ───────────────────────────────────────────

    /** Returns the conversations map for use by ConversationsFragment */
    public Map<String, Conversation> getConversations() {
        return conversations;
    }

    /** Returns messages for a specific conversation */
    public List<Message> getMessagesForConversation(String conversationId) {
        return conversationMessages.getOrDefault(conversationId, new ArrayList<>());
    }

    /** Stores a message in the appropriate conversation bucket */
    public void storeMessage(String conversationId, Message message) {
        conversationMessages
                .computeIfAbsent(conversationId, k -> new ArrayList<>())
                .add(message);
    }

    /** Updates the conversation's preview text and timestamp */
    public void updateConversationPreview(String convId, String convName,
                                          boolean isGroup, String lastMsg) {
        Conversation conv = conversations.get(convId);
        if (conv == null) {
            conv = isGroup ? Conversation.createGroup()
                    : Conversation.createPrivate(convId, convName);
            conversations.put(convId, conv);
        }
        conv.lastMessage = lastMsg;
        conv.lastTimestamp = System.currentTimeMillis();
    }

    /**
     * Routes an incoming message to the right conversation.
     * Called by fragments when they receive a message outside their scope.
     */
    public void dispatchMessage(Message message) {
        String convId;
        String convName;

        if (message.getChannelType() == Message.CHANNEL_BROADCAST) {
            convId = Conversation.GROUP_ID;
            convName = "Group Chat";
        } else {
            // Private message — conversation keyed by sender's node ID
            convId = message.getSenderId();
            convName = message.getSenderName();
        }

        // Ensure conversation exists
        if (!conversations.containsKey(convId)) {
            Conversation conv = message.getChannelType() == Message.CHANNEL_BROADCAST
                    ? Conversation.createGroup()
                    : Conversation.createPrivate(convId, convName);
            conversations.put(convId, conv);
            conversationMessages.put(convId, new ArrayList<>());
        }

        // Store the message
        storeMessage(convId, message);

        // Update preview and unread count
        Conversation conv = conversations.get(convId);
        if (conv != null) {
            conv.lastMessage = message.getContent();
            conv.lastTimestamp = message.getTimestamp();
            conv.unreadCount++;
        }

        // Refresh the conversations list if it's visible
        if (activeMainFragment == conversationsFragment) {
            conversationsFragment.refreshList();
        }
    }

    /** Called when an ACK is received — notifies active ChatFragment */
    public void handleDeliveryStatus(String messageId) {
        // The active ChatFragment handles its own UI update via the listener
        // Here we update the persisted message status
        for (List<Message> msgList : conversationMessages.values()) {
            for (Message msg : msgList) {
                if (msg.getId().equals(messageId)) {
                    msg.setDeliveryStatus(Message.DELIVERY_DELIVERED);
                    return;
                }
            }
        }
    }

    // ─── Global mesh listener ────────────────────────────────────────────

    /**
     * Registers a fallback listener that handles messages when no specific
     * fragment has registered its own listener (e.g. when on Settings tab).
     */
    private void registerGlobalMeshListener() {
        if (!isBound || meshService == null || meshService.getMeshManager() == null) return;

        meshService.getMeshManager().setMessageListener(new MeshManager.MessageListener() {
            @Override
            public void onMessageReceived(Message message) {
                runOnUiThread(() -> dispatchMessage(message));
            }

            @Override
            public void onNodeInfoUpdated(List<NodeInfo> nodes) {}

            @Override
            public void onNetworkDiscovered(List<Network> networks) {}

            @Override
            public void onQueueFlushed(int count) {}

            @Override
            public void onDeliveryStatusChanged(String messageId) {
                runOnUiThread(() -> handleDeliveryStatus(messageId));
            }

            @Override
            public void onNetworkJoined(String networkName) {}

            @Override
            public void onNetworkLeft() {
                runOnUiThread(() -> showDiscoveryScreen());
            }
        });
    }

    // ─── Permissions ────────────────────────────────────────────────────

    private void checkPermissions() {
        ArrayList<String> needed = new ArrayList<>();

        // Core Bluetooth
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            addIfNeeded(needed, Manifest.permission.BLUETOOTH_SCAN);
            addIfNeeded(needed, Manifest.permission.BLUETOOTH_ADVERTISE);
            addIfNeeded(needed, Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            addIfNeeded(needed, Manifest.permission.BLUETOOTH);
            addIfNeeded(needed, Manifest.permission.BLUETOOTH_ADMIN);
        }
        // Location required for BT scanning
        addIfNeeded(needed, Manifest.permission.ACCESS_FINE_LOCATION);
        addIfNeeded(needed, Manifest.permission.ACCESS_COARSE_LOCATION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addIfNeeded(needed, Manifest.permission.POST_NOTIFICATIONS);
        }

        if (needed.isEmpty()) {
            startMeshService();
        } else {
            ActivityCompat.requestPermissions(this,
                    needed.toArray(new String[0]), PERMISSIONS_REQUEST_CODE);
        }
    }

    private void addIfNeeded(ArrayList<String> list, String permission) {
        if (ContextCompat.checkSelfPermission(this, permission)
                != PackageManager.PERMISSION_GRANTED) {
            list.add(permission);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            startMeshService();
        }
    }

    private void startMeshService() {
        Intent intent = new Intent(this, MeshService.class);
        startService(intent);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }
}
