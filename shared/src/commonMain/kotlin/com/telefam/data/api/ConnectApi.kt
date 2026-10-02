package com.telefam.data.api

import com.telefam.connect.ConnectPageDto
import com.telefam.connect.ContactHashUploadRequest
import com.telefam.connect.ContactHashUploadResponse
import com.telefam.connect.FollowStateDto
import com.telefam.connect.ProfileDetailsDto
import com.telefam.connect.RelatedSearchDto
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

/** Thrown on HTTP 429 from the follow endpoints — carries the silent retry delay. */
class FollowRateLimitedException(val secondsRemaining: Long) : Exception("rate limited")

@Serializable private data class RateLimitedBody(val secondsRemaining: Long)
@Serializable private data class LocationUpdateRequest(val lat: Double, val lng: Double, val name: String? = null)

/** Client for /api/connect — discovery, follow graph, profiles, contact hashing. */
class ConnectApi(private val client: HttpClient) {

    private val base get() = ApiConfig.baseUrl.trimEnd('/')

    suspend fun suggestions(offset: Int = 0, limit: Int = 20): ConnectPageDto =
        client.get("$base/api/connect/suggestions") {
            parameter("offset", offset); parameter("limit", limit)
        }.body()

    suspend fun search(query: String, offset: Int = 0, limit: Int = 20): ConnectPageDto =
        client.get("$base/api/connect/search") {
            parameter("q", query); parameter("offset", offset); parameter("limit", limit)
        }.body()

    suspend fun relatedSearch(query: String): RelatedSearchDto =
        client.get("$base/api/connect/search/related") { parameter("q", query) }.body()

    /**
     * Idempotent follow: safe to retry — the server treats repeats as no-ops.
     * [idempotencyKey] lets the server (and logs) correlate client retries/outbox replays.
     */
    suspend fun follow(userId: String, idempotencyKey: String): FollowStateDto {
        val response = client.post("$base/api/connect/follow/$userId") {
            header("Idempotency-Key", idempotencyKey)
        }
        if (response.status == HttpStatusCode.TooManyRequests) {
            throw FollowRateLimitedException(runCatching { response.body<RateLimitedBody>().secondsRemaining }.getOrDefault(60))
        }
        if (!response.status.isSuccess()) throw Exception("follow failed: ${response.status}")
        return response.body()
    }

    suspend fun unfollow(userId: String, idempotencyKey: String): FollowStateDto {
        val response = client.delete("$base/api/connect/follow/$userId") {
            header("Idempotency-Key", idempotencyKey)
        }
        if (!response.status.isSuccess()) throw Exception("unfollow failed: ${response.status}")
        return response.body()
    }

    suspend fun followState(userId: String): FollowStateDto =
        client.get("$base/api/connect/follow-state/$userId").body()

    suspend fun uploadContactHashes(hashes: List<com.telefam.connect.ContactHashEntry>): ContactHashUploadResponse =
        client.post("$base/api/connect/contacts/hashes") {
            contentType(ContentType.Application.Json)
            setBody(ContactHashUploadRequest(hashes))
        }.body()

    suspend fun updateLocation(lat: Double, lng: Double, name: String?) {
        client.post("$base/api/connect/location") {
            contentType(ContentType.Application.Json)
            setBody(LocationUpdateRequest(lat, lng, name))
        }
    }

    suspend fun profile(userId: String): ProfileDetailsDto? {
        val response = client.get("$base/api/connect/profile/$userId")
        return when {
            response.status.isSuccess() -> response.body()
            response.status == HttpStatusCode.NotFound -> null
            else -> throw Exception("profile failed: ${response.status}")
        }
    }

    suspend fun connectionList(userId: String, kind: String, offset: Int = 0, limit: Int = 20): ConnectPageDto =
        client.get("$base/api/connect/profile/$userId/list/$kind") {
            parameter("offset", offset); parameter("limit", limit)
        }.body()

    @Serializable
    data class SubscribeStateDto(val viewerSubscribed: Boolean, val subscriberCount: Long)

    suspend fun setSubscribe(userId: String, subscribe: Boolean): SubscribeStateDto {
        val response = if (subscribe) client.post("$base/api/connect/subscribe/$userId")
        else client.delete("$base/api/connect/subscribe/$userId")
        if (!response.status.isSuccess()) throw Exception("subscribe failed: ${response.status}")
        return response.body()
    }

    /** Absolute URL for server-relative paths such as avatars. */
    fun absoluteUrl(pathOrUrl: String): String =
        if (pathOrUrl.startsWith("http")) pathOrUrl else base + pathOrUrl
}
