package com.telefam.data.api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable

/** Base URL comes from build config / env, never hardcoded — see ApiConfig.kt (expect/actual per platform). */
expect object ApiConfig {
    val baseUrl: String
    /** GIPHY API key, build-config driven per platform (gradle.properties / .xcconfig). Empty = feature hidden. */
    val giphyApiKey: String
}

@Serializable data class SignUpBody(val email: String, val password: String)
@Serializable data class LoginBody(val email: String, val password: String)
@Serializable data class OtpSendBody(val email: String, val purpose: String)
@Serializable data class OtpVerifyBody(val email: String, val purpose: String, val code: String)
@Serializable data class GoogleBody(val idToken: String)
@Serializable data class AppleBody(val identityToken: String)
@Serializable data class RefreshBody(val refreshToken: String)
@Serializable data class AuthTokens(val accessToken: String, val refreshToken: String)
@Serializable data class CooldownResponse(val secondsRemaining: Long)
@Serializable data class ForgotPasswordBody(val email: String)
@Serializable data class ResetPasswordBody(val email: String, val code: String, val newPassword: String)
/** Returned by POST /api/auth/login (HTTP 200) when the account needs the email-OTP second factor. */
@Serializable data class TwoFactorRequired(val twoFactorRequired: Boolean, val secondsUntilNextResend: Long = 60)

class AuthApi(private val client: HttpClient) {

    constructor() : this(createTelefamHttpClient())

    suspend fun signUp(email: String, password: String) =
        client.post("${ApiConfig.baseUrl}/api/auth/signup") {
            contentType(ContentType.Application.Json); setBody(SignUpBody(email, password))
        }

    suspend fun login(email: String, password: String) =
        client.post("${ApiConfig.baseUrl}/api/auth/login") {
            contentType(ContentType.Application.Json); setBody(LoginBody(email, password))
        }

    suspend fun sendOtp(email: String, purpose: String) =
        client.post("${ApiConfig.baseUrl}/api/auth/otp/send") {
            contentType(ContentType.Application.Json); setBody(OtpSendBody(email, purpose))
        }

    suspend fun verifyOtp(email: String, purpose: String, code: String) =
        client.post("${ApiConfig.baseUrl}/api/auth/otp/verify") {
            contentType(ContentType.Application.Json); setBody(OtpVerifyBody(email, purpose, code))
        }

    suspend fun googleSignIn(idToken: String) =
        client.post("${ApiConfig.baseUrl}/api/auth/google") {
            contentType(ContentType.Application.Json); setBody(GoogleBody(idToken))
        }

    suspend fun appleSignIn(identityToken: String) =
        client.post("${ApiConfig.baseUrl}/api/auth/apple") {
            contentType(ContentType.Application.Json); setBody(AppleBody(identityToken))
        }

    suspend fun refresh(refreshToken: String) =
        client.post("${ApiConfig.baseUrl}/api/auth/refresh") {
            contentType(ContentType.Application.Json); setBody(RefreshBody(refreshToken))
        }

    /** Starts a password reset. Always 200 server-side, whether or not the email has an account. */
    suspend fun forgotPassword(email: String) =
        client.post("${ApiConfig.baseUrl}/api/auth/password/forgot") {
            contentType(ContentType.Application.Json); setBody(ForgotPasswordBody(email))
        }

    /** Completes a reset with the emailed code. Does NOT sign the user in — they log in with the new password. */
    suspend fun resetPassword(email: String, code: String, newPassword: String) =
        client.post("${ApiConfig.baseUrl}/api/auth/password/reset") {
            contentType(ContentType.Application.Json); setBody(ResetPasswordBody(email, code, newPassword))
        }
}
