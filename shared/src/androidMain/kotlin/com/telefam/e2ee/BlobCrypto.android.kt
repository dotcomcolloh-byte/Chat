package com.telefam.e2ee

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Android implementation: AES-256-GCM via JCA (hardware-backed on modern devices). */
actual object BlobCrypto {
    private const val KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    actual fun encrypt(plaintext: ByteArray): Pair<String, ByteArray> {
        val key = ByteArray(KEY_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        val ciphertext = cipher.doFinal(plaintext)
        return "${base64Encode(key)}.${base64Encode(nonce)}" to ciphertext
    }

    actual fun decrypt(keyNonce: String, ciphertext: ByteArray): ByteArray? = runCatching {
        val parts = keyNonce.split(".")
        require(parts.size == 2)
        val key = base64Decode(parts[0])
        val nonce = base64Decode(parts[1])
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.doFinal(ciphertext) // throws on tag mismatch — nothing untrusted is ever returned
    }.getOrNull()
}
