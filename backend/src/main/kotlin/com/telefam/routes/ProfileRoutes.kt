package com.telefam.routes

import com.telefam.auth.ApiError
import com.telefam.auth.AuthService
import com.telefam.auth.ProfileSetupRequest
import com.telefam.validation.Validators
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import java.util.*

fun Route.profileRoutes(authService: AuthService) {
    authenticate("auth-jwt") {
        route("/api/profile") {
            post("/setup") {
                val principal = call.principal<UserIdPrincipal>()!!
                val userId = UUID.fromString(principal.name)
                val req = call.receive<ProfileSetupRequest>()

                if (!Validators.isValidFullName(req.fullName)) return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid full name"))
                if (!Validators.isValidUsername(req.username)) return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid username"))
                if (!Validators.isValidCountryDialCode(req.countryDialCode)) return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid country code"))
                if (!Validators.isValidPhone(req.phoneNumber)) return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid phone number"))
                if (!Validators.isValidGender(req.gender)) return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid gender"))
                if (!Validators.isValidDob(req.dateOfBirth)) return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid date of birth"))
                if (req.bio != null && !Validators.isValidBio(req.bio)) return@post call.respond(HttpStatusCode.BadRequest, ApiError("Bio too long"))

                if (authService.isUsernameTaken(req.username)) {
                    return@post call.respond(HttpStatusCode.Conflict, ApiError("Username already taken"))
                }

                val mediaId = req.profileImageMediaId?.let {
                    try { UUID.fromString(it) } catch (e: Exception) {
                        return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid media reference"))
                    }
                }

                authService.completeProfile(userId, req, mediaId)
                // Registration complete: the official Telefam account invites the new
                // user (welcome conversation + auto-follow of the official profile).
                runCatching {
                    val officialId = com.telefam.official.OfficialAccountService.ensureExists()
                    com.telefam.notifications.NotificationService.welcomeNewUser(userId)
                    // Auto-follow the official account (bypasses follow budgets; idempotent).
                    val follows = com.telefam.posts.Follows
                    val already = com.telefam.db.DatabaseFactory.dbQuery {
                        follows.selectAll().where {
                            (follows.followerId eq userId) and (follows.followeeId eq officialId)
                        }.any()
                    }
                    if (!already && officialId != userId) {
                        com.telefam.db.DatabaseFactory.dbQuery {
                            follows.insert {
                                it[follows.id] = UUID.randomUUID()
                                it[follows.followerId] = userId
                                it[follows.followeeId] = officialId
                                it[follows.createdAt] = java.time.LocalDateTime.now()
                            }
                        }
                    }
                }
                call.respond(HttpStatusCode.OK, mapOf("status" to "profile_complete"))
            }
        }
    }
}
