package com.telefam.routes

import com.telefam.auth.*
import com.telefam.validation.Validators
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.*

/** Security notification: every freshly issued session shows up in the notification centre. */
private suspend fun notifyNewLogin(userId: UUID, userAgent: String?) {
    com.telefam.notifications.NotificationService.notify(
        userId = userId,
        type = com.telefam.notifications.NotificationTypes.NEW_LOGIN,
        title = "New login detected",
        body = "A new sign-in to your account${userAgent?.take(60)?.let { " from $it" } ?: ""}. If this wasn't you, change your password immediately.",
        targetType = com.telefam.notifications.NotificationTargets.NOTIFICATIONS,
        targetId = null
    )
}

fun Route.authRoutes(
    authService: AuthService,
    otpService: OtpService,
    jwtService: JwtService,
    loginAttempts: LoginAttemptService,
    googleVerifier: GoogleAuthVerifier,
    appleVerifier: AppleAuthVerifier
) {
    rateLimit(RateLimitName("auth")) {
        route("/api/auth") {

            post("/signup") {
                val req = call.receive<SignUpRequest>()
                if (!Validators.isValidEmail(req.email) || !Validators.isValidPassword(req.password)) {
                    return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid email or password format"))
                }
                if (authService.findUserByEmail(req.email) != null) {
                    // Same generic message as any other signup failure — no account enumeration.
                    return@post call.respond(HttpStatusCode.BadRequest, ApiError("Unable to create account"))
                }
                val userId = authService.createUserWithPassword(req.email, req.password)
                otpService.sendOtp(userId, req.email, OtpPurpose.SIGNUP_VERIFY)
                call.respond(HttpStatusCode.Created, mapOf("userId" to userId.toString()))
            }

            rateLimit(RateLimitName("otp")) {
                post("/otp/send") {
                    val req = call.receive<OtpRequestDto>()
                    val purpose = OtpPurpose.entries.find { it.name == req.purpose }
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid request"))
                    val user = authService.findUserByEmail(req.email)
                        ?: return@post call.respond(HttpStatusCode.OK, mapOf("secondsUntilNextResend" to 60)) // don't reveal non-existence
                    val userId = user[com.telefam.db.Users.id].value
                    when (val result = otpService.sendOtp(userId, req.email, purpose)) {
                        is OtpSendResult.Sent -> call.respond(HttpStatusCode.OK, mapOf("secondsUntilNextResend" to result.secondsUntilNextResend))
                        is OtpSendResult.Cooldown -> call.respond(HttpStatusCode.OK, CooldownResponse(result.secondsRemaining))
                    }
                }

                post("/otp/verify") {
                    val req = call.receive<OtpVerifyRequestDto>()
                    val purpose = OtpPurpose.entries.find { it.name == req.purpose }
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid request"))
                    // Only sign-in style codes may mint tokens here. Password-reset codes must go through
                    // /password/reset (which sets the new password); email-change / payout codes are
                    // consumed by their own authenticated routes.
                    if (purpose != OtpPurpose.SIGNUP_VERIFY && purpose != OtpPurpose.LOGIN_2FA) {
                        return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid request"))
                    }
                    if (!Validators.isValidOtpCode(req.code)) {
                        return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid code"))
                    }
                    val user = authService.findUserByEmail(req.email)
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid code"))
                    val userId = user[com.telefam.db.Users.id].value

                    when (val result = otpService.verifyOtp(userId, purpose, req.code)) {
                        OtpVerifyResult.Success -> {
                            if (purpose == OtpPurpose.SIGNUP_VERIFY) authService.markEmailVerified(userId)
                            if (purpose == OtpPurpose.LOGIN_2FA) notifyNewLogin(userId, call.request.headers["User-Agent"])
                            val access = jwtService.createAccessToken(userId)
                            val refresh = jwtService.issueNewRefreshTokenFamily(userId, call.request.headers["User-Agent"])
                            call.respond(HttpStatusCode.OK, AuthTokens(access, refresh))
                        }
                        OtpVerifyResult.Invalid -> call.respond(HttpStatusCode.BadRequest, ApiError("Invalid code"))
                        is OtpVerifyResult.Cooldown -> call.respond(HttpStatusCode.TooManyRequests, CooldownResponse(result.secondsRemaining))
                    }
                }
            }

            post("/login") {
                val req = call.receive<LoginRequest>()
                if (!Validators.isValidEmail(req.email)) {
                    return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid credentials"))
                }
                val user = authService.findUserByEmail(req.email)
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiError("Invalid credentials"))
                val userId = user[com.telefam.db.Users.id].value
                val hash = user[com.telefam.db.Users.passwordHash]
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiError("Invalid credentials"))

                when (val gate = loginAttempts.checkGate(userId)) {
                    is LoginGate.Locked -> return@post call.respond(HttpStatusCode.TooManyRequests, CooldownResponse(gate.secondsRemaining))
                    LoginGate.Allowed -> {}
                }

                if (!authService.verifyPassword(req.password, hash)) {
                    val gate = loginAttempts.recordFailure(userId)
                    return@post when (gate) {
                        is LoginGate.Locked -> call.respond(HttpStatusCode.TooManyRequests, CooldownResponse(gate.secondsRemaining))
                        LoginGate.Allowed -> call.respond(HttpStatusCode.Unauthorized, ApiError("Invalid credentials"))
                    }
                }

                loginAttempts.recordSuccess(userId)
                // Deactivated / deleted accounts cannot log in.
                if (user[com.telefam.db.Users.accountStatus] != "ACTIVE") {
                    return@post call.respond(HttpStatusCode.Unauthorized, ApiError("Invalid credentials"))
                }
                // Second factor: an enabled 2FA account must complete an email OTP before tokens are issued.
                if (user[com.telefam.db.Users.twoFactorEnabled]) {
                    when (val send = otpService.sendOtp(userId, req.email, OtpPurpose.LOGIN_2FA)) {
                        is OtpSendResult.Sent -> call.respond(HttpStatusCode.OK, TwoFactorRequiredResponse(true, send.secondsUntilNextResend))
                        is OtpSendResult.Cooldown -> call.respond(HttpStatusCode.OK, TwoFactorRequiredResponse(true, send.secondsRemaining))
                    }
                    return@post
                }
                notifyNewLogin(userId, call.request.headers["User-Agent"])
                val access = jwtService.createAccessToken(userId)
                val refresh = jwtService.issueNewRefreshTokenFamily(userId, call.request.headers["User-Agent"])
                call.respond(HttpStatusCode.OK, AuthTokens(access, refresh))
            }

            // Logout: revoke the whole token family that owns the presented refresh token.
            // Authenticated so only the account owner can kill a session; idempotent.
            authenticate("auth-jwt") {
                post("/logout") {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val refresh = call.request.headers["X-Refresh-Token"]
                    if (refresh != null) jwtService.revokeByRefreshToken(userId, refresh)
                    call.respond(HttpStatusCode.OK, mapOf("status" to "logged_out"))
                }
            }

            post("/refresh") {
                val req = call.receive<RefreshRequest>()
                when (val result = jwtService.rotateRefreshToken(req.refreshToken, call.request.headers["User-Agent"])) {
                    is JwtService.RefreshResult.Success -> call.respond(HttpStatusCode.OK, AuthTokens(result.newAccessToken, result.newRefreshToken))
                    JwtService.RefreshResult.Invalid -> call.respond(HttpStatusCode.Unauthorized, ApiError("Session expired"))
                    JwtService.RefreshResult.ReuseDetected -> call.respond(HttpStatusCode.Unauthorized, ApiError("Session revoked"))
                }
            }

            post("/google") {
                val req = call.receive<GoogleSignInRequest>()
                val identity = googleVerifier.verify(req.idToken)
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiError("Invalid Google token"))
                val userId = authService.upsertGoogleUser(identity)
                notifyNewLogin(userId, call.request.headers["User-Agent"])
                val access = jwtService.createAccessToken(userId)
                val refresh = jwtService.issueNewRefreshTokenFamily(userId, call.request.headers["User-Agent"])
                call.respond(HttpStatusCode.OK, AuthTokens(access, refresh))
            }

            post("/apple") {
                val req = call.receive<AppleSignInRequest>()
                val identity = appleVerifier.verify(req.identityToken)
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiError("Invalid Apple token"))
                val userId = authService.upsertAppleUser(identity)
                notifyNewLogin(userId, call.request.headers["User-Agent"])
                val access = jwtService.createAccessToken(userId)
                val refresh = jwtService.issueNewRefreshTokenFamily(userId, call.request.headers["User-Agent"])
                call.respond(HttpStatusCode.OK, AuthTokens(access, refresh))
            }

            rateLimit(RateLimitName("otp")) {
                post("/password/forgot") {
                    val req = call.receive<ForgotPasswordRequest>()
                    val user = authService.findUserByEmail(req.email)
                    if (user != null) {
                        otpService.sendOtp(user[com.telefam.db.Users.id].value, req.email, OtpPurpose.PASSWORD_RESET)
                    }
                    // Always 200 — never reveal whether the email exists.
                    call.respond(HttpStatusCode.OK, mapOf("secondsUntilNextResend" to 60))
                }

                post("/password/reset") {
                    val req = call.receive<ResetPasswordRequest>()
                    if (!Validators.isValidPassword(req.newPassword)) {
                        return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid password format"))
                    }
                    val user = authService.findUserByEmail(req.email)
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid code"))
                    val userId = user[com.telefam.db.Users.id].value

                    when (val result = otpService.verifyOtp(userId, OtpPurpose.PASSWORD_RESET, req.code)) {
                        OtpVerifyResult.Success -> {
                            authService.setPassword(userId, req.newPassword)
                            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
                        }
                        OtpVerifyResult.Invalid -> call.respond(HttpStatusCode.BadRequest, ApiError("Invalid code"))
                        is OtpVerifyResult.Cooldown -> call.respond(HttpStatusCode.TooManyRequests, CooldownResponse(result.secondsRemaining))
                    }
                }
            }
        }
    }
}
