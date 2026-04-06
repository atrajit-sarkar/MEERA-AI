package com.example.meeraai.service

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * Encryption service — Fernet-compatible encryption for API keys.
 * Matches the Python cryptography.fernet.Fernet format exactly so keys
 * encrypted by the Python backend can be decrypted here and vice-versa.
 *
 * Fernet key = url-safe-base64( signing_key[16] || encryption_key[16] )
 * Fernet token = url-safe-base64( version[1] || timestamp[8] || iv[16] || ciphertext[…] || hmac[32] )
 */
object EncryptionService {

    private const val FERNET_VERSION: Byte = 0x80.toByte()
    private const val IV_LEN = 16
    private const val KEY_HALF = 16
    private const val HMAC_LEN = 32

    private fun splitFernetKey(fernetKey: String): Pair<ByteArray, ByteArray> {
        val raw = Base64.decode(fernetKey, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        require(raw.size == 32) { "Fernet key must be 32 bytes, got ${raw.size}" }
        val signingKey = raw.copyOfRange(0, KEY_HALF)
        val encryptionKey = raw.copyOfRange(KEY_HALF, 32)
        return signingKey to encryptionKey
    }

    fun encrypt(plainText: String, fernetKey: String): String {
        val (signingKey, encryptionKey) = splitFernetKey(fernetKey)

        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val time = System.currentTimeMillis() / 1000
        val timeBytes = ByteArray(8) { i -> ((time shr (56 - 8 * i)) and 0xFF).toByte() }

        // AES-128-CBC with PKCS7 padding
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encryptionKey, "AES"), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        // Assemble payload: version || time || iv || ciphertext
        val payload = byteArrayOf(FERNET_VERSION) + timeBytes + iv + ciphertext

        // HMAC-SHA256 over payload
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(signingKey, "HmacSHA256"))
        val hmac = mac.doFinal(payload)

        return Base64.encodeToString(payload + hmac, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun decrypt(encryptedText: String, fernetKey: String): String {
        val (signingKey, encryptionKey) = splitFernetKey(fernetKey)
        val data = Base64.decode(encryptedText, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        require(data.size >= 1 + 8 + IV_LEN + 16 + HMAC_LEN) { "Token too short" }
        require(data[0] == FERNET_VERSION) { "Bad Fernet version" }

        val payloadLen = data.size - HMAC_LEN
        val payload = data.copyOfRange(0, payloadLen)
        val receivedHmac = data.copyOfRange(payloadLen, data.size)

        // Verify HMAC
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(signingKey, "HmacSHA256"))
        val computedHmac = mac.doFinal(payload)
        require(computedHmac.contentEquals(receivedHmac)) { "HMAC verification failed" }

        // Decrypt
        val iv = data.copyOfRange(9, 9 + IV_LEN)
        val ciphertext = data.copyOfRange(9 + IV_LEN, payloadLen)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encryptionKey, "AES"), IvParameterSpec(iv))
        val decrypted = cipher.doFinal(ciphertext)
        return String(decrypted, Charsets.UTF_8)
    }

    fun generateEncryptionKey(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
