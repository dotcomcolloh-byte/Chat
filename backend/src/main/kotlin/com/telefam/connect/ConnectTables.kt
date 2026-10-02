package com.telefam.connect

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.datetime

/**
 * Social-graph support tables for the Contacts / Discover / Profile features.
 *
 * Privacy model for contact discovery: clients NEVER upload raw phone numbers or
 * emails — only salted SHA-256 hashes (see shared/.../connect/ContactHash.kt for
 * the exact normalisation both sides implement). Hashes of people who are not on
 * Telefam are stored so a future signup can still be matched, but they are never
 * returned to anyone.
 */
object ContactHashes : UUIDTable("contact_hashes") {
    val userId = uuid("user_id")
    /** PHONE | EMAIL */
    val hashType = varchar("hash_type", 10)
    val hashValue = varchar("hash_value", 64)
    val uploadedAt = datetime("uploaded_at")
    init { uniqueIndex(userId, hashType, hashValue) }
}

/**
 * DB-backed follow rate limiting (survives restarts, works across instances sharing
 * the database): a fixed per-minute window and a fixed per-hour window per actor.
 */
object FollowRateLimits : UUIDTable("follow_rate_limits") {
    val userId = uuid("user_id").uniqueIndex()
    val minuteWindowStart = datetime("minute_window_start")
    val minuteCount = integer("minute_count").default(0)
    val hourWindowStart = datetime("hour_window_start")
    val hourCount = integer("hour_count").default(0)
    val updatedAt = datetime("updated_at")
}

/**
 * Behavioural risk score per user, maintained server-side only. Followers AND
 * followees are scored: bursts of following (bot follow-farms), follow-back ratio,
 * account age and being-mass-followed patterns all feed the score. Only a coarse
 * level (LOW/MEDIUM/HIGH) is ever exposed to clients, and never the raw signals.
 */
object UserRiskScores : UUIDTable("user_risk_scores") {
    val userId = uuid("user_id").uniqueIndex()
    val score = integer("score").default(0)
    /** Comma-separated machine-readable signal codes, e.g. "FOLLOW_BURST,NEW_ACCOUNT". */
    val signals = varchar("signals", 500).default("")
    val updatedAt = datetime("updated_at")
}
