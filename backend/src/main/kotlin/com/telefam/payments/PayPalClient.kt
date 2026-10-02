package com.telefam.payments

import com.telefam.config.AppConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Base64

/**
 * Real PayPal REST v2 client. Server-side only.
 * Used for: order create, order capture, order status read, refund, webhook signature verify.
 */
class PayPalClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(CIO) { install(ContentNegotiation) { json(json) } }

    private var cachedToken: Pair<String, Long>? = null // token to expiry epoch-seconds

    private suspend fun accessToken(): String {
        val now = System.currentTimeMillis() / 1000
        cachedToken?.let { if (it.second > now + 60) return it.first }
        val basic = Base64.getEncoder().encodeToString("${AppConfig.paypalClientId}:${AppConfig.paypalClientSecret}".toByteArray())
        val res = client.submitForm(
            url = "${AppConfig.paypalBaseUrl}/v1/oauth2/token",
            formParameters = Parameters.build { append("grant_type", "client_credentials") }
        ) { header(HttpHeaders.Authorization, "Basic $basic") }
        if (!res.status.isSuccess()) throw IllegalStateException("PayPal auth failed: ${res.status}")
        val obj = json.parseToJsonElement(res.body<String>()).jsonObject
        val token = obj["access_token"]!!.jsonPrimitive.content
        val ttl = obj["expires_in"]!!.jsonPrimitive.content.toLong()
        cachedToken = token to (now + ttl)
        return token
    }

    data class OrderCreated(val orderId: String, val approveUrl: String)
    data class OrderState(val status: String, val paid: Boolean, val amountMinor: Long, val currency: String, val captureId: String?)
    data class RefundSubmission(val status: String, val refundId: String? = null, val failureReason: String? = null)
    data class RefundSnapshot(val refundId: String, val status: String, val amountMinor: Long, val currency: String)
    data class CaptureRefundState(
        val status: String, val amountMinor: Long, val currency: String, val totalRefundedMinor: Long
    )

    suspend fun createOrder(amountMinor: Long, currency: String, returnUrl: String, cancelUrl: String): OrderCreated {
        val res = client.post("${AppConfig.paypalBaseUrl}/v2/checkout/orders") {
            header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
            contentType(ContentType.Application.Json)
            setBody(mapOf(
                "intent" to "CAPTURE",
                "purchase_units" to listOf(mapOf(
                    "amount" to mapOf(
                        "currency_code" to currency,
                        "value" to "%.2f".format(amountMinor / 100.0)
                    ),
                    "description" to "Telefam Verified"
                )),
                "application_context" to mapOf("return_url" to returnUrl, "cancel_url" to cancelUrl)
            ))
        }
        if (!res.status.isSuccess()) throw IllegalStateException("PayPal create order failed: ${res.status}")
        val obj = json.parseToJsonElement(res.body<String>()).jsonObject
        val links = obj["links"]!!.jsonArray
        val approve = links.first { it.jsonObject["rel"]!!.jsonPrimitive.content == "approve" }
            .jsonObject["href"]!!.jsonPrimitive.content
        return OrderCreated(obj["id"]!!.jsonPrimitive.content, approve)
    }

    /** Captures an approved order and returns the authoritative post-capture state. */
    suspend fun captureOrder(orderId: String): OrderState {
        val res = client.post("${AppConfig.paypalBaseUrl}/v2/checkout/orders/$orderId/capture") {
            header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        if (!res.status.isSuccess()) throw IllegalStateException("PayPal capture failed: ${res.status}")
        return parseOrder(res.body<String>())
    }

    /** Authoritative read — used to re-verify even after webhooks arrive. */
    suspend fun getOrder(orderId: String): OrderState {
        val res = client.get("${AppConfig.paypalBaseUrl}/v2/checkout/orders/$orderId") {
            header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
        }
        if (!res.status.isSuccess()) throw IllegalStateException("PayPal get order failed: ${res.status}")
        return parseOrder(res.body<String>())
    }

    private fun parseOrder(body: String): OrderState {
        val obj = json.parseToJsonElement(body).jsonObject
        val status = obj["status"]!!.jsonPrimitive.content
        val unit = obj["purchase_units"]!!.jsonArray.first().jsonObject
        val amount = unit["amount"]!!.jsonObject
        val amountMinor = minorUnits(amount["value"]!!.jsonPrimitive.content)
            ?: throw IllegalStateException("PayPal order amount is not a valid minor-unit value")
        val captureId = unit["payments"]?.jsonObject
            ?.get("captures")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("id")?.jsonPrimitive?.content
        return OrderState(
            status = status,
            paid = status == "COMPLETED",
            amountMinor = amountMinor,
            currency = amount["currency_code"]!!.jsonPrimitive.content,
            captureId = captureId
        )
    }

    /** Full refunds are idempotent for the stable PayPal-Request-Id. */
    suspend fun requestFullRefund(captureId: String, requestId: String): RefundSubmission {
        val res = client.post("${AppConfig.paypalBaseUrl}/v2/payments/captures/$captureId/refund") {
            header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
            header("PayPal-Request-Id", requestId)
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        if (!res.status.isSuccess()) {
            val code = res.status.value
            return RefundSubmission(
                status = if (code == 409 || code == 429 || code >= 500) "UNKNOWN" else "FAILED",
                failureReason = "paypal_refund_http_$code"
            )
        }
        val obj = json.parseToJsonElement(res.body<String>()).jsonObject
        return RefundSubmission(
            status = obj["status"]?.jsonPrimitive?.content?.uppercase() ?: "UNKNOWN",
            refundId = obj["id"]?.jsonPrimitive?.content,
            failureReason = if (obj["id"] == null) "paypal_refund_id_missing" else null
        )
    }

    suspend fun getRefund(refundId: String): RefundSnapshot? {
        val res = client.get("${AppConfig.paypalBaseUrl}/v2/payments/refunds/$refundId") {
            header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
        }
        if (!res.status.isSuccess()) return null
        val obj = json.parseToJsonElement(res.body<String>()).jsonObject
        val amount = obj["amount"]?.jsonObject ?: return null
        return RefundSnapshot(
            refundId = obj["id"]?.jsonPrimitive?.content ?: refundId,
            status = obj["status"]?.jsonPrimitive?.content?.uppercase() ?: "UNKNOWN",
            amountMinor = amount["value"]?.jsonPrimitive?.content?.let(::minorUnits) ?: return null,
            currency = amount["currency_code"]?.jsonPrimitive?.content ?: ""
        )
    }

    /** Fallback for an ambiguous POST whose response never exposed a refund ID. */
    suspend fun getCaptureRefundState(captureId: String): CaptureRefundState? {
        val res = client.get("${AppConfig.paypalBaseUrl}/v2/payments/captures/$captureId") {
            header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
        }
        if (!res.status.isSuccess()) return null
        val obj = json.parseToJsonElement(res.body<String>()).jsonObject
        val amount = obj["amount"]?.jsonObject ?: return null
        val breakdown = obj["seller_receivable_breakdown"]?.jsonObject
        val refunded = breakdown?.get("total_refunded_amount")?.jsonObject
        return CaptureRefundState(
            status = obj["status"]?.jsonPrimitive?.content?.uppercase() ?: "UNKNOWN",
            amountMinor = amount["value"]?.jsonPrimitive?.content?.let(::minorUnits) ?: return null,
            currency = amount["currency_code"]?.jsonPrimitive?.content ?: "",
            totalRefundedMinor = refunded?.get("value")?.jsonPrimitive?.content?.let(::minorUnits) ?: 0L
        )
    }

    private fun minorUnits(value: String): Long? = runCatching {
        BigDecimal(value).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
    }.getOrNull()

    /** Existing verification-payment refund path; subscription refunds use the reconciled API above. */
    suspend fun refundCapture(captureId: String): Boolean {
        val res = client.post("${AppConfig.paypalBaseUrl}/v2/payments/captures/$captureId/refund") {
            header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        if (!res.status.isSuccess()) return false
        val status = json.parseToJsonElement(res.body<String>()).jsonObject["status"]?.jsonPrimitive?.content
        return status == "COMPLETED" || status == "PENDING"
    }

    /** Asks PayPal itself whether a webhook delivery is genuine (verify-webhook-signature). */
    suspend fun isValidWebhook(headers: Map<String, String>, rawBody: String): Boolean {
        val res = client.post("${AppConfig.paypalBaseUrl}/v1/notifications/verify-webhook-signature") {
            header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
            contentType(ContentType.Application.Json)
            setBody(mapOf(
                "auth_algo" to (headers["paypal-auth-algo"] ?: return false),
                "cert_url" to (headers["paypal-cert-url"] ?: return false),
                "transmission_id" to (headers["paypal-transmission-id"] ?: return false),
                "transmission_sig" to (headers["paypal-transmission-sig"] ?: return false),
                "transmission_time" to (headers["paypal-transmission-time"] ?: return false),
                "webhook_id" to AppConfig.paypalWebhookId,
                "webhook_event" to json.parseToJsonElement(rawBody)
            ))
        }
        if (!res.status.isSuccess()) return false
        return json.parseToJsonElement(res.body<String>()).jsonObject["verification_status"]?.jsonPrimitive?.content == "SUCCESS"
    }
}
