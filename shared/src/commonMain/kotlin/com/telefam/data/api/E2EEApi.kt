package com.telefam.data.api

import com.telefam.e2ee.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

@Serializable private data class ReplenishBody(val deviceId: Int, val preKeys: List<PreKeyEntryDto>)
@Serializable private data class RotateSignedPreKeyBody(val deviceId: Int, val keyId: Int, val publicKey: String, val signature: String)

/** Every call here carries only public keys, signatures over public keys, or opaque ciphertext — see SignalEngine for where those bytes actually come from. */
class E2EEApi(private val client: HttpClient) {

    suspend fun registerDevice(req: RegisterDeviceRequestDto) =
        client.post("${ApiConfig.baseUrl}/api/e2ee/keys/register") {
            contentType(ContentType.Application.Json); setBody(req)
        }

    suspend fun replenishPreKeys(deviceId: Int, preKeys: List<PreKeyEntryDto>) =
        client.post("${ApiConfig.baseUrl}/api/e2ee/keys/replenish") {
            contentType(ContentType.Application.Json); setBody(ReplenishBody(deviceId, preKeys))
        }

    suspend fun remainingPreKeyCount(deviceId: Int) =
        client.get("${ApiConfig.baseUrl}/api/e2ee/keys/count/$deviceId")

    suspend fun rotateSignedPreKey(deviceId: Int, keyId: Int, publicKey: String, signature: String) =
        client.post("${ApiConfig.baseUrl}/api/e2ee/keys/rotate-signed") {
            contentType(ContentType.Application.Json); setBody(RotateSignedPreKeyBody(deviceId, keyId, publicKey, signature))
        }

    suspend fun listDevices(userId: String) = client.get("${ApiConfig.baseUrl}/api/e2ee/keys/devices/$userId")

    suspend fun fetchPreKeyBundle(userId: String, deviceId: Int) =
        client.get("${ApiConfig.baseUrl}/api/e2ee/keys/bundle/$userId/$deviceId")

    suspend fun sendEnvelope(req: SendEnvelopeRequestDto) =
        client.post("${ApiConfig.baseUrl}/api/e2ee/messages") {
            contentType(ContentType.Application.Json); setBody(req)
        }

    suspend fun fetchPendingEnvelopes(deviceId: Int) = client.get("${ApiConfig.baseUrl}/api/e2ee/messages/$deviceId")

    suspend fun ackEnvelope(envelopeId: String) = client.delete("${ApiConfig.baseUrl}/api/e2ee/messages/$envelopeId")
}
