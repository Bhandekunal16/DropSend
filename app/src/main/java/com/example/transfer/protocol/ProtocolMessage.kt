package com.example.transfer.protocol

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

sealed class ProtocolMessage {

    companion object {
        const val MAGIC_NUMBER = 0x44524F50 // "DROP"
        const val PROTOCOL_VERSION = 2

        const val TYPE_HELLO: Byte = 1
        const val TYPE_SESSION_REQUEST: Byte = 2
        const val TYPE_SESSION_ACCEPT: Byte = 3
        const val TYPE_SESSION_REJECT: Byte = 4
        const val TYPE_TRANSPORT_INFO: Byte = 5
        const val TYPE_FILE_START: Byte = 6
        const val TYPE_CHUNK: Byte = 7
        const val TYPE_CHUNK_ACK: Byte = 8
        const val TYPE_FILE_COMPLETE: Byte = 9
        const val TYPE_FILE_VERIFY_ACK: Byte = 10
        const val TYPE_TRANSFER_PAUSE: Byte = 11
        const val TYPE_TRANSFER_RESUME: Byte = 12
        const val TYPE_TRANSFER_COMPLETE: Byte = 13
        const val TYPE_TRANSFER_CANCEL: Byte = 14
        const val TYPE_SESSION_CLOSE: Byte = 15
        const val TYPE_AUTH_HANDSHAKE: Byte = 16
        const val TYPE_AUTH_HANDSHAKE_ACK: Byte = 17
        const val TYPE_SESSION_RESUME_REQ: Byte = 18
        const val TYPE_SESSION_RESUME_ACK: Byte = 19

        fun readFromStream(input: InputStream): ProtocolMessage? {
            val dis = DataInputStream(input)
            val magic = try {
                dis.readInt()
            } catch (e: Exception) {
                return null
            }
            if (magic != MAGIC_NUMBER) return null

            val type = try {
                dis.readByte()
            } catch (e: Exception) {
                return null
            }

            val totalLength = try {
                dis.readInt()
            } catch (e: Exception) {
                return null
            }

            // Sanity check length to prevent OOM
            if (totalLength < 0 || totalLength > 16 * 1024 * 1024) {
                return null
            }

            return try {
                when (type) {
                    TYPE_HELLO -> {
                        val bytes = ByteArray(totalLength)
                        dis.readFully(bytes)
                        val json = JSONObject(String(bytes, Charsets.UTF_8))
                        Hello(
                            deviceId = json.getString("deviceId"),
                            deviceName = json.getString("deviceName"),
                            protocolVersion = json.optInt("protocolVersion", PROTOCOL_VERSION),
                            publicKeyBase64 = json.optString("publicKey", "")
                        )
                    }
                    TYPE_AUTH_HANDSHAKE -> {
                        val bytes = ByteArray(totalLength)
                        dis.readFully(bytes)
                        val json = JSONObject(String(bytes, Charsets.UTF_8))
                        AuthHandshake(
                            senderId = json.getString("senderId"),
                            sessionToken = json.getString("sessionToken"),
                            publicKeyBase64 = json.getString("publicKey")
                        )
                    }
                    TYPE_AUTH_HANDSHAKE_ACK -> {
                        val bytes = ByteArray(totalLength)
                        dis.readFully(bytes)
                        val json = JSONObject(String(bytes, Charsets.UTF_8))
                        AuthHandshakeAck(
                            receiverId = json.getString("receiverId"),
                            sessionToken = json.getString("sessionToken"),
                            publicKeyBase64 = json.getString("publicKey"),
                            verificationCode = json.getString("verificationCode")
                        )
                    }
                    TYPE_SESSION_REQUEST -> {
                        val bytes = ByteArray(totalLength)
                        dis.readFully(bytes)
                        val json = JSONObject(String(bytes, Charsets.UTF_8))
                        val filesArray = json.getJSONArray("files")
                        val filesList = mutableListOf<FileMetadata>()
                        for (i in 0 until filesArray.length()) {
                            val fo = filesArray.getJSONObject(i)
                            filesList.add(
                                FileMetadata(
                                    id = fo.getString("id"),
                                    name = fo.getString("name"),
                                    mimeType = fo.optString("mimeType", "*/*"),
                                    size = fo.getLong("size"),
                                    checksum = fo.optString("checksum", "")
                                )
                            )
                        }
                        SessionRequest(
                            senderId = json.getString("senderId"),
                            senderName = json.getString("senderName"),
                            totalSize = json.getLong("totalSize"),
                            verificationCode = json.getString("verificationCode"),
                            files = filesList,
                            sessionToken = json.optString("sessionToken", "")
                        )
                    }
                    TYPE_SESSION_ACCEPT -> {
                        val bytes = ByteArray(totalLength)
                        dis.readFully(bytes)
                        val json = JSONObject(String(bytes, Charsets.UTF_8))
                        SessionAccept(
                            receiverId = json.getString("receiverId"),
                            receiverName = json.getString("receiverName"),
                            verificationCode = json.getString("verificationCode"),
                            availableStorageBytes = json.optLong("availableStorageBytes", -1L)
                        )
                    }
                    TYPE_SESSION_REJECT -> {
                        val bytes = ByteArray(totalLength)
                        dis.readFully(bytes)
                        val json = JSONObject(String(bytes, Charsets.UTF_8))
                        SessionReject(
                            reason = json.optString("reason", "Declined by user")
                        )
                    }
                    TYPE_TRANSPORT_INFO -> {
                        val bytes = ByteArray(totalLength)
                        dis.readFully(bytes)
                        val json = JSONObject(String(bytes, Charsets.UTF_8))
                        TransportInfo(
                            transport = json.getString("transport"),
                            ip = json.optString("ip", ""),
                            port = json.getInt("port")
                        )
                    }
                    TYPE_SESSION_RESUME_REQ -> {
                        val bytes = ByteArray(totalLength)
                        dis.readFully(bytes)
                        val json = JSONObject(String(bytes, Charsets.UTF_8))
                        SessionResumeRequest(
                            sessionToken = json.getString("sessionToken"),
                            lastFileId = json.getString("lastFileId"),
                            confirmedOffset = json.getLong("confirmedOffset")
                        )
                    }
                    TYPE_SESSION_RESUME_ACK -> {
                        val bytes = ByteArray(totalLength)
                        dis.readFully(bytes)
                        val json = JSONObject(String(bytes, Charsets.UTF_8))
                        SessionResumeAck(
                            accepted = json.getBoolean("accepted"),
                            resumeFileId = json.getString("resumeFileId"),
                            resumeOffset = json.getLong("resumeOffset")
                        )
                    }
                    TYPE_FILE_START -> {
                        val bytes = ByteArray(totalLength)
                        dis.readFully(bytes)
                        val json = JSONObject(String(bytes, Charsets.UTF_8))
                        FileStart(
                            fileIndex = json.getInt("fileIndex"),
                            totalFiles = json.getInt("totalFiles"),
                            fileId = json.getString("fileId"),
                            name = json.getString("name"),
                            mimeType = json.optString("mimeType", "*/*"),
                            size = json.getLong("size"),
                            checksum = json.optString("checksum", ""),
                            startOffset = json.optLong("startOffset", 0L)
                        )
                    }
                    TYPE_CHUNK -> {
                        val fileIdLen = dis.readShort().toInt()
                        if (fileIdLen <= 0 || fileIdLen > 256) return null
                        val fileIdBytes = ByteArray(fileIdLen)
                        dis.readFully(fileIdBytes)
                        val fileId = String(fileIdBytes, Charsets.UTF_8)
                        val sequence = dis.readLong()
                        val offset = dis.readLong()
                        val payloadLen = dis.readInt()
                        if (payloadLen < 0 || payloadLen > 1024 * 1024) return null
                        val payload = ByteArray(payloadLen)
                        dis.readFully(payload)
                        Chunk(
                            fileId = fileId,
                            sequence = sequence,
                            offset = offset,
                            payload = payload
                        )
                    }
                    TYPE_CHUNK_ACK -> {
                        val bytes = ByteArray(totalLength)
                        dis.readFully(bytes)
                        val json = JSONObject(String(bytes, Charsets.UTF_8))
                        ChunkAck(
                            fileId = json.getString("fileId"),
                            sequence = json.getLong("sequence"),
                            bytesReceived = json.getLong("bytesReceived")
                        )
                    }
                    TYPE_FILE_COMPLETE -> {
                        val bytes = ByteArray(totalLength)
                        dis.readFully(bytes)
                        val json = JSONObject(String(bytes, Charsets.UTF_8))
                        FileComplete(
                            fileId = json.getString("fileId"),
                            checksum = json.getString("checksum")
                        )
                    }
                    TYPE_FILE_VERIFY_ACK -> {
                        val bytes = ByteArray(totalLength)
                        dis.readFully(bytes)
                        val json = JSONObject(String(bytes, Charsets.UTF_8))
                        FileVerifyAck(
                            fileId = json.getString("fileId"),
                            success = json.getBoolean("success")
                        )
                    }
                    TYPE_TRANSFER_PAUSE -> TransferPause
                    TYPE_TRANSFER_RESUME -> TransferResume
                    TYPE_TRANSFER_COMPLETE -> TransferComplete
                    TYPE_TRANSFER_CANCEL -> TransferCancel
                    TYPE_SESSION_CLOSE -> SessionClose
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    abstract fun writeToStream(output: OutputStream)

    data class FileMetadata(
        val id: String,
        val name: String,
        val mimeType: String,
        val size: Long,
        val checksum: String = ""
    )

    data class Hello(
        val deviceId: String,
        val deviceName: String,
        val protocolVersion: Int = PROTOCOL_VERSION,
        val publicKeyBase64: String = ""
    ) : ProtocolMessage() {
        override fun writeToStream(output: OutputStream) {
            val json = JSONObject().apply {
                put("deviceId", deviceId)
                put("deviceName", deviceName)
                put("protocolVersion", protocolVersion)
                if (publicKeyBase64.isNotEmpty()) {
                    put("publicKey", publicKeyBase64)
                }
            }
            writeJson(output, TYPE_HELLO, json)
        }
    }

    data class AuthHandshake(
        val senderId: String,
        val sessionToken: String,
        val publicKeyBase64: String
    ) : ProtocolMessage() {
        override fun writeToStream(output: OutputStream) {
            val json = JSONObject().apply {
                put("senderId", senderId)
                put("sessionToken", sessionToken)
                put("publicKey", publicKeyBase64)
            }
            writeJson(output, TYPE_AUTH_HANDSHAKE, json)
        }
    }

    data class AuthHandshakeAck(
        val receiverId: String,
        val sessionToken: String,
        val publicKeyBase64: String,
        val verificationCode: String
    ) : ProtocolMessage() {
        override fun writeToStream(output: OutputStream) {
            val json = JSONObject().apply {
                put("receiverId", receiverId)
                put("sessionToken", sessionToken)
                put("publicKey", publicKeyBase64)
                put("verificationCode", verificationCode)
            }
            writeJson(output, TYPE_AUTH_HANDSHAKE_ACK, json)
        }
    }

    data class SessionRequest(
        val senderId: String,
        val senderName: String,
        val totalSize: Long,
        val verificationCode: String,
        val files: List<FileMetadata>,
        val sessionToken: String = ""
    ) : ProtocolMessage() {
        override fun writeToStream(output: OutputStream) {
            val json = JSONObject().apply {
                put("senderId", senderId)
                put("senderName", senderName)
                put("totalSize", totalSize)
                put("verificationCode", verificationCode)
                if (sessionToken.isNotEmpty()) {
                    put("sessionToken", sessionToken)
                }
                val arr = JSONArray()
                files.forEach { f ->
                    arr.put(JSONObject().apply {
                        put("id", f.id)
                        put("name", f.name)
                        put("mimeType", f.mimeType)
                        put("size", f.size)
                        put("checksum", f.checksum)
                    })
                }
                put("files", arr)
            }
            writeJson(output, TYPE_SESSION_REQUEST, json)
        }
    }

    data class SessionAccept(
        val receiverId: String,
        val receiverName: String,
        val verificationCode: String,
        val availableStorageBytes: Long = -1L
    ) : ProtocolMessage() {
        override fun writeToStream(output: OutputStream) {
            val json = JSONObject().apply {
                put("receiverId", receiverId)
                put("receiverName", receiverName)
                put("verificationCode", verificationCode)
                if (availableStorageBytes >= 0) {
                    put("availableStorageBytes", availableStorageBytes)
                }
            }
            writeJson(output, TYPE_SESSION_ACCEPT, json)
        }
    }

    data class SessionReject(val reason: String = "Declined by user") : ProtocolMessage() {
        override fun writeToStream(output: OutputStream) {
            val json = JSONObject().apply { put("reason", reason) }
            writeJson(output, TYPE_SESSION_REJECT, json)
        }
    }

    data class TransportInfo(
        val transport: String,
        val ip: String,
        val port: Int
    ) : ProtocolMessage() {
        override fun writeToStream(output: OutputStream) {
            val json = JSONObject().apply {
                put("transport", transport)
                put("ip", ip)
                put("port", port)
            }
            writeJson(output, TYPE_TRANSPORT_INFO, json)
        }
    }

    data class SessionResumeRequest(
        val sessionToken: String,
        val lastFileId: String,
        val confirmedOffset: Long
    ) : ProtocolMessage() {
        override fun writeToStream(output: OutputStream) {
            val json = JSONObject().apply {
                put("sessionToken", sessionToken)
                put("lastFileId", lastFileId)
                put("confirmedOffset", confirmedOffset)
            }
            writeJson(output, TYPE_SESSION_RESUME_REQ, json)
        }
    }

    data class SessionResumeAck(
        val accepted: Boolean,
        val resumeFileId: String,
        val resumeOffset: Long
    ) : ProtocolMessage() {
        override fun writeToStream(output: OutputStream) {
            val json = JSONObject().apply {
                put("accepted", accepted)
                put("resumeFileId", resumeFileId)
                put("resumeOffset", resumeOffset)
            }
            writeJson(output, TYPE_SESSION_RESUME_ACK, json)
        }
    }

    data class FileStart(
        val fileIndex: Int,
        val totalFiles: Int,
        val fileId: String,
        val name: String,
        val mimeType: String,
        val size: Long,
        val checksum: String,
        val startOffset: Long = 0L
    ) : ProtocolMessage() {
        override fun writeToStream(output: OutputStream) {
            val json = JSONObject().apply {
                put("fileIndex", fileIndex)
                put("totalFiles", totalFiles)
                put("fileId", fileId)
                put("name", name)
                put("mimeType", mimeType)
                put("size", size)
                put("checksum", checksum)
                if (startOffset > 0) {
                    put("startOffset", startOffset)
                }
            }
            writeJson(output, TYPE_FILE_START, json)
        }
    }

    data class Chunk(
        val fileId: String,
        val sequence: Long,
        val offset: Long,
        val payload: ByteArray
    ) : ProtocolMessage() {
        override fun writeToStream(output: OutputStream) {
            val dos = DataOutputStream(output)
            val fileIdBytes = fileId.toByteArray(Charsets.UTF_8)
            val totalLen = 2 + fileIdBytes.size + 8 + 8 + 4 + payload.size
            dos.writeInt(MAGIC_NUMBER)
            dos.writeByte(TYPE_CHUNK.toInt())
            dos.writeInt(totalLen)
            dos.writeShort(fileIdBytes.size)
            dos.write(fileIdBytes)
            dos.writeLong(sequence)
            dos.writeLong(offset)
            dos.writeInt(payload.size)
            dos.write(payload)
            dos.flush()
        }
    }

    data class ChunkAck(
        val fileId: String,
        val sequence: Long,
        val bytesReceived: Long
    ) : ProtocolMessage() {
        override fun writeToStream(output: OutputStream) {
            val json = JSONObject().apply {
                put("fileId", fileId)
                put("sequence", sequence)
                put("bytesReceived", bytesReceived)
            }
            writeJson(output, TYPE_CHUNK_ACK, json)
        }
    }

    data class FileComplete(
        val fileId: String,
        val checksum: String
    ) : ProtocolMessage() {
        override fun writeToStream(output: OutputStream) {
            val json = JSONObject().apply {
                put("fileId", fileId)
                put("checksum", checksum)
            }
            writeJson(output, TYPE_FILE_COMPLETE, json)
        }
    }

    data class FileVerifyAck(
        val fileId: String,
        val success: Boolean
    ) : ProtocolMessage() {
        override fun writeToStream(output: OutputStream) {
            val json = JSONObject().apply {
                put("fileId", fileId)
                put("success", success)
            }
            writeJson(output, TYPE_FILE_VERIFY_ACK, json)
        }
    }

    object TransferPause : ProtocolMessage() {
        override fun writeToStream(output: OutputStream) = writeEmpty(output, TYPE_TRANSFER_PAUSE)
    }

    object TransferResume : ProtocolMessage() {
        override fun writeToStream(output: OutputStream) = writeEmpty(output, TYPE_TRANSFER_RESUME)
    }

    object TransferComplete : ProtocolMessage() {
        override fun writeToStream(output: OutputStream) = writeEmpty(output, TYPE_TRANSFER_COMPLETE)
    }

    object TransferCancel : ProtocolMessage() {
        override fun writeToStream(output: OutputStream) = writeEmpty(output, TYPE_TRANSFER_CANCEL)
    }

    object SessionClose : ProtocolMessage() {
        override fun writeToStream(output: OutputStream) = writeEmpty(output, TYPE_SESSION_CLOSE)
    }
}

private fun writeJson(output: OutputStream, type: Byte, json: JSONObject) {
    val dos = DataOutputStream(output)
    val bytes = json.toString().toByteArray(Charsets.UTF_8)
    dos.writeInt(ProtocolMessage.MAGIC_NUMBER)
    dos.writeByte(type.toInt())
    dos.writeInt(bytes.size)
    dos.write(bytes)
    dos.flush()
}

private fun writeEmpty(output: OutputStream, type: Byte) {
    val dos = DataOutputStream(output)
    dos.writeInt(ProtocolMessage.MAGIC_NUMBER)
    dos.writeByte(type.toInt())
    dos.writeInt(0)
    dos.flush()
}
