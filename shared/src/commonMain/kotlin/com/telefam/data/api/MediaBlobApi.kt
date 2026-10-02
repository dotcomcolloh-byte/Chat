package com.telefam.data.api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*

/**
 * Dedicated channel for large encrypted media blobs. The bytes here are AEAD
 * ciphertext produced by BlobCrypto before upload — this API moves opaque data only;
 * the matching key never leaves the Signal-encrypted chat payload.
 */
class MediaBlobApi(private val client: HttpClient) {

    /** Returns the server id for the stored blob, or null on failure (caller keeps the message pending). */
    suspend fun upload(ciphertext: ByteArray): String? {
        val response = runCatching {
            client.post("${ApiConfig.baseUrl}/api/media/blob") {
                contentType(ContentType.Application.OctetStream)
                setBody(ciphertext)
            }
        }.getOrNull() ?: return null
        if (!response.status.isSuccess()) return null
        return runCatching { response.body<Map<String, String>>()["mediaId"] }.getOrNull()
    }

    suspend fun download(mediaId: String): ByteArray? {
        val response = runCatching { client.get("${ApiConfig.baseUrl}/api/media/blob/$mediaId") }.getOrNull() ?: return null
        if (!response.status.isSuccess()) return null
        return runCatching { response.body<ByteArray>() }.getOrNull()
    }
}
