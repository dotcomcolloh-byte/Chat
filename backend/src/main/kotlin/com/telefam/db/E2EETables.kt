package com.telefam.db

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.datetime

/**
 * One row per (user, device). A user can have multiple devices (phone, tablet,
 * desktop), each with its own long-term Signal Protocol identity key pair — the
 * private half NEVER leaves the device (Android Keystore / iOS Keychain), only
 * identityPublicKey is ever sent here.
 */
object DeviceIdentities : UUIDTable("device_identities") {
    val userId = uuid("user_id")
    val deviceId = integer("device_id")
    val registrationId = integer("registration_id")
    val identityPublicKey = binary("identity_public_key", 33) // Signal public key wire format
    val label = varchar("label", 60).nullable() // "Alice's Pixel", "Alice's iPad" - for the multi-device UI
    val createdAt = datetime("created_at")
    val lastSeenAt = datetime("last_seen_at")
}

/**
 * One active signed prekey per device at a time (older ones kept briefly for
 * in-flight messages, then pruned). Rotated periodically per Signal Protocol
 * hygiene - see E2EEService.SIGNED_PREKEY_ROTATION_DAYS.
 */
object SignedPreKeys : UUIDTable("signed_prekeys") {
    val userId = uuid("user_id")
    val deviceId = integer("device_id")
    val keyId = integer("key_id")
    val publicKey = binary("public_key", 33)
    val signature = binary("signature", 64)
    val createdAt = datetime("created_at")
}

/**
 * A batch of single-use prekeys uploaded by each device. Each is consumed
 * (deleted) the moment another device fetches it to start a session — that
 * single-use property is what gives X3DH its per-session forward secrecy
 * before the Double Ratchet takes over.
 */
object OneTimePreKeys : UUIDTable("one_time_prekeys") {
    val userId = uuid("user_id")
    val deviceId = integer("device_id")
    val keyId = integer("key_id")
    val publicKey = binary("public_key", 33)
    val createdAt = datetime("created_at")
}

/**
 * The mailbox: opaque Signal Protocol ciphertext envelopes waiting for delivery
 * to a specific (recipient, device). `ciphertext` is meaningless to the server -
 * it is exactly what SessionCipher.encrypt() produced on the sender's device,
 * relayed byte-for-byte. Deleted once the recipient device has fetched and
 * decrypted it (store-and-forward, not persistent history - chat history lives
 * encrypted on-device, matching how Signal's own server works).
 */
object MessageEnvelopes : UUIDTable("message_envelopes") {
    val senderId = uuid("sender_id")
    val senderDeviceId = integer("sender_device_id")
    val recipientId = uuid("recipient_id")
    val recipientDeviceId = integer("recipient_device_id")
    val envelopeType = varchar("envelope_type", 20) // PREKEY_MESSAGE or WHISPER_MESSAGE (see EnvelopeType)
    val ciphertext = binary("ciphertext")
    val contentCategory = varchar("content_category", 20) // TEXT/IMAGE/VIDEO/VOICE/FILE - routing only, server can't read the actual content
    val viewOnce = bool("view_once").default(false)
    val createdAt = datetime("created_at")
}
