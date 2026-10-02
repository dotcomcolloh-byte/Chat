package com.telefam.creator

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.datetime

/**
 * Creator-program tables: monetization applications, Stars transactions and
 * Stars goals. All queries go through Exposed's typed DSL (prepared statements
 * only) and every table is RLS-protected in db/rls_policies.sql.
 */

/**
 * One row per user. Monetization is invite-only with manual review:
 * submitting an application ALWAYS lands in UNDER_REVIEW — there is no
 * auto-approval path anywhere in the codebase.
 */
object MonetizationApplications : UUIDTable("monetization_applications") {
    val userId = uuid("user_id").uniqueIndex()
    /** UNDER_REVIEW | APPROVED | REJECTED */
    val status = varchar("status", 20).default("UNDER_REVIEW")
    val submittedAt = datetime("submitted_at")
    val reviewedAt = datetime("reviewed_at").nullable()
    /** JSON array of rejection reason codes, e.g. ["ELIGIBILITY","CONTENT","HISTORY"]. */
    val rejectionReasons = varchar("rejection_reasons", 1000).default("[]")
    /** JSON snapshot of the eligibility metrics at submission time (audit trail). */
    val progressSnapshot = varchar("progress_snapshot", 2000).default("{}")
}

/** One row per Stars gift from a fan to a creator. */
object StarTransactions : UUIDTable("star_transactions") {
    val senderId = uuid("sender_id")
    val receiverId = uuid("receiver_id")
    val stars = long("stars")
    /** Creator earnings in USD cents for this gift. */
    val amountCents = long("amount_cents")
    val createdAt = datetime("created_at")
    init {
        index("idx_star_tx_receiver_created", false, receiverId, createdAt)
        index("idx_star_tx_sender_created", false, senderId, createdAt)
    }
}

/** A creator's public Stars goal (one active goal per creator). */
object StarGoals : UUIDTable("star_goals") {
    val userId = uuid("user_id").uniqueIndex()
    val title = varchar("title", 120).default("")
    val targetStars = long("target_stars")
    val updatedAt = datetime("updated_at")
}
