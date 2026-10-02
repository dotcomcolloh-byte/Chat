package com.telefam.posts

import com.telefam.config.AppConfig
import com.telefam.db.DatabaseFactory.dbQuery
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.UUID

/**
 * Chunked, resumable video intake.
 *
 *  - Client calls init() with metadata; server validates size (<=100MB), mime
 *    whitelist, sanitizes the filename, and allocates a session + a random
 *    quarantine filename (UUID.ext — the user's name never touches disk).
 *  - Chunks arrive with an explicit byte offset; the server writes them with a
 *    RandomAccessFile and tracks receivedBytes in the DB. A client interrupted
 *    by network loss calls status(), gets the authoritative receivedBytes, and
 *    resumes from exactly that offset — no re-upload of completed chunks.
 *  - complete() verifies total size, computes SHA-256 of the assembled file,
 *    marks the session QUARANTINED and enqueues a worker job. Quarantined
 *    files are never served; only the worker can promote derived media to
 *    public storage after full validation/transcode/fingerprint.
 */
class ChunkedUploadService(private val jobQueue: VideoJobQueue) {

    private val quarantineRoot: File get() = File(AppConfig.postQuarantinePath).apply { mkdirs() }

    data class InitResult(val sessionId: UUID, val chunkSize: Int, val maxBytes: Long)
    sealed class ChunkResult {
        data class Accepted(val receivedBytes: Long, val complete: Boolean) : ChunkResult()
        data class Conflict(val expectedOffset: Long) : ChunkResult()
        data class Rejected(val reason: String) : ChunkResult()
    }

    suspend fun init(ownerId: UUID, filename: String?, mime: String, sizeBytes: Long): InitResult? {
        if (sizeBytes <= 0 || sizeBytes > AppConfig.postMaxUploadBytes) return null
        if (!FilenameSanitizer.isAllowedVideoMime(mime)) return null
        val ext = FilenameSanitizer.extForMime(mime) ?: return null

        val id = UUID.randomUUID()
        val qFile = File(quarantineRoot, "$id.$ext")
        // Verify containment: the quarantine file must stay inside the quarantine root.
        if (!qFile.canonicalPath.startsWith(quarantineRoot.canonicalPath + File.separator)) return null

        val now = LocalDateTime.now()
        dbQuery {
            UploadSessions.insert {
                it[UploadSessions.id] = id
                it[UploadSessions.ownerId] = ownerId
                it[originalFilename] = FilenameSanitizer.sanitizeDisplayName(filename)
                it[declaredMime] = mime.lowercase()
                it[declaredSizeBytes] = sizeBytes
                it[chunkSizeBytes] = AppConfig.postChunkSizeBytes
                it[receivedBytes] = 0
                it[status] = "RECEIVING"
                it[quarantinePath] = qFile.absolutePath
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
        return InitResult(id, AppConfig.postChunkSizeBytes, AppConfig.postMaxUploadBytes)
    }

    /** Idempotent per-offset: re-sending an already-written chunk is a no-op success. */
    suspend fun writeChunk(ownerId: UUID, sessionId: UUID, offset: Long, bytes: ByteArray): ChunkResult {
        val session = dbQuery {
            UploadSessions.selectAll()
                .where { (UploadSessions.id eq sessionId) and (UploadSessions.ownerId eq ownerId) }
                .firstOrNull()
        } ?: return ChunkResult.Rejected("Unknown session")

        if (session[UploadSessions.status] != "RECEIVING") return ChunkResult.Rejected("Session not open")
        val received = session[UploadSessions.receivedBytes]
        if (offset != received) return ChunkResult.Conflict(received)
        if (received + bytes.size > session[UploadSessions.declaredSizeBytes]) {
            return ChunkResult.Rejected("Exceeds declared size")
        }
        if (bytes.size > AppConfig.postChunkSizeBytes) return ChunkResult.Rejected("Chunk too large")

        val qFile = File(session[UploadSessions.quarantinePath]!!)
        RandomAccessFile(qFile, "rw").use { raf ->
            raf.seek(offset)
            raf.write(bytes)
        }
        val newReceived = received + bytes.size
        val done = newReceived == session[UploadSessions.declaredSizeBytes]
        dbQuery {
            UploadSessions.update({ UploadSessions.id eq sessionId }) {
                it[receivedBytes] = newReceived
                it[updatedAt] = LocalDateTime.now()
            }
        }
        return ChunkResult.Accepted(newReceived, done)
    }

    /** Resume support: tells the client exactly where to continue from. */
    suspend fun status(ownerId: UUID, sessionId: UUID): Pair<String, Long>? = dbQuery {
        UploadSessions.selectAll()
            .where { (UploadSessions.id eq sessionId) and (UploadSessions.ownerId eq ownerId) }
            .firstOrNull()?.let { it[UploadSessions.status] to it[UploadSessions.receivedBytes] }
    }

    suspend fun complete(ownerId: UUID, sessionId: UUID): UUID? {
        val session = dbQuery {
            UploadSessions.selectAll()
                .where { (UploadSessions.id eq sessionId) and (UploadSessions.ownerId eq ownerId) }
                .firstOrNull()
        } ?: return null

        if (session[UploadSessions.status] != "RECEIVING") return null
        if (session[UploadSessions.receivedBytes] != session[UploadSessions.declaredSizeBytes]) return null

        // Exact fingerprint of what was actually assembled on disk.
        val sha = sha256File(File(session[UploadSessions.quarantinePath]!!))

        val postId = UUID.randomUUID()
        val now = LocalDateTime.now()
        dbQuery {
            UploadSessions.update({ UploadSessions.id eq sessionId }) {
                it[status] = "QUARANTINED"
                it[UploadSessions.sha256] = sha
                it[updatedAt] = now
            }
            Posts.insert {
                it[Posts.id] = postId
                it[Posts.ownerId] = ownerId
                it[uploadSessionId] = sessionId
                it[status] = "DRAFT"
                it[createdAt] = now
            }
        }
        // Quarantine -> workers start here.
        jobQueue.enqueue(VideoJob(postId = postId, sessionId = sessionId, ownerId = ownerId))
        return postId
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
