package com.telefam.subscriptions

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import java.time.LocalDateTime
import java.util.UUID

/** Must be called inside a DatabaseFactory.dbQuery transaction. */
object SubscriptionEntitlements {
    fun hasAccess(subscriberId: UUID, creatorId: UUID, now: LocalDateTime = LocalDateTime.now()): Boolean {
        if (subscriberId == creatorId) return true
        return PaidSubscriptions.selectAll().where {
            (PaidSubscriptions.subscriberId eq subscriberId) and
                (PaidSubscriptions.creatorId eq creatorId) and
                (
                    ((PaidSubscriptions.status eq "ACTIVE") and
                        PaidSubscriptions.currentPeriodEnd.isNotNull() and
                        (PaidSubscriptions.currentPeriodEnd greaterEq now)) or
                    ((PaidSubscriptions.status eq "PAST_DUE") and
                        (PaidSubscriptions.graceUntil greaterEq now))
                )
        }.any()
    }
}