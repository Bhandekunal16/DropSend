package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.storage.StorageManager
import com.example.data.storage.StorageValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("DropSend", appName)
  }

  @Test
  fun `test storage manager temp file operations and cleanup`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val storageManager = StorageManager(context)

    val tempFile = storageManager.createTempFileForReceiving("f-12345", "test_photo.jpg")
    assertTrue(tempFile.exists())
    assertEquals(0L, tempFile.length())

    // Write simulated chunks
    val chunk1 = byteArrayOf(10, 20, 30, 40)
    val chunk2 = byteArrayOf(50, 60, 70, 80)
    storageManager.writeChunkToTempFile(tempFile, 0L, chunk1)
    storageManager.writeChunkToTempFile(tempFile, 4L, chunk2)

    assertEquals(8L, tempFile.length())

    val partOffset = storageManager.getExistingPartOffset("f-12345", "test_photo.jpg")
    assertEquals(8L, partOffset)

    // Clear temp files
    storageManager.clearTempFiles()
    assertFalse(tempFile.exists())
  }

  @Test
  fun `test storage manager storage space validation`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val storageManager = StorageManager(context)

    val result = storageManager.validateStorageAvailable(1024 * 1024L) // 1 MB
    assertTrue(result is StorageValidationResult)
    when (result) {
      is StorageValidationResult.Sufficient -> {
        assertEquals(1024 * 1024L, result.requiredBytes)
      }
      is StorageValidationResult.Insufficient -> {
        assertEquals(1024 * 1024L, result.requiredBytes)
        assertTrue(result.message.isNotEmpty())
      }
    }
  }

  @Test
  fun `test qr code bitmap generation with high contrast and valid dimensions`() {
    val payload = "dropsend://connect?ssid=DropSend-A1B2&pass=dp_A1B2&ip=192.168.43.1&port=8888&dev=Pixel+8&id=A1B2"
    val bitmap = com.example.presentation.components.generateQrBitmap(
      content = payload,
      dimension = 512,
      foregroundColor = 0xFF000000.toInt(),
      backgroundColor = 0xFFFFFFFF.toInt(),
      margin = 2
    )

    org.junit.Assert.assertNotNull("QR bitmap should not be null", bitmap)
    assertEquals(512, bitmap!!.width)
    assertEquals(512, bitmap.height)

    // Verify presence of both foreground (black) and background (white) pixels
    var blackPixelFound = false
    var whitePixelFound = false

    val pixels = IntArray(512 * 512)
    bitmap.getPixels(pixels, 0, 512, 0, 0, 512, 512)

    for (pixel in pixels) {
      // Ensure full opacity (alpha channel is 0xFF)
      val alpha = (pixel shr 24) and 0xFF
      assertEquals("Pixel must be 100% opaque", 0xFF, alpha)

      if (pixel == 0xFF000000.toInt()) {
        blackPixelFound = true
      } else if (pixel == 0xFFFFFFFF.toInt()) {
        whitePixelFound = true
      }
    }

    assertTrue("QR code must contain black modules", blackPixelFound)
    assertTrue("QR code must contain white quiet-zone modules", whitePixelFound)

    // Corner quiet zone checks: Top-left and top-right quiet zone margin pixels must be pure white
    assertEquals("Quiet zone top-left corner must be white", 0xFFFFFFFF.toInt(), bitmap.getPixel(0, 0))
    assertEquals("Quiet zone top-right corner must be white", 0xFFFFFFFF.toInt(), bitmap.getPixel(511, 0))
    assertEquals("Quiet zone bottom-left corner must be white", 0xFFFFFFFF.toInt(), bitmap.getPixel(0, 511))
    assertEquals("Quiet zone bottom-right corner must be white", 0xFFFFFFFF.toInt(), bitmap.getPixel(511, 511))
  }

  @Test
  fun `test qr code returns null on blank or empty input`() {
    val blankBitmap = com.example.presentation.components.generateQrBitmap("")
    org.junit.Assert.assertNull("Blank content should return null", blankBitmap)

    val whitespaceBitmap = com.example.presentation.components.generateQrBitmap("   ")
    org.junit.Assert.assertNull("Whitespace content should return null", whitespaceBitmap)
  }
}

