package com.telefam.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import com.telefam.db.DatabaseFactory.dbQuery
import com.telefam.db.Users
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.util.*

class AuthService(private val otpService: OtpService, private val jwtService: JwtService, private val loginAttempts: LoginAttemptService) {

    suspend fun findUserByEmail(email: String) = dbQuery {
        Users.selectAll().where { Users.email eq email.lowercase() }.singleOrNull()
    }

    suspend fun createUserWithPassword(email: String, plainPassword: String): UUID {
        val now = LocalDateTime.now()
        val hash = BCrypt.withDefaults().hashToString(12, plainPassword.toCharArray())
        val id = UUID.randomUUID()
        dbQuery {
            Users.insert {
                it[Users.id] = id
                it[Users.email] = email.lowercase()
                it[Users.passwordHash] = hash
                it[Users.emailVerified] = false
                it[Users.profileComplete] = false
                it[Users.createdAt] = now
                it[Users.updatedAt] = now
            }
        }
        return id
    }

    fun verifyPassword(plain: String, hash: String) = BCrypt.verifyer().verify(plain.toCharArray(), hash).verified

    suspend fun markEmailVerified(userId: UUID) {
        dbQuery { Users.update({ Users.id eq userId }) { it[Users.emailVerified] = true } }
    }

    suspend fun setPassword(userId: UUID, newPassword: String) {
        val hash = BCrypt.withDefaults().hashToString(12, newPassword.toCharArray())
        dbQuery { Users.update({ Users.id eq userId }) { it[Users.passwordHash] = hash; it[Users.updatedAt] = LocalDateTime.now() } }
        jwtService.revokeAllForUser(userId) // password reset kills all existing sessions
        com.telefam.risk.RiskService.record(userId, com.telefam.risk.RiskService.PASSWORD_RESET)
    }

    suspend fun upsertGoogleUser(identity: GoogleIdentity): UUID {
        val existing = dbQuery { Users.selectAll().where { Users.googleSub eq identity.sub }.singleOrNull() }
        if (existing != null) return existing[Users.id].value

        val existingByEmail = dbQuery { Users.selectAll().where { Users.email eq identity.email.lowercase() }.singleOrNull() }
        if (existingByEmail != null) {
            val id = existingByEmail[Users.id].value
            dbQuery { Users.update({ Users.id eq id }) { it[Users.googleSub] = identity.sub; it[Users.emailVerified] = true } }
            return id
        }

        val id = UUID.randomUUID()
        val now = LocalDateTime.now()
        dbQuery {
            Users.insert {
                it[Users.id] = id
                it[Users.email] = identity.email.lowercase()
                it[Users.googleSub] = identity.sub
                it[Users.emailVerified] = true
                it[Users.fullName] = identity.name
                it[Users.profileComplete] = false
                it[Users.createdAt] = now
                it[Users.updatedAt] = now
            }
        }
        return id
    }

    suspend fun upsertAppleUser(identity: AppleIdentity): UUID {
        val existing = dbQuery { Users.selectAll().where { Users.appleSub eq identity.sub }.singleOrNull() }
        if (existing != null) return existing[Users.id].value

        val id = UUID.randomUUID()
        val now = LocalDateTime.now()
        dbQuery {
            Users.insert {
                it[Users.id] = id
                it[Users.email] = identity.email?.lowercase() ?: "$id@privaterelay.telefam"
                it[Users.appleSub] = identity.sub
                it[Users.emailVerified] = identity.email != null
                it[Users.profileComplete] = false
                it[Users.createdAt] = now
                it[Users.updatedAt] = now
            }
        }
        return id
    }

    suspend fun completeProfile(userId: UUID, req: ProfileSetupRequest, mediaId: UUID?) {
        dbQuery {
            Users.update({ Users.id eq userId }) {
                it[Users.fullName] = req.fullName
                it[Users.username] = req.username.lowercase()
                it[Users.phoneCountryCode] = req.countryDialCode
                it[Users.phoneNumber] = req.phoneNumber
                it[Users.gender] = req.gender
                it[Users.dateOfBirth] = java.time.LocalDate.parse(req.dateOfBirth).atStartOfDay()
                it[Users.bio] = req.bio
                it[Users.profileImageMediaId] = mediaId
                it[Users.profileComplete] = true
                it[Users.updatedAt] = LocalDateTime.now()
            }
        }
    }

    suspend fun isUsernameTaken(username: String) = dbQuery {
        Users.selectAll().where { Users.username eq username.lowercase() }.any()
    }
}
