# MeshChat — Reference Documentation

## What Is MeshChat?

MeshChat is an Android peer-to-peer messaging application that works entirely over
**Bluetooth RFCOMM** — no internet connection, no central server, no SIM card required.
Devices discover each other by scanning for Bluetooth devices whose names start with
the prefix `MC_<networkName>`.  Once connected, they form a **mesh network**: every node
forwards messages it receives to all other nodes it is connected to, so messages travel
across multi-hop chains even when two devices cannot reach each other directly.

Key capabilities:

| Capability | Detail |
|---|---|
| Infrastructure-free messaging | Works completely offline via Bluetooth |
| Named mesh networks | Users create or join a network by name; devices advertise the name via BT device name |
| Broadcast group chat | Messages flood the entire network |
| Private (direct) messages | Addressed to a specific node UUID; relayed through the mesh if not directly connected |
| Delivery receipts | ACK frames confirm private message delivery (single/double tick) |
| AES-256-GCM encryption | All message payloads are encrypted before being sent over the wire |
| Offline queuing | Messages sent with no peers connected are held and delivered on reconnect |
| Live location map | NetworkDiscoveryFragment shows the user's GPS position on an OSMDroid (OpenStreetMap) map |
| Internet connectivity banner | A global banner slides in/out to signal online/offline state |
| FCM push notifications | When a new node joins the mesh, all other users receive a Firebase Cloud Messaging notification (uses service-account OAuth2 JWT — no Google Play Services dependency for the token exchange) |

---

## Application Flow

```
RegistrationActivity  ──(username set)──►  MainActivity
                                               │
                              ┌────────────────┴────────────────────┐
                              │  Not in a network                   │  In a network
                              ▼                                     ▼
                   NetworkDiscoveryFragment            4-tab bottom nav
                   (map + BT scan + join/create)      ┌──────────────────────┐
                                                       │ ConversationsFragment│
                                                       │ PeersListFragment    │
                                                       │ NetworkTopologyFrag. │
                                                       └──────────────────────┘
                                                         + ChatFragment (back-stack)
```

`MeshService` runs as a foreground service for the entire session and owns a single
`MeshManager` instance.  All fragments communicate with the engine through
`MainActivity.getMeshService().getMeshManager()`.

---

## Java Source Files

### Activities

#### `RegistrationActivity.java`
The launcher activity. Displayed on first run (or if no username is stored).
Prompts the user for a display name and saves it to `SharedPreferences`.
Once saved, starts `MainActivity` and finishes itself.
- Prefs key constants (`PREFS_NAME`, `KEY_USERNAME`) are reused throughout the app.

#### `MainActivity.java`
Navigation host and message-dispatch hub.

Responsibilities:
- Checks whether a username exists; redirects to `RegistrationActivity` if not.
- Requests Bluetooth and notification permissions at runtime.
- Binds to `MeshService` and routes all service callbacks into the correct fragment.
- Holds the **conversation map** (`Map<String, Conversation>`) and per-conversation
  **message lists** (`Map<String, List<Message>>`).
- Switches between `NetworkDiscoveryFragment` (no bottom nav) and the 4-tab layout
  (bottom nav visible) depending on whether the user is in a network.
- Manages the **connectivity banner** (green/red/orange strip at the top) via
  `ConnectivityObserver`.
- Fetches the device's FCM token on startup and persists it via `FcmTokenManager`.

---

### Service

#### `MeshService.java`
Android `Service` subclass (foreground, `connectedDevice` type).
Owns and lifecycle-manages the single `MeshManager` instance.
Exposes a `MeshBinder` so `MainActivity` can call `getService()` and reach the engine.
Posts a persistent notification so Android does not kill the process while the mesh is active.

---

### Mesh Engine

#### `MeshManager.java`
The core networking engine. All Bluetooth work happens here.

**Peer discovery**
- Calls `BluetoothAdapter.startDiscovery()` periodically (every 30 s) and on demand.
- Filters discovered devices by the `MC_` prefix.
- Auto-connects to peers in the same network whose RSSI is above the configurable threshold.

**Connection management**
- `BluetoothServerThread` — listens for inbound RFCOMM connections in a loop;
  accepts any number of simultaneous clients.
- `BluetoothClientThread` — outbound connector; cancels discovery before
  `socket.connect()` (required on Android) and guards against duplicate connections
  via the `connectingNodes` set.
- `MessageReceiverThread` — one per live connection; creates `ObjectOutputStream`
  (header first to prevent deadlock), then `ObjectInputStream`, sends the handshake,
  and enters a read loop.

**Handshake protocol**
- Wire format: `"username|nodeUUID|fcmToken"`
- Bidirectional UUID ↔ MAC mapping is recorded so private messages can be routed.
- On receiving a handshake, the peer's FCM token is stored and all other peers are
  notified via `FcmNotificationSender`.

**Message routing**
- Broadcast: flooded to all connected peers except the one it arrived from.
  Deduplication via an LRU `processedMessages` set (max 10 000 IDs).
- Private: if the recipient's MAC is known and connected, sent directly; otherwise
  flooded so intermediate nodes can relay it.
- Hop limit of 10 prevents routing loops.

**Offline queues**
- `broadcastOfflineQueue` — holds broadcast messages when no peers are connected.
- `perRecipientQueue` — per-UUID queue for private messages; flushed when the
  recipient connects or is reachable through a relay.

**Encryption**
- All chat content is encrypted with `CryptoManager` before being placed on the wire
  and decrypted immediately after receipt.

---

### Fragments

#### `NetworkDiscoveryFragment.java`
First screen shown after login when the user is not yet in a network.

- Embeds an **OSMDroid MapView** showing the user's live GPS location.
  - Requests `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` at runtime via
    `ActivityResultLauncher`.
  - Uses `LocationManager` with both `GPS_PROVIDER` and `NETWORK_PROVIDER`
    (network registered first for a fast coarse fix).
  - `getBestLastKnownLocation()` iterates all providers to show a cached fix
    immediately while waiting for a fresh satellite lock.
  - A `Marker` overlay tracks the current position; an accuracy chip shows `± N m`.
  - A "Locating you…" overlay is shown until the first fix arrives; an error
    overlay is shown if location services are disabled.
  - Full lifecycle management: `onResume` → `mapView.onResume()`;
    `onPause` → `mapView.onPause()` + `removeUpdates()`;
    `onDestroyView` → `mapView.onDetach()`.
- Below the map, a `RecyclerView` lists discovered nearby Bluetooth mesh networks.
- An Extended FAB opens a dialog to create a new network.

#### `ConversationsFragment.java`
Lists all conversations (group chat + one private thread per known peer).
Tapping a row opens `ChatFragment` for that conversation (pushed onto the back stack).

#### `ChatFragment.java`
The actual messaging screen for one conversation (group or private).

- Receives a `Conversation` via Bundle arguments.
- Shows a `RecyclerView` of messages using `MessageAdapter`.
- Routes outgoing messages through `MeshManager.broadcastMessage()` (group) or
  `MeshManager.sendPrivateMessage()` (private).
- Listens for `onMessageReceived`, `onDeliveryStatusChanged`, `onQueueFlushed`, and
  `onNetworkLeft` callbacks.
- Pushes the input bar above the system navigation bar using `WindowInsetsCompat`.

#### `PeersListFragment.java`
Displays all currently connected peer nodes using `PeerAdapter`.
Each row shows the peer's display name, BT MAC, signal strength (RSSI bars),
and online status dot.

#### `NetworkTopologyFragment.java`
Shows a stats summary of the active mesh: number of connected nodes, queued messages,
hop statistics, and any event log entries from `ConnectivityObserver`.

#### `NetworkFragment.java`
Diagnostics tab. Shows internet connectivity state, hardware status from
`HardwareDiagnostics`, and a scrolling event log via `EventLogAdapter`.

#### `SettingsFragment.java`
Allows the user to:
- Change their display name.
- Adjust the RSSI auto-connect threshold (slider).
- Toggle node visibility.
- Leave the current network.

---

### Data Models

#### `Message.java`
Serializable message object transmitted over Bluetooth sockets.

Fields of note:
- `subType` — `SUBTYPE_CHAT`, `SUBTYPE_HANDSHAKE`, or `SUBTYPE_ACK`.
- `channelType` — `CHANNEL_BROADCAST` or `CHANNEL_PRIVATE`.
- `deliveryStatus` — `DELIVERY_SENT` or `DELIVERY_DELIVERED`.
- `recipientId` — target node UUID for private messages; `null` for broadcasts.
- `ttlMs` — absolute expiry timestamp; expired messages are silently dropped.
- `hopCount` — incremented each time the message is forwarded; capped at `MAX_HOPS = 10`.

Factory methods: `createHandshake(username, nodeId, fcmToken)` and
`createAck(originalMsgId, myNodeId, senderNodeId)`.

#### `Conversation.java`
Lightweight model representing one conversation thread.
Fields: `id`, `name`, `isGroup`, `peerId`, `lastMessage`, `lastTimestamp`.
The group conversation has the constant ID `"group"`.

#### `Network.java`
Represents a discovered Bluetooth mesh network.
Fields: `name` (the part after `MC_`), `nodeCount`.
`getNodeCountText()` returns a human-readable peer count string.

#### `NodeInfo.java`
Snapshot of a connected peer's identity for display in the UI.
Fields: `nodeId` (UUID), `macAddress`, `displayName`, `rssi`, `isOnline`.

---

### Adapters

#### `MessageAdapter.java`
`RecyclerView.Adapter` for the chat screen.
Uses two view types — sent (`item_message_sent.xml`) and received
(`item_message_received.xml`) — based on `Message.getType()`.
Renders delivery tick indicators (✓ / ✓✓) for private messages.

#### `PeerAdapter.java`
`RecyclerView.Adapter` for the peers list.
Binds `NodeInfo` objects to `item_peer_node.xml` rows.
Shows signal strength via RSSI bar drawables and an online/offline status dot.

#### `EventLogAdapter.java`
`RecyclerView.Adapter` for the diagnostics event log.
Displays timestamped `ConnectivityObserver.Event` entries.

---

### Connectivity & Diagnostics

#### `ConnectivityObserver.java`
Monitors internet connectivity using `ConnectivityManager.NetworkCallback`.
Also listens for `BroadcastReceiver` events (Bluetooth state, WiFi state, airplane mode).

States: `IDLE` (no network), `CONNECTED` (network present but not validated),
`ACTIVE` (validated internet).

Exposes a `Listener` interface with `onNetworkStateChanged(state, transport)` and
`onEventLogged(event)` callbacks.

Used by `MainActivity` to drive the animated connectivity banner and by
`NetworkFragment` to populate the event log.

#### `HardwareDiagnostics.java`
Utility class that queries the device's hardware capabilities:
- Bluetooth adapter availability and enabled state.
- WiFi adapter availability and enabled state.
- Location services enabled state.
Returns a `DiagnosticsResult` object consumed by `NetworkFragment`.

#### `CryptoManager.java`
Wraps AES-256-GCM encryption/decryption.
- Derives a key from a passphrase using PBKDF2.
- `encrypt(plaintext)` → Base64-encoded ciphertext (IV prepended).
- `decrypt(ciphertext)` → original plaintext.
All messages are encrypted before being written to a Bluetooth socket and decrypted
immediately upon receipt.

---

### FCM (Push Notifications)

#### `MeshChatMessagingService.java`
Extends `FirebaseMessagingService`.
- `onNewToken(token)` — persists the refreshed FCM registration token via
  `FcmTokenManager`.
- `onMessageReceived(remoteMessage)` — builds and posts a system notification
  with a `PendingIntent` that opens `MainActivity` on tap.
  Creates the `meshchat_push` notification channel automatically.

#### `FcmTokenManager.java`
`SharedPreferences`-backed store for FCM tokens.
- Own token: single entry updated whenever Firebase rotates it.
- Peer tokens: keyed by peer node UUID (the same UUID exchanged in handshakes).
- `getAllPeerTokens()` returns a `Map<nodeId, token>` for bulk notification dispatch.

#### `FcmNotificationSender.java`
Sends FCM HTTP V1 push notifications using **only standard Android/Java APIs**
(no `google-auth-library` dependency — avoids AGP resource-merge conflicts).

Flow:
1. Reads `service-account.json` from `assets/`.
2. Builds a signed JWT (RS256) from the service-account email and private key using
   `java.security.Signature` with `SHA256withRSA`.
3. POSTs the JWT to `https://oauth2.googleapis.com/token` to obtain a short-lived
   Bearer token (result cached in memory until 60 s before expiry).
4. POSTs the FCM notification payload to
   `https://fcm.googleapis.com/v1/projects/meshchat-56aaf/messages:send`.
All network calls are dispatched to a background `ExecutorService`.

---

## Resource Files

### Layouts (`res/layout/`)

| File | Used by | Description |
|---|---|---|
| `activity_main.xml` | `MainActivity` | Root `LinearLayout`: connectivity banner + fragment container + hairline divider + `BottomNavigationView` |
| `activity_registration.xml` | `RegistrationActivity` | Teal gradient background, app logo, username `EditText`, and a "Get Started" button |
| `fragment_network_discovery.xml` | `NetworkDiscoveryFragment` | Teal header + `MaterialCardView` housing the OSMDroid `MapView` + "Locating" overlay + `RecyclerView` of networks + Extended FAB |
| `fragment_conversations.xml` | `ConversationsFragment` | Teal header + `RecyclerView` of conversation rows + New-chat FAB |
| `fragment_chat.xml` | `ChatFragment` | Teal header with back arrow + peer avatar + `RecyclerView` for messages + `inputBar` (EditText + send button) |
| `fragment_peers_list.xml` | `PeersListFragment` | Teal header + `RecyclerView` of peer rows |
| `fragment_network_topology.xml` | `NetworkTopologyFragment` | Teal header + stats tile row (nodes, queued msgs, hop avg) + `RecyclerView` event log |
| `fragment_network.xml` | `NetworkFragment` | Diagnostics: connectivity state card + hardware status card + event log list |
| `fragment_settings.xml` | `SettingsFragment` | Teal header + scroll view containing name field, RSSI slider, visibility toggle, leave-network button |
| `item_conversation.xml` | `ConversationsFragment` adapter | 52 dp avatar circle + conversation name + last message preview + timestamp |
| `item_message_sent.xml` | `MessageAdapter` | Right-aligned teal bubble with timestamp and delivery tick |
| `item_message_received.xml` | `MessageAdapter` | Left-aligned white bubble with sender avatar, sender name, and timestamp |
| `item_peer_node.xml` | `PeerAdapter` | 52 dp avatar + display name + MAC address + RSSI bars + online status dot |
| `item_network.xml` | `NetworkDiscoveryFragment` adapter | Network icon (link emoji in teal circle) + network name + node count + Join button |
| `item_peer.xml` | Legacy peer list rows | Simple peer row used in some diagnostic views |
| `item_event_log.xml` | `EventLogAdapter` | Single timestamped event log entry row |
| `dialog_peer_list.xml` | Peer selection dialog | Scrollable list used when choosing a recipient for a private message |

### Drawables (`res/drawable/`)

| File | Description |
|---|---|
| `ic_launcher_foreground.xml` / `ic_launcher_background.xml` | Adaptive launcher icon layers |
| `ic_chat.xml` | Bottom-nav chat tab icon (vector) |
| `ic_peers.xml` | Bottom-nav peers tab icon (vector) |
| `ic_network.xml` | Bottom-nav network tab icon (vector) |
| `ic_settings.xml` | Bottom-nav settings tab icon (vector) |
| `ic_back_arrow.xml` | Back arrow used in fragment headers |
| `ic_send.xml` | Send button icon in the chat input bar |
| `avatar_circle.xml` | Oval shape drawable used as the background for user avatar initials |
| `message_bubble_sent.xml` | Rounded rectangle (teal) for outgoing messages |
| `message_bubble_received.xml` | Rounded rectangle (white + 1 dp stroke) for incoming messages |
| `edit_text_background.xml` | Rounded rectangle with 1 dp stroke for text input fields |
| `bg_registration.xml` | Diagonal teal gradient for the registration screen background |
| `signal_bar.xml` | RSSI signal-strength bar drawable used in the peers list |
| `status_dot.xml` | Small circle used as the online/offline indicator on peer rows |
| `bottom_sheet_handle.xml` | Drag handle shape for bottom sheets |

### Values (`res/values/`)

| File | Description |
|---|---|
| `colors.xml` | Brand palette: `colorPrimary` (#00897B teal), `colorAccent`, surface backgrounds, text colours (`textPrimary`, `textSecondary`, `textHint`), bubble colours, divider colour, and overlay tints |
| `strings.xml` | All user-visible strings: app name, screen titles, button labels, status messages, error strings, and hint texts |
| `themes.xml` | `Theme.MeshChat` extends `Theme.Material3.Light.NoActionBar`. Also defines reusable styles: `MeshButton`, `MeshButtonOutlined`, `MeshButtonDanger` (pill-shaped buttons), `MeshCard` (14 dp corners, 2 dp elevation), `SectionLabel` (all-caps, muted secondary colour) |

### Menu (`res/menu/`)

| File | Description |
|---|---|
| `bottom_nav_menu.xml` | Four items for the bottom navigation bar: Chats (`nav_chats`), Peers (`nav_peers`), Network (`nav_topology`), Settings (`nav_settings`) |

---

## Build & Configuration Files

| File | Description |
|---|---|
| `app/build.gradle.kts` | App-level build script. Sets `compileSdk = 36`, `minSdk = 31`, `targetSdk = 36`. Applies the `google-services` plugin. Declares dependencies: AndroidX AppCompat, Material 3, Activity, ConstraintLayout, Firebase BOM + FCM, OSMDroid 6.1.18. Packaging exclusions for `META-INF` files that conflict during JAR merging |
| `build.gradle.kts` (root) | Root build script. Applies `android-application` and `google-services` plugins with `apply false` |
| `gradle/libs.versions.toml` | Version catalog. Single source of truth for all dependency versions and library/plugin aliases used in the KTS build scripts |
| `settings.gradle.kts` | Declares the project name (`MeshChat`) and the single module (`:app`) |
| `app/src/main/AndroidManifest.xml` | Declares permissions (Bluetooth, Location, Internet, notifications, foreground service), hardware features, the `MeshService` (foreground, `connectedDevice` type), `RegistrationActivity` (launcher), `MainActivity`, and `MeshChatMessagingService` (FCM receiver) |
| `app/src/main/assets/service-account.json` | Firebase service-account credentials used by `FcmNotificationSender` to obtain OAuth2 Bearer tokens for the FCM HTTP V1 API. **Never commit to a public repository.** |
| `app/src/main/assets/google-services.json` | Firebase project configuration consumed by the `google-services` Gradle plugin at build time |

---

## Permissions Used

| Permission | Purpose |
|---|---|
| `BLUETOOTH`, `BLUETOOTH_ADMIN` | Legacy Bluetooth APIs (API < 31) |
| `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE` | Modern Bluetooth APIs (API 31+) |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Required for Bluetooth scanning on Android 10+; also used for the GPS map |
| `NEARBY_WIFI_DEVICES` | Future WiFi Direct discovery |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Internet connectivity monitoring + FCM HTTP calls |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` | WiFi diagnostics |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Keeps `MeshService` alive |
| `POST_NOTIFICATIONS` | FCM and mesh-event notifications |

---

## Data Flow: Sending a Broadcast Message

```
User types message → ChatFragment.sendMessage()
  → MeshManager.broadcastMessage(message)
      → CryptoManager.encrypt(content)
      → processedMessages.add(id)          // deduplication seed
      → sendToAllPeers(encryptedCopy, null)
          → writeToStream(out, message, mac)  // for each connected peer

Peer receives on MessageReceiverThread
  → handleIncomingMessage(message, sourceMac)
      → processedMessages.contains(id)?  → drop duplicate
      → message.isExpired()?             → drop stale
      → message.incrementHopCount()
      → sendToAllPeers(message, sourceMac)  // relay, skip origin
      → CryptoManager.decrypt(content)
      → messageListener.onMessageReceived(message)
          → ChatFragment.addMessage(message)  // shown in UI
```

## Data Flow: Joining a Network (3+ users)

```
Device C: joinNetwork("office")
  → BT name set to "MC_office"
  → startDiscovery()
  → Discovers A and B (both named "MC_office")
  → BluetoothClientThread → cancelDiscovery() → socket.connect()
  → MessageReceiverThread → sendHandshake(username|nodeId|fcmToken)
  → notifyNodeConnected() → mainHandler.postDelayed(startDiscovery, 2s)

Device A (already connected to B):
  → 2 s re-scan triggered by its own previous connection
  → Discovers C → BluetoothClientThread connects to C
  → Mesh is now fully connected: A↔B, A↔C, B↔C
```
