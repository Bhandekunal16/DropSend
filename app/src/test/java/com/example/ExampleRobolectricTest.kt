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
}

