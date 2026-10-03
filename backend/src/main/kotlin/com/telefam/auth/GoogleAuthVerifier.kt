package com.telefam.auth

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.telefam.config.AppConfig
import java.util.Collections

data class GoogleIdentity(val sub: String, val email: String, val emailVerified: Boolean, val name: String?)

/**
 * Verifies a real Google ID token (sent from the Android app after Google Sign-In)
 * against Google's public keys. Accepts either the Android or Web OAuth client ID
 * as valid audience (Android apps using the default GoogleSignInClient present the
 * server/web client ID as audience per Google's own docs).
 */
class GoogleAuthVerifier {
    private val verifier = GoogleIdTokenVerifier.Builder(NetHttpTransport(), GsonFactory())
        .setAudience(Collections.singletonList(AppConfig.googleClientIdWeb))
        .build()

    fun verify(idTokenString: String): GoogleIdentity? = try {
        val idToken: GoogleIdToken? = verifier.verify(idTokenString)
        idToken?.payload?.let { payload ->
            GoogleIdentity(
                sub = payload.subject,
                email = payload.email,
                emailVerified = payload.emailVerified ?: false,
                name = payload["name"] as? String
            )
        }
    } catch (e: Exception) {
        null // malformed / unverifiable token -> caller answers 401, never 500
    }
}
