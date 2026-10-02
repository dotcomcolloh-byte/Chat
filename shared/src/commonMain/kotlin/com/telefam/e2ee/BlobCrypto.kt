package com.telefam.e2ee

/**
 * Per-file authenticated encryption for the large-media blob channel.
 *
 * Small media travels inline inside the Signal-encrypted payload (base64). Files above
 * the inline threshold instead go through the dedicated blob endpoint as ciphertext:
 * the client generates a fresh random key per file, encrypts locally, uploads the
 * opaque blob, and only the key (wrapped inside the Signal-encrypted ChatPayload)
 * ever lets the receiver recover the plaintext. The server stores bytes it cannot read.
 *
 * `keyNonce` format: "<base64(key)>.<base64(nonce/iv)>" — safe to carry inside the
 * already-encrypted chat payload, useless to anyone who only has the blob.
 */
expect object BlobCrypto {
    /** Generates a fresh key, encrypts, returns keyNonce to ciphertext. */
    fun encrypt(plaintext: ByteArray): Pair<String, ByteArray>

    /** Returns null on tamper/wrong key (GCM tag failure) or malformed input. */
    fun decrypt(keyNonce: String, ciphertext: ByteArray): ByteArray?
}
