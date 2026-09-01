package com.example.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ConnectivityPill(
            label = "Bluetooth",
            isOn = isBluetoothOn,
            activeIcon = Icons.Default.Bluetooth,
            inactiveIcon = Icons.Default.BluetoothDisabled
        )

        ConnectivityPill(
            label = "Wi-Fi",
            isOn = isWifiOn,
            activeIcon = Icons.Default.Wifi,
            inactiveIcon = Icons.Default.WifiOff
        )
    }
}

@Composable
fun ConnectivityPill(
    label: String,
    isOn: Boolean,
    activeIcon: androidx.compose.ui.graphics.vector.ImageVector,
    inactiveIcon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    val statusDotColor = if (isOn) SleekGreen else SleekRed
    val bgColor = MaterialTheme.colorScheme.surfaceVariant

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(bgColor)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(statusDotColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "$label: ${if (isOn) "ON" else "OFF"}",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.2).sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
