package com.telefam.posts

import com.telefam.chat.PickedFile
import com.telefam.data.api.PostsApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class UploadProgress(
    val stage: UploadStage = UploadStage.IDLE,
    val fraction: Float = 0f,
    val postId: String? = null,
    val error: String? = null
)

/**
 * Chunked, resumable upload driver.
 *
 *  - Compresses client-side first (COMPRESSING) when the platform supports it.
 *  - Sends the file in server-assigned chunks (UPLOADING with % progress).
 *  - On network failure it retries with backoff; before each retry it asks the
 *    server for receivedBytes and resumes from exactly there — nothing already
 *    uploaded is sent again. A 409 Conflict from the chunk endpoint carries the
 *    same authoritative offset.
 *  - After the last chunk, complete() moves the file to quarantine and the screen
 *    polls pipeline-status until the workers finish (PROCESSING -> UPLOADED).
 */
class ChunkedUploadManager(
    private val api: PostsApi,
    private val platformContext: Any? = null
) {
    private val _progress = MutableStateFlow(UploadProgress())
    val progress: StateFlow<UploadProgress> = _progress

    suspend fun upload(
        file: PickedFile,
        onCompressing: (String) -> Unit = {},
        maxAttemptsPerChunk: Int = 5
    ): String? {
        if (file.sizeBytes > 100L * 1024 * 1024) {
            _progress.value = UploadProgress(UploadStage.FAILED, error = "Video exceeds the 100MB limit")
            return null
        }

        // 1. Client-side compression — internal step, the UI only ever shows UPLOADING
        _progress.value = UploadProgress(UploadStage.UPLOADING, fraction = 0f)
        val sendPath = PostPlatformSupport.compressIfNeeded(
            file.path, file.mimeType ?: "video/mp4", file.sizeBytes, platformContext
        )
        onCompressing(sendPath)
        val mime = file.mimeType ?: "video/mp4"
        // The upload session must use the actual bytes of the file returned by compression.
        // Using the original size can make the final chunk read past EOF and abort a valid upload.
        val size = PostPlatformSupport.fileSize(sendPath).takeIf { it > 0L } ?: file.sizeBytes

        // 2. Init session
        val init = runCatching { api.initUpload(file.displayName ?: "video", mime, size) }
            .getOrElse {
                _progress.value = UploadProgress(UploadStage.FAILED, error = "Couldn't start upload")
                return null
            }

        // 3. Chunks with resume
        _progress.value = UploadProgress(UploadStage.UPLOADING, fraction = 0f)
        val chunkSize = init.chunkSize.toInt().coerceAtLeast(256 * 1024)
        var offset = runCatching { api.uploadStatus(init.sessionId).receivedBytes }.getOrDefault(0L)

        while (offset < size) {
            val len = minOf(chunkSize.toLong(), size - offset).toInt()
            val bytes = PostPlatformSupport.readChunk(sendPath, offset, len)
            if (bytes.isEmpty()) {
                _progress.value = UploadProgress(UploadStage.FAILED, error = "Couldn't read local file")
                return null
            }
            var attempt = 0
            var sent = false
            while (!sent && attempt < maxAttemptsPerChunk) {
                attempt++
                try {
                    val ack = api.uploadChunk(init.sessionId, offset, bytes)
                    if (ack == null) {
                        // Offset conflict: re-sync to server-authoritative resume point.
                        offset = api.uploadStatus(init.sessionId).receivedBytes
                        sent = true // outer loop re-reads from the corrected offset
                    } else {
                        offset = ack.receivedBytes
                        sent = true
                        _progress.value = UploadProgress(UploadStage.UPLOADING, fraction = offset.toFloat() / size)
                    }
                } catch (e: Exception) {
                    delay(1000L * attempt) // backoff: 1s, 2s, 3s... then resume from status
                    offset = runCatching { api.uploadStatus(init.sessionId).receivedBytes }.getOrDefault(offset)
                }
            }
            if (!sent) {
                _progress.value = UploadProgress(UploadStage.FAILED, error = "Network lost — tap Post to resume")
                return null
            }
        }

        // 4. Assemble + quarantine
        val complete = runCatching { api.completeUpload(init.sessionId) }.getOrElse {
            _progress.value = UploadProgress(UploadStage.FAILED, error = "Couldn't finalize upload")
            return null
        }

        // 5. Workers: quarantine -> validate/transcode/fingerprint -> public storage
        _progress.value = UploadProgress(UploadStage.PROCESSING, fraction = 1f, postId = complete.postId)
        repeat(120) { // poll up to ~10 min
            delay(5000)
            val st = runCatching { api.pipelineStatus(complete.postId).status }.getOrNull() ?: return@repeat
            when (st) {
                "READY" -> {
                    _progress.value = UploadProgress(UploadStage.UPLOADED, fraction = 1f, postId = complete.postId)
                    return complete.postId
                }
                "FAILED", "REJECTED" -> {
                    _progress.value = UploadProgress(UploadStage.FAILED, postId = complete.postId, error = "Video rejected during processing")
                    return null
                }
            }
        }
        _progress.value = UploadProgress(UploadStage.FAILED, postId = complete.postId, error = "Processing timed out")
        return null
    }
}
