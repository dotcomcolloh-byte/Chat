package com.telefam.routes

import com.telefam.auth.ApiError
import com.telefam.privacy.AccessLevel
import com.telefam.privacy.BubbleColour
import com.telefam.privacy.ChatsTheme
import com.telefam.privacy.PrivacyService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.*

@Serializable data class UpdatePrivacyFieldRequest(val field: String, val value: String)

fun Route.privacyRoutes(privacyService: PrivacyService) {
    authenticate("auth-jwt") {
        route("/api/privacy") {
            get {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                call.respond(HttpStatusCode.OK, privacyService.get(userId))
            }

            put {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val req = call.receive<UpdatePrivacyFieldRequest>()

                val valid = when (req.field) {
                    in PrivacyService.ACCESS_LEVEL_FIELDS -> runCatching { AccessLevel.valueOf(req.value) }.isSuccess
                    in PrivacyService.BOOL_FIELDS -> req.value == "true" || req.value == "false"
                    PrivacyService.THEME_FIELD -> runCatching { ChatsTheme.valueOf(req.value) }.isSuccess
                    PrivacyService.BUBBLE_FIELD -> runCatching { BubbleColour.valueOf(req.value) }.isSuccess
                    else -> false
                }
                if (!valid) return@put call.respond(HttpStatusCode.BadRequest, ApiError("Invalid field or value"))

                privacyService.updateField(userId, req.field, req.value)
                call.respond(HttpStatusCode.OK, privacyService.get(userId))
            }
        }
    }
}
