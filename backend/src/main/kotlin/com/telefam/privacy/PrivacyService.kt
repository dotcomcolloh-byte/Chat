package com.telefam.privacy

import com.telefam.db.PrivacySettings
import com.telefam.db.RlsContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.util.*

@Serializable
data class PrivacySettingsDto(
    val messageRequests: String = "ANYONE",
    val whoCanCallMe: String = "ANYONE",
    val whoCanScreenshotChats: String = "ANYONE",
    val whoCanShareChats: String = "ANYONE",
    val whoCanCopyMessages: String = "ANYONE",
    val whoCanDownloadMedia: String = "ANYONE",
    val whoCanSeeLastSeen: String = "ANYONE",
    val chatsTheme: String = "LIGHT",
    val messageBubbleColour: String = "RED",
    // --- Detailed visibility center ---
    val privateAccount: Boolean = false,
    val whoCanFollowMe: String = "ANYONE",
    val allowProfileDiscovery: Boolean = true,
    val whoCanSeePosts: String = "ANYONE",
    val whoCanSeeLikes: String = "ANYONE",
    val whoCanComment: String = "ANYONE",
    val whoCanSeeFollowing: String = "ANYONE",
    val whoCanSeeFollowers: String = "ANYONE",
    val whoCanMentionMe: String = "ANYONE",
    val whoCanTagMe: String = "ANYONE",
    val whoCanSeeActivity: String = "ANYONE"
)

/** [onFieldChanged] lets other services (e.g. realtime presence) react immediately when a setting changes. */
class PrivacyService(private val onFieldChanged: suspend (userId: UUID, field: String) -> Unit = { _, _ -> }) {

    suspend fun get(userId: UUID): PrivacySettingsDto = RlsContext.asUser(userId) {
        val row = PrivacySettings.selectAll().where { PrivacySettings.userId eq userId }.singleOrNull()
        if (row == null) {
            createDefault(userId)
            PrivacySettingsDto()
        } else {
            PrivacySettingsDto(
                messageRequests = row[PrivacySettings.messageRequests],
                whoCanCallMe = row[PrivacySettings.whoCanCallMe],
                whoCanScreenshotChats = row[PrivacySettings.whoCanScreenshotChats],
                whoCanShareChats = row[PrivacySettings.whoCanShareChats],
                whoCanCopyMessages = row[PrivacySettings.whoCanCopyMessages],
                whoCanDownloadMedia = row[PrivacySettings.whoCanDownloadMedia],
                whoCanSeeLastSeen = row[PrivacySettings.whoCanSeeLastSeen],
                chatsTheme = row[PrivacySettings.chatsTheme],
                messageBubbleColour = row[PrivacySettings.messageBubbleColour],
                privateAccount = row[PrivacySettings.privateAccount],
                whoCanFollowMe = row[PrivacySettings.whoCanFollowMe],
                allowProfileDiscovery = row[PrivacySettings.allowProfileDiscovery],
                whoCanSeePosts = row[PrivacySettings.whoCanSeePosts],
                whoCanSeeLikes = row[PrivacySettings.whoCanSeeLikes],
                whoCanComment = row[PrivacySettings.whoCanComment],
                whoCanSeeFollowing = row[PrivacySettings.whoCanSeeFollowing],
                whoCanSeeFollowers = row[PrivacySettings.whoCanSeeFollowers],
                whoCanMentionMe = row[PrivacySettings.whoCanMentionMe],
                whoCanTagMe = row[PrivacySettings.whoCanTagMe],
                whoCanSeeActivity = row[PrivacySettings.whoCanSeeActivity]
            )
        }
    }

    private fun createDefault(userId: UUID) {
        PrivacySettings.insert {
            it[PrivacySettings.userId] = userId
            it[PrivacySettings.updatedAt] = LocalDateTime.now()
        }
    }

    /** field must be one of the known column keys below; value is validated against its enum before this is called. */
    suspend fun updateField(userId: UUID, field: String, value: String) {
        applyField(userId, field, value)
        onFieldChanged(userId, field)
    }

    private suspend fun applyField(userId: UUID, field: String, value: String) = RlsContext.asUser(userId) {
        val exists = PrivacySettings.selectAll().where { PrivacySettings.userId eq userId }.any()
        if (!exists) createDefault(userId)

        PrivacySettings.update({ PrivacySettings.userId eq userId }) { stmt ->
            when (field) {
                "messageRequests" -> stmt[PrivacySettings.messageRequests] = value
                "whoCanCallMe" -> stmt[PrivacySettings.whoCanCallMe] = value
                "whoCanScreenshotChats" -> stmt[PrivacySettings.whoCanScreenshotChats] = value
                "whoCanShareChats" -> stmt[PrivacySettings.whoCanShareChats] = value
                "whoCanCopyMessages" -> stmt[PrivacySettings.whoCanCopyMessages] = value
                "whoCanDownloadMedia" -> stmt[PrivacySettings.whoCanDownloadMedia] = value
                "whoCanSeeLastSeen" -> stmt[PrivacySettings.whoCanSeeLastSeen] = value
                "chatsTheme" -> stmt[PrivacySettings.chatsTheme] = value
                "messageBubbleColour" -> stmt[PrivacySettings.messageBubbleColour] = value
                "privateAccount" -> stmt[PrivacySettings.privateAccount] = value.toBoolean()
                "allowProfileDiscovery" -> stmt[PrivacySettings.allowProfileDiscovery] = value.toBoolean()
                "whoCanFollowMe" -> stmt[PrivacySettings.whoCanFollowMe] = value
                "whoCanSeePosts" -> stmt[PrivacySettings.whoCanSeePosts] = value
                "whoCanSeeLikes" -> stmt[PrivacySettings.whoCanSeeLikes] = value
                "whoCanComment" -> stmt[PrivacySettings.whoCanComment] = value
                "whoCanSeeFollowing" -> stmt[PrivacySettings.whoCanSeeFollowing] = value
                "whoCanSeeFollowers" -> stmt[PrivacySettings.whoCanSeeFollowers] = value
                "whoCanMentionMe" -> stmt[PrivacySettings.whoCanMentionMe] = value
                "whoCanTagMe" -> stmt[PrivacySettings.whoCanTagMe] = value
                "whoCanSeeActivity" -> stmt[PrivacySettings.whoCanSeeActivity] = value
                else -> error("Unknown privacy field: $field")
            }
            stmt[PrivacySettings.updatedAt] = LocalDateTime.now()
        }
    }

    companion object {
        val ACCESS_LEVEL_FIELDS = setOf(
            "messageRequests", "whoCanCallMe", "whoCanScreenshotChats",
            "whoCanShareChats", "whoCanCopyMessages", "whoCanDownloadMedia", "whoCanSeeLastSeen",
            "whoCanFollowMe", "whoCanSeePosts", "whoCanSeeLikes", "whoCanComment",
            "whoCanSeeFollowing", "whoCanSeeFollowers", "whoCanMentionMe", "whoCanTagMe", "whoCanSeeActivity"
        )
        /** Boolean toggles — value must be "true"/"false". */
        val BOOL_FIELDS = setOf("privateAccount", "allowProfileDiscovery")
        const val THEME_FIELD = "chatsTheme"
        const val BUBBLE_FIELD = "messageBubbleColour"
    }
}
