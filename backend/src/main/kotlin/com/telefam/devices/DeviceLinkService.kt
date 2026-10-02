package com.telefam.devices

import com.telefam.auth.JwtService
import com.telefam.db.DatabaseFactory.dbQuery
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.datetime
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDateTime
import java.util.*

/**
 * Linked-devices (companion device) pairing, modelled on WhatsApp Web / Signal:
 *
 *  1. The already-signed-in device (the OWNER) requests a link challenge. The
 *     challenge is a one-time (id, secret) pair rendered as a QR code. Any
 *     previous challenge belonging to the owner is INVALIDATED first, so a
 *     refreshed QR code permanently kills the old one — a screenshot of an
 *     expired code can never be replayed.
 *  2. Challenges expire 30 seconds after creation (QR_TTL_SECONDS). The client
 *     auto-refreshes by simply requesting a new challenge.
 *  3. The new device (the SCANNER) scans the QR and submits the secret. The
 *     challenge moves PENDING -> SCANNED and an accept deadline starts:
 *     the owner must explicitly tap "Accept" within ACCEPT_WINDOW_SECONDS
 *     (5 minutes) or the challenge is dead.
 *  4. On accept, a brand-new refresh-token family is minted for the scanner
 *     (same rotation/reuse-detection as a normal login). The tokens are handed
 *     to the scanner exactly once via the result endpoint, then the row is
 *     CONSUMED.
 *
 * Secrets are stored as SHA-256 hashes only — the raw secret never touches the
 * database, matching how refresh tokens are stored.
 */
object DeviceLinkChallenges : UUIDTable("device_link_challenges") {
    val ownerId = uuid("owner_id").index()
    val secretHash = varchar("secret_hash", 64)
    /** PENDING, SCANNED, ACCEPTED, CONSUMED, EXPIRED, INVALIDATED */
    val status = varchar("status", 16).default("PENDING").index()
    val scannerLabel = varchar("scanner_label", 120).nullable()
    val scannedBy = uuid("scanned_by").nullable()
    val expiresAt = datetime("expires_at")
    val acceptDeadlineAt = datetime("accept_deadline_at").nullable()
    val resultAccessToken = text("result_access_token").nullable()
    val resultRefreshToken = text("result_refresh_token").nullable()
    val createdAt = datetime("created_at")
}

class DeviceLinkService(private val jwtService: JwtService) {

    companion object {
        const val QR_TTL_SECONDS = 30L
        const val ACCEPT_WINDOW_SECONDS = 300L // 5 minutes to press Accept after a scan
        private val random = SecureRandom()
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    data class Challenge(val id: UUID, val secret: String, val expiresAt: LocalDateTime) {
        /** Payload encoded into the QR code. */
        val qrPayload: String get() = "telefam://link-device?c=$id&k=$secret"
    }

    enum class Status { PENDING, SCANNED, ACCEPTED, CONSUMED, EXPIRED, INVALIDATED }

    data class ChallengeStatus(
        val status: Status,
        val secondsRemaining: Long,
        val scannerLabel: String?,
        val acceptSecondsRemaining: Long
    )

    /** Creates a fresh challenge for the owner; every live challenge of theirs is invalidated first. */
    suspend fun createChallenge(ownerId: UUID): Challenge {
        val id = UUID.randomUUID()
        val secret = buildString {
            val bytes = ByteArray(24).also(random::nextBytes)
            bytes.forEach { append("%02x".format(it)) }
        }
        val now = LocalDateTime.now()
        dbQuery {
            // Invalidate any still-live challenge — only one QR may be usable at a time.
            DeviceLinkChallenges.update({
                (DeviceLinkChallenges.ownerId eq ownerId) and
                    (DeviceLinkChallenges.status inList listOf("PENDING", "SCANNED"))
            }) { it[status] = "INVALIDATED" }
            DeviceLinkChallenges.insert {
                it[DeviceLinkChallenges.id] = id
                it[DeviceLinkChallenges.ownerId] = ownerId
                it[secretHash] = sha256(secret)
                it[status] = "PENDING"
                it[expiresAt] = now.plusSeconds(QR_TTL_SECONDS)
                it[createdAt] = now
            }
        }
        return Challenge(id, secret, now.plusSeconds(QR_TTL_SECONDS))
    }

    private fun sweepExpiry(row: ResultRow): Status {
        val stored = Status.valueOf(row[DeviceLinkChallenges.status])
        val now = LocalDateTime.now()
        return when (stored) {
            Status.PENDING, Status.SCANNED ->
                if (row[DeviceLinkChallenges.expiresAt].isBefore(now)) {
                    // A scanned-but-unaccepted challenge also dies when its accept window passes.
                    val deadline = row[DeviceLinkChallenges.acceptDeadlineAt]
                    if (stored == Status.SCANNED && deadline != null && deadline.isAfter(now)) stored
                    else Status.EXPIRED
                } else stored
            else -> stored
        }
    }

    /** Owner-side view: poll this to drive the countdown and the Accept prompt. */
    suspend fun status(ownerId: UUID, challengeId: UUID): ChallengeStatus? {
        val row = dbQuery {
            DeviceLinkChallenges.selectAll().where {
                (DeviceLinkChallenges.id eq challengeId) and (DeviceLinkChallenges.ownerId eq ownerId)
            }.singleOrNull()
        } ?: return null
        val now = LocalDateTime.now()
        val status = sweepExpiry(row)
        if (status != Status.valueOf(row[DeviceLinkChallenges.status])) {
            dbQuery {
                DeviceLinkChallenges.update({ DeviceLinkChallenges.id eq challengeId }) { it[DeviceLinkChallenges.status] = status.name }
            }
        }
        val secondsRemaining = maxOf(0L, java.time.Duration.between(now, row[DeviceLinkChallenges.expiresAt]).seconds)
        val acceptRemaining = row[DeviceLinkChallenges.acceptDeadlineAt]
            ?.let { maxOf(0L, java.time.Duration.between(now, it).seconds) } ?: 0L
        return ChallengeStatus(status, secondsRemaining, row[DeviceLinkChallenges.scannerLabel], acceptRemaining)
    }

    sealed class ScanResult {
        object Ok : ScanResult()
        object Invalid : ScanResult()   // unknown id, wrong secret, expired, already used
    }

    /** Scanner-side: present the scanned challenge. Rate-limited at the route layer. */
    suspend fun markScanned(challengeId: UUID, secret: String, deviceLabel: String): ScanResult {
        val now = LocalDateTime.now()
        val hash = sha256(secret)
        return dbQuery {
            val row = DeviceLinkChallenges.selectAll().where { DeviceLinkChallenges.id eq challengeId }.singleOrNull()
                ?: return@dbQuery ScanResult.Invalid
            if (row[DeviceLinkChallenges.secretHash] != hash) return@dbQuery ScanResult.Invalid
            if (Status.valueOf(row[DeviceLinkChallenges.status]) != Status.PENDING) return@dbQuery ScanResult.Invalid
            if (!row[DeviceLinkChallenges.expiresAt].isAfter(now)) return@dbQuery ScanResult.Invalid
            DeviceLinkChallenges.update({ DeviceLinkChallenges.id eq challengeId }) {
                it[status] = "SCANNED"
                it[scannerLabel] = deviceLabel.take(120)
                it[acceptDeadlineAt] = now.plusSeconds(ACCEPT_WINDOW_SECONDS)
            }
            ScanResult.Ok
        }
    }

    sealed class AcceptResult {
        object Ok : AcceptResult()
        object Invalid : AcceptResult() // not SCANNED anymore, or the 5-minute window passed
    }

    /** Owner-side: approve the scanned device. Mints the scanner's session tokens. */
    suspend fun accept(ownerId: UUID, challengeId: UUID): AcceptResult {
        val now = LocalDateTime.now()
        val row = dbQuery {
            DeviceLinkChallenges.selectAll().where {
                (DeviceLinkChallenges.id eq challengeId) and (DeviceLinkChallenges.ownerId eq ownerId)
            }.singleOrNull()
        } ?: return AcceptResult.Invalid
        if (Status.valueOf(row[DeviceLinkChallenges.status]) != Status.SCANNED) return AcceptResult.Invalid
        val deadline = row[DeviceLinkChallenges.acceptDeadlineAt]
        if (deadline == null || !deadline.isAfter(now)) {
            dbQuery { DeviceLinkChallenges.update({ DeviceLinkChallenges.id eq challengeId }) { it[status] = "EXPIRED" } }
            return AcceptResult.Invalid
        }
        val label = row[DeviceLinkChallenges.scannerLabel] ?: "Linked device"
        val access = jwtService.createAccessToken(ownerId)
        val refresh = jwtService.issueNewRefreshTokenFamily(ownerId, label)
        dbQuery {
            DeviceLinkChallenges.update({ DeviceLinkChallenges.id eq challengeId }) {
                it[status] = "ACCEPTED"
                it[resultAccessToken] = access
                it[resultRefreshToken] = refresh
            }
        }
        return AcceptResult.Ok
    }

    /** Owner-side: reject the scanned device. */
    suspend fun decline(ownerId: UUID, challengeId: UUID): Boolean {
        val updated = dbQuery {
            DeviceLinkChallenges.update({
                (DeviceLinkChallenges.id eq challengeId) and
                    (DeviceLinkChallenges.ownerId eq ownerId) and
                    (DeviceLinkChallenges.status inList listOf("PENDING", "SCANNED"))
            }) { it[status] = "INVALIDATED" }
        }
        return updated > 0
    }

    sealed class ResultFetch {
        data class Tokens(val accessToken: String, val refreshToken: String) : ResultFetch()
        data class Pending(val status: Status) : ResultFetch()
        object Invalid : ResultFetch()
    }

    /**
     * Scanner-side: poll for the outcome. Requires the QR secret, so only the
     * device that physically scanned the code can collect the tokens. Tokens are
     * handed over exactly once; afterwards the challenge is CONSUMED.
     */
    suspend fun fetchResult(challengeId: UUID, secret: String): ResultFetch {
        val hash = sha256(secret)
        val row = dbQuery {
            DeviceLinkChallenges.selectAll().where { DeviceLinkChallenges.id eq challengeId }.singleOrNull()
        } ?: return ResultFetch.Invalid
        if (row[DeviceLinkChallenges.secretHash] != hash) return ResultFetch.Invalid
        return when (sweepExpiry(row)) {
            Status.ACCEPTED -> {
                val access = row[DeviceLinkChallenges.resultAccessToken]
                val refresh = row[DeviceLinkChallenges.resultRefreshToken]
                if (access == null || refresh == null) return ResultFetch.Invalid
                dbQuery {
                    DeviceLinkChallenges.update({ DeviceLinkChallenges.id eq challengeId }) {
                        it[status] = "CONSUMED"
                        it[resultAccessToken] = null
                        it[resultRefreshToken] = null
                    }
                }
                ResultFetch.Tokens(access, refresh)
            }
            Status.PENDING -> ResultFetch.Pending(Status.PENDING)
            Status.SCANNED -> ResultFetch.Pending(Status.SCANNED)
            else -> ResultFetch.Invalid
        }
    }
}
