package com.telefam.data.api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*

// ---------- Wire DTOs mirroring backend WalletService responses exactly ----------

@kotlinx.serialization.Serializable
data class WalletBalanceDto(
    val currency: String,
    val totalMinor: Long, val totalFormatted: String,
    val availableMinor: Long, val availableFormatted: String,
    val pendingMinor: Long, val pendingFormatted: String,
    val reservedMinor: Long, val reservedFormatted: String,
    val pendingCount: Long,
    val canWithdraw: Boolean,
    val withdrawBlockedReason: String? = null
)

@kotlinx.serialization.Serializable
data class WalletOverviewSourceDto(
    val source: String, val amountMinor: Long, val amountFormatted: String,
    val deltaPercent: Double? = null
)

@kotlinx.serialization.Serializable
data class WalletPendingItemDto(
    val earningId: String, val source: String, val grossMinor: Long, val feeMinor: Long,
    val netMinor: Long, val netFormatted: String, val currency: String,
    val status: String, val earnedAt: String, val settlementAt: String
)

@kotlinx.serialization.Serializable
data class WalletPendingBreakdownDto(
    val currency: String, val pendingMinor: Long, val pendingFormatted: String,
    val pendingCount: Long, val items: List<WalletPendingItemDto>,
    val explanation: String
)

@kotlinx.serialization.Serializable
data class WalletChartPointDto(val label: String, val amountMinor: Long)

@kotlinx.serialization.Serializable
data class WalletTransactionDto(
    val id: String, val type: String, val amountMinor: Long, val amountFormatted: String,
    val currency: String, val status: String, val source: String? = null,
    val description: String, val reference: String, val createdAt: String,
    val grossMinor: Long? = null, val feeMinor: Long? = null,
    val settlementAt: String? = null, val payoutStatus: String? = null
)

@kotlinx.serialization.Serializable
data class WalletTransactionPageDto(
    val items: List<WalletTransactionDto>, val nextCursor: String? = null, val hasMore: Boolean
)

@kotlinx.serialization.Serializable
data class PayoutMethodDto(
    val id: String, val type: String, val label: String, val maskedDetail: String,
    val verificationStatus: String, val countryCode: String
)

@kotlinx.serialization.Serializable
data class PayoutDto(
    val id: String, val amountMinor: Long, val amountFormatted: String,
    val feeMinor: Long, val netMinor: Long, val netFormatted: String, val currency: String,
    val status: String, val destination: String, val reference: String,
    val requestedAt: String, val completedAt: String? = null, val userMessage: String? = null
)

@kotlinx.serialization.Serializable
data class PayoutSummaryDto(
    val totalMinor: Long, val totalFormatted: String, val pendingCount: Long,
    val paidCount: Long, val failedCount: Long, val nextScheduled: String? = null,
    val items: List<PayoutDto>
)

@kotlinx.serialization.Serializable
data class WithdrawQuoteDto(
    val currency: String, val availableMinor: Long, val availableFormatted: String,
    val minWithdrawalMinor: Long, val minWithdrawalFormatted: String,
    val amountMinor: Long, val amountFormatted: String,
    val feeMinor: Long, val feeFormatted: String,
    val netMinor: Long, val netFormatted: String,
    val method: PayoutMethodDto
)

@kotlinx.serialization.Serializable
data class PayoutEligibilityDto(
    val eligible: Boolean, val reason: String? = null,
    val health: String, val requiresRecentAuth: Boolean
)

@kotlinx.serialization.Serializable
data class MethodChangeStartDto(val changeId: String, val secondsUntilResend: Long, val maskedEmail: String)

@kotlinx.serialization.Serializable
data class SupportedMethodsDto(val countryCode: String, val types: List<String>)

/**
 * Wallet API client. No balance, fee, settlement, risk or payout math ever happens
 * here — every number comes from the backend ledger endpoints.
 */
class WalletApi(private val client: HttpClient) {
    private val base get() = ApiConfig.baseUrl.trimEnd('/')

    suspend fun balance(): WalletBalanceDto = client.get("$base/api/wallet/balance").body()

    suspend fun overview(periodDays: Int = 30): List<WalletOverviewSourceDto> =
        client.get("$base/api/wallet/overview") { parameter("periodDays", periodDays) }.body()

    suspend fun chart(periodDays: Int): List<WalletChartPointDto> =
        client.get("$base/api/wallet/chart") { parameter("periodDays", periodDays) }.body()

    suspend fun pendingBreakdown(): WalletPendingBreakdownDto =
        client.get("$base/api/wallet/pending").body()

    suspend fun sourceEarnings(source: String, cursor: String? = null): WalletTransactionPageDto =
        client.get("$base/api/wallet/earnings/$source") { if (cursor != null) parameter("cursor", cursor) }.body()

    suspend fun transactions(filter: String = "ALL", cursor: String? = null): WalletTransactionPageDto =
        client.get("$base/api/wallet/transactions") {
            parameter("filter", filter)
            if (cursor != null) parameter("cursor", cursor)
        }.body()

    suspend fun transactionDetails(id: String): WalletTransactionDto =
        client.get("$base/api/wallet/transactions/$id").body()

    suspend fun payouts(): PayoutSummaryDto = client.get("$base/api/wallet/payouts").body()

    suspend fun payoutDetails(id: String): PayoutDto = client.get("$base/api/wallet/payouts/$id").body()

    suspend fun cancelPayout(id: String) {
        client.post("$base/api/wallet/payouts/$id/cancel")
    }

    suspend fun payoutEligibility(): PayoutEligibilityDto =
        client.get("$base/api/wallet/payout-eligibility").body()

    suspend fun withdrawQuote(methodId: String, amountMinor: Long): WithdrawQuoteDto =
        client.post("$base/api/wallet/withdraw/quote") {
            contentType(ContentType.Application.Json)
            setBody("""{"methodId":"$methodId","amountMinor":$amountMinor}""")
        }.body()

    suspend fun requestPayout(methodId: String, amountMinor: Long, idempotencyKey: String): PayoutDto =
        client.post("$base/api/wallet/payouts") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"methodId":"$methodId","amountMinor":$amountMinor,"idempotencyKey":"$idempotencyKey"}"""
            )
        }.body()

    suspend fun methods(): List<PayoutMethodDto> = client.get("$base/api/wallet/methods").body()

    suspend fun supportedMethods(): SupportedMethodsDto =
        client.get("$base/api/wallet/methods/supported").body()

    suspend fun startMethodChange(
        action: String, methodId: String? = null, type: String? = null,
        label: String? = null, detail: String? = null
    ): MethodChangeStartDto = client.post("$base/api/wallet/methods/changes") {
        contentType(ContentType.Application.Json)
        setBody(
            buildString {
                append("""{"action":"$action"""")
                if (methodId != null) append(""","methodId":"$methodId"""")
                if (type != null) append(""","type":"$type"""")
                if (label != null) append(""","label":"$label"""")
                if (detail != null) append(""","detail":"${detail.replace("\"", "")}"""")
                append("}")
            }
        )
    }.body()

    suspend fun confirmMethodChange(changeId: String, code: String): Boolean {
        val res = client.post("$base/api/wallet/methods/changes/confirm") {
            contentType(ContentType.Application.Json)
            setBody("""{"changeId":"$changeId","code":"$code"}""")
        }
        return res.status.value in 200..299
    }
}
