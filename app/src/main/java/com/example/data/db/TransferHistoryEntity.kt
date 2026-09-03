
package com.example.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transfer_history",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["status", "timestamp"]),
        Index(value = ["sessionId"]),
    ],
)
data class TransferHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val sessionId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val isSending: Boolean,
    val peerName: String,
    val peerDeviceId: String,
    val transportType: String,
    val status: String,
    val durationSeconds: Long,
    val averageSpeedBps: Long,
    val fileUriString: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val errorMessage: String? = null,
)
