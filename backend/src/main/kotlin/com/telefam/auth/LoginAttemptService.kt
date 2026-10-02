package com.telefam.auth

import com.telefam.config.AppConfig
import com.telefam.db.DatabaseFactory.dbQuery
import com.telefam.db.LoginAttempts
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.*

sealed class LoginGate {
    object Allowed : LoginGate()
    data class Locked(val secondsRemaining: Long) : LoginGate()
}

/**
 * 3 failed password attempts -> account locked for LOGIN_LOCKOUT_MINUTES.
 * Purely server-enforced; the API response only ever carries a generic
 * "invalid credentials" or a plain retry-after seconds count — never the
 * word "locked" or the attempt count, so it can't be used to enumerate accounts.
 */
class LoginAttemptService {

    suspend fun checkGate(userId: UUID): LoginGate {
        val now = LocalDateTime.now()
        val row = dbQuery { LoginAttempts.selectAll().where { LoginAttempts.userId eq userId }.singleOrNull() }
        val lockedUntil = row?.get(LoginAttempts.lockedUntil)
        if (lockedUntil != null && lockedUntil.isAfter(now)) {
            return LoginGate.Locked(ChronoUnit.SECONDS.between(now, lockedUntil))
        }
        return LoginGate.Allowed
    }

    suspend fun recordFailure(userId: UUID): LoginGate {
        val now = LocalDateTime.now()
        val row = dbQuery { LoginAttempts.selectAll().where { LoginAttempts.userId eq userId }.singleOrNull() }

        if (row == null) {
            dbQuery {
                LoginAttempts.insert {
                    it[LoginAttempts.userId] = userId
                    it[LoginAttempts.failedCount] = 1
                    it[LoginAttempts.lastAttemptAt] = now
                }
            }
            return LoginGate.Allowed
        }

        val newCount = row[LoginAttempts.failedCount] + 1
        com.telefam.risk.RiskService.record(userId, com.telefam.risk.RiskService.LOGIN_FAILURE)
        if (newCount >= AppConfig.maxLoginAttempts) {
            val lockedUntil = now.plusMinutes(AppConfig.loginLockoutMinutes)
            com.telefam.risk.RiskService.record(userId, com.telefam.risk.RiskService.LOGIN_LOCKOUT)
            dbQuery {
                LoginAttempts.update({ LoginAttempts.userId eq userId }) {
                    it[LoginAttempts.failedCount] = 0
                    it[LoginAttempts.lockedUntil] = lockedUntil
                    it[LoginAttempts.lastAttemptAt] = now
                }
            }
            return LoginGate.Locked(AppConfig.loginLockoutMinutes * 60)
        }

        dbQuery {
            LoginAttempts.update({ LoginAttempts.userId eq userId }) {
                it[LoginAttempts.failedCount] = newCount
                it[LoginAttempts.lastAttemptAt] = now
            }
        }
        return LoginGate.Allowed
    }

    suspend fun recordSuccess(userId: UUID) {
        dbQuery {
            LoginAttempts.update({ LoginAttempts.userId eq userId }) {
                it[LoginAttempts.failedCount] = 0
                it[LoginAttempts.lockedUntil] = null
            }
        }
    }
}
