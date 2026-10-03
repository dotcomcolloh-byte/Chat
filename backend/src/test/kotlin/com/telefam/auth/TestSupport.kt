package com.telefam.auth

import com.telefam.db.DatabaseFactory
import com.telefam.email.ResendClient
import com.telefam.plugins.configureRateLimiting
import com.telefam.plugins.configureSecurity
import com.telefam.routes.authRoutes
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap

/** Records the OTP "emails" instead of sending them, so tests can complete verification flows. */
class CapturingResend : ResendClient() {
    /** Latest code sent per (lower-cased) address. */
    val codes = ConcurrentHashMap<String, String>()
    var resetNotices = 0

    override suspend fun sendOtpEmail(toEmail: String, code: String) {
        codes[toEmail.lowercase()] = code
    }

    override suspend fun sendPasswordResetNotice(toEmail: String) {
        resetNotices++
    }
}

/** Real Postgres, shared by all integration tests in the JVM. */
object TestDb {
    val available: Boolean by lazy {
        try {
            DriverManager.getConnection(
                System.getenv("DATABASE_URL"), System.getenv("DATABASE_USER"), System.getenv("DATABASE_PASSWORD")
            ).use { it.isValid(3) }
        } catch (e: Exception) {
            false
        }
    }

    private var initialised = false

    @Synchronized
    fun ensureInit() {
        if (!initialised) {
            DatabaseFactory.init() // creates every table + applies the RLS policies, exactly like production boot
            initialised = true
        }
    }
}

/**
 * The production auth wiring (same services, same routes, same plugins) minus the unrelated
 * features, plus one protected probe route so tests can check that issued tokens really work.
 */
fun Application.authTestModule(resend: ResendClient) {
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<Throwable> { call, _ ->
            call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "Something went wrong"))
        }
    }
    val jwtService = JwtService()
    configureSecurity(jwtService)
    configureRateLimiting()
    val otpService = OtpService(resend)
    val loginAttempts = LoginAttemptService()
    val authService = AuthService(otpService, jwtService, loginAttempts)
    routing {
        authRoutes(authService, otpService, jwtService, loginAttempts, GoogleAuthVerifier(), AppleAuthVerifier())
        authenticate("auth-jwt") {
            get("/api/test/whoami") {
                call.respondText(call.principal<UserIdPrincipal>()!!.name)
            }
        }
    }
}
