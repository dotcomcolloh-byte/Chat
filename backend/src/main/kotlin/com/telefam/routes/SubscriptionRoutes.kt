package com.telefam.routes

import com.telefam.auth.ApiError
import com.telefam.subscriptions.SubscriptionService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

@Serializable data class CreatePlanRequest(val name: String, val description: String = "", val interval: String, val priceMinor: Long)
@Serializable data class UpdatePlanRequest(val name: String, val description: String = "", val priceMinor: Long, val isMostPopular: Boolean = false)
@Serializable data class SubscribeRequest(val planId: String)

/**
 * Paid subscription endpoints. Provider webhooks live OUTSIDE auth-jwt (the
 * providers call them) and are authenticated by provider signature verification
 * inside SubscriptionService. Everything else takes the user id strictly from
 * the JWT principal — never from the request.
 */
fun Route.subscriptionRoutes(subscriptionService: SubscriptionService) {

    // --- Provider webhooks: signature-verified, replay-safe, provider re-checked ---
    post("/webhooks/paystack/subscriptions") {
        val raw = call.receiveText().toByteArray(Charsets.UTF_8)
        subscriptionService.handlePaystackWebhook(raw, call.request.header("x-paystack-signature"))
        call.respond(HttpStatusCode.OK) // always 200; invalid signatures are dropped silently
    }
    post("/webhooks/paypal/subscriptions") {
        val raw = call.receiveText()
        val headers = call.request.headers.names().associateWith { call.request.headers[it] ?: "" }
        subscriptionService.handlePayPalWebhook(headers, raw)
        call.respond(HttpStatusCode.OK)
    }

    authenticate("auth-jwt") {
        route("/api/subscriptions") {

            // ---------- Creator: plans CRUD ----------
            get("/plans") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val creatorId = call.request.queryParameters["creatorId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                // Owner sees all plans (incl. inactive); viewers see only active ones.
                val target = creatorId ?: userId
                call.respond(subscriptionService.listPlans(target, ownerView = target == userId))
            }

            post("/plans") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val req = call.receive<CreatePlanRequest>()
                val plan = runCatching {
                    subscriptionService.createPlan(userId, req.name, req.description, req.interval, req.priceMinor)
                }.getOrElse { return@post call.respond(HttpStatusCode.BadRequest, ApiError(it.message ?: "Invalid plan")) }
                call.respond(HttpStatusCode.Created, plan)
            }

            put("/plans/{id}") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val planId = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ApiError("Invalid plan id"))
                val req = call.receive<UpdatePlanRequest>()
                val plan = runCatching {
                    subscriptionService.updatePlan(userId, planId, req.name, req.description, req.priceMinor, req.isMostPopular)
                }.getOrElse {
                    return@put call.respond(
                        if (it is NoSuchElementException) HttpStatusCode.NotFound else HttpStatusCode.BadRequest,
                        ApiError(it.message ?: "Update failed"))
                }
                call.respond(plan)
            }

            delete("/plans/{id}") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val planId = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiError("Invalid plan id"))
                runCatching { subscriptionService.deletePlan(userId, planId) }.fold(
                    onSuccess = { call.respond(HttpStatusCode.OK, mapOf("status" to "ok")) },
                    onFailure = { call.respond(HttpStatusCode.NotFound, ApiError(it.message ?: "Plan not found")) }
                )
            }

            // ---------- Creator: earnings / insights / subscribers ----------
            get("/overview") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val period = (call.request.queryParameters["periodDays"]?.toIntOrNull() ?: 30).coerceIn(1, 365)
                call.respond(subscriptionService.overview(userId, period))
            }

            get("/insights") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val period = (call.request.queryParameters["periodDays"]?.toIntOrNull() ?: 30).coerceIn(1, 365)
                call.respond(subscriptionService.insights(userId, period))
            }

            get("/subscribers") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
                val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
                call.respond(subscriptionService.subscribers(userId, limit, offset))
            }

            get("/subscribers/recent") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                call.respond(subscriptionService.recentSubscribers(userId))
            }

            /** Creator billing ledger, limited to payments belonging to the authenticated creator. */
            get("/creator/payments") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 200)
                call.respond(subscriptionService.creatorPaymentHistory(userId, limit))
            }

            /** Starts or returns the single full-refund request for this creator's paid charge. */
            post("/payments/{id}/refund") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val paymentId = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid payment id"))
                runCatching { subscriptionService.requestRefund(userId, paymentId) }.fold(
                    onSuccess = { call.respond(it) },
                    onFailure = {
                        val code = when (it) {
                            is NoSuchElementException -> HttpStatusCode.NotFound
                            is IllegalStateException -> HttpStatusCode.Conflict
                            else -> HttpStatusCode.BadRequest
                        }
                        call.respond(code, ApiError(it.message ?: "Could not start refund"))
                    }
                )
            }

            /** Subscriber-initiated refund: auto within the configurable window, review path otherwise. */
            post("/payments/{id}/subscriber-refund") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val paymentId = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid payment id"))
                val reason = runCatching {
                    kotlinx.serialization.json.Json.parseToJsonElement(call.receiveText())
                        .jsonObject["reason"]?.jsonPrimitive?.content ?: ""
                }.getOrDefault("")
                runCatching { subscriptionService.requestSubscriberRefund(userId, paymentId, reason) }.fold(
                    onSuccess = { call.respond(it) },
                    onFailure = {
                        val code = when (it) {
                            is NoSuchElementException -> HttpStatusCode.NotFound
                            is IllegalStateException -> HttpStatusCode.Conflict
                            else -> HttpStatusCode.BadRequest
                        }
                        call.respond(code, ApiError(it.message ?: "Could not request refund"))
                    }
                )
            }

            get("/payments/{id}/refund") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val paymentId = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("Invalid payment id"))
                runCatching { subscriptionService.creatorRefundStatus(userId, paymentId) }.fold(
                    onSuccess = { refund ->
                        if (refund == null) call.respond(HttpStatusCode.NotFound, ApiError("Refund not requested"))
                        else call.respond(refund)
                    },
                    onFailure = { call.respond(HttpStatusCode.NotFound, ApiError(it.message ?: "Payment not found")) }
                )
            }

            // ---------- Fan: subscribe page, checkout, polling, manage ----------
            /** Public subscribe page for a creator: plans + the viewer's payment provider + current state. */
            get("/page/{creatorId}") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val creatorId = runCatching { UUID.fromString(call.parameters["creatorId"]) }.getOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("Invalid creator id"))
                runCatching { subscriptionService.creatorPage(creatorId, userId) }.fold(
                    onSuccess = { call.respond(it) },
                    onFailure = { call.respond(HttpStatusCode.NotFound, ApiError(it.message ?: "Creator not found")) }
                )
            }

            /**
             * Start a subscription payment. Idempotent: the Idempotency-Key header
             * pins the attempt — retries return the same payment and checkout URL.
             * Amount/currency/provider are all server-computed from the plan.
             */
            post("/subscribe") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val req = call.receive<SubscribeRequest>()
                val planId = runCatching { UUID.fromString(req.planId) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid plan id"))
                val idem = call.request.header("Idempotency-Key")?.takeIf { it.isNotBlank() }
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Missing Idempotency-Key header"))
                runCatching { subscriptionService.subscribe(userId, planId, idem) }.fold(
                    onSuccess = { call.respond(it) },
                    onFailure = {
                        val code = when (it) {
                            is NoSuchElementException -> HttpStatusCode.NotFound
                            is IllegalStateException -> HttpStatusCode.Conflict
                            else -> HttpStatusCode.BadRequest
                        }
                        call.respond(code, ApiError(it.message ?: "Could not start subscription"))
                    }
                )
            }

            /** Client back from checkout — we re-verify with the provider, never trust the client. */
            post("/payments/{id}/confirm") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val paymentId = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid payment id"))
                runCatching { subscriptionService.confirmPayment(userId, paymentId) }.fold(
                    onSuccess = { call.respond(it) },
                    onFailure = { call.respond(HttpStatusCode.BadRequest, ApiError(it.message ?: "Confirmation failed")) }
                )
            }

            /** Poll endpoint: PENDING payments are re-checked against the provider on every call. */
            get("/payments/{id}/status") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val paymentId = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("Invalid payment id"))
                runCatching { subscriptionService.paymentStatus(userId, paymentId) }.fold(
                    onSuccess = { call.respond(it) },
                    onFailure = { call.respond(HttpStatusCode.BadRequest, ApiError(it.message ?: "Unknown payment")) }
                )
            }

            /** Cancel renewal at the end of the already-paid period. */
            post("/{id}/unsubscribe") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val subId = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid subscription id"))
                runCatching { subscriptionService.unsubscribe(userId, subId) }.fold(
                    onSuccess = { call.respond(HttpStatusCode.OK, mapOf("status" to "ok")) },
                    onFailure = { call.respond(HttpStatusCode.NotFound, ApiError(it.message ?: "Subscription not found")) }
                )
            }

            /** The fan's own active subscriptions (manage / unsubscribe surface). */
            get("/mine") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                call.respond(subscriptionService.mySubscriptions(userId))
            }

            get("/mine/payments") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                call.respond(subscriptionService.myPaymentHistory(userId))
            }
        }
    }
}
