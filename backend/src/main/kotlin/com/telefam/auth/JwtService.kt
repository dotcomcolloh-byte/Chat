package com.telefam.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.telefam.config.AppConfig
import com.telefam.db.RefreshTokens
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.*

/**
 * Refresh-token rotation with reuse detection:
 * every refresh issues a brand-new refresh token and immediately revokes the old one.
 * If a token that's already revoked is presented again, the entire family (chain)
 * is revoked — that's the signal an attacker is replaying a stolen token.
 */
class JwtService {
    private val accessAlgorithm = Algorithm.HMAC256(AppConfig.jwtAccessSecret)
    private val refreshAlgorithm = Algorithm.HMAC256(AppConfig.jwtRefreshSecret)

    fun createAccessToken(userId: UUID): String =
        JWT.create()
            .withIssuer(AppConfig.jwtIssuer)
            .withAudience(AppConfig.jwtAudience)
            .withSubject(userId.toString())
            .withClaim("type", "access")
            .withExpiresAt(Date(System.currentTimeMillis() + AppConfig.jwtAccessTtlMinutes * 60_000))
            .sign(accessAlgorithm)

    fun verifyAccessToken(token: String): UUID? = try {
        val verifier = JWT.require(accessAlgorithm)
            .withIssuer(AppConfig.jwtIssuer)
            .withAudience(AppConfig.jwtAudience)
            .withClaim("type", "access")
            .build()
        UUID.fromString(verifier.verify(token).subject)
    } catch (e: Exception) {
        null
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Issues a fresh refresh token for a brand-new session (new family). */
    suspend fun issueNewRefreshTokenFamily(userId: UUID, deviceInfo: String?): String {
        val familyId = UUID.randomUUID()
        return mintAndStoreRefreshToken(userId, familyId, deviceInfo)
    }

    private suspend fun mintAndStoreRefreshToken(userId: UUID, familyId: UUID, deviceInfo: String?): String {
        val jti = UUID.randomUUID().toString()
        val expiry = System.currentTimeMillis() + AppConfig.jwtRefreshTtlDays * 86_400_000
        val token = JWT.create()
            .withIssuer(AppConfig.jwtIssuer)
            .withAudience(AppConfig.jwtAudience)
            .withSubject(userId.toString())
            .withClaim("type", "refresh")
            .withClaim("family", familyId.toString())
            .withJWTId(jti)
            .withExpiresAt(Date(expiry))
            .sign(refreshAlgorithm)

        val hash = sha256(token)
        com.telefam.db.DatabaseFactory.dbQuery {
            RefreshTokens.insert {
                it[RefreshTokens.userId] = userId
                it[RefreshTokens.tokenHash] = hash
                it[RefreshTokens.familyId] = familyId
                it[RefreshTokens.revoked] = false
                it[RefreshTokens.expiresAt] = LocalDateTime.ofEpochSecond(expiry / 1000, 0, java.time.ZoneOffset.UTC)
                it[RefreshTokens.createdAt] = LocalDateTime.now()
                it[RefreshTokens.deviceInfo] = deviceInfo
            }
        }
        return token
    }

    sealed class RefreshResult {
        data class Success(val newAccessToken: String, val newRefreshToken: String) : RefreshResult()
        object Invalid : RefreshResult()
        object ReuseDetected : RefreshResult() // whole family revoked; caller should force re-login
    }

    /** Validates, rotates, and detects reuse. This is the core anti-theft mechanism. */
    suspend fun rotateRefreshToken(presentedToken: String, deviceInfo: String?): RefreshResult {
        val decoded = try {
            JWT.require(refreshAlgorithm)
                .withIssuer(AppConfig.jwtIssuer)
                .withAudience(AppConfig.jwtAudience)
                .withClaim("type", "refresh")
                .build()
                .verify(presentedToken)
        } catch (e: Exception) {
            return RefreshResult.Invalid
        }

        val userId = UUID.fromString(decoded.subject)
        val familyId = UUID.fromString(decoded.getClaim("family").asString())
        val presentedHash = sha256(presentedToken)

        val row = com.telefam.db.DatabaseFactory.dbQuery {
            RefreshTokens.selectAll().where { RefreshTokens.tokenHash eq presentedHash }.singleOrNull()
        } ?: return RefreshResult.Invalid

        if (row[RefreshTokens.revoked]) {
            // Token was already rotated away and is being presented again -> theft/replay.
            // Nuke the entire family so every descendant token stops working.
            com.telefam.risk.RiskService.record(userId, com.telefam.risk.RiskService.TOKEN_REUSE_DETECTED)
            com.telefam.db.DatabaseFactory.dbQuery {
                RefreshTokens.update({ RefreshTokens.familyId eq familyId }) {
                    it[RefreshTokens.revoked] = true
                }
            }
            return RefreshResult.ReuseDetected
        }

        // Rotate: revoke current, mint + link a replacement in the same family.
        val newToken = mintAndStoreRefreshToken(userId, familyId, deviceInfo)
        val newHash = sha256(newToken)
        com.telefam.db.DatabaseFactory.dbQuery {
            RefreshTokens.update({ RefreshTokens.id eq row[RefreshTokens.id] }) {
                it[RefreshTokens.revoked] = true
                it[RefreshTokens.replacedByHash] = newHash
            }
        }

        return RefreshResult.Success(createAccessToken(userId), newToken)
    }

    suspend fun revokeAllForUser(userId: UUID) {
        com.telefam.db.DatabaseFactory.dbQuery {
            RefreshTokens.update({ RefreshTokens.userId eq userId }) {
                it[RefreshTokens.revoked] = true
            }
        }
    }

    // ---------- device / session management ----------

    /** One session = one refresh-token family. */
    data class SessionInfo(
        val familyId: UUID,
        val deviceInfo: String?,
        val createdAt: LocalDateTime,
        val lastRotatedAt: LocalDateTime,
        val current: Boolean
    )

    fun refreshTokenHash(token: String): String = sha256(token)

    /** Every live (non-revoked, unexpired) session of the user, newest first. */
    suspend fun listSessions(userId: UUID, currentRefreshToken: String?): List<SessionInfo> {
        val currentHash = currentRefreshToken?.let(::sha256)
        val now = LocalDateTime.now()
        val rows = com.telefam.db.DatabaseFactory.dbQuery {
            RefreshTokens.selectAll().where { (RefreshTokens.userId eq userId) and (RefreshTokens.revoked eq false) }
                .toList()
        }
        // Within each family only the newest (unreplaced) token is live; older rows in a
        // rotated family are revoked, so non-revoked rows are the current heads.
        val currentFamily = rows.firstOrNull { it[RefreshTokens.tokenHash] == currentHash }
            ?.get(RefreshTokens.familyId)
        return rows
            .filter { it[RefreshTokens.expiresAt].isAfter(now) }
            .groupBy { it[RefreshTokens.familyId] }
            .map { (family, tokens) ->
                val head = tokens.maxBy { it[RefreshTokens.createdAt] }
                SessionInfo(
                    familyId = family,
                    deviceInfo = head[RefreshTokens.deviceInfo],
                    createdAt = tokens.minOf { it[RefreshTokens.createdAt] },
                    lastRotatedAt = head[RefreshTokens.createdAt],
                    current = family == currentFamily
                )
            }
            .sortedByDescending { it.lastRotatedAt }
    }

    /** Log out one device: revoke its whole token family. */
    suspend fun revokeFamily(userId: UUID, familyId: UUID) {
        com.telefam.db.DatabaseFactory.dbQuery {
            RefreshTokens.update({ (RefreshTokens.userId eq userId) and (RefreshTokens.familyId eq familyId) }) {
                it[RefreshTokens.revoked] = true
            }
        }
    }

    /** Log out: revoke the token family that owns this refresh token. No-ops silently for unknown tokens. */
    suspend fun revokeByRefreshToken(userId: UUID, refreshToken: String) {
        val hash = sha256(refreshToken)
        com.telefam.db.DatabaseFactory.dbQuery {
            val family = RefreshTokens.selectAll()
                .where { (RefreshTokens.userId eq userId) and (RefreshTokens.tokenHash eq hash) }
                .firstOrNull()?.get(RefreshTokens.familyId)
            if (family != null) {
                RefreshTokens.update({ (RefreshTokens.userId eq userId) and (RefreshTokens.familyId eq family) }) {
                    it[RefreshTokens.revoked] = true
                }
            }
        }
    }

    /** "Log out all other devices": every family except the caller's current one. */
    suspend fun revokeOtherFamilies(userId: UUID, currentRefreshToken: String?) {
        val currentHash = currentRefreshToken?.let(::sha256)
        val currentFamily = com.telefam.db.DatabaseFactory.dbQuery {
            RefreshTokens.selectAll().where { (RefreshTokens.userId eq userId) and (RefreshTokens.tokenHash eq (currentHash ?: "")) }
                .firstOrNull()?.get(RefreshTokens.familyId)
        }
        com.telefam.db.DatabaseFactory.dbQuery {
            RefreshTokens.update({
                (RefreshTokens.userId eq userId) and (RefreshTokens.familyId neq (currentFamily ?: UUID(0L, 0L)))
            }) { it[RefreshTokens.revoked] = true }
        }
    }
}
