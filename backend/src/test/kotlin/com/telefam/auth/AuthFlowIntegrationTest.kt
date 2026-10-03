package com.telefam.auth

import com.telefam.db.DatabaseFactory
import com.telefam.db.Users
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end tests of the real auth routes against a real Postgres. Email delivery is the only
 * thing replaced (by [CapturingResend]). Skipped automatically when no database is reachable.
 */
class AuthFlowIntegrationTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val password = "Passw0rd123"

    @BeforeTest
    fun requireDatabase() {
        assumeTrue(TestDb.available, "Postgres not reachable — see build.gradle.kts for how to start one")
        TestDb.ensureInit()
    }

    // ---------------------------------------------------------------- helpers

    private fun newEmail() = "user-${UUID.randomUUID()}@example.com"

    private fun withApp(block: suspend ApplicationTestBuilder.(CapturingResend) -> Unit) = testApplication {
        val resend = CapturingResend()
        application { authTestModule(resend) }
        block(resend)
    }

    private suspend fun ApplicationTestBuilder.post(
        path: String,
        body: String,
        extra: Map<String, String> = emptyMap()
    ): HttpResponse = client.post(path) {
        contentType(ContentType.Application.Json)
        extra.forEach { (k, v) -> header(k, v) }
        setBody(body)
    }

    private suspend fun HttpResponse.obj(): JsonObject = json.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

    private data class Tokens(val access: String, val refresh: String)

    private suspend fun HttpResponse.tokens(): Tokens {
        assertEquals(HttpStatusCode.OK, status)
        val o = obj()
        return Tokens(o.str("accessToken"), o.str("refreshToken"))
    }

    private suspend fun ApplicationTestBuilder.signUp(email: String, pw: String = password): HttpResponse =
        post("/api/auth/signup", """{"email":"$email","password":"$pw"}""")

    private suspend fun ApplicationTestBuilder.login(email: String, pw: String): HttpResponse =
        post("/api/auth/login", """{"email":"$email","password":"$pw"}""")

    private suspend fun ApplicationTestBuilder.refresh(token: String): HttpResponse =
        post("/api/auth/refresh", """{"refreshToken":"$token"}""")

    private suspend fun ApplicationTestBuilder.verifyOtp(email: String, purpose: String, code: String): HttpResponse =
        post("/api/auth/otp/verify", """{"email":"$email","purpose":"$purpose","code":"$code"}""")

    private suspend fun ApplicationTestBuilder.whoami(token: String?): HttpResponse =
        client.get("/api/test/whoami") { if (token != null) header(HttpHeaders.Authorization, "Bearer $token") }

    /** Sign up and return a working session (password login works as soon as the account exists). */
    private suspend fun ApplicationTestBuilder.registered(email: String): Tokens {
        assertEquals(HttpStatusCode.Created, signUp(email).status)
        return login(email, password).tokens()
    }

    // ---------------------------------------------------------------- sign up

    @Test
    fun `signup creates an account and emails a code`() = withApp { resend ->
        val email = newEmail()
        val res = signUp(email)
        assertEquals(HttpStatusCode.Created, res.status)
        UUID.fromString(res.obj().str("userId")) // a real UUID comes back
        assertTrue(resend.codes[email]!!.matches(Regex("^[0-9]{6}$")))
    }

    @Test
    fun `signup rejects bad input and duplicates without revealing which`() = withApp {
        val email = newEmail()
        assertEquals(HttpStatusCode.BadRequest, signUp("not-an-email").status)
        assertEquals(HttpStatusCode.BadRequest, signUp(email, "short1").status)
        assertEquals(HttpStatusCode.BadRequest, signUp(email, "onlyletters").status)
        assertEquals(HttpStatusCode.Created, signUp(email).status)
        val dup = signUp(email)
        assertEquals(HttpStatusCode.BadRequest, dup.status)
        assertEquals("Unable to create account", dup.obj().str("message"))
    }

    @Test
    fun `emailed signup code verifies once and returns working tokens`() = withApp { resend ->
        val email = newEmail()
        val userId = signUp(email).obj().str("userId")
        val code = resend.codes[email]!!
        val wrong = if (code == "000000") "111111" else "000000"

        assertEquals(HttpStatusCode.BadRequest, verifyOtp(email, "SIGNUP_VERIFY", wrong).status)

        val tokens = verifyOtp(email, "SIGNUP_VERIFY", code).tokens()
        val me = whoami(tokens.access)
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals(userId, me.bodyAsText())

        // single use
        assertEquals(HttpStatusCode.BadRequest, verifyOtp(email, "SIGNUP_VERIFY", code).status)
    }

    @Test
    fun `otp verify cannot be used to sign in with a password-reset code`() = withApp { resend ->
        val email = newEmail()
        signUp(email)
        post("/api/auth/password/forgot", """{"email":"$email"}""")
        val code = resend.codes[email]!!
        assertEquals(HttpStatusCode.BadRequest, verifyOtp(email, "PASSWORD_RESET", code).status)
        assertEquals(HttpStatusCode.BadRequest, verifyOtp(email, "PAYOUT_SECURITY", code).status)
    }

    // ---------------------------------------------------------------- login

    @Test
    fun `login works with the right password and gives usable tokens`() = withApp {
        val email = newEmail()
        val tokens = registered(email)
        assertEquals(HttpStatusCode.OK, whoami(tokens.access).status)
    }

    @Test
    fun `wrong password and unknown email both give the same 401`() = withApp {
        val email = newEmail()
        signUp(email)
        val wrongPw = login(email, "Wrong-pass1")
        val unknown = login(newEmail(), password)
        assertEquals(HttpStatusCode.Unauthorized, wrongPw.status)
        assertEquals(HttpStatusCode.Unauthorized, unknown.status)
        assertEquals(wrongPw.obj().str("message"), unknown.obj().str("message"))
    }

    @Test
    fun `three wrong passwords lock the account even for the right password`() = withApp {
        val email = newEmail()
        signUp(email)
        assertEquals(HttpStatusCode.Unauthorized, login(email, "Wrong-pass1").status)
        assertEquals(HttpStatusCode.Unauthorized, login(email, "Wrong-pass2").status)
        val third = login(email, "Wrong-pass3")
        assertEquals(HttpStatusCode.TooManyRequests, third.status)
        assertTrue(third.obj()["secondsRemaining"]!!.jsonPrimitive.long > 0)
        assertEquals(HttpStatusCode.TooManyRequests, login(email, password).status)
    }

    @Test
    fun `account with two-factor on gets a challenge instead of tokens`() = withApp { resend ->
        val email = newEmail()
        signUp(email)
        DatabaseFactory.dbQuery { Users.update({ Users.email eq email }) { it[twoFactorEnabled] = true } }

        val res = login(email, password)
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.obj()
        assertEquals(true, body["twoFactorRequired"]!!.jsonPrimitive.boolean)
        assertNull(body["accessToken"])

        val tokens = verifyOtp(email, "LOGIN_2FA", resend.codes[email]!!).tokens()
        assertEquals(HttpStatusCode.OK, whoami(tokens.access).status)
    }

    // ---------------------------------------------------------------- sessions

    @Test
    fun `protected routes need a valid bearer token`() = withApp {
        assertEquals(HttpStatusCode.Unauthorized, whoami(null).status)
        assertEquals(HttpStatusCode.Unauthorized, whoami("garbage").status)
        // a refresh token must not be accepted as an access token
        val tokens = registered(newEmail())
        assertEquals(HttpStatusCode.Unauthorized, whoami(tokens.refresh).status)
    }

    @Test
    fun `refresh rotates the token and replaying the old one kills the whole session`() = withApp {
        val first = registered(newEmail())

        val second = refresh(first.refresh).tokens()
        assertNotEquals(first.refresh, second.refresh)
        assertEquals(HttpStatusCode.OK, whoami(second.access).status)

        // replay of an already-rotated token = theft signal
        assertEquals(HttpStatusCode.Unauthorized, refresh(first.refresh).status)
        // ...which also revokes the legitimate descendant
        assertEquals(HttpStatusCode.Unauthorized, refresh(second.refresh).status)
    }

    @Test
    fun `refresh rejects junk`() = withApp {
        assertEquals(HttpStatusCode.Unauthorized, refresh("not-a-token").status)
    }

    @Test
    fun `logout revokes the session's refresh token`() = withApp {
        val tokens = registered(newEmail())
        val out = post(
            "/api/auth/logout", "{}",
            mapOf(HttpHeaders.Authorization to "Bearer ${tokens.access}", "X-Refresh-Token" to tokens.refresh)
        )
        assertEquals(HttpStatusCode.OK, out.status)
        assertEquals(HttpStatusCode.Unauthorized, refresh(tokens.refresh).status)
    }

    @Test
    fun `logout without a bearer token is refused`() = withApp {
        assertEquals(HttpStatusCode.Unauthorized, post("/api/auth/logout", "{}").status)
    }

    // ---------------------------------------------------------------- password reset

    @Test
    fun `password reset with the emailed code changes the password and ends old sessions`() = withApp { resend ->
        val email = newEmail()
        val old = registered(email)

        assertEquals(HttpStatusCode.OK, post("/api/auth/password/forgot", """{"email":"$email"}""").status)
        val code = resend.codes[email]!!
        val newPw = "NewPassw0rd9"

        // bad new password is refused before the code is even checked
        assertEquals(
            HttpStatusCode.BadRequest,
            post("/api/auth/password/reset", """{"email":"$email","code":"$code","newPassword":"weak"}""").status
        )
        // wrong code
        val wrong = if (code == "000000") "111111" else "000000"
        assertEquals(
            HttpStatusCode.BadRequest,
            post("/api/auth/password/reset", """{"email":"$email","code":"$wrong","newPassword":"$newPw"}""").status
        )
        // right code
        assertEquals(
            HttpStatusCode.OK,
            post("/api/auth/password/reset", """{"email":"$email","code":"$code","newPassword":"$newPw"}""").status
        )

        assertEquals(HttpStatusCode.Unauthorized, login(email, password).status)
        assertNotNull(login(email, newPw).tokens())
        assertEquals(HttpStatusCode.Unauthorized, refresh(old.refresh).status) // old session is dead
    }

    @Test
    fun `forgot password answers 200 for unknown emails so accounts cannot be enumerated`() = withApp { resend ->
        val unknown = newEmail()
        assertEquals(HttpStatusCode.OK, post("/api/auth/password/forgot", """{"email":"$unknown"}""").status)
        assertNull(resend.codes[unknown])
    }

    // ---------------------------------------------------------------- social sign-in

    @Test
    fun `invalid Google and Apple tokens are 401, not 500`() = withApp {
        assertEquals(HttpStatusCode.Unauthorized, post("/api/auth/google", """{"idToken":"garbage"}""").status)
        assertEquals(HttpStatusCode.Unauthorized, post("/api/auth/apple", """{"identityToken":"garbage"}""").status)
    }

    // ---------------------------------------------------------------- abuse protection

    @Test
    fun `per-IP rate limit kicks in on the auth endpoints`() = withApp {
        val statuses = (1..25).map { login("not-an-email", "x").status }
        assertEquals(HttpStatusCode.BadRequest, statuses.first())
        assertTrue(statuses.any { it == HttpStatusCode.TooManyRequests }, "expected a 429 within 25 rapid requests")
    }
}
