# MeshChat Technical Reference

This document explains the purpose of every project file and class, along with the purpose of methods, class variables/fields, and object/data flow between files.

---

## 1) Project files and purpose

### Root files
- `build.gradle.kts`: Top-level Gradle plugin configuration.
- `settings.gradle.kts`: Declares Gradle modules.
- `gradle.properties`: Build/runtime Gradle flags.
- `gradle/libs.versions.toml`: Version catalog for AGP, AndroidX, Material, test libs.
- `gradlew`, `gradlew.bat`: Gradle wrapper launchers.
- `local.properties`: Local Android SDK path (machine-specific).

### App module files
- `app/build.gradle.kts`: Android module config (namespace, SDK levels, build types, dependencies).
- `app/proguard-rules.pro`: Shrinker/obfuscation rules for release builds.
- `app/src/main/AndroidManifest.xml`: App components, permissions, features.

### UI/resources
- `app/src/main/res/layout/*.xml`: Screens and item layouts.
- `app/src/main/res/drawable/*.xml`: Icons/shapes/backgrounds.
- `app/src/main/res/menu/bottom_nav_menu.xml`: Bottom tab definitions.
- `app/src/main/res/values/*.xml`: Strings/colors/themes.
- `app/src/main/res/xml/*.xml`: Backup/data extraction policies.

### Test files
- `app/src/test/java/.../ExampleUnitTest.java`: Local JVM sample test.
- `app/src/androidTest/java/.../ExampleInstrumentedTest.java`: Instrumented sample test.

---

## 2) Java classes, fields, methods, and object flow

## `RegistrationActivity.java`
**Purpose:** First-run profile gate. Captures username and stores it in SharedPreferences.

### Constants/fields
- `PREFS_NAME = "MeshChatPrefs"`: SharedPreferences file.
- `KEY_USERNAME = "username"`: Username key.
- `MIN_USERNAME_LENGTH = 2`: Validation threshold.

### Methods
- `onCreate(Bundle)`: Initializes registration UI. If username exists, routes to `MainActivity`.
- `launchMainActivity()`: Starts `MainActivity` and finishes registration screen.

### Objects passed to/from other classes
- Writes username used by: `MainActivity`, `MeshManager`, `ChatFragment`, `SettingsFragment`.

---

## `MainActivity.java`
**Purpose:** App shell and navigation host (Chat / Network / Settings tabs).

### Fields
- `PERMISSIONS_REQUEST_CODE`: Runtime permission request code.
- `meshService`: Bound `MeshService` instance.
- `isBound`: Whether service binding is active.
- `chatFragment`, `networkFragment`, `settingsFragment`: Tab fragments.
- `activeFragment`: Currently visible fragment.
- `bottomNav`: Bottom navigation control.
- `connection`: `ServiceConnection` binder callback.

### Methods
- `onCreate(Bundle)`: Registration guard, layout setup, nav setup, permission check.
- `getMeshService()`: Exposes service to child fragments.
- `isServiceBound()`: Exposes bind status.
- `setupBottomNavigation()`: Creates fragments, adds/hides/shows on tab selection.
- `checkPermissions()`: Collects/request required runtime permissions.
- `onRequestPermissionsResult(...)`: Starts service if all required permissions granted.
- `startMeshService()`: Starts and binds to `MeshService`.
- `onDestroy()`: Unbinds service.

### Objects passed to/from other classes
- Passes `MeshService` access to all fragments.
- Receives `MeshService` via binder from `MeshService.MeshBinder`.

---

## `MeshService.java`
**Purpose:** Foreground service to keep networking alive when app is backgrounded.

### Fields/constants
- `CHANNEL_ID`: Notification channel ID.
- `binder`: Binder object returned to activity.
- `meshManager`: Core networking manager.

### Inner class
- `MeshBinder`: Provides `getService()` to return `MeshService`.

### Methods
- `onCreate()`: Creates notification channel and instantiates `MeshManager`.
- `onStartCommand(...)`: Promotes to foreground, starts discovery, returns `START_STICKY`.
- `getMeshManager()`: Provides access to networking core.
- `onDestroy()`: Calls `meshManager.cleanup()`.
- `onBind(Intent)`: Returns binder.
- `createNotificationChannel()`: API 26+ channel setup.

### Objects passed to/from other classes
- Provides `MeshManager` to `MainActivity`/fragments.
- Receives app lifecycle control from Android OS and `MainActivity`.

---

## `MeshManager.java`
**Purpose:** Core mesh engine for discovery, connection management, encryption integration, routing, deduplication, queueing, and callbacks.

### Core fields
- `context`: App context.
- `wifiP2pManager`, `channel`: WiFi Direct API handles.
- `bluetoothAdapter`: Bluetooth API handle.
- `cryptoManager`: Encryption/decryption component.
- `messageListener`: UI callback receiver.
- `mainHandler`: Main-thread callback dispatcher.

### Network state/data fields
- `connectedNodes`: Thread-safe set of connected peer node IDs.
- `processedMessages`: Deduplication cache (LRU-backed set).
- `bluetoothOutputStreams`, `wifiOutputStreams`: Outbound object streams by node.
- `bluetoothSockets`, `wifiSockets`: Active socket maps.
- `discoveredPeers`: Latest discovered peer map.
- `visible`: Discoverability flag.
- `username`: User display/advertising name.
- `offlineMessageQueue`: Queue for unsent encrypted messages.
- `wifiServerThread`, `bluetoothServerThread`: Long-running listeners.
- `discoveryScheduler`: Periodic discovery executor.

### Constants
- `MY_UUID`: Bluetooth RFCOMM service UUID.
- `SERVICE_NAME`: Bluetooth service name.
- `WIFI_PORT`: TCP port for WiFi Direct socket transport.
- `MAX_CACHED_MESSAGE_IDS`: Dedup cache bound.
- `DISCOVERY_INTERVAL_SECONDS`: Auto-discovery interval.
- `DEFAULT_PASSPHRASE`: Default encryption passphrase.

### Callback interface
`MessageListener` methods:
- `onMessageReceived(Message)`
- `onNodeConnected(String)`
- `onNodeDisconnected(String)`
- `onQueueFlushed(int)`
- `onPeerDiscovered(List<PeerAdapter.PeerInfo>)`
- `onScanComplete(int)`

### Key methods and purpose
- `MeshManager(Context)`: Loads username, initializes crypto and network subsystems.
- `initialize()`: Starts servers/receivers/discovery scheduler.
- `setupBluetooth()`: Configures BT adapter name and server thread.
- `registerReceivers()`: Registers WiFi P2P + Bluetooth discovery receivers.
- `startDiscovery()`: Clears peer list, runs WiFi + BT discovery, triggers scan-complete callback.
- `startPeriodicDiscovery()`: Schedules recurring discovery.
- `broadcastMessage(Message)`: Marks message as processed, encrypts network copy, sends or queues.
- `sendToAllPeers(Message)`: Writes message over all active streams.
- `handleIncomingMessage(Message)`: Dedup checks, forwarding, decryption, UI callback.
- `flushOfflineQueue()`: Sends queued messages when peers become available.
- `notifyNodeConnected(String)`: Connection bookkeeping + callback.
- `notifyNodeDisconnected(String)`: Cleanup maps/sockets + callback.
- `connectToPeer(PeerAdapter.PeerInfo)`: Initiates BT or WiFi client connection.
- `notifyPeersUpdated()`: Pushes discovered peer list to UI.
- `setMessageListener(...)`: Sets callback receiver.
- `getConnectedNodeCount()`: Connected node metric.
- `getQueuedMessageCount()`: Queue size metric.
- `getDiscoveredPeers()`: Snapshot list for peer dialogs.
- `setVisible(boolean)`: Controls discoverability behavior.
- `isVisible()`: Returns discoverability status.
- `getCryptoManager()`: Exposes crypto helper.
- `getUsername()`: Exposes local user name.
- `cleanup()`: Unregisters receivers, stops scheduler/threads, closes sockets.
- `hasPermission(String)`: Safe permission guard helper.

### Inner thread classes and purpose
- `BluetoothServerThread`: Accepts RFCOMM inbound sockets.
- `BluetoothClientThread`: Connects to selected Bluetooth peer.
- `WifiServerThread`: Accepts TCP socket connections on `WIFI_PORT`.
- `WifiClientThread`: Connects to WiFi group owner.
- `MessageReceiverThread`: Reads `Message` objects from established stream and hands off to `handleIncomingMessage`.

### Objects passed to/from other classes
- Receives `Message` from `ChatFragment`.
- Encrypts/decrypts through `CryptoManager`.
- Sends connection/message events to `ChatFragment` (via `MessageListener`).
- Uses `PeerAdapter.PeerInfo` for discovered/connectable peer metadata.

---

## `CryptoManager.java`
**Purpose:** Encryption/decryption utility for secure payload transport.

### Fields/constants
- `secretKey`: Active AES key.
- `secureRandom`: IV generator.
- `TRANSFORMATION = "AES/GCM/NoPadding"`
- `GCM_IV_LENGTH = 12`
- `GCM_TAG_LENGTH = 128`
- `PBKDF2_ITERATIONS = 65536`
- `KEY_LENGTH = 256`
- `SALT`: Static PBKDF2 salt bytes.

### Methods
- `CryptoManager(String passphrase)`: Initializes key from passphrase.
- `deriveKey(String)`: PBKDF2 key derivation.
- `encrypt(String)`: Produces Base64 payload of `IV + ciphertext+tag`.
- `decrypt(String)`: Parses payload and decrypts; returns fallback text on auth/key failure.
- `updatePassphrase(String)`: Re-derives key.

### Objects passed to/from other classes
- Called by `MeshManager` before send and after receive.

---

## `Message.java`
**Purpose:** Serializable packet model used in UI and transport.

### Constants
- `TYPE_RECEIVED = 0`
- `TYPE_SENT = 1`
- `MAX_HOPS = 10`
- `serialVersionUID = 2L`

### Fields
- `id`: Unique message UUID.
- `content`: Message text or encrypted payload.
- `senderId`: Origin node ID.
- `senderName`: Human-readable sender.
- `timestamp`: Creation time.
- `type`: Sent/received view type.
- `hopCount`: Relay depth.
- `originalSenderId`: Stable origin ID across forwards.
- `encrypted`: Whether `content` is encrypted.

### Methods
- Constructors for new sent messages and received/forwarded messages.
- Getters for all fields.
- `setContent`, `setType`, `setEncrypted`, `setSenderName`.
- `incrementHopCount()`, `canForward()`.
- `getFormattedTime()` for UI display.
- `copy()` for safe network/UI separation.

### Objects passed to/from other classes
- Created in `ChatFragment`.
- Processed/relayed by `MeshManager`.
- Rendered by `MessageAdapter`.

---

## `ChatFragment.java`
**Purpose:** Chat UI tab for message timeline, send, scan/connect, visibility control, and mesh status.

### Fields
- `messageAdapter`, `messages`: Recycler data.
- UI fields: `recyclerView`, `messageInput`, `sendButton`, `connectionStatus`, `statusDot`, `scanButton`, `visibilityToggle`.
- Peer dialog fields: `discoveredPeers`, `peerAdapter`, `peerDialog`.
- `isVisible`: Local visibility state.

### Methods
- Lifecycle: `onCreateView`, `onViewCreated`, `onResume`, `onDestroyView`.
- UI setup: `initializeViews`, `setupRecyclerView`.
- Service helpers: `getMeshService`, `isServiceBound`.
- Actions: `toggleVisibility`, `startScanning`, `showPeerDialog`, `sendMessage`.
- Callback registration: `setupMeshListener` (handles all 6 `MessageListener` callbacks).
- Status/UI helpers: `updateStatus`, `showSnackbar`.

### Objects passed to/from other classes
- Sends `Message` to `MeshManager.broadcastMessage`.
- Receives message/connection/peer/queue callbacks from `MeshManager`.
- Uses `PeerAdapter` and `MessageAdapter` for rendering.

---

## `NetworkFragment.java`
**Purpose:** Network UI tab that visualizes connectivity state and system event history.

### Fields
- State UI: `networkStateDot`, `networkStateText`, `transportText`.
- Mesh stats UI: `connectedNodesCount`, `queuedMessagesCount`, `visibilityStatus`.
- Event UI: `emptyLogText`, `eventLogRecycler`.
- Data: `connectivityObserver`, `eventLog`, `eventLogAdapter`.

### Methods
- Lifecycle: `onCreateView`, `onViewCreated`, `onResume`, `onPause`.
- UI setup: `initializeViews`, `setupEventLog`.
- Observer callbacks: `onNetworkStateChanged`, `onEventLogged`.
- Metrics: `updateMeshStats`.
- Helper: `setDotColor`.

### Objects passed to/from other classes
- Receives observer callbacks from `ConnectivityObserver`.
- Reads mesh metrics from `MeshManager` through `MainActivity`/`MeshService`.

---

## `ConnectivityObserver.java`
**Purpose:** Lecture-2 connectivity manager + broadcast receiver abstraction.

### Types
- `NetworkState` enum: `IDLE`, `CONNECTED`, `ACTIVE`.
- `Event` class: event description + timestamp + `getFormattedTime`.
- `Listener` interface:
  - `onNetworkStateChanged(NetworkState, String transport)`
  - `onEventLogged(Event)`

### Fields
- `context`, `listener`, `mainHandler`.
- `eventHistory` list.
- `currentState`, `currentTransport`.
- `connectivityManager`, `networkCallback`, `systemEventReceiver`.

### Methods
- Lifecycle: `start`, `stop`.
- Accessors: `getCurrentState`, `getCurrentTransport`, `getEventHistory`.
- Connectivity internals: `registerNetworkCallback`, `unregisterNetworkCallback`, `detectTransport`, `detectTransportFromCaps`.
- Broadcast internals: `registerBroadcastReceiver`, `unregisterBroadcastReceiver`, `handleAirplaneMode`, `handleBluetoothState`, `handleWifiState`.
- Notification internals: `updateState`, `logEvent`.

### Objects passed to/from other classes
- Emits state/event callbacks to `NetworkFragment`.

---

## `SettingsFragment.java`
**Purpose:** Settings UI tab for profile editing and hardware diagnostics rendering.

### Fields
- Profile UI: `userAvatar`, `currentUsername`, `changeUsernameButton`, `usernameEditSection`, `newUsernameInput`, `saveUsernameButton`.
- Diagnostics UI: `hardwareContainer`.

### Methods
- Lifecycle: `onCreateView`, `onViewCreated`, `onResume`.
- Setup: `initializeViews`, `setupUsernameSection`.
- Profile methods: `loadUsername`, `saveUsername`, `getStoredUsername`.
- Diagnostics methods: `populateHardwareDiagnostics`, `createDiagnosticTile`.
- Utility: `dp`, `showSnackbar`.

### Objects passed to/from other classes
- Reads/writes username SharedPreferences shared with `RegistrationActivity` and mesh pipeline.
- Consumes `HardwareDiagnostics.runAll` output.

---

## `HardwareDiagnostics.java`
**Purpose:** Stateless utility that queries device hardware/system capabilities.

### Inner class
- `DiagnosticItem(label, value, colorResId)`.

### Methods
- `runAll(Context)`: Aggregates all diagnostic items.
- `getCpuInfo`, `getRamInfo`, `getStorageInfo`, `getBatteryInfo`, `getDisplayInfo`, `getSensorInfo`, `getBluetoothInfo`, `getWifiInfo`, `getDeviceInfo`.
- `formatBytes(long)`: Human-readable byte formatter.

### Objects passed to/from other classes
- Returns `List<DiagnosticItem>` consumed by `SettingsFragment`.

---

## `MessageAdapter.java`
**Purpose:** RecyclerView adapter for chat bubbles.

### Field
- `messages`: Rendered list.

### Methods
- `onCreateViewHolder`, `onBindViewHolder`, `getItemCount`, `getItemViewType`.
- Inner `MessageViewHolder.bind(Message)` sets message text/time/sender metadata.

### Objects passed to/from other classes
- Consumes `Message` list managed by `ChatFragment`.

---

## `PeerAdapter.java`
**Purpose:** RecyclerView adapter for discovered peers list.

### Types
- `PeerInfo(name, address, isConnected, type)`.
- `OnPeerClickListener.onConnectClicked(PeerInfo)`.
- `PeerViewHolder` UI holder.

### Methods
- Adapter lifecycle methods.
- `PeerViewHolder.bind(PeerInfo)` to populate name/address/state/button behavior.

### Objects passed to/from other classes
- `MeshManager` produces peer info.
- `ChatFragment` displays and passes selected peer back to `MeshManager.connectToPeer`.

---

## `EventLogAdapter.java`
**Purpose:** Recycler adapter for connectivity/system event log rows.

### Field
- `events`: `ConnectivityObserver.Event` list.

### Methods
- Adapter lifecycle methods.
- View binding sets description/time and semantic dot color.

### Objects passed to/from other classes
- Consumes events emitted by `ConnectivityObserver` and collected in `NetworkFragment`.

---

## 3) Resource file purpose map

### Layouts
- `activity_main.xml`: Fragment host + bottom nav shell.
- `activity_registration.xml`: Username onboarding form.
- `fragment_chat.xml`: Chat controls and timeline.
- `fragment_network.xml`: State indicators + event log.
- `fragment_settings.xml`: Profile + diagnostics panel.
- `dialog_peer_list.xml`: Scan results bottom sheet.
- `item_message_sent.xml` / `item_message_received.xml`: Chat rows.
- `item_peer.xml`: Peer list row.
- `item_event_log.xml`: Network event row.

### Drawables/menu/values
- `drawable/*`: Icons and reusable shape backgrounds (status dots, avatar circle, bubbles).
- `menu/bottom_nav_menu.xml`: Bottom tab items and icons.
- `values/strings.xml`: All user-facing text and format strings.
- `values/colors.xml`: Palette and semantic status/diagnostic colors.
- `values/themes.xml`, `values-night/themes.xml`: App theming.

---

## 4) Cross-file object/data flow summary

1. User profile
- `RegistrationActivity` and `SettingsFragment` write username to SharedPreferences.
- `MainActivity`, `MeshManager`, `ChatFragment` read username.

2. Message send path
- `ChatFragment.sendMessage` creates `Message` -> calls `MeshManager.broadcastMessage`.
- `MeshManager` copies and encrypts payload via `CryptoManager`.
- Message is sent to BT/WiFi streams or queued offline.

3. Message receive path
- `MessageReceiverThread` reads serialized `Message` objects.
- `MeshManager.handleIncomingMessage` deduplicates, forwards if allowed, decrypts, marks type received.
- Callback `onMessageReceived` updates `ChatFragment` list and `MessageAdapter`.

4. Peer discovery + connect
- `MeshManager.startDiscovery` + receivers build `PeerInfo` map.
- `ChatFragment` displays peers in `PeerAdapter`.
- User click triggers `MeshManager.connectToPeer`.

5. Network observability
- `NetworkFragment` starts `ConnectivityObserver`.
- `ConnectivityObserver` emits state changes + events to fragment.
- Fragment renders via labels and `EventLogAdapter`.

6. Hardware diagnostics
- `SettingsFragment` calls `HardwareDiagnostics.runAll`.
- Diagnostic items rendered as dynamic tiles.

---

## 5) Build/runtime essentials

- **Application ID:** `patience.meshchat`
- **Compile SDK:** 36
- **Min SDK:** 33
- **Target SDK:** 36
- **Java:** 11
- **AGP:** 9.0.1

Core dependencies:
- `androidx.appcompat:appcompat:1.6.1`
- `com.google.android.material:material:1.10.0`
- `androidx.activity:activity:1.12.4`
- `androidx.constraintlayout:constraintlayout:2.1.4`

Build command:
```bash
gradlew.bat assembleDebug
```

---

## 6) Notes for maintainers

- Keep networking callbacks thread-safe; update UI on main thread.
- Preserve output/input stream initialization order for object streams.
- Use `Message.copy()` before network mutation (encryption/hop changes).
- Keep `processedMessages` bounded to avoid memory growth.
- Any changes to SharedPreferences keys must be synchronized across Registration, Settings, Main, and MeshManager.
