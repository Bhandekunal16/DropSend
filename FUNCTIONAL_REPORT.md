# DropSend — Functional Application Report

**Application:** DropSend  
**Platform:** Android (Kotlin and Jetpack Compose)  
**Version:** 1.0 (`versionCode` 1)  
**Minimum Android version:** Android 7.0 / API 24  
**Report basis:** Current source code

## 1. Purpose

DropSend is an offline, nearby-device file-sharing application. It lets a sender select one or more files and send them directly to a nearby receiver over local Wi-Fi, Wi-Fi Direct, a local-only hotspot, or Bluetooth as a fallback. An internet connection is not required for the transfer itself.

## 2. Functional Requirements and Implementation

| ID | Function | User outcome | Implementation status |
| --- | --- | --- | --- |
| FR-01 | File selection | Select multiple documents from the Android picker. | Implemented |
| FR-02 | System share target | Share one or multiple files into DropSend from another Android app. | Implemented |
| FR-03 | Sender discovery | Find nearby receiving devices through LAN discovery, BLE, and Wi-Fi Direct. | Implemented |
| FR-04 | Receiver mode | Make the device ready to receive and advertise its availability. | Implemented |
| FR-05 | QR pairing | Connect using a scanned QR payload containing connection details. | Implemented |
| FR-06 | Direct IP connection | Connect manually to a host/IP address and port. | Implemented |
| FR-07 | Secure transfer | Transfer encrypted file chunks and show a user-verifiable session code. | Implemented |
| FR-08 | Integrity checking | Calculate SHA-256 checksums and validate received files before saving. | Implemented |
| FR-09 | Transfer controls | Pause, resume, or cancel an active transfer. | Implemented |
| FR-10 | Progress feedback | Show total progress, current file, speed, ETA, and transfer state. | Implemented |
| FR-11 | Background feedback | Keep an active-transfer notification with progress and cancel action. | Implemented |
| FR-12 | Receive storage | Save verified received files to `Downloads/DropSend`. | Implemented |
| FR-13 | Appearance preferences | Select an app palette and light/dark/system theme preference. | Implemented |
| FR-14 | Test simulation | Run sender/receiver simulations and populate demo peers for emulator testing. | Implemented |

## 3. Main User Flows

### Send files

1. The user selects files from the home screen, or shares them into DropSend from another app.
2. DropSend resolves each file's name, MIME type, and size, then opens the sender flow.
3. The user scans for nearby devices, scans a QR code, or enters an IP address.
4. After choosing a receiver, the app exchanges session details and displays a verification code.
5. The receiver accepts the request.
6. Files transfer sequentially in encrypted chunks. The UI and foreground notification display progress, speed, and ETA.
7. The sender receives verification acknowledgements and the session finishes with a completion screen.

### Receive files

1. The user chooses **Receive** from the home screen.
2. The app starts receiver advertising and a TCP/Bluetooth listener. It also requests a local-only hotspot where supported.
3. The receiver shares or displays connection information, including QR pairing data.
4. When a sender requests a session, the receiver can accept or decline it.
5. Received chunks are decrypted into a temporary file.
6. DropSend verifies the SHA-256 checksum and saves the file to `Downloads/DropSend` only after a successful check.

## 4. Functional Architecture

```text
Compose screens
    ↓ user actions / state
DropSendViewModel
    ├── StorageManager: file metadata, checksums, temporary files, Downloads output
    ├── DeviceDiscoveryManager: LAN + BLE + Wi-Fi Direct discovery
    ├── LocalHotspotManager / HotspotAutoConnector: offline hotspot pairing
    ├── TcpTransferTransport / BluetoothTransferTransport: connection and transfer
    ├── SessionCrypto: AES-GCM encryption and SHA-256 verification
    └── DropSendTransferService: foreground transfer notification
```

The UI is state-driven. `MainActivity` renders the home, sender, receiver, transfer, and success screens according to the `SessionState` and the user's sender/receiver role.

## 5. Connection and Transfer Behaviour

| Area | Behaviour |
| --- | --- |
| Preferred transports | Local Wi-Fi and Wi-Fi Direct are prioritised; Bluetooth is the fallback. |
| Default TCP port | `8888` |
| Discovery sources | LAN advertising/discovery, Bluetooth Low Energy, and Wi-Fi Direct peer discovery. |
| QR connection | Supports QR payload-based connection setup. |
| File transfer protocol | Uses typed protocol messages for hello, request/accept/reject, file start, chunks, acknowledgements, completion, pause/resume, cancellation, and close. |
| Chunk sizes | 128 KB for Wi-Fi and 32 KB for Bluetooth. |
| Progress | Tracks current file, total bytes, speed, ETA, transport type, pause state, and reconnecting state. |

## 6. Security and Data Handling

- A temporary `DROP-XXXX` device identity is generated for each session.
- A 256-bit AES session key is generated for the transfer session.
- File chunks are encrypted with AES-GCM before sending and decrypted on receipt.
- A human-readable verification code is derived from the session information for both peers to compare.
- The sender calculates a SHA-256 checksum; the receiver checks it before finalising a file.
- Incomplete received files live temporarily in the app cache and are cleared after transfer cleanup/cancellation.
- Completed files are written to `Downloads/DropSend` through `MediaStore` on Android 10+.

## 7. Permissions and Device Capabilities

| Capability | Reason |
| --- | --- |
| Camera | Scan QR pairing codes. |
| Wi-Fi, network state, and nearby Wi-Fi devices | Discover/connect to peers and operate local networking. |
| Bluetooth scan, advertise, and connect | Discover Bluetooth peers and use Bluetooth transfer fallback. |
| Location on legacy Android versions | Required for older Bluetooth scanning behaviour. |
| Notifications and foreground service | Display active transfer progress while the app is backgrounded. |
| Vibration | Provide haptic feedback for discovery/interaction events. |

The manifest declares Wi-Fi Direct, Bluetooth LE, and camera as optional hardware features, so devices without those capabilities can still install the app and use supported alternatives.

## 8. Screens and User Feedback

| Screen/component | Function |
| --- | --- |
| Home | Start send/receive, select theme, review connectivity, open app information, and launch test tools. |
| Send flow | Review selected files, discover devices, select a peer, use QR/manual-IP pairing, or rescan. |
| Receive flow | Show receiver identity/connection information and accept or decline incoming requests. |
| Transfer | Display per-transfer status and provide pause/resume/cancel actions. |
| Success | Confirm completed file count and transferred size, with options to finish or send more. |
| Status bar | Shows connection/session state and Wi-Fi, Bluetooth, hotspot, and transport indicators. |

## 9. Build and Run

1. Open the project in Android Studio.
2. Add `GEMINI_API_KEY` to a local `.env` file if required by the configured Firebase AI dependency.
3. Build a debug APK with `./gradlew assembleDebug`.
4. Install `app/build/outputs/apk/debug/app-debug.apk` on Android devices.
5. For a real nearby transfer, use two compatible Android devices and grant the requested permissions on both.

## 10. Validation Scope

This report documents functionality implemented in source. Practical verification should cover transfers on two physical devices across local Wi-Fi, QR/local-hotspot pairing, and Bluetooth fallback, including cancellation, checksum failure, and background notification behaviour.

