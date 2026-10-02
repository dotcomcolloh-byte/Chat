package com.telefam.routes

import com.telefam.auth.ApiError
import com.telefam.auth.OtpPurpose
import com.telefam.auth.OtpSendResult
import com.telefam.auth.OtpService
import com.telefam.auth.OtpVerifyResult
import com.telefam.auth.AuthService
import com.telefam.settings.*
import com.telefam.validation.Validators
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.*

fun Route.accountRoutes(accountService: AccountService, authService: AuthService, otpService: OtpService) {
    authenticate("auth-jwt") {
        route("/api/account") {

            get {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val details = accountService.accountDetails(userId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("Not found"))
                call.respond(HttpStatusCode.OK, details)
            }

            /** Edit profile: name/bio/photo any time; username at most once every 7 days. */
            put("/profile") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val req = call.receive<EditProfileRequest>()
                if (req.fullName != null && !Validators.isValidFullName(req.fullName)) {
                    return@put call.respond(HttpStatusCode.BadRequest, ApiError("Invalid full name"))
                }
                if (req.username != null && !Validators.isValidUsername(req.username)) {
                    return@put call.respond(HttpStatusCode.BadRequest, ApiError("Invalid username"))
                }
                if (req.bio != null && !Validators.isValidBio(req.bio)) {
                    return@put call.respond(HttpStatusCode.BadRequest, ApiError("Bio too long"))
                }
                if (req.profileImageMediaId != null && runCatching { UUID.fromString(req.profileImageMediaId) }.isFailure) {
                    return@put call.respond(HttpStatusCode.BadRequest, ApiError("Invalid media reference"))
                }
                if (req.website != null && req.website.isNotBlank() && !Validators.isValidWebsite(req.website.trim())) {
                    return@put call.respond(HttpStatusCode.BadRequest, ApiError("Invalid website URL"))
                }
                if (req.locationName != null && !Validators.isValidLocationName(req.locationName)) {
                    return@put call.respond(HttpStatusCode.BadRequest, ApiError("Invalid location"))
                }
                try {
                    val cooldown = accountService.editProfile(userId, req)
                    call.respond(HttpStatusCode.OK, EditProfileResponse("updated", cooldown))
                } catch (e: UsernameCooldownException) {
                    call.respond(HttpStatusCode.Conflict, UsernameCooldownBody(e.daysRemaining))
                } catch (e: IllegalArgumentException) {
                    val status = if (e.message == "username_taken") HttpStatusCode.Conflict else HttpStatusCode.BadRequest
                    call.respond(status, ApiError(if (e.message == "username_taken") "Username already taken" else "Invalid request"))
                }
            }

            /** Step 1: record the pending change + OTP to the NEW address. */
            rateLimit(RateLimitName("otp")) {
                post("/email/change-request") {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val req = call.receive<EmailChangeRequest>()
                    if (!Validators.isValidEmail(req.newEmail)) {
                        return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid email"))
                    }
                    try {
                        accountService.requestEmailChange(userId, req.newEmail)
                    } catch (e: IllegalArgumentException) {
                        return@post call.respond(HttpStatusCode.Conflict, ApiError("Email already in use"))
                    }
                    when (val send = otpService.sendOtp(userId, req.newEmail, OtpPurpose.EMAIL_CHANGE)) {
                        is OtpSendResult.Sent -> call.respond(HttpStatusCode.OK, mapOf("secondsUntilNextResend" to send.secondsUntilNextResend))
                        is OtpSendResult.Cooldown -> call.respond(HttpStatusCode.OK, CooldownBody(send.secondsRemaining))
                    }
                }

                /** Step 2: verify the OTP that went to the new address; applies the change and re-issues nothing (client signs in again). */
                post("/email/confirm") {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val req = call.receive<EmailChangeConfirm>()
                    if (!Validators.isValidOtpCode(req.code)) {
                        return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid code"))
                    }
                    when (val result = otpService.verifyOtp(userId, OtpPurpose.EMAIL_CHANGE, req.code)) {
                        OtpVerifyResult.Success -> {
                            try {
                                accountService.confirmEmailChange(userId, req.newEmail)
                                call.respond(HttpStatusCode.OK, mapOf("status" to "email_updated"))
                            } catch (e: IllegalArgumentException) {
                                call.respond(HttpStatusCode.BadRequest, ApiError("No pending change"))
                            }
                        }
                        OtpVerifyResult.Invalid -> call.respond(HttpStatusCode.BadRequest, ApiError("Invalid code"))
                        is OtpVerifyResult.Cooldown -> call.respond(HttpStatusCode.TooManyRequests, CooldownBody(result.secondsRemaining))
                    }
                }
            }

            put("/phone") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val req = call.receive<PhoneUpdateRequest>()
                if (!Validators.isValidCountryDialCode(req.countryDialCode) || !Validators.isValidPhone(req.phoneNumber)) {
                    return@put call.respond(HttpStatusCode.BadRequest, ApiError("Invalid phone number"))
                }
                accountService.updatePhone(userId, req.countryDialCode, req.phoneNumber)
                call.respond(HttpStatusCode.OK, mapOf("status" to "updated"))
            }

            post("/password") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val req = call.receive<PasswordChangeRequest>()
                if (!Validators.isValidPassword(req.newPassword)) {
                    return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid password format"))
                }
                val ok = accountService.changePassword(userId, req.currentPassword, req.newPassword, authService::verifyPassword)
                if (!ok) return@post call.respond(HttpStatusCode.Unauthorized, ApiError("Invalid credentials"))
                call.respond(HttpStatusCode.OK, mapOf("status" to "password_updated"))
            }

            route("/security") {
                get {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    call.respond(HttpStatusCode.OK, accountService.securitySettings(userId))
                }
                put {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val req = call.receive<SecuritySettingsUpdate>()
                    call.respond(HttpStatusCode.OK, accountService.updateSecuritySettings(userId, req))
                }
            }

            route("/sessions") {
                get {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val refresh = call.request.headers["X-Refresh-Token"]
                    call.respond(HttpStatusCode.OK, accountService.listSessions(userId, refresh))
                }
                delete("/{sessionId}") {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val family = call.parameters["sessionId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: return@delete call.respond(HttpStatusCode.BadRequest)
                    accountService.revokeSession(userId, family)
                    call.respond(HttpStatusCode.OK, mapOf("status" to "revoked"))
                }
                delete("/others") {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    accountService.revokeOtherSessions(userId, call.request.headers["X-Refresh-Token"])
                    call.respond(HttpStatusCode.OK, mapOf("status" to "revoked"))
                }
            }

            post("/deactivate") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                accountService.setAccountStatus(userId, "DEACTIVATED")
                call.respond(HttpStatusCode.OK, mapOf("status" to "deactivated"))
            }

            delete {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                accountService.setAccountStatus(userId, "DELETED")
                call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
            }
        }

        route("/api/app-settings") {
            get {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                call.respond(HttpStatusCode.OK, accountService.appSettings(userId))
            }
            put {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                call.respond(HttpStatusCode.OK, accountService.updateAppSettings(userId, call.receive<AppSettingsDto>()))
            }
        }

        route("/api/support") {
            post("/reports") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val req = call.receive<ProblemReportRequest>()
                if (req.description.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, ApiError("Description required"))
                }
                call.respond(HttpStatusCode.Created, accountService.submitProblemReport(userId, req))
            }
            get("/reports") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                call.respond(HttpStatusCode.OK, accountService.myProblemReports(userId))
            }
        }
    }
}

@Serializable
private data class UsernameCooldownBody(val daysRemaining: Long)

@Serializable
private data class CooldownBody(val secondsRemaining: Long)
