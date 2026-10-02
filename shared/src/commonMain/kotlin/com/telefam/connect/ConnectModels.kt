package com.telefam.connect

import kotlinx.serialization.Serializable

/** Wire DTOs mirroring backend ConnectService responses exactly. */

@Serializable
data class ConnectUserDto(
    val userId: String,
    val username: String? = null,
    val fullName: String? = null,
    val avatarUrl: String? = null,
    val mutualConnections: Int = 0,
    val suggestionReason: String? = null, // FROM_CONTACTS | FRIEND_OF_FRIEND | NEARBY | NEW_USER | SEARCH
    val friendDegree: Int? = null,
    val distanceKm: Double? = null,
    val isNewUser: Boolean = false,
    val viewerFollowing: Boolean = false,
    val viewerFollowedBy: Boolean = false,
    val isFriend: Boolean = false,
    /** Backend-granted, unexpired verification badge. */
    val isVerified: Boolean = false,
    /** True for the backend-controlled official Telefam account (follow-only, no calls). */
    val isOfficialAccount: Boolean = false
) {
    val displayName: String get() = fullName?.takeIf { it.isNotBlank() } ?: username ?: "Telefam user"
    val displayHandle: String get() = username?.let { "@$it" } ?: ""
}

@Serializable
data class ConnectPageDto(
    val items: List<ConnectUserDto>,
    val nextOffset: Int? = null,
    val serverTime: String = ""
)

@Serializable
data class ProfileDetailsDto(
    val userId: String,
    val username: String? = null,
    val fullName: String? = null,
    val bio: String? = null,
    val locationName: String? = null,
    val avatarUrl: String? = null,
    /** Optional public website link shown on the profile. */
    val website: String? = null,
    val profileUrl: String = "",
    val followerCount: Long = 0,
    val followingCount: Long = 0,
    val friendsCount: Long = 0,
    val likesCount: Long = 0,
    val postsCount: Long = 0,
    val subscriberCount: Long = 0,
    val isOwner: Boolean = false,
    val viewerFollowing: Boolean = false,
    val viewerFollowedBy: Boolean = false,
    val viewerSubscribed: Boolean = false,
    val isFriend: Boolean = false,
    val isBlockedByViewer: Boolean = false,
    /** Backend-granted, unexpired verification badge. */
    val isVerified: Boolean = false,
    /** True for the backend-controlled official Telefam account (followers-only profile). */
    val isOfficialAccount: Boolean = false,
    // The server's internal trust/risk classification is intentionally not exposed here.
    val joinedAt: String = ""
) {
    val displayName: String get() = fullName?.takeIf { it.isNotBlank() } ?: username ?: "Telefam user"
    val displayHandle: String get() = username?.let { "@$it" } ?: ""
}

@Serializable
data class FollowStateDto(
    val viewerFollowing: Boolean,
    val viewerFollowedBy: Boolean,
    val isFriend: Boolean,
    val followerCount: Long
)

@Serializable
data class ContactHashEntry(val type: String, val value: String)

@Serializable
data class ContactHashUploadRequest(val hashes: List<ContactHashEntry>, val replaceAll: Boolean = true)

@Serializable
data class ContactHashUploadResponse(val stored: Int, val matchedUsers: Int)

@Serializable
data class RelatedSearchDto(val terms: List<String>)

/** Follow/unfollow button state machine shared by Contacts, Profile and lists. */
enum class FollowAction {
    /** Not following; they don't follow you. */
    FOLLOW,
    /** Not following; they follow you — tapping creates a mutual (friend) relationship. */
    FOLLOW_BACK,
    /** You follow them (one-way). */
    FOLLOWING,
    /** Mutual follow — friends. */
    FRIENDS;

    companion object {
        fun of(viewerFollowing: Boolean, viewerFollowedBy: Boolean, isFriend: Boolean): FollowAction = when {
            isFriend || (viewerFollowing && viewerFollowedBy) -> FRIENDS
            viewerFollowing -> FOLLOWING
            viewerFollowedBy -> FOLLOW_BACK
            else -> FOLLOW
        }
    }
}
