package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StorageManagerConcurrencyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // 1. Partial channel writes
    @Test
    fun test1_PartialChannelWritesHandledCorrectly() {
        val sm = StorageManager(context)
        val temp = sm.createTempFileForReceiving("f-test1", "partial_write.bin")
        val payload = ByteArray(256 * 1024) { (it % 127).toByte() }

        sm.writeChunkToTempFile(temp, 0L, payload)
        assertEquals(payload.size.toLong(), temp.length())

        val readBack = ByteArray(payload.size)
        FileInputStream(temp).use { it.read(readBack) }
        assertArrayEquals(payload, readBack)

        sm.clearTempFiles()
    }

    // 2. Zero-byte channel writes / loop completion guarantee
    @Test
    fun test2_ZeroByteWritesDoNotTerminateEarly() {
        val sm = StorageManager(context)
        val temp = sm.createTempFileForReceiving("f-test2", "zero_byte.bin")
        val payload = ByteArray(64 * 1024) { (it % 250).toByte() }

        // Must write exactly payload.size without dropping bytes
        sm.writeChunkToTempFile(temp, 0L, payload)
        assertEquals(payload.size.toLong(), temp.length())

        // Empty payload writes should be a safe no-op
        sm.writeChunkToTempFile(temp, payload.size.toLong(), ByteArray(0))
        assertEquals(payload.size.toLong(), temp.length())

        sm.clearTempFiles()
    }

    // 3. Concurrent chunks to the same file
    @Test
    fun test3_ConcurrentChunksToSameFile() = runBlocking {
        val sm = StorageManager(context)
        val temp = sm.createTempFileForReceiving("f-test3", "concurrent_same.bin")
        val chunkSize = 32 * 1024
        val totalChunks = 8

        val jobs = (0 until totalChunks).map { chunkIndex ->
            async(Dispatchers.IO) {
                val offset = chunkIndex.toLong() * chunkSize
                val payload = ByteArray(chunkSize) { (chunkIndex + 10).toByte() }
                sm.writeChunkToTempFile(temp, offset, payload)
            }
        }
        jobs.awaitAll()

        assertEquals((chunkSize * totalChunks).toLong(), temp.length())

        val fullData = ByteArray(chunkSize * totalChunks)
        FileInputStream(temp).use { it.read(fullData) }
        for (i in 0 until totalChunks) {
            val expectedByte = (i + 10).toByte()
            for (j in 0 until chunkSize) {
                assertEquals(expectedByte, fullData[i * chunkSize + j])
            }
        }

        sm.clearTempFiles()
    }

    // 4. Concurrent chunks to different files
    @Test
    fun test4_ConcurrentChunksToDifferentFiles() = runBlocking {
        val sm = StorageManager(context)
        val numFiles = 12
        val payload = ByteArray(16 * 1024) { 42 }

        val jobs = (0 until numFiles).map { fileIdx ->
            async(Dispatchers.IO) {
                val file = sm.createTempFileForReceiving("f-test4-$fileIdx", "file_$fileIdx.bin")
                sm.writeChunkToTempFile(file, 0L, payload)
                assertEquals(payload.size.toLong(), file.length())
            }
        }
        jobs.awaitAll()

        sm.clearTempFiles()
    }

    // 5. Handle eviction during active write (reference count protection)
    @Test
    fun test5_HandleEvictionDuringActiveWriteCannotCloseInUseHandle() = runBlocking {
        val sm = StorageManager(context)
        val activeFile = sm.createTempFileForReceiving("f-test5", "eviction_guard.bin")
        val payload = ByteArray(64 * 1024) { 1 }

        // Start multiple writes in parallel while clearTempFiles is called concurrently
        val writeErrors = AtomicInteger(0)
        val startLatch = CountDownLatch(1)

        val writeJobs = (0..5).map { idx ->
            async(Dispatchers.IO) {
                startLatch.await(5, TimeUnit.SECONDS)
                try {
                    sm.writeChunkToTempFile(activeFile, idx * 64 * 1024L, payload)
                } catch (e: Exception) {
                    writeErrors.incrementAndGet()
                }
            }
        }

        startLatch.countDown()
        val clearJob = async(Dispatchers.IO) {
            sm.clearTempFiles()
        }

        writeJobs.awaitAll()
        clearJob.await()

        assertEquals(0, writeErrors.get())
        sm.clearTempFiles()
    }

    // 6. clearTempFiles() during active write
    @Test
    fun test6_ClearTempFilesDuringActiveWritePreservesActiveTransfers() = runBlocking {
        val sm = StorageManager(context)
        val activeFile = sm.createTempFileForReceiving("f-test6-act", "active.bin")
        val staleFile = sm.createTempFileForReceiving("f-test6-stl", "stale.bin")

        sm.writeChunkToTempFile(staleFile, 0L, byteArrayOf(1, 2, 3))

        val startedLatch = CountDownLatch(1)
        val allowFinishLatch = CountDownLatch(1)
        val writeCompleted = AtomicBoolean(false)

        val writerJob = launch(Dispatchers.IO) {
            val payload = ByteArray(32 * 1024) { 9 }
            startedLatch.countDown()
            sm.writeChunkToTempFile(activeFile, 0L, payload)
            allowFinishLatch.await(5, TimeUnit.SECONDS)
            sm.writeChunkToTempFile(activeFile, payload.size.toLong(), payload)
            writeCompleted.set(true)
        }

        startedLatch.await(5, TimeUnit.SECONDS)
        sm.clearTempFiles()

        allowFinishLatch.countDown()
        writerJob.join()

        assertTrue(writeCompleted.get())
        sm.clearTempFiles()
    }

    // 7. Concurrent finalization and cleanup
    @Test
    fun test7_ConcurrentFinalizationAndCleanup() = runBlocking {
        val sm = StorageManager(context)
        val temp = sm.createTempFileForReceiving("f-test7", "finalize_test.txt")
        val data = "Hello DropSend Concurrent World".toByteArray()
        sm.writeChunkToTempFile(temp, 0L, data)

        val digest = MessageDigest.getInstance("SHA-256")
        val expectedChecksum = digest.digest(data).joinToString("") { "%02x".format(it) }

        val finalizeStartedLatch = CountDownLatch(1)
        val finalizeJob = async(Dispatchers.IO) {
            finalizeStartedLatch.countDown()
            sm.finalizeReceivedFile(
                tempFile = temp,
                targetFileName = "finalized_output.txt",
                mimeType = "text/plain",
                expectedChecksum = expectedChecksum
            )
        }

        finalizeStartedLatch.await(5, TimeUnit.SECONDS)
        val cleanupJob = async(Dispatchers.IO) {
            sm.clearTempFiles()
        }

        val resultUri = finalizeJob.await()
        cleanupJob.await()

        assertNotNull(resultUri)
        sm.clearTempFiles()
    }

    // 8. Exceptions during chunk writes
    @Test
    fun test8_ExceptionsDuringChunkWritesPropagateImmediately() {
        val sm = StorageManager(context)
        val temp = sm.createTempFileForReceiving("f-test8", "error_boundary.bin")

        try {
            sm.writeChunkToTempFile(temp, -1L, byteArrayOf(1, 2))
            fail("Expected IllegalArgumentException on negative offset")
        } catch (e: IllegalArgumentException) {
            // Expected
        }

        try {
            sm.writeChunkToTempFile(temp, Long.MAX_VALUE - 5L, ByteArray(10))
            fail("Expected IllegalArgumentException on Long overflow")
        } catch (e: IllegalArgumentException) {
            // Expected
        }

        sm.clearTempFiles()
    }

    // 9. Coroutine cancellation during writes
    @Test
    fun test9_CoroutineCancellationDuringWritesLeavesManagerHealthy() = runBlocking {
        val sm = StorageManager(context)
        val temp = sm.createTempFileForReceiving("f-test9", "cancel_healthy.bin")

        val job = launch(Dispatchers.IO) {
            for (i in 0..200) {
                sm.writeChunkToTempFile(temp, i * 4096L, ByteArray(4096) { 5 })
            }
        }

        job.cancelAndJoin()

        // sm must remain operational
        sm.writeChunkToTempFile(temp, 0L, byteArrayOf(9, 8, 7))
        assertTrue(temp.length() >= 3L)

        sm.clearTempFiles()
        assertFalse(temp.exists())
    }

    // 10. Repeated cleanup is safe and idempotent
    @Test
    fun test10_RepeatedCleanupIsSafeAndIdempotent() {
        val sm = StorageManager(context)
        // Clean repeatedly on empty state
        sm.clearTempFiles()
        sm.clearTempFiles()
        sm.clearTempFiles()

        val temp = sm.createTempFileForReceiving("f-test10", "repeat.bin")
        sm.writeChunkToTempFile(temp, 0L, byteArrayOf(10, 20, 30))
        assertTrue(temp.exists())

        sm.clearTempFiles()
        assertFalse(temp.exists())

        // Again after file deletion
        sm.clearTempFiles()
        sm.clearTempFiles()
    }
}
