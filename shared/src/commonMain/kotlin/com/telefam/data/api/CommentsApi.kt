package com.telefam.data.api

import com.telefam.posts.CommentLikeResponse
import com.telefam.posts.CommentPageDto
import com.telefam.posts.CommentDto
import com.telefam.posts.CommentReportRequest
import com.telefam.posts.CreateCommentRequest
import com.telefam.posts.EditCommentRequest
import com.telefam.posts.GiftStarsRequest
import com.telefam.posts.GiftStarsResponse
import com.telefam.posts.InsufficientStarsException
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*

/** Client for the comment endpoints. Uses the shared authenticated client (JWT + silent refresh). */
class CommentsApi(private val client: HttpClient) {

    private val base get() = ApiConfig.baseUrl.trimEnd('/')

    suspend fun list(postId: String, cursor: String? = null, limit: Int? = null): CommentPageDto =
        client.get("$base/api/feeds/$postId/comments") {
            if (cursor != null) parameter("cursor", cursor)
            if (limit != null) parameter("limit", limit)
        }.body()

    suspend fun replies(postId: String, rootId: String, cursor: String? = null, limit: Int? = null): CommentPageDto =
        client.get("$base/api/feeds/$postId/comments/$rootId/replies") {
            if (cursor != null) parameter("cursor", cursor)
            if (limit != null) parameter("limit", limit)
        }.body()

    suspend fun search(postId: String, query: String, cursor: String? = null, limit: Int? = null): CommentPageDto =
        client.get("$base/api/feeds/$postId/comments/search") {
            parameter("q", query)
            if (cursor != null) parameter("cursor", cursor)
            if (limit != null) parameter("limit", limit)
        }.body()

    suspend fun create(postId: String, req: CreateCommentRequest): CommentDto =
        client.post("$base/api/feeds/$postId/comments") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }.body()

    suspend fun edit(commentId: String, body: String): CommentDto =
        client.put("$base/api/comments/$commentId") {
            contentType(ContentType.Application.Json)
            setBody(EditCommentRequest(body))
        }.body()

    suspend fun delete(commentId: String) {
        client.delete("$base/api/comments/$commentId")
    }

    suspend fun like(commentId: String): CommentLikeResponse =
        client.post("$base/api/comments/$commentId/like").body()

    suspend fun unlike(commentId: String): CommentLikeResponse =
        client.delete("$base/api/comments/$commentId/like").body()

    suspend fun pin(commentId: String) { client.post("$base/api/comments/$commentId/pin") }
    suspend fun unpin(commentId: String) { client.delete("$base/api/comments/$commentId/pin") }

    suspend fun report(commentId: String, reason: String, details: String?) {
        client.post("$base/api/comments/$commentId/report") {
            contentType(ContentType.Application.Json)
            setBody(CommentReportRequest(reason, details))
        }
    }

    /** Star gift. Throws [InsufficientStarsException] on 402 so the UI can route to Buy Stars. */
    suspend fun giftStars(commentId: String, stars: Long, idempotencyKey: String): GiftStarsResponse {
        val response = client.post("$base/api/comments/$commentId/stars") {
            contentType(ContentType.Application.Json)
            header("Idempotency-Key", idempotencyKey)
            setBody(GiftStarsRequest(stars))
            // 402 is an expected business outcome, not a transport error.
            expectSuccess = false
        }
        if (response.status == HttpStatusCode.PaymentRequired) {
            val body = response.body<Map<String, String>>()
            throw InsufficientStarsException(
                balanceStars = body["balanceStars"]?.toLongOrNull() ?: 0L,
                requiredStars = body["requiredStars"]?.toLongOrNull() ?: stars
            )
        }
        if (!response.status.isSuccess()) throw ClientRequestException(response, "Gift failed: ${response.status}")
        return response.body()
    }

    /** Photo comments go through the standard media pipeline (same as profile pictures). */
    suspend fun uploadPhoto(bytes: ByteArray, mimeType: String): String {
        val response = client.submitFormWithBinaryData(
            url = "$base/api/media/upload",
            formData = formData {
                append("file", bytes, Headers.build {
                    append(HttpHeaders.ContentType, mimeType)
                    append(HttpHeaders.ContentDisposition, "filename=\"comment.jpg\"")
                })
            }
        )
        val body = response.body<Map<String, String>>()
        return body["mediaId"] ?: error("Upload failed")
    }

    /** Absolute media URL: relative paths come from the authenticated comment media route. */
    fun absoluteUrl(pathOrUrl: String): String =
        if (pathOrUrl.startsWith("http")) pathOrUrl else base + pathOrUrl
}
