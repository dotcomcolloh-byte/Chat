package com.telefam.routes

import com.telefam.auth.ApiError
import com.telefam.privacy.SocialService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.*

@Serializable data class RespondRequestBody(val accept: Boolean)
@Serializable data class UserIdBody(val userId: String)
@Serializable data class ChatIdBody(val chatId: String)

fun Route.socialRoutes(socialService: SocialService) {
    authenticate("auth-jwt") {
        route("/api/social") {

            get("/requests") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                call.respond(HttpStatusCode.OK, socialService.listPendingRequests(userId))
            }

            post("/requests/{id}/respond") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val requestId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid request id"))
                val body = call.receive<RespondRequestBody>()
                val ok = socialService.respondToRequest(userId, requestId, body.accept)
                if (ok) call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
                else call.respond(HttpStatusCode.NotFound, ApiError("Request not found"))
            }

            get("/blocked") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                call.respond(HttpStatusCode.OK, socialService.listBlocked(userId))
            }

            post("/blocked") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val body = call.receive<UserIdBody>()
                val targetId = runCatching { UUID.fromString(body.userId) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid user id"))
                socialService.blockUser(userId, targetId)
                call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
            }

            delete("/blocked/{userId}") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val targetId = call.parameters["userId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiError("Invalid user id"))
                socialService.unblockUser(userId, targetId)
                call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
            }

            /** Two-sided block state for one conversation (drives the chat composer/banner). */
            get("/blocked-status/{peerId}") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val peerId = call.parameters["peerId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("Invalid user id"))
                call.respond(HttpStatusCode.OK, socialService.blockStatus(userId, peerId))
            }

            get("/archived") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                call.respond(HttpStatusCode.OK, socialService.listArchived(userId))
            }

            post("/archived") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val body = call.receive<ChatIdBody>()
                val chatId = runCatching { UUID.fromString(body.chatId) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid chat id"))
                socialService.archiveChat(userId, chatId)
                call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
            }

            delete("/archived/{chatId}") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val chatId = call.parameters["chatId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiError("Invalid chat id"))
                socialService.unarchiveChat(userId, chatId)
                call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
            }
        }
    }
}
