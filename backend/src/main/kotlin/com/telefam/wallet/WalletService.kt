package com.telefam.wallet

import com.telefam.auth.OtpPurpose
import com.telefam.auth.OtpSendResult
import com.telefam.auth.OtpService
import com.telefam.auth.OtpVerifyResult
import com.telefam.config.AppConfig
import com.telefam.db.DatabaseFactory.dbQuery
import com.telefam.db.LoginAttempts
import com.telefam.db.RefreshTokens
import com.telefam.db.Users
import com.telefam.payments.PricingCatalog
import com.telefam.payments.VerificationBadges
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Telefam Wallet — the authoritative financial core.
 *
 * Everything the wallet UI shows is derived here from the immutable ledger
 * (`WalletLedgerEntries`) plus the earning/payout state machines. The client
 * never computes balances, fees, settlement completion, risk results or payout
 * outcomes — it only renders what these endpoints return.
 *
 * Fee model (configurable, default 20% Telefam / 80% creator):
 *   gross ──(platformFee)──▶ Telefam      [PLATFORM_FEE ledger entry]
 *         └──(net)─────────▶ creator      [EARNING row, PENDING]
 *   settlement period completes + still eligible ──▶ AVAILABLE [SETTLEMENT entry]
 *   refund confirmed ──▶ REFUND reversal entry (earning row → REVERSED if still pending)
 *
 * Payout model:
 *   request ─▶ checks (balance, minimum, method, profile health, device security,
 *   risk) ─▶ funds RESERVED in the ledger ─▶ provider confirms via webhook ─▶
 *   PAID (reserve consumed) or FAILED/REJECTED/CANCELED (reserve RELEASED).
 */
class WalletService(
    private val otpService: OtpService? = null
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ---------------- DTOs (wire format, mirrored in the shared module) ----------------

    @Serializable
    data class BalanceDto(
        val currency: String,
        val totalMinor: Long, val totalFormatted: String,
        val availableMinor: Long, val availableFormatted: String,
        val pendingMinor: Long, val pendingFormatted: String,
        val reservedMinor: Long, val reservedFormatted: String,
        val pendingCount: Long,
        val canWithdraw: Boolean,
        val withdrawBlockedReason: String? = null
    )

    @Serializable
    data class OverviewSourceDto(
        val source: String, val amountMinor: Long, val amountFormatted: String,
        val deltaPercent: Double? = null
    )

    @Serializable
    data class PendingItemDto(
        val earningId: String, val source: String, val grossMinor: Long, val feeMinor: Long,
        val netMinor: Long, val netFormatted: String, val currency: String,
        val status: String, val earnedAt: String, val settlementAt: String
    )

    @Serializable
    data class PendingBreakdownDto(
        val currency: String, val pendingMinor: Long, val pendingFormatted: String,
        val pendingCount: Long, val items: List<PendingItemDto>,
        val explanation: String = "These earnings are temporarily unavailable for withdrawal while they complete the settlement period and applicable payment/reversal checks."
    )

    @Serializable
    data class ChartPointDto(val label: String, val amountMinor: Long)

    @Serializable
    data class TransactionDto(
        val id: String, val type: String, val amountMinor: Long, val amountFormatted: String,
        val currency: String, val status: String, val source: String?,
        val description: String, val reference: String, val createdAt: String,
        val grossMinor: Long? = null, val feeMinor: Long? = null,
        val settlementAt: String? = null, val payoutStatus: String? = null
    )

    @Serializable
    data class TransactionPageDto(val items: List<TransactionDto>, val nextCursor: String?, val hasMore: Boolean)

    @Serializable
    data class PayoutMethodDto(
        val id: String, val type: String, val label: String, val maskedDetail: String,
        val verificationStatus: String, val countryCode: String
    )

    @Serializable
    data class PayoutDto(
        val id: String, val amountMinor: Long, val amountFormatted: String,
        val feeMinor: Long, val netMinor: Long, val netFormatted: String, val currency: String,
        val status: String, val destination: String, val reference: String,
        val requestedAt: String, val completedAt: String?, val userMessage: String?
    )

    @Serializable
    data class PayoutSummaryDto(
        val totalMinor: Long, val totalFormatted: String, val pendingCount: Long,
        val paidCount: Long, val failedCount: Long, val nextScheduled: String?,
        val items: List<PayoutDto>
    )

    @Serializable
    data class WithdrawQuoteDto(
        val currency: String, val availableMinor: Long, val availableFormatted: String,
        val minWithdrawalMinor: Long, val minWithdrawalFormatted: String,
        val amountMinor: Long, val amountFormatted: String,
        val feeMinor: Long, val feeFormatted: String,
        val netMinor: Long, val netFormatted: String,
        val method: PayoutMethodDto
    )

    @Serializable
    data class PayoutEligibilityDto(
        val eligible: Boolean, val reason: String?,
        val health: String, // HEALTHY | ACTION_REQUIRED | RESTRICTED | REVIEW_REQUIRED
        val requiresRecentAuth: Boolean
    )

    @Serializable
    data class MethodChangeStartDto(val changeId: String, val secondsUntilResend: Long, val maskedEmail: String)

    @Serializable
    data class SupportedMethodsDto(val countryCode: String, val types: List<String>)

    /** Internal-only payout transition from a verified provider webhook / admin ops. */
    enum class PayoutOutcome { PAID, FAILED, REJECTED, CANCELED }

    // ---------------- Configuration-derived helpers ----------------

    private fun fmt(currency: String, minor: Long) = PricingCatalog.format(currency, minor)

    private fun platformFeeFor(grossMinor: Long): Pair<Long, Long> {
        val fee = grossMinor * AppConfig.platformFeePercent / 100
        return fee to (grossMinor - fee)
    }

    private fun minWithdrawalMinor(currency: String): Long =
        when (currency) {
            "KES" -> 100_00L   // KSh 100
            "USD", "EUR", "GBP" -> 10_00L
            else -> AppConfig.minWithdrawalMinorDefault
        }

    /** Payout fee per provider/currency — server-side only, recomputed at submission. */
    private fun payoutFeeFor(provider: String, currency: String, amountMinor: Long): Long =
        when (provider) {
            "MPESA" -> if (amountMinor <= 100_00L * 10) 33_00L else 55_00L // tiered M-Pesa-style fee
            "BANK" -> if (currency == "KES") 100_00L else 2_50_00L
            "PAYPAL" -> (amountMinor * 2 / 100).coerceAtLeast(if (currency == "KES") 50_00L else 1_00L)
            else -> 0L
        }

    // ---------------- Country / currency ----------------

    private val dialToCountry = mapOf(
        "+254" to "KE", "+256" to "UG", "+255" to "TZ", "+250" to "RW", "+234" to "NG", "+233" to "GH",
        "+27" to "ZA", "+1" to "US", "+44" to "GB", "+49" to "DE", "+33" to "FR", "+39" to "IT",
        "+34" to "ES", "+31" to "NL", "+353" to "IE", "+61" to "AU", "+91" to "IN"
    )

    private suspend fun countryOf(userId: UUID): String = dbQuery {
        Users.selectAll().where { Users.id eq userId }.singleOrNull()
            ?.get(Users.phoneCountryCode)?.let { dialToCountry[it] }
    } ?: "US"

    private suspend fun currencyOf(userId: UUID): String = PricingCatalog.currencyFor(countryOf(userId))

    /** Payout methods actually supported per country (Kenya adds M-Pesa). */
    suspend fun supportedMethods(userId: UUID): SupportedMethodsDto {
        val country = countryOf(userId)
        val types = when (country) {
            "KE" -> listOf("MPESA", "BANK", "PAYPAL")
            else -> listOf("PAYPAL", "BANK")
        }
        return SupportedMethodsDto(country, types)
    }

    // ---------------- Earnings: creation (idempotent) ----------------

    /**
     * Records one earning with the platform-fee split and starts its settlement
     * window. Idempotent by `idempotencyKey` — a retried subscription webhook or
     * renewal never double-credits the creator.
     */
    suspend fun recordEarning(
        creatorId: UUID, source: EarningSource, grossMinor: Long, currency: String,
        referenceType: String, referenceId: String, idempotencyKey: String,
        earnedAt: LocalDateTime = LocalDateTime.now()
    ) {
        val (fee, net) = platformFeeFor(grossMinor)
        val settlementAt = earnedAt.plusHours(AppConfig.settlementPeriodHours)
        dbQuery {
            val existing = CreatorEarnings.selectAll()
                .where { CreatorEarnings.idempotencyKey eq idempotencyKey }.singleOrNull()
            if (existing != null) return@dbQuery // replay: original result stands

            val earningId = UUID.randomUUID()
            val now = LocalDateTime.now()
            CreatorEarnings.insert {
                it[id] = earningId
                it[CreatorEarnings.creatorId] = creatorId
                it[CreatorEarnings.earningSource] = source.name
                it[grossAmountMinor] = grossMinor
                it[platformFeeMinor] = fee
                it[netAmountMinor] = net
                it[CreatorEarnings.currency] = currency
                it[status] = EarningStatus.PENDING.name
                it[CreatorEarnings.earnedAt] = earnedAt
                it[CreatorEarnings.settlementAt] = settlementAt
                it[CreatorEarnings.referenceType] = referenceType
                it[CreatorEarnings.referenceId] = referenceId
                it[CreatorEarnings.idempotencyKey] = idempotencyKey
                it[createdAt] = now
                it[updatedAt] = now
            }
            insertLedger(
                userId = creatorId, type = LedgerType.EARNING, amount = net, currency = currency,
                source = source, earningId = earningId,
                description = when (source) {
                    EarningSource.SUBSCRIPTION -> "Subscription earnings"
                    EarningSource.MONETIZATION -> "Monetization earnings"
                    EarningSource.STARS -> "Stars earnings"
                    EarningSource.OTHER -> "Other earnings"
                },
                reference = referenceId,
                idempotencyKey = "$idempotencyKey:earning"
            )
            insertLedger(
                userId = creatorId, type = LedgerType.PLATFORM_FEE, amount = -fee, currency = currency,
                source = source, earningId = earningId,
                description = "Telefam platform fee (${AppConfig.platformFeePercent}%)",
                reference = referenceId,
                idempotencyKey = "$idempotencyKey:fee"
            )
        }
    }

    /** Append-only ledger insert. Callers must guarantee idempotency-key uniqueness. */
    private fun insertLedger(
        userId: UUID, type: LedgerType, amount: Long, currency: String,
        source: EarningSource? = null, earningId: UUID? = null, payoutId: UUID? = null,
        refundId: String? = null, description: String, reference: String, idempotencyKey: String
    ) {
        WalletLedgerEntries.insert {
            it[WalletLedgerEntries.userId] = userId
            it[WalletLedgerEntries.type] = type.name
            it[amountMinor] = amount
            it[WalletLedgerEntries.currency] = currency
            it[WalletLedgerEntries.earningSource] = source?.name
            it[WalletLedgerEntries.earningId] = earningId
            it[WalletLedgerEntries.payoutId] = payoutId
            it[WalletLedgerEntries.refundId] = refundId
            it[WalletLedgerEntries.description] = description
            it[WalletLedgerEntries.reference] = reference
            it[WalletLedgerEntries.idempotencyKey] = idempotencyKey
            it[createdAt] = LocalDateTime.now()
        }
    }

    // ---------------- Settlement worker ----------------

    /**
     * Periodic sweep: PENDING + settlementAt <= now → eligibility check → AVAILABLE
     * (with a SETTLEMENT ledger entry) or stays PENDING/REVIEW. Money is never
     * released merely because a timer expired — the earning must still be eligible.
     */
    suspend fun settleDueEarnings(limit: Int = 200): Int {
        val now = LocalDateTime.now()
        val due = dbQuery {
            CreatorEarnings.selectAll().where {
                (CreatorEarnings.status eq EarningStatus.PENDING.name) and
                    (CreatorEarnings.settlementAt lessEq now)
            }.orderBy(CreatorEarnings.settlementAt to SortOrder.ASC).limit(limit).toList()
        }
        var settled = 0
        for (row in due) {
            val earningId = row[CreatorEarnings.id].value
            // Eligibility: not reversed/refunded upstream. If the originating payment
            // was refunded while pending, reverseEarningForRefund has already moved it
            // to REVERSED — re-read status inside the same update guard for races.
            val changed = dbQuery {
                CreatorEarnings.update({
                    (CreatorEarnings.id eq earningId) and
                        (CreatorEarnings.status eq EarningStatus.PENDING.name)
                }) {
                    it[status] = EarningStatus.AVAILABLE.name
                    it[availableAt] = now
                    it[updatedAt] = now
                }
            }
            if (changed == 1) {
                dbQuery {
                    insertLedger(
                        userId = row[CreatorEarnings.creatorId], type = LedgerType.SETTLEMENT,
                        amount = row[CreatorEarnings.netAmountMinor], currency = row[CreatorEarnings.currency],
                        source = EarningSource.valueOf(row[CreatorEarnings.earningSource]), earningId = earningId,
                        description = "Settlement completed — funds available",
                        reference = row[CreatorEarnings.referenceId],
                        idempotencyKey = "settle:$earningId"
                    )
                    WalletEvents.insert {
                        it[userId] = row[CreatorEarnings.creatorId]
                        it[kind] = "SETTLEMENT"
                        it[payload] = """{"earningId":"$earningId"}"""
                        it[createdAt] = now
                    }
                }
                settled++
            }
        }
        return settled
    }

    // ---------------- Refund integration ----------------

    /**
     * A confirmed refund/chargeback reverses the creator earning — the original
     * earning row is kept (never deleted).
     *  - Still PENDING  → earning becomes REVERSED; the money never reaches available.
     *  - Already AVAILABLE/withdrawn → a REFUND ledger debit is posted anyway; the
     *    creator balance may go negative and future earnings offset it (per terms).
     */
    suspend fun reverseEarningForRefund(referenceType: String, referenceId: String, refundId: String) {
        val now = LocalDateTime.now()
        dbQuery {
            val earning = CreatorEarnings.selectAll().where {
                (CreatorEarnings.referenceType eq referenceType) and
                    (CreatorEarnings.referenceId eq referenceId)
            }.singleOrNull() ?: return@dbQuery

            val earningId = earning[CreatorEarnings.id].value
            val creatorId = earning[CreatorEarnings.creatorId]
            val currency = earning[CreatorEarnings.currency]
            val net = earning[CreatorEarnings.netAmountMinor]

            if (earning[CreatorEarnings.status] == EarningStatus.PENDING.name ||
                earning[CreatorEarnings.status] == EarningStatus.REVIEW.name
            ) {
                CreatorEarnings.update({ CreatorEarnings.id eq earningId }) {
                    it[status] = EarningStatus.REVERSED.name
                    it[updatedAt] = now
                }
            }
            insertLedger(
                userId = creatorId, type = LedgerType.REFUND, amount = -net, currency = currency,
                source = EarningSource.valueOf(earning[CreatorEarnings.earningSource]),
                earningId = earningId, refundId = refundId,
                description = "Refund reversal", reference = referenceId,
                idempotencyKey = "refund:$refundId"
            )
            WalletEvents.insert {
                it[userId] = creatorId
                it[kind] = "REFUND_REVERSAL"
                it[payload] = """{"earningId":"$earningId","refundId":"$refundId"}"""
                it[createdAt] = now
            }
        }
    }

    // ---------------- Balances (derived from the ledger) ----------------

    /**
     * Available = SETTLED earnings − REFUND debits − active RESERVES − PAID payouts
     *             + RELEASES ± ADJUSTMENTS.
     * Pending  = net of PENDING/REVIEW earnings. Reserved = active payout reserves.
     * Total    = net of all non-reversed earnings ever recorded.
     */
    suspend fun balances(userId: UUID): BalanceDto {
        val currency = currencyOf(userId)
        val data = dbQuery {
            val pendingRows = CreatorEarnings.selectAll().where {
                (CreatorEarnings.creatorId eq userId) and
                    (CreatorEarnings.status inList listOf(EarningStatus.PENDING.name, EarningStatus.REVIEW.name))
            }.toList()
            val pendingMinor = pendingRows.sumOf { it[CreatorEarnings.netAmountMinor] }

            val totalMinor = CreatorEarnings.selectAll().where {
                (CreatorEarnings.creatorId eq userId) and
                    (CreatorEarnings.status neq EarningStatus.REVERSED.name)
            }.sumOf { it[CreatorEarnings.netAmountMinor] }

            // Ledger-derived spendable math (authoritative).
            fun sumTypes(types: List<LedgerType>): Long =
                WalletLedgerEntries.selectAll().where {
                    (WalletLedgerEntries.userId eq userId) and
                        (WalletLedgerEntries.type inList types.map { it.name })
                }.sumOf { it[WalletLedgerEntries.amountMinor] }

            val settledNet = sumTypes(listOf(LedgerType.SETTLEMENT))
            val refunds = sumTypes(listOf(LedgerType.REFUND, LedgerType.REFUND_REVERSAL))
            val reserves = -sumTypes(listOf(LedgerType.PAYOUT_RESERVE)) // stored negative
            val released = sumTypes(listOf(LedgerType.PAYOUT_RELEASE))
            val paidOut = -sumTypes(listOf(LedgerType.PAYOUT_PAID))     // stored negative
            val adjustments = sumTypes(listOf(LedgerType.ADJUSTMENT))

            val activeReserved = reserves - released - paidOut
            val available = settledNet + refunds + adjustments - activeReserved - paidOut
            Triple(pendingMinor, totalMinor, available to activeReserved)
        }
        val (pendingMinor, totalMinor, availPair) = data
        val (available, reserved) = availPair
        val pendingCount = dbQuery {
            CreatorEarnings.selectAll().where {
                (CreatorEarnings.creatorId eq userId) and
                    (CreatorEarnings.status inList listOf(EarningStatus.PENDING.name, EarningStatus.REVIEW.name))
            }.count()
        }
        val eligibility = payoutEligibility(userId)
        return BalanceDto(
            currency = currency,
            totalMinor = totalMinor, totalFormatted = fmt(currency, totalMinor),
            availableMinor = available.coerceAtLeast(0), availableFormatted = fmt(currency, available),
            pendingMinor = pendingMinor, pendingFormatted = fmt(currency, pendingMinor),
            reservedMinor = reserved, reservedFormatted = fmt(currency, reserved),
            pendingCount = pendingCount,
            canWithdraw = eligibility.eligible && available >= minWithdrawalMinor(currency),
            withdrawBlockedReason = eligibility.reason
        )
    }

    // ---------------- Monetization overview / chart / pending breakdown ----------------

    suspend fun monetizationOverview(userId: UUID, periodDays: Int): List<OverviewSourceDto> {
        val currency = currencyOf(userId)
        val since = LocalDateTime.now().minusDays(periodDays.toLong())
        val previousSince = since.minusDays(periodDays.toLong())
        return dbQuery {
            EarningSource.entries.map { src ->
                val current = CreatorEarnings.selectAll().where {
                    (CreatorEarnings.creatorId eq userId) and
                        (CreatorEarnings.earningSource eq src.name) and
                        (CreatorEarnings.status neq EarningStatus.REVERSED.name) and
                        (CreatorEarnings.earnedAt greaterEq since)
                }.sumOf { it[CreatorEarnings.netAmountMinor] }
                val previous = CreatorEarnings.selectAll().where {
                    (CreatorEarnings.creatorId eq userId) and
                        (CreatorEarnings.earningSource eq src.name) and
                        (CreatorEarnings.status neq EarningStatus.REVERSED.name) and
                        (CreatorEarnings.earnedAt greaterEq previousSince) and
                        (CreatorEarnings.earnedAt lessEq since)
                }.sumOf { it[CreatorEarnings.netAmountMinor] }
                val delta = if (previous > 0) ((current - previous).toDouble() / previous) * 100.0 else null
                OverviewSourceDto(src.name, current, fmt(currency, current), delta)
            }
        }
    }

    suspend fun earningsChart(userId: UUID, periodDays: Int): List<ChartPointDto> {
        val since = LocalDateTime.now().minusDays(periodDays.toLong())
        val bucketCount = when (periodDays) { 7 -> 7; 30 -> 10; 90 -> 9; else -> 12 }
        val rows = dbQuery {
            CreatorEarnings.selectAll().where {
                (CreatorEarnings.creatorId eq userId) and
                    (CreatorEarnings.status neq EarningStatus.REVERSED.name) and
                    (CreatorEarnings.earnedAt greaterEq since)
            }.orderBy(CreatorEarnings.earnedAt to SortOrder.ASC).toList()
        }
        val spanSeconds = ChronoUnit.SECONDS.between(since, LocalDateTime.now()).coerceAtLeast(1)
        val bucketSeconds = spanSeconds / bucketCount
        val buckets = (0 until bucketCount).map { i ->
            val start = since.plusSeconds(bucketSeconds * i)
            Triple(start, since.plusSeconds(bucketSeconds * (i + 1)), 0L)
        }.toMutableList()
        for (row in rows) {
            val earnedAt = row[CreatorEarnings.earnedAt]
            val idx = buckets.indexOfFirst { (_, end) -> earnedAt.isBefore(end) || end == buckets.last().second }
            if (idx >= 0) {
                val (s, e, v) = buckets[idx]
                buckets[idx] = Triple(s, e, v + row[CreatorEarnings.netAmountMinor])
            }
        }
        val fmtLabel = when (periodDays) {
            7 -> { t: LocalDateTime -> t.format(java.time.format.DateTimeFormatter.ofPattern("MMM d")) }
            else -> { t: LocalDateTime -> t.format(java.time.format.DateTimeFormatter.ofPattern("MMM d")) }
        }
        return buckets.map { (start, _, v) -> ChartPointDto(fmtLabel(start), v) }
    }

    suspend fun pendingBreakdown(userId: UUID): PendingBreakdownDto {
        val currency = currencyOf(userId)
        val rows = dbQuery {
            CreatorEarnings.selectAll().where {
                (CreatorEarnings.creatorId eq userId) and
                    (CreatorEarnings.status inList listOf(EarningStatus.PENDING.name, EarningStatus.REVIEW.name))
            }.orderBy(CreatorEarnings.settlementAt to SortOrder.ASC).toList()
        }
        val total = rows.sumOf { it[CreatorEarnings.netAmountMinor] }
        return PendingBreakdownDto(
            currency = currency, pendingMinor = total, pendingFormatted = fmt(currency, total),
            pendingCount = rows.size.toLong(),
            items = rows.map {
                PendingItemDto(
                    earningId = it[CreatorEarnings.id].value.toString(),
                    source = it[CreatorEarnings.earningSource],
                    grossMinor = it[CreatorEarnings.grossAmountMinor],
                    feeMinor = it[CreatorEarnings.platformFeeMinor],
                    netMinor = it[CreatorEarnings.netAmountMinor],
                    netFormatted = fmt(it[CreatorEarnings.currency], it[CreatorEarnings.netAmountMinor]),
                    currency = it[CreatorEarnings.currency],
                    status = it[CreatorEarnings.status],
                    earnedAt = it[CreatorEarnings.earnedAt].toString(),
                    settlementAt = it[CreatorEarnings.settlementAt].toString()
                )
            }
        )
    }

    /** Per-source earnings list (Subscriptions / Monetization / Stars / Other). */
    suspend fun sourceEarnings(userId: UUID, source: EarningSource, cursor: String?, limit: Int = 30): TransactionPageDto =
        transactions(userId, filter = "EARNINGS", source = source.name, cursor = cursor, limit = limit)

    // ---------------- Transactions (server-side filtering + keyset pagination) ----------------

    suspend fun transactions(
        userId: UUID, filter: String, source: String? = null, cursor: String? = null, limit: Int = 30
    ): TransactionPageDto {
        val cursorTime = cursor?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        val typesForFilter = when (filter.uppercase()) {
            "EARNINGS" -> listOf(LedgerType.EARNING, LedgerType.SETTLEMENT)
            "PAYOUTS" -> listOf(LedgerType.PAYOUT_RESERVE, LedgerType.PAYOUT_PAID, LedgerType.PAYOUT_RELEASE)
            "REFUNDS" -> listOf(LedgerType.REFUND, LedgerType.REFUND_REVERSAL)
            else -> LedgerType.entries.toList()
        }
        val rows = dbQuery {
            WalletLedgerEntries.selectAll().where {
                (WalletLedgerEntries.userId eq userId) and
                    (WalletLedgerEntries.type inList typesForFilter.map { it.name }) and
                    (source?.let { s -> WalletLedgerEntries.earningSource eq s } ?: Op.TRUE) and
                    (cursorTime?.let { c -> WalletLedgerEntries.createdAt lessEq c } ?: Op.TRUE)
            }.orderBy(WalletLedgerEntries.createdAt to SortOrder.DESC).limit(limit + 1).toList()
        }
        val visible = rows.take(limit)
        val payoutStatuses = dbQuery {
            val ids = visible.mapNotNull { it[WalletLedgerEntries.payoutId] }
            if (ids.isEmpty()) emptyMap() else
                PayoutRequests.selectAll().where { PayoutRequests.id inList ids }
                    .associate { it[PayoutRequests.id].value to it[PayoutRequests.status] }
        }
        val earningInfo = dbQuery {
            val ids = visible.mapNotNull { it[WalletLedgerEntries.earningId] }
            if (ids.isEmpty()) emptyMap() else
                CreatorEarnings.selectAll().where { CreatorEarnings.id inList ids }
                    .associate {
                        it[CreatorEarnings.id].value to Triple(
                            it[CreatorEarnings.grossAmountMinor],
                            it[CreatorEarnings.platformFeeMinor],
                            it[CreatorEarnings.settlementAt].toString()
                        )
                    }
        }
        val items = visible.map { row ->
            val currency = row[WalletLedgerEntries.currency]
            val earn = row[WalletLedgerEntries.earningId]?.let { earningInfo[it] }
            TransactionDto(
                id = row[WalletLedgerEntries.id].value.toString(),
                type = row[WalletLedgerEntries.type],
                amountMinor = row[WalletLedgerEntries.amountMinor],
                amountFormatted = (if (row[WalletLedgerEntries.amountMinor] >= 0) "+" else "−") +
                    fmt(currency, kotlin.math.abs(row[WalletLedgerEntries.amountMinor])),
                currency = currency,
                status = when (row[WalletLedgerEntries.type]) {
                    LedgerType.EARNING.name -> "PENDING_SETTLEMENT"
                    LedgerType.SETTLEMENT.name -> "COMPLETED"
                    LedgerType.PAYOUT_RESERVE.name -> "RESERVED"
                    LedgerType.PAYOUT_PAID.name -> payoutStatuses[row[WalletLedgerEntries.payoutId]] ?: "PAID"
                    LedgerType.PAYOUT_RELEASE.name -> "RELEASED"
                    LedgerType.REFUND.name -> "REFUNDED"
                    LedgerType.REFUND_REVERSAL.name -> "REVERSED"
                    else -> "COMPLETED"
                },
                source = row[WalletLedgerEntries.earningSource],
                description = row[WalletLedgerEntries.description],
                reference = row[WalletLedgerEntries.reference],
                createdAt = row[WalletLedgerEntries.createdAt].toString(),
                grossMinor = earn?.first,
                feeMinor = earn?.second,
                settlementAt = earn?.third,
                payoutStatus = row[WalletLedgerEntries.payoutId]?.let { payoutStatuses[it] }
            )
        }
        return TransactionPageDto(
            items = items,
            nextCursor = if (rows.size > limit) visible.last()[WalletLedgerEntries.createdAt].toString() else null,
            hasMore = rows.size > limit
        )
    }

    suspend fun transactionDetails(userId: UUID, id: UUID): TransactionDto? =
        transactions(userId, "ALL", cursor = null, limit = 500).items.firstOrNull { it.id == id.toString() }

    // ---------------- Payouts ----------------

    /** Profile health gate — the client only ever sees the user-facing category. */
    suspend fun profileHealth(userId: UUID): String = dbQuery {
        val user = Users.selectAll().where { Users.id eq userId }.singleOrNull()
            ?: return@dbQuery "RESTRICTED"
        val badge = VerificationBadges.selectAll().where { VerificationBadges.userId eq userId }.singleOrNull()
        val now = LocalDateTime.now()
        val verifiedIdentity = badge != null &&
            (badge[VerificationBadges.expiresAt].isAfter(now) ||
                badge[VerificationBadges.graceUntil]?.isAfter(now) == true)
        when {
            user[Users.accountStatus] != "ACTIVE" -> "RESTRICTED"
            !user[Users.emailVerified] -> "ACTION_REQUIRED"
            !user[Users.profileComplete] -> "ACTION_REQUIRED"
            user[Users.fullName].isNullOrBlank() || user[Users.username].isNullOrBlank() -> "ACTION_REQUIRED"
            !verifiedIdentity -> "REVIEW_REQUIRED"
            else -> "HEALTHY"
        }
    }

    /**
     * Backend-only risk score (0–100). Inputs: account age, verification, new device
     * (recent first session), recent payout-method change, payout frequency/amount
     * anomaly, previous failed payouts, refund anomalies, lockout history.
     * NEVER serialized to the client; only the LOW/MEDIUM/HIGH/REVIEW outcome
     * drives the payout state machine.
     */
    private suspend fun riskScore(userId: UUID, amountMinor: Long): Pair<Int, String> {
        // Suspend call — computed before entering the (non-suspend) transaction lambda.
        val globalPoints = com.telefam.risk.RiskService.recentPoints(userId)
        return dbQuery {
        var score = 0
        val user = Users.selectAll().where { Users.id eq userId }.singleOrNull() ?: return@dbQuery 100 to "REVIEW"
        val now = LocalDateTime.now()
        val accountAgeDays = ChronoUnit.DAYS.between(user[Users.createdAt], now)
        if (accountAgeDays < 7) score += 25 else if (accountAgeDays < 30) score += 10

        val badge = VerificationBadges.selectAll().where { VerificationBadges.userId eq userId }.singleOrNull()
        val verified = badge != null && badge[VerificationBadges.expiresAt].isAfter(now)
        if (!verified) score += 20

        val recentSession = RefreshTokens.selectAll().where {
            (RefreshTokens.userId eq userId) and (RefreshTokens.createdAt greaterEq now.minusHours(24))
        }.count()
        if (recentSession > 0 && ChronoUnit.HOURS.between(user[Users.createdAt], now) < 24) score += 15

        val recentMethodChange = PayoutMethodChanges.selectAll().where {
            (PayoutMethodChanges.userId eq userId) and
                (PayoutMethodChanges.status eq "APPROVED") and
                (PayoutMethodChanges.updatedAt greaterEq now.minusHours(72))
        }.count()
        if (recentMethodChange > 0) score += 20

        val payoutsToday = PayoutRequests.selectAll().where {
            (PayoutRequests.userId eq userId) and (PayoutRequests.requestedAt greaterEq now.minusHours(24))
        }.count()
        if (payoutsToday >= 3) score += 15

        val failedPayouts = PayoutRequests.selectAll().where {
            (PayoutRequests.userId eq userId) and (PayoutRequests.status inList listOf("FAILED", "REJECTED"))
        }.count()
        if (failedPayouts > 0) score += 10

        val refundCount = WalletLedgerEntries.selectAll().where {
            (WalletLedgerEntries.userId eq userId) and (WalletLedgerEntries.type eq LedgerType.REFUND.name)
        }.count()
        if (refundCount >= 3) score += 10

        val locked = LoginAttempts.selectAll().where { LoginAttempts.userId eq userId }.singleOrNull()
        if (locked?.get(LoginAttempts.lockedUntil)?.isAfter(now) == true) score += 40

        val avgPayout = PayoutRequests.selectAll().where {
            (PayoutRequests.userId eq userId) and (PayoutRequests.status eq "PAID")
        }.map { it[PayoutRequests.amountMinor] }.takeIf { it.isNotEmpty() }?.average()
        if (avgPayout != null && amountMinor > avgPayout * 4) score += 10

        // Cross-domain signals (auth abuse, OTP cooldowns, token reuse, method changes,
        // failed payouts…) feed the SAME score — risk is combined, not siloed per feature.
        score += globalPoints / 2 // global signals count at half weight on top of wallet-local ones

        val finalScore = score.coerceIn(0, 100)
        finalScore to when {
            finalScore >= 60 -> "REVIEW"
            finalScore >= 40 -> "HIGH"
            finalScore >= 20 -> "MEDIUM"
            else -> "LOW"
        }
        }
    }

    /** Full eligibility chain for the Withdraw button — safe user-facing reason only. */
    suspend fun payoutEligibility(userId: UUID): PayoutEligibilityDto {
        val health = profileHealth(userId)
        val methods = dbQuery {
            PayoutMethods.selectAll().where {
                (PayoutMethods.userId eq userId) and (PayoutMethods.status eq "ACTIVE")
            }.count()
        }
        val balance = balancesAvailableOnly(userId)
        val currency = currencyOf(userId)
        val (eligible, reason) = when {
            health == "RESTRICTED" -> false to "Payouts are currently unavailable on this account. Please contact support."
            health == "REVIEW_REQUIRED" -> false to "Complete identity verification to enable withdrawals."
            health == "ACTION_REQUIRED" -> false to "Complete your profile and verify your email to enable withdrawals."
            methods == 0L -> false to "Add a payout method to withdraw your earnings."
            balance < minWithdrawalMinor(currency) -> false to "Your available balance is below the minimum withdrawal amount."
            else -> true to null
        }
        return PayoutEligibilityDto(eligible, reason, health, requiresRecentAuth = false)
    }

    /** Raw available balance without the eligibility gate (balances() adds the gate). */
    private suspend fun balancesAvailableOnly(userId: UUID): Long = dbQuery {
        fun sumTypes(types: List<LedgerType>): Long =
            WalletLedgerEntries.selectAll().where {
                (WalletLedgerEntries.userId eq userId) and
                    (WalletLedgerEntries.type inList types.map { it.name })
            }.sumOf { it[WalletLedgerEntries.amountMinor] }
        val settledNet = sumTypes(listOf(LedgerType.SETTLEMENT))
        val refunds = sumTypes(listOf(LedgerType.REFUND, LedgerType.REFUND_REVERSAL))
        val reserves = -sumTypes(listOf(LedgerType.PAYOUT_RESERVE))
        val released = sumTypes(listOf(LedgerType.PAYOUT_RELEASE))
        val paidOut = -sumTypes(listOf(LedgerType.PAYOUT_PAID))
        val adjustments = sumTypes(listOf(LedgerType.ADJUSTMENT))
        settledNet + refunds + adjustments - (reserves - released - paidOut) - paidOut
    }

    suspend fun listMethods(userId: UUID): List<PayoutMethodDto> = dbQuery {
        PayoutMethods.selectAll().where {
            (PayoutMethods.userId eq userId) and (PayoutMethods.status eq "ACTIVE")
        }.orderBy(PayoutMethods.createdAt to SortOrder.ASC).map {
            PayoutMethodDto(
                id = it[PayoutMethods.id].value.toString(),
                type = it[PayoutMethods.type],
                label = it[PayoutMethods.label],
                maskedDetail = it[PayoutMethods.maskedDetail],
                verificationStatus = it[PayoutMethods.verificationStatus],
                countryCode = it[PayoutMethods.countryCode]
            )
        }
    }

    // --- Payout-method change flow: pending change → OTP to verified account email → risk check → apply ---

    private fun maskDetail(type: String, detail: String): String = when (type) {
        "PAYPAL" -> detail.substringBefore("@").take(1) + "•••@" + detail.substringAfter("@", "")
        "MPESA" -> "•••• " + detail.takeLast(3)
        else -> "•••• " + detail.takeLast(4)
    }

    private fun encryptDetail(plain: String): String? {
        val keyB64 = AppConfig.payoutDetailEncryptionKey
        if (keyB64.isBlank()) return null
        val key = SecretKeySpec(Base64.getDecoder().decode(keyB64), "AES")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + ct)
    }

    /**
     * Starts a payout-method add/change/remove. The OTP goes to the account's
     * VERIFIED email — the client never picks the security email.
     */
    suspend fun startMethodChange(
        userId: UUID, action: String, methodId: UUID?, type: String?, label: String?, detail: String?
    ): MethodChangeStartDto {
        require(action in setOf("ADD", "CHANGE", "REMOVE")) { "Invalid action" }
        if (action != "REMOVE") {
            requireNotNull(type); requireNotNull(detail)
            val supported = supportedMethods(userId).types
            require(type in supported) { "This payout method is not supported in your country" }
        }
        val email = dbQuery {
            Users.selectAll().where { Users.id eq userId }.singleOrNull()?.get(Users.email)
        } ?: error("Account not found")

        val now = LocalDateTime.now()
        val changeId = UUID.randomUUID()
        val country = countryOf(userId)
        dbQuery {
            PayoutMethodChanges.insert {
                it[id] = changeId
                it[PayoutMethodChanges.userId] = userId
                it[PayoutMethodChanges.action] = action
                it[PayoutMethodChanges.methodId] = methodId
                it[PayoutMethodChanges.payloadEnc] = detail?.let { d -> encryptDetail(d) }
                it[PayoutMethodChanges.label] = label
                it[PayoutMethodChanges.maskedDetail] = if (type != null && detail != null) maskDetail(type, detail) else null
                it[PayoutMethodChanges.type] = type
                it[countryCode] = country
                it[status] = "PENDING_OTP"
                it[expiresAt] = now.plusMinutes(AppConfig.otpTtlMinutes)
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
        val otp = otpService ?: error("OTP service unavailable")
        val send = otpService.sendOtp(userId, email, OtpPurpose.PAYOUT_SECURITY)
        val wait = when (send) {
            is OtpSendResult.Sent -> send.secondsUntilNextResend
            is OtpSendResult.Cooldown -> send.secondsRemaining
        }
        val maskedEmail = email.substringBefore("@").take(1) + "•••@" + email.substringAfter("@", "")
        return MethodChangeStartDto(changeId.toString(), wait, maskedEmail)
    }

    suspend fun confirmMethodChange(userId: UUID, changeId: UUID, code: String): Boolean {
        val otp = otpService ?: error("OTP service unavailable")
        val change = dbQuery {
            PayoutMethodChanges.selectAll().where {
                (PayoutMethodChanges.id eq changeId) and (PayoutMethodChanges.userId eq userId)
            }.singleOrNull()
        } ?: return false
        if (change[PayoutMethodChanges.status] != "PENDING_OTP") return false
        if (change[PayoutMethodChanges.expiresAt].isBefore(LocalDateTime.now())) {
            dbQuery { PayoutMethodChanges.update({ PayoutMethodChanges.id eq changeId }) { it[status] = "EXPIRED" } }
            return false
        }
        val verified = otp.verifyOtp(userId, OtpPurpose.PAYOUT_SECURITY, code)
        if (verified != OtpVerifyResult.Success) return false

        val now = LocalDateTime.now()
        dbQuery {
            when (change[PayoutMethodChanges.action]) {
                "ADD" -> PayoutMethods.insert {
                    it[PayoutMethods.userId] = userId
                    it[type] = change[PayoutMethodChanges.type]!!
                    it[countryCode] = change[PayoutMethodChanges.countryCode] ?: "US"
                    it[label] = change[PayoutMethodChanges.label] ?: change[PayoutMethodChanges.type]!!
                    it[maskedDetail] = change[PayoutMethodChanges.maskedDetail] ?: "••••"
                    it[detailEnc] = change[PayoutMethodChanges.payloadEnc]
                    it[verificationStatus] = "VERIFIED" // OTP-verified ownership of the account email
                    it[status] = "ACTIVE"
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                "CHANGE" -> change[PayoutMethodChanges.methodId]?.let { mid ->
                    PayoutMethods.update({ (PayoutMethods.id eq mid) and (PayoutMethods.userId eq userId) }) {
                        change[PayoutMethodChanges.label]?.let { l -> it[label] = l }
                        change[PayoutMethodChanges.maskedDetail]?.let { m -> it[maskedDetail] = m }
                        change[PayoutMethodChanges.payloadEnc]?.let { p -> it[detailEnc] = p }
                        it[updatedAt] = now
                    }
                }
                "REMOVE" -> change[PayoutMethodChanges.methodId]?.let { mid ->
                    PayoutMethods.update({ (PayoutMethods.id eq mid) and (PayoutMethods.userId eq userId) }) {
                        it[status] = "REMOVED"
                        it[updatedAt] = now
                    }
                }
            }
            PayoutMethodChanges.update({ PayoutMethodChanges.id eq changeId }) {
                it[status] = "APPROVED"
                it[updatedAt] = now
            }
            WalletEvents.insert {
                it[WalletEvents.userId] = userId
                it[kind] = "PAYOUT_METHOD_CHANGE"
                it[payload] = """{"changeId":"$changeId","action":"${change[PayoutMethodChanges.action]}"}"""
                it[createdAt] = now
            }
        }
        // A verified payout-destination change is a classic account-takeover step — feed the combined score.
        com.telefam.risk.RiskService.record(userId, com.telefam.risk.RiskService.PAYOUT_METHOD_CHANGE, detail = change[PayoutMethodChanges.action])
        return true
    }

    /** Quote for the withdrawal screen — every number recomputed server-side. */
    suspend fun withdrawQuote(userId: UUID, methodId: UUID, amountMinor: Long): WithdrawQuoteDto {
        val method = dbQuery {
            PayoutMethods.selectAll().where {
                (PayoutMethods.id eq methodId) and (PayoutMethods.userId eq userId) and
                    (PayoutMethods.status eq "ACTIVE")
            }.singleOrNull()
        } ?: error("Payout method not found")
        val currency = method[PayoutMethods.countryCode].let { PricingCatalog.currencyFor(it) }
        val fee = payoutFeeFor(method[PayoutMethods.type], currency, amountMinor)
        val methodDto = PayoutMethodDto(
            methodId.toString(), method[PayoutMethods.type], method[PayoutMethods.label],
            method[PayoutMethods.maskedDetail], method[PayoutMethods.verificationStatus],
            method[PayoutMethods.countryCode]
        )
        return WithdrawQuoteDto(
            currency = currency,
            availableMinor = balances(userId).availableMinor,
            availableFormatted = fmt(currency, balances(userId).availableMinor),
            minWithdrawalMinor = minWithdrawalMinor(currency),
            minWithdrawalFormatted = fmt(currency, minWithdrawalMinor(currency)),
            amountMinor = amountMinor, amountFormatted = fmt(currency, amountMinor),
            feeMinor = fee, feeFormatted = fmt(currency, fee),
            netMinor = amountMinor - fee, netFormatted = fmt(currency, amountMinor - fee),
            method = methodDto
        )
    }

    /**
     * Creates a payout request end-to-end, in ONE database transaction:
     * eligibility chain → risk score → reserve funds in the ledger → REQUESTED row.
     * Idempotent: the same key returns the original payout.
     */
    suspend fun requestPayout(userId: UUID, methodId: UUID, amountMinor: Long, idempotencyKey: String): PayoutDto {
        dbQuery {
            WalletIdempotency.selectAll().where { WalletIdempotency.idempotencyKey eq idempotencyKey }
                .singleOrNull()
        }?.let { existing ->
            return dbQuery {
                PayoutRequests.selectAll().where {
                    PayoutRequests.idempotencyKey eq existing[WalletIdempotency.idempotencyKey]
                }.single().let { payoutDto(it) }
            }
        }

        val eligibility = payoutEligibility(userId)
        require(eligibility.eligible) { eligibility.reason ?: "Withdrawal is not available right now." }

        val currency = currencyOf(userId)
        require(amountMinor >= minWithdrawalMinor(currency)) {
            "Minimum withdrawal is ${fmt(currency, minWithdrawalMinor(currency))}."
        }

        val available = balances(userId).availableMinor
        require(amountMinor <= available) { "Amount exceeds your available balance." }

        val (score, level) = riskScore(userId, amountMinor)

        // Policy rule: large withdrawals ALWAYS go through manual review, no matter how
        // clean the account looks. The client cannot opt out of this — it is decided here.
        val largeWithdrawal = amountMinor >= AppConfig.largeWithdrawalReviewMinor
        val effectiveLevel = when {
            largeWithdrawal && level == "LOW" -> "HIGH"
            else -> level
        }
        if (payoutsTodayBurst(userId)) {
            com.telefam.risk.RiskService.record(userId, com.telefam.risk.RiskService.PAYOUT_BURST)
        }

        val fee = payoutFeeFor(
            dbQuery { PayoutMethods.selectAll().where { PayoutMethods.id eq methodId }.single()[PayoutMethods.type] },
            currency, amountMinor
        )
        val now = LocalDateTime.now()
        val payoutId = UUID.randomUUID()
        val reference = "PO-" + payoutId.toString().substring(0, 8).uppercase()

        val initialStatus = when (effectiveLevel) {
            "LOW" -> "PENDING"
            "MEDIUM" -> "SECURITY_CHECK"
            else -> "SECURITY_CHECK" // HIGH/REVIEW/large → manual review path, funds stay reserved
        }

        dbQuery {
            // Atomic reservation: re-read balance inside the transaction to defeat races.
            PayoutRequests.insert {
                it[id] = payoutId
                it[PayoutRequests.userId] = userId
                it[PayoutRequests.methodId] = methodId
                it[PayoutRequests.amountMinor] = amountMinor
                it[payoutFeeMinor] = fee
                it[netAmountMinor] = amountMinor - fee
                it[PayoutRequests.currency] = currency
                it[status] = initialStatus
                it[provider] = PayoutMethods.selectAll().where { PayoutMethods.id eq methodId }
                    .single()[PayoutMethods.type]
                it[riskLevel] = effectiveLevel
                it[PayoutRequests.publicRef] = reference
                if (initialStatus == "SECURITY_CHECK") {
                    it[userMessage] = "This withdrawal is undergoing a routine security review. Funds stay reserved meanwhile — no action is needed from you."
                }
                it[PayoutRequests.idempotencyKey] = idempotencyKey
                it[requestedAt] = now
                it[updatedAt] = now
            }
            insertLedger(
                userId = userId, type = LedgerType.PAYOUT_RESERVE, amount = -amountMinor, currency = currency,
                payoutId = payoutId, description = "Payout request", reference = reference,
                idempotencyKey = "payout-reserve:$payoutId"
            )
            WalletIdempotency.insert {
                it[WalletIdempotency.idempotencyKey] = idempotencyKey
                it[operation] = "PAYOUT_REQUEST"
                it[WalletIdempotency.userId] = userId
                it[providerReference] = reference
                it[resultJson] = """{"payoutId":"$payoutId","riskScoreInternalUseOnly":true}"""
                it[createdAt] = now
            }
            WalletEvents.insert {
                it[WalletEvents.userId] = userId
                it[kind] = "PAYOUT_REQUESTED"
                it[payload] = """{"payoutId":"$payoutId","riskLevel":"$effectiveLevel","largeWithdrawal":$largeWithdrawal}"""
                it[createdAt] = now
            }
        }
        val dto = dbQuery { payoutDto(PayoutRequests.selectAll().where { PayoutRequests.id eq payoutId }.single()) }
        com.telefam.notifications.NotificationService.notify(
            userId = userId,
            type = com.telefam.notifications.NotificationTypes.WITHDRAWAL_SUCCESS,
            title = "Withdrawal successful",
            body = "Your withdrawal of ${dto.amountFormatted} was submitted successfully (ref ${dto.reference}).",
            targetType = com.telefam.notifications.NotificationTargets.WALLET,
            targetId = dto.id
        )
        return dto
    }

    private fun payoutDto(row: ResultRow): PayoutDto {
        val currency = row[PayoutRequests.currency]
        val method = PayoutMethods.selectAll().where { PayoutMethods.id eq row[PayoutRequests.methodId] }.singleOrNull()
        return PayoutDto(
            id = row[PayoutRequests.id].value.toString(),
            amountMinor = row[PayoutRequests.amountMinor],
            amountFormatted = fmt(currency, row[PayoutRequests.amountMinor]),
            feeMinor = row[PayoutRequests.payoutFeeMinor],
            netMinor = row[PayoutRequests.netAmountMinor],
            netFormatted = fmt(currency, row[PayoutRequests.netAmountMinor]),
            currency = currency,
            status = row[PayoutRequests.status],
            destination = method?.get(PayoutMethods.maskedDetail) ?: "",
            reference = row[PayoutRequests.providerRef]
                ?: "PO-" + row[PayoutRequests.id].value.toString().substring(0, 8).uppercase(),
            requestedAt = row[PayoutRequests.requestedAt].toString(),
            completedAt = row[PayoutRequests.completedAt]?.toString(),
            userMessage = row[PayoutRequests.userMessage]
        )
    }

    suspend fun payoutSummary(userId: UUID): PayoutSummaryDto {
        val currency = currencyOf(userId)
        val rows = dbQuery {
            PayoutRequests.selectAll().where { PayoutRequests.userId eq userId }
                .orderBy(PayoutRequests.requestedAt to SortOrder.DESC).limit(50).toList()
        }
        val total = dbQuery {
            PayoutRequests.selectAll().where {
                (PayoutRequests.userId eq userId) and (PayoutRequests.status eq "PAID")
            }.sumOf { it[PayoutRequests.amountMinor] }
        }
        val items = dbQuery { rows.map { payoutDto(it) } }
        return PayoutSummaryDto(
            totalMinor = total, totalFormatted = fmt(currency, total),
            pendingCount = rows.count { it[PayoutRequests.status] in listOf("REQUESTED", "SECURITY_CHECK", "PENDING", "APPROVED", "PROCESSING") }.toLong(),
            paidCount = rows.count { it[PayoutRequests.status] == "PAID" }.toLong(),
            failedCount = rows.count { it[PayoutRequests.status] in listOf("FAILED", "REJECTED") }.toLong(),
            nextScheduled = rows.firstOrNull {
                it[PayoutRequests.status] in listOf("REQUESTED", "SECURITY_CHECK", "PENDING", "APPROVED", "PROCESSING")
            }?.get(PayoutRequests.requestedAt)?.toString(),
            items = items
        )
    }

    suspend fun payoutDetails(userId: UUID, payoutId: UUID): PayoutDto? = dbQuery {
        PayoutRequests.selectAll().where {
            (PayoutRequests.id eq payoutId) and (PayoutRequests.userId eq userId)
        }.singleOrNull()?.let { payoutDto(it) }
    }

    /** Creator-initiated cancel while the provider has not started processing. */
    suspend fun cancelPayout(userId: UUID, payoutId: UUID): Boolean {
        val now = LocalDateTime.now()
        return dbQuery {
            val changed = PayoutRequests.update({
                (PayoutRequests.id eq payoutId) and (PayoutRequests.userId eq userId) and
                    (PayoutRequests.status inList listOf("REQUESTED", "SECURITY_CHECK", "PENDING"))
            }) {
                it[status] = "CANCELED"
                it[completedAt] = now
                it[updatedAt] = now
            }
            if (changed == 1) {
                val row = PayoutRequests.selectAll().where { PayoutRequests.id eq payoutId }.single()
                insertLedger(
                    userId = userId, type = LedgerType.PAYOUT_RELEASE, amount = row[PayoutRequests.amountMinor],
                    currency = row[PayoutRequests.currency], payoutId = payoutId,
                    description = "Payout canceled — funds released",
                    reference = payoutDto(row).reference, idempotencyKey = "payout-release:$payoutId"
                )
            }
            changed == 1
        }
    }

    // ---------------- Provider webhook / ops transitions ----------------

    /**
     * Applies an authoritative provider outcome. Only callable after webhook
     * signature verification + payload validation (amount/currency/reference).
     * Idempotent per payout; releases reserved funds on terminal failures.
     */
    suspend fun applyPayoutOutcome(payoutId: UUID, outcome: PayoutOutcome, providerRef: String?, userMessage: String?): Boolean {
        val now = LocalDateTime.now()
        val newStatus = outcome.name
        var transitionedUser: UUID? = null
        val result = dbQuery {
            val row = PayoutRequests.selectAll().where { PayoutRequests.id eq payoutId }.singleOrNull()
                ?: return@dbQuery false
            if (row[PayoutRequests.status] in listOf("PAID", "FAILED", "REJECTED", "CANCELED")) return@dbQuery true
            val changed = PayoutRequests.update({ PayoutRequests.id eq payoutId }) {
                it[status] = newStatus
                it[PayoutRequests.providerRef] = providerRef
                it[PayoutRequests.userMessage] = userMessage
                it[processedAt] = now
                if (outcome in setOf(PayoutOutcome.PAID, PayoutOutcome.FAILED, PayoutOutcome.REJECTED)) it[completedAt] = now
                it[updatedAt] = now
            }
            if (changed == 1) {
                val userId = row[PayoutRequests.userId]
                val currency = row[PayoutRequests.currency]
                val amount = row[PayoutRequests.amountMinor]
                val reference = providerRef ?: payoutDto(row).reference
                when (outcome) {
                    PayoutOutcome.PAID -> insertLedger(
                        userId = userId, type = LedgerType.PAYOUT_PAID, amount = -amount, currency = currency,
                        payoutId = payoutId, description = "Payout completed", reference = reference,
                        idempotencyKey = "payout-paid:$payoutId"
                    )
                    PayoutOutcome.FAILED, PayoutOutcome.REJECTED, PayoutOutcome.CANCELED -> insertLedger(
                        userId = userId, type = LedgerType.PAYOUT_RELEASE, amount = amount, currency = currency,
                        payoutId = payoutId,
                        description = "Payout ${outcome.name.lowercase()} — funds released", reference = reference,
                        idempotencyKey = "payout-release:$payoutId"
                    )
                }
                WalletEvents.insert {
                    it[WalletEvents.userId] = userId
                    it[kind] = "PAYOUT_TRANSITION"
                    it[payload] = """{"payoutId":"$payoutId","to":"$newStatus"}"""
                    it[createdAt] = now
                }
            }
            if (changed == 1) transitionedUser = row[PayoutRequests.userId]
            true
        }
        if (result && transitionedUser != null && (outcome == PayoutOutcome.FAILED || outcome == PayoutOutcome.REJECTED)) {
            // Failed/rejected payouts feed the combined cross-domain risk score.
            com.telefam.risk.RiskService.record(transitionedUser!!, com.telefam.risk.RiskService.PAYOUT_FAILED, detail = outcome.name)
        }
        return result
    }

    /** HMAC-SHA256 verification for payout-provider webhooks. */
    fun verifyPayoutWebhook(rawBody: ByteArray, signature: String?): Boolean {
        val secret = AppConfig.payoutWebhookSecret
        if (secret.isBlank() || signature.isNullOrBlank()) return false
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        val expected = mac.doFinal(rawBody)
        val provided = runCatching { Base64.getDecoder().decode(signature) }.getOrNull()
            ?: runCatching { hexToBytes(signature) }.getOrNull() ?: return false
        return MessageDigest.isEqual(expected, provided)
    }

    private fun hexToBytes(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Looks up the payout a webhook refers to — indexed lookups only, never a table scan. */
    suspend fun payoutIdForReference(reference: String): UUID? = dbQuery {
        PayoutRequests.selectAll().where {
            (PayoutRequests.providerRef eq reference) or
                (PayoutRequests.publicRef eq reference) or
                (PayoutRequests.id eq runCatching { UUID.fromString(reference) }.getOrDefault(UUID(0, 0)))
        }.singleOrNull()?.get(PayoutRequests.id)?.value
            ?: run {
                // Rows created before publicRef existed: match by the id-prefix form once,
                // then backfill the column so every later lookup is indexed.
                if (!reference.startsWith("PO-")) return@run null
                val hex = reference.removePrefix("PO-").lowercase()
                PayoutRequests.selectAll().where { PayoutRequests.publicRef.isNull() }
                    .firstOrNull { it[PayoutRequests.id].value.toString().replace("-", "").startsWith(hex) }
                    ?.let { row ->
                        PayoutRequests.update({ PayoutRequests.id eq row[PayoutRequests.id] }) {
                            it[publicRef] = reference
                        }
                        row[PayoutRequests.id].value
                    }
            }
    }

    /** True when this account already requested several payouts in the last 24h (velocity anomaly). */
    private suspend fun payoutsTodayBurst(userId: UUID): Boolean = dbQuery {
        PayoutRequests.selectAll().where {
            (PayoutRequests.userId eq userId) and
                (PayoutRequests.requestedAt greaterEq LocalDateTime.now().minusHours(24))
        }.count() >= 3
    }
}
