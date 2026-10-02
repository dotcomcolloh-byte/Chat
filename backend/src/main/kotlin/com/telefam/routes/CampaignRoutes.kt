package com.telefam.routes

import com.telefam.auth.ApiError
import com.telefam.campaigns.CampaignService
import com.telefam.campaigns.CreateCampaignRequest
import com.telefam.campaigns.CreateSeriesRequest
import com.telefam.campaigns.InsufficientStarsException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.readBytes
import java.util.*

/**
 * Stars store + paid campaign endpoints. Provider webhooks live OUTSIDE auth-jwt
 * (the caller is Paystack/PayPal) and are signature-verified + replay-deduplicated.
 * Everything else is owner-scoped from the JWT principal; the client never sends
 * an amount, currency, provider, or price — only package/tier/days choices.
 */
fun Route.campaignRoutes(campaignService: CampaignService) {

    // --- Provider webhooks: signature-verified, replay-safe, provider re-checked ---
    post("/webhooks/paystack/stars") {
        val raw = call.receiveChannel().readRemaining().readBytes()
        campaignService.handlePaystackWebhook(raw, call.request.header("x-paystack-signature"))
        call.respond(HttpStatusCode.OK)
    }
    post("/webhooks/paypal/stars") {
        val raw = call.receiveText()
        val headers = call.request.headers.entries().associate { it.key.lowercase() to it.value.joinToString(",") }
        campaignService.handlePayPalWebhook(headers, raw)
        call.respond(HttpStatusCode.OK)
    }

    authenticate("auth-jwt") {
        route("/api/stars") {
            get("/balance") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                call.respond(campaignService.balance(userId))
            }
            get("/packages") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                call.respond(campaignService.packages(userId))
            }
            post("/purchase") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val idem = call.request.header("Idempotency-Key")?.take(128)
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Idempotency-Key header required"))
                val packageId = runCatching {
                    call.receive<Map<String, String>>()["packageId"]
                }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("packageId required"))
                try {
                    call.respond(campaignService.initiatePurchase(userId, packageId, idem))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "invalid request"))
                }
            }
            post("/purchase/{id}/confirm") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid id"))
                try {
                    call.respond(campaignService.confirmPurchase(userId, id))
                } catch (e: NoSuchElementException) {
                    call.respond(HttpStatusCode.NotFound, ApiError("purchase not found"))
                }
            }
        }

        route("/api/campaigns") {
            get("/config") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                call.respond(campaignService.config(userId))
            }
            post {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val idem = call.request.header("Idempotency-Key")?.take(128)
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Idempotency-Key header required"))
                val body = call.receive<CreateCampaignRequest>()
                try {
                    call.respond(HttpStatusCode.Created, campaignService.createCampaign(userId, body, idem))
                } catch (e: InsufficientStarsException) {
                    call.respond(
                        HttpStatusCode.PaymentRequired,
                        mapOf("code" to "INSUFFICIENT_STARS", "balanceStars" to e.balanceStars.toString(),
                            "requiredStars" to e.requiredStars.toString())
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "invalid request"))
                }
            }
            get("/mine") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                call.respond(campaignService.myCampaigns(userId))
            }
            get("/{id}/analytics") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("invalid id"))
                try {
                    call.respond(campaignService.analytics(userId, id))
                } catch (e: NoSuchElementException) {
                    call.respond(HttpStatusCode.NotFound, ApiError("campaign not found"))
                }
            }
            post("/{id}/pause") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid id"))
                try {
                    call.respond(campaignService.pauseCampaign(userId, id))
                } catch (e: NoSuchElementException) {
                    call.respond(HttpStatusCode.NotFound, ApiError("campaign not found"))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.Conflict, ApiError(e.message ?: "cannot pause"))
                }
            }
            post("/{id}/resume") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid id"))
                try {
                    call.respond(campaignService.resumeCampaign(userId, id))
                } catch (e: NoSuchElementException) {
                    call.respond(HttpStatusCode.NotFound, ApiError("campaign not found"))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.Conflict, ApiError(e.message ?: "cannot resume"))
                }
            }
            post("/{id}/cancel") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid id"))
                try {
                    call.respond(campaignService.cancelCampaign(userId, id))
                } catch (e: NoSuchElementException) {
                    call.respond(HttpStatusCode.NotFound, ApiError("campaign not found"))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.Conflict, ApiError(e.message ?: "cannot cancel"))
                }
            }
            post("/{id}/event") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid id"))
                val kind = runCatching { call.receive<Map<String, String>>()["kind"] }.getOrNull() ?: "IMPRESSION"
                campaignService.recordEvent(userId, id, kind)
                call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
            }

            route("/series") {
                get {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    call.respond(campaignService.mySeries(userId))
                }
                post {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val idem = call.request.header("Idempotency-Key")?.take(128)
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Idempotency-Key header required"))
                    val body = call.receive<CreateSeriesRequest>()
                    try {
                        call.respond(HttpStatusCode.Created, campaignService.createSeries(userId, body, idem))
                    } catch (e: InsufficientStarsException) {
                        call.respond(
                            HttpStatusCode.PaymentRequired,
                            mapOf("code" to "INSUFFICIENT_STARS", "balanceStars" to e.balanceStars.toString(),
                                "requiredStars" to e.requiredStars.toString())
                        )
                    } catch (e: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "invalid request"))
                    }
                }
            }
        }
    }
}
