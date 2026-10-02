package com.telefam.data.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

@Serializable
data class NotificationDto(
    val id: String,
    val type: String,
    val actorId: String? = null,
    val actorName: String? = null,
    /** NEW_FOLLOWER events the viewer can answer with "Follow back". */
    val canFollowBack: Boolean = false,
    val title: String,
    val body: String,
    /** POST | COMMENT | PROFILE | WALLET | SUBSCRIPTIONS | NOTIFICATIONS — tap redirect target. */
    val targetType: String? = null,
    val targetId: String? = null,
    val read: Boolean,
    val createdAt: String
)

@Serializable
data class NotificationPageDto(
    val items: List<NotificationDto>,
    val nextOffset: Int? = null,
    val unreadCount: Long = 0
)

@Serializable
data class SystemMessageDto(
    val id: String,
    val text: String,
    val read: Boolean,
    val createdAt: String
)

@Serializable
data class SystemMessagePageDto(
    val items: List<SystemMessageDto>,
    val nextOffset: Int? = null
)

/** Pinned system-conversation headers for the inbox list. */
@Serializable
data class SystemInboxDto(
    val officialUserId: String,
    val officialName: String,
    val officialUsername: String,
    val officialVerified: Boolean = true,
    val officialLastMessage: String? = null,
    val officialLastAt: String? = null,
    val officialUnreadCount: Long = 0,
    val notificationsLastTitle: String? = null,
    val notificationsLastBody: String? = null,
    val notificationsLastAt: String? = null,
    val notificationsUnreadCount: Long = 0
)

/** Client for /api/notifications + /api/inbox/system. */
class NotificationsApi(private val client: HttpClient) {

    private val base get() = ApiConfig.baseUrl.trimEnd('/')

    suspend fun list(filter: String = "all", offset: Int = 0, limit: Int = 30): NotificationPageDto =
        client.get("$base/api/notifications") {
            parameter("filter", filter); parameter("offset", offset); parameter("limit", limit)
        }.body()

    suspend fun unreadCount(): Long =
        client.get("$base/api/notifications/unread-count").body<Map<String, Long>>()["unreadCount"] ?: 0

    suspend fun markRead(id: String) {
        client.post("$base/api/notifications/$id/read")
    }

    suspend fun markAllRead() {
        client.post("$base/api/notifications/read-all")
    }

    /** The per-notification 3-dot "Delete". */
    suspend fun delete(id: String): Boolean =
        client.delete("$base/api/notifications/$id").status.isSuccess()

    suspend fun systemInbox(): SystemInboxDto =
        client.get("$base/api/inbox/system").body()

    suspend fun systemMessages(offset: Int = 0, limit: Int = 50): SystemMessagePageDto =
        client.get("$base/api/inbox/system/messages") {
            parameter("offset", offset); parameter("limit", limit)
        }.body()

    suspend fun markSystemMessagesRead() {
        client.post("$base/api/inbox/system/read-all")
    }
}
