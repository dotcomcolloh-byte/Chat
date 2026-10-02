package com.telefam.routes

import com.telefam.auth.ApiError
import com.telefam.privacy.ChatPreferencesService
import com.telefam.privacy.MuteRequest
import com.telefam.privacy.ReportRequest
import com.telefam.privacy.ReportService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.*

fun Route.chatPreferencesRoutes(service: ChatPreferencesService) {
    authenticate("auth-jwt") {
        route("/api/chat-prefs") {
            post("/mute") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val req = call.receive<MuteRequest>()
                val peerId = runCatching { UUID.fromString(req.peerId) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid peer id"))
                service.setMute(userId, peerId, req.mutedUntilEpochSeconds)
                call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
            }

            get("/{peerId}") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val peerId = call.parameters["peerId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("Invalid peer id"))
                call.respond(HttpStatusCode.OK, service.getPreference(userId, peerId))
            }

            get {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                call.respond(HttpStatusCode.OK, service.listAllPreferences(userId))
            }
        }
    }
}

fun Route.reportRoutes(service: ReportService) {
    authenticate("auth-jwt") {
        rateLimit(RateLimitName("otp")) {
            route("/api/reports") {
                post {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val req = call.receive<ReportRequest>()
                    val reportedId = runCatching { UUID.fromString(req.reportedUserId) }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid user id"))
                    if (req.reason.isBlank()) {
                        return@post call.respond(HttpStatusCode.BadRequest, ApiError("Please describe the issue"))
                    }
                    service.submitReport(userId, reportedId, req.reason)
                    call.respond(HttpStatusCode.Created, mapOf("status" to "submitted"))
                }
            }
        }
    }
}
