package com.telefam.posts

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AVFoundation.*
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.*
import platform.UIKit.UIImage
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class)
actual object PostPlatformSupport {

    actual fun fileSize(path: String): Long = NSFileManager.defaultManager.attributesOfItemAtPath(path, null)?.get(NSFileSize)?.let { (it as NSNumber).longLongValue } ?: 0L

    actual fun readChunk(path: String, offset: Long, length: Int): ByteArray {
        val handle = NSFileHandle.fileHandleForReadingAtPath(path) ?: return ByteArray(0)
        return try {
            handle.seekToOffset(offset.toULong())
            val data = handle.readDataOfLength(length.toULong())
            val size = data.length.toInt()
            val bytes = ByteArray(size)
            if (size > 0) {
                bytes.usePinned { pinned ->
                    memcpy(pinned.addressOf(0), data.bytes, data.length)
                }
            }
            bytes
        } finally {
            handle.closeFile()
        }
    }

    /** Client-side compression via AVAssetExportSession (720p preset) for videos > 12MB. */
    actual suspend fun compressIfNeeded(path: String, mime: String, sizeBytes: Long, context: Any?): String =
        withContext(Dispatchers.Default) {
            if (sizeBytes <= 12L * 1024 * 1024) return@withContext path
            try {
                val asset = AVAsset.assetWithURL(NSURL.fileURLWithPath(path))
                val preset = AVAssetExportPreset1280x720
                val export = AVAssetExportSession.exportSessionWithAsset(asset, preset) ?: return@withContext path
                val tmp = NSTemporaryDirectory() + "compressed_${NSUUID().UUIDString}.mp4"
                export.outputURL = NSURL.fileURLWithPath(tmp)
                export.outputFileType = AVFileTypeMPEG4
                export.shouldOptimizeForNetworkUse = true
                suspendCancellableCoroutine<String> { cont ->
                    export.exportAsynchronouslyWithCompletionHandler {
                        val ok = export.status == AVAssetExportSessionStatusCompleted &&
                            (NSFileManager.defaultManager.fileExistsAtPath(tmp))
                        cont.resume(if (ok) tmp else path)
                    }
                }
            } catch (e: Exception) {
                path
            }
        }

    /** Frame strip for the trim timeline via AVAssetImageGenerator (tolerant of non-keyframes,
     *  snapping to iframes where possible for speed). */
    actual fun extractFrameStrip(path: String, durationMs: Long, count: Int): List<ImageBitmap> {
        return try {
            val asset = AVAsset.assetWithURL(NSURL.fileURLWithPath(path))
            val dur = if (durationMs > 0) durationMs else (asset.duration.value / asset.duration.timescale.toDouble() * 1000).toLong()
            if (dur <= 0) return emptyList()
            val gen = AVAssetImageGenerator(asset)
            gen.appliesPreferredTrackTransform = true
            gen.requestedTimeToleranceBefore = CMTimeMake(1, 2) // allow iframe snapping = fast
            gen.requestedTimeToleranceAfter = CMTimeMake(1, 2)
            (0 until count).mapNotNull { i ->
                val atSec = (dur / 1000.0) * (i * 2 + 1) / (count * 2)
                val time = CMTimeMakeWithSeconds(atSec, 600)
                memScoped {
                    val err = alloc<ObjCObjectVar<NSError?>>()
                    val cgImage = gen.copyCGImageAtTime(time, null, err.ptr)
                    cgImage?.let { UIImage.imageWithCGImage(it) }
                }?.let { uiImage ->
                    // Bridge UIImage -> ImageBitmap via Skia
                    val png = platform.UIKit.UIImagePNGRepresentation(uiImage)?.toByteArray() ?: return@let null
                    org.jetbrains.skia.Image.makeFromEncoded(png).toComposeImageBitmap()
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun NSData.toByteArray(): ByteArray {
        val size = length.toInt()
        if (size == 0) return ByteArray(0)
        val out = ByteArray(size)
        out.usePinned { memcpy(it.addressOf(0), bytes, length) }
        return out
    }
}
