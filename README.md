# MeshChat

Infrastructure-independent Android mesh messenger using **Bluetooth RFCOMM + WiFi Direct + BLE**, with **AES-256-GCM / ECIES X25519** encryption, offline store-and-forward, gossip-based eventual consistency, and a Jetpack Compose UI.

## Overview

MeshChat enables local peer-to-peer messaging without relying on mobile data, internet, or centralized servers. Devices discover each other over Bluetooth, WiFi Direct, and BLE, connect directly, and relay messages hop-by-hop through nearby peers.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java + Kotlin |
| UI | Jetpack Compose (Material 3) + XML |
| DI | Hilt 2.56.2 |
| Persistence | Room 2.6.1 |
| Background | Foreground Service + WorkManager |
| Build | Gradle Kotlin DSL, AGP 9.0.1 |
| Target | API 36 (min 31) |

---

## Assignment Questions — Implementation Summary

### Q1: WiFi Direct Group Owner Discovery

**Question:** *How does a device discover and connect to the WiFi Direct Group Owner in an ad-hoc mesh network?*

**Mechanism:**
- Uses `WifiP2pManager.discoverPeers()` to scan for nearby WiFi Direct devices.
- A `BroadcastReceiver` listens for `WIFI_P2P_PEERS_CHANGED_ACTION` and calls `requestPeers()` to obtain the device list.
- When a peer is selected, `WifiP2pManager.connect()` initiates group formation using `WifiP2pConfig`.
- One device is elected **Group Owner (GO)** — it runs a TCP `ServerSocket` on port 8988.
- Non-GO peers connect to the GO's IP via a TCP `Socket`.
- The GO relays messages between all peers (non-GO peers cannot talk directly to each other).

**Key Files:**
- `MeshManager.java` — `initializeWifiDirect()`, `WifiDirectServerThread`, `WifiDirectClientThread`, `WifiDirectReceiverThread`
- `transport/WifiDirectTransport.java` — transport-abstracted WiFi Direct implementation

---

### Q2: Heartbeat Mechanism & Stale Peer Eviction

**Question:** *What heartbeat mechanism would you implement to detect and remove stale peers from the mesh?*

**Mechanism:**
- A `ScheduledExecutorService` sends `SUBTYPE_HEARTBEAT` messages to all connected peers every **20 seconds**.
- Each peer maintains a `lastSeen` timestamp map, updated on every received message (heartbeat or data).
- A concurrent eviction check runs alongside the heartbeat: any peer whose `lastSeen` exceeds **90 seconds** is considered stale.
- Stale peers have their sockets closed, output streams removed, and identity mappings cleared.
- UI listeners are notified of disconnection so the peers list updates in real time.

**Key Files:**
- `MeshManager.java` — `startHeartbeatScheduler()`, `sendHeartbeat()`, `handleHeartbeat()`, `evictStalePeers()`, `updateLastSeen()`

---

### Q3: End-to-End Encryption via ECIES

**Question:** *How would you implement end-to-end encryption for private messages between two peers who may be multiple hops apart?*

**Mechanism:**
- Each device generates an **X25519 key pair** on first launch.
- The private key is encrypted with an **Android Keystore** AES-256 wrapper key (hardware-backed) and stored in `SharedPreferences`. It never exists in plaintext on disk.
- Public keys are distributed during handshakes and via gossip-propagated `SUBTYPE_KEY_ANNOUNCE` messages across the entire mesh.
- To send a private message: a fresh **ephemeral X25519 key pair** is generated, ECDH is performed with the recipient's public key, the shared secret is hashed (SHA-256) to derive an AES-256 key, and the message is encrypted with **AES-256-GCM**.
- The ciphertext format is `E2E:<Base64(ephemeralPubKey | IV | ciphertext)>`.
- Relay nodes see only ciphertext — they cannot decrypt. This provides **forward secrecy** because ephemeral keys are discarded after each message.

**Key Files:**
- `E2ECryptoManager.java` — key generation, ECDH, encrypt/decrypt, Keystore wrapping
- `CryptoManager.java` — shared-passphrase AES-256-GCM (fallback for broadcast)
- `MeshManager.java` — `sendPrivateMessage()`, `handleKeyAnnounce()`

---

### Q4: BLE Advertising for Dead-Zone Discovery

**Question:** *How would you use BLE advertising to discover peers in a dead zone (no WiFi AP, no cellular)?*

**Mechanism:**
- Each device broadcasts a **BLE advertisement** containing:
  - A custom 128-bit service UUID identifying MeshChat nodes.
  - **Manufacturer data** (14 bytes): WiFi Direct MAC address (6 bytes) + truncated mesh node ID (8 bytes).
- Nearby devices scan for advertisements matching the MeshChat service UUID using a `ScanFilter`.
- When a matching ad is found, the scanner extracts the WiFi Direct MAC and initiates a WiFi Direct connection — **BLE is used only for discovery**, actual messaging uses WiFi Direct's high-bandwidth TCP link.
- The `BleTransport` class also implements full bidirectional messaging over **BLE GATT** characteristics for short messages when WiFi Direct is unavailable.

**Key Files:**
- `BleAdvertiser.java` — `startAdvertising()`, `startScanning()`, manufacturer data encode/decode
- `transport/BleTransport.java` — GATT server/client for BLE data transport

---

### Q5 (not assigned)

---

### Q6a: Store-and-Forward with Room Persistence

**Question:** *How do you handle message persistence using Room for a store-and-forward mechanism?*

**Mechanism:**
- When `sendToNode()` or `sendToAllPeers()` fails (peer offline, socket broken), the message is serialized to Base64 and persisted to Room via `QueuedMessage` entity.
- Messages are keyed by `next_hop_address` — this allows per-hop resume rather than per-recipient, so forwarding resumes from the exact failure point.
- When a peer reconnects (detected via BLE scan, Bluetooth discovery, or WiFi Direct), `StoreAndForwardManager.attemptDelivery(peerAddress)` loads all `QUEUED` messages for that address, deserializes them, and attempts redelivery.
- A `MessageExpiryWorker` (WorkManager periodic task) cleans up messages older than 24 hours.

**Key Files:**
- `StoreAndForwardManager.java` — `enqueue()`, `attemptDelivery()`, `expireOldMessages()`
- `QueuedMessage.java` — Room entity with fields: id, messageData (Base64), nextHopAddress, recipientId, status, timestamp
- `QueuedMessageDao.java` — Room DAO for queue operations
- `MeshChatDatabase.java` — Room database (version 2)
- `MessageExpiryWorker.java` — periodic cleanup via WorkManager

---

### Q6b: Duty-Cycle BLE & Power Management

**Question:** *How do you manage power consumption with BLE scanning in a mesh network?*

**Mechanism:**
- BLE scanning uses a **duty-cycle pattern**: scan for 2 seconds, pause for 8 seconds, repeat. This reduces power consumption by ~80% compared to continuous scanning.
- Scan mode adapts to context:
  - **Foreground** (`appInForeground = true`): `ScanSettings.SCAN_MODE_LOW_LATENCY` — fast discovery.
  - **Background**: `ScanSettings.SCAN_MODE_LOW_POWER` — battery-friendly.
  - **Low battery** (below 20%): scan pause is extended, advertising power drops to `ADVERTISE_TX_POWER_LOW`.
- Battery level is read from `BatteryManager` to dynamically switch modes.
- The duty-cycle handler uses `Handler.postDelayed()` for precise timing without a dedicated thread.

**Key Files:**
- `MeshManager.java` — `startDutyCycleBleScanning()`, `setAppInForeground()`, battery-level checks
- `BleAdvertiser.java` — `startScanning()` with configurable `ScanSettings`

---

### Q7: Compose Message List UI

**Question:** *How do you build a real-time message list using Jetpack Compose with a Room-backed Flow?*

**Mechanism:**
- `ChatMessageDao` exposes a `Flow<List<ChatMessageEntity>>` query via Room's `@Query` annotation. Room automatically emits a new list whenever a row is inserted or updated.
- `ChatViewModel` (AndroidViewModel) maps the Room entities to `ChatUiMessage` display models and exposes a `Flow` to the UI.
- `MessageListScreen` (Composable) collects this Flow via `collectAsState()`, giving the list automatic live updates.
- UI features: sent/received bubbles with teal/grey colours, sender name + avatar initial, hop-count badge with `AnimatedContent`, delivery status icons with `Crossfade` animation, auto-scroll to bottom on new messages.
- `ComposeMessageListHelper.kt` bridges Java fragment code to the Compose screen by calling `setContent {}` on a `ComposeView`.

**Key Files:**
- `ui/MessageListScreen.kt` — Compose UI with bubbles, animations, status indicators
- `ui/ChatViewModel.kt` — ViewModel bridging Room Flow to Compose
- `ui/ComposeMessageListHelper.kt` — Java ↔ Compose bridge
- `ChatMessageEntity.java` — Room entity
- `ChatMessageDao.java` — Flow-based DAO queries

---

### Q8: Transport Abstraction Layer with Hilt DI

**Question:** *How would you design a transport abstraction layer that supports WiFi Direct, BLE, and NFC, using dependency injection?*

**Mechanism:**
- A `MeshTransport` interface defines transport-agnostic operations: `send()`, `listen()`, `discover()`, `cleanup()`, plus callback interfaces for data and discovery events.
- Three concrete implementations:
  - `WifiDirectTransport` — WiFi Direct P2P over TCP sockets
  - `BleTransport` — BLE GATT server/client messaging
  - `NfcTransport` — NFC Android Beam stub (follows the interface)
- `CompositeTransport` aggregates all three into a single facade. When `send()` is called, it tries WiFi Direct first, then BLE, then NFC, returning on the first success.
- **Hilt** provides dependency injection:
  - Each transport is `@Singleton` with an `@Inject` constructor.
  - `TransportModule` (a Hilt `@Module`) binds `MeshTransport` to `CompositeTransport`.
  - `MeshChatApplication` is annotated with `@HiltAndroidApp`.
  - `MeshService` is `@AndroidEntryPoint` and receives `CompositeTransport` via field injection.
- `MeshManager` receives the composite transport via `setCompositeTransport()` and routes incoming data through the existing `handleIncomingMessage()` pipeline.

**Key Files:**
- `transport/MeshTransport.java` — interface with `TransportCallback` and `DiscoveryCallback`
- `transport/WifiDirectTransport.java` — WiFi Direct implementation
- `transport/BleTransport.java` — BLE GATT implementation
- `transport/NfcTransport.java` — NFC stub implementation
- `transport/CompositeTransport.java` — multi-transport facade
- `transport/TransportModule.java` — Hilt DI module
- `MeshChatApplication.java` — `@HiltAndroidApp` entry point
- `MeshService.java` — `@AndroidEntryPoint` with injected transport

---

### Q9: Group Chat with Eventual Consistency (Gossip Protocol)

**Question:** *How do you ensure eventual consistency — that all group members eventually receive a broadcast message even with intermittent connectivity?*

**Mechanism:**

1. **Gossip Flooding (immediate delivery):**
   - When a node sends a broadcast, `MeshManager.broadcastMessage()` encrypts it and calls `sendToAllPeers()`, which writes to every Bluetooth and WiFi Direct output stream.
   - When a node receives a broadcast, `handleIncomingMessage()` checks a **seen-ID set** (LRU cache of 10,000 message IDs). If the message is new, it increments the hop count and re-broadcasts to all peers *except the sender* — this is classic **gossip flooding** with loop prevention.

2. **Bloom Filter Anti-Entropy (consistency repair):**
   - `GossipManager` maintains a `BloomFilter` of all received message IDs. The filter uses SHA-256 double hashing, is sized for 1024 items at 1% false-positive rate (~1.2 KB — compact enough for BLE).
   - Every **30 seconds**, the node serializes its Bloom filter and broadcasts it as a `SUBTYPE_BLOOM_FILTER` control message.
   - When a peer's Bloom filter arrives, the receiver checks each of its own message IDs against the filter:
     - `peerFilter.mightContain(id) == true` → peer probably has it → skip.
     - `peerFilter.mightContain(id) == false` → peer **definitely** doesn't have it → push the missing message.
   - Missing messages are sourced from an in-memory cache or from the Room database (`ChatMessageDao.getMessageById()`).

3. **Persistence across restarts:**
   - On startup, `GossipManager` loads all stored message IDs from Room (`getAllMessageIds()`) into the seen-set, so the Bloom filter is populated even after an app restart.

**Convergence guarantee:** After at most 2 anti-entropy cycles between any pair of connected nodes, they will have identical message sets.

**Key Files:**
- `gossip/BloomFilter.java` — probabilistic set with `add()`, `mightContain()`, `toByteArray()` serialization
- `gossip/GossipManager.java` — anti-entropy scheduler, Bloom filter broadcast/compare, missing message push
- `MeshManager.java` — `handleIncomingMessage()` dispatches `SUBTYPE_BLOOM_FILTER`, calls `gossipManager.recordMessage()` on every new message
- `Message.java` — `SUBTYPE_BLOOM_FILTER = 5`, `SUBTYPE_MESSAGE_REQUEST = 6`
- `ChatMessageDao.java` — `getAllMessageIds()`, `getMessageById()`

---

## Project Structure

```
app/src/main/java/patience/meshchat/
├── MainActivity.java              # Navigation host, binds MeshService
├── RegistrationActivity.java      # First-launch username registration
├── MeshService.java               # Foreground service (@AndroidEntryPoint)
├── MeshChatApplication.java       # Hilt @HiltAndroidApp entry point
├── MeshManager.java               # Core mesh engine (discovery, routing, encryption)
├── Message.java                   # Message model (subtypes, TTL, hop count)
├── Conversation.java              # Chat thread model (group / private)
│
├── CryptoManager.java             # AES-256-GCM shared-passphrase encryption
├── E2ECryptoManager.java          # ECIES/X25519 end-to-end encryption
│
├── BleAdvertiser.java             # BLE advertising & scanning
├── StoreAndForwardManager.java    # Room-persisted offline queue
├── MessageExpiryWorker.java       # WorkManager periodic cleanup
│
├── MeshChatDatabase.java          # Room database (v2)
├── ChatMessageEntity.java         # Room entity — chat messages
├── ChatMessageDao.java            # Room DAO — Flow queries + gossip queries
├── QueuedMessage.java             # Room entity — store-and-forward queue
├── QueuedMessageDao.java          # Room DAO — queue operations
│
├── ConnectivityObserver.java      # Network state monitoring
├── HardwareDiagnostics.java       # Device capability detection
├── FcmTokenManager.java           # FCM token persistence
├── FcmNotificationSender.java     # FCM push notifications
├── MeshChatMessagingService.java   # Firebase messaging service
│
├── ChatFragment.java              # Messaging screen
├── ConversationsFragment.java     # Chat thread list
├── PeersListFragment.java         # Connected peers display
├── NetworkFragment.java           # Connectivity monitor + event log
├── NetworkTopologyFragment.java   # Visual mesh topology map
├── NetworkDiscoveryFragment.java  # Pre-join network discovery
├── SettingsFragment.java          # Settings & diagnostics
│
├── MessageAdapter.java            # RecyclerView — messages (legacy XML)
├── PeerAdapter.java               # RecyclerView — discovered peers
├── EventLogAdapter.java           # RecyclerView — event log
├── Network.java                   # Discovered network model
├── NodeInfo.java                  # Peer node model
│
├── gossip/
│   ├── BloomFilter.java           # Probabilistic set membership
│   └── GossipManager.java        # Anti-entropy gossip protocol
│
├── transport/
│   ├── MeshTransport.java         # Transport interface
│   ├── WifiDirectTransport.java   # WiFi Direct implementation
│   ├── BleTransport.java         # BLE GATT implementation
│   ├── NfcTransport.java         # NFC stub implementation
│   ├── CompositeTransport.java   # Multi-transport facade
│   └── TransportModule.java      # Hilt DI module
│
└── ui/
    ├── MessageListScreen.kt       # Compose chat message list
    ├── ChatViewModel.kt           # ViewModel (Room Flow → Compose)
    ├── ComposeMessageListHelper.kt # Java ↔ Compose bridge
    └── MessageStatus.kt           # Delivery status sealed class
```

## Build

```bash
# Debug build
.\gradlew.bat assembleDebug

# Compile check (without google-services)
.\gradlew.bat compileDebugJavaWithJavac -x processDebugGoogleServices
```

## Permissions

Bluetooth: `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`
WiFi: `NEARBY_WIFI_DEVICES`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`
Location: `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`
Service: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`
