package com.telefam.verification

import kotlinx.serialization.Serializable

/** Wire DTOs mirroring the backend verification endpoints exactly. */

@Serializable
data class PlanPriceDto(
    val plan: String,
    val amountMinor: Long,
    val formatted: String,
    val strikeFormatted: String? = null,
    val offPercent: Int = 0
)

@Serializable
data class PricingResponseDto(
    /** PAYSTACK | PAYPAL — the single payment method available to this user (server-decided). */
    val provider: String,
    val providerLabel: String,
    val currency: String,
    val countryCode: String,
    val plans: List<PlanPriceDto>
) {
    val monthly: PlanPriceDto? get() = plans.firstOrNull { it.plan == "MONTHLY" }
    val annual: PlanPriceDto? get() = plans.firstOrNull { it.plan == "ANNUAL" }
}

@Serializable
data class InitiatedPaymentDto(val paymentId: String, val provider: String, val checkoutUrl: String, val reference: String)

@Serializable
data class PaymentStatusDto(val paymentId: String, val status: String, val failureReason: String? = null, val refunded: Boolean = false)

@Serializable
data class VerificationStatusDto(
    val state: String, // NONE | PAYMENT_PENDING | LIVENESS | ID_CAPTURE | SUBMITTED | PENDING_REVIEW | APPROVED | REJECTED | ADMIN_REVIEW
    val applicationId: String? = null,
    val paymentId: String? = null,
    val rejectionReason: String? = null,
    val canRetry: Boolean = false,
    val refundEligible: Boolean = false,
    val refundPolicyDays: Long = 0,
    val badgeExpiresAt: String? = null
)

@Serializable
data class LivenessChallengeDto(
    val challengeId: String,
    val movements: List<String>, // e.g. [UP, LEFT, RIGHT, UP, LEFT, RIGHT] — server-randomised
    val signature: String,       // HMAC the server will check on completion
    val expiresInSeconds: Long
)

@Serializable
data class LivenessCompletionRequest(
    val signature: String,
    val movementTimestampsMs: List<Long>,
    val selfieMediaId: String
)

@Serializable
data class IdSubmissionRequestDto(val frontMediaId: String, val backMediaId: String)

@Serializable
data class StateResponse(val state: String)
