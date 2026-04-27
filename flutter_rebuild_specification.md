# MeshChat — Flutter Rebuild Specification

## 1. Project Overview

**MeshChat** is an infrastructure-free peer-to-peer mesh messaging application. It allows users to exchange text messages with nearby devices over Bluetooth Classic (RFCOMM), Bluetooth Low Energy (BLE/GATT), and Wi-Fi Direct without any internet connection, mobile data, or centralised server. Messages hop between devices to reach recipients multiple radio-hops away. The app is intended for use in environments where normal communication infrastructure is unavailable or unreliable.

- **Platforms to support:** Android (primary target). iOS is an open question — iOS severely restricts BLE advertising and prohibits Wi-Fi Direct, making the core mesh transport largely non-functional. Flag for product decision before any iOS work begins.
- **Authentication:** No account-based authentication. On first launch the user selects a free-text display name (username). That name is stored locally and serves as their identity across all mesh sessions. There is no password, login server, or token-based auth flow.
- **Internet dependency:** The app functions entirely without internet. Firebase Cloud Messaging (FCM) is used as a supplemental notification channel to alert users when a peer has joined the mesh while the app is backgrounded, but the core messaging feature does not require it.

---

## 2. Application Screens & Navigation

### Navigation Overview

The app has two distinct navigation states:

- **Pre-join state:** The user is not a member of any mesh network. Only the Network Discovery screen is shown; the bottom navigation bar is hidden.
- **In-network state:** The user has joined or created a network. A four-tab bottom navigation bar is visible with tabs: Chats, Peers, Network, and Settings.

The Chat screen is a full-screen overlay pushed on top of the bottom navigation (which is hidden while Chat is open). Pressing the back button from Chat restores the bottom navigation.

---

### Screen: Registration

- **Purpose:** Shown once on the very first launch of the app. Allows the user to choose a display name (username) before entering the app. If a username has already been saved, this screen is skipped automatically and the user is sent straight to the main app.
- **Accessible From:** App launch (first time only)
- **Navigates To:** Network Discovery Screen (on successful registration)
- **Key UI Elements:**
  - Large welcome title ("Welcome to MeshChat")
  - Subtitle ("Infrastructure-free messaging")
  - Text input field with label and helper text ("This is how others will see you in the mesh")
  - Primary action button ("Join the Mesh")
  - Inline validation error message below the input
- **User Actions:**
  - Type a username
  - Tap "Join the Mesh" to confirm
- **Data Displayed:** Static welcome copy and input field
- **Loading / Empty / Error States:**
  - Error state: if the field is empty or the username is fewer than 2 characters, a red error message appears beneath the input. The button does not navigate until the error is resolved.
  - On success: a brief toast-style notification ("Welcome, [name]!") appears for approximately 0.6 seconds before navigation occurs.

---

### Screen: Network Discovery

- **Purpose:** Shown whenever the user is not a member of any mesh network. Allows the user to either join an existing nearby network or create a new one. Also shows the user's GPS location on a map.
- **Accessible From:** App start (when not in a network); also shown when the user leaves a network from the Network tab
- **Navigates To:** In-network Main App (after successfully joining or creating a network)
- **Key UI Elements:**
  - Full-screen OpenStreetMap map view centred on the device's GPS location, with a location pin marker and a GPS accuracy chip (e.g. "± 12 m")
  - GPS unavailable overlay with explanatory message (shown when location permission is denied or GPS is disabled)
  - Scrollable list of discovered nearby mesh networks, each showing the network name and how many devices are advertising it
  - Indeterminate progress indicator shown during the active scan window (approximately 13 seconds)
  - Status text label below the list ("Scanning for nearby networks…" / "No networks found nearby" / "Tap a network to join")
  - Extended floating action button labelled "Create Network"
- **User Actions:**
  - View their position on the map (read-only; no interaction)
  - Tap a network in the list to initiate joining it (shows the Connection Progress Dialog)
  - Tap "Create Network" to open the Create Network dialog and start a new network
- **Data Displayed:** Real-time GPS coordinates rendered on map; live list of advertising mesh networks discovered via Bluetooth/BLE/Wi-Fi Direct scanning
- **Loading / Empty / Error States:**
  - Scanning state: progress bar visible, status text shows "Scanning for nearby networks…"
  - Empty state: after the scan window closes with no results, status text changes to "No networks found nearby. Create one to get started!"
  - Results state: status text changes to "Tap a network to join"
  - GPS unavailable: the map is replaced with an overlay message explaining why location cannot be shown
  - GPS permission denied: overlay message "Location permission denied. Cannot show your position."
  - GPS disabled: overlay message "Location services disabled. Enable GPS in Settings."

---

### Dialog: Connection Progress

- **Purpose:** A modal overlay that appears while a join or create operation is in progress. Displays the live multi-step connection pipeline so the user can see exactly where in the process the app is.
- **Accessible From:** Network Discovery Screen (triggered on tap of a network or after creating one)
- **Navigates To:** Dismissed automatically when connection succeeds (transitions to main app) or fails (dismissed with an error snackbar)
- **Key UI Elements:**
  - Title text showing the network name being joined ("Connecting to CampusNet…")
  - A 5-step horizontal stepper showing the connection phases: Scan → Detected → Connecting → Handshaking → Connected
  - Each step has a dot indicator that changes colour (inactive grey → active teal → completed) as the phase advances
  - Thin connector lines between step dots
  - A peer information row (shown when a peer has been detected) displaying the peer's username, the transport type used (e.g. "WiFi Direct", "Bluetooth", "BLE"), and RSSI signal strength bars
  - A status text label below the stepper showing the current phase description
- **User Actions:** None — the dialog is informational only. It may be dismissed if the screen is rotated or if the connection fails.
- **Data Displayed:** Live connection phase updates, detected peer name, transport type, signal strength

---

### Dialog: Create Network

- **Purpose:** A simple confirmation dialog for creating a new mesh network. The user enters a network name that will be broadcast to nearby devices.
- **Accessible From:** Network Discovery Screen (via "Create Network" FAB)
- **Navigates To:** Dismissed on confirm or cancel; Connection Progress Dialog opens on confirm
- **Key UI Elements:**
  - Dialog title ("Create a New Network")
  - Description text ("Enter a name for your mesh network. Others nearby will see this name when scanning.")
  - Text input field with hint ("Network name (e.g. 'CampusNet')")
  - "Create" confirm button
  - "Cancel" dismiss button
- **User Actions:**
  - Type a network name
  - Tap "Create" to confirm
  - Tap "Cancel" to dismiss without action
- **Data Displayed:** None beyond static copy
- **Loading / Empty / Error States:**
  - Error state: if the name field is empty, a validation error is shown and the dialog is not dismissed.

---

### Screen: Chats (Conversations List)

- **Purpose:** The home tab of the main app. Shows all active chat threads — always one group/broadcast thread at the top, followed by any private one-on-one threads the user has participated in, sorted by most recent activity.
- **Accessible From:** Main app bottom navigation (Chats tab); also the default tab shown after joining a network
- **Navigates To:** Chat Screen (on tap of any conversation row)
- **Key UI Elements:**
  - Full-screen scrollable list of conversation rows
  - Each row contains: a circular avatar with the first letter of the peer's name (or "#" for the group), the conversation name, a preview of the last message, a timestamp of the last message (formatted as HH:mm), and an unread message count badge
  - The group thread is always pinned at the top and labelled "Group Chat"
  - Private threads are sorted newest-first below the group
- **User Actions:**
  - Scroll the list
  - Tap a conversation row to open the Chat Screen for that thread
- **Data Displayed:** Conversation name, last message preview, last message time, unread count
- **Loading / Empty / Error States:**
  - When no private conversations exist, only the Group Chat row is shown. There is no explicit empty state — the group always appears.
  - Unread badges disappear when the conversation is opened.

---

### Screen: Chat

- **Purpose:** The messaging screen for a single conversation — either the group broadcast channel or a private one-on-one thread with a specific peer. Displays all messages in the thread and allows the user to send new ones.
- **Accessible From:** Chats Screen (tap on conversation); Peers Screen (tap "Message" button on a peer)
- **Navigates To:** Back to Chats Screen (via back button)
- **Key UI Elements:**
  - Header bar with the conversation name (peer name or "Group Chat") and a back arrow
  - Status text below the header showing the number of connected nodes and any queued message count
  - Scrollable message list with chat bubbles:
    - Sent messages: right-aligned, teal background, white text
    - Received messages: left-aligned, white background, dark text, with a thin border
    - Each bubble shows the sender's name (for received messages), the message content, the time sent, and a delivery status indicator
  - Delivery status indicators on sent messages: Sending (spinner or animated icon), Sent (single checkmark ✓), Delivered (double checkmark ✓✓), Queued (clock or queued icon), Failed (warning icon) — animated with a crossfade transition between states
  - For received messages: hop count shown (how many devices the message traversed to reach the user)
  - Text input field at the bottom ("Type a message…")
  - Send icon button beside the input
- **User Actions:**
  - Scroll through message history
  - Type a message in the input field
  - Tap the send button (or press enter on the keyboard) to send
  - Press back to return to the Chats screen
- **Data Displayed:** All messages in the current conversation thread, ordered chronologically; real-time mesh connection status; delivery receipts
- **Loading / Empty / Error States:**
  - Status text shows "Queued: [n] message(s) — will deliver on reconnect" when there are offline-queued messages
  - Status text shows "[n] peer(s) connected" when online
  - A snackbar appears when queued messages are delivered ("n queued message(s) delivered")
  - A snackbar appears if the mesh service is not ready when the user tries to send
  - If the user leaves the network while on this screen, they are automatically navigated back to the Network Discovery Screen

---

### Screen: Peers

- **Purpose:** Shows all currently connected mesh nodes (peers who are directly or indirectly reachable in the network). Allows the user to initiate a private conversation with any peer.
- **Accessible From:** Main app bottom navigation (Peers tab)
- **Navigates To:** Chat Screen (via "Message" button on a peer row)
- **Key UI Elements:**
  - Screen subtitle ("Currently connected nodes")
  - Scrollable list of connected peer nodes; each row shows:
    - Circular avatar with the peer's first initial
    - Username and the peer's Bluetooth MAC address
    - Signal quality label (e.g. "Good · -65 dBm") with graphical signal bars (1–4 filled bars)
    - A "Message" button to start a private chat
  - Empty state message when no peers are connected
- **User Actions:**
  - Scroll the peer list
  - Tap "Message" to open a private chat with that peer
- **Data Displayed:** Peer username, Bluetooth MAC address, RSSI signal strength, connection status
- **Loading / Empty / Error States:**
  - Empty state: "No peers connected. Join a network and wait for others to appear." (full-screen centered text)
  - The list updates automatically in real time as peers connect and disconnect.

---

### Screen: Network (Topology & Controls)

- **Purpose:** Provides a visual representation of the current mesh topology and exposes network-level controls. The user can see all connected nodes arranged around their own node, tune the auto-connection distance, and leave the network.
- **Accessible From:** Main app bottom navigation (Network tab)
- **Navigates To:** Network Discovery Screen (after successfully leaving the network)
- **Key UI Elements:**
  - Header with the title "Network" and the name of the current network displayed below it
  - A custom canvas-drawn topology visualisation: the local device ("YOU") is rendered as a filled teal circle in the centre; each connected peer is rendered as a circle with their initial, arranged in a ring around the centre; lines connect each peer to the centre node, with opacity varying by signal strength
  - Stats row showing: connected node count, queued message count
  - RSSI threshold slider with a label showing the current dBm value (e.g. "-75 dBm") — controls how close a device must be (signal-strength-wise) for an automatic direct connection to be made
  - A "Leave Network" button (destructive action, styled in red/danger colour)
- **User Actions:**
  - View the topology diagram (read-only)
  - Drag the RSSI slider to tune auto-connection distance (changes take effect immediately)
  - Tap "Leave Network" — triggers a confirmation dialog before disconnecting
- **Data Displayed:** Visual mesh topology; connected node count; queued message count; current network name; RSSI threshold
- **Loading / Empty / Error States:**
  - When only the local node is present (no peers), the topology view shows only the central "YOU" node with no connection lines.
  - Stats update in real time as nodes join and leave.
  - Leave network confirmation dialog: "Leave Network" title, "You will be disconnected from all peers. Leave the network?" message, "Leave" and "Cancel" buttons.

---

### Screen: Settings

- **Purpose:** Allows the user to view and update their display name (username). Also displays a hardware diagnostics panel showing real-time information about the device's hardware components.
- **Accessible From:** Main app bottom navigation (Settings tab)
- **Navigates To:** No navigation (stays on this screen)
- **Key UI Elements:**
  - **Profile section:**
    - Circular avatar showing the user's first initial
    - Current username text
    - "Edit" toggle button that reveals/hides the inline username editor
    - When editing: a text input pre-filled with the current name and a "Save" button
  - **Hardware Diagnostics section:**
    - Section title label ("Hardware Diagnostics")
    - A vertically stacked list of diagnostic tiles, each containing a coloured accent bar, a component label, and a detected value string. Tiles are:
      - CPU — number of cores and architecture (e.g. "8 cores — arm64-v8a")
      - RAM — available and total memory (e.g. "2.1 GB free / 8 GB total")
      - Storage — available and total internal storage
      - Battery — current percentage and charging state (e.g. "76% (Charging)")
      - Display — screen resolution and pixel density
      - Sensors — count and names of available hardware sensors
      - Bluetooth — availability and enabled state
      - WiFi — availability and enabled state
      - Device — manufacturer, model, and Android OS version
  - **About section:**
    - Static text showing app version and description ("MeshChat v2.0 — Infrastructure-free mesh messenger")
- **User Actions:**
  - Tap "Edit" to open the username editor
  - Enter a new username (minimum 2 characters) and tap "Save"
  - Tap "Cancel" to hide the editor without saving
  - Read hardware diagnostics (display only — no interaction)
- **Data Displayed:** Current username; real-time hardware diagnostics values (refreshed every time this tab is visited); static app version information
- **Loading / Empty / Error States:**
  - Username validation: error snackbar if the field is empty or too short.
  - Username success: snackbar confirmation "Username updated to [name]" and the avatar updates immediately.
  - Diagnostics refresh every time the tab becomes visible (no loading indicator — reads are synchronous and fast).

---

### Screen: Network Monitor (Connectivity Observer)

- **Purpose:** A supplemental screen that demonstrates real-time monitoring of internet connectivity state and system-level radio events (Bluetooth toggled, Wi-Fi toggled, Airplane mode changed). It shows the current network state (Idle / Connected / Active), the transport type, mesh stats, and a scrolling timestamped event log.
- **Accessible From:** Not wired into the current bottom navigation — this screen exists in the codebase but is not surfaced in the main navigation. It may be intended as a developer/diagnostic view or for a future release.
- **Navigates To:** No navigation
- **Key UI Elements:**
  - A coloured status dot and state label ("IDLE" / "CONNECTED" / "ACTIVE")
  - Transport type label (e.g. "WiFi", "Cellular", "None")
  - Connected nodes count and queued messages count
  - Visibility status label
  - Scrollable event log list showing timestamped system broadcast events (e.g. "14:32:05 — Bluetooth turned ON")
  - Empty log state label
- **User Actions:** None — display only
- **Data Displayed:** Live connectivity state, transport type, mesh stats, timestamped event history
- **Loading / Empty / Error States:**
  - Empty log state: "No events yet" label shown when the log is empty.

---

## 3. Functional Requirements

### Registration & Identity

- FR-001: On first launch, the app must present the Registration Screen and require the user to choose a display name before entering the app.
- FR-002: The username must be a minimum of 2 characters. Entering an empty username or one shorter than 2 characters must show an inline validation error.
- FR-003: The username must be persisted locally so that the Registration Screen is never shown again on subsequent launches.
- FR-004: The app must automatically skip the Registration Screen and navigate directly to the main app if a username is already saved.
- FR-005: The user must be able to change their username at any time from the Settings screen without losing their message history or network membership.

### Network Discovery & Joining

- FR-006: When the user is not a member of any mesh network, the app must show the Network Discovery Screen and hide the bottom navigation bar.
- FR-007: The app must scan for nearby mesh networks using Bluetooth Classic, BLE, and Wi-Fi Direct simultaneously on entry to the Network Discovery Screen.
- FR-008: Discovered networks must be displayed in a list showing the network name and the number of advertising devices nearby.
- FR-009: The scanning progress indicator must be visible for approximately 13 seconds and then hidden, after which the list shows either results or an empty state.
- FR-010: Tapping a network in the list must initiate the join process and display the Connection Progress Dialog showing the live connection phases.
- FR-011: The Connection Progress Dialog must update in real time through five phases: Scanning → Peer Detected → Connecting → Handshaking → Connected.
- FR-012: When a peer is detected during connection, the dialog must show the peer's username, the transport being used (Wi-Fi Direct / Bluetooth / BLE), and visual signal strength bars.
- FR-013: The user must be able to create a new mesh network by tapping the "Create Network" button, entering a name, and confirming.
- FR-014: A network name must not be empty; creation must be blocked with a validation error if no name is entered.
- FR-015: On successful join or creation, the Connection Progress Dialog must close and the main four-tab app must appear.
- FR-016: On connection failure, the Connection Progress Dialog must close and a snackbar error message must inform the user.

### Mesh Networking & Transport

- FR-017: The app must run a foreground service that keeps the mesh active when the user switches to other apps or locks the screen. A persistent notification must be shown ("MeshChat is active — Relaying messages in the mesh network").
- FR-018: The foreground service must use START_STICKY so that Android restarts it automatically if the system kills it to reclaim memory.
- FR-019: Messages must be routed across the mesh using a flood-fill algorithm: each node re-broadcasts every message it receives to all connected peers except the sender, subject to deduplication.
- FR-020: Duplicate messages must be suppressed using a seen-message-ID set (maximum 10,000 entries before the oldest are evicted).
- FR-021: Every message must carry a hop count incremented at each relay node. Messages exceeding 10 hops must be discarded.
- FR-022: Every message must carry a TTL timestamp (default 24 hours from creation). Messages received after their TTL has expired must be silently discarded.
- FR-023: The app must maintain peer identity by performing a handshake on every new connection, exchanging the user's display name, persistent node UUID, and E2E public key.
- FR-024: The app must broadcast heartbeat messages to all connected peers every 20 seconds to signal liveness.
- FR-025: Peers that have not sent any message (including heartbeats) within 90 seconds must be treated as stale and disconnected. Their entries must be removed from all peer lists and topology views.
- FR-026: When in the foreground, the app must use high-intensity BLE scanning. When backgrounded, it must switch to low-power BLE scanning to conserve battery.
- FR-027: When the device's battery falls below 20%, the app must reduce scan frequency to conserve power.
- FR-028: BLE scanning must operate in duty cycles: 2 seconds active scanning, 8 seconds paused, repeating, rather than continuous scanning.
- FR-029: The transport layer must prefer Wi-Fi Direct for data (highest throughput), fall back to BLE GATT if Wi-Fi Direct is unavailable, and use NFC as a future tertiary option.

### Messaging

- FR-030: Every message sent from the Chats tab "Group Chat" thread must be broadcast to all nodes in the network (broadcast channel).
- FR-031: Private conversations opened from the Peers tab or by tapping a private thread must send messages addressed to a specific peer's node UUID (private channel).
- FR-032: Private messages addressed to the current device must trigger an ACK reply back to the sender.
- FR-033: Sent messages must show a delivery status indicator that transitions through: Sending → Sent → Delivered (on ACK receipt), or Sent → Queued (if the recipient is offline), or Failed (on send error).
- FR-034: Delivery status transitions must be animated with a crossfade effect on the status icon.
- FR-035: The hop count for each received message must be displayed in the message bubble so the user knows how many relay nodes the message traversed.
- FR-036: The Conversations list must maintain an unread message count badge per thread, reset when the thread is opened.
- FR-037: The Conversations list must show a preview of the last message and its timestamp per thread.
- FR-038: Private conversations must appear in the Conversations list below the Group Chat thread, sorted by most recent message timestamp.

### Store-and-Forward (Offline Queuing)

- FR-039: If a message cannot be delivered because the next-hop peer is unreachable, it must be persisted to local storage (SQLite) in a queued_messages table rather than dropped.
- FR-040: When a peer that had queued messages comes back online (detected via BLE scan or Bluetooth discovery), the app must automatically attempt redelivery of all pending messages for that peer.
- FR-041: A WorkManager periodic task must run every 6 hours to expire messages in the queue that are older than 24 hours and to reset any failed delivery attempts for a fresh retry.
- FR-042: The Chat Screen status bar must show the number of currently queued messages so the user knows messages are pending.
- FR-043: When queued messages are successfully delivered, a snackbar must notify the user ("n queued message(s) delivered").

### Encryption

- FR-044: All broadcast messages must be encrypted with AES-256-GCM using a shared passphrase derived into a key via PBKDF2 (65,536 iterations). Relay nodes that do not know the passphrase must not be able to read the content.
- FR-045: All private messages must be encrypted end-to-end using X25519 ECDH with an ephemeral key pair, providing forward secrecy. The shared secret must be derived via SHA-256 and used as an AES-256-GCM key.
- FR-046: Each device's X25519 private key must be generated once on first launch and protected by the Android Keystore (hardware-backed AES-256 wrapper key). The private key must never exist in plaintext on disk.
- FR-047: Each device's X25519 public key must be distributed to all other nodes via handshake on direct connection AND via gossip-propagated Key Announce messages across the full mesh.

### Gossip Anti-Entropy

- FR-048: Every 30 seconds, each node must broadcast a compact Bloom filter containing the IDs of all messages it has seen. This allows other nodes to identify which messages they are missing.
- FR-049: When a node receives a peer's Bloom filter, it must compare its own message set against the filter and push any messages the peer is definitely missing (Bloom false negatives are impossible, so a filter miss is definitive).
- FR-050: Anti-entropy pushes must be capped at 50 messages per cycle to avoid flooding the mesh.

### Peers List

- FR-051: The Peers screen must show all nodes currently reachable in the mesh, including both directly connected peers and multi-hop peers.
- FR-052: Each peer row must display the peer's username, Bluetooth MAC address, RSSI signal quality label ("Excellent" / "Good" / "Fair" / "Weak"), and a 1-to-4-bar signal strength graphic.
- FR-053: The peer list must update in real time as nodes connect and disconnect.
- FR-054: Tapping "Message" on any peer must open a private chat thread for that peer, creating it if it does not already exist.

### Network Topology

- FR-055: The Network screen must render a live canvas diagram showing the local node in the centre and all connected peers arranged in a ring around it, with lines indicating direct Bluetooth links. Line opacity must reflect signal strength.
- FR-056: The Network screen must display the name of the current mesh network.
- FR-057: The user must be able to adjust the RSSI auto-connection threshold using a slider. Devices with a signal above the threshold are auto-connected; those below it are reachable only via relay nodes. Changes must take effect immediately.
- FR-058: The user must be able to leave the current network. A confirmation dialog must be shown before disconnecting. On confirmation, all peer connections must be dropped and the user must be returned to the Network Discovery Screen.

### Internet Connectivity Banner

- FR-059: A persistent banner must appear at the top of the screen when the device's internet connection drops ("No internet connection" in red).
- FR-060: When the device comes back online after being offline, the banner must briefly show "Online · [transport type]" in green for 3 seconds before auto-hiding.
- FR-061: The banner must also show "Connecting…" in orange when a network is detected but not yet validated.

### Push Notifications (FCM)

- FR-062: The app must register with Firebase Cloud Messaging on startup and persist the FCM token locally.
- FR-063: When a new node joins the mesh, the mesh coordinator must send FCM push notifications to all known peer tokens so backgrounded peers are alerted.
- FR-064: Tapping an FCM notification must open the main app.

### Hardware Diagnostics

- FR-065: The Settings screen must display a live hardware diagnostics panel, refreshed every time the tab is visited, reporting: CPU core count and architecture, RAM (available/total), internal storage (available/total), battery level and charging state, display resolution and density, available sensor count, Bluetooth availability and state, Wi-Fi availability and state, and device model/manufacturer/OS version.

### GPS & Map

- FR-066: The Network Discovery Screen must display the user's current GPS location on an OpenStreetMap map, animating to the position on the first GPS fix.
- FR-067: The location accuracy must be shown as a chip label (e.g. "± 12 m").
- FR-068: If location permission is denied or GPS is disabled, an overlay message must be shown on the map area explaining the issue. The rest of the screen (network scanning) must remain functional.
- FR-069: Location updates must be requested from both the network provider (fast coarse fix) and GPS provider (slow accurate fix). The map pin must update as accuracy improves.

---

## 4. Data Models

### Model: Message

| Field | Type | Description |
|---|---|---|
| id | String (UUID) | Unique identifier for this message |
| content | String | The message text (may be encrypted ciphertext) |
| senderId | String (UUID) | The persistent node UUID of the sending device |
| senderName | String | The display name of the sender |
| timestamp | Long (epoch ms) | When the message was created |
| type | Integer (enum) | 0 = received from another device; 1 = sent by this user |
| hopCount | Integer | Number of relay hops the message has taken; max 10 |
| originalSenderId | String (UUID) | UUID of the original sender (before any relaying) |
| encrypted | Boolean | Whether the content field is encrypted ciphertext |
| recipientId | String (UUID) | Target node UUID for private messages; null for broadcast |
| channelType | Integer (enum) | 0 = broadcast; 1 = private/addressed |
| deliveryStatus | Integer (enum) | 0 = sent; 1 = delivered (ACK received) |
| ttlMs | Long (epoch ms) | Absolute expiry time; messages received after this are discarded |
| subType | Integer (enum) | 0 = chat; 1 = handshake; 2 = ACK; 3 = heartbeat; 4 = key announce; 5 = bloom filter; 6 = message request |

---

### Model: Conversation

| Field | Type | Description |
|---|---|---|
| id | String | GROUP_ID ("group") for the broadcast channel; peer's node UUID for private threads |
| peerId | String (UUID) | UUID of the peer; null for the group conversation |
| name | String | Display name of the conversation (peer name or "Group Chat") |
| isGroup | Boolean | True for the broadcast channel; false for private threads |
| lastMessage | String | Preview text of the most recently received or sent message |
| lastTimestamp | Long (epoch ms) | Timestamp of the last message |
| unreadCount | Integer | Number of messages received since the user last opened this thread |

---

### Model: NodeInfo

| Field | Type | Description |
|---|---|---|
| nodeId | String (UUID) | Persistent UUID that identifies this device across sessions |
| address | String | Bluetooth MAC address used internally for routing |
| username | String | The peer's chosen display name |
| rssi | Integer (dBm) | Last known signal strength; Integer.MIN_VALUE if unknown |
| isDirectlyConnected | Boolean | True if this node is on a direct radio link with the local device |

Signal quality labels derived from RSSI: ≥ -60 = Excellent (4 bars), ≥ -70 = Good (3 bars), ≥ -80 = Fair (2 bars), < -80 = Weak (1 bar), unknown = 0 bars.

---

### Model: Network

| Field | Type | Description |
|---|---|---|
| name | String | The human-readable network name entered by the creator |
| nodeCount | Integer | Number of devices seen advertising this network name |
| discoveredAt | Long (epoch ms) | When this network was first detected in the current scan |
| lastSeenMs | Long (epoch ms) | Timestamp of the most recent advertisement from this network |

---

### Model: ChatMessageEntity (Persisted — Room/SQLite)

| Field | Type | Description |
|---|---|---|
| messageId | String (UUID) | Primary key; matches Message.id |
| conversationId | String | FK to the conversation this message belongs to |
| content | String | Message text |
| senderId | String (UUID) | Sender's node UUID |
| senderName | String | Sender's display name |
| timestamp | Long (epoch ms) | When the message was created |
| type | Integer | 0 = received; 1 = sent |
| hopCount | Integer | Relay hop count |
| channelType | Integer | 0 = broadcast; 1 = private |
| deliveryStatus | Integer | 0 = Sending; 1 = Sent; 2 = Delivered; 3 = Failed; 4 = Queued |

---

### Model: QueuedMessage (Persisted — Room/SQLite)

| Field | Type | Description |
|---|---|---|
| messageId | String (UUID) | Primary key; matches the original Message.id |
| nextHopAddress | String | Bluetooth MAC or Wi-Fi Direct IP of the next-hop peer |
| recipientId | String (UUID) | Final destination node UUID; empty for broadcast messages |
| payload | String | Serialised (JSON) message payload for redelivery |
| status | Integer | 0 = Queued; 1 = Delivered; 2 = Failed |
| createdAt | Long (epoch ms) | When the message was first queued; used for TTL expiry |

---

### Model: ChatUiMessage (In-memory UI layer)

| Field | Type | Description |
|---|---|---|
| id | String (UUID) | Message identifier |
| content | String | Decrypted message text |
| senderName | String | Display name of the sender |
| timestamp | Long (epoch ms) | Creation time |
| isSent | Boolean | True if this message was sent by the local user |
| hopCount | Integer | Number of relay hops |
| channelType | Integer | 0 = broadcast; 1 = private |
| status | MessageStatus | Sending / Sent / Delivered / Failed / Queued |

---

## 5. API & Backend Integration

MeshChat does not use a traditional REST or GraphQL backend for its core functionality. All messaging is peer-to-peer over local radio transports. The only cloud integration is Firebase Cloud Messaging for push notifications.

### Firebase Cloud Messaging (HTTP V1 API)

The app sends FCM notifications from device to device using the FCM HTTP V1 API with a service-account credential (JWT bearer token exchange).

- **Base URL:** `https://fcm.googleapis.com/v1/projects/meshchat-56aaf/messages:send`
- **OAuth2 token endpoint:** `https://oauth2.googleapis.com/token`
- **Authentication:** A service account private key (stored in `assets/service-account.json`) is used to sign a JWT, which is exchanged for a short-lived Bearer access token. The token is cached in memory and reused until 60 seconds before expiry.

| Method | Endpoint | Description |
|---|---|---|
| POST | `https://oauth2.googleapis.com/token` | Exchange a signed JWT for a short-lived Bearer access token |
| POST | `https://fcm.googleapis.com/v1/projects/meshchat-56aaf/messages:send` | Send a push notification to a specific device token |

**Notification triggers:**
- When a new node joins the mesh, notifications are sent to all known peer FCM tokens (excluding the joining node itself), with the title "New node joined" and body "[username] has joined the mesh".

**Note:** The service-account JSON key and the project ID (`meshchat-56aaf`) are embedded in the app. The Flutter rebuild team must evaluate whether this is the correct architecture for the production app. Embedding service-account credentials in a client-side app is a security risk and should be replaced with a lightweight cloud function.

---

## 6. Local Storage Requirements

### Key-Value Storage (SharedPreferences equivalent)

| Key | Store | Value | Description |
|---|---|---|---|
| username | MeshChatPrefs | String | The user's chosen display name |
| node_id | MeshChatPrefs | String (UUID) | This device's persistent mesh node UUID, generated once on install |
| current_network_name | MeshChatPrefs | String | The name of the network the user most recently joined |
| rssi_threshold | MeshChatPrefs | Integer (dBm) | Auto-connection RSSI cutoff; default −80 dBm |
| own_token | fcm_tokens | String | This device's current FCM push token |
| peer_[nodeId] | fcm_tokens | String | FCM token for a known peer, keyed by that peer's node UUID |
| e2e_private_key (encrypted) | MeshChatPrefs | String (Base64) | X25519 private key bytes, encrypted with an Android Keystore wrapper key |
| e2e_public_key | MeshChatPrefs | String (Base64) | X25519 public key bytes, stored in plaintext |
| peer_keys_[nodeId] | MeshChatPrefs | String (Base64) | Cached X25519 public key for each known peer, keyed by node UUID |
| osmdroid (various) | osmdroid | Mixed | OSMDroid map tile cache settings |

**Security note for Flutter rebuild:** The private key is currently stored as an encrypted value in SharedPreferences, protected by an Android Keystore AES-256 wrapper key. In Flutter, the equivalent is `flutter_secure_storage`, which uses the Android Keystore (and iOS Secure Enclave / Keychain) directly. The peer public keys do not require secure storage.

### Structured Storage (SQLite / Room equivalent)

Two tables must be persisted in a local SQLite database:

| Table | Purpose | Retention |
|---|---|---|
| chat_messages | Stores every message displayed in the chat UI, enabling the message list to survive app restarts and allowing real-time observable queries | Kept indefinitely (no expiry policy defined in current code — open question) |
| queued_messages | Store-and-forward queue for messages that could not be delivered due to peer unavailability | Entries expire after 24 hours; delivered entries are deleted on the next 6-hour WorkManager cycle; failed entries are reset to queued for retry |

---

## 7. Third-Party Integrations

| Integration | Current Android Library | Usage in App | Flutter Equivalent |
|---|---|---|---|
| Firebase Cloud Messaging | `com.google.firebase:firebase-messaging` | Sends and receives push notifications to wake backgrounded peers when a new node joins the mesh | `firebase_messaging` |
| OpenStreetMap (osmdroid) | `org.osmdroid:osmdroid-android:6.1.18` | Renders a zoomable, draggable map tile view on the Network Discovery screen showing the user's GPS location | `flutter_map` (with OpenStreetMap tile layer) |
| Room (SQLite ORM) | `androidx.room` | Persistent storage for chat messages and the store-and-forward queue; exposes reactive Flow queries for the Compose UI | `drift` (formerly moor) or `sqflite` |
| WorkManager | `androidx.work:work-runtime` | Schedules a periodic background task every 6 hours to expire stale queued messages | `workmanager` |
| Hilt (Dependency Injection) | `com.google.dagger:hilt-android` | Injects transport implementations (Wi-Fi Direct, BLE, NFC) into the composite transport and mesh manager | `get_it` + `injectable` |
| Gson | `com.google.code.gson:gson` | Serialises Message objects to JSON for the store-and-forward queue payload and wire protocol | `dart:convert` (built-in JSON) or `json_serializable` |
| Jetpack Compose | `androidx.compose` (BOM) | Renders the message list as a reactive Compose UI, observing Room Flow queries for automatic recomposition on new messages or delivery status changes | Flutter's native widget system (no equivalent needed — Flutter is already declarative/reactive) |

**Note on mesh transport:** There is no Flutter package that fully replicates the Bluetooth Classic RFCOMM + Wi-Fi Direct + BLE GATT composite transport used by MeshChat. The Flutter rebuild team must evaluate the following options:

- **Google Nearby Connections API** (`nearby_connections` Flutter package): provides a high-level abstraction over Bluetooth + Wi-Fi Direct + BLE, handles discovery and data transfer, and is available on both Android and iOS. This is the most pragmatic approach for the Flutter rebuild.
- **flutter_blue_plus**: covers BLE GATT (advertising, scanning, characteristic reads/writes) but not Bluetooth Classic or Wi-Fi Direct.
- **Native platform channels**: implement the transport layer natively in Java/Kotlin (Android) and Swift/ObjC (iOS) and expose it to Flutter via platform channels. Highest fidelity but highest implementation cost.

See Open Questions section for further discussion.

---

## 8. Permissions Required

| Permission | Platform | Reason |
|---|---|---|
| ACCESS_FINE_LOCATION | Android | Required by Android 12+ to scan for Bluetooth devices and Wi-Fi Direct peers. Also used for GPS positioning on the Network Discovery map. |
| ACCESS_COARSE_LOCATION | Android | Coarse fallback for GPS when fine location is unavailable. Also required by older Android versions for Bluetooth scanning. |
| BLUETOOTH | Android (legacy) | Enables Bluetooth Classic operations on Android 11 and below. |
| BLUETOOTH_ADMIN | Android (legacy) | Allows the app to manage Bluetooth connections and discoverability on Android 11 and below. |
| BLUETOOTH_SCAN | Android 12+ | Required to scan for nearby Bluetooth devices (Classic and BLE). |
| BLUETOOTH_ADVERTISE | Android 12+ | Required to broadcast BLE advertisements so nearby peers can discover this device. |
| BLUETOOTH_CONNECT | Android 12+ | Required to establish Bluetooth Classic (RFCOMM) connections with discovered peers. |
| NEARBY_WIFI_DEVICES | Android 13+ | Required to discover and connect to Wi-Fi Direct peers without needing location permission on Android 13+. |
| INTERNET | Android | Required to reach Firebase Cloud Messaging endpoints and to load OpenStreetMap tiles. |
| ACCESS_NETWORK_STATE | Android | Allows the connectivity observer to monitor internet connectivity state transitions. |
| CHANGE_WIFI_MULTICAST_STATE | Android | Required for certain Wi-Fi Direct multicast operations. |
| ACCESS_WIFI_STATE | Android | Allows reading Wi-Fi state (enabled/disabled, SSID, etc.) for connectivity monitoring. |
| CHANGE_WIFI_STATE | Android | Required to initiate Wi-Fi Direct peer discovery and group formation. |
| NFC | Android | Future NFC transport. Currently declared but not actively used (stub implementation). |
| FOREGROUND_SERVICE | Android | Required to run a foreground service that keeps the mesh relay active when the app is backgrounded. |
| FOREGROUND_SERVICE_CONNECTED_DEVICE | Android 14+ | Specific foreground service type required for Bluetooth/BLE operations in the foreground service. |
| FOREGROUND_SERVICE_DATA_SYNC | Android 14+ | Specific foreground service type required for the Wi-Fi Direct data relay function. |
| POST_NOTIFICATIONS | Android 13+ | Required to show the persistent foreground service notification and FCM push notifications. |

---

## 9. Non-Functional Requirements

### Offline Support

The entire core messaging feature must work without any internet connection. The app must gracefully degrade when internet is absent: FCM notifications will not be sent, OSM map tiles that are not cached will not load (the map should show a blank tile with the location pin still rendered), but peer discovery, connection, and message routing must be fully unaffected. The Store-and-Forward queue ensures no messages are lost when a peer is temporarily unreachable.

### Performance

- The message list must scroll smoothly at 60 fps even when a conversation contains hundreds of messages. Use a lazy-loading list (Flutter `ListView.builder`) and avoid rebuilding the entire list on each new message.
- The BLE duty-cycle scan (2 seconds on, 8 seconds off) is non-negotiable for battery life. The Flutter rebuild must implement equivalent duty-cycling, especially on Android, where continuous BLE scanning can drain the battery rapidly.
- Topology canvas redraws must be throttled — do not redraw on every heartbeat if the node list has not changed.
- All database operations must run on a background isolate or thread, never on the UI thread.

### Security

- The X25519 private key must be stored in hardware-backed secure storage on both Android (Keystore) and iOS (Secure Enclave). Use `flutter_secure_storage` rather than SharedPreferences or Hive for any private key material.
- The service-account JSON key currently embedded in `assets/service-account.json` must NOT be shipped in a production Flutter app. The recommended replacement is a server-side Cloud Function that handles FCM dispatch, so client devices never hold a service account credential.
- The shared passphrase for broadcast AES-256-GCM encryption defaults to "MeshChat" in the current implementation. The Flutter rebuild team must determine whether this default is acceptable or whether users should set a per-network passphrase (see Open Questions).
- AES-256-GCM provides both confidentiality and integrity. The Flutter crypto implementation must maintain GCM (not CBC) to preserve tamper detection.
- Input validation: usernames and network names must be validated on the client before use, as they are broadcast to all nodes in the mesh and displayed to other users.

### Accessibility

No explicit accessibility implementation was observed in the codebase (no content descriptions, no TalkBack support). The Flutter rebuild should add semantic labels to all interactive elements as a baseline.

---

## 10. Recommended Flutter Architecture

### Architecture Pattern

**Clean Architecture with BLoC** is recommended for the Flutter rebuild. The app has multiple distinct domains (mesh transport, cryptography, store-and-forward, gossip protocol, UI state) that benefit from hard separation. BLoC provides predictable, testable state management for real-time features such as peer list updates, message delivery status, and connectivity state changes.

### Folder Structure

```
lib/
  main.dart
  app.dart

  core/
    constants/
    errors/
    utils/

  features/
    registration/
      data/
      domain/
      presentation/

    network_discovery/
      data/
      domain/
      presentation/

    conversations/
      data/
      domain/
      presentation/

    chat/
      data/
      domain/
      presentation/

    peers/
      data/
      domain/
      presentation/

    topology/
      data/
      domain/
      presentation/

    settings/
      data/
      domain/
      presentation/

  mesh/
    transport/
    crypto/
    gossip/
    store_forward/
    models/

  local/
    database/
    preferences/

  notifications/

  di/

android/
ios/
```

### State Management Rationale

BLoC is the right choice because:

- **Real-time event streams:** The mesh engine continuously emits events (peer connected, peer disconnected, message received, delivery status changed, queue flushed). BLoC's stream-based architecture maps naturally onto these event sources.
- **Multiple independent state machines:** Each feature (conversations list, active chat, peer list, topology, connectivity banner) has its own lifecycle and state. BLoC Cubits allow each to operate independently without cross-contaminating state.
- **Testability:** The mesh transport and gossip logic are complex. BLoC cleanly separates business logic from UI, making unit testing each state transition straightforward with `bloc_test`.
- **Predictability:** With real-time data (heartbeats, incoming messages, delivery ACKs) arriving from multiple parallel streams, a unidirectional data flow pattern prevents race conditions in the UI layer.

---

## 11. Recommended Flutter Packages

### Core & Architecture

| Package | Purpose |
|---|---|
| flutter_bloc | State management using BLoC/Cubit pattern |
| get_it | Service locator / dependency injection container |
| injectable | Code generation for get_it registration setup |

### Navigation

| Package | Purpose |
|---|---|
| go_router | Declarative routing with deep link and back-stack support |

### Networking (FCM only — mesh is local radio)

| Package | Purpose |
|---|---|
| http | HTTP client for FCM V1 API calls (lightweight alternative to Dio for this limited use case) |

### Local Storage

| Package | Purpose |
|---|---|
| drift | Type-safe SQLite ORM with reactive stream queries (direct Room equivalent) |
| flutter_secure_storage | Hardware-backed encrypted storage for the X25519 private key and session tokens |
| shared_preferences | Simple key-value storage for username, node ID, RSSI threshold, and network name |

### Mesh Transport

| Package | Purpose |
|---|---|
| nearby_connections | Google Nearby Connections API — high-level abstraction over Bluetooth + Wi-Fi Direct + BLE for peer discovery and data transfer (recommended primary approach) |
| flutter_blue_plus | BLE GATT scanning, advertising, and characteristic I/O (supplemental if Nearby Connections is insufficient) |

### Cryptography

| Package | Purpose |
|---|---|
| pointycastle | Pure-Dart cryptographic primitives including AES-GCM, PBKDF2, and EC key operations |
| cryptography | Alternative crypto library with X25519 and AES-256-GCM support, simpler API than PointyCastle |

### UI Components

| Package | Purpose |
|---|---|
| flutter_map | Tile-based map widget supporting OpenStreetMap (osmdroid equivalent) |
| latlong2 | Latitude/longitude coordinate types used with flutter_map |
| shimmer | Loading skeleton animations for list placeholders |
| lottie | Lottie animation rendering for delivery status icon transitions |

### Location

| Package | Purpose |
|---|---|
| geolocator | GPS location access with provider fallback (GPS + network) and permission handling |
| permission_handler | Runtime permission request handling for location, Bluetooth, and notifications |

### Background & Scheduling

| Package | Purpose |
|---|---|
| workmanager | Background periodic task scheduling for the message expiry worker |
| flutter_local_notifications | Display foreground service persistent notifications and FCM notification handling |

### Firebase

| Package | Purpose |
|---|---|
| firebase_core | Firebase SDK initialisation |
| firebase_messaging | FCM push notification reception and token management |

### Utilities

| Package | Purpose |
|---|---|
| freezed | Immutable data classes and sealed union types (for MessageStatus, ConnectionPhase, NetworkState) |
| json_serializable | JSON serialisation code generation for Message and other wire-format models |
| equatable | Value equality without boilerplate for BLoC state comparison |
| logger | Structured console logging for debug builds |
| connectivity_plus | Internet connectivity state monitoring (Idle / Connected / Active) |
| intl | Date and time formatting for message timestamps |
| uuid | RFC4122 UUID generation for message IDs and node IDs |

### Testing

| Package | Purpose |
|---|---|
| bloc_test | Unit testing for BLoC/Cubit state transitions |
| mocktail | Mocking library for dependencies in unit tests |
| integration_test | Flutter's official integration testing framework |

---

## 12. Open Questions & Assumptions

- **OQ-001: iOS viability.** The core mesh transport relies on Bluetooth Classic RFCOMM, BLE advertising, and Wi-Fi Direct. On iOS, BLE advertising in the background is severely limited (the app must be in the foreground to advertise), and Wi-Fi Direct is not available at all via public APIs. Before any iOS development begins, the product team must decide whether iOS is a target platform and, if so, whether Apple's Multipeer Connectivity framework is a sufficient substitute for the Android transports.

- **OQ-002: Mesh transport strategy for Flutter.** The current app uses a hand-rolled composite transport (BT Classic RFCOMM + Wi-Fi Direct TCP + BLE GATT). Flutter has no complete equivalent. The team must choose between: (a) Google Nearby Connections API (`nearby_connections` package) as a managed higher-level abstraction, (b) platform channels to expose the existing Java transport code directly to Flutter, or (c) a complete reimplementation using `flutter_blue_plus` and a native Wi-Fi Direct channel. This is the highest-risk architectural decision for the Flutter rebuild.

- **OQ-003: Shared network passphrase.** Broadcast messages are encrypted with AES-256-GCM derived from the hardcoded passphrase "MeshChat". Should the Flutter rebuild allow network creators to set a custom passphrase? If so, how is the passphrase distributed to other nodes (it cannot be sent over the mesh unencrypted)? This requires a product decision and possibly a key exchange mechanism.

- **OQ-004: Service-account credentials in the client.** The current app embeds a Google service-account private key in `assets/service-account.json` to authenticate FCM HTTP V1 API calls from the device. This is a significant security risk — anyone who decompiles the APK can extract this key. The Flutter rebuild should replace this with a lightweight Cloud Function (Firebase Functions or similar) that accepts FCM dispatch requests from verified mesh nodes. What is the accepted authentication mechanism for those requests?

- **OQ-005: Chat message retention policy.** The `chat_messages` Room table has no expiry policy — messages persist indefinitely. The queued_messages table has a 24-hour TTL. Should the Flutter rebuild impose a retention limit on chat history (e.g. last 30 days)? If the device is used for extended mesh deployments, the database could grow very large.

- **OQ-006: Message history on first join.** When a new device joins an existing mesh network, it has no message history. The gossip Bloom filter protocol can deliver messages from the last 24 hours that other nodes have seen. Should the Flutter rebuild surface a "Syncing message history…" state to the user when they first join, and should there be a maximum history replay size?

- **OQ-007: Network Discovery map provider.** The current app uses osmdroid with OpenStreetMap (Mapnik) tiles. OpenStreetMap tile usage at scale requires compliance with the tile usage policy or a paid tile hosting arrangement. Should the Flutter rebuild use OSM tiles (via flutter_map), Google Maps, or Mapbox? This affects which SDK and API key setup is required.

- **OQ-008: Network naming and discoverability.** Any device can create a network with any name. There is no uniqueness check or namespace for network names. If two unrelated groups both create a network called "CampusNet", devices will see both and users may join the wrong one. Is this acceptable, or should network names include a random suffix or QR code pairing step?

- **OQ-009: NFC transport.** The NFC transport is declared in the manifest and implemented as a stub. Is active NFC support a requirement for the Flutter rebuild, or should it remain a future placeholder?

- **OQ-010: Foreground service behaviour.** The foreground service requires a persistent notification, which users may find intrusive. Are there product requirements about how this notification should look or behave? Can the user dismiss it (doing so stops the mesh relay)? This needs a product decision before implementation.

- **OQ-011: Multi-device / multi-account support.** The current app supports exactly one user identity per device install. The node UUID is generated once and stored permanently. Should the Flutter rebuild support multiple profiles, device switching, or the ability to reset the node UUID?

- **OQ-012: Tablet and large-screen layouts.** No tablet-specific layouts were observed in the existing app. Should the Flutter rebuild include adaptive layouts for tablets and foldables?

- **OQ-013: Accessibility baseline.** No accessibility attributes (content descriptions, semantic labels, minimum touch target sizes) were found in the existing XML layouts. Does the product have any accessibility compliance requirements (WCAG 2.1 AA, CVAA, etc.)?

- **OQ-014: Internationalisation.** All strings are in English. Is multi-language support a requirement for the Flutter rebuild? If so, which locales should be included?
