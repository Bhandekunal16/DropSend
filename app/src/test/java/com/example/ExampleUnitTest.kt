package com.example

import com.example.data.storage.StorageManager
import com.example.domain.model.formatFileSize
import com.example.security.SessionCrypto
import com.example.transfer.protocol.ProtocolMessage
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class ExampleUnitTest {

    @Test
    fun testEcdhKeyExchangeAndDerivation() {
        val aliceKeyPair = SessionCrypto.generateEcKeyPair()
        val bobKeyPair = SessionCrypto.generateEcKeyPair()

        val aliceSharedKey = SessionCrypto.deriveSharedSessionKey(aliceKeyPair.private, bobKeyPair.public.encoded)
        val bobSharedKey = SessionCrypto.deriveSharedSessionKey(bobKeyPair.private, aliceKeyPair.public.encoded)

        assertArrayEquals("Shared keys derived by Alice and Bob should match", aliceSharedKey, bobSharedKey)

        val code1 = SessionCrypto.deriveVerificationCode("DROP-ALICE-BOB", aliceSharedKey)
        val code2 = SessionCrypto.deriveVerificationCode("DROP-ALICE-BOB", bobSharedKey)
        assertEquals("Verification codes should match", code1, code2)
    }

    @Test
    fun testChunkEncryptionDecryption() {
        val sessionKey = SessionCrypto.generateSessionKey()
        val samplePayload = "DropSend secure offline transmission test payload bytes".toByteArray(Charsets.UTF_8)
        val sequence = 42L

        val encrypted = SessionCrypto.encryptChunk(samplePayload, sessionKey, sequence)
        assertFalse("Ciphertext should differ from plaintext", samplePayload.contentEquals(encrypted))

        val decrypted = SessionCrypto.decryptChunk(encrypted, sessionKey)
        assertArrayEquals("Decrypted bytes must match original plaintext", samplePayload, decrypted)

        SessionCrypto.wipeKey(sessionKey)
        for (b in sessionKey) {
            assertEquals(0.toByte(), b)
        }
    }

    @Test
    fun testFileNameSanitization() {
        val dirtyName1 = "../../../etc/passwd"
        val clean1 = StorageManager.sanitizeFileName(dirtyName1)
        assertEquals("passwd", clean1)

        val dirtyName2 = "my/vacation:photo?*.jpg"
        val clean2 = StorageManager.sanitizeFileName(dirtyName2)
        assertEquals("my_vacation_photo__.jpg", clean2)

        val emptyName = "   "
        val cleanEmpty = StorageManager.sanitizeFileName(emptyName)
        assertEquals("received_file", cleanEmpty)
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

    @Test
    fun testProtocolMessageSerialization() {
        val authHandshake = ProtocolMessage.AuthHandshake(
            senderId = "DROP-TEST",
            sessionToken = "sess-12345",
            publicKeyBase64 = "base64testkey"
        )

        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        ProtocolMessage.writeToStream(dos, authHandshake)
        dos.flush()

        val dis = DataInputStream(ByteArrayInputStream(bos.toByteArray()))
        val deserialized = ProtocolMessage.readFromStream(dis)

        assertTrue(deserialized is ProtocolMessage.AuthHandshake)
        val parsed = deserialized as ProtocolMessage.AuthHandshake
        assertEquals("DROP-TEST", parsed.senderId)
        assertEquals("sess-12345", parsed.sessionToken)
        assertEquals("base64testkey", parsed.publicKeyBase64)
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
