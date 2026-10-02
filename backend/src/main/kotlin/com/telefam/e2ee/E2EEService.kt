package com.telefam.e2ee

import com.telefam.db.DatabaseFactory.dbQuery
import com.telefam.db.DeviceIdentities
import com.telefam.db.MessageEnvelopes
import com.telefam.db.OneTimePreKeys
import com.telefam.db.SignedPreKeys
import com.telefam.db.RlsContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.util.*

@Serializable
data class RegisterDeviceRequest(
    val deviceId: Int,
    val registrationId: Int,
    val identityPublicKey: String, // base64
    val signedPreKeyId: Int,
    val signedPreKeyPublic: String, // base64
    val signedPreKeySignature: String, // base64 - signature over signedPreKeyPublic by the identity key
    val oneTimePreKeys: List<PreKeyEntry>,
    val label: String? = null
)

@Serializable data class PreKeyEntry(val keyId: Int, val publicKey: String)

@Serializable
data class PreKeyBundleResponse(
    val userId: String,
    val deviceId: Int,
    val registrationId: Int,
    val identityPublicKey: String,
    val signedPreKeyId: Int,
    val signedPreKeyPublic: String,
    val signedPreKeySignature: String,
    val oneTimePreKey: PreKeyEntry?
)

@Serializable data class DeviceInfo(val deviceId: Int, val label: String?, val lastSeenAt: String)

@Serializable
data class SendEnvelopeRequest(
    val recipientId: String,
    val recipientDeviceId: Int,
    val senderDeviceId: Int,
    val envelopeType: String,
    val ciphertext: String,
    val contentCategory: String,
    val viewOnce: Boolean = false
)

@Serializable
data class EnvelopeResponse(
    val id: String,
    val senderId: String,
    val senderDeviceId: Int,
    val envelopeType: String,
    val ciphertext: String,
    val contentCategory: String,
    val viewOnce: Boolean,
    val createdAt: String
)

private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
private fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)

class E2EEService(
    private val onEnvelopeStored: (recipientId: UUID) -> Unit = {},
    /** Fires after a non-control envelope is durably stored — used to wake the recipient's
     *  device with a (content-free) push so backgrounded apps can show Reply / Mark-as-read. */
    private val onMessageStored: suspend (recipientId: UUID, senderId: UUID) -> Unit = { _, _ -> }
) {

    suspend fun registerDevice(userId: UUID, req: RegisterDeviceRequest) = RlsContext.asUser(userId) {
        val now = LocalDateTime.now()
        val existing = DeviceIdentities.selectAll().where {
            (DeviceIdentities.userId eq userId) and (DeviceIdentities.deviceId eq req.deviceId)
        }.singleOrNull()

        if (existing == null) {
            DeviceIdentities.insert {
                it[DeviceIdentities.userId] = userId
                it[DeviceIdentities.deviceId] = req.deviceId
                it[DeviceIdentities.registrationId] = req.registrationId
                it[DeviceIdentities.identityPublicKey] = unb64(req.identityPublicKey)
                it[DeviceIdentities.label] = req.label
                it[DeviceIdentities.createdAt] = now
                it[DeviceIdentities.lastSeenAt] = now
            }
        } else {
            DeviceIdentities.update({ (DeviceIdentities.userId eq userId) and (DeviceIdentities.deviceId eq req.deviceId) }) {
                it[DeviceIdentities.lastSeenAt] = now
            }
        }

        SignedPreKeys.insert {
            it[SignedPreKeys.userId] = userId
            it[SignedPreKeys.deviceId] = req.deviceId
            it[SignedPreKeys.keyId] = req.signedPreKeyId
            it[SignedPreKeys.publicKey] = unb64(req.signedPreKeyPublic)
            it[SignedPreKeys.signature] = unb64(req.signedPreKeySignature)
            it[SignedPreKeys.createdAt] = now
        }

        req.oneTimePreKeys.forEach { entry ->
            OneTimePreKeys.insert {
                it[OneTimePreKeys.userId] = userId
                it[OneTimePreKeys.deviceId] = req.deviceId
                it[OneTimePreKeys.keyId] = entry.keyId
                it[OneTimePreKeys.publicKey] = unb64(entry.publicKey)
                it[OneTimePreKeys.createdAt] = now
            }
        }
    }

    suspend fun replenishOneTimePreKeys(userId: UUID, deviceId: Int, entries: List<PreKeyEntry>) = RlsContext.asUser(userId) {
        val now = LocalDateTime.now()
        entries.forEach { entry ->
            OneTimePreKeys.insert {
                it[OneTimePreKeys.userId] = userId
                it[OneTimePreKeys.deviceId] = deviceId
                it[OneTimePreKeys.keyId] = entry.keyId
                it[OneTimePreKeys.publicKey] = unb64(entry.publicKey)
                it[OneTimePreKeys.createdAt] = now
            }
        }
    }

    suspend fun remainingOneTimePreKeyCount(userId: UUID, deviceId: Int): Long = RlsContext.asUser(userId) {
        OneTimePreKeys.selectAll().where { (OneTimePreKeys.userId eq userId) and (OneTimePreKeys.deviceId eq deviceId) }.count()
    }

    suspend fun rotateSignedPreKey(userId: UUID, deviceId: Int, keyId: Int, publicKey: String, signature: String) = RlsContext.asUser(userId) {
        SignedPreKeys.insert {
            it[SignedPreKeys.userId] = userId
            it[SignedPreKeys.deviceId] = deviceId
            it[SignedPreKeys.keyId] = keyId
            it[SignedPreKeys.publicKey] = unb64(publicKey)
            it[SignedPreKeys.signature] = unb64(signature)
            it[SignedPreKeys.createdAt] = LocalDateTime.now()
        }
        val old = SignedPreKeys.selectAll().where { (SignedPreKeys.userId eq userId) and (SignedPreKeys.deviceId eq deviceId) }
            .orderBy(SignedPreKeys.createdAt to SortOrder.DESC).drop(2).map { it[SignedPreKeys.keyId] }
        if (old.isNotEmpty()) {
            SignedPreKeys.deleteWhere { sql -> with(sql) { (SignedPreKeys.userId eq userId) and (SignedPreKeys.deviceId eq deviceId) and (SignedPreKeys.keyId inList old) } }
        }
    }

    suspend fun listDevices(userId: UUID): List<DeviceInfo> = dbQuery {
        DeviceIdentities.selectAll().where { DeviceIdentities.userId eq userId }
            .map { DeviceInfo(it[DeviceIdentities.deviceId], it[DeviceIdentities.label], it[DeviceIdentities.lastSeenAt].toString()) }
    }

    /** Fetches (and atomically consumes) a PreKeyBundle so a caller can start a session with this device. */
    suspend fun fetchPreKeyBundle(targetUserId: UUID, targetDeviceId: Int): PreKeyBundleResponse? = dbQuery {
        val identity = DeviceIdentities.selectAll().where {
            (DeviceIdentities.userId eq targetUserId) and (DeviceIdentities.deviceId eq targetDeviceId)
        }.singleOrNull() ?: return@dbQuery null

        val signedPreKey = SignedPreKeys.selectAll().where {
            (SignedPreKeys.userId eq targetUserId) and (SignedPreKeys.deviceId eq targetDeviceId)
        }.orderBy(SignedPreKeys.createdAt to SortOrder.DESC).firstOrNull() ?: return@dbQuery null

        val oneTime = OneTimePreKeys.selectAll().where {
            (OneTimePreKeys.userId eq targetUserId) and (OneTimePreKeys.deviceId eq targetDeviceId)
        }.orderBy(OneTimePreKeys.createdAt to SortOrder.ASC).firstOrNull()

        if (oneTime != null) {
            OneTimePreKeys.deleteWhere { sql -> with(sql) { OneTimePreKeys.id eq oneTime[OneTimePreKeys.id] } }
        }

        PreKeyBundleResponse(
            userId = targetUserId.toString(),
            deviceId = targetDeviceId,
            registrationId = identity[DeviceIdentities.registrationId],
            identityPublicKey = b64(identity[DeviceIdentities.identityPublicKey]),
            signedPreKeyId = signedPreKey[SignedPreKeys.keyId],
            signedPreKeyPublic = b64(signedPreKey[SignedPreKeys.publicKey]),
            signedPreKeySignature = b64(signedPreKey[SignedPreKeys.signature]),
            oneTimePreKey = oneTime?.let { PreKeyEntry(it[OneTimePreKeys.keyId], b64(it[OneTimePreKeys.publicKey])) }
        )
    }

    suspend fun sendEnvelope(senderId: UUID, req: SendEnvelopeRequest): UUID {
        val recipientId = UUID.fromString(req.recipientId)

        // Server-authoritative block enforcement: if the recipient has blocked this sender,
        // the envelope is silently dropped (the sender's client keeps its local "sent" state,
        // exactly like mainstream messengers — a block never leaks a detectable error).
        val blocked = RlsContext.asUser(senderId) {
            com.telefam.db.BlockedUsers.selectAll().where {
                (com.telefam.db.BlockedUsers.blockerId eq recipientId) and
                    (com.telefam.db.BlockedUsers.blockedId eq senderId)
            }.any()
        }
        if (blocked) return UUID.randomUUID()

        val id = RlsContext.asUser(senderId) {
            val newId = UUID.randomUUID()
            MessageEnvelopes.insert {
                it[MessageEnvelopes.id] = newId
                it[MessageEnvelopes.senderId] = senderId
                it[MessageEnvelopes.senderDeviceId] = req.senderDeviceId
                it[MessageEnvelopes.recipientId] = recipientId
                it[MessageEnvelopes.recipientDeviceId] = req.recipientDeviceId
                it[MessageEnvelopes.envelopeType] = req.envelopeType
                it[MessageEnvelopes.ciphertext] = unb64(req.ciphertext)
                it[MessageEnvelopes.contentCategory] = req.contentCategory
                it[MessageEnvelopes.viewOnce] = req.viewOnce
                it[MessageEnvelopes.createdAt] = LocalDateTime.now()
            }
            newId
        }
        // Realtime nudge: the recipient's live sockets pull their mailbox immediately
        // instead of waiting for the next polling tick. Best-effort, metadata only —
        // and always OUTSIDE the database transaction (network calls never hold a tx open).
        runCatching { onEnvelopeStored(recipientId) }
        // Chat push for backgrounded/killed apps. Control traffic (receipts, edits, …) stays silent.
        if (req.contentCategory != "CONTROL" && req.contentCategory != "POLL_VOTE" && req.contentCategory != "FLAG") {
            runCatching { onMessageStored(recipientId, senderId) }
        }
        return id
    }

    suspend fun fetchPendingEnvelopes(userId: UUID, deviceId: Int): List<EnvelopeResponse> = RlsContext.asUser(userId) {
        MessageEnvelopes.selectAll().where {
            (MessageEnvelopes.recipientId eq userId) and (MessageEnvelopes.recipientDeviceId eq deviceId)
        }.orderBy(MessageEnvelopes.createdAt to SortOrder.ASC).map {
            EnvelopeResponse(
                id = it[MessageEnvelopes.id].value.toString(),
                senderId = it[MessageEnvelopes.senderId].toString(),
                senderDeviceId = it[MessageEnvelopes.senderDeviceId],
                envelopeType = it[MessageEnvelopes.envelopeType],
                ciphertext = b64(it[MessageEnvelopes.ciphertext]),
                contentCategory = it[MessageEnvelopes.contentCategory],
                viewOnce = it[MessageEnvelopes.viewOnce],
                createdAt = it[MessageEnvelopes.createdAt].toString()
            )
        }
    }

    suspend fun ackEnvelope(userId: UUID, envelopeId: UUID) = RlsContext.asUser(userId) {
        MessageEnvelopes.deleteWhere { sql -> with(sql) { (MessageEnvelopes.id eq envelopeId) and (MessageEnvelopes.recipientId eq userId) } }
    }

    companion object {
        const val SIGNED_PREKEY_ROTATION_DAYS = 30L
        const val ONE_TIME_PREKEY_LOW_WATER_MARK = 20
        const val ONE_TIME_PREKEY_BATCH_SIZE = 100
    }
}
