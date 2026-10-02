package com.telefam.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import com.telefam.config.AppConfig
import com.telefam.db.DatabaseFactory.dbQuery
import com.telefam.db.OtpCodes
import com.telefam.email.ResendClient
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.random.Random

enum class OtpPurpose { SIGNUP_VERIFY, PASSWORD_RESET, LOGIN_2FA, EMAIL_CHANGE, PAYOUT_SECURITY }

sealed class OtpSendResult {
    /** secondsUntilNextResend is the ONLY thing the client shows — a plain counter, no reason text. */
    data class Sent(val secondsUntilNextResend: Long) : OtpSendResult()
    data class Cooldown(val secondsRemaining: Long) : OtpSendResult()
}

sealed class OtpVerifyResult {
    object Success : OtpVerifyResult()
    object Invalid : OtpVerifyResult()
    data class Cooldown(val secondsRemaining: Long) : OtpVerifyResult()
}

class OtpService(private val resendClient: ResendClient) {

    private fun generateCode(): String = Random.nextInt(0, 1_000_000).toString().padStart(6, '0')

    suspend fun sendOtp(userId: UUID, email: String, purpose: OtpPurpose): OtpSendResult {
        val now = LocalDateTime.now()
        val existing = dbQuery {
            OtpCodes.selectAll().where { (OtpCodes.userId eq userId) and (OtpCodes.purpose eq purpose.name) and (OtpCodes.consumed eq false) }
                .orderBy(OtpCodes.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC)
                .firstOrNull()
        }

        // Active cooldown from earlier abuse (5 fails or 5 resends) — server enforced, UI only sees a counter.
        existing?.get(OtpCodes.cooldownUntil)?.let { cooldownUntil ->
            if (cooldownUntil.isAfter(now)) {
                return OtpSendResult.Cooldown(ChronoUnit.SECONDS.between(now, cooldownUntil))
            }
        }

        // 60-second minimum spacing between sends.
        existing?.let {
            val secondsSinceLast = ChronoUnit.SECONDS.between(it[OtpCodes.lastSentAt], now)
            if (secondsSinceLast < AppConfig.otpResendIntervalSeconds) {
                return OtpSendResult.Cooldown(AppConfig.otpResendIntervalSeconds - secondsSinceLast)
            }
        }

        val resendCount = (existing?.get(OtpCodes.resendCount) ?: 0) + 1
        val code = generateCode()
        val codeHash = BCrypt.withDefaults().hashToString(10, code.toCharArray())
        val expiresAt = now.plusMinutes(AppConfig.otpTtlMinutes)

        if (resendCount > AppConfig.otpResendMaxCount) {
            // 5th+ resend within a window -> lock out for 5 hours, invalidate current code.
            val cooldownUntil = now.plusHours(AppConfig.otpCooldownHours)
            dbQuery {
                if (existing != null) {
                    OtpCodes.update({ OtpCodes.id eq existing[OtpCodes.id] }) {
                        it[OtpCodes.cooldownUntil] = cooldownUntil
                        it[OtpCodes.consumed] = true
                    }
                }
            }
            com.telefam.risk.RiskService.record(userId, com.telefam.risk.RiskService.OTP_COOLDOWN, detail = purpose.name)
            return OtpSendResult.Cooldown(AppConfig.otpCooldownHours * 3600)
        }

        dbQuery {
            if (existing != null) {
                OtpCodes.update({ OtpCodes.id eq existing[OtpCodes.id] }) { it[OtpCodes.consumed] = true }
            }
            OtpCodes.insert {
                it[OtpCodes.userId] = userId
                it[OtpCodes.purpose] = purpose.name
                it[OtpCodes.codeHash] = codeHash
                it[OtpCodes.expiresAt] = expiresAt
                it[OtpCodes.consumed] = false
                it[OtpCodes.failedAttempts] = 0
                it[OtpCodes.resendCount] = resendCount
                it[OtpCodes.lastSentAt] = now
                it[OtpCodes.createdAt] = now
            }
        }

        resendClient.sendOtpEmail(email, code)
        return OtpSendResult.Sent(AppConfig.otpResendIntervalSeconds)
    }

    suspend fun verifyOtp(userId: UUID, purpose: OtpPurpose, submittedCode: String): OtpVerifyResult {
        val now = LocalDateTime.now()
        val row = dbQuery {
            OtpCodes.selectAll().where { (OtpCodes.userId eq userId) and (OtpCodes.purpose eq purpose.name) and (OtpCodes.consumed eq false) }
                .orderBy(OtpCodes.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC)
                .firstOrNull()
        } ?: return OtpVerifyResult.Invalid

        row[OtpCodes.cooldownUntil]?.let {
            if (it.isAfter(now)) return OtpVerifyResult.Cooldown(ChronoUnit.SECONDS.between(now, it))
        }
        if (row[OtpCodes.expiresAt].isBefore(now)) return OtpVerifyResult.Invalid

        val matches = BCrypt.verifyer().verify(submittedCode.toCharArray(), row[OtpCodes.codeHash]).verified
        if (matches) {
            dbQuery { OtpCodes.update({ OtpCodes.id eq row[OtpCodes.id] }) { it[OtpCodes.consumed] = true } }
            return OtpVerifyResult.Success
        }

        val newFailCount = row[OtpCodes.failedAttempts] + 1
        com.telefam.risk.RiskService.record(userId, com.telefam.risk.RiskService.OTP_VERIFY_FAILED, detail = purpose.name)
        if (newFailCount >= AppConfig.maxOtpAttempts) {
            val cooldownUntil = now.plusHours(AppConfig.otpCooldownHours)
            dbQuery {
                OtpCodes.update({ OtpCodes.id eq row[OtpCodes.id] }) {
                    it[OtpCodes.failedAttempts] = newFailCount
                    it[OtpCodes.cooldownUntil] = cooldownUntil
                    it[OtpCodes.consumed] = true
                }
            }
            com.telefam.risk.RiskService.record(userId, com.telefam.risk.RiskService.OTP_COOLDOWN, detail = purpose.name)
            return OtpVerifyResult.Cooldown(AppConfig.otpCooldownHours * 3600)
        }

        dbQuery { OtpCodes.update({ OtpCodes.id eq row[OtpCodes.id] }) { it[OtpCodes.failedAttempts] = newFailCount } }
        return OtpVerifyResult.Invalid
    }
}
