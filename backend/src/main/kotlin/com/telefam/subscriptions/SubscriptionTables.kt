package com.telefam.subscriptions

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.datetime

/**
 * Paid creator-subscription tables (the "Supporter" plans fans pay for).
 *
 * Money-handling invariants (same discipline as verification payments):
 *  - Amounts are stored in MINOR units and always server-computed from the plan
 *    row — the client never sends an amount or currency for a charge.
 *  - A payment becomes PAID only after an authoritative provider check
 *    (Paystack /verify, PayPal capture+GET) or a signature-validated webhook
 *    that is itself re-checked against the provider API.
 *  - Every initiation is idempotent via `idempotencyKey`; every webhook
 *    application is deduplicated by payload hash.
 *  - All tables are RLS-protected in db/rls_policies.sql.
 */

/** A creator's subscription plan (e.g. "Supporter — $4.99 / month"). */
object SubscriptionPlans : UUIDTable("subscription_plans") {
    val creatorId = uuid("creator_id").index()
    val name = varchar("name", 60)
    val description = varchar("description", 200).default("")
    /** DAILY | WEEKLY | MONTHLY */
    val interval = varchar("interval", 10)
    /** Price in the smallest unit of `currency`, set by the creator (their country currency). */
    val priceMinor = long("price_minor")
    /** ISO-4217, server-filled from the creator's profile country (USD fallback). */
    val currency = varchar("currency", 8)
    val isActive = bool("is_active").default(true)
    /** Shown as the highlighted "Most Popular" card on the subscribe page. */
    val isMostPopular = bool("is_most_popular").default(false)
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
}

/** One paid subscription relationship per (subscriber, plan). */
object PaidSubscriptions : UUIDTable("paid_subscriptions") {
    val subscriberId = uuid("subscriber_id").index()
    val creatorId = uuid("creator_id").index()
    val planId = uuid("plan_id")
    /** PENDING_PAYMENT | ACTIVE | PAST_DUE | CANCELED | EXPIRED */
    val status = varchar("status", 20).default("PENDING_PAYMENT").index()
    val autoRenew = bool("auto_renew").default(true)
    /** Renewal tokens are AES-GCM encrypted at rest. PayPal one-time checkout has no token. */
    val renewalToken = text("renewal_token").nullable()
    val billingEmail = varchar("billing_email", 320).nullable()
    /** Price and cadence are locked per subscriber; later creator edits affect new purchases. */
    val recurringAmountMinor = long("recurring_amount_minor").nullable()
    val recurringCurrency = varchar("recurring_currency", 8).nullable()
    val recurringInterval = varchar("recurring_interval", 10).nullable()
    val cancelAtPeriodEnd = bool("cancel_at_period_end").default(false)
    val retryCount = integer("retry_count").default(0)
    val nextRetryAt = datetime("next_retry_at").nullable()
    val graceUntil = datetime("grace_until").nullable()
    val currentPeriodStart = datetime("current_period_start").nullable()
    val currentPeriodEnd = datetime("current_period_end").nullable()
    val canceledAt = datetime("canceled_at").nullable()
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    init { uniqueIndex(subscriberId, planId) }
}

/**
 * One charge per subscription period. `idempotencyKey` is client-generated per
 * subscribe attempt — a retry with the same key returns the SAME payment, so a
 * fan can never be double-charged by a double-tap or a retried network call.
 */
object SubscriptionPayments : UUIDTable("subscription_payments") {
    val subscriptionId = uuid("subscription_id").nullable()
    val isRenewal = bool("is_renewal").default(false)
    val billingPeriodStart = datetime("billing_period_start").nullable()
    val subscriberId = uuid("subscriber_id").index()
    val creatorId = uuid("creator_id").index()
    val planId = uuid("plan_id")
    val provider = varchar("provider", 16) // PAYSTACK | PAYPAL
    val amountMinor = long("amount_minor")
    val currency = varchar("currency", 8)
    val countryCode = varchar("country_code", 4) // subscriber country used for provider routing
    val providerRef = varchar("provider_ref", 128).uniqueIndex()
    val idempotencyKey = varchar("idempotency_key", 80).uniqueIndex()
    /** PENDING | PAID | FAILED | REFUNDED — never settable by the client. */
    val status = varchar("status", 12).default("PENDING").index()
    val checkoutUrl = varchar("checkout_url", 600).nullable()
    val lastWebhookHash = varchar("last_webhook_hash", 64).nullable()
    val failureReason = varchar("failure_reason", 300).nullable()
    val paidAt = datetime("paid_at").nullable()
    val refundedAt = datetime("refunded_at").nullable()
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    init { index("idx_subpay_creator_status", false, creatorId, status) }
}

/**
 * One full-refund request per paid charge. A durable row is created before the
 * provider call so timeouts/crashes are reconciled rather than blindly retried.
 */
object SubscriptionRefunds : UUIDTable("subscription_refunds") {
    val paymentId = uuid("payment_id").uniqueIndex()
    val creatorId = uuid("creator_id").index()
    val subscriberId = uuid("subscriber_id").index()
    val provider = varchar("provider", 16)
    /** Stable PayPal-Request-Id; also internal correlation for Paystack attempts. */
    val requestId = uuid("request_id").uniqueIndex()
    val paypalReplayCount = integer("paypal_replay_count").default(0)
    val providerRefundRef = varchar("provider_refund_ref", 128).nullable()
    val amountMinor = long("amount_minor")
    val currency = varchar("currency", 8)
    /** SUBMITTING | UNKNOWN | PENDING | PROCESSING | PROCESSED | FAILED | NEEDS_REVIEW */
    val status = varchar("status", 24).default("SUBMITTING").index()
    val failureReason = varchar("failure_reason", 300).nullable()
    val lastWebhookHash = varchar("last_webhook_hash", 64).nullable()
    val lastReconciledAt = datetime("last_reconciled_at").nullable()
    val completedAt = datetime("completed_at").nullable()
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    init { index("idx_subrefund_status_updated", false, status, updatedAt) }
}
