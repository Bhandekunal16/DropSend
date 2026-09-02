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

## 7. Android Compatibility Matrix

| Android Version | API Level | Discovery Mechanisms | Transfer Transport | Background Execution | Storage Mechanism | Runtime Permissions | Validation Status |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Android 7.0–10** | API 24–29 | UDP Multicast, Legacy BLE | TCP Sockets, Bluetooth SPP | Standard Foreground Service | Direct File System (`Downloads/DropSend`) | `ACCESS_FINE_LOCATION`, `BLUETOOTH`, `BLUETOOTH_ADMIN` | `IMPLEMENTED — NOT YET VERIFIED ON PHYSICAL HARDWARE` |
| **Android 11–12** | API 30–31 | UDP Multicast, BLE Scan/Adv | TCP Sockets, Bluetooth SPP | Foreground Service (`connectedDevice`) | Scoped Storage / `MediaStore.Downloads` | `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` | `IMPLEMENTED — NOT YET VERIFIED ON PHYSICAL HARDWARE` |
| **Android 13** | API 33 | UDP, BLE, Wi-Fi Direct | TCP Sockets, Bluetooth SPP | Foreground Service | `MediaStore.Downloads` (`DropSend` dir) | `NEARBY_WIFI_DEVICES`, `POST_NOTIFICATIONS` | `IMPLEMENTED — NOT YET VERIFIED ON PHYSICAL HARDWARE` |
| **Android 14** | API 34 | UDP, BLE, Wi-Fi Direct | TCP Sockets, Bluetooth SPP | FGS (`connectedDevice\|dataSync`) | `MediaStore.Downloads` | `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | `IMPLEMENTED — NOT YET VERIFIED ON PHYSICAL HARDWARE` |
| **Android 15 / 16** | API 35–36 | UDP, BLE, Wi-Fi Direct | TCP Sockets, Bluetooth SPP | FGS 6-hour DataSync limits handled | `MediaStore.Downloads` | Strict 16KB page alignment compatible | `VERIFIED (JVM / Robolectric API 36)` |

---

## 8. Failure & Recovery Matrix

| Failure Scenario | Trigger Condition | System Action | Final Transfer State | User Recovery Guidance |
| :--- | :--- | :--- | :--- | :--- |
| **Network Disconnect** | Wi-Fi dropped / Socket closed | Preserves `.part` file, pauses transfer | `DISCONNECTED` / `FAILED` | Reconnect to same Wi-Fi network and tap Resume. |
| **Sender Cancellation** | Sender presses Cancel | Sends `CANCEL` frame, tears down session, clears temp buffers | `CANCELLED` | Start a new transfer when ready. |
| **Receiver Cancellation** | Receiver presses Cancel | Sends `CANCEL` frame, deletes unverified `.part` file | `CANCELLED` | File was discarded; no partial files remain on disk. |
| **Checksum Mismatch** | SHA-256 hash doesn't match manifest | Quarantines & immediately deletes `.part` file | `FAILED` | Checksum verification failed; transfer aborted for data integrity. |
| **Storage Full** | Free disk space < file size + 50MB | Pre-flight check halts transfer prior to receiving chunks | `FAILED` | Free up device storage and retry transfer. |
| **Storage Write Failure** | I/O error writing to disk | Halts stream, reports error to UI, deletes partial buffer | `FAILED` | Check device storage permissions and retry. |
| **Peer Rejection** | Receiver declines incoming prompt | Sends `SESSION_REJECT` frame, disconnects socket | `REJECTED` | Transfer was declined by the receiver. |
| **Handshake Timeout** | Peer does not respond within 15s | Socket closes, resources wiped | `FAILED` | Check if peer is still awake and within range. |

---

## 9. Performance & Telemetry Model

### 9.1 Benchmark Methodology
- **Simulated Testbench (In-App Simulator):** Provides synthetic network throttling (5 MB/s, 25 MB/s, 100 MB/s) to validate UI smoothness, progress interpolation, and state transitions without physical hardware.
- **Physical Device Benchmark Suite (Procedure Defined):**
  - **Metrics Measured:** Peer discovery latency (ms), connection setup time (ms), cryptographic handshake duration (ms), effective transfer throughput (MB/s), memory allocation (MB), and resume overhead (ms).
  - **Physical Device Benchmark Status:** `NOT TESTED ON PHYSICAL HARDWARE` (Hardware lab validation pending).

### 9.2 Real-Time Telemetry Pipeline
- **Throughput Calculation:** Computed via Exponential Moving Average (EMA) with smoothing factor $\alpha = 0.35$:
  $$\text{Speed}_{\text{smoothed}} = \alpha \cdot \text{Speed}_{\text{instant}} + (1 - \alpha) \cdot \text{Speed}_{\text{prev}}$$
- **ETA Estimation:** Calculated as $\frac{\text{Remaining Bytes}}{\text{Speed}_{\text{smoothed}}}$, gracefully clamped to avoid divide-by-zero or negative values.

---

## 10. Storage & File Management Policy

1. **Filename Collision Resolution:** If `Downloads/DropSend/photo.jpg` exists, the system automatically resolves to `photo (1).jpg`, `photo (2).jpg`, etc., preventing data overwrite.
2. **Path Traversal & Unicode Sanitization:** Strips directory traversal sequences (`../`), null bytes (`\0`), and reserved filesystem characters while preserving valid Unicode filenames across languages.
3. **Large File Support:** Employs 64-bit `Long` variables for all file sizes, byte offsets, transfer progress, and checksum streams, supporting transfers exceeding 4 GB+.
4. **Atomic Commit:** Received chunks are written to temporary sandbox cache files (`fileId_name.part`) and only promoted to public `Downloads/DropSend` upon passing end-to-end SHA-256 integrity checks.

---

## 11. Concurrency & Queueing Policy

- **Single Active Transfer Policy:** To ensure strict cryptographic isolation, zero session key cross-talk, and predictable foreground service lifecycle, DropSend enforces **one active transfer session at a time**.
- **Multi-File Batching:** Multiple files selected by the user are bundled into a single atomic transfer session with individual file progress and overall batch progress tracking.

---

## 12. Quality Assurance & Test Verification

- **Automated Unit & Robolectric Tests:** Comprehensive test suite covering:
  - Ephemeral ECDH key exchange & RFC 5869 HKDF derivation
  - AES-256-GCM authenticated encryption and tamper detection
  - Short Authentication String (SAS) 4-digit code matching
  - Protocol message serialization & version negotiation (v1–v2)
  - `TransferStateMachine` legal vs. illegal state transitions
  - Filename collision resolution & Unicode path sanitization
  - Large file offset and size formatting (> 4 GB)
  - `StorageManager` temp file caching and storage space pre-validation
  - High-contrast QR bitmap generation with pure opaque modules, quiet zone isolation, and zero theme tinting
  - Direct Network Connection card with status badge, copy actions, and masked credential visibility
- **Compilation Status:** Clean build with Android SDK 36.

