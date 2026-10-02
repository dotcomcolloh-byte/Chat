package com.telefam.email

import com.telefam.config.AppConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable

@Serializable
private data class ResendEmailRequest(
    val from: String,
    val to: List<String>,
    val subject: String,
    val html: String
)

/** Real Resend (resend.com) transactional email integration — no mock/sandbox mode. */
class ResendClient {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json() }
    }

    suspend fun sendOtpEmail(toEmail: String, code: String) {
        client.post("https://api.resend.com/emails") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${AppConfig.resendApiKey}")
            setBody(
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
    }

    suspend fun sendPasswordResetNotice(toEmail: String) {
        client.post("https://api.resend.com/emails") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${AppConfig.resendApiKey}")
            setBody(
                ResendEmailRequest(
                    from = AppConfig.resendFromAddress,
                    to = listOf(toEmail),
                    subject = "Your Telefam password was reset",
                    html = "<p>Your Telefam password was just reset. If this wasn't you, contact support immediately.</p>"
                )
            )
        }
    }
}
