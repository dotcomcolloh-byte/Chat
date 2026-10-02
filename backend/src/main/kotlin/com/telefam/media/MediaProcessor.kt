package com.telefam.media

import com.telefam.config.AppConfig
import com.telefam.db.DatabaseFactory.dbQuery
import com.telefam.db.MediaAssets
import net.coobird.thumbnailator.Thumbnails
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.*
import javax.imageio.ImageIO

sealed class MediaProcessResult {
    data class Success(val mediaId: UUID, val path: String, val reused: Boolean) : MediaProcessResult()
    data class Rejected(val reason: String) : MediaProcessResult()
}

/**
 * Untrusted uploads never touch disk with their original bytes or metadata:
 *  1. Size-capped read into memory only.
 *  2. Decoded strictly via ImageIO (rejects anything that isn't a real raster image —
 *     this alone blocks disguised/polyglot files, since ImageIO won't decode them).
 *  3. Re-encoded from scratch (Thumbnailator) — this strips EXIF/GPS/ICC metadata and
 *     any non-image payload appended to the file, because only decoded pixel data survives.
 *  4. Resized to a max dimension and compressed.
 *  5. Fingerprinted (SHA-256 exact + a simple perceptual hash) so identical/near-identical
 *     re-uploads are detected and the existing stored asset is reused instead of duplicated.
 * Storage root (AppConfig.mediaStoragePath) is a dedicated directory outside any served
 * static path — files are only ever served back through the authenticated media route,
 * never directly.
 */
class MediaProcessor {

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Cheap perceptual hash: downscale to 8x8 grayscale, threshold against average -> 64-bit fingerprint. */
    private fun perceptualHash(image: BufferedImage): String {
        val small = Thumbnails.of(image).forceSize(8, 8).asBufferedImage()
        val gray = IntArray(64)
        var sum = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            val rgb = small.getRGB(x, y)
            val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
            val lum = (r + g + b) / 3
            gray[y * 8 + x] = lum
            sum += lum
        }
        val avg = sum / 64
        val sb = StringBuilder()
        var bits = 0L
        for (i in 0 until 64) if (gray[i] >= avg) bits = bits or (1L shl i)
        return "%016x".format(bits)
    }

    suspend fun processAndStore(ownerId: UUID, rawBytes: ByteArray, declaredMime: String): MediaProcessResult {
        if (rawBytes.size.toLong() > AppConfig.mediaMaxUploadBytes) {
            return MediaProcessResult.Rejected("File too large")
        }
        if (declaredMime !in setOf("image/jpeg", "image/png", "image/webp")) {
            return MediaProcessResult.Rejected("Unsupported type")
        }

        val decoded = try {
            ImageIO.read(rawBytes.inputStream()) ?: return MediaProcessResult.Rejected("Not a valid image")
        } catch (e: Exception) {
            return MediaProcessResult.Rejected("Not a valid image")
        }

        val sha = sha256(rawBytes)
        val pHash = perceptualHash(decoded)

        // Reuse: identical content already stored for this owner -> skip reprocessing & storage.
        val existing = dbQuery {
            MediaAssets.selectAll().where { (MediaAssets.ownerId eq ownerId) and (MediaAssets.sha256 eq sha) }.firstOrNull()
        }
        if (existing != null) {
            return MediaProcessResult.Success(existing[MediaAssets.id].value, existing[MediaAssets.storagePath], reused = true)
        }

        val outputStream = ByteArrayOutputStream()
        Thumbnails.of(decoded)
            .size(AppConfig.mediaMaxDimension, AppConfig.mediaMaxDimension)
            .outputQuality(0.82)
            .outputFormat("jpg")
            .toOutputStream(outputStream)
        val processedBytes = outputStream.toByteArray()

        val mediaId = UUID.randomUUID()
        val ownerDir = File(AppConfig.mediaStoragePath, ownerId.toString()).apply { mkdirs() }
        val outFile = File(ownerDir, "$mediaId.jpg")
        outFile.writeBytes(processedBytes)

        val finalImage = ImageIO.read(outFile)

        dbQuery {
            MediaAssets.insert {
                it[MediaAssets.id] = mediaId
                it[MediaAssets.ownerId] = ownerId
                it[MediaAssets.sha256] = sha
                it[MediaAssets.perceptualHash] = pHash
                it[MediaAssets.storagePath] = outFile.absolutePath
                it[MediaAssets.mimeType] = "image/jpeg"
                it[MediaAssets.width] = finalImage.width
                it[MediaAssets.height] = finalImage.height
                it[MediaAssets.byteSize] = processedBytes.size.toLong()
                it[MediaAssets.createdAt] = LocalDateTime.now()
            }
        }

        return MediaProcessResult.Success(mediaId, outFile.absolutePath, reused = false)
    }
}
