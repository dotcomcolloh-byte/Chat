package com.telefam.e2ee

import kotlinx.serialization.Serializable

@Serializable data class PreKeyEntryDto(val keyId: Int, val publicKey: String)

@Serializable
data class RegisterDeviceRequestDto(
    val deviceId: Int,
    val registrationId: Int,
    val identityPublicKey: String,
    val signedPreKeyId: Int,
    val signedPreKeyPublic: String,
    val signedPreKeySignature: String,
    val oneTimePreKeys: List<PreKeyEntryDto>,
    val label: String? = null
)

@Serializable
data class PreKeyBundleDto(
    val userId: String,
    val deviceId: Int,
    val registrationId: Int,
    val identityPublicKey: String,
    val signedPreKeyId: Int,
    val signedPreKeyPublic: String,
    val signedPreKeySignature: String,
    val oneTimePreKey: PreKeyEntryDto?
)

@Serializable data class DeviceInfoDto(val deviceId: Int, val label: String?, val lastSeenAt: String)

enum class EnvelopeType { PREKEY_MESSAGE, WHISPER_MESSAGE }
enum class ContentCategory { TEXT, IMAGE, VIDEO, VOICE, AUDIO, FILE, GIF, STICKER, POLL, POLL_VOTE, CONTACT, LOCATION, EVENT, CONTROL, FLAG }

@Serializable
data class SendEnvelopeRequestDto(
    val recipientId: String,
    val recipientDeviceId: Int,
    val senderDeviceId: Int,
    val envelopeType: String,
    val ciphertext: String,
    val contentCategory: String,
    val viewOnce: Boolean = false
)

@Serializable
data class EnvelopeDto(
    val id: String,
    val senderId: String,
    val senderDeviceId: Int,
    val envelopeType: String,
    val ciphertext: String,
    val contentCategory: String,
    val viewOnce: Boolean,
    val createdAt: String
)

/** Result of encrypting a plaintext for one recipient device. */
data class OutgoingCiphertext(val envelopeType: EnvelopeType, val ciphertext: ByteArray)

/** A verification code the user can compare out-of-band with their contact (Signal's "safety number" concept). */
data class SafetyNumber(val displayCode: String)

/**
 * Every method here is implemented per-platform on top of the real Signal Protocol
 * library (org.signal:libsignal-android on Android; Signal's LibSignalClient Swift
 * package via a thin bridge on iOS — see iosMain and the README for the required
 * Xcode-side wiring). Nothing in this interface, or in either actual, sends a
 * private key or plaintext to the network — only what SessionCipher itself
 * produces (opaque ciphertext) ever leaves the device.
 */
interface SignalEngine {
    /** Generates this device's identity + registration id the first time it's called; idempotent after. */
    suspend fun ensureIdentity(deviceId: Int): RegisterDeviceRequestDto

    /** Call when the local one-time prekey supply is low (see E2EEService.ONE_TIME_PREKEY_LOW_WATER_MARK). */
    suspend fun generateMorePreKeys(deviceId: Int, startId: Int, count: Int): List<PreKeyEntryDto>

    /** Call roughly every SIGNED_PREKEY_ROTATION_DAYS. */
    suspend fun rotateSignedPreKey(deviceId: Int, keyId: Int): Triple<Int, String, String> // keyId, publicKey b64, signature b64

    /** Consumes a fetched PreKeyBundle to establish (or re-establish) a session with a remote device. */
    suspend fun processPreKeyBundle(bundle: PreKeyBundleDto)

    /** True once a session with (userId, deviceId) exists locally. */
    fun hasSession(remoteUserId: String, remoteDeviceId: Int): Boolean

    /** Encrypts plaintext for one specific recipient device using its established (or freshly built) session. */
    suspend fun encrypt(remoteUserId: String, remoteDeviceId: Int, plaintext: ByteArray): OutgoingCiphertext

    /** Decrypts an inbound envelope, advancing the Double Ratchet as needed. Handles both message types. */
    suspend fun decrypt(remoteUserId: String, remoteDeviceId: Int, envelopeType: EnvelopeType, ciphertext: ByteArray): ByteArray

    /** Safety-number-style fingerprint for out-of-band device verification. */
    fun safetyNumber(remoteUserId: String, remoteDeviceId: Int): SafetyNumber

    /** True if this device's identity key for (remoteUserId, remoteDeviceId) changed since we last saw it (re-pairing / possible MITM signal). */
    fun identityChanged(remoteUserId: String, remoteDeviceId: Int, newIdentityPublicKey: String): Boolean

    fun trustNewIdentity(remoteUserId: String, remoteDeviceId: Int, newIdentityPublicKey: String)
}
