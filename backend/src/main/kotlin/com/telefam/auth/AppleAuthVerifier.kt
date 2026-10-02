package com.telefam.auth

import com.auth0.jwk.UrlJwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.telefam.config.AppConfig
import java.net.URL
import java.security.interfaces.RSAPublicKey

data class AppleIdentity(val sub: String, val email: String?)

/**
 * Verifies a real Apple identity token against Apple's live JWKS (appleid.apple.com/auth/keys).
 * Keys are fetched per verification (auth0 UrlJwkProvider caches internally per instance),
 * matched by `kid`, and the RS256 signature + iss/aud/exp are all checked — no shortcuts.
 */
class AppleAuthVerifier {
    private val jwkProvider = UrlJwkProvider(URL(AppConfig.appleKeysUrl))

    fun verify(identityToken: String): AppleIdentity? = try {
        val decodedJwt = JWT.decode(identityToken)
        val jwk = jwkProvider.get(decodedJwt.keyId)
        val publicKey = jwk.publicKey as RSAPublicKey
        val algorithm = Algorithm.RSA256(publicKey, null)

        val verifier = JWT.require(algorithm)
            .withIssuer(AppConfig.appleIssuer)
            .withAudience(AppConfig.appleClientId)
            .build()

        val verified = verifier.verify(identityToken)
        AppleIdentity(
            sub = verified.subject,
            email = verified.getClaim("email").asString()
        )
    } catch (e: Exception) {
        null
    }
}
