package com.telefam.auth

import kotlinx.serialization.Serializable

@Serializable data class SignUpRequest(val email: String, val password: String)
@Serializable data class LoginRequest(val email: String, val password: String)
@Serializable data class OtpRequestDto(val email: String, val purpose: String)
@Serializable data class OtpVerifyRequestDto(val email: String, val purpose: String, val code: String)
@Serializable data class RefreshRequest(val refreshToken: String)
@Serializable data class GoogleSignInRequest(val idToken: String)
@Serializable data class AppleSignInRequest(val identityToken: String)
@Serializable data class ForgotPasswordRequest(val email: String)
@Serializable data class ResetPasswordRequest(val email: String, val code: String, val newPassword: String)

@Serializable data class ProfileSetupRequest(
    val fullName: String,
    val username: String,
    val countryDialCode: String,
    val phoneNumber: String,
    val gender: String,
    val dateOfBirth: String, // ISO yyyy-MM-dd
    val bio: String?,
    val profileImageMediaId: String?
)

@Serializable data class AuthTokens(val accessToken: String, val refreshToken: String)

/** Login succeeded on the password factor; the account requires the email OTP second factor. */
@Serializable data class TwoFactorRequiredResponse(val twoFactorRequired: Boolean, val secondsUntilNextResend: Long)

/** Generic, uninformative error body — never reveals *why* (locked vs wrong password vs no account). */
@Serializable data class ApiError(val message: String)

/** Only a plain seconds counter goes to the client — no attempt counts, no "locked" wording. */
@Serializable data class CooldownResponse(val secondsRemaining: Long)
