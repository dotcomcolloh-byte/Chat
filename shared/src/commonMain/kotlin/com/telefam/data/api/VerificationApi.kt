package com.telefam.data.api

import com.telefam.verification.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class VerificationApi(private val client: HttpClient) {
    private val base get() = ApiConfig.baseUrl

    suspend fun pricing(): PricingResponseDto = client.get("$base/api/verification/pricing").body()

    suspend fun status(): VerificationStatusDto = client.get("$base/api/verification/status").body()

    suspend fun initiatePayment(plan: String): InitiatedPaymentDto =
        client.post("$base/api/verification/payments/initiate") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("plan" to plan))
        }.body()

    /** Server re-verifies with the provider — this returns the authoritative status. */
    suspend fun confirmPayment(paymentId: String): PaymentStatusDto =
        client.post("$base/api/verification/payments/$paymentId/confirm").body()

    suspend fun refund(paymentId: String): PaymentStatusDto =
        client.post("$base/api/verification/payments/$paymentId/refund").body()

    suspend fun startLiveness(): LivenessChallengeDto =
        client.post("$base/api/verification/liveness/start").body()

    suspend fun completeLiveness(request: LivenessCompletionRequest): StateResponse =
        client.post("$base/api/verification/liveness/complete") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun submitId(frontMediaId: String, backMediaId: String): StateResponse =
        client.post("$base/api/verification/id") {
            contentType(ContentType.Application.Json)
            setBody(IdSubmissionRequestDto(frontMediaId, backMediaId))
        }.body()

    suspend fun retry(): StateResponse = client.post("$base/api/verification/retry").body()

    /** Reuses the hardened media pipeline (re-encode, EXIF strip) for verification artifacts. */
    suspend fun uploadArtifact(bytes: ByteArray, mimeType: String, filename: String): String {
        val res = client.submitFormWithBinaryData(
            url = "$base/api/media/upload",
            formData = formData {
                append("file", bytes, Headers.build {
                    append(HttpHeaders.ContentType, mimeType)
                    append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                })
            }
        )
        if (!res.status.isSuccess()) throw Exception("upload failed: ${res.status}")
        return kotlinx.serialization.json.Json.parseToJsonElement(res.bodyAsText())
            .jsonObject.getValue("mediaId").jsonPrimitive.content
    }
}
