package com.telefam.e2ee

import com.telefam.data.api.E2EEApi
import io.ktor.client.call.body
import io.ktor.http.isSuccess

/**
 * Orchestrates the Signal Protocol message flow. This class contains zero
 * cryptography itself — every crypto operation goes through `engine`, which is
 * the real libsignal-backed implementation on each platform. What's shared here
 * is the *protocol choreography*: when to fetch a PreKeyBundle, when a session
 * already exists, how to poll and ack the mailbox — logic that's identical on
 * every platform and has no business being duplicated per-target.
 */
class MessageRepository(
    private val engine: SignalEngine,
    private val api: E2EEApi,
    private val localDeviceId: Int
) {
    suspend fun registerThisDevice() {
        val registration = engine.ensureIdentity(localDeviceId)
        api.registerDevice(registration)
    }

    suspend fun replenishPreKeysIfLow(startId: Int) {
        val remaining = runCatching { api.remainingPreKeyCount(localDeviceId).body<Map<String, Long>>()["remaining"] ?: 0L }.getOrDefault(0L)
        if (remaining < ONE_TIME_PREKEY_LOW_WATER_MARK) {
            val fresh = engine.generateMorePreKeys(localDeviceId, startId, ONE_TIME_PREKEY_BATCH_SIZE)
            api.replenishPreKeys(localDeviceId, fresh)
        }
    }

    /** Encrypts and sends `plaintext` to every one of the recipient's active devices (multi-device fan-out). */
    suspend fun sendToUser(recipientUserId: String, contentCategory: ContentCategory, viewOnce: Boolean, plaintext: ByteArray) {
        val devicesResponse = api.listDevices(recipientUserId)
        if (!devicesResponse.status.isSuccess()) error("Could not list recipient devices (${devicesResponse.status})")
        val devices = devicesResponse.body<List<DeviceInfoDto>>()
        // A recipient with no registered device can't be sent to; surfacing this keeps the message pending instead of falsely "sent".
        if (devices.isEmpty()) error("Recipient has no registered devices yet")
        for (device in devices) {
            if (!engine.hasSession(recipientUserId, device.deviceId)) {
                val bundleResponse = api.fetchPreKeyBundle(recipientUserId, device.deviceId)
                if (!bundleResponse.status.isSuccess()) error("Could not fetch prekey bundle (${bundleResponse.status})")
                engine.processPreKeyBundle(bundleResponse.body<PreKeyBundleDto>())
            }
            val outgoing = engine.encrypt(recipientUserId, device.deviceId, plaintext)
            val sendResponse = api.sendEnvelope(
                SendEnvelopeRequestDto(
                    recipientId = recipientUserId,
                    recipientDeviceId = device.deviceId,
                    senderDeviceId = localDeviceId,
                    envelopeType = outgoing.envelopeType.name,
                    ciphertext = base64Encode(outgoing.ciphertext),
                    contentCategory = contentCategory.name,
                    viewOnce = viewOnce
                )
            )
            if (!sendResponse.status.isSuccess()) error("Envelope rejected (${sendResponse.status})")
        }
    }

    /** Pulls and decrypts everything waiting in this device's mailbox, acking each as it's consumed. */
    suspend fun pullAndDecryptInbox(): List<DecryptedMessage> {
        val pendingResponse = api.fetchPendingEnvelopes(localDeviceId)
        if (!pendingResponse.status.isSuccess()) return emptyList()
        val pending = pendingResponse.body<List<EnvelopeDto>>()
        val results = mutableListOf<DecryptedMessage>()
        for (envelope in pending) {
            val plaintext = runCatching {
                engine.decrypt(
                    envelope.senderId, envelope.senderDeviceId,
                    EnvelopeType.valueOf(envelope.envelopeType), base64Decode(envelope.ciphertext)
                )
            }.getOrNull()
            api.ackEnvelope(envelope.id) // ack regardless — a message that fails to decrypt can't be recovered by retrying fetch
            if (plaintext != null) {
                results += DecryptedMessage(envelope.senderId, envelope.senderDeviceId, ContentCategory.valueOf(envelope.contentCategory), envelope.viewOnce, plaintext)
            }
        }
        return results
    }

    companion object {
        const val ONE_TIME_PREKEY_LOW_WATER_MARK = 20
        const val ONE_TIME_PREKEY_BATCH_SIZE = 100
    }
}

data class DecryptedMessage(
    val senderId: String,
    val senderDeviceId: Int,
    val contentCategory: ContentCategory,
    val viewOnce: Boolean,
    val plaintext: ByteArray
)

expect fun base64Encode(bytes: ByteArray): String
expect fun base64Decode(value: String): ByteArray
