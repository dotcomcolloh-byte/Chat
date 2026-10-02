package com.telefam.data.api

import com.telefam.posts.FeedDownloadsAllowedRequest
import com.telefam.posts.FeedEditPostRequest
import com.telefam.posts.FeedPageDto
import com.telefam.posts.FeedPostDto
import com.telefam.posts.FeedReportRequest
import com.telefam.posts.FeedReshareRequest
import com.telefam.posts.FeedTab
import com.telefam.posts.FeedViewRecordRequest
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*

/** Client for the feed endpoints. Uses the shared authenticated client (JWT + silent refresh). */
class FeedApi(private val client: HttpClient) {

    private val base get() = ApiConfig.baseUrl.trimEnd('/')

    suspend fun feed(tab: FeedTab, cursor: String? = null, limit: Int? = null): FeedPageDto =
        client.get("$base/api/feeds/${tab.path}") {
            if (cursor != null) parameter("cursor", cursor)
            if (limit != null) parameter("limit", limit)
        }.body()

    suspend fun search(query: String, cursor: String? = null, limit: Int? = null): FeedPageDto =
        client.get("$base/api/feeds/search") {
            parameter("q", query)
            if (cursor != null) parameter("cursor", cursor)
            if (limit != null) parameter("limit", limit)
        }.body()

    suspend fun userPosts(userId: String, cursor: String? = null, limit: Int? = null): FeedPageDto =
        client.get("$base/api/feeds/user/$userId") {
            if (cursor != null) parameter("cursor", cursor)
            if (limit != null) parameter("limit", limit)
        }.body()

    /** Owner profile grid tabs: "posts" | "reshared" | "locked" | "saved". */
    suspend fun userTabPosts(userId: String, tab: String, cursor: String? = null, limit: Int? = null): FeedPageDto =
        client.get("$base/api/feeds/user/$userId/tab/$tab") {
            if (cursor != null) parameter("cursor", cursor)
            if (limit != null) parameter("limit", limit)
        }.body()

    suspend fun recordView(postId: String, watchedMs: Long, completed: Boolean) {
        client.post("$base/api/feeds/$postId/view") {
            contentType(ContentType.Application.Json)
            setBody(FeedViewRecordRequest(watchedMs, completed))
        }
    }

    suspend fun like(postId: String) = client.post("$base/api/feeds/$postId/like")
    suspend fun unlike(postId: String) = client.delete("$base/api/feeds/$postId/like")
    suspend fun save(postId: String) = client.post("$base/api/feeds/$postId/save")
    suspend fun unsave(postId: String) = client.delete("$base/api/feeds/$postId/save")

    suspend fun reshare(postId: String, channel: String = "EXTERNAL") =
        client.post("$base/api/feeds/$postId/reshare") {
            contentType(ContentType.Application.Json)
            setBody(FeedReshareRequest(channel))
        }

    suspend fun notInterested(postId: String) = client.post("$base/api/feeds/$postId/not-interested")

    suspend fun report(postId: String, reason: String, details: String?): HttpResponse =
        client.post("$base/api/feeds/$postId/report") {
            contentType(ContentType.Application.Json)
            setBody(FeedReportRequest(reason, details))
        }

    suspend fun follow(userId: String) = client.post("$base/api/feeds/$userId/follow")
    suspend fun unfollow(userId: String) = client.delete("$base/api/feeds/$userId/follow")

    suspend fun getOwnedPost(postId: String): FeedPostDto = client.get("$base/api/posts/$postId").body()

    suspend fun editPost(postId: String, req: FeedEditPostRequest): HttpResponse =
        client.put("$base/api/posts/$postId") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }

    suspend fun deletePost(postId: String): HttpResponse = client.delete("$base/api/posts/$postId")

    suspend fun setDownloadsAllowed(postId: String, allowed: Boolean): HttpResponse =
        client.put("$base/api/posts/$postId/downloads") {
            contentType(ContentType.Application.Json)
            setBody(FeedDownloadsAllowedRequest(allowed))
        }

    /** Absolute media URL: signed paths arrive relative unless minted on a CDN origin. */
    fun absoluteUrl(pathOrUrl: String): String =
        if (pathOrUrl.startsWith("http")) pathOrUrl else base + pathOrUrl
}
