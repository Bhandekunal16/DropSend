package com.example.data.storage

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.domain.model.TransferFile
import com.example.domain.model.formatFileSize
import com.example.security.SessionCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.UUID

sealed class StorageValidationResult {
    data class Sufficient(val availableBytes: Long, val requiredBytes: Long) : StorageValidationResult()
    data class Insufficient(val availableBytes: Long, val requiredBytes: Long, val message: String) : StorageValidationResult()
}

class StorageManager(private val context: Context) {

    companion object {
        private const val TAG = "StorageManager"
        const val DROPSEND_FOLDER_NAME = "DropSend"
        private const val STORAGE_SAFETY_MARGIN_BYTES = 50L * 1024 * 1024 // 50 MB buffer
    }

    private val contentResolver: ContentResolver = context.contentResolver

    /**
     * Resolves a Uri to a TransferFile model with actual sanitized name, size, and mimeType
     */
    suspend fun resolveFile(uri: Uri): TransferFile = withContext(Dispatchers.IO) {
        var rawName = "file_${System.currentTimeMillis()}"
        var size = 0L
        val mimeType = contentResolver.getType(uri) ?: "*/*"

        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        rawName = cursor.getString(nameIndex) ?: rawName
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex != -1) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error resolving file uri: $uri", e)
        }

        // If size is still 0, attempt to read stream available length
        if (size <= 0) {
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    size = stream.available().toLong()
                }
            } catch (_: Exception) {}
        }

        val sanitized = sanitizeFileName(rawName)

        TransferFile(
            id = UUID.randomUUID().toString(),
            uri = uri,
            name = sanitized,
            mimeType = mimeType,
            sizeBytes = size
        )
    }

    /**
     * Sanitizes a filename to protect against path traversal (../), null bytes, and illegal filesystem characters
     */
    fun sanitizeFileName(rawName: String): String {
        var clean = rawName
            .replace("\\", "/")
            .substringAfterLast("/")
            .replace("\u0000", "")
            .trim()

        // Replace illegal filesystem characters: : * ? " < > |
        clean = clean.replace(Regex("[\\\\/:*?\"<>|]"), "_")

        // Prevent hidden files or relative path navigations
        while (clean.startsWith(".")) {
            clean = clean.removePrefix(".")
        }

        if (clean.isBlank()) {
            clean = "transferred_file_${System.currentTimeMillis()}"
        }

        // Limit name length to 200 chars to avoid OS length limit issues
        if (clean.length > 200) {
            val ext = clean.substringAfterLast('.', "")
            val base = clean.substringBeforeLast('.')
            clean = if (ext.isNotEmpty()) "${base.take(190)}.$ext" else base.take(200)
        }

        return clean
    }

    /**
     * Checks if the device has enough free storage to receive incoming files
     */
    fun validateStorageAvailable(bytesNeeded: Long): StorageValidationResult {
        return try {
            val path = context.getExternalFilesDir(null) ?: context.filesDir
            val stat = StatFs(path.path)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            val totalRequired = bytesNeeded + STORAGE_SAFETY_MARGIN_BYTES

            if (availableBytes >= totalRequired) {
                StorageValidationResult.Sufficient(availableBytes, bytesNeeded)
            } else {
                val availableFormatted = formatFileSize(availableBytes)
                val requiredFormatted = formatFileSize(bytesNeeded)
                StorageValidationResult.Insufficient(
                    availableBytes = availableBytes,
                    requiredBytes = bytesNeeded,
                    message = "Insufficient storage space: $requiredFormatted required, but only $availableFormatted free."
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error calculating available storage", e)
            StorageValidationResult.Sufficient(Long.MAX_VALUE, bytesNeeded)
        }
    }

    /**
     * Compute SHA-256 for a file to send
     */
    suspend fun calculateFileChecksum(file: TransferFile): String = withContext(Dispatchers.IO) {
        val uri = file.uri ?: return@withContext ""
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                SessionCrypto.calculateChecksum(stream)
            } ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "Failed to calculate checksum for ${file.name}", e)
            ""
        }
    }

    /**
     * Opens an input stream for reading file to send (chunked)
     */
    fun openFileForReading(uri: Uri): InputStream? {
        return try {
            contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening input stream for uri: $uri", e)
            null
        }
    }

    /**
     * Prepares a temporary file in app cache to write received chunks
     */
    fun createTempFileForReceiving(fileId: String, fileName: String, resume: Boolean = false): File {
        val safeName = sanitizeFileName(fileName)
        val tempDir = File(context.cacheDir, "dropsend_temp")
        if (!tempDir.exists()) tempDir.mkdirs()
        val tempFile = File(tempDir, "${fileId}_$safeName.part")
        if (!resume && tempFile.exists()) {
            tempFile.delete()
        }
        if (!tempFile.exists()) {
            tempFile.createNewFile()
        }
        return tempFile
    }

    /**
     * Retrieves current byte length of an existing partial file for checkpoint resume
     */
    fun getExistingPartOffset(fileId: String, fileName: String): Long {
        val safeName = sanitizeFileName(fileName)
        val tempDir = File(context.cacheDir, "dropsend_temp")
        val tempFile = File(tempDir, "${fileId}_$safeName.part")
        return if (tempFile.exists()) tempFile.length() else 0L
    }

    /**
     * Appends or writes a chunk payload to the temporary file at specific offset
     */
    fun writeChunkToTempFile(tempFile: File, offset: Long, payload: ByteArray) {
        RandomAccessFile(tempFile, "rw").use { raf ->
            raf.seek(offset)
            raf.write(payload)
        }
    }

    /**
     * Saves completed temporary file to public Downloads/DropSend and returns its accessible Uri
     */
    suspend fun finalizeReceivedFile(
        tempFile: File,
        targetFileName: String,
        mimeType: String,
        expectedChecksum: String
    ): Uri? = withContext(Dispatchers.IO) {
        val sanitizedTarget = sanitizeFileName(targetFileName)
        try {
            // Verify checksum if available
            if (expectedChecksum.isNotBlank()) {
                val actualChecksum = FileInputStream(tempFile).use {
                    SessionCrypto.calculateChecksum(it)
                }
                if (!actualChecksum.equals(expectedChecksum, ignoreCase = true)) {
                    Log.e(TAG, "Checksum mismatch! Expected: $expectedChecksum, Actual: $actualChecksum")
                    tempFile.delete()
                    return@withContext null
                }
            }

            // Determine non-conflicting unique filename if duplicate exists
            val uniqueName = resolveUniqueFileName(sanitizedTarget)

            // Save to Downloads/DropSend and retrieve content Uri
            val savedUri = saveToDownloadsFolder(tempFile, uniqueName, mimeType)
            tempFile.delete()
            savedUri
        } catch (e: Exception) {
            Log.e(TAG, "Error finalizing received file", e)
            tempFile.delete()
            null
        }
    }

    /**
     * Resolves a non-conflicting unique filename: if "photo.jpg" exists, produces "photo (1).jpg", etc.
     */
    fun resolveUniqueFileName(baseName: String): String {
        val sanitized = sanitizeFileName(baseName)
        val nameWithoutExt = sanitized.substringBeforeLast('.', sanitized)
        val extWithDot = if (sanitized.contains('.')) ".${sanitized.substringAfterLast('.')}" else ""

        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dropSendDir = File(downloadsDir, DROPSEND_FOLDER_NAME)

        var candidate = sanitized
        var counter = 1

        while (File(dropSendDir, candidate).exists() || isFileInMediaStore(candidate)) {
            candidate = "$nameWithoutExt ($counter)$extWithDot"
            counter++
            if (counter > 1000) break
        }

        return candidate
    }

    private fun isFileInMediaStore(fileName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val projection = arrayOf(MediaStore.Downloads._ID)
            val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf(fileName, "%$DROPSEND_FOLDER_NAME%")
            contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                cursor.count > 0
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun saveToDownloadsFolder(tempFile: File, fileName: String, mimeType: String): Uri? {
        var resultUri: Uri? = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType.ifBlank { "*/*" })
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DROPSEND_FOLDER_NAME")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }

                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(tempFile).use { input ->
                            input.copyTo(out, 64 * 1024)
                        }
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                    Log.d(TAG, "Saved file to MediaStore Downloads: $uri")
                    resultUri = uri
                }
            } catch (e: Exception) {
                Log.w(TAG, "MediaStore insert failed, falling back to legacy storage", e)
            }
        }

        // Fallback or legacy file save
        if (resultUri == null) {
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val dropSendDir = File(downloadsDir, DROPSEND_FOLDER_NAME)
                if (!dropSendDir.exists()) dropSendDir.mkdirs()
                val destFile = File(dropSendDir, fileName)
                FileInputStream(tempFile).use { input ->
                    FileOutputStream(destFile).use { out ->
                        input.copyTo(out, 64 * 1024)
                    }
                }
                resultUri = getFileProviderUri(destFile)
                Log.d(TAG, "Saved file to legacy storage: ${destFile.absolutePath}, uri: $resultUri")
            } catch (e: Exception) {
                Log.w(TAG, "Legacy storage save failed, saving to internal files", e)
                try {
                    val appDir = File(context.filesDir, DROPSEND_FOLDER_NAME)
                    if (!appDir.exists()) appDir.mkdirs()
                    val destFile = File(appDir, fileName)
                    FileInputStream(tempFile).use { input ->
                        FileOutputStream(destFile).use { out ->
                            input.copyTo(out, 64 * 1024)
                        }
                    }
                    resultUri = getFileProviderUri(destFile)
                } catch (ex: Exception) {
                    Log.e(TAG, "Failed all storage save attempts", ex)
                }
            }
        }

        return resultUri
    }

    /**
     * Generates a real sample file on disk for simulation/demo so user can immediately open and test it.
     */
    suspend fun createAndSaveDemoFile(fileName: String, mimeType: String): Uri? = withContext(Dispatchers.IO) {
        val safeName = sanitizeFileName(fileName)
        try {
            val appDir = File(context.filesDir, DROPSEND_FOLDER_NAME)
            if (!appDir.exists()) appDir.mkdirs()
            val destFile = File(appDir, safeName)

            FileOutputStream(destFile).use { out ->
                when {
                    mimeType.startsWith("image/") -> {
                        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(Color.rgb(30, 41, 59))
                        val paint = Paint().apply {
                            color = Color.rgb(56, 189, 248)
                            textSize = 36f
                            isAntiAlias = true
                            textAlign = Paint.Align.CENTER
                        }
                        canvas.drawText("DropSend Transferred Image", 400f, 280f, paint)
                        paint.color = Color.WHITE
                        paint.textSize = 24f
                        canvas.drawText(safeName, 400f, 340f, paint)
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                    mimeType == "application/pdf" -> {
                        val pdfContent = "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj 2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj 3 0 obj<</Type/Page/MediaBox[0 0 595 842]/Parent 2 0 R/Resources<<>>>>endobj\nxref\n0 4\n0000000000 65535 f\n0000000010 00000 n\n0000000053 00000 n\n0000000102 00000 n\ntrailer<</Size 4/Root 1 0 R>>\nstartxref\n178\n%%EOF"
                        out.write(pdfContent.toByteArray())
                    }
                    mimeType.startsWith("text/") -> {
                        val text = "DropSend Fast Local Transfer Document\n\nFile Name: $safeName\nTransferred securely via direct encrypted link.\nTimestamp: ${System.currentTimeMillis()}\n"
                        out.write(text.toByteArray())
                    }
                    else -> {
                        val text = "DropSend File Transfer: $safeName\nTransferred successfully!\n"
                        out.write(text.toByteArray())
                        out.write(ByteArray(1024) { (it % 128).toByte() })
                    }
                }
            }

            getFileProviderUri(destFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error generating demo file", e)
            null
        }
    }

    /**
     * Obtains a secure FileProvider content Uri
     */
    fun getFileProviderUri(file: File): Uri? {
        return try {
            val authority = "${context.packageName}.fileprovider"
            FileProvider.getUriForFile(context, authority, file)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating FileProvider Uri for ${file.absolutePath}", e)
            Uri.fromFile(file)
        }
    }

    /**
     * Opens a transferred file in the default Android viewer or system chooser
     */
    fun openFile(file: TransferFile) {
        val uri = file.uri
        if (uri == null) {
            openDownloadsFolder()
            return
        }

        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, file.mimeType.ifBlank { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No specific activity found for ${file.mimeType}, trying generic */*", e)
            try {
                val genericIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val chooser = Intent.createChooser(genericIntent, "Open with...").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to open file chooser", ex)
                Toast.makeText(
                    context,
                    "Saved to Downloads/DropSend: ${file.name}",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching file view intent", e)
            Toast.makeText(
                context,
                "File saved: ${file.name}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Opens the system Downloads / DropSend folder
     */
    fun openDownloadsFolder() {
        try {
            val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val dropSendDir = File(downloadsDir, DROPSEND_FOLDER_NAME)
                    val targetUri = if (dropSendDir.exists()) getFileProviderUri(dropSendDir) else null
                    if (targetUri != null) {
                        setDataAndType(targetUri, "*/*")
                    } else {
                        setDataAndType(Uri.parse(downloadsDir.path), "*/*")
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (ex: Exception) {
                Toast.makeText(context, "Saved in Downloads/DropSend", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Deletes any remaining temporary files when session finishes or cancels
     */
    fun clearTempFiles() {
        try {
            val tempDir = File(context.cacheDir, "dropsend_temp")
            if (tempDir.exists()) {
                tempDir.listFiles()?.forEach { it.delete() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing temp files", e)
        }
    }
}
