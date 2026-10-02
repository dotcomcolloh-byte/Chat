package com.telefam.wallet

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.datetime

/**
 * Telefam Wallet tables — the financial core.
 *
 * Money-handling invariants (same discipline as verification/subscription payments):
 *  - Amounts are ALWAYS stored in MINOR units (cents / cents-of-KSh), server-computed.
 *    The client never sends an authoritative amount, fee, balance or risk score.
 *  - The ledger (`WalletLedgerEntries`) is IMMUTABLE: rows are inserted, never updated
 *    or deleted. Corrections happen through reversal/adjustment entries.
 *  - Balances are derived from the ledger (available = earnings available + releases
 *    − reserves − paid payouts − refund reversals …), not mutated with += / −=.
 *  - Every mutating operation carries an idempotency key with a unique index —
 *    retries return the original result instead of duplicating money movement.
 *  - All tables are RLS-protected in db/rls_policies.sql.
 */

/** Earning lifecycle sources shown in the Monetization Overview. */
enum class EarningSource { SUBSCRIPTION, MONETIZATION, STARS, OTHER }

/** Earning settlement status machine: PENDING → AVAILABLE | REVERSED | REVIEW. */
enum class EarningStatus { PENDING, AVAILABLE, REVERSED, REVIEW }

/** Ledger entry types. Entries are append-only. */
enum class LedgerType {
    EARNING,            // + net creator earning (available part happens at SETTLEMENT)
    PLATFORM_FEE,       // + Telefam platform share (informational on creator ledger)
    SETTLEMENT,         // earning moved PENDING → AVAILABLE
    PAYOUT_RESERVE,     // − funds locked for a payout request
    PAYOUT_PAID,        // reserved funds left the platform
    PAYOUT_RELEASE,     // + reserved funds returned to available on failure/rejection/cancel
    REFUND,             // − creator earning reversal for a confirmed refund
    REFUND_REVERSAL,    // + a refund reversal was itself reversed
    ADJUSTMENT          // ± manual/support adjustment
}

/**
 * One earning event (gross → fee → net) with its settlement schedule.
 * `netAmountMinor` is what the creator eventually receives; `platformFeeMinor` is
 * Telefam's configurable share (default 20%).
 */
object CreatorEarnings : UUIDTable("creator_earnings") {
    val creatorId = uuid("creator_id").index()
    val earningSource = varchar("source", 16) // SUBSCRIPTION | MONETIZATION | STARS | OTHER
    val grossAmountMinor = long("gross_amount_minor")
    val platformFeeMinor = long("platform_fee_minor")
    val netAmountMinor = long("net_amount_minor")
    val currency = varchar("currency", 8)
    /** PENDING | AVAILABLE | REVERSED | REVIEW */
    val status = varchar("status", 12).default("PENDING").index()
    val earnedAt = datetime("earned_at")
    /** When the settlement period completes (configurable, SETTLEMENT_PERIOD_HOURS). */
    val settlementAt = datetime("settlement_at")
    /** When it actually became available (null while pending/reversed). */
    val availableAt = datetime("available_at").nullable()
    /** Links back to the originating object, e.g. subscription payment id / stars tx id. */
    val referenceType = varchar("reference_type", 40)
    val referenceId = varchar("reference_id", 64)
    /** One earning per logical event — subscription payment, renewal, stars batch, ad payout. */
    val idempotencyKey = varchar("idempotency_key", 96).uniqueIndex()
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    init { index("idx_earnings_creator_status", false, creatorId, status) }
}

/** Immutable append-only financial ledger. Balances are derived from these rows. */
object WalletLedgerEntries : UUIDTable("wallet_ledger_entries") {
    val userId = uuid("user_id").index()
    val type = varchar("type", 20) // LedgerType
    /** Signed minor amount: credits positive, debits negative. */
    val amountMinor = long("amount_minor")
    val currency = varchar("currency", 8)
    val earningSource = varchar("source", 16).nullable() // EarningSource, where applicable
    val earningId = uuid("earning_id").nullable()
    val payoutId = uuid("payout_id").nullable()
    val refundId = varchar("refund_id", 64).nullable()
    /** Human-safe description rendered in transaction history. */
    val description = varchar("description", 200)
    /** External/provider reference, safe to display. */
    val reference = varchar("reference", 96)
    val idempotencyKey = varchar("idempotency_key", 96).uniqueIndex()
    val createdAt = datetime("created_at").index()
    init { index("idx_ledger_user_created", false, userId, createdAt) }
}

/**
 * A withdrawal request. Funds are RESERVED at creation and only ever transition:
 * REQUESTED → SECURITY_CHECK → PENDING → APPROVED → PROCESSING → PAID
 *                                                        └→ FAILED / REJECTED / CANCELED
 */
object PayoutRequests : UUIDTable("payout_requests") {
    val userId = uuid("user_id").index()
    val methodId = uuid("method_id")
    val amountMinor = long("amount_minor")          // requested, reserved
    val payoutFeeMinor = long("payout_fee_minor")   // provider/payout fee, server-computed
    val netAmountMinor = long("net_amount_minor")   // amount − fee; what the creator receives
    val currency = varchar("currency", 8)
    /** REQUESTED | SECURITY_CHECK | PENDING | APPROVED | PROCESSING | PAID | FAILED | REJECTED | CANCELED */
    val status = varchar("status", 16).default("REQUESTED").index()
    val provider = varchar("provider", 16)          // MPESA | BANK | PAYPAL
    val providerRef = varchar("provider_ref", 128).nullable().uniqueIndex()
    /** Our own stable public reference ("PO-XXXXXXXX"), stored + indexed at creation so
     *  webhooks resolve payouts by index instead of scanning the whole table. */
    val publicRef = varchar("public_ref", 16).nullable()
    /** Safe, user-facing failure reason — never internal risk detail. */
    val userMessage = varchar("user_message", 300).nullable()
    /** Internal risk outcome (LOW/MEDIUM/HIGH/REVIEW) — never serialized to the client. */
    val riskLevel = varchar("risk_level", 12).nullable()
    val idempotencyKey = varchar("idempotency_key", 96).uniqueIndex()
    val lastWebhookHash = varchar("last_webhook_hash", 64).nullable()
    val requestedAt = datetime("requested_at")
    val processedAt = datetime("processed_at").nullable()
    val completedAt = datetime("completed_at").nullable()
    val updatedAt = datetime("updated_at")
    init { index("idx_payout_user_status", false, userId, status) }
}

/** A creator payout destination. Only masked identifiers are stored in the clear. */
object PayoutMethods : UUIDTable("payout_methods") {
    val userId = uuid("user_id").index()
    /** MPESA | BANK | PAYPAL */
    val type = varchar("type", 12)
    val countryCode = varchar("country_code", 4)
    /** Display label, e.g. "Kenya Commercial Bank" / "M-Pesa" / "PayPal". */
    val label = varchar("label", 80)
    /** Masked identifier only, e.g. "•••• 4587" or "r•••@gmail.com". */
    val maskedDetail = varchar("masked_detail", 80)
    /** AES-GCM encrypted full detail (account number / MSISDN / PayPal email), needed for disbursement. */
    val detailEnc = text("detail_enc").nullable()
    /** PENDING | VERIFIED | REJECTED */
    val verificationStatus = varchar("verification_status", 12).default("PENDING")
    /** ACTIVE | REMOVED — removal is soft so historical payouts keep their destination. */
    val status = varchar("status", 12).default("ACTIVE")
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    init { index("idx_payout_methods_user", false, userId, status) }
}

/**
 * Sensitive payout-method changes (add/change/remove) require an email OTP sent to
 * the account's VERIFIED email — the client never chooses the security email.
 * The change is only applied after OTP + device/risk checks pass.
 */
object PayoutMethodChanges : UUIDTable("payout_method_changes") {
    val userId = uuid("user_id").index()
    /** ADD | CHANGE | REMOVE */
    val action = varchar("action", 12)
    val methodId = uuid("method_id").nullable() // null for ADD
    /** Encrypted payload of the new method detail (for ADD/CHANGE). */
    val payloadEnc = text("payload_enc").nullable()
    val label = varchar("label", 80).nullable()
    val maskedDetail = varchar("masked_detail", 80).nullable()
    val type = varchar("type", 12).nullable()
    val countryCode = varchar("country_code", 4).nullable()
    /** PENDING_OTP | APPROVED | REJECTED | EXPIRED */
    val status = varchar("status", 16).default("PENDING_OTP").index()
    val expiresAt = datetime("expires_at")
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
}

/**
 * Generic financial idempotency registry: one row per (key). Repeat submissions
 * return the stored result instead of re-executing the operation.
 */
object WalletIdempotency : UUIDTable("wallet_idempotency") {
    val idempotencyKey = varchar("idempotency_key", 96).uniqueIndex()
    val operation = varchar("operation", 40)
    val userId = uuid("user_id").index()
    val providerReference = varchar("provider_reference", 128).nullable()
    /** Serialized response to replay for duplicate submissions. */
    val resultJson = text("result_json")
    val createdAt = datetime("created_at")
}

/** Append-only audit trail of wallet events (settlement runs, payout transitions, webhooks). */
object WalletEvents : UUIDTable("wallet_events") {
    val userId = uuid("user_id").nullable().index()
    val kind = varchar("kind", 40) // SETTLEMENT_RUN, PAYOUT_TRANSITION, WEBHOOK_RECEIVED, …
    val payload = text("payload")
    val createdAt = datetime("created_at")
}
