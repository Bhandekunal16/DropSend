package com.example.presentation.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * High-contrast, deterministic QR code display component.
 *
 * Implements a strict high-contrast color scheme (opaque pure black foreground on opaque pure white
 * background with dedicated quiet zone padding) regardless of light mode, dark mode, or dynamic Material 3 theme.
 */
@Composable
fun QrCodeDisplay(
    content: String,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 220.dp,
    foregroundColor: Color = Color.Black,
    backgroundColor: Color = Color.White
) {
    // Generate QR bitmap with high-contrast opaque pixels and error correction
    val qrBitmap = remember(content, foregroundColor, backgroundColor) {
        if (content.isBlank()) null else generateQrBitmap(
            content = content,
            dimension = 512,
            foregroundColor = foregroundColor.toArgb(),
            backgroundColor = backgroundColor.toArgb(),
            margin = 2
        )
    }

    // Outer high-contrast container with card framing and subtle theme border
    Surface(
        modifier = modifier
            .size(sizeDp)
            .shadow(elevation = 6.dp, shape = RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .border(
                width = 2.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                shape = RoundedCornerShape(20.dp)
            )
            .semantics {
                contentDescription = "Connection QR code for direct peer transfer"
            }
            .testTag("qr_code_display_container"),
        shape = RoundedCornerShape(20.dp),
        color = Color.White // Strict opaque white base container for scanner reliability
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .padding(16.dp), // Quiet zone margin preventing corner clipping
            contentAlignment = Alignment.Center
        ) {
            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "DropSend direct connection QR code",
                    colorFilter = null, // Ensure zero theme tinting or color inversion
                    modifier = Modifier
                        .fillMaxSize()
                        .aspectRatio(1f)
                        .testTag("qr_code_image")
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("qr_code_loading_indicator"),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp
                )
            }
        }
    }
}

/**
 * Pure deterministic QR Bitmap generator.
 * Encodes QR with UTF-8, configurable margin (quiet zone), error correction level M, and opaque ARGB_8888 pixels.
 */
fun generateQrBitmap(
    content: String,
    dimension: Int = 512,
    foregroundColor: Int = 0xFF000000.toInt(),
    backgroundColor: Int = 0xFFFFFFFF.toInt(),
    margin: Int = 2
): Bitmap? {
    if (content.isBlank() || dimension <= 0) return null
    return try {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to margin,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
        )
        val bitMatrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            dimension,
            dimension,
            hints
        )
        val width = bitMatrix.width
        val height = bitMatrix.height
        val pixels = IntArray(width * height)

        // Ensure full alpha opacity for both colors
        val opaqueFg = foregroundColor or (0xFF shl 24)
        val opaqueBg = backgroundColor or (0xFF shl 24)

        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (bitMatrix.get(x, y)) opaqueFg else opaqueBg
            }
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        bitmap
    } catch (_: Exception) {
        null
    }
}
