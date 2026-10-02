package com.telefam.data.api

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

@Serializable data class PrivacySettingsDto(
    val messageRequests: String = "ANYONE",
    val whoCanCallMe: String = "ANYONE",
    val whoCanScreenshotChats: String = "ANYONE",
    val whoCanShareChats: String = "ANYONE",
    val whoCanCopyMessages: String = "ANYONE",
    val whoCanDownloadMedia: String = "ANYONE",
    val whoCanSeeLastSeen: String = "ANYONE",
    val chatsTheme: String = "LIGHT",
    val messageBubbleColour: String = "RED",
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

@Serializable data class UpdatePrivacyFieldBody(val field: String, val value: String)
@Serializable data class MessageRequestDto(val id: String, val fromUserId: String, val fromName: String?, val previewText: String?, val createdAt: String, val fromVerified: Boolean = false)
@Serializable data class RespondRequestBody(val accept: Boolean)
@Serializable data class BlockedUserDto(
    val userId: String,
    val name: String?,
    val username: String? = null,
    val avatarUrl: String? = null,
    val isVerified: Boolean = false,
)
@Serializable data class ArchivedChatDto(val chatId: String, val archivedAt: String)

/**
 * No manual Authorization header handling here anymore — `client` already carries
 * Ktor's real Auth/bearer plugin (see createTelefamHttpClient), which attaches the
 * current access token to every call and transparently refreshes it on a 401.
 */
class PrivacyApi(private val client: HttpClient) {

    suspend fun getPrivacySettings() = client.get("${ApiConfig.baseUrl}/api/privacy")

    suspend fun updatePrivacyField(field: String, value: String) =
        client.put("${ApiConfig.baseUrl}/api/privacy") {
            contentType(ContentType.Application.Json); setBody(UpdatePrivacyFieldBody(field, value))
        }

    suspend fun listMessageRequests() = client.get("${ApiConfig.baseUrl}/api/social/requests")

    suspend fun respondToRequest(requestId: String, accept: Boolean) =
        client.post("${ApiConfig.baseUrl}/api/social/requests/$requestId/respond") {
            contentType(ContentType.Application.Json); setBody(RespondRequestBody(accept))
        }

    suspend fun listBlocked() = client.get("${ApiConfig.baseUrl}/api/social/blocked")
    suspend fun unblock(userId: String) = client.delete("${ApiConfig.baseUrl}/api/social/blocked/$userId")

    /** Two-sided block state for a conversation: { iBlocked, blockedMe }. */
    @Serializable
    data class BlockStatusDto(val iBlocked: Boolean, val blockedMe: Boolean)

    suspend fun blockStatus(peerId: String) =
        client.get("${ApiConfig.baseUrl}/api/social/blocked-status/$peerId")

    suspend fun listArchived() = client.get("${ApiConfig.baseUrl}/api/social/archived")
    suspend fun unarchive(chatId: String) = client.delete("${ApiConfig.baseUrl}/api/social/archived/$chatId")
}
