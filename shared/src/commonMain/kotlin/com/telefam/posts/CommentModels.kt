package com.telefam.posts

import kotlinx.serialization.Serializable

/** Wire DTOs for the comments feature — mirror the backend's CommentService DTOs. */

@Serializable
data class CommentMentionDto(val userId: String, val username: String? = null)

@Serializable
data class CommentDto(
    val commentId: String,
    val postId: String,
    val parentId: String? = null,
    val rootId: String,
    val authorId: String,
    val authorUsername: String? = null,
    val authorFullName: String? = null,
    val authorAvatarUrl: String? = null,
    /** TEXT | PHOTO | STICKER | GIF */
    val kind: String = "TEXT",
    val body: String? = null,
    val mediaUrl: String? = null,
    val stickerUrl: String? = null,
    val mentions: List<CommentMentionDto> = emptyList(),
    val likeCount: Long = 0,
    val replyCount: Long = 0,
    val viewerLiked: Boolean = false,
    val viewerIsAuthor: Boolean = false,
    val pinnedByOwner: Boolean = false,
    val viewerIsPostOwner: Boolean = false,
    val edited: Boolean = false,
    val deleted: Boolean = false,
    val starTotal: Long = 0,
    val createdAt: String = ""
) {
    val displayName: String get() = authorFullName?.takeIf { it.isNotBlank() } ?: authorUsername ?: "Telefam user"
}

@Serializable
data class CommentPageDto(
    val items: List<CommentDto>,
    val nextCursor: String? = null,
    val pinned: CommentDto? = null,
    val commenting: String = "EVERYONE",
    val viewerCanComment: Boolean = true,
    val viewerIsPostOwner: Boolean = false,
    val totalCount: Long = 0,
    val serverTime: String = ""
)

@Serializable
data class CreateCommentRequest(
    val body: String? = null,
    val parentId: String? = null,
    val kind: String = "TEXT",
    val mediaId: String? = null,
    val stickerUrl: String? = null
)

@Serializable
data class EditCommentRequest(val body: String)

@Serializable
data class CommentReportRequest(val reason: String, val details: String? = null)

@Serializable
data class GiftStarsRequest(val stars: Long)

@Serializable
data class GiftStarsResponse(
    val balanceStars: Long,
    val giftId: String,
    val commentStarTotal: Long
)

@Serializable
data class CommentLikeResponse(val likeCount: Long)

/** Thrown when the star wallet can't cover a gift; the UI redirects to Buy Stars. */
class InsufficientStarsException(val balanceStars: Long, val requiredStars: Long) : Exception("Not enough stars")

/** Comment report reasons — same fixed set as post reports, keeps moderation machine-readable. */
val COMMENT_REPORT_REASONS = listOf(
    "Spam or misleading",
    "Nudity or sexual content",
    "Hate speech or harassment",
    "Violence or dangerous acts",
    "Self-harm",
    "Copyright infringement",
    "Scam or fraud",
    "Something else"
)
