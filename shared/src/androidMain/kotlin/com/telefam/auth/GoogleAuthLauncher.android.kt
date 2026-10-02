package com.telefam.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.telefam.shared.BuildConfig
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.SecureRandom
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Real Google Sign-In via the modern Credential Manager API (androidx.credentials +
 * Google Identity Services). Returns the actual signed Google ID token, which the
 * client sends to POST /api/auth/google — the backend re-verifies it independently
 * against Google's public keys (see GoogleAuthVerifier.kt), so this launcher never
 * has to be trusted on its own.
 *
 * Requires GOOGLE_CLIENT_ID_WEB (the *server/web* OAuth client ID, per Google's own
 * guidance for Android + backend verification) wired via BuildConfig — see
 * androidApp/build.gradle.kts, sourced from GOOGLE_CLIENT_ID_WEB in .env / gradle.properties.
 */
class GoogleAuthLauncher(private val context: Context) {

    private fun generateNonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    suspend fun signIn(): String = suspendCancellableCoroutine { cont ->
        val credentialManager = CredentialManager.create(context)
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(BuildConfig.GOOGLE_CLIENT_ID_WEB)
            .setNonce(generateNonce())
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        MainScope().launch {
            try {
                val result = credentialManager.getCredential(context, request)
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(result.credential.data)
                cont.resume(googleIdTokenCredential.idToken)
            } catch (e: GetCredentialException) {
                cont.resumeWithException(e)
            }
        }
    }
}
