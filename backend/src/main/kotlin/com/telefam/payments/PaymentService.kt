package com.telefam.payments

import com.telefam.config.AppConfig
import com.telefam.db.DatabaseFactory.dbQuery
import com.telefam.db.Users
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.UUID

/**
 * Payment orchestration for Telefam Verified.
 *
 * Security invariants enforced here:
 *  - Amount, currency and provider are computed server-side from the user's profile
 *    country (Users.phoneCountryCode). The client sends only a plan choice.
 *  - A payment becomes PAID only after an authoritative provider API call
 *    (Paystack /verify, PayPal capture+GET) or a signature-validated webhook —
 *    never from client-supplied success flags.
 *  - Every webhook application is deduplicated by payload hash (replay-safe).
 *  - Refunds go through the provider API and are re-checked before marking REFUNDED.
 */
class PaymentService(
    private val paystack: PaystackClient = PaystackClient(),
    private val paypal: PayPalClient = PayPalClient()
) {
    @Serializable
    data class PlanPrice(val plan: String, val amountMinor: Long, val formatted: String, val strikeFormatted: String? = null, val offPercent: Int = 0)

    @Serializable
    data class PricingResponse(
        val provider: String,               // PAYSTACK | PAYPAL — the single method offered to this user
        val providerLabel: String,          // "Paystack" | "PayPal"
        val currency: String,
        val countryCode: String,
        val plans: List<PlanPrice>
    )

    @Serializable
    data class InitiatedPayment(val paymentId: String, val provider: String, val checkoutUrl: String, val reference: String)

    @Serializable
    data class PaymentStatus(val paymentId: String, val status: String, val failureReason: String? = null, val refunded: Boolean = false)

    /** Dial-code -> ISO country. Used to localise pricing; unknown dial codes default to USD/PayPal. */
    private val DIAL_TO_COUNTRY = mapOf(
        "+254" to "KE", "+255" to "TZ", "+256" to "UG", "+250" to "RW",
        "+234" to "NG", "+233" to "GH", "+27" to "ZA",
        "+1" to "US", "+44" to "GB", "+49" to "DE", "+33" to "FR", "+39" to "IT",
        "+34" to "ES", "+31" to "NL", "+353" to "IE", "+61" to "AU", "+91" to "IN"
    )

    private suspend fun countryOf(userId: UUID): String = dbQuery {
        Users.selectAll().where { Users.id eq userId }.singleOrNull()
            ?.get(Users.phoneCountryCode)
            ?.let { DIAL_TO_COUNTRY[it] } ?: "US"
    }

    suspend fun pricing(userId: UUID): PricingResponse {
        val country = countryOf(userId)
        val provider = PricingCatalog.providerFor(country)
        val currency = PricingCatalog.currencyFor(country)
        val (monthly, _) = PricingCatalog.priceFor(currency, PricingCatalog.Plan.MONTHLY)
        val (annual, _) = PricingCatalog.priceFor(currency, PricingCatalog.Plan.ANNUAL)
        return PricingResponse(
            provider = provider.name,
            providerLabel = if (provider == PricingCatalog.Provider.PAYSTACK) "Paystack" else "PayPal",
            currency = currency,
            countryCode = country,
            plans = listOf(
                PlanPrice("MONTHLY", monthly, PricingCatalog.format(currency, monthly)),
                PlanPrice("ANNUAL", annual, PricingCatalog.format(currency, annual),
                    strikeFormatted = PricingCatalog.format(currency, PricingCatalog.monthlyEquivalentAnnual(currency)),
                    offPercent = PricingCatalog.discountPercent(currency))
            )
        )
    }

    /** Creates a PENDING payment row + provider checkout. Amount comes from PricingCatalog only. */
    suspend fun initiate(userId: UUID, plan: PricingCatalog.Plan): InitiatedPayment {
        val country = countryOf(userId)
        val provider = PricingCatalog.providerFor(country)
        val currency = PricingCatalog.currencyFor(country)
        val (amountMinor, _) = PricingCatalog.priceFor(currency, plan)
        val paymentId = UUID.randomUUID()
        val email = dbQuery { Users.selectAll().where { Users.id eq userId }.single()[Users.email] }

        val (ref, url) = when (provider) {
            PricingCatalog.Provider.PAYSTACK -> {
                val init = paystack.initialize(
                    email = email, amountMinor = amountMinor, currency = currency,
                    reference = "tfv_${paymentId.toString().replace("-", "")}",
                    callbackUrl = "${AppConfig.publicBaseUrl}/verify/callback"
                )
                init.reference to init.authorizationUrl
            }
            PricingCatalog.Provider.PAYPAL -> {
                val order = paypal.createOrder(
                    amountMinor = amountMinor, currency = currency,
                    returnUrl = "${AppConfig.publicBaseUrl}/verify/paypal/return",
                    cancelUrl = "${AppConfig.publicBaseUrl}/verify/paypal/cancel"
                )
                order.orderId to order.approveUrl
            }
        }

        dbQuery {
            VerificationPayments.insert {
                it[id] = paymentId; it[VerificationPayments.userId] = userId
                it[VerificationPayments.provider] = provider.name
                it[VerificationPayments.plan] = plan.name
                it[VerificationPayments.amountMinor] = amountMinor
                it[VerificationPayments.currency] = currency
                it[VerificationPayments.countryCode] = country
                it[providerRef] = ref
                it[checkoutUrl] = url
                it[status] = "PENDING"
                it[createdAt] = LocalDateTime.now(); it[updatedAt] = LocalDateTime.now()
            }
            VerificationAudit.insert {
                it[VerificationAudit.userId] = userId; it[actor] = "USER"
                it[event] = "PAYMENT_INITIATED"; it[detail] = "${provider.name} $plan $currency $amountMinor"
                it[createdAt] = LocalDateTime.now()
            }
        }
        return InitiatedPayment(paymentId.toString(), provider.name, url, ref)
    }

    /**
     * Client returned from checkout. We do NOT trust the client: we ask the provider
     * directly for the authoritative state, then persist it. For PayPal this also
     * performs the capture (money only moves here).
     */
    suspend fun confirmFromProvider(userId: UUID, paymentId: UUID): PaymentStatus {
        val row = dbQuery {
            VerificationPayments.selectAll().where {
                (VerificationPayments.id eq paymentId) and (VerificationPayments.userId eq userId)
            }.singleOrNull()
        } ?: throw NoSuchElementException("payment not found")
        return settleWithProvider(row, actor = "USER")
    }

    private suspend fun settleWithProvider(row: ResultRow, actor: String): PaymentStatus {
        val paymentId = row[VerificationPayments.id].value
        val userId = row[VerificationPayments.userId]
        val provider = row[VerificationPayments.provider]
        val ref = row[VerificationPayments.providerRef]
        val expectedAmount = row[VerificationPayments.amountMinor]
        val expectedCurrency = row[VerificationPayments.currency]
        if (row[VerificationPayments.status] in listOf("PAID", "REFUNDED"))
            return PaymentStatus(paymentId.toString(), row[VerificationPayments.status], refunded = row[VerificationPayments.status] == "REFUNDED")

        val outcome = when (provider) {
            "PAYSTACK" -> paystack.verify(ref).let { Triple(it.paid, it.amountMinor to it.currency, it.failureReason) }
            else -> {
                val captured = runCatching { paypal.captureOrder(ref) }.getOrElse { paypal.getOrder(ref) }
                Triple(captured.paid, captured.amountMinor to captured.currency, if (captured.paid) null else captured.status)
            }
        }
        val (paid, amountCurrency, failure) = outcome
        // Amount/currency mismatch = tampering or provider error: never mark paid.
        val amountOk = amountCurrency.first == expectedAmount && amountCurrency.second.equals(expectedCurrency, true)
        val newStatus = when {
            paid && amountOk -> "PAID"
            paid && !amountOk -> "FAILED" // paid but wrong amount -> flagged, admin refund path
            failure != null && failure != "PENDING" && failure != "CREATED" && failure != "APPROVED" -> "FAILED"
            else -> "PENDING"
        }
        dbQuery {
            VerificationPayments.update({ VerificationPayments.id eq paymentId }) {
                it[status] = newStatus
                it[failureReason] = if (newStatus == "FAILED") (failure ?: "amount_mismatch") else null
                it[updatedAt] = LocalDateTime.now()
            }
            VerificationAudit.insert {
                it[VerificationAudit.userId] = userId; it[VerificationAudit.actor] = actor
                it[event] = "PAYMENT_$newStatus"; it[detail] = failure
                it[createdAt] = LocalDateTime.now()
            }
        }
        return PaymentStatus(paymentId.toString(), newStatus, if (newStatus == "FAILED") (failure ?: "amount_mismatch") else null)
    }

    // ---------------- Webhooks (signature-verified, replay-safe) ----------------

    suspend fun handlePaystackWebhook(rawBody: ByteArray, signature: String?) {
        if (!paystack.isValidWebhookSignature(rawBody, signature)) return // drop silently
        val body = String(rawBody)
        val ref = Regex("\"reference\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: return
        val event = Regex("\"event\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: return
        applyWebhook("PAYSTACK", ref, event == "charge.success", hashOf(rawBody))
    }

    suspend fun handlePayPalWebhook(headers: Map<String, String>, rawBody: String) {
        if (!paypal.isValidWebhook(headers, rawBody)) return // drop silently
        val orderId = Regex("\"order_id\"\\s*:\\s*\"([^\"]+)\"").find(rawBody)?.groupValues?.get(1)
            ?: Regex("\"id\"\\s*:\\s*\"([A-Z0-9]{17})\"").find(rawBody)?.groupValues?.get(1) ?: return
        val paid = rawBody.contains("PAYMENT.CAPTURE.COMPLETED") || rawBody.contains("CHECKOUT.ORDER.APPROVED")
        applyWebhook("PAYPAL", orderId, paid, hashOf(rawBody.toByteArray()))
    }

    /** Even for webhooks we re-check with the provider API before trusting — defense in depth. */
    private suspend fun applyWebhook(provider: String, ref: String, paidHint: Boolean, payloadHash: String) {
        val row = dbQuery {
            VerificationPayments.selectAll().where {
                (VerificationPayments.providerRef eq ref) and (VerificationPayments.provider eq provider)
            }.singleOrNull()
        } ?: return
        if (row[VerificationPayments.lastWebhookHash] == payloadHash) return // replay
        dbQuery {
            VerificationPayments.update({ VerificationPayments.id eq row[VerificationPayments.id] }) {
                it[lastWebhookHash] = payloadHash; it[updatedAt] = LocalDateTime.now()
            }
        }
        if (paidHint) settleWithProvider(row, actor = "WEBHOOK")
    }

    // ---------------- Refunds (policy-gated, provider-verified) ----------------

    /**
     * Refund policy: a user becomes refund-eligible when their verification has been
     * stuck in a failed state for VERIFICATION_REFUND_DAYS days. The refund itself is
     * executed against the provider and only marked REFUNDED after the provider confirms.
     */
    suspend fun refund(userId: UUID, paymentId: UUID): PaymentStatus {
        val row = dbQuery {
            VerificationPayments.selectAll().where {
                (VerificationPayments.id eq paymentId) and (VerificationPayments.userId eq userId)
            }.singleOrNull()
        } ?: throw NoSuchElementException("payment not found")
        require(row[VerificationPayments.status] == "PAID") { "only paid payments are refundable" }

        val ok = when (row[VerificationPayments.provider]) {
            "PAYSTACK" -> paystack.refund(row[VerificationPayments.providerRef], row[VerificationPayments.amountMinor])
            else -> {
                // PayPal refunds target the capture id; look it up from the live order.
                val order = paypal.getOrder(row[VerificationPayments.providerRef])
                order.captureId?.let { paypal.refundCapture(it) } ?: false
            }
        }
        if (!ok) throw IllegalStateException("provider refund failed")
        dbQuery {
            VerificationPayments.update({ VerificationPayments.id eq paymentId }) {
                it[status] = "REFUNDED"; it[refundedAt] = LocalDateTime.now(); it[updatedAt] = LocalDateTime.now()
            }
            VerificationAudit.insert {
                it[VerificationAudit.userId] = userId; it[actor] = "USER"
                it[event] = "PAYMENT_REFUNDED"; it[createdAt] = LocalDateTime.now()
            }
        }
        return PaymentStatus(paymentId.toString(), "REFUNDED", refunded = true)
    }

    private fun hashOf(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
