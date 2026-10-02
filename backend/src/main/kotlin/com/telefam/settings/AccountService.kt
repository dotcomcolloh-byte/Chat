package com.telefam.settings

import com.telefam.auth.JwtService
import com.telefam.db.AppSettings
import com.telefam.db.DatabaseFactory.dbQuery
import com.telefam.db.PendingEmailChanges
import com.telefam.db.ProblemReports
import com.telefam.db.RlsContext
import com.telefam.db.Users
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.util.UUID

// ---------- DTOs ----------

@Serializable
data class AccountDetailsDto(
    val userId: String,
    val email: String,
    val emailVerified: Boolean,
    val username: String?,
    val fullName: String?,
    val bio: String?,
    val phoneCountryCode: String?,
    val phoneNumber: String?,
    val gender: String?,
    val dateOfBirth: String?,
    val avatarUrl: String?,
    val website: String? = null,
    val locationName: String? = null,
    val accountStatus: String,
    val twoFactorEnabled: Boolean,
    val loginAlertsEnabled: Boolean,
    val usernameChangeCooldownDays: Int, // days until the username may change again (0 = now)
    val joinedAt: String
)

@Serializable
data class EditProfileRequest(
    val fullName: String? = null,
    val username: String? = null,
    val bio: String? = null,
    val profileImageMediaId: String? = null,
    /** Empty string clears the website; null leaves it unchanged. */
    val website: String? = null,
    /** Empty string clears the location; null leaves it unchanged. */
    val locationName: String? = null,
    /** When true, removes the current profile photo. */
    val removeAvatar: Boolean = false
)

@Serializable
data class EditProfileResponse(
    val status: String,
    val usernameChangeCooldownDays: Int = 0
)

class UsernameCooldownException(val daysRemaining: Long) : Exception()

@Serializable
data class EmailChangeRequest(val newEmail: String)

@Serializable
data class EmailChangeConfirm(val newEmail: String, val code: String)

@Serializable
data class PhoneUpdateRequest(val countryDialCode: String, val phoneNumber: String)

@Serializable
data class PasswordChangeRequest(val currentPassword: String, val newPassword: String)

@Serializable
data class SecuritySettingsDto(val twoFactorEnabled: Boolean, val loginAlertsEnabled: Boolean)

@Serializable
data class SecuritySettingsUpdate(val twoFactorEnabled: Boolean? = null, val loginAlertsEnabled: Boolean? = null)

@Serializable
data class SessionDto(
    val sessionId: String,
    val deviceInfo: String?,
    val createdAt: String,
    val lastActiveAt: String,
    val current: Boolean
)

@Serializable
data class AppSettingsDto(
    val contentLanguage: String = "en",
    val videoQuality: String = "AUTO",
    val autoplay: Boolean = true,
    val dataSaver: Boolean = false,
    val sensitiveContent: Boolean = true,
    val suggestedContent: Boolean = true,
    val textSize: String = "MEDIUM",
    val captionsEnabled: Boolean = false,
    val reducedMotion: Boolean = false,
    val highContrast: Boolean = false,
    val dailyLimitMinutes: Int = 0,
    val breakReminderMinutes: Int = 0,
    val quietModeEnabled: Boolean = false
)

@Serializable
data class ProblemReportRequest(
    val category: String,
    val description: String,
    val appVersion: String = "",
    val attachmentMediaId: String? = null
)

@Serializable
data class ProblemReportDto(
    val reportId: String,
    val category: String,
    val description: String,
    val status: String,
    val appVersion: String,
    val createdAt: String
)

val PROBLEM_CATEGORIES = setOf(
    "ACCOUNT", "LOGIN", "PROFILE", "POSTS", "FEED", "MESSAGES", "CONTACTS", "SETTINGS", "SUPPORT", "OTHER"
)

/**
 * Everything behind Settings & privacy that touches the account itself:
 * profile editing (with the 7-day username cooldown), verified email changes,
 * phone updates, password rotation, 2FA / login alerts, device sessions,
 * deactivation/deletion, synced app preferences, and problem reports.
 */
class AccountService(private val jwtService: JwtService) {

    companion object { const val USERNAME_COOLDOWN_DAYS = 7L }

    // ---------- account overview ----------

    suspend fun accountDetails(userId: UUID): AccountDetailsDto? {
        val row = dbQuery { Users.selectAll().where { Users.id eq userId }.singleOrNull() } ?: return null
        val usernameChangedAt = row[Users.usernameChangedAt]
        val cooldown = if (usernameChangedAt == null) 0 else {
            val elapsed = java.time.Duration.between(usernameChangedAt, LocalDateTime.now()).toDays()
            (USERNAME_COOLDOWN_DAYS - elapsed).coerceAtLeast(0).toInt()
        }
        val avatar = if (row[Users.profileImageMediaId] != null) "/api/feeds/avatar/$userId" else null
        return AccountDetailsDto(
            userId = userId.toString(),
            email = row[Users.email],
            emailVerified = row[Users.emailVerified],
            username = row[Users.username],
            fullName = row[Users.fullName],
            bio = row[Users.bio],
            phoneCountryCode = row[Users.phoneCountryCode],
            phoneNumber = row[Users.phoneNumber],
            gender = row[Users.gender],
            dateOfBirth = row[Users.dateOfBirth]?.toLocalDate()?.toString(),
            avatarUrl = avatar,
            website = row[Users.website],
            locationName = row[Users.locationName],
            accountStatus = row[Users.accountStatus],
            twoFactorEnabled = row[Users.twoFactorEnabled],
            loginAlertsEnabled = row[Users.loginAlertsEnabled],
            usernameChangeCooldownDays = cooldown,
            joinedAt = row[Users.createdAt].toString()
        )
    }

    // ---------- edit profile (7-day username cooldown) ----------

    suspend fun editProfile(userId: UUID, req: EditProfileRequest): Int {
        var cooldown = 0
        dbQuery {
            val row = Users.selectAll().where { Users.id eq userId }.single()
            val newUsername = req.username?.lowercase()?.takeIf { it.isNotBlank() }
            if (newUsername != null && newUsername != row[Users.username]) {
                val last = row[Users.usernameChangedAt]
                if (last != null) {
                    val elapsed = java.time.Duration.between(last, LocalDateTime.now()).toDays()
                    if (elapsed < USERNAME_COOLDOWN_DAYS) throw UsernameCooldownException(USERNAME_COOLDOWN_DAYS - elapsed)
                }
                val taken = Users.selectAll().where { (Users.username eq newUsername) and (Users.id neq userId) }.any()
                require(!taken) { "username_taken" }
            }
            Users.update({ Users.id eq userId }) { st ->
                req.fullName?.let { st[Users.fullName] = it.take(100) }
                req.bio?.let { st[Users.bio] = it.take(150) }
                req.profileImageMediaId?.let { st[Users.profileImageMediaId] = UUID.fromString(it) }
                if (req.removeAvatar) st[Users.profileImageMediaId] = null
                req.website?.let { st[Users.website] = it.trim().takeIf { w -> w.isNotBlank() } }
                req.locationName?.let { st[Users.locationName] = it.trim().takeIf { l -> l.isNotBlank() } }
                if (newUsername != null && newUsername != row[Users.username]) {
                    st[Users.username] = newUsername
                    st[Users.usernameChangedAt] = LocalDateTime.now()
                }
                st[Users.updatedAt] = LocalDateTime.now()
            }
            val changed = Users.selectAll().where { Users.id eq userId }.single()[Users.usernameChangedAt]
            cooldown = if (changed == null) 0 else
                (USERNAME_COOLDOWN_DAYS - java.time.Duration.between(changed, LocalDateTime.now()).toDays()).coerceAtLeast(0).toInt()
        }
        return cooldown
    }

    // ---------- verified email change ----------

    suspend fun requestEmailChange(userId: UUID, newEmail: String) {
        require(dbQuery { Users.selectAll().where { Users.email eq newEmail.lowercase() }.none() }) { "email_taken" }
        RlsContext.asUser(userId) {
            PendingEmailChanges.deleteWhere { PendingEmailChanges.userId eq userId }
            PendingEmailChanges.insert {
                it[PendingEmailChanges.userId] = userId
                it[PendingEmailChanges.newEmail] = newEmail.lowercase()
                it[createdAt] = LocalDateTime.now()
            }
        }
    }

    /** Applies the pending change after the caller has verified the OTP sent to the NEW address. */
    suspend fun confirmEmailChange(userId: UUID, newEmail: String) {
        val pending = RlsContext.asUser(userId) {
            PendingEmailChanges.selectAll().where { PendingEmailChanges.userId eq userId }.singleOrNull()
        } ?: throw IllegalArgumentException("no_pending_change")
        require(pending[PendingEmailChanges.newEmail] == newEmail.lowercase()) { "email_mismatch" }
        require(dbQuery { Users.selectAll().where { Users.email eq newEmail.lowercase() }.none() }) { "email_taken" }
        dbQuery {
            Users.update({ Users.id eq userId }) {
                it[Users.email] = newEmail.lowercase()
                it[Users.emailVerified] = true
                it[Users.updatedAt] = LocalDateTime.now()
            }
        }
        RlsContext.asUser(userId) { PendingEmailChanges.deleteWhere { PendingEmailChanges.userId eq userId } }
        jwtService.revokeAllForUser(userId) // sign in again with the new address
    }

    // ---------- phone ----------

    suspend fun updatePhone(userId: UUID, countryDialCode: String, phoneNumber: String) = dbQuery {
        Users.update({ Users.id eq userId }) {
            it[Users.phoneCountryCode] = countryDialCode.take(8)
            it[Users.phoneNumber] = phoneNumber.take(20)
            it[Users.updatedAt] = LocalDateTime.now()
        }
    }

    // ---------- security ----------

    suspend fun securitySettings(userId: UUID): SecuritySettingsDto {
        val row = dbQuery { Users.selectAll().where { Users.id eq userId }.single() }
        return SecuritySettingsDto(row[Users.twoFactorEnabled], row[Users.loginAlertsEnabled])
    }

    suspend fun updateSecuritySettings(userId: UUID, update: SecuritySettingsUpdate): SecuritySettingsDto {
        dbQuery {
            Users.update({ Users.id eq userId }) { st ->
                update.twoFactorEnabled?.let { st[Users.twoFactorEnabled] = it }
                update.loginAlertsEnabled?.let { st[Users.loginAlertsEnabled] = it }
                st[Users.updatedAt] = LocalDateTime.now()
            }
        }
        // Enabling or disabling 2FA must not leave stolen sessions alive.
        if (update.twoFactorEnabled != null) jwtService.revokeOtherFamilies(userId, null)
        return securitySettings(userId)
    }

    suspend fun changePassword(userId: UUID, currentPassword: String, newPassword: String, verify: (String, String) -> Boolean): Boolean {
        val row = dbQuery { Users.selectAll().where { Users.id eq userId }.single() }
        val hash = row[Users.passwordHash] ?: return false // social-only account: no current password to check
        if (!verify(currentPassword, hash)) return false
        dbQuery {
            Users.update({ Users.id eq userId }) {
                it[Users.passwordHash] = at.favre.lib.crypto.bcrypt.BCrypt.withDefaults().hashToString(12, newPassword.toCharArray())
                it[Users.updatedAt] = LocalDateTime.now()
            }
        }
        jwtService.revokeAllForUser(userId)
        return true
    }

    // ---------- sessions / devices ----------

    suspend fun listSessions(userId: UUID, currentRefreshToken: String?): List<SessionDto> =
        jwtService.listSessions(userId, currentRefreshToken).map {
            SessionDto(it.familyId.toString(), it.deviceInfo, it.createdAt.toString(), it.lastRotatedAt.toString(), it.current)
        }

    suspend fun revokeSession(userId: UUID, familyId: UUID) = jwtService.revokeFamily(userId, familyId)

    suspend fun revokeOtherSessions(userId: UUID, currentRefreshToken: String?) =
        jwtService.revokeOtherFamilies(userId, currentRefreshToken)

    // ---------- deactivate / delete ----------

    suspend fun setAccountStatus(userId: UUID, status: String) {
        require(status in setOf("ACTIVE", "DEACTIVATED", "DELETED"))
        dbQuery {
            Users.update({ Users.id eq userId }) {
                it[Users.accountStatus] = status
                it[Users.updatedAt] = LocalDateTime.now()
            }
        }
        jwtService.revokeAllForUser(userId)
    }

    // ---------- synced app settings ----------

    suspend fun appSettings(userId: UUID): AppSettingsDto = RlsContext.asUser(userId) {
        val row = AppSettings.selectAll().where { AppSettings.userId eq userId }.singleOrNull()
        if (row == null) {
            AppSettings.insert {
                it[AppSettings.userId] = userId
                it[updatedAt] = LocalDateTime.now()
            }
            AppSettingsDto()
        } else {
            AppSettingsDto(
                contentLanguage = row[AppSettings.contentLanguage],
                videoQuality = row[AppSettings.videoQuality],
                autoplay = row[AppSettings.autoplay],
                dataSaver = row[AppSettings.dataSaver],
                sensitiveContent = row[AppSettings.sensitiveContent],
                suggestedContent = row[AppSettings.suggestedContent],
                textSize = row[AppSettings.textSize],
                captionsEnabled = row[AppSettings.captionsEnabled],
                reducedMotion = row[AppSettings.reducedMotion],
                highContrast = row[AppSettings.highContrast],
                dailyLimitMinutes = row[AppSettings.dailyLimitMinutes],
                breakReminderMinutes = row[AppSettings.breakReminderMinutes],
                quietModeEnabled = row[AppSettings.quietModeEnabled]
            )
        }
    }

    suspend fun updateAppSettings(userId: UUID, dto: AppSettingsDto): AppSettingsDto {
        RlsContext.asUser(userId) {
            val exists = AppSettings.selectAll().where { AppSettings.userId eq userId }.any()
            if (!exists) {
                AppSettings.insert {
                    it[AppSettings.userId] = userId
                    it[updatedAt] = LocalDateTime.now()
                }
            }
            AppSettings.update({ AppSettings.userId eq userId }) { st ->
                st[contentLanguage] = dto.contentLanguage.take(10)
                st[videoQuality] = dto.videoQuality.uppercase().let { q -> if (q in setOf("AUTO", "LOW", "MEDIUM", "HIGH")) q else "AUTO" }
                st[autoplay] = dto.autoplay
                st[dataSaver] = dto.dataSaver
                st[sensitiveContent] = dto.sensitiveContent
                st[suggestedContent] = dto.suggestedContent
                st[textSize] = dto.textSize.uppercase().let { s -> if (s in setOf("SMALL", "MEDIUM", "LARGE", "XLARGE")) s else "MEDIUM" }
                st[captionsEnabled] = dto.captionsEnabled
                st[reducedMotion] = dto.reducedMotion
                st[highContrast] = dto.highContrast
                st[dailyLimitMinutes] = dto.dailyLimitMinutes.coerceIn(0, 24 * 60)
                st[breakReminderMinutes] = dto.breakReminderMinutes.coerceIn(0, 12 * 60)
                st[quietModeEnabled] = dto.quietModeEnabled
                st[updatedAt] = LocalDateTime.now()
            }
        }
        return appSettings(userId)
    }

    // ---------- problem reports / support ----------

    suspend fun submitProblemReport(userId: UUID, req: ProblemReportRequest): ProblemReportDto {
        val category = req.category.uppercase().take(30).let { if (it in PROBLEM_CATEGORIES) it else "OTHER" }
        val id = UUID.randomUUID()
        RlsContext.asUser(userId) {
            ProblemReports.insert {
                it[ProblemReports.id] = id
                it[ProblemReports.userId] = userId
                it[ProblemReports.category] = category
                it[ProblemReports.description] = req.description.trim().take(2000)
                it[ProblemReports.appVersion] = req.appVersion.take(40)
                it[ProblemReports.attachmentMediaId] = req.attachmentMediaId?.let { m -> runCatching { UUID.fromString(m) }.getOrNull() }
                it[ProblemReports.status] = "OPEN"
                it[ProblemReports.createdAt] = LocalDateTime.now()
            }
        }
        return ProblemReportDto(id.toString(), category, req.description.trim().take(2000), "OPEN", req.appVersion.take(40), LocalDateTime.now().toString())
    }

    suspend fun myProblemReports(userId: UUID): List<ProblemReportDto> = RlsContext.asUser(userId) {
        ProblemReports.selectAll().where { ProblemReports.userId eq userId }
            .orderBy(ProblemReports.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC)
            .map {
                ProblemReportDto(
                    it[ProblemReports.id].value.toString(), it[ProblemReports.category],
                    it[ProblemReports.description], it[ProblemReports.status],
                    it[ProblemReports.appVersion], it[ProblemReports.createdAt].toString()
                )
            }
    }
}
