package com.telefam.posts

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.datetime

/**
 * Tables for the Create Post video pipeline.
 * Flow: upload_sessions (chunked intake) -> quarantined file -> worker job ->
 *       post_media rows (one per derived quality + extracted audio) -> posts row.
 * Nothing in quarantine is ever served; only rows whose status reached READY
 * and whose files were moved into the public storage root are deliverable,
 * and then only via signed URLs.
 */

object UploadSessions : UUIDTable("upload_sessions") {
    val ownerId = uuid("owner_id")
    /** Sanitized display-only filename — never used as a path component. */
    val originalFilename = varchar("original_filename", 120)
    val declaredMime = varchar("declared_mime", 60)
    val declaredSizeBytes = long("declared_size_bytes")
    val chunkSizeBytes = integer("chunk_size_bytes")
    /** Bytes received so far — authoritative source for resume. */
    val receivedBytes = long("received_bytes").default(0)
    /** RECEIVING -> QUARANTINED -> PROCESSING -> READY | REJECTED | FAILED */
    val status = varchar("status", 20).default("RECEIVING")
    /** Path inside the quarantine root. Random UUID name, extension-whitelisted. */
    val quarantinePath = varchar("quarantine_path", 512).nullable()
    val rejectReason = varchar("reject_reason", 255).nullable()
    /** SHA-256 of the fully-assembled file, computed server-side after assembly. */
    val sha256 = varchar("sha256", 64).nullable()
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
}

object Posts : UUIDTable("posts") {
    val ownerId = uuid("owner_id")
    val uploadSessionId = uuid("upload_session_id").nullable()
    val description = varchar("description", 2000).nullable()
    val hashtags = varchar("hashtags", 500).default("") // space-separated, server-normalized
    /** JSON array of tagged friend user ids — validated server-side against real users. */
    val taggedUserIds = varchar("tagged_user_ids", 2000).default("[]")
    val commenting = varchar("commenting", 20).default("EVERYONE") // EVERYONE | FRIENDS | OFF
    val privacy = varchar("privacy", 20).default("PUBLIC")        // PUBLIC | FRIENDS | PRIVATE
    val embedAllowed = bool("embed_allowed").default(true)         // Embed / iframe toggle
    val songTitle = varchar("song_title", 200).nullable()
    val songArtist = varchar("song_artist", 200).nullable()
    val songPreviewUrl = varchar("song_preview_url", 500).nullable()
    val songArtworkUrl = varchar("song_artwork_url", 500).nullable()
    /** DRAFT (uploading/processing) -> PUBLISHED (ready) | REJECTED | DELETED (owner removed) */
    val status = varchar("status", 20).default("DRAFT")
    val trimStartMs = long("trim_start_ms").default(0)
    val trimEndMs = long("trim_end_ms").default(0)
    val durationMs = long("duration_ms").default(0)
    /** Owner switch: when false, the Download action is hidden from everyone else in feeds. */
    val downloadsAllowed = bool("downloads_allowed").default(true)
    /** Requires an active paid subscription to the post owner; enforced server-side. */
    val subscriberOnly = bool("subscriber_only").default(false)
    /** Set when this post is a reshare of another post; the original keeps its own counts. */
    val reshareOfId = uuid("reshare_of_id").nullable()
    val createdAt = datetime("created_at")
    val publishedAt = datetime("published_at").nullable()

    init {
        // Feed pagination and profile feeds always filter by publication state and
        // order by publication time. The owner index supports profile/new-creator queries.
        index("idx_posts_status_published", false, status, publishedAt)
        index("idx_posts_owner_published", false, ownerId, publishedAt)
    }
}

/** Follow graph — powers the Following feed tab and the + button on feed avatars. */
object Follows : UUIDTable("follows") {
    val followerId = uuid("follower_id")
    val followeeId = uuid("followee_id")
    val createdAt = datetime("created_at")
    init { uniqueIndex(followerId, followeeId) }
}

object PostLikes : UUIDTable("post_likes") {
    val postId = uuid("post_id")
    val userId = uuid("user_id")
    val createdAt = datetime("created_at")
    init { uniqueIndex(postId, userId) }
}

object PostSaves : UUIDTable("post_saves") {
    val postId = uuid("post_id")
    val userId = uuid("user_id")
    val createdAt = datetime("created_at")
    init { uniqueIndex(postId, userId) }
}

/** One row per (viewer, post): view counts are deduped per user; watch time accumulates. */
object PostViews : UUIDTable("post_views") {
    val postId = uuid("post_id")
    val viewerId = uuid("viewer_id")
    val watchedMs = long("watched_ms").default(0)
    val completed = bool("completed").default(false)
    val viewedAt = datetime("viewed_at")
    init { uniqueIndex(postId, viewerId) }
}

object PostReshares : UUIDTable("post_reshares") {
    val postId = uuid("post_id")
    val userId = uuid("user_id")
    /** EXTERNAL (system share sheet) | CHAT (sent into a Telefam chat) | REPOST */
    val channel = varchar("channel", 20).default("EXTERNAL")
    val createdAt = datetime("created_at")
}

/** Feed personalization: never show this post to this user again. */
object NotInterested : UUIDTable("not_interested") {
    val userId = uuid("user_id")
    val postId = uuid("post_id")
    val createdAt = datetime("created_at")
    init { uniqueIndex(userId, postId) }
}

/** Post-level reports with a structured form (reason + free-text details). */
object PostReports : UUIDTable("post_reports") {
    val postId = uuid("post_id")
    val reporterId = uuid("reporter_id")
    val reason = varchar("reason", 60)
    val details = varchar("details", 1000).nullable()
    val status = varchar("status", 20).default("OPEN") // OPEN | REVIEWED | ACTIONED
    val createdAt = datetime("created_at")
}

/** One row per derived artifact: each video quality, the extracted audio track, the thumbnail. */
object PostMedia : UUIDTable("post_media") {
    val postId = uuid("post_id")
    val kind = varchar("kind", 20) // VIDEO_1080 | VIDEO_720 | VIDEO_480 | AUDIO | THUMBNAIL
    /** Path inside the PUBLIC storage root — quarantine paths never appear here. */
    val publicPath = varchar("public_path", 512)
    val width = integer("width").nullable()
    val height = integer("height").nullable()
    val byteSize = long("byte_size")
    val mimeType = varchar("mime_type", 60)
    val createdAt = datetime("created_at")
}

/** Piracy / monetization fingerprinting: every published video gets both an exact
 *  hash (re-upload detection) and a perceptual fingerprint (near-duplicate / re-encode
 *  detection) sampled from multiple frames, so cropped/re-compressed copies still match. */
object MediaFingerprints : UUIDTable("media_fingerprints") {
    val postId = uuid("post_id")
    val sha256 = varchar("sha256", 64)
    /** Comma-joined 64-bit perceptual hashes (hex) from N sampled frames. */
    val frameHashes = varchar("frame_hashes", 2000)
    val audioHash = varchar("audio_hash", 64).nullable() // hash of extracted audio for audio reuse detection
    val createdAt = datetime("created_at")
}
