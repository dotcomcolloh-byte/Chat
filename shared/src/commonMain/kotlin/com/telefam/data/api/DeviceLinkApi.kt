package com.telefam.data.api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

/** Wire DTOs mirroring the backend's com.telefam.routes.DeviceLinkRoutes. */
@Serializable
data class LinkChallengeDto(
    val challengeId: String,
    val qrPayload: String,
    val expiresInSeconds: Long
)

@Serializable
data class LinkChallengeStatusDto(
    val status: String,
    val secondsRemaining: Long,
    val scannerLabel: String? = null,
    val acceptSecondsRemaining: Long = 0
)

@Serializable
data class LinkScanBody(val challengeId: String, val secret: String, val deviceLabel: String)

@Serializable
data class LinkAcceptBody(val challengeId: String)

@Serializable
data class LinkResultDto(
    val status: String,
    val accessToken: String? = null,
    val refreshToken: String? = null
)

/** Thrown when the server reports the challenge is dead (410 Gone / 400). */
class LinkChallengeInvalidException(message: String) : Exception(message)

/** Client for /api/devices/link — the linked-devices pairing handshake. */
class DeviceLinkApi(private val client: HttpClient) {

    constructor() : this(createTelefamHttpClient())

    private val base get() = ApiConfig.baseUrl.trimEnd('/')

    /** Owner: mint a fresh QR challenge (invalidates any previous one server-side). */
    suspend fun createChallenge(): LinkChallengeDto {
        val res = client.post("$base/api/devices/link/challenge")
        if (!res.status.isSuccess()) throw Exception("challenge failed: ${res.status}")
        return res.body()
    }

    /** Owner: poll challenge state. */
    suspend fun challengeStatus(challengeId: String): LinkChallengeStatusDto {
        val res = client.get("$base/api/devices/link/challenge/$challengeId")
        if (!res.status.isSuccess()) throw LinkChallengeInvalidException("status failed: ${res.status}")
        return res.body()
    }

    /** Scanner: present a scanned QR payload. */
    suspend fun scan(challengeId: String, secret: String, deviceLabel: String) {
        val res = client.post("$base/api/devices/link/scan") {
            contentType(ContentType.Application.Json)
            setBody(LinkScanBody(challengeId, secret, deviceLabel))
        }
        if (!res.status.isSuccess()) throw LinkChallengeInvalidException("This QR code is no longer valid")
    }

    /** Owner: approve the scanned device. */
    suspend fun accept(challengeId: String) {
        val res = client.post("$base/api/devices/link/accept") {
            contentType(ContentType.Application.Json)
            setBody(LinkAcceptBody(challengeId))
        }
        if (!res.status.isSuccess()) throw LinkChallengeInvalidException("This link request is no longer valid")
    }

    /** Owner: reject the scanned device. */
    suspend fun decline(challengeId: String) {
        val res = client.post("$base/api/devices/link/decline") {
            contentType(ContentType.Application.Json)
            setBody(LinkAcceptBody(challengeId))
        }
        if (!res.status.isSuccess()) throw LinkChallengeInvalidException("This link request is no longer valid")
    }

    /**
     * Scanner: poll for the outcome of a scanned challenge.
     * Returns ACCEPTED with tokens (exactly once), PENDING/SCANNED while waiting,
     * or throws [LinkChallengeInvalidException] when the request is dead.
     */
    suspend fun fetchResult(challengeId: String, secret: String): LinkResultDto {
        val res = client.get("$base/api/devices/link/result/$challengeId") {
            parameter("k", secret)
        }
        if (res.status == HttpStatusCode.Gone || res.status == HttpStatusCode.BadRequest) {
            throw LinkChallengeInvalidException("This link request is no longer valid")
        }
        if (!res.status.isSuccess()) throw Exception("result failed: ${res.status}")
        return res.body()
    }
}
