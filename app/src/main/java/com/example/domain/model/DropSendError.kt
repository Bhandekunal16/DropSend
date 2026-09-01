package com.example.domain.model

sealed class DropSendError(
    val code: String,
    val userMessage: String,
    val isRecoverable: Boolean = false,
    override val cause: Throwable? = null
) : Exception(userMessage, cause) {

    class StorageFull(requiredBytes: Long, availableBytes: Long) : DropSendError(
        code = "ERR_STORAGE_FULL",
        userMessage = "Not enough free storage space to receive files (${formatFileSize(requiredBytes)} needed, ${formatFileSize(availableBytes)} available)."
    )

    class ChecksumMismatch(val fileName: String) : DropSendError(
        code = "ERR_CHECKSUM_MISMATCH",
        userMessage = "File verification failed for $fileName. Data may be corrupted or tampered."
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

    class AuthenticationFailed : DropSendError(
        code = "ERR_AUTH_FAILED",
        userMessage = "Cryptographic authentication or key exchange with peer failed."
    )

    class SessionRejected(val reason: String) : DropSendError(
        code = "ERR_SESSION_REJECTED",
        userMessage = "Transfer request was declined: $reason"
    )

    class TransferCancelled : DropSendError(
        code = "ERR_TRANSFER_CANCELLED",
        userMessage = "Transfer was cancelled."
    )

    class Unknown(message: String, cause: Throwable? = null) : DropSendError(
        code = "ERR_UNKNOWN",
        userMessage = message,
        cause = cause
    )
}
