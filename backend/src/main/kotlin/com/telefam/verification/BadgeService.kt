package com.telefam.verification

import com.telefam.config.AppConfig
import com.telefam.db.DatabaseFactory.dbQuery
import com.telefam.payments.VerificationBadges
import com.telefam.payments.VerificationApplications
import com.telefam.payments.VerificationPayments
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import java.time.LocalDateTime
import java.util.UUID

/**
 * The verification badge is granted ONLY here, server-side, after review approval.
 * Validity starts only when review grants the badge. Monthly/annual paid terms start
 * at grant; the badge remains active through the configured renewal grace window.
 * Clients can never set or extend a badge themselves.
 */
object BadgeService {

    suspend fun grant(userId: UUID, applicationId: UUID) = dbQuery {
        val now = LocalDateTime.now()
        // The billing period starts only when verification is actually granted, not
        // when checkout succeeds or while the application is awaiting review.
        val application = VerificationApplications.selectAll()
            .where { VerificationApplications.id eq applicationId }.singleOrNull()
        val paymentId = application?.get(VerificationApplications.paymentId)
        val plan = paymentId?.let { id ->
            VerificationPayments.selectAll().where { VerificationPayments.id eq id }.singleOrNull()
                ?.get(VerificationPayments.plan)
        }
        val termDays = when (plan) {
            "MONTHLY" -> 30L
            "ANNUAL" -> 365L
            else -> AppConfig.verificationBadgeTtlDays
        }
        val periodEnd = now.plusDays(termDays)
        VerificationBadges.upsert {
            it[VerificationBadges.userId] = userId
            it[grantedAt] = now
            it[expiresAt] = periodEnd
            it[graceUntil] = periodEnd.plusDays(AppConfig.verificationBillingGraceDays)
            it[VerificationBadges.applicationId] = applicationId
        }
    }

    suspend fun revoke(userId: UUID) = dbQuery {
        VerificationBadges.deleteWhere { VerificationBadges.userId eq userId }
    }

    suspend fun isVerified(userId: UUID): Boolean = dbQuery {
        VerificationBadges.selectAll().where {
            (VerificationBadges.userId eq userId) and (
                (VerificationBadges.expiresAt greater LocalDateTime.now()) or
                    (VerificationBadges.graceUntil greater LocalDateTime.now())
            )
        }.any()
    }

    /** Batch lookup used to decorate every user payload (inbox, feeds, comments, search, lists). */
    suspend fun verifiedUserIds(ids: Collection<UUID>): Set<UUID> {
        if (ids.isEmpty()) return emptySet()
        return dbQuery {
            VerificationBadges.selectAll().where {
                (VerificationBadges.userId inList ids.toList()) and (
                    (VerificationBadges.expiresAt greater LocalDateTime.now()) or
                        (VerificationBadges.graceUntil greater LocalDateTime.now())
                )
            }.map { it[VerificationBadges.userId] }.toSet()
        }
    }

    /** Opportunistic sweep — removes expired rows when an admin/status path touches it. */
    suspend fun purgeExpired() = dbQuery {
        VerificationBadges.deleteWhere { graceUntil lessEq LocalDateTime.now() }
    }
}
