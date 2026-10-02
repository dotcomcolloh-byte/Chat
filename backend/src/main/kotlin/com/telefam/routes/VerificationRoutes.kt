package com.telefam.routes

import com.telefam.auth.ApiError
import com.telefam.payments.PaymentService
import com.telefam.payments.PricingCatalog
import com.telefam.verification.BadgeService
import com.telefam.verification.VerificationService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import java.util.UUID

@Serializable data class InitiatePaymentRequest(val plan: String)
@Serializable data class IdSubmissionRequest(val frontMediaId: String, val backMediaId: String)
@Serializable data class RefundRequest(val paymentId: String)
@Serializable data class AdminDecisionRequest(val applicationId: String, val approve: Boolean, val reason: String? = null)

/**
 * Verification + payment endpoints.
 * Webhooks are NOT under auth-jwt (providers call them) — they are authenticated by
 * provider signature verification inside PaymentService instead.
 */
fun Route.verificationRoutes(paymentService: PaymentService, verificationService: VerificationService) {

    // --- Provider webhooks: signature-verified, replay-safe, provider-re-checked ---
    post("/webhooks/paystack") {
        val raw = call.receiveText().toByteArray(Charsets.UTF_8)
        paymentService.handlePaystackWebhook(raw, call.request.header("x-paystack-signature"))
        call.respond(HttpStatusCode.OK) // always 200; invalid signatures are dropped silently
    }
    post("/webhooks/paypal") {
        val raw = call.receiveText()
        val headers = call.request.headers.names().associateWith { call.request.headers[it] ?: "" }
        paymentService.handlePayPalWebhook(headers, raw)
        call.respond(HttpStatusCode.OK)
    }

    authenticate("auth-jwt") {
        route("/api/verification") {

            /** Pricing + the single payment method for this user's country (no provider choice). */
            get("/pricing") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                call.respond(paymentService.pricing(userId))
            }

            get("/status") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                call.respond(verificationService.status(userId))
            }

            /** Start payment. The plan is validated; amount/currency/provider are server-computed. */
            post("/payments/initiate") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val req = call.receive<InitiatePaymentRequest>()
                val plan = runCatching { PricingCatalog.Plan.valueOf(req.plan) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid plan"))
                verificationService.ensureApplication(userId)
                call.respond(paymentService.initiate(userId, plan))
            }

            /** Client is back from checkout — we re-verify with the provider, never trust the client. */
            post("/payments/{id}/confirm") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val paymentId = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid payment id"))
                val status = runCatching { paymentService.confirmFromProvider(userId, paymentId) }.getOrElse {
                    return@post call.respond(HttpStatusCode.BadRequest, ApiError(it.message ?: "confirmation failed"))
                }
                if (status.status == "PAID") verificationService.onPaymentConfirmed(userId, paymentId)
                call.respond(status)
            }

            post("/payments/{id}/refund") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val paymentId = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid payment id"))
                val st = verificationService.status(userId)
                if (!st.refundEligible) return@post call.respond(HttpStatusCode.Forbidden,
                    ApiError("Refund becomes available ${st.refundPolicyDays} days after a failed verification"))
                val status = runCatching { paymentService.refund(userId, paymentId) }.getOrElse {
                    return@post call.respond(HttpStatusCode.BadRequest, ApiError(it.message ?: "refund failed"))
                }
                call.respond(status)
            }

            // --- Liveness (server-issued signed challenge) ---
            post("/liveness/start") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                runCatching { verificationService.startLiveness(userId) }.fold(
                    onSuccess = { call.respond(it) },
                    onFailure = { call.respond(HttpStatusCode.Conflict, ApiError(it.message ?: "not available")) }
                )
            }
            post("/liveness/complete") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val body = call.receive<VerificationService.LivenessCompletion>()
                runCatching { verificationService.completeLiveness(userId, body) }.fold(
                    onSuccess = { call.respond(HttpStatusCode.OK, mapOf("state" to "ID_CAPTURE")) },
                    onFailure = { call.respond(HttpStatusCode.BadRequest, ApiError(it.message ?: "liveness check failed")) }
                )
            }

            // --- ID capture -> submit for review ---
            post("/id") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val req = call.receive<IdSubmissionRequest>()
                val front = runCatching { UUID.fromString(req.frontMediaId) }.getOrNull()
                val back = runCatching { UUID.fromString(req.backMediaId) }.getOrNull()
                if (front == null || back == null) return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid media ids"))
                runCatching { verificationService.submitId(userId, front, back) }.fold(
                    onSuccess = { call.respond(HttpStatusCode.Accepted, mapOf("state" to "SUBMITTED")) },
                    onFailure = { call.respond(HttpStatusCode.BadRequest, ApiError(it.message ?: "submission failed")) }
                )
            }

            post("/retry") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                runCatching { verificationService.retry(userId) }.fold(
                    onSuccess = { call.respond(mapOf("state" to "LIVENESS")) },
                    onFailure = { call.respond(HttpStatusCode.Conflict, ApiError(it.message ?: "retry unavailable")) }
                )
            }
        }

        // --- Admin console (separate admin auth claim enforced by configureSecurity) ---
        authenticate("auth-jwt-admin") {
            route("/api/admin/verification") {
                get("/queue") {
                    call.respond(verificationServiceAdminQueue())
                }
                post("/decide") {
                    val adminId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val req = call.receive<AdminDecisionRequest>()
                    val appId = runCatching { UUID.fromString(req.applicationId) }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid application id"))
                    verificationService.adminDecide(adminId, appId, req.approve, req.reason)
                    call.respond(HttpStatusCode.OK)
                }
            }
        }
    }
}

@Serializable data class AdminQueueItem(val applicationId: String, val userId: String, val confidence: Double?, val submittedAt: String)

private suspend fun verificationServiceAdminQueue(): List<AdminQueueItem> =
    com.telefam.db.DatabaseFactory.dbQuery {
        com.telefam.payments.VerificationApplications.selectAll().where {
            com.telefam.payments.VerificationApplications.state eq "ADMIN_REVIEW"
        }.orderBy(com.telefam.payments.VerificationApplications.updatedAt, org.jetbrains.exposed.sql.SortOrder.ASC)
            .map { row ->
                AdminQueueItem(
                    row[com.telefam.payments.VerificationApplications.id].value.toString(),
                    row[com.telefam.payments.VerificationApplications.userId].toString(),
                    row[com.telefam.payments.VerificationApplications.reviewConfidence],
                    row[com.telefam.payments.VerificationApplications.updatedAt].toString()
                )
            }
    }
