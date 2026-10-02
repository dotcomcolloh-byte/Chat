package com.telefam.e2ee

import kotlinx.cinterop.*
import platform.CommonCrypto.*
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

/**
 * iOS implementation: AES-256 via CommonCrypto (CCCrypt, CBC+PKCS7) with a
 * SecRandom-generated key and IV per file. The key+iv travel only inside the
 * Signal-encrypted chat payload, and the blob itself is additionally protected by
 * TLS, JWT auth and an unguessable capability id on the server.
 */
@OptIn(ExperimentalForeignApi::class)
actual object BlobCrypto {
    private const val KEY_BYTES = 32 // kCCKeySizeAES256
    private const val IV_BYTES = 16  // kCCBlockSizeAES128

    private fun secureRandom(count: Int): ByteArray {
        val out = ByteArray(count)
        out.usePinned { pinned ->
            SecRandomCopyBytes(kSecRandomDefault, count.toULong(), pinned.addressOf(0))
        }
        return out
    }

    private fun crypt(key: ByteArray, iv: ByteArray, input: ByteArray, encrypt: Boolean): ByteArray? =
        memScoped {
            val outSize = input.size + IV_BYTES
            val out = ByteArray(outSize)
            val moved = alloc<ULongVar>()
            val status = input.usePinned { inPin ->
                out.usePinned { outPin ->
                    key.usePinned { keyPin ->
                        iv.usePinned { ivPin ->
                            CCCrypt(
                                if (encrypt) kCCEncrypt else kCCDecrypt,
                                kCCAlgorithmAES, kCCOptionPKCS7Padding,
                                keyPin.addressOf(0), KEY_BYTES.toULong(),
                                ivPin.addressOf(0),
                                inPin.addressOf(0), input.size.toULong(),
                                outPin.addressOf(0), outSize.toULong(),
                                moved.ptr
                            )
                        }
                    }
                }
            }
            if (status != kCCSuccess) null else out.copyOf(moved.value.toInt())
        }

    actual fun encrypt(plaintext: ByteArray): Pair<String, ByteArray> {
        val key = secureRandom(KEY_BYTES)
        val iv = secureRandom(IV_BYTES)
        val ciphertext = crypt(key, iv, plaintext, encrypt = true) ?: error("AES encrypt failed")
        return "${base64Encode(key)}.${base64Encode(iv)}" to ciphertext
    }

    actual fun decrypt(keyNonce: String, ciphertext: ByteArray): ByteArray? {
        val parts = keyNonce.split(".")
        if (parts.size != 2) return null
        val key = runCatching { base64Decode(parts[0]) }.getOrNull() ?: return null
        val iv = runCatching { base64Decode(parts[1]) }.getOrNull() ?: return null
        return crypt(key, iv, ciphertext, encrypt = false)
    }
}
