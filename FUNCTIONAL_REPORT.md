# DropSend — Function-Based Application Report

**Application:** DropSend  
**Platform:** Android (Kotlin & Jetpack Compose)  
**Package:** `com.example`  
**Minimum SDK:** Android 7.0 (API 24) | **Target SDK:** Android 15 (API 35)  
**Architecture Pattern:** Clean Architecture + MVVM + MVI StateFlow + Foreground Services  

---

## 1. Executive Summary

DropSend is an offline, peer-to-peer (P2P), direct device-to-device file transfer application. It facilitates high-speed, local data exchange across Android devices without requiring an internet connection, cloud accounts, or third-party servers. Data is encrypted in transit using session-based AES-GCM and verified with SHA-256 integrity checksums.

---

## 2. Functional Requirements & Implementation Matrix

| Function ID | Function Category | Description / User Outcome | Primary Code Components | Implementation Status |
| :--- | :--- | :--- | :--- | :--- |
| **FN-01** | **File Selection & Ingestion** | Open system document picker for multiple file selection, resolving MIME types, names, and sizes. | `HomeScreen.kt`, `StorageManager.kt`, `ActivityResultContracts.OpenMultipleDocuments` | **Implemented** |
| **FN-02** | **System Share Target** | Ingest `SEND` and `SEND_MULTIPLE` intents from other Android applications. | `MainActivity.kt`, `AndroidManifest.xml`, `StorageManager.kt` | **Implemented** |
| **FN-03** | **Multi-Modal Discovery** | Discover nearby peers via LAN UDP broadcast, Bluetooth Low Energy (BLE), and Wi-Fi Direct. | `DeviceDiscoveryManager.kt`, `LanDiscoveryService.kt`, `BleDiscoveryService.kt`, `WifiP2pDirectManager.kt` | **Implemented** |
| **FN-04** | **Receiver Advertising** | Announce device availability, local IP/port, BLE beacons, and dynamic Wi-Fi Direct groups. | `DeviceDiscoveryManager.kt`, `ReceiveScreen.kt` | **Implemented** |
| **FN-05** | **QR Code Pairing** | Generate and scan QR payloads encoding IP, port, and temporary public session IDs. | `QrCodeDisplay.kt`, `QrScannerDialog.kt`, `ZXing Embedded` | **Implemented** |
| **FN-06** | **Direct IP Connection** | Support manual IP and port input for firewall-restricted or cross-subnet LAN setups. | `SendFlowScreen.kt`, `TcpTransferTransport.kt` | **Implemented** |
| **FN-07** | **Session Encryption** | End-to-end 256-bit AES-GCM encryption with dynamic IVs per chunk and 4-digit verification visual codes. | `SessionCrypto.kt`, `VerifyCodeBadge.kt` | **Implemented** |
| **FN-08** | **Integrity Verification** | Compute and enforce SHA-256 checksum comparison before committing transferred files to disk. | `StorageManager.kt`, `SessionCrypto.kt`, `TransferScreen.kt` | **Implemented** |
| **FN-09** | **Transfer Control** | Pause, resume, reconnect, or cancel active transfers mid-flight. | `DropSendViewModel.kt`, `TransferTransport.kt`, `TransferScreen.kt` | **Implemented** |
| **FN-10** | **Live Telemetry & Speed** | Track chunk progress, dynamic MB/s throughput, rolling ETA, and active transport channel. | `DropSendViewModel.kt`, `RealtimeConnectionStatusBar.kt`, `TransferScreen.kt` | **Implemented** |
| **FN-11** | **Real-Time Connectivity Bar** | Sub-second real-time tracking of Wi-Fi, Hotspot, and Bluetooth states with clickable shortcuts. | `ConnectivityMonitor.kt`, `ConnectivityPill.kt`, `RealtimeConnectionStatusBar.kt` | **Implemented** |
| **FN-12** | **Background Service** | Maintain foreground transfer notifications with real-time percentage, file progress, and cancel actions. | `DropSendTransferService.kt`, `NotificationManager` | **Implemented** |
| **FN-13** | **Local File Persistence** | Save verified transfers to `Downloads/DropSend` using `MediaStore` API (Android 10+) and direct file writes (legacy). | `StorageManager.kt` | **Implemented** |
| **FN-14** | **Transfer History & Database** | Persist historical incoming/outgoing transfers with room database storage, search, filter, and clearing. | `AppDatabase.kt`, `TransferHistoryDao.kt`, `TransferHistoryRepository.kt`, `HistoryScreen.kt` | **Implemented** |
| **FN-15** | **Dynamic Material 3 Theming** | Centralized theme engine with dynamic color palettes (Indigo, Emerald, Violet, Amber, Rose) and dark/light/system modes. | `ThemePreferences.kt`, `ThemeSelectionSheet.kt`, `Theme.kt`, `Color.kt` | **Implemented** |
| **FN-16** | **Sandbox & Simulation** | Built-in testbench simulator for emulator testing with adjustable network speeds, simulated peers, and mock transfers. | `EmulatorTestBottomSheet.kt`, `DropSendViewModel.kt` | **Implemented** |

---

## 3. Function-Based Architecture Breakdown

```text
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           UI Layer (Jetpack Compose)                            │
│  HomeScreen │ SendFlowScreen │ ReceiveScreen │ TransferScreen │ HistoryScreen   │
│  RealtimeConnectionStatusBar │ ConnectivityPill │ ThemeSelectionSheet           │
└────────────────────────────────────────┬────────────────────────────────────────┘
                                         │ StateFlow / Events
┌────────────────────────────────────────▼────────────────────────────────────────┐
│                      ViewModel Layer (DropSendViewModel)                        │
│  Session Orchestration │ Peer Management │ Progress & Telemetry Pipeline       │
└──────┬──────────────────────┬──────────────────────┬─────────────────────┬──────┘
       │                      │                      │                     │
┌──────▼─────────────┐ ┌──────▼─────────────┐ ┌──────▼─────────────┐ ┌─────▼──────┐
│  Discovery Layer   │ │   Transport Layer  │ │   Security Layer   │ │ Storage &  │
│  - LAN (UDP 8889)  │ │ - TCP Socket (8888)│ │ - AES-256 GCM      │ │ Database   │
│  - BLE Scanner/Adv │ │ - Bluetooth SPP    │ │ - SHA-256 Hash     │ │ - Room DB  │
│  - Wi-Fi Direct    │ │ - Chunking Engine  │ │ - 4-Digit Code     │ │ - Storage  │
└────────────────────┘ └────────────────────┘ └────────────────────┘ └────────────┘
```

### 3.1 Connectivity & Real-Time Telemetry Subsystem
- **Component:** `ConnectivityMonitor.kt` & `RealtimeConnectionStatusBar.kt`
- **Functions:**
  - `computeCurrentState()`: Evaluates real-time Bluetooth adapter state, Wi-Fi radio status, and active network capabilities.
  - `startMonitoring()`: Registers system `BroadcastReceiver` filters (`WIFI_STATE_CHANGED_ACTION`, `ACTION_STATE_CHANGED`, `WIFI_AP_STATE_CHANGED`), registers `ConnectivityManager.NetworkCallback`, and runs a 1-second coroutine heartbeat to guarantee live responsiveness.
  - `getLocalIpAddress()`: Scans network link properties and prioritizes interfaces (`wlan0`, `ap0`, `p2p0`, `eth0`) to identify valid non-loopback IPv4 addresses.

### 3.2 Discovery & Pairing Subsystem
- **Component:** `DeviceDiscoveryManager.kt`, `LanDiscoveryService.kt`, `BleDiscoveryService.kt`, `WifiP2pDirectManager.kt`
- **Functions:**
  - `startDiscovery()`: Concurrently launches UDP multicast listener on port `8889`, Bluetooth Low Energy scanner, and Wi-Fi Direct peer discovery.
  - `startAdvertising()`: Broadcasts JSON-encoded announcements (`device_id`, `device_name`, `ip`, `port`, `supported_transports`).
  - `QrCodeDisplay` & `QrScannerDialog`: Encodes/decodes structured connection URLs (`dropsend://connect?ip=...&port=...&id=...`).

### 3.3 Security & Cryptography Subsystem
- **Component:** `SessionCrypto.kt`
- **Functions:**
  - `generateSessionKey()`: Creates a secure 256-bit AES key per transfer session.
  - `encryptChunk(data, key)`: Encrypts packet payloads using `AES/GCM/NoPadding` with a unique 12-byte initialization vector (IV).
  - `decryptChunk(encryptedData, key)`: Validates authentication tags and decrypts payloads in memory.
  - `calculateSha256(file)`: Streams input files through `MessageDigest.getInstance("SHA-256")` for cryptographic checksum verification.
  - `deriveVerificationCode(key, salt)`: Derives a 4-digit human-verifiable security code displayed on both sender and receiver screens.

### 3.4 Transport & Transfer Protocol Subsystem
- **Component:** `TcpTransferTransport.kt`, `BluetoothTransferTransport.kt`, `TransferTransport.kt`, `TransferStateMachine.kt`, `ProtocolMessage.kt`
- **Functions:**
  - `TransferStateMachine`: Formalized deterministic state transition validation (`IDLE`, `DISCOVERING`, `DEVICE_FOUND`, `CONNECTING`, `AUTHENTICATING`, `WAITING_FOR_ACCEPT`, `TRANSFERRING`, `VERIFYING`, `COMPLETED`, `FAILED`, `CANCELLED`, `EXPIRED`, `DISCONNECTED`).
  - `ProtocolMessage`: Versioned framing (`PROTOCOL_VERSION = 2`, `MIN_COMPATIBLE_VERSION = 1`) with safety limits (`MAX_CHUNK_SIZE = 1MB`, `MAX_METADATA_LENGTH = 64KB`).
  - `sendFiles()` / `receiveFiles()`: Breaks files into size-optimized chunks (128 KB for Wi-Fi LAN / Wi-Fi Direct, 32 KB for Bluetooth SPP) with chunk sequence numbers, monotonic ACK offsets, and disk write exception handling.
  - `startSpeedTracker()`: Exponential Moving Average (EMA) smoothed throughput calculation (MB/s) and rolling estimated time of arrival (ETA).

### 3.5 Storage & Persistence Subsystem
- **Component:** `StorageManager.kt`, `AppDatabase.kt`, `TransferHistoryDao.kt`, `TransferHistoryRepository.kt`, `DropSendError.kt`
- **Functions:**
  - `DropSendError`: Unified, domain-wide typed error taxonomy (`DiscoveryFailed`, `PermissionDenied`, `ConnectionFailed`, `StorageFull`, `StorageWriteFailed`, `ChecksumMismatch`, `ProtocolError`, `UnsupportedProtocol`, `Timeout`, `Cancelled`).
  - `resolveFileMetadata(uris)`: Extracts filename, MIME type, and byte length from `ContentResolver`.
  - `saveReceivedFile(tempFile, metadata)`: Streams validated temporary cache files directly into the Android public `Downloads/DropSend` directory using `MediaStore.Downloads` collection.
  - `insertTransfer()` / `getAllTransfers()`: Records completion timestamps, sender/receiver names, total size, file count, and status in Room SQLite database.

---

## 4. End-to-End User Workflows

### 4.1 Sender Journey
1. **Selection:** User taps "Send Files" or shares files via system share intent.
2. **Peer Discovery:** Device displays radar scanning animation; nearby devices appear in real-time.
3. **Session Initiation:** Sender selects a receiver; a session handshake (`TRANSFER_REQUEST`) is transmitted with file manifest and verification code.
4. **Active Transfer:** After receiver approval, files stream in encrypted chunks with live progress, speed metrics, and foreground notification updates.
5. **Completion:** Sender receives `FILE_COMPLETE` acknowledgements; transfer record is persisted to Room DB; success screen is shown.

### 4.2 Receiver Journey
1. **Listening:** User taps "Receive Files"; the app starts UDP advertising, BLE beacons, and TCP listener on port `8888`.
2. **Connection Display:** Displays device ID, local IP/port, and QR code for rapid pairing.
3. **Incoming Prompt:** Modal sheet prompts user with sender name, file list, total size, and 4-digit verification code.
4. **Decryption & Staging:** Incoming chunks are decrypted and appended to a sandbox cache file.
5. **Integrity Check & Storage:** SHA-256 is verified against the sender manifest; the verified file is saved to `Downloads/DropSend`.

---

## 5. Security & Privacy Guarantees

- **100% Offline by Design:** No telemetry, analytics, user identifiers, or metadata are sent over the internet or to external cloud servers.
- **Dynamic Session Keys:** A distinct AES-256 session key is generated for every individual file transfer session.
- **Zero Incomplete File Exposure:** Incomplete or corrupted transfers are quarantined in application cache and deleted if cancelled or if checksum validation fails.
- **Physical Proximity Confirmation:** The 4-digit verification code allows visual peer confirmation against man-in-the-middle attacks.

---

## 6. Permissions & Hardware Compliance

| Permission | Android Level | Purpose |
| :--- | :--- | :--- |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` | All | Monitor Wi-Fi state and initiate Wi-Fi Direct / Hotspot connections. |
| `NEARBY_WIFI_DEVICES` | Android 13+ (API 33+) | Discover and connect to nearby Wi-Fi peers without location access. |
| `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` | Android 12+ (API 31+) | Perform BLE peer discovery, advertising, and RFCOMM transfer fallback. |
| `ACCESS_FINE_LOCATION` | Legacy (API 24-30) | Required on older Android versions for Wi-Fi and Bluetooth scanning. |
| `POST_NOTIFICATIONS` | Android 13+ (API 33+) | Display ongoing transfer progress notifications in foreground service. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Android 14+ (API 34+) | Maintain active socket connection and file streaming while backgrounded. |
| `CAMERA` | All (Optional) | Scan QR codes for instant device pairing. |

---

## 7. Quality Assurance & Verification

- **Robolectric & Unit Tests:** Unit test suites for cryptography (`SessionCrypto`), protocol serialization (`TransferProtocolMessage`), and state management.
- **Emulator Simulation Suite:** Built-in simulation tool capable of injecting simulated sender/receiver transfer sessions at custom throttle rates (e.g. 5 MB/s, 25 MB/s, 100 MB/s).
- **Compilation Status:** Verified with `compile_applet` — Build clean.
