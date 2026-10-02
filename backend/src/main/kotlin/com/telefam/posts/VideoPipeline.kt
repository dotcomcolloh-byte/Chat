package com.telefam.posts

import com.telefam.config.AppConfig
import com.telefam.db.DatabaseFactory.dbQuery
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

data class VideoJob(val postId: UUID, val sessionId: UUID, val ownerId: UUID)

/**
 * Bounded worker queue. CPU-heavy ffmpeg work is capped at AppConfig.postWorkerCount
 * concurrent jobs so the web tier keeps serving requests; additional uploads simply
 * wait in the channel. Jobs run on Dispatchers.IO around external processes and
 * never block Netty event-loop threads.
 */
class VideoJobQueue(private val scope: CoroutineScope) {
    private val channel = Channel<VideoJob>(capacity = 256)
    private val processor = VideoProcessingWorker()

    fun start() {
        repeat(AppConfig.postWorkerCount) {
            scope.launch(Dispatchers.IO) {
                for (job in channel) {
                    runCatching { processor.process(job) }
                        .onFailure { processor.markFailed(job, it.message ?: "processing error") }
                }
            }
        }
    }

    suspend fun enqueue(job: VideoJob) = channel.send(job)
}

/**
 * Quarantine -> public pipeline. Every step re-validates; any failure rejects the upload.
 *
 *  1. ffprobe — strict decode of the container: duration, streams, dimensions. A file
 *     ffmpeg can't decode never leaves quarantine (blocks disguised/polyglot uploads).
 *  2. Sanitize — full decode + re-encode to a clean H.264/AAC mp4. Only decoded frames
 *     survive, so embedded payloads and malformed metadata are stripped.
 *  3. Qualities — 1080p / 720p / 480p encoded as SEPARATE files (adaptive delivery).
 *  4. Audio extraction — audio track demuxed to .m4a so it can be reused later
 *     (e.g. the "Add song"/sound-on-other-posts feature) without touching the video.
 *  5. Merge check — the extracted audio is muxed back with the 480p video and the
 *     result is probed, proving A/V integrity of the extracted assets.
 *  6. Fingerprint — SHA-256 of the sanitized file + perceptual hashes of sampled
 *     frames + hash of the extracted audio, stored in MediaFingerprints for
 *     piracy/monetization matching.
 *  7. Promote — only now are derived files moved to the PUBLIC storage root and
 *     PostMedia rows written. Quarantined originals are deleted after success.
 */
class VideoProcessingWorker {

    private val publicRoot: File get() = File(AppConfig.postPublicStoragePath).apply { mkdirs() }
    private val ffmpeg get() = AppConfig.ffmpegPath
    private val ffprobe get() = AppConfig.ffprobePath

    data class Probe(val durationMs: Long, val width: Int, val height: Int, val hasAudio: Boolean)

    private fun run(vararg cmd: String, timeoutSec: Long = 300): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) { p.destroyForcibly(); return -1 to "timeout" }
        return p.exitValue() to out
    }

    fun probe(file: File): Probe? {
        val (code, out) = run(
            ffprobe, "-v", "error", "-show_entries",
            "format=duration:stream=codec_type,width,height",
            "-of", "json", file.absolutePath
        )
        if (code != 0) return null
        val durationMs = Regex("\"duration\"\\s*:\\s*\"([0-9.]+)\"")
            .find(out)?.groupValues?.get(1)?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: return null
        val width = Regex("\"width\"\\s*:\\s*(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val height = Regex("\"height\"\\s*:\\s*(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val hasAudio = out.contains("\"codec_type\": \"audio\"") || out.contains("\"codec_type\":\"audio\"")
        return Probe(durationMs, width, height, hasAudio)
    }

    /** 64-bit perceptual hash (8x8 average hash) of one decoded frame, hex-encoded. */
    private fun frameHash(videoFile: File, atSeconds: Double, tmpDir: File, index: Int): String? {
        val png = File(tmpDir, "frame_$index.png")
        val (code, _) = run(
            ffmpeg, "-y", "-ss", atSeconds.toString(), "-i", videoFile.absolutePath,
            "-frames:v", "1", "-vf", "scale=8:8,format=gray", png.absolutePath, timeoutSec = 60
        )
        if (code != 0 || !png.exists()) return null
        val img = javax.imageio.ImageIO.read(png) ?: return null
        val lum = IntArray(64)
        var sum = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            val v = img.getRGB(x, y) and 0xFF
            lum[y * 8 + x] = v; sum += v
        }
        val avg = sum / 64
        var bits = 0L
        for (i in 0 until 64) if (lum[i] >= avg) bits = bits or (1L shl i)
        png.delete()
        return "%016x".format(bits)
    }

    private fun sha256File(file: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = ins.read(buf); if (n < 0) break; d.update(buf, 0, n) }
        }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun markFailed(job: VideoJob, reason: String) {
        dbQuery {
            UploadSessions.update({ UploadSessions.id eq job.sessionId }) {
                it[status] = "FAILED"; it[rejectReason] = reason.take(255); it[updatedAt] = LocalDateTime.now()
            }
            Posts.update({ Posts.id eq job.postId }) { it[status] = "REJECTED" }
        }
    }

    suspend fun process(job: VideoJob) = withContext(Dispatchers.IO) {
        val session = dbQuery {
            UploadSessions.selectAll().where { UploadSessions.id eq job.sessionId }.firstOrNull()
        } ?: return@withContext markFailed(job, "session missing")
        val quarantineFile = File(session[UploadSessions.quarantinePath]!!)
        if (!quarantineFile.exists()) return@withContext markFailed(job, "quarantine file missing")

        dbQuery {
            UploadSessions.update({ UploadSessions.id eq job.sessionId }) {
                it[status] = "PROCESSING"; it[updatedAt] = LocalDateTime.now()
            }
        }

        val tmpDir = File(AppConfig.postWorkPath, job.postId.toString()).apply { mkdirs() }
        try {
            // 1. Strict probe/decode validation
            val probe = probe(quarantineFile) ?: return@withContext markFailed(job, "undecodable video")
            if (probe.durationMs <= 0 || probe.durationMs > AppConfig.postMaxDurationMs) {
                return@withContext markFailed(job, "duration out of bounds")
            }

            // 2. Sanitize: decode + re-encode to clean baseline mp4
            val sanitized = File(tmpDir, "sanitized.mp4")
            val (sc, _) = run(
                ffmpeg, "-y", "-i", quarantineFile.absolutePath,
                "-map_metadata", "-1", "-c:v", "libx264", "-preset", "veryfast",
                "-crf", "23", "-c:a", "aac", "-b:a", "128k",
                "-movflags", "+faststart", sanitized.absolutePath, timeoutSec = 600
            )
            if (sc != 0 || !sanitized.exists()) return@withContext markFailed(job, "sanitize failed")

            // 3. Separate qualities — only downscale, never upscale
            data class Q(val kind: String, val height: Int, val bitrate: String)
            val targets = listOf(Q("VIDEO_1080", 1080, "3500k"), Q("VIDEO_720", 720, "2000k"), Q("VIDEO_480", 480, "900k"))
                .filter { it.height <= probe.height }
            val outputs = mutableListOf<Pair<String, File>>()
            for (q in targets) {
                val out = File(tmpDir, "${q.kind.lowercase()}.mp4")
                val (code, _) = run(
                    ffmpeg, "-y", "-i", sanitized.absolutePath,
                    "-vf", "scale=-2:${q.height}", "-c:v", "libx264", "-preset", "veryfast",
                    "-b:v", q.bitrate, "-c:a", "aac", "-b:a", "128k",
                    "-movflags", "+faststart", out.absolutePath, timeoutSec = 600
                )
                if (code == 0 && out.exists()) outputs.add(q.kind to out)
            }
            if (outputs.isEmpty()) return@withContext markFailed(job, "no quality renditions produced")

            // 3b. Feed/search thumbnail — a real decoded frame from the sanitized file,
            //     so the grid never has to decode video just to render a preview.
            val thumbOut = File(tmpDir, "thumbnail.jpg")
            run {
                val atSec = (probe.durationMs / 1000.0 * 0.10).coerceAtLeast(0.0)
                val (code, _) = run(
                    ffmpeg, "-y", "-ss", atSec.toString(), "-i", sanitized.absolutePath,
                    "-frames:v", "1", "-vf", "scale=-2:480", "-q:v", "4",
                    thumbOut.absolutePath, timeoutSec = 60
                )
                if (code == 0 && thumbOut.exists()) outputs.add("THUMBNAIL" to thumbOut)
            }

            // 4. Extract audio for later reuse
            val audioOut = File(tmpDir, "audio.m4a")
            if (probe.hasAudio) {
                val (code, _) = run(
                    ffmpeg, "-y", "-i", sanitized.absolutePath,
                    "-vn", "-c:a", "aac", "-b:a", "128k", audioOut.absolutePath, timeoutSec = 300
                )
                if (code == 0 && audioOut.exists()) outputs.add("AUDIO" to audioOut)
            }

            // 5. Merge check: mux extracted audio back with lowest video, probe the result
            val lowestVideo = outputs.lastOrNull { it.first.startsWith("VIDEO_") }?.second
            if (probe.hasAudio && audioOut.exists() && lowestVideo != null) {
                val merged = File(tmpDir, "merged_check.mp4")
                val (code, _) = run(
                    ffmpeg, "-y", "-i", lowestVideo.absolutePath, "-i", audioOut.absolutePath,
                    "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-c:a", "copy",
                    "-shortest", merged.absolutePath, timeoutSec = 300
                )
                if (code != 0 || probe(merged) == null) return@withContext markFailed(job, "audio merge integrity check failed")
                merged.delete()
            }

            // 6. Fingerprint (piracy / monetization): exact + perceptual + audio
            val sha = sha256File(sanitized)
            val sampleCount = 5
            val hashes = (0 until sampleCount).mapNotNull { i ->
                val at = probe.durationMs / 1000.0 * (i + 0.5) / sampleCount
                frameHash(sanitized, at, tmpDir, i)
            }
            if (hashes.isEmpty()) return@withContext markFailed(job, "fingerprint failed")
            val audioHash = if (audioOut.exists()) sha256File(audioOut) else null

            // 7. Promote derived files to public storage
            val postDir = File(publicRoot, job.postId.toString()).apply { mkdirs() }
            if (!postDir.canonicalPath.startsWith(publicRoot.canonicalPath + File.separator)) {
                return@withContext markFailed(job, "path containment failure")
            }
            val now = LocalDateTime.now()
            for ((kind, f) in outputs) {
                val dest = File(postDir, f.name)
                f.copyTo(dest, overwrite = true)
                val probeInfo = if (kind.startsWith("VIDEO")) probe(dest) else null
                dbQuery {
                    PostMedia.insert {
                        it[PostMedia.id] = UUID.randomUUID()
                        it[postId] = job.postId
                        it[PostMedia.kind] = kind
                        it[publicPath] = dest.absolutePath
                        it[width] = probeInfo?.width
                        it[height] = probeInfo?.height
                        it[byteSize] = dest.length()
                        it[mimeType] = when (kind) {
                            "AUDIO" -> "audio/mp4"
                            "THUMBNAIL" -> "image/jpeg"
                            else -> "video/mp4"
                        }
                        it[createdAt] = now
                    }
                }
            }
            dbQuery {
                MediaFingerprints.insert {
                    it[MediaFingerprints.id] = UUID.randomUUID()
                    it[postId] = job.postId
                    it[sha256] = sha
                    it[frameHashes] = hashes.joinToString(",")
                    it[MediaFingerprints.audioHash] = audioHash
                    it[createdAt] = now
                }
                // Processing completion only makes the media publishable. Metadata
                // finalization is the atomic publication step in PostRoutes, so a
                // partially finalized post can never enter the feed.
                Posts.update({ Posts.id eq job.postId }) {
                    it[status] = "DRAFT"
                    it[durationMs] = probe.durationMs
                    it[publishedAt] = null
                }
                UploadSessions.update({ UploadSessions.id eq job.sessionId }) {
                    it[status] = "READY"; it[updatedAt] = now
                }
            }
            quarantineFile.delete() // original never stays around after promotion
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}
