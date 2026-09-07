package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.storage.ChecksumResult
import com.example.data.storage.StorageManager
import com.example.data.storage.StorageValidationResult
import com.example.domain.model.TransferFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StorageManagerP2HardenedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    // 1. Resume integrity & metadata checkpointing (P2-1)
    @Test
    fun testP2_1_ResumeTransferIntegrityWithMetadata() {
        val sm = StorageManager(context)
        val fileId = "test-resume-p2"
        val fileName = "data_chunk.bin"
        val expectedSize = 1024L

        // Create initial temp file with expected size
        val temp = sm.createTempFileForReceiving(fileId, fileName, expectedSize = expectedSize, resume = false)
        sm.writeChunkToTempFile(temp, 0L, ByteArray(256) { 1 })

        val offset = sm.getExistingPartOffset(fileId, fileName, expectedSize = expectedSize)
        assertEquals(256L, offset)

        // If sender sends a different expectedSize, resume checkpoint is invalidated
        val mismatchedOffset = sm.getExistingPartOffset(fileId, fileName, expectedSize = 2048L)
        assertEquals(0L, mismatchedOffset)

        // Truncate temp file check
        val tempResume = sm.createTempFileForReceiving(fileId, fileName, expectedSize = expectedSize, resume = false)
        sm.writeChunkToTempFile(tempResume, 0L, ByteArray(512) { 2 })
        assertEquals(512L, tempResume.length())

        sm.truncateTempFile(tempResume, 256L)
        assertEquals(256L, tempResume.length())

        sm.clearTempFiles()
        assertFalse(tempResume.exists())
    }

    // 2. Checksum error semantics (P2-2)
    @Test
    fun testP2_2_ChecksumSemanticsNeverReturnEmptyStringOnFailure() =
        runBlocking {
            val sm = StorageManager(context)

            // Missing URI throws FileNotFoundException
            val invalidFile =
                TransferFile(
                    id = "f-invalid",
                    uri = null,
                    name = "missing.txt",
                    mimeType = "text/plain",
                    sizeBytes = 100L,
                )

            try {
                sm.calculateFileChecksum(invalidFile)
                fail("Expected FileNotFoundException on missing URI")
            } catch (e: FileNotFoundException) {
                // Expected: Typed exception rather than silent ""
                assertNotNull(e.message)
            }

            // Structured result returns FileNotFound
            val result = sm.calculateFileChecksumResult(invalidFile)
            assertTrue(result is ChecksumResult.FileNotFound)
        }

    // 3. Multi-partition storage preflight & bounds (P2-3 & P2-8)
    @Test
    fun testP2_3_StorageValidationBoundsAndOverflow() {
        val sm = StorageManager(context)

        // Negative size rejected
        val negResult = sm.validateStorageAvailable(-100L)
        assertTrue(negResult is StorageValidationResult.Insufficient)

        // Extremely large file (Long overflow boundary) handled without exception
        val overflowResult = sm.validateStorageAvailable(Long.MAX_VALUE)
        assertTrue(overflowResult is StorageValidationResult)

        // Normal size valid
        val normalResult = sm.validateStorageAvailable(10 * 1024 * 1024L)
        assertTrue(normalResult is StorageValidationResult.Sufficient)
    }

    // 4. Filename edge cases & sanitization defense-in-depth (P2-7)
    @Test
    fun testP2_7_FilenameHardeningEdgeCases() {
        // Path traversal
        assertEquals("secret.txt", StorageManager.sanitizeFileName("../../secret.txt"))
        assertEquals("secret.txt", StorageManager.sanitizeFileName("..\\..\\secret.txt"))

        // Windows reserved device names
        assertEquals("_CON.txt", StorageManager.sanitizeFileName("CON.txt"))
        assertEquals("_NUL.pdf", StorageManager.sanitizeFileName("NUL.pdf"))
        assertEquals("_AUX", StorageManager.sanitizeFileName("AUX"))
        assertEquals("_COM1.dat", StorageManager.sanitizeFileName("COM1.dat"))

        // ASCII control codes & newlines
        assertEquals("my_file.txt", StorageManager.sanitizeFileName("my\nfile.txt"))
        assertEquals("my_file.txt", StorageManager.sanitizeFileName("my\rfile.txt"))
        assertEquals("my_file.txt", StorageManager.sanitizeFileName("my\tfile.txt"))

        // Unicode, emoji, RTL
        assertEquals("фото.jpg", StorageManager.sanitizeFileName("фото.jpg"))
        assertEquals("中文_报告.pdf", StorageManager.sanitizeFileName("中文_报告.pdf"))
        assertEquals("🎉party.png", StorageManager.sanitizeFileName("🎉party.png"))
        assertEquals("ملف.txt", StorageManager.sanitizeFileName("ملف.txt"))

        // Edge names
        assertEquals("file.tar.gz", StorageManager.sanitizeFileName("file.tar.gz"))
        assertEquals("file", StorageManager.sanitizeFileName("file."))
        assertEquals("txt", StorageManager.sanitizeFileName(".txt"))
    }

    // 5. Finalize with single-pass copy & digest (P2-11)
    @Test
    fun testP2_11_FinalizeWithAccurateChecksum() =
        runBlocking {
            val sm = StorageManager(context)
            val temp = sm.createTempFileForReceiving("f-test-finalize", "final_test.txt")
            val content = "DropSend Production Checksum Verification Content".toByteArray(Charsets.UTF_8)
            sm.writeChunkToTempFile(temp, 0L, content)

            val md = MessageDigest.getInstance("SHA-256")
            val expectedSha = md.digest(content).joinToString("") { "%02x".format(it) }

            val uri =
                sm.finalizeReceivedFile(
                    tempFile = temp,
                    targetFileName = "final_test.txt",
                    mimeType = "text/plain",
                    expectedChecksum = expectedSha,
                )

            assertNotNull(uri)
            assertFalse(temp.exists()) // Temp file must be cleaned up

            sm.clearTempFiles()
        }

    // 6. Checksum mismatch triggers total rollback (P2-2 & P2-6)
    @Test
    fun testP2_2_ChecksumMismatchTriggersTotalRollback() =
        runBlocking {
            val sm = StorageManager(context)
            val temp = sm.createTempFileForReceiving("f-test-mismatch", "corrupted.txt")
            sm.writeChunkToTempFile(temp, 0L, "Some content".toByteArray())

            val bogusChecksum = "0000000000000000000000000000000000000000000000000000000000000000"

            val uri =
                sm.finalizeReceivedFile(
                    tempFile = temp,
                    targetFileName = "corrupted.txt",
                    mimeType = "text/plain",
                    expectedChecksum = bogusChecksum,
                )

            assertNull("Checksum mismatch must return null", uri)
            assertFalse("Temp file must be deleted on checksum mismatch", temp.exists())

            sm.clearTempFiles()
        }
}
