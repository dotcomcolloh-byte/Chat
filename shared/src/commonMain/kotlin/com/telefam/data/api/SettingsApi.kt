package com.telefam.data.api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Wire DTOs mirroring the backend's com.telefam.settings package exactly. */

@Serializable
data class AccountDetailsDto(
    val userId: String,
    val email: String,
    val emailVerified: Boolean,
    val username: String? = null,
    val fullName: String? = null,
    val bio: String? = null,
    val phoneCountryCode: String? = null,
    val phoneNumber: String? = null,
    val gender: String? = null,
    val dateOfBirth: String? = null,
    val avatarUrl: String? = null,
    val website: String? = null,
    val locationName: String? = null,
    val accountStatus: String = "ACTIVE",
    val twoFactorEnabled: Boolean = false,
    val loginAlertsEnabled: Boolean = true,
    val usernameChangeCooldownDays: Int = 0,
    val joinedAt: String = ""
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
data class EditProfileResponse(val status: String, val usernameChangeCooldownDays: Int = 0)

@Serializable
data class UsernameCooldownBody(val daysRemaining: Long)

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
    val deviceInfo: String? = null,
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

@Serializable
data class TwoFactorRequiredResponse(val twoFactorRequired: Boolean, val secondsUntilNextResend: Long)

/** Client for /api/account, /api/app-settings, /api/support, /api/legal. */
class SettingsApi(private val client: HttpClient) {

    private val base get() = ApiConfig.baseUrl.trimEnd('/')

    suspend fun accountDetails(): AccountDetailsDto = client.get("$base/api/account").body()

    /**
     * Edit profile. Returns the new username cooldown on success.
     * Throws [UsernameCooldownException] on the 7-day username rule.
     */
    suspend fun editProfile(req: EditProfileRequest): EditProfileResponse {
        val res = client.put("$base/api/account/profile") {
            contentType(ContentType.Application.Json); setBody(req)
        }
        if (res.status == HttpStatusCode.Conflict) {
            val body = res.bodyAsText()
            if (body.contains("daysRemaining")) {
                throw UsernameCooldownException(
                    runCatching { kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                        .decodeFromString<UsernameCooldownBody>(body).daysRemaining }.getOrDefault(7)
                )
            }
            throw Exception("conflict")
        }
        if (!res.status.isSuccess()) throw Exception("edit failed: ${res.status}")
        return res.body()
    }

    suspend fun requestEmailChange(newEmail: String) =
        client.post("$base/api/account/email/change-request") {
            contentType(ContentType.Application.Json); setBody(EmailChangeRequest(newEmail))
        }

    suspend fun confirmEmailChange(newEmail: String, code: String) =
        client.post("$base/api/account/email/confirm") {
            contentType(ContentType.Application.Json); setBody(EmailChangeConfirm(newEmail, code))
        }

    suspend fun updatePhone(countryDialCode: String, phoneNumber: String) =
        client.put("$base/api/account/phone") {
            contentType(ContentType.Application.Json); setBody(PhoneUpdateRequest(countryDialCode, phoneNumber))
        }

    suspend fun changePassword(current: String, new: String) =
        client.post("$base/api/account/password") {
            contentType(ContentType.Application.Json); setBody(PasswordChangeRequest(current, new))
        }

    suspend fun securitySettings(): SecuritySettingsDto = client.get("$base/api/account/security").body()

    suspend fun updateSecuritySettings(update: SecuritySettingsUpdate): SecuritySettingsDto =
        client.put("$base/api/account/security") {
            contentType(ContentType.Application.Json); setBody(update)
        }.body()

    suspend fun listSessions(currentRefreshToken: String?): List<SessionDto> =
        client.get("$base/api/account/sessions") {
            if (currentRefreshToken != null) header("X-Refresh-Token", currentRefreshToken)
        }.body()

    suspend fun revokeSession(sessionId: String) = client.delete("$base/api/account/sessions/$sessionId")

    suspend fun revokeOtherSessions(currentRefreshToken: String?) =
        client.delete("$base/api/account/sessions/others") {
            if (currentRefreshToken != null) header("X-Refresh-Token", currentRefreshToken)
        }

    suspend fun deactivate() = client.post("$base/api/account/deactivate")

    suspend fun deleteAccount() = client.delete("$base/api/account")

    suspend fun appSettings(): AppSettingsDto = client.get("$base/api/app-settings").body()

    suspend fun updateAppSettings(dto: AppSettingsDto): AppSettingsDto =
        client.put("$base/api/app-settings") {
            contentType(ContentType.Application.Json); setBody(dto)
        }.body()

    suspend fun submitProblemReport(req: ProblemReportRequest): HttpResponse =
        client.post("$base/api/support/reports") {
            contentType(ContentType.Application.Json); setBody(req)
        }

    suspend fun myProblemReports(): List<ProblemReportDto> = client.get("$base/api/support/reports").body()

    /** Legal documents are served as plain text/markdown; public endpoint. */
    suspend fun legalDocument(doc: String): String = client.get("$base/api/legal/$doc").bodyAsText()

    /** Multipart upload to the media pipeline; returns the media id for use as profileImageMediaId. */
    suspend fun uploadProfileImage(bytes: ByteArray, mimeType: String): String {
        val res = client.submitFormWithBinaryData(
            url = "$base/api/media/upload",
            formData = formData {
                append("file", bytes, Headers.build {
                    append(HttpHeaders.ContentType, mimeType)
                    append(HttpHeaders.ContentDisposition, "filename=\"profile.jpg\"")
                })
            }
        )
        if (!res.status.isSuccess()) throw Exception("upload failed: ${res.status}")
        return kotlinx.serialization.json.Json.parseToJsonElement(res.bodyAsText())
            .jsonObject.getValue("mediaId").jsonPrimitive.content
    }
}

class UsernameCooldownException(val daysRemaining: Long) : Exception("username_cooldown")
