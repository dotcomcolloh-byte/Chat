package com.telefam.routes

import com.telefam.auth.ApiError
import com.telefam.devices.DeviceLinkService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.*

@Serializable
data class LinkChallengeResponse(
    val challengeId: String,
    val qrPayload: String,
    val expiresInSeconds: Long
)

@Serializable
data class LinkChallengeStatusResponse(
    val status: String,
    val secondsRemaining: Long,
    val scannerLabel: String? = null,
    val acceptSecondsRemaining: Long = 0
)

@Serializable
data class LinkScanRequest(val challengeId: String, val secret: String, val deviceLabel: String = "")

@Serializable
data class LinkAcceptRequest(val challengeId: String)

@Serializable
data class LinkResultResponse(
    val status: String,
    val accessToken: String? = null,
    val refreshToken: String? = null
)

fun Route.deviceLinkRoutes(service: DeviceLinkService) {
    route("/api/devices/link") {

        // --- Scanner-side endpoints are public: a brand-new device is signed out by
        // definition, and possession of the one-time QR secret (only visible on the
        // owner's screen for 30 seconds) is the capability. Rate-limited against
        // brute force; secrets are 192-bit random, so guessing is not realistic. ---
        rateLimit(RateLimitName("auth")) {
            post("/scan") {
                val req = call.receive<LinkScanRequest>()
                val id = runCatching { UUID.fromString(req.challengeId) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid challenge"))
                if (req.secret.isBlank() || req.secret.length > 128) {
                    return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid challenge"))
                }
                when (service.markScanned(id, req.secret, req.deviceLabel.ifBlank { "Linked device" })) {
                    DeviceLinkService.ScanResult.Ok -> call.respond(HttpStatusCode.OK, mapOf("status" to "scanned"))
                    DeviceLinkService.ScanResult.Invalid ->
                        call.respond(HttpStatusCode.BadRequest, ApiError("This QR code is no longer valid"))
                }
            }

            /** Scanner: collect the outcome. Tokens are returned exactly once. */
            get("/result/{id}") {
                val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("Invalid challenge"))
                val secret = call.request.queryParameters["k"] ?: ""
                when (val result = service.fetchResult(id, secret)) {
                    is DeviceLinkService.ResultFetch.Tokens -> call.respond(
                        HttpStatusCode.OK,
                        LinkResultResponse("ACCEPTED", result.accessToken, result.refreshToken)
                    )
                    is DeviceLinkService.ResultFetch.Pending ->
                        call.respond(HttpStatusCode.OK, LinkResultResponse(result.status.name))
                    DeviceLinkService.ResultFetch.Invalid ->
                        call.respond(HttpStatusCode.Gone, ApiError("This link request is no longer valid"))
                }
            }
        }

        // --- Owner-side endpoints require the signed-in account owner. ---
        authenticate("auth-jwt") {
            /** Owner: mint a fresh QR challenge. Any previous live challenge is invalidated. */
            rateLimit(RateLimitName("auth")) {
                post("/challenge") {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val challenge = service.createChallenge(userId)
                    call.respond(
                        HttpStatusCode.Created,
                        LinkChallengeResponse(
                            challengeId = challenge.id.toString(),
                            qrPayload = challenge.qrPayload,
                            expiresInSeconds = DeviceLinkService.QR_TTL_SECONDS
                        )
                    )
                }
            }

            /** Owner: poll the challenge state (drives the 30s countdown + Accept prompt). */
            get("/challenge/{id}") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("Invalid challenge"))
                val status = service.status(userId, id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("Unknown challenge"))
                call.respond(
                    HttpStatusCode.OK,
                    LinkChallengeStatusResponse(
                        status = status.status.name,
                        secondsRemaining = status.secondsRemaining,
                        scannerLabel = status.scannerLabel,
                        acceptSecondsRemaining = status.acceptSecondsRemaining
                    )
                )
            }

            /** Owner: approve the scanned device (within the 5-minute accept window). */
            post("/accept") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val req = call.receive<LinkAcceptRequest>()
                val id = runCatching { UUID.fromString(req.challengeId) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid challenge"))
                when (service.accept(userId, id)) {
                    DeviceLinkService.AcceptResult.Ok -> call.respond(HttpStatusCode.OK, mapOf("status" to "linked"))
                    DeviceLinkService.AcceptResult.Invalid ->
                        call.respond(HttpStatusCode.BadRequest, ApiError("This link request is no longer valid"))
                }
            }

            /** Owner: reject the scanned device. */
            post("/decline") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val req = call.receive<LinkAcceptRequest>()
                val id = runCatching { UUID.fromString(req.challengeId) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid challenge"))
                service.decline(userId, id)
                call.respond(HttpStatusCode.OK, mapOf("status" to "declined"))
            }
        }
    }
}
