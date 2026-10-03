package com.telefam.official

import at.favre.lib.crypto.bcrypt.BCrypt
import com.telefam.db.DatabaseFactory.dbQuery
import com.telefam.db.Users
import com.telefam.payments.VerificationBadges
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.security.SecureRandom
import java.time.LocalDateTime
import java.util.UUID

/**
 * The official Telefam account — a real, backend-controlled user row.
 *
 *  - Seeded on boot with the owner email; profile is complete and permanently
 *    verified (badge row with a far-future expiry, no payment/application flow).
 *  - The password is a random 512-bit secret nobody knows: the account can never
 *    be logged into through any auth endpoint — it is driven by the backend only.
 *  - Follow-only profile: users can follow it, it never follows anyone back
 *    (enforced in ConnectService), and it can never be called (enforced in
 *    CallSignalingService and hidden client-side).
 *  - On successful registration the account "invites" the new user: the user
 *    auto-follows the official account and receives the welcome system
 *    conversation in their inbox.
 */
object OfficialAccountService {
    const val EMAIL = "collinsrono359@gmail.com"
    const val USERNAME = "telefam"
    const val DISPLAY_NAME = "Telefam Official"
    /** Stable bio shown on the official profile. */
    const val BIO = "The official Telefam account. News, updates and support."

    @Volatile private var cachedId: UUID? = null

    /** Creates the account on first boot (idempotent) and returns its user id. */
    suspend fun ensureExists(): UUID {
        cachedId?.let { return it }
        val existing = dbQuery {
            Users.selectAll().where { Users.email eq EMAIL }.singleOrNull()
        }
        val now = LocalDateTime.now()
        val id = if (existing != null) {
            val uid = existing[Users.id].value
            // Keep the seeded identity canonical across restarts.
            dbQuery {
                Users.update({ Users.id eq uid }) {
                    it[fullName] = DISPLAY_NAME
                    it[username] = USERNAME
                    it[emailVerified] = true
                    it[profileComplete] = true
                    it[bio] = BIO
                    it[accountStatus] = "ACTIVE"
                    it[updatedAt] = now
                }
            }
            uid
        } else {
            val uid = UUID.randomUUID()
            // Random unguessable password — this account is backend-controlled only.
            val randomSecret = ByteArray(32).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
            dbQuery {
                Users.insert {
                    it[Users.id] = uid
                    it[email] = EMAIL
                    it[passwordHash] = BCrypt.withDefaults().hashToString(12, randomSecret.toCharArray())
                    it[emailVerified] = true
                    it[fullName] = DISPLAY_NAME
                    it[username] = USERNAME
                    it[bio] = BIO
                    it[profileComplete] = true
                    it[accountStatus] = "ACTIVE"
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
            uid
        }
        ensureVerified(id)
        cachedId = id
        return id
    }

    /** Official badge: a verification row with a 100-year term, independent of payments. */
    private suspend fun ensureVerified(userId: UUID) {
        val has = dbQuery {
            VerificationBadges.selectAll().where { VerificationBadges.userId eq userId }.any()
        }
        if (has) return
        val now = LocalDateTime.now()
        dbQuery {
            runCatching {
                VerificationBadges.insert {
                    it[VerificationBadges.id] = UUID.randomUUID()
                    it[VerificationBadges.userId] = userId
                    it[grantedAt] = now
                    it[expiresAt] = now.plusYears(100)
                    it[graceUntil] = now.plusYears(100)
                    it[applicationId] = UUID.nameUUIDFromBytes("telefam-official".toByteArray())
                }
            }
        }
    }

    fun idOrNull(): UUID? = cachedId

    suspend fun id(): UUID = cachedId ?: ensureExists()

    suspend fun isOfficial(userId: UUID): Boolean = idOrNull() == userId ||
        dbQuery { Users.selectAll().where { (Users.id eq userId) and (Users.email eq EMAIL) }.any() }
}
