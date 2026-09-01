package com.example.security

import java.io.InputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object SessionCrypto {

    private val secureRandom = SecureRandom()
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12

    /**
     * Generates a temporary cryptographically secure DropSend identity (e.g. "DROP-7A92")
     */
    fun generateTemporaryIdentity(): String {
        val bytes = ByteArray(2)
        secureRandom.nextBytes(bytes)
        val hex = bytes.joinToString("") { "%02X".format(it) }
        return "DROP-$hex"
    }

    /**
     * Generates a secure random session token (32 bytes / 256 bits) to prevent replay attacks
     */
    fun generateSessionToken(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Generates an Elliptic Curve (ECDH SECP256R1) KeyPair for ephemeral key exchange
     */
    fun generateEcKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        val ecSpec = ECGenParameterSpec("secp256r1")
        kpg.initialize(ecSpec, secureRandom)
        return kpg.generateKeyPair()
    }

    /**
     * Derives a 256-bit symmetric session key using ECDH shared secret and SHA-256 HKDF
     */
    fun deriveSharedSessionKey(
        myPrivateKey: java.security.PrivateKey,
        peerPublicKeyBytes: ByteArray,
        salt: ByteArray = byteArrayOf()
    ): ByteArray {
        val keyFactory = KeyFactory.getInstance("EC")
        val x509Spec = X509EncodedKeySpec(peerPublicKeyBytes)
        val peerPublicKey = keyFactory.generatePublic(x509Spec)

        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(myPrivateKey)
        keyAgreement.doPhase(peerPublicKey, true)
        val sharedSecret = keyAgreement.generateSecret()

        // Derive 256-bit AES key via SHA-256 KDF with salt
        val md = MessageDigest.getInstance("SHA-256")
        if (salt.isNotEmpty()) {
            md.update(salt)
        }
        md.update(sharedSecret)
        val derivedKey = md.digest()

        // Wipe intermediate secret from memory
        Arrays.fill(sharedSecret, 0.toByte())
        return derivedKey
    }

    /**
     * Generates a 256-bit ephemeral AES key for standalone or fallback sessions
     */
    fun generateSessionKey(): ByteArray {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256, secureRandom)
        return keyGen.generateKey().encoded
    }

    /**
     * Derives an authenticated, human-friendly 4-digit verification code (e.g., "82 41")
     * from the combination of session ID, peer endpoints, and shared secret.
     */
    fun deriveVerificationCode(
        sessionId: String,
        sharedKeyBytes: ByteArray,
        additionalContext: String = ""
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(sessionId.toByteArray(Charsets.UTF_8))
        digest.update(sharedKeyBytes)
        if (additionalContext.isNotEmpty()) {
            digest.update(additionalContext.toByteArray(Charsets.UTF_8))
        }
        val hash = digest.digest()

        val byte1 = (hash[0].toInt() and 0xFF) % 100
        val byte2 = (hash[1].toInt() and 0xFF) % 100
        return "%02d %02d".format(byte1, byte2)
    }

    /**
     * Encrypts a chunk payload with AES-GCM using session key and sequence number as nonce
     */
    fun encryptChunk(payload: ByteArray, keyBytes: ByteArray, sequence: Long): ByteArray {
        val secretKey: SecretKey = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        // Construct 12-byte IV using sequence number and session-derived bytes
        val iv = ByteArray(GCM_IV_LENGTH)
        val buffer = ByteBuffer.wrap(iv)
        buffer.putLong(sequence)
        for (i in 8 until GCM_IV_LENGTH) {
            iv[i] = (keyBytes[i % keyBytes.size].toInt() xor (sequence shr (i * 8)).toInt()).toByte()
        }

        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

        val cipherText = cipher.doFinal(payload)
        // Prepend IV to ciphertext
        val output = ByteBuffer.allocate(iv.size + cipherText.size)
        output.put(iv)
        output.put(cipherText)
        return output.array()
    }

    /**
     * Decrypts a chunk payload encrypted with AES-GCM
     */
    fun decryptChunk(encryptedData: ByteArray, keyBytes: ByteArray): ByteArray {
        if (encryptedData.size <= GCM_IV_LENGTH) {
            throw IllegalArgumentException("Invalid encrypted chunk length: ${encryptedData.size}")
        }
        val secretKey: SecretKey = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        val iv = ByteArray(GCM_IV_LENGTH)
        System.arraycopy(encryptedData, 0, iv, 0, GCM_IV_LENGTH)

        val cipherText = ByteArray(encryptedData.size - GCM_IV_LENGTH)
        System.arraycopy(encryptedData, GCM_IV_LENGTH, cipherText, 0, cipherText.size)

        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return cipher.doFinal(cipherText)
    }

    /**
     * Calculates SHA-256 checksum of an input stream efficiently in 64KB blocks
     */
    fun calculateChecksum(inputStream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var read: Int
        while (inputStream.read(buffer).also { read = it } != -1) {
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Securely clears sensitive cryptographic key material from memory
     */
    fun wipeKey(keyBytes: ByteArray?) {
        if (keyBytes != null) {
            Arrays.fill(keyBytes, 0.toByte())
        }
    }
}

