package com.telefam.notifications

import com.telefam.db.DatabaseFactory.dbQuery
import com.telefam.db.Users
import com.telefam.official.OfficialAccountService
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.util.UUID

/** Deep-link targets understood by every client (in-app tap and push tap). */
object NotificationTargets {
    const val POST = "POST"
    const val COMMENT = "COMMENT"
    const val PROFILE = "PROFILE"
    const val WALLET = "WALLET"
    const val SUBSCRIPTIONS = "SUBSCRIPTIONS"
    const val NOTIFICATIONS = "NOTIFICATIONS" // open the notification centre itself
}

object NotificationTypes {
    const val LIKE = "LIKE"
    const val COMMENT = "COMMENT"
    const val REPLY = "REPLY"
    const val MENTION = "MENTION"
    const val TAG = "TAG"
    const val REMOVED_POST = "REMOVED_POST"
    const val SUBSCRIPTION = "SUBSCRIPTION"
    const val PAYMENT_SUCCESS = "PAYMENT_SUCCESS"
    const val NEW_LOGIN = "NEW_LOGIN"
    const val NEW_FOLLOWER = "NEW_FOLLOWER"
    const val WITHDRAWAL_SUCCESS = "WITHDRAWAL_SUCCESS"
    const val SYSTEM = "SYSTEM"
}

@Serializable
data class NotificationDto(
    val id: String,
    val type: String,
    val actorId: String? = null,
    val actorName: String? = null,
    val canFollowBack: Boolean = false,
    val title: String,
    val body: String,
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

/**
 * Combined inbox header for the two pinned system conversations:
 * "Telefam Official" (system messages) and "Notifications" (activity feed).
 */
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

/**
 * Central, singleton notification dispatcher. Services call [notify] without
 * needing constructor wiring; Application.kt installs [pushHook] once so every
 * notification also goes out as an FCM/APNs push with its deep-link payload.
 */
object NotificationService {

    /** Set once at boot: (recipient, title, body, targetType, targetId, notificationId) -> push. */
    @Volatile
    var pushHook: (suspend (UUID, String, String, String?, String?, String) -> Unit)? = null

    suspend fun notify(
        userId: UUID,
        type: String,
        title: String,
        body: String,
        actorId: UUID? = null,
        canFollowBack: Boolean = false,
        targetType: String? = null,
        targetId: String? = null
    ) {
        if (actorId == userId) return // never notify people about their own actions
        val now = LocalDateTime.now()
        val actorName = actorId?.let { aid ->
            dbQuery {
                Users.selectAll().where { Users.id eq aid }.singleOrNull()
                    ?.let { it[Users.fullName] ?: it[Users.username] }
            }
        }
        val id = UUID.randomUUID()
        dbQuery {
            Notifications.insert {
                it[Notifications.id] = id
                it[Notifications.userId] = userId
                it[Notifications.type] = type
                it[Notifications.actorId] = actorId
                it[Notifications.actorName] = actorName
                it[Notifications.canFollowBack] = canFollowBack
                it[Notifications.title] = title.take(200)
                it[Notifications.body] = body.take(500)
                it[Notifications.targetType] = targetType
                it[Notifications.targetId] = targetId
                it[Notifications.read] = false
                it[Notifications.createdAt] = now
            }
        }
        runCatching { pushHook?.invoke(userId, title, body, targetType, targetId, id.toString()) }
    }

    // ---------- queries ----------

    private fun filterTypes(filter: String?): List<String>? = when (filter?.lowercase()) {
        "comments" -> listOf(NotificationTypes.COMMENT, NotificationTypes.REPLY)
        "mentions" -> listOf(NotificationTypes.MENTION, NotificationTypes.TAG)
        "subscriptions" -> listOf(NotificationTypes.SUBSCRIPTION, NotificationTypes.PAYMENT_SUCCESS)
        else -> null // "all"
    }

    suspend fun list(userId: UUID, filter: String?, offset: Int, limit: Int): NotificationPageDto {
        val types = filterTypes(filter)
        val rows = dbQuery {
            val q = Notifications.selectAll().where {
                (Notifications.userId eq userId) and
                    (types?.let { Notifications.type inList it } ?: Op.TRUE)
            }
            q.orderBy(Notifications.createdAt, SortOrder.DESC)
                .limit(limit + 1, offset.toLong())
                .toList()
        }
        val items = rows.take(limit).map { it.toDto() }
        val unread = unreadCount(userId)
        return NotificationPageDto(items, if (rows.size > limit) offset + limit else null, unread)
    }

    suspend fun unreadCount(userId: UUID): Long = dbQuery {
        Notifications.selectAll().where { (Notifications.userId eq userId) and (Notifications.read eq false) }.count()
    }

    suspend fun markRead(userId: UUID, id: UUID): Boolean = dbQuery {
        Notifications.update({ (Notifications.id eq id) and (Notifications.userId eq userId) }) {
            it[read] = true
        } > 0
    }

    suspend fun markAllRead(userId: UUID) {
        dbQuery {
            Notifications.update({ (Notifications.userId eq userId) and (Notifications.read eq false) }) {
                it[read] = true
            }
        }
    }

    /** The per-row 3-dot "Delete" action. */
    suspend fun delete(userId: UUID, id: UUID): Boolean = dbQuery {
        Notifications.deleteWhere { (Notifications.id eq id) and (Notifications.userId eq userId) } > 0
    }

    // ---------- official-account system messages (read-only inbox conversation) ----------

    /** Only the backend (as the official account) ever calls this. */
    suspend fun sendSystemMessage(userId: UUID, text: String) {
        dbQuery {
            SystemMessages.insert {
                it[SystemMessages.id] = UUID.randomUUID()
                it[SystemMessages.userId] = userId
                it[SystemMessages.text] = text.take(1000)
                it[read] = false
                it[createdAt] = LocalDateTime.now()
            }
        }
        runCatching {
            pushHook?.invoke(
                userId, OfficialAccountService.DISPLAY_NAME, text.take(120),
                NotificationTargets.NOTIFICATIONS, "official", "system-${System.currentTimeMillis()}"
            )
        }
    }

    suspend fun systemMessages(userId: UUID, offset: Int, limit: Int): Pair<List<SystemMessageDto>, Int?> {
        val rows = dbQuery {
            SystemMessages.selectAll().where { SystemMessages.userId eq userId }
                .orderBy(SystemMessages.createdAt, SortOrder.DESC)
                .limit(limit + 1, offset.toLong()).toList()
        }
        val items = rows.take(limit).map {
            SystemMessageDto(it[SystemMessages.id].value.toString(), it[SystemMessages.text], it[SystemMessages.read], it[SystemMessages.createdAt].toString())
        }
        return items to if (rows.size > limit) offset + limit else null
    }

    suspend fun systemUnreadCount(userId: UUID): Long = dbQuery {
        SystemMessages.selectAll().where { (SystemMessages.userId eq userId) and (SystemMessages.read eq false) }.count()
    }

    suspend fun markSystemMessagesRead(userId: UUID) {
        dbQuery {
            SystemMessages.update({ (SystemMessages.userId eq userId) and (SystemMessages.read eq false) }) {
                it[read] = true
            }
        }
    }

    /** Header rows for the two pinned system conversations in the inbox. */
    suspend fun systemInbox(userId: UUID): SystemInboxDto {
        val officialId = OfficialAccountService.id()
        val latestMsg = dbQuery {
            SystemMessages.selectAll().where { SystemMessages.userId eq userId }
                .orderBy(SystemMessages.createdAt, SortOrder.DESC).limit(1).singleOrNull()
        }
        val latestNotif = dbQuery {
            Notifications.selectAll().where { Notifications.userId eq userId }
                .orderBy(Notifications.createdAt, SortOrder.DESC).limit(1).singleOrNull()
        }
        return SystemInboxDto(
            officialUserId = officialId.toString(),
            officialName = OfficialAccountService.DISPLAY_NAME,
            officialUsername = OfficialAccountService.USERNAME,
            officialLastMessage = latestMsg?.get(SystemMessages.text),
            officialLastAt = latestMsg?.get(SystemMessages.createdAt)?.toString(),
            officialUnreadCount = systemUnreadCount(userId),
            notificationsLastTitle = latestNotif?.get(Notifications.title),
            notificationsLastBody = latestNotif?.get(Notifications.body),
            notificationsLastAt = latestNotif?.get(Notifications.createdAt)?.toString(),
            notificationsUnreadCount = unreadCount(userId)
        )
    }

    /**
     * Registration invite: the official account welcomes the new user. The new
     * user auto-follows the official profile (users follow it; it follows nobody).
     */
    suspend fun welcomeNewUser(userId: UUID) {
        val officialId = OfficialAccountService.id()
        if (officialId == userId) return
        sendSystemMessage(userId, "Welcome to Telefam! 🎉 Your account is ready. Find friends, share moments and start conversations.")
        sendSystemMessage(userId, "Invite your friends to Telefam — share your profile from the Contacts tab and connect instantly.")
        notify(
            userId, NotificationTypes.SYSTEM,
            "Welcome to Telefam!", "Your account was created successfully. Tap to visit the official Telefam profile.",
            actorId = null, targetType = NotificationTargets.PROFILE, targetId = officialId.toString()
        )
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toDto() = NotificationDto(
        id = this[Notifications.id].value.toString(),
        type = this[Notifications.type],
        actorId = this[Notifications.actorId]?.toString(),
        actorName = this[Notifications.actorName],
        canFollowBack = this[Notifications.canFollowBack],
        title = this[Notifications.title],
        body = this[Notifications.body],
        targetType = this[Notifications.targetType],
        targetId = this[Notifications.targetId],
        read = this[Notifications.read],
        createdAt = this[Notifications.createdAt].toString()
    )
}
