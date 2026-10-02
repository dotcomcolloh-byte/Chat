package com.telefam.payments

import com.telefam.config.AppConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Real Paystack API client (secret key lives only here, server-side).
 * Used for: transaction initialize, server-side verify, refund, webhook signature check.
 */
class PaystackClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(CIO) { install(ContentNegotiation) { json(json) } }

    data class Initialized(val reference: String, val authorizationUrl: String)
    data class Verified(
        val paid: Boolean,
        val amountMinor: Long,
        val currency: String,
        val failureReason: String?,
        val reusableAuthorizationCode: String? = null,
        val reusable: Boolean = false
    )

    data class AuthorizationCharge(val accepted: Boolean, val failureReason: String?)
    data class RefundSubmission(
        val status: String,
        val refundId: String? = null,
        val failureReason: String? = null
    )
    data class RefundSnapshot(
        val refundId: String,
        val status: String,
        val amountMinor: Long,
        val currency: String
    )

    suspend fun initialize(email: String, amountMinor: Long, currency: String, reference: String, callbackUrl: String): Initialized {
        val res = client.post("${AppConfig.paystackBaseUrl}/transaction/initialize") {
            header(HttpHeaders.Authorization, "Bearer ${AppConfig.paystackSecretKey}")
            contentType(ContentType.Application.Json)
            setBody(mapOf(
                "email" to email,
                "amount" to amountMinor.toString(),
                "currency" to currency,
                "reference" to reference,
                "callback_url" to callbackUrl
            ))
        }
        if (!res.status.isSuccess()) throw IllegalStateException("Paystack initialize failed: ${res.status}")
        val data = json.parseToJsonElement(res.body<String>()).jsonObject["data"]!!.jsonObject
        return Initialized(
            reference = data["reference"]!!.jsonPrimitive.content,
            authorizationUrl = data["authorization_url"]!!.jsonPrimitive.content
        )
    }

    /** Authoritative status check — the ONLY thing that may mark a payment PAID. */
    suspend fun verify(reference: String): Verified {
        val res = client.get("${AppConfig.paystackBaseUrl}/transaction/verify/$reference") {
            header(HttpHeaders.Authorization, "Bearer ${AppConfig.paystackSecretKey}")
        }
        if (!res.status.isSuccess()) return Verified(false, 0, "", "verify_http_${res.status.value}")
        val root = json.parseToJsonElement(res.body<String>()).jsonObject
        val data = root["data"]!!.jsonObject
        val status = data["status"]!!.jsonPrimitive.content
        return Verified(
            paid = status == "success",
            amountMinor = data["amount"]!!.jsonPrimitive.content.toLong(),
            currency = data["currency"]!!.jsonPrimitive.content,
            failureReason = if (status == "success") null else (data["gateway_response"]?.jsonPrimitive?.content ?: status),
            reusableAuthorizationCode = data["authorization"]?.jsonObject?.get("authorization_code")?.jsonPrimitive?.content,
            reusable = data["authorization"]?.jsonObject?.get("reusable")?.jsonPrimitive?.content == "true"
        )
    }

    /** Charges only a provider-confirmed reusable authorization, never raw card data. */
    suspend fun chargeAuthorization(
        email: String,
        authorizationCode: String,
        amountMinor: Long,
        currency: String,
        reference: String
    ): AuthorizationCharge {
        val res = client.post("${AppConfig.paystackBaseUrl}/transaction/charge_authorization") {
            header(HttpHeaders.Authorization, "Bearer ${AppConfig.paystackSecretKey}")
            contentType(ContentType.Application.Json)
            setBody(mapOf(
                "email" to email,
                "authorization_code" to authorizationCode,
                "amount" to amountMinor.toString(),
                "currency" to currency,
                "reference" to reference
            ))
        }
        if (!res.status.isSuccess()) return AuthorizationCharge(false, "charge_http_${res.status.value}")
        val data = json.parseToJsonElement(res.body<String>()).jsonObject["data"]?.jsonObject
        val status = data?.get("status")?.jsonPrimitive?.content ?: "unknown"
        return AuthorizationCharge(status == "success", if (status == "success") null else status)
    }

    suspend fun requestFullRefund(reference: String): RefundSubmission {
        val body = mutableMapOf("transaction" to reference)
        val res = client.post("${AppConfig.paystackBaseUrl}/refund") {
            header(HttpHeaders.Authorization, "Bearer ${AppConfig.paystackSecretKey}")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!res.status.isSuccess()) {
            val code = res.status.value
            return RefundSubmission(
                status = if (code == 409 || code == 429 || code >= 500) "UNKNOWN" else "FAILED",
                failureReason = "paystack_refund_http_$code"
            )
        }
        val root = json.parseToJsonElement(res.body<String>()).jsonObject
        if (root["status"]?.jsonPrimitive?.content != "true") {
            return RefundSubmission("UNKNOWN", failureReason = "paystack_refund_response_unconfirmed")
        }
        val data = root["data"]?.jsonObject
            ?: return RefundSubmission("UNKNOWN", failureReason = "paystack_refund_response_missing_data")
        return RefundSubmission(
            status = data["status"]?.jsonPrimitive?.content?.uppercase() ?: "PENDING",
            refundId = data["id"]?.jsonPrimitive?.content,
        )
    }

    /** Paystack lookup is by the original transaction reference; callers never re-POST on a miss. */
    suspend fun findRefunds(reference: String): List<RefundSnapshot>? {
        val res = client.get("${AppConfig.paystackBaseUrl}/refund") {
            header(HttpHeaders.Authorization, "Bearer ${AppConfig.paystackSecretKey}")
            parameter("reference", reference)
            parameter("perPage", "50")
        }
        if (!res.status.isSuccess()) return null
        val root = json.parseToJsonElement(res.body<String>()).jsonObject
        if (root["status"]?.jsonPrimitive?.content != "true") return null
        val items = root["data"]?.jsonArray ?: return emptyList()
        return items.mapNotNull { item ->
            val obj = item.jsonObject
            val refundId = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            RefundSnapshot(
                refundId = refundId,
                status = obj["status"]?.jsonPrimitive?.content?.uppercase() ?: "UNKNOWN",
                amountMinor = obj["amount"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null,
                currency = obj["currency"]?.jsonPrimitive?.content ?: ""
            )
        }
    }

    /** Existing verification-payment refund path; kept separate from subscription reconciliation. */
    suspend fun refund(reference: String, amountMinor: Long? = null): Boolean {
        val body = mutableMapOf("transaction" to reference)
        if (amountMinor != null) body["amount"] = amountMinor.toString()
        val res = client.post("${AppConfig.paystackBaseUrl}/refund") {
            header(HttpHeaders.Authorization, "Bearer ${AppConfig.paystackSecretKey}")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!res.status.isSuccess()) return false
        val root = json.parseToJsonElement(res.body<String>()).jsonObject
        return root["status"]?.jsonPrimitive?.content == "true"
    }

    /** Paystack signs the raw request body with HMAC-SHA512 using the secret key. */
    fun isValidWebhookSignature(rawBody: ByteArray, signatureHeader: String?): Boolean {
        if (signatureHeader.isNullOrBlank()) return false
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretSpec(AppConfig.paystackSecretKey.toByteArray()))
        val expected = mac.doFinal(rawBody).joinToString("") { "%02x".format(it) }
        return expected.equals(signatureHeader, ignoreCase = true)
    }

    private fun SecretSpec(bytes: ByteArray) = SecretKeySpec(bytes, "HmacSHA512")
}
