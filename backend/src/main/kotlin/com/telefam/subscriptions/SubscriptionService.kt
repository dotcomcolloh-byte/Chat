package com.telefam.subscriptions

import com.telefam.config.AppConfig
import com.telefam.db.DatabaseFactory.dbQuery
import com.telefam.db.Users
import com.telefam.payments.PayPalClient
import com.telefam.payments.PaystackClient
import com.telefam.payments.PricingCatalog
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

private data class ProviderRefundSubmission(
    val status: String,
    val refundId: String? = null,
    val failureReason: String? = null
)

/**
 * Paid creator subscriptions — plans, checkout, server-verified settlement,
 * webhooks, idempotency, cancellation and creator analytics.
 *
 * Provider routing reuses the verification billing rule: Paystack for
 * Paystack-supported countries, PayPal everywhere else. The currency of a plan
 * comes from the CREATOR's profile country (USD when unset); the provider is
 * chosen from the SUBSCRIBER's profile country. The fan never sends an amount.
 */
class SubscriptionService(
    private val paystack: PaystackClient = PaystackClient(),
    private val paypal: PayPalClient = PayPalClient(),
    private val wallet: com.telefam.wallet.WalletService? = null
) {
    // ---------------- DTOs (wire format, mirrored in the shared module) ----------------

    @Serializable
    data class PlanDto(
        val id: String, val name: String, val description: String,
        val interval: String, val priceMinor: Long, val formattedPrice: String,
        val currency: String, val isActive: Boolean, val isMostPopular: Boolean,
        val subscriberCount: Long = 0
    )

    @Serializable
    data class MetricDto(val value: Long, val deltaPercent: Double? = null)

    @Serializable
    data class CreatorOverviewDto(
        val currency: String,
        val totalEarningsMinorAllTime: Long,
        val totalEarningsFormatted: String,
        val periodDays: Int,
        val earnings: MetricDto,
        val earningsFormatted: String,
        val subscribers: MetricDto,
        val newSubscribers: MetricDto,
        val canceled: MetricDto
    )

    @Serializable
    data class InsightPointDto(val date: String, val earningsMinor: Long, val newSubscribers: Long, val canceled: Long)

    @Serializable
    data class InsightsDto(
        val periodDays: Int, val currency: String,
        val totalEarningsMinor: Long, val totalEarningsFormatted: String,
        val newSubscribers: Long, val canceled: Long, val activeSubscribers: Long,
        val series: List<InsightPointDto>
    )

    @Serializable
    data class SubscriberDto(
        val subscriptionId: String, val userId: String, val name: String?,
        val username: String?, val avatarUrl: String?, val isVerified: Boolean,
        val planName: String, val interval: String, val status: String,
        val since: String, val renewsAt: String?
    )

    @Serializable
    data class CreatorPageDto(
        val creatorId: String, val name: String?, val username: String?,
        val avatarUrl: String?, val bio: String?, val isVerified: Boolean,
        val plans: List<PlanDto>,
        /** Provider the CURRENT VIEWER would pay with: PAYSTACK | PAYPAL. */
        val provider: String, val providerLabel: String,
        val viewerSubscription: MySubscriptionDto?
    )

    @Serializable
    data class MySubscriptionDto(
        val subscriptionId: String, val creatorId: String, val creatorName: String?,
        val creatorUsername: String?, val creatorAvatarUrl: String?, val creatorVerified: Boolean,
        val planName: String, val interval: String, val formattedPrice: String,
        val status: String, val renewsAt: String?, val autoRenew: Boolean,
        val startedAt: String? = null, val cancelAtPeriodEnd: Boolean = false,
        val provider: String? = null
    )

    @Serializable
    data class PaymentHistoryDto(
        val paymentId: String, val subscriptionId: String?, val creatorName: String?,
        val planName: String, val amountMinor: Long, val formattedAmount: String,
        val currency: String, val provider: String, val status: String,
        val createdAt: String, val paidAt: String?, val failureReason: String? = null,
        val refundStatus: String? = null
    )

    @Serializable
    data class CreatorPaymentDto(
        val paymentId: String, val subscriberName: String?, val planName: String,
        val amountMinor: Long, val formattedAmount: String, val currency: String,
        val provider: String, val status: String, val paidAt: String?,
        val refundStatus: String? = null
    )

    @Serializable
    data class RefundDto(
        val paymentId: String, val status: String, val amountMinor: Long,
        val formattedAmount: String, val currency: String, val createdAt: String,
        val updatedAt: String, val failureReason: String? = null
    )

    @Serializable
    data class InitiatedSubscriptionPayment(
        val paymentId: String, val subscriptionId: String, val provider: String,
        val checkoutUrl: String, val reference: String,
        val amountMinor: Long, val formattedAmount: String, val currency: String,
        /** True when an idempotent retry returned the already-created payment. */
        val reused: Boolean
    )

    @Serializable
    data class PaymentStatusDto(
        val paymentId: String, val status: String, val failureReason: String? = null,
        val subscriptionId: String? = null, val subscriptionStatus: String? = null
    )

    private val DIAL_TO_COUNTRY = mapOf(
        "+254" to "KE", "+255" to "TZ", "+256" to "UG", "+250" to "RW",
        "+234" to "NG", "+233" to "GH", "+27" to "ZA",
        "+1" to "US", "+44" to "GB", "+49" to "DE", "+33" to "FR", "+39" to "IT",
        "+34" to "ES", "+31" to "NL", "+353" to "IE", "+61" to "AU", "+91" to "IN"
    )

    private suspend fun countryOf(userId: UUID): String = dbQuery {
        Users.selectAll().where { Users.id eq userId }.singleOrNull()
            ?.get(Users.phoneCountryCode)?.let { DIAL_TO_COUNTRY[it] }
    } ?: "US"

    private suspend fun currencyOf(userId: UUID): String =
        PricingCatalog.currencyFor(countryOf(userId))

    private fun fmt(currency: String, minor: Long) = PricingCatalog.format(currency, minor)

    private fun planDto(row: ResultRow, subscriberCount: Long = 0) = PlanDto(
        id = row[SubscriptionPlans.id].value.toString(),
        name = row[SubscriptionPlans.name],
        description = row[SubscriptionPlans.description],
        interval = row[SubscriptionPlans.interval],
        priceMinor = row[SubscriptionPlans.priceMinor],
        formattedPrice = fmt(row[SubscriptionPlans.currency], row[SubscriptionPlans.priceMinor]),
        currency = row[SubscriptionPlans.currency],
        isActive = row[SubscriptionPlans.isActive],
        isMostPopular = row[SubscriptionPlans.isMostPopular],
        subscriberCount = subscriberCount
    )

    // ---------------- Plans (creator CRUD) ----------------

    suspend fun listPlans(creatorId: UUID, ownerView: Boolean): List<PlanDto> = dbQuery {
        val base = SubscriptionPlans.selectAll().where { SubscriptionPlans.creatorId eq creatorId }
        val rows = (if (ownerView) base else base.andWhere { SubscriptionPlans.isActive eq true })
            .orderBy(SubscriptionPlans.isMostPopular to SortOrder.DESC, SubscriptionPlans.priceMinor to SortOrder.DESC)
            .toList()
        rows.map { r -> planDto(r, activeSubCountForPlan(r[SubscriptionPlans.id].value)) }
    }

    private fun activeSubCountForPlan(planId: UUID): Long =
        PaidSubscriptions.selectAll().where {
            (PaidSubscriptions.planId eq planId) and
                (PaidSubscriptions.status inList listOf("ACTIVE", "PAST_DUE"))
        }.count()

    suspend fun createPlan(creatorId: UUID, name: String, description: String, interval: String, priceMinor: Long): PlanDto {
        require(name.isNotBlank() && name.length <= 60) { "Plan name is required (max 60 chars)" }
        require(description.length <= 200) { "Description too long (max 200 chars)" }
        require(interval in setOf("DAILY", "WEEKLY", "MONTHLY")) { "Invalid interval" }
        require(priceMinor in 50..100_000_000) { "Price must be at least 0.50 and reasonable" }
        val currency = currencyOf(creatorId)
        val now = LocalDateTime.now()
        val id = UUID.randomUUID()
        dbQuery {
            SubscriptionPlans.insert {
                it[SubscriptionPlans.id] = id
                it[SubscriptionPlans.creatorId] = creatorId
                it[SubscriptionPlans.name] = name.trim()
                it[SubscriptionPlans.description] = description.trim()
                it[SubscriptionPlans.interval] = interval
                it[SubscriptionPlans.priceMinor] = priceMinor
                it[SubscriptionPlans.currency] = currency
                it[isActive] = true
                it[isMostPopular] = false
                it[createdAt] = now; it[updatedAt] = now
            }
        }
        return dbQuery { planDto(SubscriptionPlans.selectAll().where { SubscriptionPlans.id eq id }.single()) }
    }

    suspend fun updatePlan(creatorId: UUID, planId: UUID, name: String, description: String, priceMinor: Long, isMostPopular: Boolean): PlanDto {
        require(name.isNotBlank() && name.length <= 60) { "Plan name is required" }
        require(priceMinor in 50..100_000_000) { "Invalid price" }
        val row = dbQuery {
            SubscriptionPlans.selectAll().where {
                (SubscriptionPlans.id eq planId) and (SubscriptionPlans.creatorId eq creatorId)
            }.singleOrNull()
        } ?: throw NoSuchElementException("Plan not found")
        dbQuery {
            // Only one "Most Popular" per creator.
            if (isMostPopular) SubscriptionPlans.update({ SubscriptionPlans.creatorId eq creatorId }) {
                it[SubscriptionPlans.isMostPopular] = false
            }
            SubscriptionPlans.update({ SubscriptionPlans.id eq planId }) {
                it[SubscriptionPlans.name] = name.trim()
                it[SubscriptionPlans.description] = description.trim()
                it[SubscriptionPlans.priceMinor] = priceMinor
                it[SubscriptionPlans.isMostPopular] = isMostPopular
                it[updatedAt] = LocalDateTime.now()
            }
        }
        return dbQuery { planDto(SubscriptionPlans.selectAll().where { SubscriptionPlans.id eq planId }.single(), activeSubCountForPlan(planId)) }
    }

    /** Soft-delete: existing subscribers keep access until period end; the plan stops being offered. */
    suspend fun deletePlan(creatorId: UUID, planId: UUID) {
        val changed = dbQuery {
            SubscriptionPlans.update({
                (SubscriptionPlans.id eq planId) and (SubscriptionPlans.creatorId eq creatorId)
            }) {
                it[isActive] = false; it[isMostPopular] = false; it[updatedAt] = LocalDateTime.now()
            }
        }
        if (changed == 0) throw NoSuchElementException("Plan not found")
    }

    // ---------------- Fan-facing subscribe page ----------------

    suspend fun creatorPage(creatorId: UUID, viewerId: UUID): CreatorPageDto {
        sweepExpired()
        val creator = dbQuery {
            Users.selectAll().where {
                (Users.id eq creatorId) and (Users.profileComplete eq true) and (Users.accountStatus eq "ACTIVE")
            }.singleOrNull()
        } ?: throw NoSuchElementException("Creator not found")
        val viewerCountry = countryOf(viewerId)
        val provider = PricingCatalog.providerFor(viewerCountry)
        val verified = dbQuery {
            com.telefam.payments.VerificationBadges.selectAll().where {
                com.telefam.payments.VerificationBadges.userId eq creatorId
            }.singleOrNull()?.let {
                it[com.telefam.payments.VerificationBadges.expiresAt].isAfter(LocalDateTime.now()) ||
                    (it[com.telefam.payments.VerificationBadges.graceUntil]?.isAfter(LocalDateTime.now()) == true)
            }
        } == true
        val existing = dbQuery {
            PaidSubscriptions.join(SubscriptionPlans, JoinType.INNER, PaidSubscriptions.planId, SubscriptionPlans.id)
                .selectAll().where {
                    (PaidSubscriptions.subscriberId eq viewerId) and
                        (PaidSubscriptions.creatorId eq creatorId) and
                        (PaidSubscriptions.status inList listOf("ACTIVE", "PAST_DUE", "CANCELED", "PENDING_PAYMENT"))
                }.orderBy(PaidSubscriptions.updatedAt, SortOrder.DESC).firstOrNull()
        }
        return CreatorPageDto(
            creatorId = creatorId.toString(),
            name = creator[Users.fullName],
            username = creator[Users.username],
            avatarUrl = if (creator[Users.profileImageMediaId] != null) "/api/feeds/avatar/$creatorId" else null,
            bio = creator[Users.bio],
            isVerified = verified,
            plans = listPlans(creatorId, ownerView = false),
            provider = provider.name,
            providerLabel = if (provider == PricingCatalog.Provider.PAYSTACK) "Paystack" else "PayPal",
            viewerSubscription = existing?.let { mySubDto(it) }
        )
    }

    private fun mySubDto(row: ResultRow): MySubscriptionDto {
        val creatorId = row[PaidSubscriptions.creatorId]
        val creator = Users.selectAll().where { Users.id eq creatorId }.single()
        val verified = com.telefam.payments.VerificationBadges.selectAll().where {
            com.telefam.payments.VerificationBadges.userId eq creatorId
        }.singleOrNull()?.let {
            it[com.telefam.payments.VerificationBadges.expiresAt].isAfter(LocalDateTime.now()) ||
                (it[com.telefam.payments.VerificationBadges.graceUntil]?.isAfter(LocalDateTime.now()) == true)
        } == true
        return MySubscriptionDto(
            subscriptionId = row[PaidSubscriptions.id].value.toString(),
            creatorId = creatorId.toString(),
            creatorName = creator[Users.fullName],
            creatorUsername = creator[Users.username],
            creatorAvatarUrl = if (creator[Users.profileImageMediaId] != null) "/api/feeds/avatar/$creatorId" else null,
            creatorVerified = verified,
            planName = row[SubscriptionPlans.name],
            interval = row[PaidSubscriptions.recurringInterval] ?: row[SubscriptionPlans.interval],
            formattedPrice = fmt(
                row[PaidSubscriptions.recurringCurrency] ?: row[SubscriptionPlans.currency],
                row[PaidSubscriptions.recurringAmountMinor] ?: row[SubscriptionPlans.priceMinor]
            ),
            status = row[PaidSubscriptions.status],
            renewsAt = row[PaidSubscriptions.currentPeriodEnd]?.toString(),
            autoRenew = row[PaidSubscriptions.autoRenew],
            startedAt = row[PaidSubscriptions.currentPeriodStart]?.toString(),
            cancelAtPeriodEnd = row[PaidSubscriptions.cancelAtPeriodEnd],
            provider = SubscriptionPayments.selectAll().where {
                SubscriptionPayments.subscriptionId eq row[PaidSubscriptions.id].value
            }.orderBy(SubscriptionPayments.createdAt, SortOrder.ASC).firstOrNull()
                ?.get(SubscriptionPayments.provider)
        )
    }

    /** Everything the current user subscribes to (fan's own list — used for manage/unsubscribe). */
    suspend fun mySubscriptions(viewerId: UUID): List<MySubscriptionDto> {
        sweepExpired()
        return dbQuery {
            PaidSubscriptions.join(SubscriptionPlans, JoinType.INNER, PaidSubscriptions.planId, SubscriptionPlans.id).selectAll().where {
                (PaidSubscriptions.subscriberId eq viewerId) and
                    (PaidSubscriptions.status inList listOf("ACTIVE", "PAST_DUE", "CANCELED", "EXPIRED"))
            }.orderBy(PaidSubscriptions.updatedAt, SortOrder.DESC).map { mySubDto(it) }
        }
    }

    suspend fun myPaymentHistory(viewerId: UUID): List<PaymentHistoryDto> = dbQuery {
        SubscriptionPayments.selectAll().where { SubscriptionPayments.subscriberId eq viewerId }
            .orderBy(SubscriptionPayments.createdAt, SortOrder.DESC).limit(200).map { payment ->
                val plan = SubscriptionPlans.selectAll().where {
                    SubscriptionPlans.id eq payment[SubscriptionPayments.planId]
                }.singleOrNull()
                val creator = Users.selectAll().where {
                    Users.id eq payment[SubscriptionPayments.creatorId]
                }.singleOrNull()
                PaymentHistoryDto(
                    paymentId = payment[SubscriptionPayments.id].value.toString(),
                    subscriptionId = payment[SubscriptionPayments.subscriptionId]?.toString(),
                    creatorName = creator?.get(Users.fullName) ?: creator?.get(Users.username),
                    planName = plan?.get(SubscriptionPlans.name) ?: "Archived plan",
                    amountMinor = payment[SubscriptionPayments.amountMinor],
                    formattedAmount = fmt(payment[SubscriptionPayments.currency], payment[SubscriptionPayments.amountMinor]),
                    currency = payment[SubscriptionPayments.currency],
                    provider = payment[SubscriptionPayments.provider],
                    status = payment[SubscriptionPayments.status],
                    createdAt = payment[SubscriptionPayments.createdAt].toString(),
                    paidAt = payment[SubscriptionPayments.paidAt]?.toString(),
                    failureReason = payment[SubscriptionPayments.failureReason],
                    refundStatus = SubscriptionRefunds.selectAll().where {
                        SubscriptionRefunds.paymentId eq payment[SubscriptionPayments.id].value
                    }.singleOrNull()?.get(SubscriptionRefunds.status)
                )
            }
    }

    /** Recent charges received by this creator, including live refund state. */
    suspend fun creatorPaymentHistory(creatorId: UUID, limit: Int = 100): List<CreatorPaymentDto> = dbQuery {
        SubscriptionPayments.selectAll().where { SubscriptionPayments.creatorId eq creatorId }
            .orderBy(SubscriptionPayments.createdAt, SortOrder.DESC).limit(limit.coerceIn(1, 200)).map { payment ->
                val subscriber = Users.selectAll().where {
                    Users.id eq payment[SubscriptionPayments.subscriberId]
                }.singleOrNull()
                val plan = SubscriptionPlans.selectAll().where {
                    SubscriptionPlans.id eq payment[SubscriptionPayments.planId]
                }.singleOrNull()
                CreatorPaymentDto(
                    paymentId = payment[SubscriptionPayments.id].value.toString(),
                    subscriberName = subscriber?.get(Users.fullName) ?: subscriber?.get(Users.username),
                    planName = plan?.get(SubscriptionPlans.name) ?: "Archived plan",
                    amountMinor = payment[SubscriptionPayments.amountMinor],
                    formattedAmount = fmt(payment[SubscriptionPayments.currency], payment[SubscriptionPayments.amountMinor]),
                    currency = payment[SubscriptionPayments.currency],
                    provider = payment[SubscriptionPayments.provider],
                    status = payment[SubscriptionPayments.status],
                    paidAt = payment[SubscriptionPayments.paidAt]?.toString(),
                    refundStatus = SubscriptionRefunds.selectAll().where {
                        SubscriptionRefunds.paymentId eq payment[SubscriptionPayments.id].value
                    }.singleOrNull()?.get(SubscriptionRefunds.status)
                )
            }
    }

    private fun refundDto(row: ResultRow) = RefundDto(
        paymentId = row[SubscriptionRefunds.paymentId].toString(),
        status = row[SubscriptionRefunds.status],
        amountMinor = row[SubscriptionRefunds.amountMinor],
        formattedAmount = fmt(row[SubscriptionRefunds.currency], row[SubscriptionRefunds.amountMinor]),
        currency = row[SubscriptionRefunds.currency],
        createdAt = row[SubscriptionRefunds.createdAt].toString(),
        updatedAt = row[SubscriptionRefunds.updatedAt].toString(),
        failureReason = row[SubscriptionRefunds.failureReason]
    )

    /**
     * Subscriber-initiated refund under the configurable automatic-refund window
     * (SUBSCRIPTION_REFUND_WINDOW_MINUTES, default 15). Eligibility is enforced
     * server-side: within the window + payment PAID + not already refunded → the
     * refund is submitted automatically. Outside the window, exceptional cases
     * (unauthorized charge, duplicate, technical error, legal rights, provider
     * reversals) are recorded as NEEDS_REVIEW for the support/review path.
     */
    suspend fun requestSubscriberRefund(subscriberId: UUID, paymentId: UUID, reason: String): RefundDto {
        val payment = dbQuery {
            SubscriptionPayments.selectAll().where {
                (SubscriptionPayments.id eq paymentId) and (SubscriptionPayments.subscriberId eq subscriberId)
            }.singleOrNull()
        } ?: throw NoSuchElementException("Payment not found")
        if (payment[SubscriptionPayments.status] != "PAID")
            throw IllegalStateException("Only a successful payment can be refunded")

        val existing = dbQuery {
            SubscriptionRefunds.selectAll().where { SubscriptionRefunds.paymentId eq paymentId }.singleOrNull()
        }
        if (existing != null) {
            reconcileRefund(existing[SubscriptionRefunds.id].value)
            return refundDto(existing.let {
                dbQuery { SubscriptionRefunds.selectAll().where { SubscriptionRefunds.id eq it[SubscriptionRefunds.id] }.single() }
            })
        }

        val now = LocalDateTime.now()
        val paidAt = payment[SubscriptionPayments.paidAt] ?: throw IllegalStateException("Payment not settled")
        val withinWindow = ChronoUnit.MINUTES.between(paidAt, now) <= AppConfig.subscriptionRefundWindowMinutes

        val refundId = UUID.randomUUID()
        dbQuery {
            SubscriptionRefunds.insert {
                it[id] = refundId
                it[SubscriptionRefunds.paymentId] = paymentId
                it[creatorId] = payment[SubscriptionPayments.creatorId]
                it[SubscriptionRefunds.subscriberId] = subscriberId
                it[provider] = payment[SubscriptionPayments.provider]
                it[requestId] = UUID.randomUUID()
                it[amountMinor] = payment[SubscriptionPayments.amountMinor]
                it[currency] = payment[SubscriptionPayments.currency]
                it[status] = if (withinWindow) "SUBMITTING" else "NEEDS_REVIEW"
                it[failureReason] = if (withinWindow) null else "outside_window:${reason.take(200)}"
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
        val row = dbQuery { SubscriptionRefunds.selectAll().where { SubscriptionRefunds.id eq refundId }.single() }
        if (withinWindow) submitRefund(row, payment)
        return refundDto(dbQuery { SubscriptionRefunds.selectAll().where { SubscriptionRefunds.id eq refundId }.single() })
    }

    /** Only the receiving creator may request a full refund; the client sends no amount or provider data. */
    suspend fun requestRefund(creatorId: UUID, paymentId: UUID): RefundDto {
        val payment = dbQuery {
            SubscriptionPayments.selectAll().where {
                (SubscriptionPayments.id eq paymentId) and (SubscriptionPayments.creatorId eq creatorId)
            }.singleOrNull()
        } ?: throw NoSuchElementException("Payment not found")

        val existing = dbQuery {
            SubscriptionRefunds.selectAll().where { SubscriptionRefunds.paymentId eq paymentId }.singleOrNull()
        }
        if (existing != null) {
            reconcileRefund(existing[SubscriptionRefunds.id].value)
            return creatorRefundStatus(creatorId, paymentId)!!
        }
        if (payment[SubscriptionPayments.status] != "PAID") {
            throw IllegalStateException("Only a settled payment can be refunded")
        }

        val now = LocalDateTime.now()
        val refundId = UUID.randomUUID()
        val requestId = UUID.randomUUID()
        val inserted = runCatching {
            dbQuery {
                SubscriptionRefunds.insert {
                    it[SubscriptionRefunds.id] = refundId
                    it[SubscriptionRefunds.paymentId] = paymentId
                    it[SubscriptionRefunds.creatorId] = creatorId
                    it[SubscriptionRefunds.subscriberId] = payment[SubscriptionPayments.subscriberId]
                    it[provider] = payment[SubscriptionPayments.provider]
                    it[SubscriptionRefunds.requestId] = requestId
                    it[amountMinor] = payment[SubscriptionPayments.amountMinor]
                    it[currency] = payment[SubscriptionPayments.currency]
                    it[status] = "SUBMITTING"
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
            true
        }.getOrDefault(false)

        val request = dbQuery {
            SubscriptionRefunds.selectAll().where { SubscriptionRefunds.paymentId eq paymentId }.singleOrNull()
        } ?: throw IllegalStateException("Refund request could not be recorded; no provider call was made")
        if (inserted) submitRefund(request, payment)
        else reconcileRefund(request[SubscriptionRefunds.id].value)
        return creatorRefundStatus(creatorId, paymentId)!!
    }

    suspend fun creatorRefundStatus(creatorId: UUID, paymentId: UUID): RefundDto? {
        val paymentExists = dbQuery {
            SubscriptionPayments.selectAll().where {
                (SubscriptionPayments.id eq paymentId) and (SubscriptionPayments.creatorId eq creatorId)
            }.count() > 0
        }
        if (!paymentExists) throw NoSuchElementException("Payment not found")
        return dbQuery {
            SubscriptionRefunds.selectAll().where { SubscriptionRefunds.paymentId eq paymentId }
                .singleOrNull()?.let(::refundDto)
        }
    }

    private suspend fun submitRefund(
        refund: ResultRow,
        payment: ResultRow
    ) {
        val submission = when (refund[SubscriptionRefunds.provider]) {
            "PAYSTACK" -> runCatching {
                paystack.requestFullRefund(payment[SubscriptionPayments.providerRef]).let {
                    ProviderRefundSubmission(it.status, it.refundId, it.failureReason)
                }
            }.getOrElse {
                ProviderRefundSubmission("UNKNOWN", failureReason = "provider_response_unavailable")
            }
            "PAYPAL" -> {
                val order = runCatching { paypal.getOrder(payment[SubscriptionPayments.providerRef]) }.getOrNull()
                if (order == null) {
                    ProviderRefundSubmission(
                        "UNKNOWN", failureReason = "provider_order_unavailable"
                    )
                } else if (!order.paid || order.amountMinor != payment[SubscriptionPayments.amountMinor] ||
                    !order.currency.equals(payment[SubscriptionPayments.currency], true)
                ) {
                    ProviderRefundSubmission("NEEDS_REVIEW", failureReason = "provider_order_mismatch")
                } else {
                    val captureId = order.captureId
                    if (captureId == null) {
                        ProviderRefundSubmission("NEEDS_REVIEW", failureReason = "capture_id_missing")
                    } else runCatching {
                        paypal.requestFullRefund(captureId, refund[SubscriptionRefunds.requestId].toString()).let {
                            ProviderRefundSubmission(it.status, it.refundId, it.failureReason)
                        }
                    }.getOrElse {
                        ProviderRefundSubmission("UNKNOWN", failureReason = "provider_response_unavailable")
                    }
                }
            }
            else -> return updateRefundResult(
                refund[SubscriptionRefunds.id].value, "NEEDS_REVIEW", null, "unsupported_provider"
            )
        }
        val normalized = normalizeRefundStatus(refund[SubscriptionRefunds.provider], submission.status)
        if (normalized == "PROCESSED") {
            // A create response is not enough to finalize: verify provider amount/currency
            // through the refund lookup or capture totals first.
            updateRefundResult(
                refund[SubscriptionRefunds.id].value, "PROCESSING", submission.refundId, null
            )
            reconcileRefund(refund[SubscriptionRefunds.id].value)
            return
        }
        updateRefundResult(
            refund[SubscriptionRefunds.id].value,
            normalized,
            submission.refundId,
            submission.failureReason
        )
    }

    private fun normalizeRefundStatus(provider: String, providerStatus: String): String = when (provider) {
        "PAYSTACK" -> when (providerStatus.uppercase()) {
            "PENDING", "QUEUED" -> "PENDING"
            "PROCESSING" -> "PROCESSING"
            "PROCESSED" -> "PROCESSED"
            "REVERSED" -> "NEEDS_REVIEW"
            "FAILED" -> "FAILED"
            else -> "UNKNOWN"
        }
        "PAYPAL" -> when (providerStatus.uppercase()) {
            "PENDING" -> "PENDING"
            "COMPLETED" -> "PROCESSED"
            "FAILED", "DENIED" -> "FAILED"
            "NEEDS_REVIEW" -> "NEEDS_REVIEW"
            else -> "UNKNOWN"
        }
        else -> "NEEDS_REVIEW"
    }

    private suspend fun updateRefundResult(
        refundId: UUID,
        status: String,
        providerRefundRef: String?,
        failureReason: String?
    ) {
        var refundedPaymentId: UUID? = null
        dbQuery {
            val now = LocalDateTime.now()
            SubscriptionRefunds.update({ SubscriptionRefunds.id eq refundId }) {
                it[SubscriptionRefunds.status] = status
                if (providerRefundRef != null) it[SubscriptionRefunds.providerRefundRef] = providerRefundRef
                it[SubscriptionRefunds.failureReason] = failureReason?.take(300)
                it[lastReconciledAt] = now
                it[updatedAt] = now
                if (status == "PROCESSED") it[completedAt] = now
            }
            if (status == "PROCESSED") {
                applyRefundToPaymentAndEntitlement(refundId, now)
                refundedPaymentId = SubscriptionRefunds.selectAll().where { SubscriptionRefunds.id eq refundId }
                    .singleOrNull()?.get(SubscriptionRefunds.paymentId)
            }
        }
        // Wallet: a confirmed refund reverses the creator's earning (the earning
        // row is kept — reversal entries, never deletes). Idempotent by refund id,
        // so webhook/poll races and maintenance re-sweeps are all safe.
        val pid = refundedPaymentId
        if (status == "PROCESSED" && pid != null) {
            val walletService = wallet ?: return
            runCatching {
                walletService.reverseEarningForRefund("SUBSCRIPTION_PAYMENT", pid.toString(), refundId.toString())
            }
        }
    }

    /** Finalization is guarded by the payment's PAID state, making webhook/poll races idempotent. */
    private fun applyRefundToPaymentAndEntitlement(refundId: UUID, now: LocalDateTime) {
        val refund = SubscriptionRefunds.selectAll().where { SubscriptionRefunds.id eq refundId }.singleOrNull() ?: return
        val paymentId = refund[SubscriptionRefunds.paymentId]
        val payment = SubscriptionPayments.selectAll().where { SubscriptionPayments.id eq paymentId }.singleOrNull() ?: return
        SubscriptionPayments.update({
            (SubscriptionPayments.id eq paymentId) and (SubscriptionPayments.status eq "PAID")
        }) {
            it[status] = "REFUNDED"
            it[refundedAt] = now
            it[updatedAt] = now
        }
        val subId = payment[SubscriptionPayments.subscriptionId] ?: return
        val sub = PaidSubscriptions.selectAll().where { PaidSubscriptions.id eq subId }.singleOrNull() ?: return
        val refundedPeriodStart = payment[SubscriptionPayments.billingPeriodStart]
        val currentPeriodStart = sub[PaidSubscriptions.currentPeriodStart]
        // Only revoke access when this refund reverses the period currently being used.
        if (refundedPeriodStart != null && refundedPeriodStart == currentPeriodStart &&
            sub[PaidSubscriptions.status] in listOf("ACTIVE", "PAST_DUE")
        ) {
            PaidSubscriptions.update({ PaidSubscriptions.id eq subId }) {
                it[status] = "CANCELED"
                it[autoRenew] = false
                it[cancelAtPeriodEnd] = false
                it[renewalToken] = null
                it[nextRetryAt] = null
                it[currentPeriodEnd] = now
                it[canceledAt] = now
                it[updatedAt] = now
            }
            removePlatformSubscriberIfNoAccess(sub[PaidSubscriptions.subscriberId], sub[PaidSubscriptions.creatorId], now)
        }
    }

    private suspend fun reconcileRefund(refundId: UUID) {
        val refund = dbQuery {
            SubscriptionRefunds.selectAll().where { SubscriptionRefunds.id eq refundId }.singleOrNull()
        } ?: return
        if (refund[SubscriptionRefunds.status] in setOf("PROCESSED", "FAILED", "NEEDS_REVIEW")) return
        val payment = dbQuery {
            SubscriptionPayments.selectAll().where {
                SubscriptionPayments.id eq refund[SubscriptionRefunds.paymentId]
            }.singleOrNull()
        } ?: return updateRefundResult(refundId, "NEEDS_REVIEW", null, "payment_record_missing")

        when (refund[SubscriptionRefunds.provider]) {
            "PAYSTACK" -> {
                val snapshots = runCatching {
                    paystack.findRefunds(payment[SubscriptionPayments.providerRef])
                }.getOrNull()
                if (snapshots == null) {
                    return updateRefundResult(refundId, "UNKNOWN", null, "provider_status_unavailable")
                }
                if (snapshots.isEmpty()) {
                    // Paystack has no documented idempotency key for refund creation:
                    // never POST again after a timeout; keep reconciling by transaction reference.
                    return updateRefundResult(refundId, "UNKNOWN", null, "refund_not_yet_visible")
                }
                if (snapshots.size != 1) {
                    return updateRefundResult(refundId, "NEEDS_REVIEW", null, "multiple_provider_refunds_found")
                }
                val snapshot = snapshots.single()
                if (snapshot.amountMinor != refund[SubscriptionRefunds.amountMinor] ||
                    !snapshot.currency.equals(refund[SubscriptionRefunds.currency], true)
                ) {
                    return updateRefundResult(refundId, "NEEDS_REVIEW", snapshot.refundId, "provider_amount_mismatch")
                }
                updateRefundResult(
                    refundId,
                    normalizeRefundStatus("PAYSTACK", snapshot.status),
                    snapshot.refundId,
                    null
                )
            }
            "PAYPAL" -> {
                val existingRef = refund[SubscriptionRefunds.providerRefundRef]
                if (existingRef != null) {
                    val snapshot = runCatching { paypal.getRefund(existingRef) }.getOrNull()
                    if (snapshot == null) return updateRefundResult(refundId, "UNKNOWN", existingRef, "provider_status_unavailable")
                    if (snapshot.amountMinor != refund[SubscriptionRefunds.amountMinor] ||
                        !snapshot.currency.equals(refund[SubscriptionRefunds.currency], true)
                    ) {
                        return updateRefundResult(refundId, "NEEDS_REVIEW", existingRef, "provider_amount_mismatch")
                    }
                    return updateRefundResult(
                        refundId, normalizeRefundStatus("PAYPAL", snapshot.status), snapshot.refundId, null
                    )
                }

                val order = runCatching { paypal.getOrder(payment[SubscriptionPayments.providerRef]) }.getOrNull()
                    ?: return updateRefundResult(refundId, "UNKNOWN", null, "provider_order_unavailable")
                if (!order.paid || order.amountMinor != payment[SubscriptionPayments.amountMinor] ||
                    !order.currency.equals(payment[SubscriptionPayments.currency], true)
                ) {
                    return updateRefundResult(refundId, "NEEDS_REVIEW", null, "provider_order_mismatch")
                }
                val captureId = order.captureId
                    ?: return updateRefundResult(refundId, "NEEDS_REVIEW", null, "capture_id_missing")
                val capture = runCatching { paypal.getCaptureRefundState(captureId) }.getOrNull()
                    ?: return updateRefundResult(refundId, "UNKNOWN", null, "provider_capture_unavailable")
                val expected = refund[SubscriptionRefunds.amountMinor]
                if (capture.amountMinor != expected ||
                    !capture.currency.equals(refund[SubscriptionRefunds.currency], true) ||
                    capture.totalRefundedMinor > expected
                ) {
                    return updateRefundResult(refundId, "NEEDS_REVIEW", null, "provider_total_mismatch")
                }
                if (capture.totalRefundedMinor == expected && capture.status == "REFUNDED") {
                    return updateRefundResult(refundId, "PROCESSED", null, null)
                }
                if (capture.status in setOf("REFUNDED", "PARTIALLY_REFUNDED")) {
                    return updateRefundResult(refundId, "NEEDS_REVIEW", null, "provider_refund_total_unconfirmed")
                }
                if (capture.totalRefundedMinor > 0) {
                    return updateRefundResult(refundId, "NEEDS_REVIEW", null, "partial_or_pending_refund_detected")
                }

                // A retry with the identical PayPal-Request-Id is safe and recovers
                // the original request result; it cannot create a second refund.
                if (refund[SubscriptionRefunds.createdAt].isBefore(LocalDateTime.now().minusSeconds(30)) &&
                    refund[SubscriptionRefunds.paypalReplayCount] < 3
                ) {
                    val replayNumber = refund[SubscriptionRefunds.paypalReplayCount]
                    val claimed = dbQuery {
                        SubscriptionRefunds.update({
                            (SubscriptionRefunds.id eq refundId) and
                                (SubscriptionRefunds.paypalReplayCount eq replayNumber)
                        }) {
                            it[paypalReplayCount] = replayNumber + 1
                            it[updatedAt] = LocalDateTime.now()
                        } == 1
                    }
                    if (!claimed) return
                    val replay = runCatching {
                        paypal.requestFullRefund(captureId, refund[SubscriptionRefunds.requestId].toString())
                    }.getOrElse {
                        PayPalClient.RefundSubmission("UNKNOWN", failureReason = "provider_response_unavailable")
                    }
                    val replayStatus = normalizeRefundStatus("PAYPAL", replay.status)
                    if (replayStatus == "PROCESSED") {
                        updateRefundResult(refundId, "PROCESSING", replay.refundId, null)
                        return reconcileRefund(refundId)
                    }
                    return updateRefundResult(
                        refundId,
                        replayStatus,
                        replay.refundId,
                        replay.failureReason
                    )
                }
                updateRefundResult(
                    refundId, "UNKNOWN", null,
                    if (refund[SubscriptionRefunds.paypalReplayCount] >= 3)
                        "manual_reconciliation_required" else "refund_outcome_pending"
                )
            }
        }
    }

    private suspend fun reconcileRefunds(limit: Int = 50) {
        val cutoff = LocalDateTime.now().minusSeconds(15)
        val pending = dbQuery {
            SubscriptionRefunds.selectAll().where {
                (SubscriptionRefunds.status inList listOf("SUBMITTING", "UNKNOWN", "PENDING", "PROCESSING")) and
                    (SubscriptionRefunds.updatedAt less cutoff)
            }.orderBy(SubscriptionRefunds.updatedAt, SortOrder.ASC).limit(limit).toList()
        }
        pending.forEach { row -> runCatching { reconcileRefund(row[SubscriptionRefunds.id].value) } }
    }

    // ---------------- Subscribe + payment (idempotent, server-verified) ----------------

    /**
     * Creates (or idempotently returns) a PENDING payment + checkout URL.
     * Idempotency: the same client-generated key always maps to the same payment;
     * additionally, an unfinished PENDING payment for the same subscription is
     * reused so double-taps never create two charges.
     */
    suspend fun subscribe(subscriberId: UUID, planId: UUID, idempotencyKey: String): InitiatedSubscriptionPayment {
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 80) { "Missing idempotency key" }
        val plan = dbQuery {
            SubscriptionPlans.selectAll().where {
                (SubscriptionPlans.id eq planId) and (SubscriptionPlans.isActive eq true)
            }.singleOrNull()
        } ?: throw NoSuchElementException("Plan not found")
        val creatorId = plan[SubscriptionPlans.creatorId]
        require(creatorId != subscriberId) { "You cannot subscribe to your own plan" }

        // Idempotency layer 1: same key -> same payment.
        dbQuery {
            SubscriptionPayments.selectAll().where {
                SubscriptionPayments.idempotencyKey eq idempotencyKey
            }.singleOrNull()
        }?.let {
            if (it[SubscriptionPayments.subscriberId] != subscriberId) {
                throw IllegalStateException("Idempotency key is already in use")
            }
            return initiatedFromRow(it, reused = true)
        }

        // One subscription row per (subscriber, plan).
        val now = LocalDateTime.now()
        val subscriptionId = dbQuery {
            val existing = PaidSubscriptions.selectAll().where {
                (PaidSubscriptions.subscriberId eq subscriberId) and (PaidSubscriptions.planId eq planId)
            }.singleOrNull()
            if (existing != null) {
                val st = existing[PaidSubscriptions.status]
                if (st in listOf("ACTIVE", "PAST_DUE")) throw IllegalStateException("Already subscribed to this plan")
                existing[PaidSubscriptions.id].value
            } else {
                val id = UUID.randomUUID()
                PaidSubscriptions.insert {
                    it[PaidSubscriptions.id] = id
                    it[PaidSubscriptions.subscriberId] = subscriberId
                    it[PaidSubscriptions.creatorId] = creatorId
                    it[PaidSubscriptions.planId] = planId
                    it[status] = "PENDING_PAYMENT"
                    it[autoRenew] = true
                    it[createdAt] = now; it[updatedAt] = now
                }
                id
            }
        }

        // Idempotency layer 2: reuse an unfinished payment for this subscription.
        dbQuery {
            SubscriptionPayments.selectAll().where {
                (SubscriptionPayments.subscriptionId eq subscriptionId) and
                    (SubscriptionPayments.status eq "PENDING") and
                    (SubscriptionPayments.checkoutUrl.isNotNull())
            }.orderBy(SubscriptionPayments.createdAt, SortOrder.DESC).firstOrNull()
        }?.let { return initiatedFromRow(it, reused = true) }

        val amountMinor = plan[SubscriptionPlans.priceMinor]
        val currency = plan[SubscriptionPlans.currency]
        val country = countryOf(subscriberId)
        val provider = PricingCatalog.providerFor(country)
        val email = dbQuery { Users.selectAll().where { Users.id eq subscriberId }.single()[Users.email] }
        val paymentId = UUID.randomUUID()

        val (ref, url) = when (provider) {
            PricingCatalog.Provider.PAYSTACK -> {
                val init = paystack.initialize(
                    email = email, amountMinor = amountMinor, currency = currency,
                    reference = "tfsub_${paymentId.toString().replace("-", "")}",
                    callbackUrl = "${AppConfig.publicBaseUrl}/subscribe/callback"
                )
                init.reference to init.authorizationUrl
            }
            PricingCatalog.Provider.PAYPAL -> {
                val order = paypal.createOrder(
                    amountMinor = amountMinor, currency = currency,
                    returnUrl = "${AppConfig.publicBaseUrl}/subscribe/paypal/return",
                    cancelUrl = "${AppConfig.publicBaseUrl}/subscribe/paypal/cancel"
                )
                order.orderId to order.approveUrl
            }
        }

        dbQuery {
            SubscriptionPayments.insert {
                it[id] = paymentId
                it[SubscriptionPayments.subscriptionId] = subscriptionId
                it[SubscriptionPayments.subscriberId] = subscriberId
                it[SubscriptionPayments.creatorId] = creatorId
                it[SubscriptionPayments.planId] = planId
                it[SubscriptionPayments.provider] = provider.name
                it[SubscriptionPayments.amountMinor] = amountMinor
                it[SubscriptionPayments.currency] = currency
                it[countryCode] = country
                it[providerRef] = ref
                it[SubscriptionPayments.idempotencyKey] = idempotencyKey
                it[checkoutUrl] = url
                it[status] = "PENDING"
                it[createdAt] = now; it[updatedAt] = now
            }
        }
        return initiatedFromRow(dbQuery {
            SubscriptionPayments.selectAll().where { SubscriptionPayments.id eq paymentId }.single()
        }, reused = false)
    }

    private fun initiatedFromRow(row: ResultRow, reused: Boolean) = InitiatedSubscriptionPayment(
        paymentId = row[SubscriptionPayments.id].value.toString(),
        subscriptionId = row[SubscriptionPayments.subscriptionId].toString(),
        provider = row[SubscriptionPayments.provider],
        checkoutUrl = row[SubscriptionPayments.checkoutUrl] ?: "",
        reference = row[SubscriptionPayments.providerRef],
        amountMinor = row[SubscriptionPayments.amountMinor],
        formattedAmount = fmt(row[SubscriptionPayments.currency], row[SubscriptionPayments.amountMinor]),
        currency = row[SubscriptionPayments.currency],
        reused = reused
    )

    /** Client returned from checkout — re-verify with the provider (never trust the client). */
    suspend fun confirmPayment(subscriberId: UUID, paymentId: UUID): PaymentStatusDto {
        val row = dbQuery {
            SubscriptionPayments.selectAll().where {
                (SubscriptionPayments.id eq paymentId) and (SubscriptionPayments.subscriberId eq subscriberId)
            }.singleOrNull()
        } ?: throw NoSuchElementException("Payment not found")
        return settleWithProvider(row)
    }

    private suspend fun settleWithProvider(row: ResultRow): PaymentStatusDto {
        val paymentId = row[SubscriptionPayments.id].value
        if (row[SubscriptionPayments.status] == "PAID") return statusDto(row)
        if (row[SubscriptionPayments.status] in listOf("REFUNDED")) return statusDto(row)

        val provider = row[SubscriptionPayments.provider]
        val ref = row[SubscriptionPayments.providerRef]
        val expectedAmount = row[SubscriptionPayments.amountMinor]
        val expectedCurrency = row[SubscriptionPayments.currency]

        var reusableAuthorization: String? = null
        val (paid, amountCurrency, failure) = when (provider) {
            "PAYSTACK" -> paystack.verify(ref).let {
                if (it.reusable) reusableAuthorization = it.reusableAuthorizationCode
                Triple(it.paid, it.amountMinor to it.currency, it.failureReason)
            }
            else -> {
                val captured = runCatching { paypal.captureOrder(ref) }.getOrElse { paypal.getOrder(ref) }
                Triple(captured.paid, captured.amountMinor to captured.currency, if (captured.paid) null else captured.status)
            }
        }
        val amountOk = amountCurrency.first == expectedAmount && amountCurrency.second.equals(expectedCurrency, true)
        val newStatus = when {
            paid && amountOk -> "PAID"
            paid && !amountOk -> "FAILED" // tamper/provider error — flagged, never activates
            failure?.startsWith("verify_http_") == true -> "PENDING" // transient provider API failure; never retry-charge yet
            failure != null && failure !in listOf("PENDING", "CREATED", "APPROVED") -> "FAILED"
            else -> "PENDING"
        }
        var becamePaid = false
        dbQuery {
            val now = LocalDateTime.now()
            // A webhook and a polling request can race. Only the first transition
            // from PENDING may grant access or advance the billing period.
            val retryablePaidTransition = if (newStatus == "PAID")
                (SubscriptionPayments.status eq "FAILED") else Op.FALSE
            val changed = SubscriptionPayments.update({
                (SubscriptionPayments.id eq paymentId) and
                    ((SubscriptionPayments.status eq "PENDING") or retryablePaidTransition)
            }) {
                it[status] = newStatus
                it[paidAt] = if (newStatus == "PAID") now else null
                it[failureReason] = if (newStatus == "FAILED") (failure ?: "amount_mismatch") else null
                it[updatedAt] = now
            }
            if (changed == 1 && newStatus == "PAID") {
                activateSubscriptionInTransaction(row, reusableAuthorization)
                becamePaid = true
            } else if (changed == 1 && newStatus == "FAILED" && row[SubscriptionPayments.isRenewal]) {
                markRenewalPastDue(row, now, failure ?: "renewal_failed")
            }
        }
        // Wallet: provider-confirmed payment starts the creator's settlement window.
        // Idempotent by payment id — webhook/poll races can never double-credit.
        wallet?.takeIf { becamePaid }?.let { walletService ->
            runCatching {
                walletService.recordEarning(
                    creatorId = row[SubscriptionPayments.creatorId],
                    source = com.telefam.wallet.EarningSource.SUBSCRIPTION,
                    grossMinor = row[SubscriptionPayments.amountMinor],
                    currency = row[SubscriptionPayments.currency],
                    referenceType = "SUBSCRIPTION_PAYMENT",
                    referenceId = paymentId.toString(),
                    idempotencyKey = "subpay:$paymentId"
                )
            }
        }
        if (becamePaid) {
            val amountText = "${row[SubscriptionPayments.currency]} ${row[SubscriptionPayments.amountMinor] / 100.0}"
            val creatorId = row[SubscriptionPayments.creatorId]
            val subscriberId = row[SubscriptionPayments.subscriberId]
            // Payer: payment confirmation. Creator: new paid subscriber.
            com.telefam.notifications.NotificationService.notify(
                subscriberId, com.telefam.notifications.NotificationTypes.PAYMENT_SUCCESS,
                "Payment successful", "Your payment of $amountText was completed successfully.",
                actorId = creatorId,
                targetType = com.telefam.notifications.NotificationTargets.SUBSCRIPTIONS,
                targetId = creatorId.toString()
            )
            com.telefam.notifications.NotificationService.notify(
                creatorId, com.telefam.notifications.NotificationTypes.SUBSCRIPTION,
                "New paid subscriber", "subscribed to your plan ($amountText).",
                actorId = subscriberId,
                targetType = com.telefam.notifications.NotificationTargets.SUBSCRIPTIONS,
                targetId = creatorId.toString()
            )
        }
        return statusDto(dbQuery { SubscriptionPayments.selectAll().where { SubscriptionPayments.id eq paymentId }.single() })
    }

    /** Payment confirmed by the provider -> subscription period is advanced exactly once. */
    private fun activateSubscriptionInTransaction(paymentRow: ResultRow, authorizationCode: String?) {
        val subId = paymentRow[SubscriptionPayments.subscriptionId] ?: return
        val now = LocalDateTime.now()
        val sub = PaidSubscriptions.selectAll().where { PaidSubscriptions.id eq subId }.singleOrNull() ?: return
        val plan = SubscriptionPlans.selectAll().where { SubscriptionPlans.id eq sub[PaidSubscriptions.planId] }.single()
        val periodStart = sub[PaidSubscriptions.currentPeriodEnd]?.takeIf { it.isAfter(now) } ?: now
        val periodInterval = if (paymentRow[SubscriptionPayments.isRenewal])
            sub[PaidSubscriptions.recurringInterval] ?: plan[SubscriptionPlans.interval]
        else plan[SubscriptionPlans.interval]
        val end = when (periodInterval) {
            "DAILY" -> periodStart.plusDays(1)
            "WEEKLY" -> periodStart.plusDays(7)
            else -> periodStart.plusMonths(1)
        }
        val encryptedToken = authorizationCode?.let(PaymentTokenVault::encrypt)
        val paystackReusable = paymentRow[SubscriptionPayments.provider] == "PAYSTACK" && encryptedToken != null
        PaidSubscriptions.update({ PaidSubscriptions.id eq subId }) {
            it[status] = "ACTIVE"
            it[currentPeriodStart] = periodStart
            it[currentPeriodEnd] = end
            it[recurringAmountMinor] = if (paymentRow[SubscriptionPayments.isRenewal])
                sub[PaidSubscriptions.recurringAmountMinor] ?: paymentRow[SubscriptionPayments.amountMinor]
            else paymentRow[SubscriptionPayments.amountMinor]
            it[recurringCurrency] = if (paymentRow[SubscriptionPayments.isRenewal])
                sub[PaidSubscriptions.recurringCurrency] ?: paymentRow[SubscriptionPayments.currency]
            else paymentRow[SubscriptionPayments.currency]
            it[recurringInterval] = periodInterval
            val canceledBeforeSettlement = sub[PaidSubscriptions.status] == "CANCELED" ||
                sub[PaidSubscriptions.canceledAt] != null
            it[autoRenew] = if (paymentRow[SubscriptionPayments.isRenewal]) {
                sub[PaidSubscriptions.autoRenew]
            } else {
                paystackReusable && !canceledBeforeSettlement
            }
            if (encryptedToken != null) it[renewalToken] = encryptedToken
            if (authorizationCode != null) {
                it[billingEmail] = Users.selectAll().where {
                    Users.id eq sub[PaidSubscriptions.subscriberId]
                }.singleOrNull()?.get(Users.email)
            }
            it[status] = "ACTIVE"
            it[retryCount] = 0
            it[nextRetryAt] = null
            it[graceUntil] = null
            it[cancelAtPeriodEnd] = paymentRow[SubscriptionPayments.isRenewal] && sub[PaidSubscriptions.cancelAtPeriodEnd]
            it[canceledAt] = if (paymentRow[SubscriptionPayments.isRenewal]) sub[PaidSubscriptions.canceledAt] else null
            it[updatedAt] = now
        }
        SubscriptionPayments.update({ SubscriptionPayments.id eq paymentRow[SubscriptionPayments.id].value }) {
            it[billingPeriodStart] = periodStart
        }
        // Ensure legacy or expired platform-level subscriber links are restored.
        val exists = com.telefam.db.Subscriptions.selectAll().where {
            (com.telefam.db.Subscriptions.subscriberId eq sub[PaidSubscriptions.subscriberId]) and
                (com.telefam.db.Subscriptions.creatorId eq sub[PaidSubscriptions.creatorId])
        }.any()
        if (!exists) com.telefam.db.Subscriptions.insert {
            it[id] = UUID.randomUUID()
            it[subscriberId] = sub[PaidSubscriptions.subscriberId]
            it[creatorId] = sub[PaidSubscriptions.creatorId]
            it[createdAt] = now
        }
    }

    private fun markRenewalPastDue(paymentRow: ResultRow, now: LocalDateTime, reason: String) {
        val subId = paymentRow[SubscriptionPayments.subscriptionId] ?: return
        val sub = PaidSubscriptions.selectAll().where { PaidSubscriptions.id eq subId }.singleOrNull() ?: return
        if (sub[PaidSubscriptions.status] !in listOf("ACTIVE", "PAST_DUE") ||
            !sub[PaidSubscriptions.autoRenew] || sub[PaidSubscriptions.cancelAtPeriodEnd]) return
        val retries = sub[PaidSubscriptions.retryCount] + 1
        val graceEnd = sub[PaidSubscriptions.graceUntil] ?: now.plusDays(AppConfig.subscriptionGraceDays)
        val retryTime = if (retries <= AppConfig.subscriptionMaxRenewalRetries && now.isBefore(graceEnd)) {
            now.plusDays((1L shl (retries - 1).coerceIn(0, 2)))
        } else null
        PaidSubscriptions.update({ PaidSubscriptions.id eq subId }) {
            it[status] = "PAST_DUE"
            it[retryCount] = retries
            it[nextRetryAt] = retryTime
            it[graceUntil] = graceEnd
            it[updatedAt] = now
        }
    }

    private fun statusDto(row: ResultRow): PaymentStatusDto {
        val subId = row[SubscriptionPayments.subscriptionId]
        val subStatus = subId?.let {
            PaidSubscriptions.selectAll().where { PaidSubscriptions.id eq it }.singleOrNull()
                ?.get(PaidSubscriptions.status)
        }
        return PaymentStatusDto(
            paymentId = row[SubscriptionPayments.id].value.toString(),
            status = row[SubscriptionPayments.status],
            failureReason = row[SubscriptionPayments.failureReason],
            subscriptionId = subId?.toString(),
            subscriptionStatus = subStatus
        )
    }

    /** Single payment status read (fan polling); re-checks PENDING payments with the provider. */
    suspend fun paymentStatus(subscriberId: UUID, paymentId: UUID): PaymentStatusDto {
        val row = dbQuery {
            SubscriptionPayments.selectAll().where {
                (SubscriptionPayments.id eq paymentId) and (SubscriptionPayments.subscriberId eq subscriberId)
            }.singleOrNull()
        } ?: throw NoSuchElementException("Payment not found")
        return if (row[SubscriptionPayments.status] in listOf("PENDING", "FAILED")) settleWithProvider(row) else statusDto(row)
    }

    // ---------------- Unsubscribe / expiry sweep / pending poll ----------------

    /** Cancel renewal while preserving paid access through the end of the current period. */
    suspend fun unsubscribe(subscriberId: UUID, subscriptionId: UUID) {
        val now = LocalDateTime.now()
        val row = dbQuery {
            PaidSubscriptions.selectAll().where {
                (PaidSubscriptions.id eq subscriptionId) and (PaidSubscriptions.subscriberId eq subscriberId)
            }.singleOrNull()
        } ?: throw NoSuchElementException("Subscription not found")
        val changed = dbQuery {
            val end = row[PaidSubscriptions.currentPeriodEnd]
            val grace = row[PaidSubscriptions.graceUntil]
            if (row[PaidSubscriptions.status] == "PAST_DUE" && grace?.isAfter(now) == true) {
                val updated = PaidSubscriptions.update({ PaidSubscriptions.id eq subscriptionId }) {
                    it[autoRenew] = false
                    it[cancelAtPeriodEnd] = true
                    it[nextRetryAt] = null
                    it[canceledAt] = now
                    it[updatedAt] = now
                }
                removePlatformSubscriberIfNoAccess(row[PaidSubscriptions.subscriberId], row[PaidSubscriptions.creatorId], now)
                updated
            } else if (row[PaidSubscriptions.status] == "PENDING_PAYMENT" || end == null || !end.isAfter(now)) {
                val updated = PaidSubscriptions.update({ PaidSubscriptions.id eq subscriptionId }) {
                    it[status] = "CANCELED"
                    it[autoRenew] = false
                    it[cancelAtPeriodEnd] = false
                    it[canceledAt] = now
                    it[updatedAt] = now
                }
                removePlatformSubscriberIfNoAccess(row[PaidSubscriptions.subscriberId], row[PaidSubscriptions.creatorId], now)
                updated
            } else {
                PaidSubscriptions.update({ PaidSubscriptions.id eq subscriptionId }) {
                    it[autoRenew] = false
                    it[cancelAtPeriodEnd] = true
                    it[canceledAt] = now
                    it[nextRetryAt] = null
                    it[updatedAt] = now
                }
            }
        }
        if (changed == 0) throw NoSuchElementException("Subscription not found")
    }

    private fun removePlatformSubscriberIfNoAccess(subscriberId: UUID, creatorId: UUID, now: LocalDateTime) {
        val stillEntitled = PaidSubscriptions.selectAll().where {
            (PaidSubscriptions.subscriberId eq subscriberId) and
                (PaidSubscriptions.creatorId eq creatorId) and
                (PaidSubscriptions.status inList listOf("ACTIVE", "PAST_DUE")) and
                (PaidSubscriptions.currentPeriodEnd greaterEq now)
        }.any() || PaidSubscriptions.selectAll().where {
            (PaidSubscriptions.subscriberId eq subscriberId) and
                (PaidSubscriptions.creatorId eq creatorId) and
                (PaidSubscriptions.status eq "PAST_DUE") and
                (PaidSubscriptions.graceUntil greaterEq now)
        }.any()
        if (!stillEntitled) com.telefam.db.Subscriptions.deleteWhere {
            (com.telefam.db.Subscriptions.subscriberId eq subscriberId) and
                (com.telefam.db.Subscriptions.creatorId eq creatorId)
        }
    }

    /** Expire canceled subscriptions at term end and failed renewals after the grace period. */
    suspend fun sweepExpired() {
        val now = LocalDateTime.now()
        dbQuery {
            val expired = PaidSubscriptions.selectAll().where {
                (PaidSubscriptions.currentPeriodEnd.isNotNull()) and
                    (PaidSubscriptions.currentPeriodEnd less now) and
                    (
                        ((PaidSubscriptions.status eq "ACTIVE") and
                            ((PaidSubscriptions.cancelAtPeriodEnd eq true) or (PaidSubscriptions.autoRenew eq false))) or
                        ((PaidSubscriptions.status eq "PAST_DUE") and
                            (PaidSubscriptions.graceUntil less now))
                    )
            }.toList()
            expired.forEach { sub ->
                PaidSubscriptions.update({ PaidSubscriptions.id eq sub[PaidSubscriptions.id] }) {
                    it[status] = "EXPIRED"; it[updatedAt] = now
                }
                removePlatformSubscriberIfNoAccess(sub[PaidSubscriptions.subscriberId], sub[PaidSubscriptions.creatorId], now)
            }
        }
    }

    /**
     * Scheduled billing worker. It creates a unique renewal payment row before
     * calling Paystack, so a worker restart or duplicate scheduler cannot issue
     * a second charge for the same subscription period/retry number.
     */
    suspend fun processRenewals(limit: Int = 50) {
        val now = LocalDateTime.now()
        val due = dbQuery {
            PaidSubscriptions.selectAll().where {
                (PaidSubscriptions.status inList listOf("ACTIVE", "PAST_DUE")) and
                    (PaidSubscriptions.autoRenew eq true) and
                    (PaidSubscriptions.cancelAtPeriodEnd eq false) and
                    (PaidSubscriptions.currentPeriodEnd.isNotNull()) and
                    (PaidSubscriptions.currentPeriodEnd lessEq now) and
                    (
                        ((PaidSubscriptions.status eq "ACTIVE") and PaidSubscriptions.nextRetryAt.isNull()) or
                        ((PaidSubscriptions.status eq "PAST_DUE") and
                            (PaidSubscriptions.graceUntil greaterEq now) and
                            PaidSubscriptions.nextRetryAt.isNotNull() and (PaidSubscriptions.nextRetryAt lessEq now))
                    )
            }.orderBy(PaidSubscriptions.currentPeriodEnd, SortOrder.ASC).limit(limit).toList()
        }
        for (sub in due) runCatching { processRenewal(sub) }
        sweepExpired()
    }

    private suspend fun processRenewal(sub: ResultRow) {
        val subId = sub[PaidSubscriptions.id].value
        val encrypted = sub[PaidSubscriptions.renewalToken]
        val authCode = encrypted?.let(PaymentTokenVault::decrypt)
        val email = sub[PaidSubscriptions.billingEmail]
        if (authCode.isNullOrBlank() || email.isNullOrBlank() || !sub[PaidSubscriptions.autoRenew]) {
            dbQuery {
                PaidSubscriptions.update({ PaidSubscriptions.id eq subId }) {
                    it[autoRenew] = false
                    it[cancelAtPeriodEnd] = true
                    it[nextRetryAt] = null
                    it[updatedAt] = LocalDateTime.now()
                }
            }
            return
        }
        val plan = dbQuery {
            SubscriptionPlans.selectAll().where { SubscriptionPlans.id eq sub[PaidSubscriptions.planId] }.single()
        }
        val periodStart = sub[PaidSubscriptions.currentPeriodEnd] ?: return
        val attempt = sub[PaidSubscriptions.retryCount]
        val amountMinor = sub[PaidSubscriptions.recurringAmountMinor] ?: plan[SubscriptionPlans.priceMinor]
        val currency = sub[PaidSubscriptions.recurringCurrency] ?: plan[SubscriptionPlans.currency]
        val key = "renew_${subId}_${periodStart.toString().replace(Regex("[^0-9]"), "").take(14)}_$attempt"
        val existing = dbQuery {
            SubscriptionPayments.selectAll().where { SubscriptionPayments.idempotencyKey eq key }.singleOrNull()
        }
        if (existing != null) {
            // A previous worker may have reached Paystack before it stopped.
            if (existing[SubscriptionPayments.status] == "PENDING") runCatching { settleWithProvider(existing) }
            return
        }

        val subscriberId = sub[PaidSubscriptions.subscriberId]
        val country = countryOf(subscriberId)
        val paymentId = UUID.randomUUID()
        val ref = "tfr_${paymentId.toString().replace("-", "")}"
        val now = LocalDateTime.now()
        val inserted = runCatching {
            dbQuery {
                SubscriptionPayments.insert {
                    it[id] = paymentId
                    it[subscriptionId] = subId
                    it[SubscriptionPayments.isRenewal] = true
                    it[SubscriptionPayments.billingPeriodStart] = periodStart
                    it[SubscriptionPayments.subscriberId] = subscriberId
                    it[SubscriptionPayments.creatorId] = sub[PaidSubscriptions.creatorId]
                    it[SubscriptionPayments.planId] = plan[SubscriptionPlans.id].value
                    it[provider] = "PAYSTACK"
                    it[SubscriptionPayments.amountMinor] = amountMinor
                    it[SubscriptionPayments.currency] = currency
                    it[countryCode] = country
                    it[providerRef] = ref
                    it[idempotencyKey] = key
                    it[status] = "PENDING"
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
            true
        }.getOrDefault(false)
        if (!inserted) return

        val payment = dbQuery { SubscriptionPayments.selectAll().where { SubscriptionPayments.id eq paymentId }.single() }
        val charged = runCatching {
            paystack.chargeAuthorization(
                email, authCode, amountMinor, currency, ref
            )
        }.getOrNull()
        if (charged == null) {
            // Leave PENDING: a later provider verification resolves an ambiguous timeout
            // without issuing another charge.
            return
        }
        if (charged.accepted) {
            runCatching { settleWithProvider(payment) }
        } else {
            val responseCode = charged.failureReason?.removePrefix("charge_http_")?.toIntOrNull()
            if (responseCode != null && (responseCode == 429 || responseCode >= 500)) {
                // A gateway error may have occurred after Paystack accepted the
                // charge. Keep this attempt pending and reconcile by reference.
                return
            }
            dbQuery {
                val changed = SubscriptionPayments.update({
                    (SubscriptionPayments.id eq paymentId) and (SubscriptionPayments.status eq "PENDING")
                }) {
                    it[status] = "FAILED"
                    it[failureReason] = charged.failureReason?.take(300) ?: "renewal_declined"
                    it[updatedAt] = LocalDateTime.now()
                }
                if (changed == 1) markRenewalPastDue(payment, LocalDateTime.now(), charged.failureReason ?: "renewal_declined")
            }
        }
    }

    suspend fun billingMaintenance() {
        pollPendingPayments()
        processRenewals()
        reconcileRefunds()
    }

    /**
     * Poll PENDING payments older than [olderThanSeconds] against their provider.
     * Runs on creator overview and fan status reads, so abandoned-but-paid
     * checkouts settle even if the webhook never arrived.
     */
    suspend fun pollPendingPayments(olderThanSeconds: Long = 60, limit: Int = 25) {
        val cutoff = LocalDateTime.now().minus(olderThanSeconds, ChronoUnit.SECONDS)
        val pending = dbQuery {
            SubscriptionPayments.selectAll().where {
                (SubscriptionPayments.status eq "PENDING") and (SubscriptionPayments.createdAt less cutoff)
            }.orderBy(SubscriptionPayments.createdAt, SortOrder.ASC).limit(limit).toList()
        }
        pending.forEach { runCatching { settleWithProvider(it) } }
    }

    // ---------------- Webhooks (signature-verified, replay-safe, provider re-checked) ----------------

    suspend fun handlePaystackWebhook(rawBody: ByteArray, signature: String?) {
        if (!paystack.isValidWebhookSignature(rawBody, signature)) return
        val body = String(rawBody)
        val event = Regex("\"event\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: return
        val payloadHash = hashOf(rawBody)
        if (event.startsWith("refund.")) {
            val transactionRef = Regex("\"transaction_reference\"\\s*:\\s*\"([^\"]+)\"")
                .find(body)?.groupValues?.get(1)
                ?: Regex("\"reference\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            val refundIdFromEvent = Regex("\"id\"\\s*:\\s*\"?([A-Za-z0-9_-]+)\"?")
                .find(body)?.groupValues?.get(1)
            val refund = (if (transactionRef != null) {
                dbQuery {
                    val payment = SubscriptionPayments.selectAll().where {
                        (SubscriptionPayments.provider eq "PAYSTACK") and
                            (SubscriptionPayments.providerRef eq transactionRef)
                    }.singleOrNull() ?: return@dbQuery null
                    SubscriptionRefunds.selectAll().where {
                        SubscriptionRefunds.paymentId eq payment[SubscriptionPayments.id].value
                    }.singleOrNull()
                }
            } else null) ?: refundIdFromEvent?.let { providerRefundRef ->
                dbQuery {
                    SubscriptionRefunds.selectAll().where {
                        (SubscriptionRefunds.provider eq "PAYSTACK") and
                            (SubscriptionRefunds.providerRefundRef eq providerRefundRef)
                    }.singleOrNull()
                }
            } ?: return
            if (refund[SubscriptionRefunds.lastWebhookHash] == payloadHash) return
            dbQuery {
                SubscriptionRefunds.update({ SubscriptionRefunds.id eq refund[SubscriptionRefunds.id] }) {
                    it[lastWebhookHash] = payloadHash
                    it[updatedAt] = LocalDateTime.now()
                }
            }
            // The signed event is only a wake-up signal; the provider API is authoritative.
            reconcileRefund(refund[SubscriptionRefunds.id].value)
            return
        }
        val ref = Regex("\"reference\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: return
        applyWebhook("PAYSTACK", ref, event == "charge.success", hashOf(rawBody))
    }

    suspend fun handlePayPalWebhook(headers: Map<String, String>, rawBody: String) {
        if (!paypal.isValidWebhook(headers, rawBody)) return
        val eventType = Regex("\"event_type\"\\s*:\\s*\"([^\"]+)\"").find(rawBody)?.groupValues?.get(1)
        if (eventType?.contains("REFUND") == true || eventType?.contains("REVERSED") == true) {
            val pending = dbQuery {
                SubscriptionRefunds.selectAll().where {
                    (SubscriptionRefunds.provider eq "PAYPAL") and
                        (SubscriptionRefunds.status inList listOf("SUBMITTING", "UNKNOWN", "PENDING", "PROCESSING"))
                }.orderBy(SubscriptionRefunds.updatedAt, SortOrder.ASC).limit(50).toList()
            }
            pending.forEach { runCatching { reconcileRefund(it[SubscriptionRefunds.id].value) } }
            return
        }
        val orderId = Regex("\"order_id\"\\s*:\\s*\"([^\"]+)\"").find(rawBody)?.groupValues?.get(1)
            ?: Regex("\"id\"\\s*:\\s*\"([A-Z0-9]{17})\"").find(rawBody)?.groupValues?.get(1) ?: return
        val paid = rawBody.contains("PAYMENT.CAPTURE.COMPLETED") || rawBody.contains("CHECKOUT.ORDER.APPROVED")
        applyWebhook("PAYPAL", orderId, paid, hashOf(rawBody.toByteArray()))
    }

    private suspend fun applyWebhook(provider: String, ref: String, paidHint: Boolean, payloadHash: String) {
        val row = dbQuery {
            SubscriptionPayments.selectAll().where {
                (SubscriptionPayments.providerRef eq ref) and (SubscriptionPayments.provider eq provider)
            }.singleOrNull()
        } ?: return
        if (row[SubscriptionPayments.lastWebhookHash] == payloadHash) return // replay
        dbQuery {
            SubscriptionPayments.update({ SubscriptionPayments.id eq row[SubscriptionPayments.id] }) {
                it[lastWebhookHash] = payloadHash; it[updatedAt] = LocalDateTime.now()
            }
        }
        if (paidHint) settleWithProvider(row)
    }

    private fun hashOf(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ---------------- Creator dashboard: overview, insights, subscribers ----------------

    suspend fun overview(creatorId: UUID, periodDays: Int): CreatorOverviewDto {
        sweepExpired()
        pollPendingPayments()
        val currency = currencyOf(creatorId)
        val now = LocalDateTime.now()
        val since = now.minusDays(periodDays.toLong())
        val prevSince = since.minusDays(periodDays.toLong())
        return dbQuery {
            fun earningsBetween(from: LocalDateTime?, to: LocalDateTime?): Long {
                var cond: Op<Boolean> = (SubscriptionPayments.creatorId eq creatorId) and (SubscriptionPayments.status eq "PAID")
                if (from != null) cond = cond and (SubscriptionPayments.paidAt greaterEq from)
                if (to != null) cond = cond and (SubscriptionPayments.paidAt less to)
                return SubscriptionPayments.select(SubscriptionPayments.amountMinor).where(cond)
                    .sumOf { it[SubscriptionPayments.amountMinor] }
            }
            val allTime = earningsBetween(null, null)
            val inPeriod = earningsBetween(since, null)
            val prevPeriod = earningsBetween(prevSince, since)

            fun subsBetween(from: LocalDateTime, to: LocalDateTime?): Long {
                var cond: Op<Boolean> = (PaidSubscriptions.creatorId eq creatorId) and (PaidSubscriptions.createdAt greaterEq from)
                if (to != null) cond = cond and (PaidSubscriptions.createdAt less to)
                return PaidSubscriptions.selectAll().where(cond).count()
            }
            val newIn = subsBetween(since, null)
            val newPrev = subsBetween(prevSince, since)

            fun canceledBetween(from: LocalDateTime, to: LocalDateTime?): Long {
                var cond: Op<Boolean> = (PaidSubscriptions.creatorId eq creatorId) and
                    (PaidSubscriptions.canceledAt.isNotNull()) and (PaidSubscriptions.canceledAt greaterEq from)
                if (to != null) cond = cond and (PaidSubscriptions.canceledAt less to)
                return PaidSubscriptions.selectAll().where(cond).count()
            }
            val cancIn = canceledBetween(since, null)
            val cancPrev = canceledBetween(prevSince, since)

            val active = PaidSubscriptions.selectAll().where {
                (PaidSubscriptions.creatorId eq creatorId) and
                    (PaidSubscriptions.status inList listOf("ACTIVE", "PAST_DUE"))
            }.count()
            val activePrevCutoff = PaidSubscriptions.selectAll().where {
                (PaidSubscriptions.creatorId eq creatorId) and
                    (PaidSubscriptions.status eq "ACTIVE") and (PaidSubscriptions.createdAt less since)
            }.count()

            fun delta(cur: Long, prev: Long): Double? = when {
                prev > 0 -> ((cur - prev) * 100.0) / prev
                cur > 0 -> 100.0
                else -> null
            }
            CreatorOverviewDto(
                currency = currency,
                totalEarningsMinorAllTime = allTime,
                totalEarningsFormatted = fmt(currency, allTime),
                periodDays = periodDays,
                earnings = MetricDto(inPeriod, delta(inPeriod, prevPeriod)),
                earningsFormatted = fmt(currency, inPeriod),
                subscribers = MetricDto(active, delta(active, activePrevCutoff)),
                newSubscribers = MetricDto(newIn, delta(newIn, newPrev)),
                canceled = MetricDto(cancIn, delta(cancIn, cancPrev))
            )
        }
    }

    suspend fun insights(creatorId: UUID, periodDays: Int): InsightsDto {
        sweepExpired()
        val currency = currencyOf(creatorId)
        val now = LocalDateTime.now()
        val days = periodDays.coerceIn(1, 365)
        return dbQuery {
            val payments = SubscriptionPayments.selectAll().where {
                (SubscriptionPayments.creatorId eq creatorId) and (SubscriptionPayments.status eq "PAID") and
                    (SubscriptionPayments.paidAt greaterEq now.minusDays(days.toLong()))
            }.toList()
            val subs = PaidSubscriptions.selectAll().where {
                (PaidSubscriptions.creatorId eq creatorId) and (PaidSubscriptions.createdAt greaterEq now.minusDays(days.toLong()))
            }.toList()
            val cancels = PaidSubscriptions.selectAll().where {
                (PaidSubscriptions.creatorId eq creatorId) and (PaidSubscriptions.canceledAt.isNotNull()) and
                    (PaidSubscriptions.canceledAt greaterEq now.minusDays(days.toLong()))
            }.toList()
            val series = (0 until days).map { i ->
                val day = now.minusDays((days - 1 - i).toLong()).toLocalDate()
                InsightPointDto(
                    date = day.toString(),
                    earningsMinor = payments.filter { it[SubscriptionPayments.paidAt]?.toLocalDate() == day }
                        .sumOf { it[SubscriptionPayments.amountMinor] },
                    newSubscribers = subs.count { it[PaidSubscriptions.createdAt].toLocalDate() == day }.toLong(),
                    canceled = cancels.count { it[PaidSubscriptions.canceledAt]?.toLocalDate() == day }.toLong()
                )
            }
            val active = PaidSubscriptions.selectAll().where {
                (PaidSubscriptions.creatorId eq creatorId) and
                    (PaidSubscriptions.status inList listOf("ACTIVE", "PAST_DUE"))
            }.count()
            val total = payments.sumOf { it[SubscriptionPayments.amountMinor] }
            InsightsDto(
                periodDays = days, currency = currency,
                totalEarningsMinor = total, totalEarningsFormatted = fmt(currency, total),
                newSubscribers = subs.size.toLong(), canceled = cancels.size.toLong(),
                activeSubscribers = active, series = series
            )
        }
    }

    /** Creator's full subscribers list (See All) — joins users for identity + verified badge. */
    suspend fun subscribers(creatorId: UUID, limit: Int, offset: Int): List<SubscriberDto> {
        sweepExpired()
        return dbQuery {
            PaidSubscriptions.join(SubscriptionPlans, JoinType.INNER, PaidSubscriptions.planId, SubscriptionPlans.id).selectAll().where {
                (PaidSubscriptions.creatorId eq creatorId) and
                    (PaidSubscriptions.status inList listOf("ACTIVE", "PAST_DUE", "CANCELED"))
            }.orderBy(PaidSubscriptions.updatedAt, SortOrder.DESC)
                .limit(limit).offset(offset.toLong())
                .map { row ->
                    val uid = row[PaidSubscriptions.subscriberId]
                    val u = Users.selectAll().where { Users.id eq uid }.singleOrNull()
                    val verified = com.telefam.payments.VerificationBadges.selectAll().where {
                        com.telefam.payments.VerificationBadges.userId eq uid
                    }.singleOrNull()?.let {
                        it[com.telefam.payments.VerificationBadges.expiresAt].isAfter(LocalDateTime.now()) ||
                            (it[com.telefam.payments.VerificationBadges.graceUntil]?.isAfter(LocalDateTime.now()) == true)
                    } == true
                    SubscriberDto(
                        subscriptionId = row[PaidSubscriptions.id].value.toString(),
                        userId = uid.toString(),
                        name = u?.get(Users.fullName),
                        username = u?.get(Users.username),
                        avatarUrl = if (u?.get(Users.profileImageMediaId) != null) "/api/feeds/avatar/$uid" else null,
                        isVerified = verified,
                        planName = row[SubscriptionPlans.name],
                        interval = row[PaidSubscriptions.recurringInterval] ?: row[SubscriptionPlans.interval],
                        status = row[PaidSubscriptions.status],
                        since = row[PaidSubscriptions.createdAt].toString(),
                        renewsAt = row[PaidSubscriptions.currentPeriodEnd]?.toString()
                    )
                }
        }
    }

    suspend fun recentSubscribers(creatorId: UUID): List<SubscriberDto> = subscribers(creatorId, limit = 5, offset = 0)
}
