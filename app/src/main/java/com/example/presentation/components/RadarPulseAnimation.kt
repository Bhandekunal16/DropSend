package com.example.presentation.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.ui.theme.DropCyan

@Composable
fun RadarPulseAnimation(
    modifier: Modifier = Modifier,
    centerIconColor: Color = DropCyan
) {
    val infiniteTransition = rememberInfiniteTransition(label = "radar_transition")

    val pulse1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse1"
    )

    val pulse2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, delayMillis = 750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse2"
    )

    val pulse3 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, delayMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse3"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val maxRadius = size.minDimension / 2f
            
            // Outer ring 1
            if (pulse1 > 0f) {
                drawCircle(
                    color = centerIconColor.copy(alpha = (1f - pulse1) * 0.45f),
                    radius = maxRadius * pulse1,
                    style = Stroke(width = 2.5.dp.toPx())
                )
            }

            // Outer ring 2
            if (pulse2 > 0f) {
                drawCircle(
                    color = centerIconColor.copy(alpha = (1f - pulse2) * 0.45f),
                    radius = maxRadius * pulse2,
                    style = Stroke(width = 2.dp.toPx())
                )
            }

            // Outer ring 3
            if (pulse3 > 0f) {
                drawCircle(
                    color = centerIconColor.copy(alpha = (1f - pulse3) * 0.45f),
                    radius = maxRadius * pulse3,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }

        // Center glowing node
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(centerIconColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(centerIconColor.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Sensors,
                    contentDescription = "Radar beacon",
                    tint = centerIconColor,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}
