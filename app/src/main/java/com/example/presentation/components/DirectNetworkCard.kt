package com.example.presentation.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Direct Network Connection Status enumeration.
 */
enum class DirectNetworkStatus(
    val label: String,
    val icon: ImageVector
) {
    AVAILABLE("Ready for connection", Icons.Default.WifiTethering),
    STARTING("Preparing network…", Icons.Default.Sync),
    UNAVAILABLE("Network unavailable", Icons.Default.WifiOff),
    ERROR("Unable to start network", Icons.Default.ErrorOutline)
}

/**
 * Polished Material 3 Direct Network Information Card.
 *
 * Provides a clear visual hierarchy for connecting a secondary peer device directly to DropSend:
 * 1. Network status header with real-time availability indicator.
 * 2. Network SSID with one-tap copy.
 * 3. IP address (formatted in monospace font) with copy action.
 * 4. Wi-Fi passphrase masked by default with show/hide toggle and copy.
 * 5. Explanatory context footer.
 */
@Composable
fun DirectNetworkCard(
    ssid: String,
    ipAddress: String,
    passphrase: String,
    modifier: Modifier = Modifier,
    status: DirectNetworkStatus = if (ssid.isNotBlank()) DirectNetworkStatus.AVAILABLE else DirectNetworkStatus.STARTING,
    errorMessage: String? = null,
    onCopyFeedback: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    var isPasswordVisible by remember { mutableStateOf(false) }

    fun copyToClipboard(label: String, value: String, successMessage: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText(label, value)
        clipboard?.setPrimaryClip(clip)

        if (onCopyFeedback != null) {
            onCopyFeedback(successMessage)
        } else {
            Toast.makeText(context, successMessage, Toast.LENGTH_SHORT).show()
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(250))
            .testTag("direct_network_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Header: Connection Type & Status Subtitle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = status.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Column {
                        Text(
                            text = "Direct Network",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.15.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = errorMessage ?: status.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status == DirectNetworkStatus.ERROR) {
                                MaterialTheme.colorScheme.error
                            } else if (status == DirectNetworkStatus.AVAILABLE) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                // Status Badge Pill
                val (badgeBg, badgeFg) = when (status) {
                    DirectNetworkStatus.AVAILABLE -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary
                    DirectNetworkStatus.STARTING -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.secondary
                    DirectNetworkStatus.UNAVAILABLE -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
                    DirectNetworkStatus.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.error
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = badgeBg,
                    modifier = Modifier.semantics {
                        contentDescription = "Direct network status: ${status.label}"
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(badgeFg)
                        )
                        Text(
                            text = when (status) {
                                DirectNetworkStatus.AVAILABLE -> "Active"
                                DirectNetworkStatus.STARTING -> "Starting"
                                DirectNetworkStatus.UNAVAILABLE -> "Off"
                                DirectNetworkStatus.ERROR -> "Error"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = badgeFg
                        )
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                thickness = 1.dp
            )

            // 2. Network SSID Field
            DirectNetworkInfoRow(
                label = "NETWORK NAME",
                value = ssid.ifBlank { "—" },
                testTagPrefix = "ssid",
                onCopy = if (ssid.isNotBlank()) {
                    { copyToClipboard("DropSend Network", ssid, "Network name copied") }
                } else null,
                copyContentDescription = "Copy network name $ssid"
            )

            // 3. IP Address Field
            DirectNetworkInfoRow(
                label = "IP ADDRESS",
                value = ipAddress.ifBlank { "—" },
                isMonospace = true,
                testTagPrefix = "ip",
                onCopy = if (ipAddress.isNotBlank()) {
                    { copyToClipboard("DropSend IP", ipAddress, "IP address copied") }
                } else null,
                copyContentDescription = "Copy IP address $ipAddress"
            )

            // 4. Wi-Fi Password Field (Masked by default with Show/Hide toggle)
            if (passphrase.isNotBlank()) {
                val displayPassword = if (isPasswordVisible) passphrase else "••••••••••••••"

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.6f))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "WI-FI PASSWORD",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = displayPassword,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                                letterSpacing = if (isPasswordVisible) 0.5.sp else 2.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("direct_network_password_text"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            // Show/Hide Toggle Button (Accessible >= 48dp touch target)
                            IconButton(
                                onClick = { isPasswordVisible = !isPasswordVisible },
                                modifier = Modifier
                                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                    .testTag("toggle_password_visibility_button"),
                                colors = IconButtonDefaults.iconButtonColors(
                                    contentColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(
                                    imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (isPasswordVisible) "Hide Wi-Fi password" else "Show Wi-Fi password",
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Copy Password Button
                            IconButton(
                                onClick = { copyToClipboard("DropSend Password", passphrase, "Wi-Fi password copied") },
                                modifier = Modifier
                                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                    .testTag("copy_password_button"),
                                colors = IconButtonDefaults.iconButtonColors(
                                    contentColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy Wi-Fi password",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 5. Explanatory Instructional Footer
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Devices,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Connect another device to this network to transfer files directly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Reusable row item for network name and IP address with copy button.
 */
@Composable
private fun DirectNetworkInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    isMonospace: Boolean = false,
    testTagPrefix: String = "info",
    onCopy: (() -> Unit)? = null,
    copyContentDescription: String = "Copy"
) {
    var isJustCopied by remember { mutableStateOf(false) }

    LaunchedEffect(isJustCopied) {
        if (isJustCopied) {
            delay(1500)
            isJustCopied = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(2.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .testTag("${testTagPrefix}_value_text"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (onCopy != null) {
                IconButton(
                    onClick = {
                        onCopy()
                        isJustCopied = true
                    },
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("copy_${testTagPrefix}_button"),
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = if (isJustCopied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary
                    )
                ) {
                    if (isJustCopied) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Copied",
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = copyContentDescription,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
