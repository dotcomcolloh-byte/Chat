package com.telefam.comments

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.datetime

/**
 * Post comments, threaded replies, comment likes, reports and comment star gifts.
 * Rules:
 *  - Comments are soft-deleted (deletedAt) so reply threads never collapse under readers.
 *  - Star gifts move ONLY through the campaign star ledger (StarWallets +
 *    StarLedgerEntries) with the same atomic-conditional-debit + idempotency-key
 *    pattern used by campaign spend — a retry can never double-charge the sender.
 *  - The pin slot is per post and owner-controlled; enforced by unpinning the previous
 *    pin inside the same transaction (at most one pinned comment per post).
 */
object PostComments : UUIDTable("post_comments") {
    val postId = uuid("post_id").index()
    val authorId = uuid("author_id").index()
    /** Immediate parent for replies; NULL for top-level comments. */
    val parentId = uuid("parent_id").nullable().index()
    /** Top-level comment of the thread (equals own id for top-level rows) — fast thread counts. */
    val rootId = uuid("root_id").index()
    /** TEXT | PHOTO | STICKER | GIF */
    val kind = varchar("kind", 12).default("TEXT")
    val body = varchar("body", 2000).nullable()
    /** MediaAssets id for kind=PHOTO (uploaded via the standard /api/media/upload pipeline). */
    val mediaId = uuid("media_id").nullable()
    /** Remote URL for kind=STICKER | GIF (GIPHY renditions, same source as chats). */
    val stickerUrl = varchar("sticker_url", 600).nullable()
    /** JSON array of mentioned user ids, parsed + validated server-side from the body. */
    val mentions = varchar("mentions", 2000).default("[]")
    val pinnedByOwner = bool("pinned_by_owner").default(false)
    val editedAt = datetime("edited_at").nullable()
    val deletedAt = datetime("deleted_at").nullable()
    val createdAt = datetime("created_at")

    init {
        index("idx_comments_post_root_created", false, postId, rootId, createdAt)
        index("idx_comments_post_pinned", false, postId, pinnedByOwner)
    }
}

object CommentLikes : UUIDTable("comment_likes") {
    val commentId = uuid("comment_id").index()
    val userId = uuid("user_id")
    val createdAt = datetime("created_at")
    init { uniqueIndex(commentId, userId) }
}

/** Comment-level reports with the same structured form used for posts. */
object CommentReports : UUIDTable("comment_reports") {
    val commentId = uuid("comment_id").index()
    val reporterId = uuid("reporter_id")
    val reason = varchar("reason", 60)
    val details = varchar("details", 1000).nullable()
    val status = varchar("status", 20).default("OPEN") // OPEN | REVIEWED | ACTIONED
    val createdAt = datetime("created_at")
}

/** One row per star gift attached to a comment; ledger entries point here. */
object CommentStarGifts : UUIDTable("comment_star_gifts") {
    val commentId = uuid("comment_id").index()
    val senderId = uuid("sender_id").index()
    val recipientId = uuid("recipient_id").index()
    val stars = long("stars")
    val idempotencyKey = varchar("idempotency_key", 128).uniqueIndex()
    val createdAt = datetime("created_at")
}
