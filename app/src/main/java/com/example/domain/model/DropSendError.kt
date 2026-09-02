package com.example.domain.model

sealed class DropSendError(
    val code: String,
    val userMessage: String,
    val isRecoverable: Boolean = false,
    override val cause: Throwable? = null
) : Exception(userMessage, cause) {

    class DiscoveryFailed(reason: String = "Unable to start local discovery") : DropSendError(
        code = "ERR_DISCOVERY_FAILED",
        userMessage = "Device discovery failed: $reason"
    )

    class PermissionDenied(val permissionName: String) : DropSendError(
        code = "ERR_PERMISSION_DENIED",
        userMessage = "Required permission was denied: $permissionName. Please grant permissions in Settings."
    )

    class ConnectionFailed(val peerName: String, cause: Throwable? = null) : DropSendError(
        code = "ERR_CONNECTION_FAILED",
        userMessage = "Could not establish connection to $peerName. Please ensure both devices are nearby and on the same network.",
        cause = cause
    )

    class AuthenticationFailed(detail: String = "Cryptographic authentication with peer failed") : DropSendError(
        code = "ERR_AUTH_FAILED",
        userMessage = "Cryptographic authentication with peer failed. The connection was terminated for security."
    )

    class SessionRejected(val reason: String = "Declined by user") : DropSendError(
        code = "ERR_SESSION_REJECTED",
        userMessage = "Transfer request was declined: $reason"
    )

    class Timeout(val operation: String = "Operation") : DropSendError(
        code = "ERR_TIMEOUT",
        userMessage = "$operation timed out waiting for response from peer."
    )

    class NetworkInterrupted(cause: Throwable? = null) : DropSendError(
        code = "ERR_NETWORK_INTERRUPTED",
        userMessage = "Connection to peer was interrupted.",
        isRecoverable = true,
        cause = cause
    )

    class ConnectionLost(cause: Throwable? = null) : DropSendError(
        code = "ERR_CONNECTION_LOST",
        userMessage = "Connection to peer was interrupted.",
        isRecoverable = true,
        cause = cause
    )

    class ReconnectFailed : DropSendError(
        code = "ERR_RECONNECT_FAILED",
        userMessage = "Failed to restore connection to peer after multiple attempts."
    )

    class ProtocolError(val detail: String) : DropSendError(
        code = "ERR_PROTOCOL_ERROR",
        userMessage = "Protocol error encountered: $detail"
    )

    class UnsupportedProtocol(val version: Int) : DropSendError(
        code = "ERR_UNSUPPORTED_PROTOCOL",
        userMessage = "Incompatible DropSend protocol version ($version). Please update the app on both devices."
    )

    class FileRangeError(val fileName: String, val offset: Long, val expectedOffset: Long) : DropSendError(
        code = "ERR_FILE_RANGE",
        userMessage = "File chunk offset mismatch for $fileName (offset $offset vs expected $expectedOffset)."
    )

    class ChecksumMismatch(val fileName: String) : DropSendError(
        code = "ERR_CHECKSUM_MISMATCH",
        userMessage = "File verification failed for $fileName. Data may be corrupted or tampered."
    )

    class StorageFull(requiredBytes: Long, availableBytes: Long) : DropSendError(
        code = "ERR_STORAGE_FULL",
        userMessage = "Not enough free storage space to receive files (${formatFileSize(requiredBytes)} needed, ${formatFileSize(availableBytes)} available)."
    )

    class StorageWriteFailed(val fileName: String, cause: Throwable? = null) : DropSendError(
        code = "ERR_STORAGE_WRITE_FAILED",
        userMessage = "Failed writing file data to storage for $fileName.",
        cause = cause
    )

    class TransferCancelled(val isPeerInitiated: Boolean = false) : DropSendError(
        code = "ERR_TRANSFER_CANCELLED",
        userMessage = if (isPeerInitiated) "Transfer was cancelled by peer." else "Transfer was cancelled."
    )

    class SessionExpired : DropSendError(
        code = "ERR_SESSION_EXPIRED",
        userMessage = "Transfer session expired or timed out."
    )

    class Unknown(message: String, cause: Throwable? = null) : DropSendError(
        code = "ERR_UNKNOWN",
        userMessage = message,
        cause = cause
    )
}
