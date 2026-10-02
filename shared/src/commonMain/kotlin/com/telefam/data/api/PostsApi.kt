package com.telefam.data.api

import com.telefam.posts.ChunkAck
import com.telefam.posts.CompleteResponse
import com.telefam.posts.FinalizePostRequest
import com.telefam.posts.InitUploadRequest
import com.telefam.posts.InitUploadResponse
import com.telefam.posts.PipelineStatusDto
import com.telefam.posts.UploadStatusDto
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*

/** Client for the chunked post-upload pipeline. All calls go through the app's
 *  authenticated HttpClient (JWT + refresh already installed by HttpClientFactory). */
class PostsApi(private val client: HttpClient) {

    suspend fun initUpload(filename: String, mime: String, sizeBytes: Long): InitUploadResponse =
        client.post("${ApiConfig.baseUrl}/api/posts/uploads/init") {
            contentType(ContentType.Application.Json)
            setBody(InitUploadRequest(filename, mime, sizeBytes))
        }.body()

    /** Sends one chunk. Returns ack, or null when the server reports an offset conflict
     *  (caller should re-sync via [uploadStatus] and resume from the expected offset). */
    suspend fun uploadChunk(sessionId: String, offset: Long, bytes: ByteArray): ChunkAck? {
        val res = client.put("${ApiConfig.baseUrl}/api/posts/uploads/$sessionId/chunk") {
            contentType(ContentType.Application.OctetStream)
            header("X-Chunk-Offset", offset.toString())
            setBody(bytes)
        }
        return if (res.status == HttpStatusCode.Conflict) null else res.body()
    }

    suspend fun uploadStatus(sessionId: String): UploadStatusDto =
        client.get("${ApiConfig.baseUrl}/api/posts/uploads/$sessionId/status").body()

    suspend fun completeUpload(sessionId: String): CompleteResponse =
        client.post("${ApiConfig.baseUrl}/api/posts/uploads/$sessionId/complete").body()

    suspend fun pipelineStatus(postId: String): PipelineStatusDto =
        client.get("${ApiConfig.baseUrl}/api/posts/$postId/pipeline-status").body()

    suspend fun finalizePost(req: FinalizePostRequest): HttpResponse =
        client.post("${ApiConfig.baseUrl}/api/posts/finalize") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }
}
