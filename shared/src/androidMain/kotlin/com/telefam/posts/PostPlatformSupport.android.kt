package com.telefam.posts

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

actual object PostPlatformSupport {

    actual fun fileSize(path: String): Long = File(path).length()

    actual fun readChunk(path: String, offset: Long, length: Int): ByteArray {
        RandomAccessFile(File(path), "r").use { raf ->
            raf.seek(offset)
            val buf = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = raf.read(buf, read, length - read)
                if (n < 0) break
                read += n
            }
            return if (read == length) buf else buf.copyOf(read)
        }
    }

    /**
     * Client-side compression: videos above ~12MB are re-encoded with Media3 Transformer
     * (720p, H.264/AAC) into cache before upload, cutting both upload time and server CPU.
     * Falls back to the original file if Transformer can't process the source.
     */
    actual suspend fun compressIfNeeded(path: String, mime: String, sizeBytes: Long, context: Any?): String =
        withContext(Dispatchers.IO) {
            val androidContext = context as? android.content.Context ?: return@withContext path
            if (sizeBytes <= 12L * 1024 * 1024) return@withContext path
            try {
                val input = File(path)
                val out = File(input.parentFile, "compressed_${input.nameWithoutExtension}.mp4")
                val context = androidContext
                val transformer = androidx.media3.transformer.Transformer.Builder(context)
                    .setVideoMimeType(androidx.media3.common.MimeTypes.VIDEO_H264)
                    .setAudioMimeType(androidx.media3.common.MimeTypes.AUDIO_AAC)
                    .build()
                val composition = androidx.media3.transformer.Composition.Builder(
                    androidx.media3.transformer.EditedMediaItemSequence.Builder(
                        androidx.media3.transformer.EditedMediaItem.Builder(
                            androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(input))
                        ).build()
                    ).build()
                ).build()
                kotlinx.coroutines.suspendCancellableCoroutine<String> { cont ->
                    transformer.start(composition, out.absolutePath)
                    transformer.addListener(object : androidx.media3.transformer.Transformer.Listener {
                        override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: androidx.media3.transformer.ExportResult) {
                            if (cont.isActive) cont.resumeWith(Result.success(if (out.exists() && out.length() in 1 until input.length()) out.absolutePath else path))
                        }
                        override fun onError(composition: androidx.media3.transformer.Composition, exportResult: androidx.media3.transformer.ExportResult, exportException: androidx.media3.transformer.ExportException) {
                            if (cont.isActive) cont.resumeWith(Result.success(path))
                        }
                    })
                }
            } catch (e: Exception) {
                path // graceful fallback: server pipeline compresses anyway
            }
        }

    /** Evenly-spaced frames for the trim strip — MediaMetadataRetriever snaps to the
     *  nearest sync frame (iframe) at OPTION_CLOSEST_SYNC, which is exactly what a
     *  fast timeline scrubber wants. */
    actual fun extractFrameStrip(path: String, durationMs: Long, count: Int): List<ImageBitmap> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val dur = if (durationMs > 0) durationMs
            else retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (dur <= 0) return emptyList()
            (0 until count).mapNotNull { i ->
                val atUs = (dur * 1000L) * (i * 2 + 1) / (count * 2)
                retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?.let { Bitmap.createScaledBitmap(it, 96, 96 * it.height / it.width, true).asImageBitmap() }
            }
        } catch (e: Exception) {
            emptyList()
        } finally {
            runCatching { retriever.release() }
        }
    }
}
