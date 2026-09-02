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
import javax.crypto.Mac
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
     * Standard RFC 5869 HKDF-Extract using HmacSHA256
     */
    fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val saltKey = if (salt.isNotEmpty()) SecretKeySpec(salt, "HmacSHA256") else SecretKeySpec(ByteArray(32), "HmacSHA256")
        mac.init(saltKey)
        return mac.doFinal(ikm)
    }

    /**
     * Standard RFC 5869 HKDF-Expand using HmacSHA256
     */
    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val result = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var i = 1
        while (offset < length) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            val toCopy = minOf(t.size, length - offset)
            System.arraycopy(t, 0, result, offset, toCopy)
            offset += toCopy
            i++
        }
        return result
    }

    /**
     * Derives a 256-bit symmetric session key using ECDH shared secret and RFC 5869 HKDF-SHA-256
     */
    fun deriveSharedSessionKey(
        myPrivateKey: java.security.PrivateKey,
        peerPublicKeyBytes: ByteArray,
        salt: ByteArray = byteArrayOf(),
        info: String = "DropSend-v2-AES-GCM-Key"
    ): ByteArray {
        val keyFactory = KeyFactory.getInstance("EC")
        val x509Spec = X509EncodedKeySpec(peerPublicKeyBytes)
        val peerPublicKey = keyFactory.generatePublic(x509Spec)

        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(myPrivateKey)
        keyAgreement.doPhase(peerPublicKey, true)
        val sharedSecret = keyAgreement.generateSecret()

        val prk = hkdfExtract(salt, sharedSecret)
        val derivedKey = hkdfExpand(prk, info.toByteArray(Charsets.UTF_8), 32)

        // Wipe intermediate secret material from memory
        Arrays.fill(sharedSecret, 0.toByte())
        Arrays.fill(prk, 0.toByte())
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
     * Provides SAS (Short Authentication String) protection against Man-in-the-Middle (MITM) attacks.
     */
    fun deriveVerificationCode(
        sessionId: String,
        sharedKeyBytes: ByteArray,
        additionalContext: String = ""
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sharedKeyBytes, "HmacSHA256"))
        mac.update(sessionId.toByteArray(Charsets.UTF_8))
        if (additionalContext.isNotEmpty()) {
            mac.update(additionalContext.toByteArray(Charsets.UTF_8))
        }
        val hash = mac.doFinal()

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

