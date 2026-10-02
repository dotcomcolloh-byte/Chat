package com.telefam.posts

import kotlinx.serialization.Serializable

/** Feed tabs matching the reference screen. Wire values are the URL path segments. */
enum class FeedTab(val path: String, val label: String) {
    FOR_YOU("for-you", "For You"),
    FRIENDS("friends", "Friends"),
    FOLLOWING("following", "Following"),
    NEW_CREATORS("new-creators", "New creators")
}

@Serializable
data class FeedVariantDto(val kind: String, val url: String, val width: Int = 0, val height: Int = 0)

@Serializable
data class FeedPostDto(
    val postId: String,
    val ownerId: String,
    val ownerUsername: String? = null,
    val ownerFullName: String? = null,
    val ownerAvatarUrl: String? = null,
    val ownerFollowerCount: Long = 0,
    val description: String? = null,
    val hashtags: List<String> = emptyList(),
    val taggedUserIds: List<String> = emptyList(),
    val commenting: String = "EVERYONE",
    val privacy: String = "PUBLIC",
    val songTitle: String? = null,
    val songArtist: String? = null,
    val songPreviewUrl: String? = null,
    val songArtworkUrl: String? = null,
    val durationMs: Long = 0,
    val createdAt: String = "",
    val likeCount: Long = 0,
    val viewCount: Long = 0,
    val reshareCount: Long = 0,
    val commentCount: Long = 0,
    val viewerLiked: Boolean = false,
    val viewerSaved: Boolean = false,
    val viewerFollowing: Boolean = false,
    val isOwner: Boolean = false,
    /** Backend-granted, unexpired badge for the post owner. */
    val ownerVerified: Boolean = false,
    val downloadsAllowed: Boolean = true,
    val subscriberOnly: Boolean = false,
    val reshareOfId: String? = null,
    val thumbnailUrl: String? = null,
    val shareUrl: String = "",
    val variants: List<FeedVariantDto> = emptyList(),
    /** Sponsored (paid boost) markers — set only by the server when a campaign injected this post. */
    val sponsored: Boolean = false,
    val sponsoredCampaignId: String? = null,
    /** CTA label + behavior for the sponsored button (Watch / Visit / Follow / Buy / ...). */
    val sponsoredCta: String? = null,
    /** WATCH | OPEN_LINK | FOLLOW */
    val sponsoredAction: String? = null,
    val sponsoredLink: String? = null
) {
    val displayName: String get() = ownerFullName?.takeIf { it.isNotBlank() } ?: ownerUsername ?: "Telefam user"
    val displayHandle: String get() = ownerUsername ?: ownerId.take(8)

    /**
     * Auto quality selection across every aspect ratio in the world: the server encodes
     * variants at fixed HEIGHTS preserving the source's native ratio (scale=-2:h), so
     * portrait/landscape/square/ultrawide all just work — we only ever pick by height.
     * [tier] 1 = constrained/metered, 2 = normal, 3 = fast unmetered.
     */
    fun bestVariantUrl(tier: Int): String? {
        if (variants.isEmpty()) return null
        val target = when (tier) { 1 -> 480; 3 -> 1080; else -> 720 }
        return variants.minByOrNull { v ->
            kotlin.math.abs(v.height - target) * 10 + (if (v.height > target) 5 else 0)
        }?.url
    }

    fun thumbnailAbsoluteUrl(baseUrl: String): String? =
        thumbnailUrl?.let { if (it.startsWith("http")) it else baseUrl.trimEnd('/') + it }
}

@Serializable
data class FeedPageDto(
    val items: List<FeedPostDto>,
    val nextCursor: String? = null,
    val serverTime: String = ""
)

@Serializable
data class FeedCountResponse(val count: Long)

@Serializable
data class FeedViewRecordRequest(val watchedMs: Long, val completed: Boolean)

@Serializable
data class FeedReshareRequest(val channel: String = "EXTERNAL")

@Serializable
data class FeedReportRequest(val reason: String, val details: String? = null)

@Serializable
data class FeedEditPostRequest(
    val description: String? = null,
    val hashtags: List<String>? = null,
    val taggedUserIds: List<String>? = null,
    val commenting: String? = null,
    val privacy: String? = null,
    val embedAllowed: Boolean? = null,
    val downloadsAllowed: Boolean? = null,
    val subscriberOnly: Boolean? = null
)

@Serializable
data class FeedDownloadsAllowedRequest(val allowed: Boolean)

/** Report form reasons — fixed set keeps moderation triage machine-readable. */
val FEED_REPORT_REASONS = listOf(
    "Spam or misleading",
    "Nudity or sexual content",
    "Hate speech or harassment",
    "Violence or dangerous acts",
    "Self-harm",
    "Copyright infringement",
    "Scam or fraud",
    "Something else"
)
