package com.telefam.routes

import com.telefam.calls.CallSignalingService
import com.telefam.calls.DeviceTokens
import com.telefam.calls.RegisterDeviceRequest
import com.telefam.config.AppConfig
import com.telefam.db.DatabaseFactory.dbQuery
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.UUID

@Serializable
data class IceServerDto(val urls: List<String>, val username: String? = null, val credential: String? = null)

@Serializable
data class IceConfigResponse(val iceServers: List<IceServerDto>, val ttlSeconds: Long = 86_400)

fun Route.callRoutes(callSignaling: CallSignalingService) {
    authenticate("auth-jwt") {

        /**
         * Call signaling channel. SDP/ICE relay only — WebRTC media is P2P and never
         * transits this socket; the server deliberately caps frame size to keep it so.
         */
        webSocket("/api/calls/ws") {
            val principal = call.principal<UserIdPrincipal>()
            if (principal == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
                return@webSocket
            }
            val userId = UUID.fromString(principal.name)
            callSignaling.join(userId, this)
            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) callSignaling.handleClientMessage(userId, frame.readText())
                }
            } finally {
                callSignaling.leave(userId, this)
            }
        }

        /**
         * ICE server configuration. STUN is free; TURN credentials come from env vars so
         * operators can plug in any TURN provider (coturn, Twilio, Metered...) without a rebuild.
         */
        get("/api/calls/ice-servers") {
            val servers = mutableListOf(
                IceServerDto(urls = listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"))
            )
            if (AppConfig.turnUrl.isNotBlank()) {
                servers += IceServerDto(
                    urls = listOf(AppConfig.turnUrl),
                    username = AppConfig.turnUsername.ifBlank { null },
                    credential = AppConfig.turnCredential.ifBlank { null }
                )
            }
            call.respond(IceConfigResponse(servers))
        }

        /** Register / refresh the device's push token so calls can ring when the app is closed. */
        post("/api/calls/devices") {
            val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
            val req = runCatching { call.receive<RegisterDeviceRequest>() }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Invalid request"))
            if (req.platform !in setOf("android", "ios") || req.token.isBlank() || req.token.length > 512) {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Invalid device token"))
            }
            dbQuery {
                // Idempotent: re-registering the same token just refreshes its timestamp.
                val exists = DeviceTokens.selectAll().where {
                    (DeviceTokens.userId eq userId) and (DeviceTokens.token eq req.token)
                }.count() > 0
                if (exists) {
                    DeviceTokens.update({ (DeviceTokens.userId eq userId) and (DeviceTokens.token eq req.token) }) {
                        it[updatedAt] = System.currentTimeMillis()
                    }
                } else {
                    DeviceTokens.insert {
                        it[DeviceTokens.userId] = userId
                        it[platform] = req.platform
                        it[token] = req.token
                        it[updatedAt] = System.currentTimeMillis()
                    }
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "registered"))
        }

        /** Unregister this device's token (logout / push disabled). */
        delete("/api/calls/devices/{token}") {
            val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
            val token = call.parameters["token"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            dbQuery { DeviceTokens.deleteWhere { (DeviceTokens.userId eq userId) and (DeviceTokens.token eq token) } }
            call.respond(HttpStatusCode.OK, mapOf("status" to "removed"))
        }
    }
}
