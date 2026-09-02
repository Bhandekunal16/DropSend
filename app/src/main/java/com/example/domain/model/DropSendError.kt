package com.example.domain.model

sealed class DropSendError(
    val code: String,
    val userMessage: String,
    val title: String = "Transfer Alert",
    val safetyNotice: String = "Your transfer state and files remain secure.",
    val actionGuidance: String = "Please try the transfer again.",
    val isRecoverable: Boolean = false,
    override val cause: Throwable? = null
) : Exception(userMessage, cause) {

    class DiscoveryFailed(reason: String = "Unable to start local discovery") : DropSendError(
        code = "ERR_DISCOVERY_FAILED",
        title = "Discovery Failed",
        userMessage = "Device discovery failed: $reason",
        safetyNotice = "No connection was established.",
        actionGuidance = "Ensure Wi-Fi or Bluetooth is turned on, or use QR scan to connect directly.",
        isRecoverable = true
    )

    class PermissionDenied(val permissionName: String) : DropSendError(
        code = "ERR_PERMISSION_DENIED",
        title = "Permission Required",
        userMessage = "Required permission was denied: $permissionName.",
        safetyNotice = "No device data was accessed.",
        actionGuidance = "Grant Nearby Devices or Location permission in Android Settings to discover peers.",
        isRecoverable = true
    )

    class ConnectionFailed(val peerName: String, cause: Throwable? = null) : DropSendError(
        code = "ERR_CONNECTION_FAILED",
        title = "Connection Failed",
        userMessage = "Could not establish connection to $peerName.",
        safetyNotice = "No unauthenticated data was exchanged.",
        actionGuidance = "Ensure both devices are on the same Wi-Fi network, or scan the receiver's QR code.",
        isRecoverable = true,
        cause = cause
    )

    class AuthenticationFailed(detail: String = "Cryptographic authentication with peer failed") : DropSendError(
        code = "ERR_AUTH_FAILED",
        title = "Authentication Failed",
        userMessage = "Security verification failed between devices.",
        safetyNotice = "Connection was immediately terminated. No files were transmitted or stored.",
        actionGuidance = "Compare the verification codes on both screens and re-initiate the connection.",
        isRecoverable = true
    )

    class SessionRejected(val reason: String = "Declined by user") : DropSendError(
        code = "ERR_SESSION_REJECTED",
        title = "Transfer Declined",
        userMessage = "Transfer request was declined: $reason",
        safetyNotice = "No files were transferred.",
        actionGuidance = "You can select files and send a new transfer request when ready.",
        isRecoverable = false
    )

    class Timeout(val operation: String = "Operation") : DropSendError(
        code = "ERR_TIMEOUT",
        title = "Transfer Timed Out",
        userMessage = "$operation timed out waiting for peer response.",
        safetyNotice = "Any incomplete files were safely discarded.",
        actionGuidance = "Check that the peer device screen is active and retry.",
        isRecoverable = true
    )

    class NetworkInterrupted(cause: Throwable? = null) : DropSendError(
        code = "ERR_NETWORK_INTERRUPTED",
        title = "Network Interrupted",
        userMessage = "The connection to peer was interrupted.",
        safetyNotice = "Partial transfer progress is saved. Incomplete chunks are quarantined.",
        actionGuidance = "Check your Wi-Fi or Hotspot connection and tap Resume to continue.",
        isRecoverable = true,
        cause = cause
    )

    class ConnectionLost(cause: Throwable? = null) : DropSendError(
        code = "ERR_CONNECTION_LOST",
        title = "Connection Lost",
        userMessage = "The connection to peer was lost.",
        safetyNotice = "Partial files are safely staged as .part files.",
        actionGuidance = "Reconnect to the same network and resume transfer.",
        isRecoverable = true,
        cause = cause
    )

    class ReconnectFailed : DropSendError(
        code = "ERR_RECONNECT_FAILED",
        title = "Reconnect Failed",
        userMessage = "Failed to restore connection to peer after multiple attempts.",
        safetyNotice = "Staged temporary data was preserved for resume.",
        actionGuidance = "Verify network connectivity on both devices and start a new transfer.",
        isRecoverable = true
    )

    class ProtocolError(val detail: String) : DropSendError(
        code = "ERR_PROTOCOL_ERROR",
        title = "Protocol Error",
        userMessage = "Protocol error encountered: $detail",
        safetyNotice = "Session was terminated to protect data integrity.",
        actionGuidance = "Restart the app on both devices and try again.",
        isRecoverable = false
    )

    class UnsupportedProtocol(val version: Int) : DropSendError(
        code = "ERR_UNSUPPORTED_PROTOCOL",
        title = "Version Incompatible",
        userMessage = "Incompatible DropSend protocol version ($version).",
        safetyNotice = "No transfer was initiated.",
        actionGuidance = "Update DropSend to the latest version on both devices.",
        isRecoverable = false
    )

    class FileRangeError(val fileName: String, val offset: Long, val expectedOffset: Long) : DropSendError(
        code = "ERR_FILE_RANGE",
        title = "Transfer Offset Error",
        userMessage = "File chunk offset mismatch for $fileName (offset $offset vs expected $expectedOffset).",
        safetyNotice = "Invalid chunk was discarded.",
        actionGuidance = "Retry the transfer to synchronize offsets.",
        isRecoverable = true
    )

    class ChecksumMismatch(val fileName: String) : DropSendError(
        code = "ERR_CHECKSUM_MISMATCH",
        title = "File Verification Failed",
        userMessage = "Integrity check failed for $fileName. Data may be corrupted.",
        safetyNotice = "The corrupted file was safely deleted and never committed to storage.",
        actionGuidance = "Re-send the file to ensure complete, uncorrupted delivery.",
        isRecoverable = true
    )

    class StorageFull(requiredBytes: Long, availableBytes: Long) : DropSendError(
        code = "ERR_STORAGE_FULL",
        title = "Not Enough Storage",
        userMessage = "Not enough free storage space to receive files (${formatFileSize(requiredBytes)} needed, ${formatFileSize(availableBytes)} available).",
        safetyNotice = "No partial files were committed to device storage.",
        actionGuidance = "Free up device storage space and try again.",
        isRecoverable = true
    )

    class StorageWriteFailed(val fileName: String, cause: Throwable? = null) : DropSendError(
        code = "ERR_STORAGE_WRITE_FAILED",
        title = "Storage Error",
        userMessage = "Failed writing file data to storage for $fileName.",
        safetyNotice = "Incomplete data was cleaned up.",
        actionGuidance = "Check storage permissions and available space, then retry.",
        isRecoverable = true,
        cause = cause
    )

    class TransferCancelled(val isPeerInitiated: Boolean = false) : DropSendError(
        code = "ERR_TRANSFER_CANCELLED",
        title = "Transfer Cancelled",
        userMessage = if (isPeerInitiated) "Transfer was cancelled by the other device." else "Transfer was cancelled.",
        safetyNotice = "All temporary files were safely cleaned up.",
        actionGuidance = "You can initiate a new transfer at any time.",
        isRecoverable = false
    )

    class SessionExpired : DropSendError(
        code = "ERR_SESSION_EXPIRED",
        title = "Session Expired",
        userMessage = "Transfer session expired or timed out.",
        safetyNotice = "Session encryption keys were purged.",
        actionGuidance = "Start a fresh transfer session.",
        isRecoverable = true
    )

    class Unknown(message: String, cause: Throwable? = null) : DropSendError(
        code = "ERR_UNKNOWN",
        title = "Transfer Error",
        userMessage = message,
        safetyNotice = "Session resources were cleaned up.",
        actionGuidance = "Please try again.",
        isRecoverable = true,
        cause = cause
    )
}
