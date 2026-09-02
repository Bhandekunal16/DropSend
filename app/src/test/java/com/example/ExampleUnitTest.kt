package com.example

import com.example.data.storage.StorageManager
import com.example.domain.model.formatFileSize
import com.example.security.SessionCrypto
import com.example.transfer.protocol.ProtocolMessage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.crypto.AEADBadTagException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleUnitTest {

    // ==========================================
    // P0-1: Secure Session Key Exchange & Authentication
    // ==========================================

    @Test
    fun testEcdhKeyExchangeAndDerivation() {
        val aliceKeyPair = SessionCrypto.generateEcKeyPair()
        val bobKeyPair = SessionCrypto.generateEcKeyPair()
        val sessionToken = SessionCrypto.generateSessionToken()
        val salt = sessionToken.toByteArray(Charsets.UTF_8)

        val aliceSharedKey = SessionCrypto.deriveSharedSessionKey(
            aliceKeyPair.private,
            bobKeyPair.public.encoded,
            salt = salt
        )
        val bobSharedKey = SessionCrypto.deriveSharedSessionKey(
            bobKeyPair.private,
            aliceKeyPair.public.encoded,
            salt = salt
        )

        assertArrayEquals("Shared keys derived by Alice and Bob via ECDH + HKDF must match", aliceSharedKey, bobSharedKey)
        assertEquals(32, aliceSharedKey.size) // 256 bits

        val aliceCode = SessionCrypto.deriveVerificationCode(
            sessionId = sessionToken,
            sharedKeyBytes = aliceSharedKey,
            additionalContext = "DROP-ALICE" + "DROP-BOB"
        )
        val bobCode = SessionCrypto.deriveVerificationCode(
            sessionId = sessionToken,
            sharedKeyBytes = bobSharedKey,
            additionalContext = "DROP-ALICE" + "DROP-BOB"
        )
        assertEquals("Verification codes (SAS) must be identical on both peers", aliceCode, bobCode)
        assertTrue("Verification code format must be 4-digit grouped (e.g. 'XX YY')", aliceCode.matches(Regex("\\d{2} \\d{2}")))
    }

    @Test
    fun testMitmAttackDetection() {
        val aliceKeyPair = SessionCrypto.generateEcKeyPair()
        val bobKeyPair = SessionCrypto.generateEcKeyPair()
        val attackerEveKeyPair = SessionCrypto.generateEcKeyPair()
        val sessionToken = SessionCrypto.generateSessionToken()
        val salt = sessionToken.toByteArray(Charsets.UTF_8)

        // Alice thinks she communicates with Bob, but Eve intercepted and replaced Bob's public key with Eve's
        val aliceSharedKey = SessionCrypto.deriveSharedSessionKey(aliceKeyPair.private, attackerEveKeyPair.public.encoded, salt)
        val bobSharedKey = SessionCrypto.deriveSharedSessionKey(bobKeyPair.private, aliceKeyPair.public.encoded, salt)

        assertFalse("Derived keys must not match under MITM substitution", aliceSharedKey.contentEquals(bobSharedKey))

        val aliceCode = SessionCrypto.deriveVerificationCode(sessionToken, aliceSharedKey, "DROP-ALICE" + "DROP-BOB")
        val bobCode = SessionCrypto.deriveVerificationCode(sessionToken, bobSharedKey, "DROP-ALICE" + "DROP-BOB")

        assertNotEquals("Verification codes must differ under MITM attack", aliceCode, bobCode)
    }

    @Test
    fun testHkdfExtractAndExpand() {
        val salt = "test-salt-bytes".toByteArray(Charsets.UTF_8)
        val ikm = "input-keying-material".toByteArray(Charsets.UTF_8)
        val prk = SessionCrypto.hkdfExtract(salt, ikm)
        assertEquals(32, prk.size)

        val info = "DropSend-v2-AES-GCM-Key".toByteArray(Charsets.UTF_8)
        val okm = SessionCrypto.hkdfExpand(prk, info, 32)
        assertEquals(32, okm.size)
    }

    @Test
    fun testKeyWiping() {
        val key = SessionCrypto.generateSessionKey()
        assertFalse(key.all { it == 0.toByte() })
        SessionCrypto.wipeKey(key)
        assertTrue("All key bytes must be zeroed out in memory", key.all { it == 0.toByte() })
    }

    // ==========================================
    // P0-2 & P0-4: Encryption, Integrity & SHA-256 Trust Model
    // ==========================================

    @Test
    fun testChunkEncryptionDecryptionAndAuthentication() {
        val sessionKey = SessionCrypto.generateSessionKey()
        val samplePayload = "DropSend secure offline transmission test payload bytes".toByteArray(Charsets.UTF_8)
        val sequence = 42L

        val encrypted = SessionCrypto.encryptChunk(samplePayload, sessionKey, sequence)
        assertFalse("Ciphertext should differ from plaintext", samplePayload.contentEquals(encrypted))

        val decrypted = SessionCrypto.decryptChunk(encrypted, sessionKey)
        assertArrayEquals("Decrypted bytes must match original plaintext", samplePayload, decrypted)

        // Tamper with encrypted chunk payload -> AEADBadTagException
        val tampered = encrypted.clone()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0xFF).toByte()
        try {
            SessionCrypto.decryptChunk(tampered, sessionKey)
            fail("Decryption of tampered ciphertext must throw AEADBadTagException")
        } catch (e: Exception) {
            assertTrue("Expected AEAD authentication failure", e is AEADBadTagException || e.cause is AEADBadTagException)
        }
    }

    @Test
    fun testSha256IntegrityVerificationTrustModel() {
        val originalData = "DropSend High Integrity Document Content 2026".toByteArray(Charsets.UTF_8)
        val originalChecksum = SessionCrypto.calculateChecksum(ByteArrayInputStream(originalData))
        assertEquals(64, originalChecksum.length)

        // Same stream -> same checksum
        val verifyChecksum = SessionCrypto.calculateChecksum(ByteArrayInputStream(originalData))
        assertEquals(originalChecksum, verifyChecksum)

        // 1-bit modified stream -> distinct checksum
        val modifiedData = originalData.clone()
        modifiedData[0] = (modifiedData[0].toInt() xor 0x01).toByte()
        val modifiedChecksum = SessionCrypto.calculateChecksum(ByteArrayInputStream(modifiedData))
        assertNotEquals(originalChecksum, modifiedChecksum)
    }

    // ==========================================
    // Sanitization & Safe Filenames
    // ==========================================

    @Test
    fun testFileNameSanitization() {
        val dirtyName1 = "../../../etc/passwd"
        val clean1 = StorageManager.sanitizeFileName(dirtyName1)
        assertEquals("passwd", clean1)

        val dirtyName2 = "my/vacation:photo?*.jpg"
        val clean2 = StorageManager.sanitizeFileName(dirtyName2)
        assertEquals("vacation_photo__.jpg", clean2)

        val emptyName = "   "
        val cleanEmpty = StorageManager.sanitizeFileName(emptyName)
        assertTrue("Empty name should fallback to valid name", cleanEmpty.startsWith("transferred_file_"))
    }

    @Test
    fun testDuplicateFileNameResolution() {
        val unique1 = StorageManager.resolveUniqueFileName("photo.jpg") { false }
        assertEquals("photo.jpg", unique1)

        val unique2 = StorageManager.resolveUniqueFileName("photo.jpg") { name ->
            name == "photo.jpg" || name == "photo (1).jpg"
        }
        assertEquals("photo (2).jpg", unique2)
    }

    // ==========================================
    // Protocol Message Serialization
    // ==========================================

    @Test
    fun testProtocolMessageSerialization() {
        val authHandshake = ProtocolMessage.AuthHandshake(
            senderId = "DROP-TEST",
            sessionToken = "sess-12345",
            publicKeyBase64 = "base64testkey"
        )

        val bos = ByteArrayOutputStream()
        ProtocolMessage.writeToStream(bos, authHandshake)
        bos.flush()

        val dis = DataInputStream(ByteArrayInputStream(bos.toByteArray()))
        val deserialized = ProtocolMessage.readFromStream(dis)

        assertTrue(deserialized is ProtocolMessage.AuthHandshake)
        val parsed = deserialized as ProtocolMessage.AuthHandshake
        assertEquals("DROP-TEST", parsed.senderId)
        assertEquals("sess-12345", parsed.sessionToken)
        assertEquals("base64testkey", parsed.publicKeyBase64)
    }

    @Test
    fun testChunkMessageSerialization() {
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val chunk = ProtocolMessage.Chunk(
            fileId = "file-101",
            sequence = 7L,
            offset = 1024L,
            payload = payload
        )

        val bos = ByteArrayOutputStream()
        chunk.writeToStream(bos)
        bos.flush()

        val dis = DataInputStream(ByteArrayInputStream(bos.toByteArray()))
        val deserialized = ProtocolMessage.readFromStream(dis)

        assertTrue(deserialized is ProtocolMessage.Chunk)
        val parsed = deserialized as ProtocolMessage.Chunk
        assertEquals("file-101", parsed.fileId)
        assertEquals(7L, parsed.sequence)
        assertEquals(1024L, parsed.offset)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun testSessionResumeRequestAndAckSerialization() {
        val req = ProtocolMessage.SessionResumeRequest(
            sessionToken = "sess-token-abc",
            lastFileId = "file-77",
            confirmedOffset = 81920L
        )

        val bos = ByteArrayOutputStream()
        req.writeToStream(bos)
        bos.flush()

        val deserialized = ProtocolMessage.readFromStream(DataInputStream(ByteArrayInputStream(bos.toByteArray())))
        assertTrue(deserialized is ProtocolMessage.SessionResumeRequest)
        val parsed = deserialized as ProtocolMessage.SessionResumeRequest
        assertEquals("sess-token-abc", parsed.sessionToken)
        assertEquals("file-77", parsed.lastFileId)
        assertEquals(81920L, parsed.confirmedOffset)
    }

    // ==========================================
    // P1: State Machine, Protocol, Chunk Validation & Error Taxonomy
    // ==========================================

    @Test
    fun testTransferStateMachineLegalTransitions() {
        // IDLE -> DISCOVERING
        assertTrue(com.example.domain.model.TransferStateMachine.isLegalTransition(
            com.example.domain.model.SessionState.IDLE,
            com.example.domain.model.SessionState.DISCOVERING
        ))

        // CONNECTING -> AUTHENTICATING -> WAITING_FOR_ACCEPT -> TRANSFERRING -> VERIFYING -> COMPLETED
        assertTrue(com.example.domain.model.TransferStateMachine.isLegalTransition(
            com.example.domain.model.SessionState.CONNECTING,
            com.example.domain.model.SessionState.AUTHENTICATING
        ))
        assertTrue(com.example.domain.model.TransferStateMachine.isLegalTransition(
            com.example.domain.model.SessionState.AUTHENTICATING,
            com.example.domain.model.SessionState.WAITING_FOR_ACCEPT
        ))
        assertTrue(com.example.domain.model.TransferStateMachine.isLegalTransition(
            com.example.domain.model.SessionState.WAITING_FOR_ACCEPT,
            com.example.domain.model.SessionState.TRANSFERRING
        ))
        assertTrue(com.example.domain.model.TransferStateMachine.isLegalTransition(
            com.example.domain.model.SessionState.TRANSFERRING,
            com.example.domain.model.SessionState.VERIFYING
        ))
        assertTrue(com.example.domain.model.TransferStateMachine.isLegalTransition(
            com.example.domain.model.SessionState.VERIFYING,
            com.example.domain.model.SessionState.COMPLETED
        ))

        // Cancellation and Disconnection
        assertTrue(com.example.domain.model.TransferStateMachine.isLegalTransition(
            com.example.domain.model.SessionState.TRANSFERRING,
            com.example.domain.model.SessionState.CANCELLED
        ))
        assertTrue(com.example.domain.model.TransferStateMachine.isLegalTransition(
            com.example.domain.model.SessionState.TRANSFERRING,
            com.example.domain.model.SessionState.DISCONNECTED
        ))
    }

    @Test
    fun testTransferStateMachineIllegalTransitions() {
        // Cannot jump directly from COMPLETED to TRANSFERRING
        assertFalse(com.example.domain.model.TransferStateMachine.isLegalTransition(
            com.example.domain.model.SessionState.COMPLETED,
            com.example.domain.model.SessionState.TRANSFERRING
        ))

        // Cannot jump directly from IDLE to VERIFYING
        assertFalse(com.example.domain.model.TransferStateMachine.isLegalTransition(
            com.example.domain.model.SessionState.IDLE,
            com.example.domain.model.SessionState.VERIFYING
        ))

        // Cannot jump directly from CANCELLED to TRANSFERRING
        assertFalse(com.example.domain.model.TransferStateMachine.isLegalTransition(
            com.example.domain.model.SessionState.CANCELLED,
            com.example.domain.model.SessionState.TRANSFERRING
        ))
    }

    @Test
    fun testTransferStateMachineTerminalStates() {
        assertTrue(com.example.domain.model.TransferStateMachine.isTerminal(com.example.domain.model.SessionState.COMPLETED))
        assertTrue(com.example.domain.model.TransferStateMachine.isTerminal(com.example.domain.model.SessionState.CANCELLED))
        assertTrue(com.example.domain.model.TransferStateMachine.isTerminal(com.example.domain.model.SessionState.FAILED))
        assertFalse(com.example.domain.model.TransferStateMachine.isTerminal(com.example.domain.model.SessionState.TRANSFERRING))
        assertFalse(com.example.domain.model.TransferStateMachine.isTerminal(com.example.domain.model.SessionState.IDLE))
    }

    @Test
    fun testProtocolVersionSupport() {
        assertTrue(ProtocolMessage.isVersionSupported(1))
        assertTrue(ProtocolMessage.isVersionSupported(2))
        assertFalse(ProtocolMessage.isVersionSupported(0))
        assertFalse(ProtocolMessage.isVersionSupported(99))
    }

    @Test
    fun testMalformedStreamHandling() {
        // Stream with invalid magic number
        val invalidMagicStream = ByteArrayInputStream(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x01, 0x00))
        assertNull(ProtocolMessage.readFromStream(invalidMagicStream))

        // Empty stream
        val emptyStream = ByteArrayInputStream(ByteArray(0))
        assertNull(ProtocolMessage.readFromStream(emptyStream))
    }

    @Test
    fun testErrorTaxonomy() {
        val storageErr = com.example.domain.model.DropSendError.StorageFull(5000L, 1000L)
        assertEquals("ERR_STORAGE_FULL", storageErr.code)
        assertTrue(storageErr.userMessage.contains("Not enough free storage"))

        val checksumErr = com.example.domain.model.DropSendError.ChecksumMismatch("test.pdf")
        assertEquals("ERR_CHECKSUM_MISMATCH", checksumErr.code)
        assertTrue(checksumErr.userMessage.contains("test.pdf"))

        val permErr = com.example.domain.model.DropSendError.PermissionDenied("NEARBY_WIFI_DEVICES")
        assertEquals("ERR_PERMISSION_DENIED", permErr.code)

        val timeoutErr = com.example.domain.model.DropSendError.Timeout("Transfer session")
        assertEquals("ERR_TIMEOUT", timeoutErr.code)
    }

    @Test
    fun testLargeFileLongOffsets() {
        val largeSize = 4L * 1024 * 1024 * 1024 // 4 GB file (> 32-bit int)
        val chunkOffset = 3L * 1024 * 1024 * 1024 // 3 GB offset
        val chunk = ProtocolMessage.Chunk(
            fileId = "large-4gb-file",
            sequence = 24576L,
            offset = chunkOffset,
            payload = byteArrayOf(1, 2, 3, 4)
        )

        val bos = ByteArrayOutputStream()
        chunk.writeToStream(bos)
        bos.flush()

        val parsed = ProtocolMessage.readFromStream(ByteArrayInputStream(bos.toByteArray())) as ProtocolMessage.Chunk
        assertEquals("large-4gb-file", parsed.fileId)
        assertEquals(chunkOffset, parsed.offset)
        assertEquals(24576L, parsed.sequence)
        assertEquals("4.00 GB", formatFileSize(largeSize))
    }

    @Test
    fun testFormatFileSize() {
        assertEquals("0 B", formatFileSize(0))
        assertEquals("500 B", formatFileSize(500))
        assertEquals("1.5 KB", formatFileSize(1536))
        assertEquals("10.0 MB", formatFileSize(10 * 1024 * 1024))
        assertEquals("2.50 GB", formatFileSize((2.5 * 1024 * 1024 * 1024).toLong()))
    }
}
