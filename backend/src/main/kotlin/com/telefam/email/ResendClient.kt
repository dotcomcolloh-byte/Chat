package com.telefam.email

import com.telefam.config.AppConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
private data class ResendEmailRequest(
    val from: String,
    val to: List<String>,
    val subject: String,
    val html: String
)

/**
 * Real Resend (resend.com) transactional email integration — no mock/sandbox mode.
 * `open` only so tests can substitute a recorder; production always uses this implementation.
 */
open class ResendClient {
    private val log = LoggerFactory.getLogger(ResendClient::class.java)
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json() }
    }

    private suspend fun send(request: ResendEmailRequest) {
        val response = client.post("https://api.resend.com/emails") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${AppConfig.resendApiKey}")
            setBody(request)
        }
        // Never log the recipient or the code — only that delivery failed and why Resend says so.
        if (!response.status.isSuccess()) {
            log.error("Resend rejected email (HTTP ${response.status.value}): ${response.bodyAsText().take(300)}")
        }
    }

    open suspend fun sendOtpEmail(toEmail: String, code: String) {
        send(
            ResendEmailRequest(
                from = AppConfig.resendFromAddress,
                to = listOf(toEmail),
                subject = "Your Telefam verification code",
                html = """
                    <div style="font-family:sans-serif;padding:24px">
                      <h2 style="color:#D32323">Telefam</h2>
                      <p>Your verification code is:</p>
                      <p style="font-size:32px;font-weight:bold;letter-spacing:6px">$code</p>
                      <p style="color:#777">This code expires in ${AppConfig.otpTtlMinutes} minutes. If you didn't request this, ignore this email.</p>
                    </div>
                """.trimIndent()
            )
        )
    }

    open suspend fun sendPasswordResetNotice(toEmail: String) {
        send(
            ResendEmailRequest(
                from = AppConfig.resendFromAddress,
                to = listOf(toEmail),
                subject = "Your Telefam password was reset",
                html = "<p>Your Telefam password was just reset. If this wasn't you, contact support immediately.</p>"
            )
        )
    }
}
