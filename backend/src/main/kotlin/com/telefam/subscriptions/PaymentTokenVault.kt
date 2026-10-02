package com.telefam.subscriptions

import com.telefam.config.AppConfig
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Encrypts provider-issued reusable authorizations; raw card details are never stored. */
internal object PaymentTokenVault {
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    private fun key(): ByteArray? = runCatching {
        Base64.getDecoder().decode(AppConfig.paymentTokenEncryptionKey).also {
            require(it.size == 32) { "PAYMENT_TOKEN_ENCRYPTION_KEY must decode to 32 bytes" }
        }
    }.getOrNull()

    fun encrypt(value: String): String? = runCatching {
        val key = key() ?: return null
        val nonce = ByteArray(NONCE_BYTES).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        Base64.getEncoder().encodeToString(nonce + encrypted)
    }.getOrNull()

    fun decrypt(value: String): String? = runCatching {
        val key = key() ?: return null
        val bytes = Base64.getDecoder().decode(value)
        require(bytes.size > NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES))
        String(cipher.doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES), Charsets.UTF_8)
    }.getOrNull()
}