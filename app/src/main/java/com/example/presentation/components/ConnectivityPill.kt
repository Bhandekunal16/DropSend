package com.example.presentation.components

import android.content.Context
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.SleekGreen
import com.example.ui.theme.SleekRed

@Composable
fun ConnectivityStatusRow(
    isBluetoothOn: Boolean,
    isWifiOn: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ConnectivityPill(
            label = "BT",
            isOn = isBluetoothOn,
            activeIcon = Icons.Default.Bluetooth,
            inactiveIcon = Icons.Default.BluetoothDisabled,
            testTag = "bluetooth_status_pill",
            onClick = {
                try {
                    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {}
            }
        )

        ConnectivityPill(
            label = "Wi-Fi",
            isOn = isWifiOn,
            activeIcon = Icons.Default.Wifi,
            inactiveIcon = Icons.Default.WifiOff,
            testTag = "wifi_status_pill",
            onClick = {
                try {
                    val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {}
            }
        )
    }
}

@Composable
fun ConnectivityPill(
    label: String,
    isOn: Boolean,
    activeIcon: ImageVector,
    inactiveIcon: ImageVector,
    testTag: String = "connectivity_pill",
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val statusColor = if (isOn) SleekGreen else SleekRed
    val animatedBgColor by animateColorAsState(
        targetValue = if (isOn) statusColor.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        label = "pill_bg_color"
    )
    val animatedBorderColor by animateColorAsState(
        targetValue = if (isOn) statusColor.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
        label = "pill_border_color"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pulse_pill")
    val dotScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot_pulse"
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(animatedBgColor)
            .border(1.dp, animatedBorderColor, RoundedCornerShape(16.dp))
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else Modifier
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status indicator dot (pulses when active)
        Box(
            modifier = Modifier
                .size(7.dp)
                .scale(if (isOn) dotScale else 1f)
                .clip(CircleShape)
                .background(statusColor)
        )

        Spacer(modifier = Modifier.width(6.dp))

        // Radio Icon with smooth animated state transition
        AnimatedContent(
            targetState = isOn,
            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
            label = "icon_anim"
        ) { active ->
            Icon(
                imageVector = if (active) activeIcon else inactiveIcon,
                contentDescription = "$label status",
                tint = if (active) statusColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(13.dp)
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        Text(
            text = "$label: ${if (isOn) "ON" else "OFF"}",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.2).sp,
            color = if (isOn) statusColor else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

