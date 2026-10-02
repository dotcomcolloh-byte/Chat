package com.telefam.payments

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.datetime

/**
 * Verification payments. Amounts are ALWAYS server-computed (see PricingCatalog) —
 * the client never sends an amount or currency, so there is nothing to tamper with.
 * Status transitions are driven exclusively by provider-side verification
 * (Paystack /transaction/verify, PayPal order capture/GET) and signed webhooks.
 */
object VerificationPayments : UUIDTable("verification_payments") {
    val userId = uuid("user_id").index()
    val provider = varchar("provider", 16) // PAYSTACK | PAYPAL
    val plan = varchar("plan", 10) // MONTHLY | ANNUAL
    val amountMinor = long("amount_minor") // in the smallest unit of `currency` (kobo/cents)
    val currency = varchar("currency", 8)  // ISO-4217, server-decided from profile country
    val countryCode = varchar("country_code", 4) // ISO-3166 alpha-2 used for the decision
    /** Provider-side reference/order id. Unique — a reference can only be consumed once. */
    val providerRef = varchar("provider_ref", 128).uniqueIndex()
    /** PENDING | PAID | FAILED | REFUNDED — never settable by the client. */
    val status = varchar("status", 12).default("PENDING").index()
    val checkoutUrl = varchar("checkout_url", 600).nullable()
    /** SHA-256 of the last webhook payload applied — replay guard. */
    val lastWebhookHash = varchar("last_webhook_hash", 64).nullable()
    val failureReason = varchar("failure_reason", 300).nullable()
    val refundedAt = datetime("refunded_at").nullable()
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
}

/**
 * One verification application per user at a time. The state machine is:
 * PAYMENT_PENDING -> PAID -> LIVENESS -> ID_CAPTURE -> SUBMITTED -> PENDING_REVIEW
 *   -> APPROVED | REJECTED | ADMIN_REVIEW
 * Every transition is performed by the backend; clients only poll status and
 * upload artifacts (selfie clip, ID photos) which are independently re-checked.
 */
object VerificationApplications : UUIDTable("verification_applications") {
    val userId = uuid("user_id").index()
    val paymentId = uuid("payment_id").nullable()
    val state = varchar("state", 20).default("PAYMENT_PENDING").index()
    /** Server-issued liveness challenge: e.g. "UP,LEFT,RIGHT,UP,LEFT,RIGHT". Random per attempt. */
    val livenessChallenge = varchar("liveness_challenge", 64).nullable()
    val livenessChallengeIssuedAt = datetime("liveness_challenge_issued_at").nullable()
    val livenessSelfieMediaId = uuid("liveness_selfie_media_id").nullable()
    val idFrontMediaId = uuid("id_front_media_id").nullable()
    val idBackMediaId = uuid("id_back_media_id").nullable()
    /** Set once AI review (or admin) returns a verdict. */
    val reviewedAt = datetime("reviewed_at").nullable()
    /** Reviewer: AI | ADMIN. Users never see how a decision was produced. */
    val reviewer = varchar("reviewer", 10).nullable()
    val rejectionReason = varchar("rejection_reason", 300).nullable()
    /** Confidence from the document review; below threshold routes to ADMIN_REVIEW. */
    val reviewConfidence = double("review_confidence").nullable()
    val retryCount = integer("retry_count").default(0)
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
}

/** Granted badges. Validity is enforced by `expiresAt` on every read — there is no permanent badge. */
object VerificationBadges : UUIDTable("verification_badges") {
    val userId = uuid("user_id").uniqueIndex()
    val grantedAt = datetime("granted_at")
    /** End of the paid verification period; set only when verification is granted. */
    val expiresAt = datetime("expiresat")
    /** Badge remains active during this renewal grace window if payment is late. */
    val graceUntil = datetime("grace_until").nullable()
    val applicationId = uuid("application_id")
}

/** Append-only audit trail for payments and verification decisions (compliance / disputes). */
object VerificationAudit : UUIDTable("verification_audit") {
    val userId = uuid("user_id").index()
    val actor = varchar("actor", 16) // USER | SYSTEM | WEBHOOK | ADMIN
    val event = varchar("event", 60)
    val detail = varchar("detail", 500).nullable()
    val createdAt = datetime("created_at")
}
