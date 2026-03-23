# MeshChat

Infrastructure-independent Android mesh messenger using **Bluetooth RFCOMM + WiFi Direct**, with **AES-256-GCM** message encryption, offline queueing, and a modern Material 3 UI.

## Overview
MeshChat enables local peer-to-peer messaging without relying on mobile data, internet, or centralized servers. Devices discover each other over Bluetooth and WiFi Direct, connect directly, and relay messages hop-by-hop through nearby peers.

### Key capabilities
- Dual transport networking: Bluetooth + WiFi Direct
- AES-256-GCM payload encryption via `CryptoManager`
- Offline message queue + automatic queue flush on reconnect
- Message deduplication cache to prevent mesh loops
- Bottom navigation architecture:
  - Chat
  - Network (connectivity observer + event log)
  - Settings (hardware diagnostics + username editing)
- Foreground service for resilient background operation

## Tech stack
- Android (Java)
- Material 3
- Gradle Kotlin DSL
- Min SDK 33, Target SDK 36

## Project structure

```text
MeshChat/
├─ app/
│  ├─ src/main/java/patience/meshchat/
│  │  ├─ MainActivity.java
│  │  ├─ RegistrationActivity.java
│  │  ├─ MeshService.java
│  │  ├─ MeshManager.java
│  │  ├─ CryptoManager.java
│  │  ├─ Message.java
│  │  ├─ ChatFragment.java
│  │  ├─ NetworkFragment.java
│  │  ├─ SettingsFragment.java
│  │  ├─ MessageAdapter.java
│  │  ├─ PeerAdapter.java
│  │  ├─ EventLogAdapter.java
│  │  ├─ ConnectivityObserver.java
│  │  └─ HardwareDiagnostics.java
│  └─ src/main/res/
│     ├─ layout/
│     ├─ drawable/
│     ├─ menu/
│     └─ values/
├─ gradle/libs.versions.toml
└─ README.md
```

## Architecture

### Runtime flow
1. `RegistrationActivity` saves username in SharedPreferences.
2. `MainActivity` validates registration, requests permissions, binds `MeshService`.
3. `MeshService` owns and starts `MeshManager` in foreground mode.
4. `MeshManager` runs discovery, connections, encryption, relay, queue, and callbacks.
5. UI fragments interact through `MainActivity.getMeshService()`:
   - `ChatFragment`: messaging + peer actions
   - `NetworkFragment`: connectivity observer + event history
   - `SettingsFragment`: diagnostics + profile settings

### Component interaction (high level)
- `ChatFragment` sends `Message` objects to `MeshManager.broadcastMessage()`.
- `MeshManager` encrypts payload (network copy), relays to all connected streams, or queues if offline.
- Receiver threads deserialize incoming `Message`, deduplicate, forward (if hops remain), decrypt, then notify listener.
- Listener callback updates UI adapters (`MessageAdapter`, `PeerAdapter`, `EventLogAdapter`).

## Connectivity modes
- **Bluetooth RFCOMM** using UUID service socket for close-range links.
- **WiFi Direct** for longer-range local links and higher throughput.
- Both transports can run at the same time.

## Security
- `CryptoManager` uses:
  - PBKDF2WithHmacSHA256 (65,536 iterations)
  - 256-bit AES key
  - AES/GCM/NoPadding
  - 12-byte IV
  - 128-bit auth tag
- Transport payload format: `Base64( IV || Ciphertext+Tag )`

## Offline behavior
- If no active peers are connected, outgoing encrypted messages are queued.
- Queue is flushed automatically when a node connects.

## Permissions
Declared in `AndroidManifest.xml`:
- Bluetooth: `BLUETOOTH`, `BLUETOOTH_ADMIN`, `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`
- WiFi/network: `NEARBY_WIFI_DEVICES`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `ACCESS_NETWORK_STATE`, `INTERNET`
- Location: `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`
- Foreground service + notifications: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`

## Build and run

### Prerequisites
- Android Studio Hedgehog+ or equivalent Gradle/AGP support
- JDK 11+
- Android SDK for API 36

### Build debug APK
```bash
gradlew.bat assembleDebug
```

### Run tests
```bash
gradlew.bat test
gradlew.bat connectedAndroidTest
```

## UX tabs

### 1) Chat
- Message timeline
- Send message
- Scan for peers (bottom sheet)
- One-tap peer connect
- Visibility toggle (discoverable vs hidden)

### 2) Network
- Live state indicator: Idle / Connected / Active
- Transport display (WiFi/Cellular/Bluetooth/Ethernet/Other)
- Mesh stats (connected nodes, queued messages, visibility)
- BroadcastReceiver event history

### 3) Settings
- Username display/edit
- Hardware diagnostics cards (CPU, RAM, storage, battery, display, sensors, Bluetooth, WiFi, device)
- About section

## Configuration constants
- Bluetooth service UUID: `fa87c0d0-afac-11de-8a39-0800200c9a66`
- WiFi TCP port: `8888`
- Discovery interval: 30 seconds
- Max hops: 10
- LRU message ID cache size: 10,000

## Developer notes
- `MeshManager` is the central orchestration class; keep transport, encryption, and relay changes there.
- UI updates from networking callbacks must be posted to main thread.
- Preserve OOS/OIS creation order (ObjectOutputStream then flush, then ObjectInputStream) to avoid stream deadlock.

## Documentation
For exhaustive file/class/method/field/object-flow documentation, see:
- `docs/TECHNICAL_REFERENCE.md`

## Status
Current branch compiles successfully:
- `assembleDebug` ✅
