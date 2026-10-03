package com.telefam.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.telefam.config.AppConfig
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Access-token behaviour only — these need no database. */
class JwtServiceTest {
    private val jwt = JwtService()

    private fun signedAccess(
        subject: String,
        type: String = "access",
        secret: String = AppConfig.jwtAccessSecret,
        audience: String = AppConfig.jwtAudience,
        expiresInMs: Long = 60_000
    ): String = JWT.create()
        .withIssuer(AppConfig.jwtIssuer)
        .withAudience(audience)
        .withSubject(subject)
        .withClaim("type", type)
        .withExpiresAt(Date(System.currentTimeMillis() + expiresInMs))
        .sign(Algorithm.HMAC256(secret))

    @Test fun `access token round-trips to the same user id`() {
        val id = UUID.randomUUID()
        assertEquals(id, jwt.verifyAccessToken(jwt.createAccessToken(id)))
    }

    @Test fun `garbage and tampered tokens are rejected`() {
        val token = jwt.createAccessToken(UUID.randomUUID())
        assertNull(jwt.verifyAccessToken("not.a.jwt"))
        assertNull(jwt.verifyAccessToken(""))
        // flip a character in the signature segment
        val tampered = token.dropLast(2) + (if (token.last() == 'A') "BB" else "AA")
        assertNull(jwt.verifyAccessToken(tampered))
    }

    @Test fun `token signed with a different secret is rejected`() {
        val forged = signedAccess(UUID.randomUUID().toString(), secret = "some-other-secret-some-other-secret-1234")
        assertNull(jwt.verifyAccessToken(forged))
    }

    @Test fun `expired token is rejected`() {
        assertNull(jwt.verifyAccessToken(signedAccess(UUID.randomUUID().toString(), expiresInMs = -5_000)))
    }

    @Test fun `token with the wrong type claim cannot be used as an access token`() {
        assertNull(jwt.verifyAccessToken(signedAccess(UUID.randomUUID().toString(), type = "refresh")))
    }

    @Test fun `token for a different audience is rejected`() {
        assertNull(jwt.verifyAccessToken(signedAccess(UUID.randomUUID().toString(), audience = "someone-else")))
    }

    @Test fun `valid token still verifies when built by hand`() {
        val id = UUID.randomUUID()
        assertNotNull(jwt.verifyAccessToken(signedAccess(id.toString())))
    }
}
