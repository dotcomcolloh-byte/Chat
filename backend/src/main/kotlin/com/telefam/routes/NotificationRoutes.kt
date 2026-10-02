package com.telefam.routes

import com.telefam.auth.ApiError
import com.telefam.notifications.NotificationService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

/**
 * Notification centre + official-account system inbox.
 *
 *  GET    /api/notifications?filter=all|comments|mentions|subscriptions
 *  GET    /api/notifications/unread-count
 *  POST   /api/notifications/read-all
 *  POST   /api/notifications/{id}/read
 *  DELETE /api/notifications/{id}            (the per-row 3-dot "Delete")
 *  GET    /api/inbox/system                  (pinned conversation headers + unread counts)
 *  GET    /api/inbox/system/messages         (read-only official-account messages)
 *  POST   /api/inbox/system/read-all
 */
fun Route.notificationRoutes() {
    authenticate("auth-jwt") {
        route("/api/notifications") {
            get {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val filter = call.request.queryParameters["filter"]
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 30).coerceIn(1, 100)
                call.respond(NotificationService.list(userId, filter, offset, limit))
            }
            get("/unread-count") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                call.respond(mapOf("unreadCount" to NotificationService.unreadCount(userId)))
            }
            post("/read-all") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                NotificationService.markAllRead(userId)
                call.respond(mapOf("status" to "ok"))
            }
            post("/{id}/read") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid id"))
                if (!NotificationService.markRead(userId, id)) {
                    return@post call.respond(HttpStatusCode.NotFound, ApiError("Not found"))
                }
                call.respond(mapOf("status" to "ok"))
            }
            delete("/{id}") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiError("Invalid id"))
                if (!NotificationService.delete(userId, id)) {
                    return@delete call.respond(HttpStatusCode.NotFound, ApiError("Not found"))
                }
                call.respond(mapOf("status" to "deleted"))
            }
        }
        route("/api/inbox/system") {
            get {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                call.respond(NotificationService.systemInbox(userId))
            }
            get("/messages") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 100)
                val (items, next) = NotificationService.systemMessages(userId, offset, limit)
                call.respond(com.telefam.notifications.SystemMessagePageDto(items, next))
            }
            post("/read-all") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                NotificationService.markSystemMessagesRead(userId)
                call.respond(mapOf("status" to "ok"))
            }
        }
    }
}
