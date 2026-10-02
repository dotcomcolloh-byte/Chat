package com.telefam.data.api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

// ---------- Wire DTOs mirroring backend SubscriptionService exactly ----------

@Serializable
data class SubscriptionPlanDto(
    val id: String,
    val name: String,
    val description: String,
    /** DAILY | WEEKLY | MONTHLY */
    val interval: String,
    val priceMinor: Long,
    val formattedPrice: String,
    val currency: String,
    val isActive: Boolean,
    val isMostPopular: Boolean,
    val subscriberCount: Long = 0
)

@Serializable
data class SubMetricDto(val value: Long, val deltaPercent: Double? = null)

@Serializable
data class SubOverviewDto(
    val currency: String,
    val totalEarningsMinorAllTime: Long,
    val totalEarningsFormatted: String,
    val periodDays: Int,
    val earnings: SubMetricDto,
    val earningsFormatted: String,
    val subscribers: SubMetricDto,
    val newSubscribers: SubMetricDto,
    val canceled: SubMetricDto
)

@Serializable
data class SubInsightPointDto(val date: String, val earningsMinor: Long, val newSubscribers: Long, val canceled: Long)

@Serializable
data class SubInsightsDto(
    val periodDays: Int,
    val currency: String,
    val totalEarningsMinor: Long,
    val totalEarningsFormatted: String,
    val newSubscribers: Long,
    val canceled: Long,
    val activeSubscribers: Long,
    val series: List<SubInsightPointDto> = emptyList()
)

@Serializable
data class SubscriberDto(
    val subscriptionId: String,
    val userId: String,
    val name: String? = null,
    val username: String? = null,
    val avatarUrl: String? = null,
    val isVerified: Boolean = false,
    val planName: String,
    val interval: String,
    val status: String,
    val since: String,
    val renewsAt: String? = null
)

@Serializable
data class MySubscriptionDto(
    val subscriptionId: String,
    val creatorId: String,
    val creatorName: String? = null,
    val creatorUsername: String? = null,
    val creatorAvatarUrl: String? = null,
    val creatorVerified: Boolean = false,
    val planName: String,
    val interval: String,
    val formattedPrice: String,
    val status: String,
    val renewsAt: String? = null,
    val autoRenew: Boolean = true,
    val startedAt: String? = null,
    val cancelAtPeriodEnd: Boolean = false,
    val provider: String? = null
)

@Serializable
data class SubscriptionPaymentHistoryDto(
    val paymentId: String,
    val subscriptionId: String? = null,
    val creatorName: String? = null,
    val planName: String,
    val amountMinor: Long,
    val formattedAmount: String,
    val currency: String,
    val provider: String,
    val status: String,
    val createdAt: String,
    val paidAt: String? = null,
    val failureReason: String? = null,
    val refundStatus: String? = null
)

@Serializable
data class CreatorSubscriptionPaymentDto(
    val paymentId: String,
    val subscriberName: String? = null,
    val planName: String,
    val amountMinor: Long,
    val formattedAmount: String,
    val currency: String,
    val provider: String,
    val status: String,
    val paidAt: String? = null,
    val refundStatus: String? = null
)

@Serializable
data class SubscriptionRefundDto(
    val paymentId: String,
    val status: String,
    val amountMinor: Long,
    val formattedAmount: String,
    val currency: String,
    val createdAt: String,
    val updatedAt: String,
    val failureReason: String? = null
)

@Serializable
data class SubscribePageDto(
    val creatorId: String,
    val name: String? = null,
    val username: String? = null,
    val avatarUrl: String? = null,
    val bio: String? = null,
    val isVerified: Boolean = false,
    val plans: List<SubscriptionPlanDto> = emptyList(),
    /** PAYSTACK | PAYPAL — the single payment method offered to this viewer, server-decided by country. */
    val provider: String,
    val providerLabel: String,
    val viewerSubscription: MySubscriptionDto? = null
)

@Serializable
data class InitiatedSubPaymentDto(
    val paymentId: String,
    val subscriptionId: String,
    val provider: String,
    val checkoutUrl: String,
    val reference: String,
    val amountMinor: Long,
    val formattedAmount: String,
    val currency: String,
    val reused: Boolean = false
)

@Serializable
data class SubPaymentStatusDto(
    val paymentId: String,
    /** PENDING | PAID | FAILED | REFUNDED — authoritative, provider-verified. */
    val status: String,
    val failureReason: String? = null,
    val subscriptionId: String? = null,
    val subscriptionStatus: String? = null
)

@Serializable
private data class SubscribeRequestBody(val planId: String)

@Serializable
private data class CreatePlanBody(val name: String, val description: String, val interval: String, val priceMinor: Long)

@Serializable
private data class UpdatePlanBody(val name: String, val description: String, val priceMinor: Long, val isMostPopular: Boolean)

/**
 * Client for the paid-subscription endpoints. Payments carry a client-generated
 * Idempotency-Key so a retried or double-tapped subscribe can never double-charge.
 */
class SubscriptionApi(private val client: HttpClient) {
    private val base get() = ApiConfig.baseUrl.trimEnd('/')

    // ---- Creator ----
    suspend fun myPlans(): List<SubscriptionPlanDto> =
        client.get("$base/api/subscriptions/plans").body()

    suspend fun createPlan(name: String, description: String, interval: String, priceMinor: Long): SubscriptionPlanDto =
        client.post("$base/api/subscriptions/plans") {
            contentType(ContentType.Application.Json)
            setBody(CreatePlanBody(name, description, interval, priceMinor))
        }.body()

    suspend fun updatePlan(planId: String, name: String, description: String, priceMinor: Long, isMostPopular: Boolean): SubscriptionPlanDto =
        client.put("$base/api/subscriptions/plans/$planId") {
            contentType(ContentType.Application.Json)
            setBody(UpdatePlanBody(name, description, priceMinor, isMostPopular))
        }.body()

    suspend fun deletePlan(planId: String) {
        client.delete("$base/api/subscriptions/plans/$planId")
    }

    suspend fun overview(periodDays: Int): SubOverviewDto =
        client.get("$base/api/subscriptions/overview") { parameter("periodDays", periodDays) }.body()

    suspend fun insights(periodDays: Int): SubInsightsDto =
        client.get("$base/api/subscriptions/insights") { parameter("periodDays", periodDays) }.body()

    suspend fun subscribers(limit: Int = 50, offset: Int = 0): List<SubscriberDto> =
        client.get("$base/api/subscriptions/subscribers") {
            parameter("limit", limit); parameter("offset", offset)
        }.body()

    suspend fun recentSubscribers(): List<SubscriberDto> =
        client.get("$base/api/subscriptions/subscribers/recent").body()

    suspend fun creatorPayments(limit: Int = 100): List<CreatorSubscriptionPaymentDto> =
        client.get("$base/api/subscriptions/creator/payments") { parameter("limit", limit) }.body()

    suspend fun requestRefund(paymentId: String): SubscriptionRefundDto =
        client.post("$base/api/subscriptions/payments/$paymentId/refund").body()

    suspend fun refundStatus(paymentId: String): SubscriptionRefundDto =
        client.get("$base/api/subscriptions/payments/$paymentId/refund").body()

    // ---- Fan ----
    suspend fun subscribePage(creatorId: String): SubscribePageDto =
        client.get("$base/api/subscriptions/page/$creatorId").body()

    suspend fun subscribe(planId: String, idempotencyKey: String): InitiatedSubPaymentDto =
        client.post("$base/api/subscriptions/subscribe") {
            contentType(ContentType.Application.Json)
            header("Idempotency-Key", idempotencyKey)
            setBody(SubscribeRequestBody(planId))
        }.body()

    suspend fun confirmPayment(paymentId: String): SubPaymentStatusDto =
        client.post("$base/api/subscriptions/payments/$paymentId/confirm").body()

    suspend fun paymentStatus(paymentId: String): SubPaymentStatusDto =
        client.get("$base/api/subscriptions/payments/$paymentId/status").body()

    suspend fun unsubscribe(subscriptionId: String) {
        client.post("$base/api/subscriptions/$subscriptionId/unsubscribe")
    }

    suspend fun mySubscriptions(): List<MySubscriptionDto> =
        client.get("$base/api/subscriptions/mine").body()

    suspend fun myPaymentHistory(): List<SubscriptionPaymentHistoryDto> =
        client.get("$base/api/subscriptions/mine/payments").body()
}
