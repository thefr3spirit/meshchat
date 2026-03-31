# MeshChat Classroom Project Guide (Beginner-Friendly + Detailed)

This guide is designed for group study and class presentation, including teammates who are new to app development.

---

## 1) What this project is (in plain English)

**MeshChat** is a messaging app that works **without mobile data or internet**.

Instead of using a server (like WhatsApp/Telegram), phones connect directly using:
- **Bluetooth** (short range), and
- **WiFi Direct** (longer range)

A message can move from one phone to another, then another (relay/hop), until it reaches people farther away.

So this app is useful in places like:
- classrooms/labs without internet,
- events,
- emergency/disaster situations,
- any offline local communication environment.

---

## 2) Quick glossary (for non-app-dev teammates)

- **APK**: The installable Android app package.
- **SDK**: Android toolset used to build apps.
- **Gradle**: Build system (compiles and packages app).
- **`gradlew` / `gradlew.bat`**: Wrapper scripts that run Gradle using the project’s exact version.
  - `gradlew` = Linux/macOS
  - `gradlew.bat` = Windows
- **Activity**: A full app screen controller.
- **Fragment**: Reusable sub-screen inside an Activity.
- **Service**: Background component that can keep running even when UI is not visible.
- **Foreground service**: Service with a persistent notification (harder for Android to kill).
- **RecyclerView**: Efficient scrollable list UI component.
- **Adapter**: Bridges data to RecyclerView items.
- **SharedPreferences**: Small key-value storage on device (used here for username).
- **BroadcastReceiver**: Listens for system-wide events (e.g., airplane mode toggled).
- **Encryption**: Scrambling text so outsiders cannot read it.

---

## 3) Build files explained (what each one does)

### Root-level build files

1. **`build.gradle.kts`**
   - Top-level Gradle config.
   - Declares Android plugin usage for project modules.

2. **`settings.gradle.kts`**
   - Declares what modules exist in the project (like `app`).

3. **`gradle/libs.versions.toml`**
   - Central list of dependency versions.
   - Example: Material version, AppCompat version, AGP version.

4. **`gradle.properties`**
   - Global Gradle behavior flags.

5. **`gradlew` and `gradlew.bat`**
   - Project-local launcher scripts for Gradle.
   - Ensures everyone builds with the same Gradle version.

6. **`local.properties`**
   - Local machine config (like Android SDK path).
   - Usually not shared between different computers.

### App module build file

7. **`app/build.gradle.kts`**
   - App-specific settings:
     - namespace/applicationId,
     - minSdk/targetSdk/compileSdk,
     - dependencies,
     - build types (debug/release),
     - Java version.

---

## 4) AndroidManifest explained

**File:** `app/src/main/AndroidManifest.xml`

This file tells Android:
- app identity (`patience.meshchat`),
- required permissions,
- available activities/services,
- launcher entry point.

### Why so many permissions?
Because mesh networking touches hardware and system features:
- Bluetooth (scan/connect/advertise),
- WiFi state and WiFi Direct,
- location (required by Android for nearby device scanning),
- foreground service,
- notifications.

### Main components declared
- `RegistrationActivity` (launcher)
- `MainActivity` (main app shell)
- `MeshService` (foreground background networking engine)

---

## 5) Project architecture (big picture)

Think of the app as 4 layers:

1. **UI layer**
   - `MainActivity` + 3 fragments:
     - `ChatFragment`
     - `NetworkFragment`
     - `SettingsFragment`

2. **Background runtime layer**
   - `MeshService`
   - Keeps networking alive in background.

3. **Networking core layer**
   - `MeshManager`
   - Discovery, connections, routing, relay, queueing.

4. **Support utilities + models**
   - `CryptoManager` (encryption/decryption)
   - `Message` (data model)
   - `ConnectivityObserver` (network + system events)
   - `HardwareDiagnostics` (device info checks)
   - Adapters (`MessageAdapter`, `PeerAdapter`, `EventLogAdapter`)

---

## 6) File-by-file explanation (main Java files)

## A) Entry and navigation

### `RegistrationActivity.java`
**Purpose:** First screen; collect and store username.

- If username already exists in SharedPreferences, skip registration.
- Saves username using:
  - prefs file: `MeshChatPrefs`
  - key: `username`

This username is reused across app components.

### `MainActivity.java`
**Purpose:** App shell + bottom navigation host.

Responsibilities:
- checks registration state,
- requests runtime permissions,
- starts/binds `MeshService`,
- hosts tab fragments (Chat/Network/Settings),
- exposes service to fragments through getters.

---

## B) Core networking

### `MeshService.java`
**Purpose:** Foreground background service.

Responsibilities:
- creates notification channel,
- creates `MeshManager`,
- enters foreground mode (persistent notification),
- survives better in background (`START_STICKY`).

### `MeshManager.java`
**Purpose:** Main networking brain.

Major jobs:
1. discover peers (Bluetooth + WiFi Direct),
2. connect/disconnect peers,
3. send and receive messages,
4. encrypt outgoing messages,
5. decrypt incoming messages,
6. relay messages to other peers,
7. avoid duplicates using processed-ID cache,
8. queue messages when offline,
9. flush queued messages when connection appears,
10. notify UI using listener callbacks.

Key constants (important for viva/presentation):
- Bluetooth service UUID: `fa87c0d0-afac-11de-8a39-0800200c9a66`
- WiFi socket port: `8888`
- Discovery interval: `30s`
- Max dedup IDs cached: `10000`
- Default passphrase: `MeshChat`

---

## C) Security/model

### `CryptoManager.java`
**Purpose:** Encrypt/decrypt message text.

Uses:
- AES-256-GCM
- PBKDF2WithHmacSHA256
- 65,536 iterations
- 12-byte IV
- 128-bit auth tag
- static salt string: `MeshChatSalt2026`

Simple flow:
- before send -> encrypt content,
- after receive -> decrypt content.

If wrong key/passphrase is used, decryption fails and message appears as encrypted/error text.

### `Message.java`
**Purpose:** Serializable message object.

Important fields:
- `id` (UUID for dedup),
- `content`,
- `senderId`, `senderName`,
- `timestamp`,
- `type` (sent/received),
- `hopCount`,
- `originalSenderId`,
- `encrypted` flag.

Important behavior:
- `canForward()` prevents infinite relay loops,
- `MAX_HOPS = 10` controls relay depth,
- `copy()` creates safe clone for network mutation.

---

## D) UI fragments

### `ChatFragment.java`
**Purpose:** Messaging UI.

What it does:
- show chat list,
- send message,
- start scan,
- show nearby peers in bottom sheet,
- connect to selected peer,
- toggle visibility (discoverable/hidden),
- show status (connected nodes + queued count).

Uses:
- `MessageAdapter` for chat bubbles,
- `PeerAdapter` for discovered peer list,
- callbacks from `MeshManager.MessageListener`.

### `NetworkFragment.java`
**Purpose:** Connectivity dashboard.

What it shows:
- network state: Idle / Connected / Active,
- transport type: WiFi/Cellular/Bluetooth/etc,
- mesh stats,
- event history (airplane mode, wifi/bluetooth state changes).

Uses:
- `ConnectivityObserver`,
- `EventLogAdapter`.

### `SettingsFragment.java`
**Purpose:** Profile + hardware diagnostics.

What it does:
- display and update username,
- render diagnostics tiles:
  - CPU,
  - RAM,
  - storage,
  - battery,
  - display,
  - sensors,
  - bluetooth,
  - wifi,
  - device info.

Uses:
- `HardwareDiagnostics.runAll(...)`.

---

## E) Helper components

### `ConnectivityObserver.java`
**Purpose:** Lecture-2 style connectivity monitor + BroadcastReceiver wrapper.

- observes connectivity via `ConnectivityManager.NetworkCallback`,
- listens to system broadcasts:
  - airplane mode,
  - bluetooth state,
  - wifi state,
- stores event history,
- emits callbacks to `NetworkFragment`.

### `HardwareDiagnostics.java`
**Purpose:** Pull hardware/system metrics.

Returns a list of `DiagnosticItem` objects (label, value, color).
Used to populate Settings diagnostics section.

### `MessageAdapter.java`
Binds `Message` list to chat RecyclerView.

### `PeerAdapter.java`
Binds discovered peers to peer list RecyclerView.
Contains `PeerInfo` model and connect-click callback interface.

### `EventLogAdapter.java`
Binds connectivity events to RecyclerView in Network tab.

---

## 7) Resource files explained

### Layout files
- `activity_main.xml`: main shell with fragment container + bottom nav.
- `activity_registration.xml`: username registration UI.
- `fragment_chat.xml`: chat tab UI.
- `fragment_network.xml`: network tab UI.
- `fragment_settings.xml`: settings tab UI.
- `dialog_peer_list.xml`: bottom-sheet peer scanner UI.
- `item_message_sent.xml` / `item_message_received.xml`: chat row designs.
- `item_peer.xml`: discovered peer row.
- `item_event_log.xml`: event log row.

### Other resources
- `menu/bottom_nav_menu.xml`: tab item definitions.
- `drawable/*.xml`: icons and shape backgrounds.
- `values/strings.xml`: all display text.
- `values/colors.xml`: color palette.
- `values/themes.xml`, `values-night/themes.xml`: day/night styles.

---

## 8) End-to-end runtime flow (presentation-friendly)

1. User opens app -> registration check.
2. Username saved/read from SharedPreferences.
3. Main activity starts and asks required permissions.
4. Service starts in foreground.
5. Mesh manager initializes BT + WiFi Direct and starts discovery.
6. User scans peers and taps connect.
7. Connection established; output/input streams ready.
8. User sends message from chat.
9. Message copied, encrypted, sent to connected peers.
10. On relay phones, message is deduplicated, hop incremented, forwarded.
11. On recipient, message decrypts and appears in chat UI.
12. If no peers were connected when sending, message stays in queue and auto-flushes on next connection.

---

## 9) Why this design is good (for lecturer discussion)

- **Resilient:** Foreground service keeps mesh alive.
- **Scalable enough for classroom/demo:** relay + dedup + max hops.
- **Secure content:** AES-GCM + key derivation.
- **User-friendly:** clear tabs and status feedback.
- **Educational value:** demonstrates Android networking, services, fragments, adapters, observers, broadcast receivers, encryption.

---

## 10) Build and run commands (Windows)

From project root:

```bash
gradlew.bat assembleDebug
```
Build debug APK.

```bash
gradlew.bat test
```
Run local JVM tests.

```bash
gradlew.bat connectedAndroidTest
```
Run instrumented tests on device/emulator.

---

## 11) Presentation plan for tomorrow (ready script)

### 2-minute opening
- Problem: messaging without internet.
- Solution: mesh network over Bluetooth + WiFi Direct.
- Highlight: encrypted message relay and offline queue.

### 3-minute architecture walk-through
- Explain `MainActivity` (navigation shell).
- Explain `MeshService` + `MeshManager` as core runtime.
- Explain `CryptoManager` and why encryption is needed in relay networks.

### 3-minute demo flow
- Register username.
- Open chat tab and scan peers.
- Connect and send message.
- Show Network tab updates/events.
- Show Settings diagnostics and username update.

### 2-minute technical close
- Mention dedup strategy (message UUID cache),
- mention hop limit (`MAX_HOPS = 10`),
- mention queued delivery when no peers.

---

## 12) Likely lecturer questions and short answers

### Q1: Why foreground service?
Because Android can kill background tasks. Foreground service keeps mesh networking alive and reliable.

### Q2: How do you prevent message loops in mesh?
Using message UUID dedup cache + hop limit (`MAX_HOPS`).

### Q3: Why encrypt if this is local network only?
Messages pass through third-party relay phones. Encryption protects privacy and integrity.

### Q4: Why both Bluetooth and WiFi Direct?
Bluetooth is power-efficient and common; WiFi Direct gives better range and throughput. Dual transport improves reliability.

### Q5: What happens if user sends while offline?
Message is placed in offline queue and auto-sent when peers reconnect.

---

## 13) Team reading guide (who should read what)

- **UI-focused members:** `MainActivity`, fragments, adapters, layout files.
- **Networking-focused members:** `MeshManager`, `MeshService`, `ConnectivityObserver`.
- **Security-focused members:** `CryptoManager`, message flow in `MeshManager`.
- **Presentation lead:** sections 1, 5, 8, 9, 11, 12 of this guide.

---

## 14) Summary

This project is a strong classroom example of a practical Android system that combines:
- offline-first communication,
- networking fundamentals,
- secure message handling,
- modern Android architecture,
- and clear UI/UX for demonstration.

Use this guide with `docs/TECHNICAL_REFERENCE.md`:
- this file for **teaching and presenting**,
- technical reference for **deep implementation detail**.
