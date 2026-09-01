package com.example.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.FileTransferStatus
import com.example.domain.model.TransferFile
import com.example.ui.theme.SleekGreen
import com.example.ui.theme.SleekRed

@Composable
fun FileItemCard(
    file: TransferFile,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
    onOpenFile: ((TransferFile) -> Unit)? = null,
    showStatus: Boolean = false
) {
    val (icon, iconTint) = getFileIconAndTint(file.name, file.mimeType)
    val isClickable = onOpenFile != null && (file.status == FileTransferStatus.COMPLETED || file.uri != null)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .then(if (isClickable) Modifier.clickable { onOpenFile?.invoke(file) } else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(iconTint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (isClickable) "${file.formattedSize} • Tap to open" else file.formattedSize,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (showStatus) {
            when (file.status) {
                FileTransferStatus.COMPLETED -> {
                    if (onOpenFile != null) {
                        IconButton(
                            onClick = { onOpenFile(file) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = "Open file",
                                tint = SleekGreen,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Completed",
                            tint = SleekGreen,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                FileTransferStatus.TRANSFERRING -> {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = "Transferring",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                FileTransferStatus.FAILED -> {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = "Failed",
                        tint = SleekRed,
                        modifier = Modifier.size(20.dp)
                    )
                }
                FileTransferStatus.PENDING -> {
                    Text(
                        text = "○",
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        } else if (onRemove != null) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove file",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

fun getFileIconAndTint(fileName: String, mimeType: String): Pair<ImageVector, Color> {
    val lower = fileName.lowercase()
    return when {
        mimeType.startsWith("image/") || lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".gif") -> {
            Icons.Default.Image to Color(0xFF38BDF8)
        }
        mimeType.startsWith("video/") || lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".mov") || lower.endsWith(".avi") -> {
            Icons.Default.Movie to Color(0xFFA855F7)
        }
        mimeType.startsWith("audio/") || lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".flac") || lower.endsWith(".m4a") -> {
            Icons.Default.Audiotrack to Color(0xFFEC4899)
        }
        lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".tar") || lower.endsWith(".gz") || lower.endsWith(".7z") -> {
            Icons.Default.FolderZip to Color(0xFFF59E0B)
        }
        lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx") || lower.endsWith(".txt") || lower.endsWith(".xlsx") || lower.endsWith(".pptx") -> {
            Icons.Default.Description to Color(0xFF10B981)
        }
        else -> {
            Icons.Default.InsertDriveFile to Color(0xFF94A3B8)
        }
    }
}
