package com.telefam.risk

import com.telefam.db.DatabaseFactory.dbQuery
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.selectAll
import java.time.LocalDateTime
import java.util.UUID

/**
 * Cross-domain risk events: ONE append-only signal stream fed by auth (failed logins,
 * lockouts, OTP abuse, refresh-token reuse), social actions, and financial actions
 * (payout-method changes, payout bursts, failed payouts).
 *
 * Every sensitive decision (login gating, payout risk scoring, follow velocity limits)
 * reads the SAME combined signal set, so an account that behaves badly in one domain
 * is treated as risky everywhere — no per-feature siloed scores.
 *
 * Privacy: events are internal-only, never serialized to any client. RLS denies all
 * client sessions (see rls_policies.sql); only privileged server connections read/write.
 */
object RiskEvents : Table("risk_events") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").index()
    /** Machine code, e.g. LOGIN_FAILURE, LOGIN_LOCKOUT, OTP_COOLDOWN, PAYOUT_METHOD_CHANGE. */
    val kind = varchar("kind", 40).index()
    /** Relative severity weight contributed to the combined score. */
    val weight = integer("weight")
    val detail = varchar("detail", 200).nullable()
    val createdAt = datetime("created_at").index()

    override val primaryKey = PrimaryKey(id)

    init { index("idx_risk_events_user_created", false, userId, createdAt) }
}

object RiskService {

    /** Decay window: only recent signals count; old good behaviour rehabilitates the score. */
    private val WINDOW: java.time.Duration = java.time.Duration.ofDays(30)

    /** Records a risk signal. Best-effort: risk bookkeeping must never break the main flow. */
    suspend fun record(userId: UUID, kind: String, weight: Int = weightFor(kind), detail: String? = null) {
        runCatching {
            dbQuery {
                RiskEvents.insert {
                    it[RiskEvents.userId] = userId
                    it[RiskEvents.kind] = kind.take(40)
                    it[RiskEvents.weight] = weight.coerceIn(0, 100)
                    it[RiskEvents.detail] = detail?.take(200)
                    it[createdAt] = LocalDateTime.now()
                }
            }
        }
    }

    /**
     * Combined risk points from ALL domains over the decay window (0..100).
     * Read by WalletService.riskScore, auth flows and action limits alike.
     */
    suspend fun recentPoints(userId: UUID): Int = runCatching {
        dbQuery {
            RiskEvents.selectAll().where {
                (RiskEvents.userId eq userId) and
                    (RiskEvents.createdAt greaterEq LocalDateTime.now().minus(WINDOW))
            }.orderBy(RiskEvents.createdAt to SortOrder.DESC).limit(500)
                .sumOf { it[RiskEvents.weight] }
        }
    }.getOrDefault(0).coerceIn(0, 100)

    // ---- Well-known signal kinds (weights tuned conservatively) ----
    const val LOGIN_FAILURE = "LOGIN_FAILURE"               // +5
    const val LOGIN_LOCKOUT = "LOGIN_LOCKOUT"               // +25
    const val OTP_COOLDOWN = "OTP_COOLDOWN"                 // +20
    const val OTP_VERIFY_FAILED = "OTP_VERIFY_FAILED"       // +4
    const val TOKEN_REUSE_DETECTED = "TOKEN_REUSE_DETECTED" // +60 (stolen refresh token replayed)
    const val PAYOUT_METHOD_CHANGE = "PAYOUT_METHOD_CHANGE" // +15
    const val PAYOUT_FAILED = "PAYOUT_FAILED"               // +10
    const val PAYOUT_BURST = "PAYOUT_BURST"                 // +15
    const val PASSWORD_RESET = "PASSWORD_RESET"             // +10
    const val EMAIL_CHANGE = "EMAIL_CHANGE"                 // +10

    fun weightFor(kind: String): Int = when (kind) {
        LOGIN_FAILURE -> 5
        LOGIN_LOCKOUT -> 25
        OTP_COOLDOWN -> 20
        OTP_VERIFY_FAILED -> 4
        TOKEN_REUSE_DETECTED -> 60
        PAYOUT_METHOD_CHANGE -> 15
        PAYOUT_FAILED -> 10
        PAYOUT_BURST -> 15
        PASSWORD_RESET -> 10
        EMAIL_CHANGE -> 10
        else -> 5
    }
}
