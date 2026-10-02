package com.telefam.data.api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.serialization.Serializable

enum class GiphyMode(val apiPath: String) {
    GIF("gifs"),
    STICKER("stickers"),
    CLIP("clips")
}

@Serializable
private data class GiphyResponse(val data: List<GiphyObject> = emptyList())

@Serializable
private data class GiphyObject(
    val id: String,
    val title: String = "",
    val images: GiphyImages? = null
)

@Serializable
private data class GiphyImages(
    val fixed_height: GiphyRendition? = null,
    val fixed_height_small: GiphyRendition? = null,
    val original: GiphyRendition? = null
)

@Serializable
private data class GiphyRendition(
    val url: String? = null,
    val mp4: String? = null,
    val width: String? = null,
    val height: String? = null,
    val size: String? = null
)

/** A flattened picker item: preview URL for the grid, send URL for the full-size download. */
data class GiphyItem(
    val id: String,
    val title: String,
    val previewUrl: String,
    val sendUrl: String,
    val isVideo: Boolean
)

/**
 * GIPHY search/trending client for GIFs, stickers and clips.
 * The API key is build-config driven (ApiConfig.giphyApiKey) — never hardcoded.
 * Results are fetched over HTTPS; once the user picks one, the bytes are downloaded
 * and then sent through the normal end-to-end-encrypted message pipeline, so GIPHY
 * never sees who sends what to whom.
 */
class GiphyApi(private val client: HttpClient) {
    private val key: String get() = ApiConfig.giphyApiKey

    val isConfigured: Boolean get() = key.isNotBlank()

    suspend fun page(mode: GiphyMode, query: String, offset: Int, limit: Int = 24): List<GiphyItem> {
        if (!isConfigured) return emptyList()
        val endpoint = if (query.isBlank()) "trending" else "search"
        val response = runCatching {
            client.get("https://api.giphy.com/v1/${mode.apiPath}/$endpoint") {
                parameter("api_key", key)
                if (query.isNotBlank()) parameter("q", query)
                parameter("limit", limit)
                parameter("offset", offset)
                parameter("rating", "pg-13")
                parameter("bundle", "messaging_non_clips")
            }
        }.getOrNull() ?: return emptyList()
        val parsed = runCatching { response.body<GiphyResponse>() }.getOrNull() ?: return emptyList()
        return parsed.data.mapNotNull { obj ->
            val images = obj.images ?: return@mapNotNull null
            val preview = images.fixed_height_small?.url ?: images.fixed_height?.url ?: return@mapNotNull null
            val isVideo = mode == GiphyMode.CLIP
            val send = if (isVideo) {
                images.original?.mp4 ?: images.fixed_height?.mp4
            } else {
                images.original?.url ?: images.fixed_height?.url
            } ?: return@mapNotNull null
            GiphyItem(obj.id, obj.title, preview, send, isVideo)
        }
    }

    /** Downloads the actual bytes to hand to the encrypted send pipeline. */
    suspend fun downloadBytes(item: GiphyItem): ByteArray? =
        runCatching { client.get(item.sendUrl).body<ByteArray>() }.getOrNull()
}
