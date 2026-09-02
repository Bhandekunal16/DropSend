package com.example.presentation.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.SessionState
import com.example.domain.model.SharingRole
import com.example.domain.model.TransportType
import com.example.ui.theme.DropAmber
import com.example.ui.theme.SleekGreen
import com.example.ui.theme.SleekOrange
import com.example.ui.theme.SleekPrimary
import com.example.ui.theme.SleekRed
import com.example.ui.theme.SleekSky

/**
 * Visual model for the color-coded connection chip.
 */
data class ConnectionVisualState(
    val title: String,
    val subtitle: String? = null,
    val containerColor: Color,
    val contentColor: Color,
    val dotColor: Color,
    val icon: ImageVector,
    val isPulsing: Boolean = false,
    val tagKey: String
)

/**
 * Resolves the visual state based on real-time session and radio telemetry.
 */
@Composable
fun resolveConnectionVisualState(
    sessionState: SessionState,
    targetDeviceName: String?,
    incomingSenderName: String?,
    role: SharingRole?,
    transportType: TransportType?,
    isWifiOn: Boolean,
    isBluetoothOn: Boolean,
    isHotspotActive: Boolean
): ConnectionVisualState {
    val activeDeviceName = targetDeviceName?.ifBlank { null }
        ?: incomingSenderName?.ifBlank { null }
        ?: "Nearby Device"

    return when (sessionState) {
        SessionState.TRANSFERRING, SessionState.VERIFYING -> {
            val transportLabel = transportType?.displayName ?: "Direct P2P"
            ConnectionVisualState(
                title = "Connected to $activeDeviceName",
                subtitle = transportLabel,
                containerColor = SleekGreen.copy(alpha = 0.16f),
                contentColor = MaterialTheme.colorScheme.primary,
                dotColor = SleekGreen,
                icon = Icons.Default.Link,
                isPulsing = true,
                tagKey = "connected"
            )
        }

        SessionState.CONNECTING, SessionState.AUTHENTICATING -> {
            ConnectionVisualState(
                title = "Connecting to $activeDeviceName...",
                subtitle = if (sessionState == SessionState.AUTHENTICATING) "Verifying key" else "Handshaking",
                containerColor = SleekSky.copy(alpha = 0.16f),
                contentColor = SleekSky,
                dotColor = SleekSky,
                icon = Icons.Default.Sync,
                isPulsing = true,
                tagKey = "connecting"
            )
        }

        SessionState.WAITING_FOR_ACCEPT -> {
            if (role == SharingRole.RECEIVER) {
                ConnectionVisualState(
                    title = "Request from $activeDeviceName",
                    subtitle = "Awaiting your approval",
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f),
                    contentColor = MaterialTheme.colorScheme.primary,
                    dotColor = MaterialTheme.colorScheme.primary,
                    icon = Icons.Default.PhoneAndroid,
                    isPulsing = true,
                    tagKey = "waiting_receive"
                )
            } else {
                ConnectionVisualState(
                    title = "Waiting for $activeDeviceName...",
                    subtitle = "Recipient reviewing request",
                    containerColor = DropAmber.copy(alpha = 0.18f),
                    contentColor = SleekOrange,
                    dotColor = DropAmber,
                    icon = Icons.Default.HourglassEmpty,
                    isPulsing = true,
                    tagKey = "waiting_send"
                )
            }
        }

        SessionState.DISCOVERING -> {
            ConnectionVisualState(
                title = "Searching...",
                subtitle = if (isWifiOn && isBluetoothOn) "Radar scanning" else "Wi-Fi / BT active",
                containerColor = DropAmber.copy(alpha = 0.16f),
                contentColor = SleekOrange,
                dotColor = DropAmber,
                icon = Icons.Default.Sensors,
                isPulsing = true,
                tagKey = "searching"
            )
        }

        SessionState.DEVICE_FOUND -> {
            ConnectionVisualState(
                title = "Device Found: $activeDeviceName",
                subtitle = "Ready to connect",
                containerColor = SleekPrimary.copy(alpha = 0.14f),
                contentColor = MaterialTheme.colorScheme.primary,
                dotColor = SleekPrimary,
                icon = Icons.Default.Devices,
                isPulsing = false,
                tagKey = "device_found"
            )
        }

        SessionState.COMPLETED -> {
            ConnectionVisualState(
                title = "Transfer Complete",
                subtitle = "Session finished",
                containerColor = SleekGreen.copy(alpha = 0.18f),
                contentColor = SleekGreen,
                dotColor = SleekGreen,
                icon = Icons.Default.DoneAll,
                isPulsing = false,
                tagKey = "completed"
            )
        }

        SessionState.FAILED, SessionState.CANCELLED, SessionState.EXPIRED -> {
            val failureTitle = if (sessionState == SessionState.CANCELLED) "Transfer Cancelled" else "Connection Failed"
            ConnectionVisualState(
                title = failureTitle,
                subtitle = "Session closed",
                containerColor = SleekRed.copy(alpha = 0.14f),
                contentColor = SleekRed,
                dotColor = SleekRed,
                icon = Icons.Default.ErrorOutline,
                isPulsing = false,
                tagKey = "failed"
            )
        }

        SessionState.DISCONNECTED, SessionState.IDLE -> {
            if (!isWifiOn && !isBluetoothOn) {
                ConnectionVisualState(
                    title = "Disconnected",
                    subtitle = "Radios offline",
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    dotColor = SleekRed,
                    icon = Icons.Default.WifiOff,
                    isPulsing = false,
                    tagKey = "disconnected_offline"
                )
            } else {
                ConnectionVisualState(
                    title = "Disconnected",
                    subtitle = if (isHotspotActive) "Hotspot active" else "Ready",
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    dotColor = MaterialTheme.colorScheme.outline,
                    icon = Icons.Default.Wifi,
                    isPulsing = false,
                    tagKey = "disconnected_ready"
                )
            }
        }
    }
}

/**
 * A persistent status bar component that displays real-time connection state
 * (e.g., 'Disconnected', 'Searching...', 'Connected to [Device Name]') using color-coded chips.
 */
@Composable
fun RealtimeConnectionStatusBar(
    sessionState: SessionState,
    targetDeviceName: String? = null,
    incomingSenderName: String? = null,
    role: SharingRole? = null,
    transportType: TransportType? = null,
    isWifiOn: Boolean = true,
    isBluetoothOn: Boolean = true,
    isHotspotActive: Boolean = false,
    showRadioPills: Boolean = true,
    modifier: Modifier = Modifier
) {
    val visualState = resolveConnectionVisualState(
        sessionState = sessionState,
        targetDeviceName = targetDeviceName,
        incomingSenderName = incomingSenderName,
        role = role,
        transportType = transportType,
        isWifiOn = isWifiOn,
        isBluetoothOn = isBluetoothOn,
        isHotspotActive = isHotspotActive
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .testTag("connection_status_bar"),
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Main Color-Coded Connection Chip with Animated Transition
                AnimatedContent(
                    targetState = visualState,
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                    label = "connection_chip_anim",
                    modifier = Modifier.weight(1f, fill = false)
                ) { targetVisual ->
                    ConnectionStateChip(
                        visualState = targetVisual,
                        modifier = Modifier.testTag("connection_state_chip")
                    )
                }

                if (showRadioPills) {
                    Spacer(modifier = Modifier.width(12.dp))
                    // Auxiliary Glanceable Radio Indicators
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isHotspotActive) {
                            RadioBadge(
                                icon = Icons.Default.WifiTethering,
                                label = "AP",
                                isActive = true,
                                activeColor = SleekSky
                            )
                        }

                        RadioBadge(
                            icon = if (isWifiOn) Icons.Default.Wifi else Icons.Default.WifiOff,
                            label = "Wi-Fi",
                            isActive = isWifiOn,
                            activeColor = SleekGreen
                        )

                        RadioBadge(
                            icon = if (isBluetoothOn) Icons.Default.Bluetooth else Icons.Default.BluetoothDisabled,
                            label = "BT",
                            isActive = isBluetoothOn,
                            activeColor = SleekSky
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            )
        }
    }
}

/**
 * The primary color-coded connection state chip with pulsating dot animation and generous spacing.
 */
@Composable
fun ConnectionStateChip(
    visualState: ConnectionVisualState,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_anim")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot_pulse"
    )

    val animatedBorderColor by animateColorAsState(
        targetValue = visualState.dotColor.copy(alpha = 0.35f),
        label = "border_color"
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(visualState.containerColor)
            .border(1.dp, animatedBorderColor, RoundedCornerShape(22.dp))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status Indicator Dot
        Box(
            modifier = Modifier
                .size(8.dp)
                .scale(if (visualState.isPulsing) pulseScale else 1f)
                .clip(CircleShape)
                .background(visualState.dotColor)
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Transport/State Icon
        Icon(
            imageVector = visualState.icon,
            contentDescription = null,
            tint = visualState.contentColor,
            modifier = Modifier.size(15.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Single-line Title and Subtitle with clean breathing room
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = visualState.title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = visualState.contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (!visualState.subtitle.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .size(3.dp)
                        .clip(CircleShape)
                        .background(visualState.contentColor.copy(alpha = 0.5f))
                )
                Text(
                    text = visualState.subtitle,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = visualState.contentColor.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Compact radio indicator pill (Wi-Fi, Bluetooth, Hotspot).
 */
@Composable
private fun RadioBadge(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    activeColor: Color,
    modifier: Modifier = Modifier
) {
    val bgColor = if (isActive) activeColor.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val contentColor = if (isActive) activeColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(13.dp)
        )
        Text(
            text = label,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = contentColor
        )
    }
}
