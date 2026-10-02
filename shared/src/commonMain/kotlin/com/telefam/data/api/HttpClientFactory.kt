package com.telefam.data.api

import com.telefam.data.AuthSession
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable

@Serializable private data class RefreshRequestBody(val refreshToken: String)
@Serializable private data class RefreshResponseBody(val accessToken: String, val refreshToken: String)

/**
 * The one HttpClient every API class in the app should use. Installs Ktor's real
 * Auth "bearer" provider wired to AuthSession:
 *  - It attaches the current access token to every request to our own backend.
 *  - On a 401, it calls POST /api/auth/refresh itself (via Ktor's dedicated
 *    refresh-request path, so this doesn't recurse), stores the returned tokens
 *    in AuthSession (which persists them to Keystore/Keychain — see TokenStorage),
 *    and transparently retries the original request. The caller never sees the
 *    401 unless the refresh token itself has been revoked or reused (see
 *    JwtService's reuse-detection on the backend), in which case AuthSession is
 *    cleared so the app can fall back to asking the person to log in again.
 *  - This is the piece that was missing: previously a stale access token just
 *    failed every call until the person logged back in manually.
 */
/** Process-wide holder so non-UI entry points (FCM service, receivers) reuse the same
 *  authenticated client instead of building a second connection pool. */
object HttpClientHolder {
    val client: HttpClient by lazy { createTelefamHttpClient() }
}

fun createTelefamHttpClient(): HttpClient = HttpClient {
    install(ContentNegotiation) { json() }

    install(Auth) {
        bearer {
            loadTokens {
                val access = AuthSession.accessToken ?: return@loadTokens null
                BearerTokens(access, AuthSession.refreshToken ?: "")
            }

            refreshTokens {
                val currentRefresh = AuthSession.refreshToken ?: return@refreshTokens null
                try {
                    // `client` here is Ktor's unauthenticated copy provided specifically for this
                    // block, so this call does not itself trigger another 401->refresh cycle.
                    val response = client.post("${ApiConfig.baseUrl}/api/auth/refresh") {
                        markAsRefreshTokenRequest()
                        contentType(ContentType.Application.Json)
                        setBody(RefreshRequestBody(currentRefresh))
                    }
                    if (response.status.value in 200..299) {
                        val body: RefreshResponseBody = response.body()
                        AuthSession.accessToken = body.accessToken
                        AuthSession.refreshToken = body.refreshToken
                        BearerTokens(body.accessToken, body.refreshToken)
                    } else {
                        AuthSession.clear()
                        null
                    }
                } catch (e: Exception) {
                    null // offline during the refresh attempt — session stays intact for next try
                }
            }

            // Only ever attach our own token to our own backend, never to any other host.
            sendWithoutRequest { request -> request.url.toString().startsWith(ApiConfig.baseUrl) }
        }
    }
}
