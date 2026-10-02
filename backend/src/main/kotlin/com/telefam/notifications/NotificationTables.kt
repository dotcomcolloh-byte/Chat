package com.telefam.notifications

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.datetime

/**
 * Notification centre tables.
 *
 * [Notifications] is the per-user activity feed behind the "Notifications"
 * conversation in the inbox. Every row carries a deep-link target
 * ([NotificationTargets]) so a tap — in-app or from a push notification —
 * redirects straight to the origin (post, comment thread, profile, wallet, …).
 */
object Notifications : UUIDTable("notifications") {
    val userId = uuid("user_id").index() // recipient
    /** LIKE | COMMENT | REPLY | MENTION | TAG | REMOVED_POST | SUBSCRIPTION |
     *  PAYMENT_SUCCESS | NEW_LOGIN | NEW_FOLLOWER | WITHDRAWAL_SUCCESS | SYSTEM */
    val type = varchar("type", 30).index()
    /** The user who triggered the event (liker, commenter, follower…). Null for system rows. */
    val actorId = uuid("actor_id").nullable()
    val actorName = varchar("actor_name", 120).nullable()
    /** True when a NEW_FOLLOWER event can be answered with "Follow back". */
    val canFollowBack = bool("can_follow_back").default(false)
    val title = varchar("title", 200)
    val body = varchar("body", 500)
    /** POST | COMMENT | PROFILE | WALLET | SUBSCRIPTIONS | NOTIFICATIONS | none. */
    val targetType = varchar("target_type", 20).nullable()
    val targetId = varchar("target_id", 64).nullable()
    val read = bool("read").default(false).index()
    val createdAt = datetime("created_at").index()
}

/**
 * Server-side messages from the official Telefam account. These are NOT part of
 * the E2EE peer-to-peer chat: they live only on the server, are delivered
 * read-only, and only the backend (as the official account) can create them.
 */
object SystemMessages : UUIDTable("system_messages") {
    val userId = uuid("user_id").index() // recipient
    val text = varchar("text", 1000)
    val read = bool("read").default(false)
    val createdAt = datetime("created_at").index()
}
