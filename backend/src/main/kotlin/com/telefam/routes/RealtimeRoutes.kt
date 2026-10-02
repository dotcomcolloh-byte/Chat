package com.telefam.routes

import com.telefam.realtime.RealtimeService
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import java.util.UUID

fun Route.realtimeRoutes(realtimeService: RealtimeService) {
    authenticate("auth-jwt") {
        webSocket("/api/realtime/ws") {
            val principal = call.principal<UserIdPrincipal>()
            if (principal == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
                return@webSocket
            }
            val userId = UUID.fromString(principal.name)
            realtimeService.join(userId, this)
            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) realtimeService.handleClientMessage(userId, this, frame.readText())
                }
            } finally {
                realtimeService.leave(userId, this)
            }
        }
    }
}
