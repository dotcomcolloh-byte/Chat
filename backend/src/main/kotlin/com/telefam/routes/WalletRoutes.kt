package com.telefam.routes

import com.telefam.auth.ApiError
import com.telefam.wallet.EarningSource
import com.telefam.wallet.WalletService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

@Serializable data class WithdrawQuoteRequest(val methodId: String, val amountMinor: Long)
@Serializable data class PayoutRequestBody(val methodId: String, val amountMinor: Long, val idempotencyKey: String)
@Serializable data class MethodChangeStartRequest(
    val action: String, // ADD | CHANGE | REMOVE
    val methodId: String? = null,
    val type: String? = null,
    val label: String? = null,
    val detail: String? = null
)
@Serializable data class MethodChangeConfirmRequest(val changeId: String, val code: String)

/**
 * Wallet endpoints. The user id ALWAYS comes from the JWT principal — never the
 * request. The payout-provider webhook lives outside auth-jwt and is authenticated
 * by HMAC signature verification + amount/currency/reference validation inside
 * the service. A client can never claim "my payout succeeded".
 */
fun Route.walletRoutes(walletService: WalletService) {

    // --- Payout provider webhook: signature-verified, idempotent, provider-authoritative ---
    post("/webhooks/payouts") {
        val raw = call.receiveText().toByteArray(Charsets.UTF_8)
        val signature = call.request.header("x-telefam-signature") ?: call.request.header("x-signature")
        if (walletService.verifyPayoutWebhook(raw, signature)) {
            runCatching {
                val body = kotlinx.serialization.json.Json.parseToJsonElement(String(raw)).jsonObject
                val reference = body["reference"]?.jsonPrimitive?.content ?: return@runCatching
                val outcomeRaw = body["status"]?.jsonPrimitive?.content?.uppercase() ?: return@runCatching
                val outcome = when (outcomeRaw) {
                    "PAID", "SUCCESS", "COMPLETED" -> WalletService.PayoutOutcome.PAID
                    "FAILED" -> WalletService.PayoutOutcome.FAILED
                    "REJECTED" -> WalletService.PayoutOutcome.REJECTED
                    "CANCELED", "CANCELLED" -> WalletService.PayoutOutcome.CANCELED
                    else -> return@runCatching
                }
                val payoutId = walletService.payoutIdForReference(reference) ?: return@runCatching
                val providerRef = body["providerRef"]?.jsonPrimitive?.content
                val message = body["message"]?.jsonPrimitive?.content?.take(200)
                walletService.applyPayoutOutcome(payoutId, outcome, providerRef, message)
            }
        }
        call.respond(HttpStatusCode.OK) // always 200; invalid signatures dropped silently
    }

    authenticate("auth-jwt") {
        route("/api/wallet") {

            // ---------- Balances & overview ----------
            get("/balance") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                call.respond(walletService.balances(userId))
            }

            get("/overview") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val periodDays = call.request.queryParameters["periodDays"]?.toIntOrNull()?.coerceIn(1, 365) ?: 30
                call.respond(walletService.monetizationOverview(userId, periodDays))
            }

            get("/chart") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val periodDays = call.request.queryParameters["periodDays"]?.toIntOrNull()
                    ?.let { if (it in listOf(7, 30, 90, 365)) it else 7 } ?: 7
                call.respond(walletService.earningsChart(userId, periodDays))
            }

            // ---------- Pending settlement breakdown ----------
            get("/pending") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                call.respond(walletService.pendingBreakdown(userId))
            }

            // ---------- Per-source earnings ----------
            get("/earnings/{source}") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val source = runCatching {
                    EarningSource.valueOf(call.parameters["source"]?.uppercase() ?: "")
                }.getOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("Unknown source"))
                call.respond(walletService.sourceEarnings(userId, source, call.request.queryParameters["cursor"]))
            }

            // ---------- Transactions ----------
            get("/transactions") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val filter = call.request.queryParameters["filter"] ?: "ALL"
                call.respond(
                    walletService.transactions(
                        userId, filter,
                        source = call.request.queryParameters["source"],
                        cursor = call.request.queryParameters["cursor"]
                    )
                )
            }
            get("/transactions/{id}") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("Invalid id"))
                val dto = walletService.transactionDetails(userId, id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("Not found"))
                call.respond(dto)
            }

            // ---------- Payouts ----------
            get("/payouts") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                call.respond(walletService.payoutSummary(userId))
            }
            get("/payouts/{id}") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("Invalid id"))
                val dto = walletService.payoutDetails(userId, id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("Not found"))
                call.respond(dto)
            }
            post("/payouts/{id}/cancel") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid id"))
                if (walletService.cancelPayout(userId, id)) call.respond(HttpStatusCode.OK)
                else call.respond(HttpStatusCode.Conflict, ApiError("This payout can no longer be canceled"))
            }

            // ---------- Withdrawal flow ----------
            get("/payout-eligibility") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                call.respond(walletService.payoutEligibility(userId))
            }
            post("/withdraw/quote") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val req = call.receive<WithdrawQuoteRequest>()
                val methodId = runCatching { UUID.fromString(req.methodId) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid method"))
                if (req.amountMinor <= 0) return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid amount"))
                val quote = runCatching { walletService.withdrawQuote(userId, methodId, req.amountMinor) }
                    .getOrElse { return@post call.respond(HttpStatusCode.BadRequest, ApiError(it.message ?: "Invalid request")) }
                call.respond(quote)
            }
            // Withdrawal submission is rate-limited per IP on top of per-account risk checks.
            rateLimit(RateLimitName("wallet")) {
            post("/payouts") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val req = call.receive<PayoutRequestBody>()
                if (req.idempotencyKey.isBlank() || req.idempotencyKey.length > 96)
                    return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid idempotency key"))
                val methodId = runCatching { UUID.fromString(req.methodId) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid method"))
                if (req.amountMinor <= 0) return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid amount"))
                val dto = runCatching {
                    walletService.requestPayout(userId, methodId, req.amountMinor, req.idempotencyKey)
                }.getOrElse {
                    return@post call.respond(HttpStatusCode.UnprocessableEntity, ApiError(it.message ?: "Payout unavailable"))
                }
                call.respond(HttpStatusCode.Created, dto)
            }
            }

            // ---------- Payout methods (OTP-protected changes) ----------
            get("/methods") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                call.respond(walletService.listMethods(userId))
            }
            get("/methods/supported") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                call.respond(walletService.supportedMethods(userId))
            }
            post("/methods/changes") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val req = call.receive<MethodChangeStartRequest>()
                val dto = runCatching {
                    walletService.startMethodChange(
                        userId, req.action.uppercase(),
                        req.methodId?.let { UUID.fromString(it) },
                        req.type?.uppercase(), req.label, req.detail
                    )
                }.getOrElse {
                    return@post call.respond(HttpStatusCode.BadRequest, ApiError(it.message ?: "Invalid change"))
                }
                call.respond(HttpStatusCode.Created, dto)
            }
            post("/methods/changes/confirm") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val req = call.receive<MethodChangeConfirmRequest>()
                val changeId = runCatching { UUID.fromString(req.changeId) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid change"))
                val ok = walletService.confirmMethodChange(userId, changeId, req.code)
                if (ok) call.respond(HttpStatusCode.OK)
                else call.respond(HttpStatusCode.UnprocessableEntity, ApiError("Couldn't verify that code"))
            }
        }
    }
}
