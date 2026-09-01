package com.example.domain.model

import android.net.Uri

enum class SharingRole {
    SENDER,
    RECEIVER
}

enum class SessionState {
    IDLE,
    DISCOVERING,
    DEVICE_FOUND,
    CONNECTING,
    AUTHENTICATING,
    WAITING_FOR_ACCEPT,
    TRANSFERRING,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED,
    EXPIRED,
    DISCONNECTED
}

enum class TransportType(val displayName: String, val isFast: Boolean) {
    WIFI_DIRECT("Wi-Fi Direct", true),
    LOCAL_WIFI("Local Wi-Fi", true),
    BLUETOOTH("Bluetooth Fallback", false)
}

enum class FileTransferStatus {
    PENDING,
    TRANSFERRING,
    COMPLETED,
    FAILED
}

data class DiscoveredDevice(
    val id: String, // e.g. "DROP-7A92"
    val name: String, // e.g. "Pixel 9" or "Rahul's Phone"
    val transportType: TransportType,
    val ipAddress: String? = null,
    val port: Int = 8888,
    val bluetoothAddress: String? = null,
    val rssi: Int = 0,
    val isReadyToReceive: Boolean = true,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
)

data class TransferFile(
    val id: String,
    val uri: Uri? = null,
    val name: String,
    val mimeType: String = "*/*",
    val sizeBytes: Long,
    val checksumSha256: String = "",
    val bytesTransferred: Long = 0L,
    val status: FileTransferStatus = FileTransferStatus.PENDING
) {
    val formattedSize: String
        get() = formatFileSize(sizeBytes)

    val progressFraction: Float
        get() = if (sizeBytes > 0) (bytesTransferred.toFloat() / sizeBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

data class TransferProgress(
    val currentFileIndex: Int = 0,
    val totalFiles: Int = 0,
    val currentFileName: String = "",
    val currentFileBytes: Long = 0L,
    val currentFileSize: Long = 0L,
    val totalBytesTransferred: Long = 0L,
    val totalSizeBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long = 0L,
    val verificationCode: String = "",
    val transportType: TransportType = TransportType.LOCAL_WIFI,
    val isPaused: Boolean = false,
    val isReconnecting: Boolean = false
) {
    val overallProgressFraction: Float
        get() = if (totalSizeBytes > 0) (totalBytesTransferred.toFloat() / totalSizeBytes.toFloat()).coerceIn(0f, 1f) else 0f

    val overallPercentage: Int
        get() = (overallProgressFraction * 100).toInt()

    val formattedSpeed: String
        get() = if (speedBytesPerSec > 1024 * 1024) {
            String.format("%.1f MB/s", speedBytesPerSec / (1024.0 * 1024.0))
        } else if (speedBytesPerSec > 1024) {
            String.format("%.0f KB/s", speedBytesPerSec / 1024.0)
        } else {
            "$speedBytesPerSec B/s"
        }

    val formattedEta: String
        get() = when {
            etaSeconds <= 0 -> "--"
            etaSeconds < 60 -> "${etaSeconds}s"
            etaSeconds < 3600 -> "${etaSeconds / 60}m ${etaSeconds % 60}s"
            else -> "${etaSeconds / 3600}h ${(etaSeconds % 3600) / 60}m"
        }

    val formattedTransferredVsTotal: String
        get() = "${formatFileSize(totalBytesTransferred)} / ${formatFileSize(totalSizeBytes)}"
}

fun formatFileSize(bytes: Long): String {
    if (bytes < 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format("%.2f GB", gb)
        mb >= 1.0 -> String.format("%.1f MB", mb)
        kb >= 1.0 -> String.format("%.1f KB", kb)
        else -> "$bytes B"
    }
}
