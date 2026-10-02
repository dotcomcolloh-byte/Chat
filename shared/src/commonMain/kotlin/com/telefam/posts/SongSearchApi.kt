package com.telefam.posts

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class ItunesResponse(val resultCount: Int = 0, val results: List<SongResult> = emptyList())

/**
 * Public song search backed by Apple's iTunes Search API — no key required,
 * returns 30s preview URLs that can be attached to a post.
 * https://performance-partners.apple.com/search-api
 */
class SongSearchApi {
    private val client = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    suspend fun search(query: String, limit: Int = 25): List<SongResult> {
        if (query.isBlank()) return emptyList()
        return runCatching {
            client.get("https://itunes.apple.com/search") {
                parameter("term", query)
                parameter("media", "music")
                parameter("entity", "song")
                parameter("limit", limit)
            }.body<ItunesResponse>().results
        }.getOrDefault(emptyList())
    }
}
