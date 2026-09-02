package com.example.domain.model

/**
 * Centralized operational and protocol configuration constants for DropSend.
 */
object DropSendConfig {
    // Protocol & Networking Ports
    const val DEFAULT_TCP_PORT = 8988
    const val DEFAULT_UDP_PORT = 8989
    const val BLE_SERVICE_UUID_STRING = "0000fd00-0000-1000-8000-00805f9b34fb"

    // Timeouts & Expirations (milliseconds)
    const val PEER_EXPIRY_MS = 15_000L
    const val DISCOVERY_CLEANUP_INTERVAL_MS = 4_000L
    const val SOCKET_CONNECT_TIMEOUT_MS = 10_000
    const val HANDSHAKE_TIMEOUT_MS = 15_000L
    const val TRANSFER_HEARTBEAT_INTERVAL_MS = 1_000L
    const val SPEED_CALCULATION_INTERVAL_MS = 500L

    // Buffer & Chunk Sizing (bytes)
    const val LAN_CHUNK_SIZE = 128 * 1024 // 128 KB
    const val BLUETOOTH_CHUNK_SIZE = 32 * 1024 // 32 KB
    const val MAX_CHUNK_SIZE = 1024 * 1024 // 1 MB payload bound
    const val MAX_METADATA_SIZE = 64 * 1024 // 64 KB
    const val MAX_MESSAGE_SIZE = 16 * 1024 * 1024 // 16 MB limit

    // Storage & Folders
    const val DROPSEND_FOLDER_NAME = "DropSend"
    const val TEMP_FILE_EXTENSION = ".part"

    // Retry Policies
    const val MAX_RETRY_ATTEMPTS = 3
    const val RETRY_BACKOFF_BASE_MS = 1_000L
}
