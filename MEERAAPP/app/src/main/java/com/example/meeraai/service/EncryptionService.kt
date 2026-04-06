package com.example.meeraai.service

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * Encryption service — AES-GCM encryption for API keys stored on-device.
 * Compatible approach to the Python Fernet encryption but using Android-native crypto.
 */
object EncryptionService {

    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12

    /**
     * Derive a 256-bit AES key from the user's encryption key string.
     * Uses SHA-256 hash of the key to get exactly 32 bytes.
     */
    private fun deriveKey(keyString: String): SecretKey {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(keyString.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    fun encrypt(plainText: String, encryptionKey: String): String {
        val key = deriveKey(encryptionKey)
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        // Prepend IV to ciphertext
        val combined = iv + encrypted
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(encryptedText: String, encryptionKey: String): String {
        val key = deriveKey(encryptionKey)
        val combined = Base64.decode(encryptedText, Base64.NO_WRAP)

        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val decrypted = cipher.doFinal(ciphertext)
        return String(decrypted, Charsets.UTF_8)
    }

    fun generateEncryptionKey(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
