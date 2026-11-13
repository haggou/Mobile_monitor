package com.example.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystoreCryptoManager(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    private val overrideKeyForTesting: SecretKey? = null
) {
    private val keyStoreType = "AndroidKeyStore"

    private fun getOrCreateKey(): SecretKey {
        if (overrideKeyForTesting != null) return overrideKeyForTesting
        val keyStore = KeyStore.getInstance(keyStoreType).apply { load(null) }
        val existing = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, keyStoreType)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    fun encryptText(plainText: String): String {
        if (plainText.isEmpty()) return ""
        val encrypted = encryptBytes(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    fun decryptText(encodedCipherText: String): String {
        if (encodedCipherText.isEmpty()) return ""
        val raw = Base64.decode(encodedCipherText, Base64.NO_WRAP)
        return decryptBytes(raw).toString(Charsets.UTF_8)
    }

    fun encryptBytes(plainBytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plainBytes)
        return ByteArray(1 + iv.size + cipherBytes.size).apply {
            this[0] = iv.size.toByte()
            System.arraycopy(iv, 0, this, 1, iv.size)
            System.arraycopy(cipherBytes, 0, this, 1 + iv.size, cipherBytes.size)
        }
    }

    fun decryptBytes(payload: ByteArray): ByteArray {
        require(payload.size > 13) { "Invalid encrypted payload" }
        val ivLength = payload[0].toInt() and 0xFF
        val iv = payload.copyOfRange(1, 1 + ivLength)
        val cipherBytes = payload.copyOfRange(1 + ivLength, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(cipherBytes)
    }

    companion object {
        private const val DEFAULT_KEY_ALIAS = "connect_activity_aes_gcm_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
