package com.telefam.plugins

import com.telefam.auth.JwtService
import io.ktor.server.application.*
import io.ktor.server.auth.*

fun Application.configureSecurity(jwtService: JwtService) {
    install(Authentication) {
        bearer("auth-jwt") {
            authenticate { credential ->
                val userId = jwtService.verifyAccessToken(credential.token) ?: return@authenticate null
                UserIdPrincipal(userId.toString())
            }
        }
        // Admin console auth: same JWT, but the subject must be in ADMIN_USER_IDS (env, comma-separated).
        bearer("auth-jwt-admin") {
            authenticate { credential ->
                val userId = jwtService.verifyAccessToken(credential.token) ?: return@authenticate null
                val admins = (System.getenv("ADMIN_USER_IDS") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (userId.toString() in admins) UserIdPrincipal(userId.toString()) else null
            }
        }
    }
}
