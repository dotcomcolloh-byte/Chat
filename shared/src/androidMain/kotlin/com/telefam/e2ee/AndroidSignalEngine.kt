package com.telefam.e2ee

import com.telefam.db.local.TelefamDatabase
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper
import java.util.Base64

private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
private fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)

class AndroidSignalEngine(
    private val db: TelefamDatabase,
    private val identityStorage: SignalIdentityKeyStorage
) : SignalEngine {

    private val store by lazy { SignalStoreImpl(db, identityStorage) }

    /**
     * Idempotent. First call creates the identity, a signed prekey and a batch of one-time prekeys.
     * Later calls NEVER regenerate keys (regenerating would overwrite local private prekeys while the
     * server still holds the old public ones, breaking session setup) - they just re-describe the
     * existing registration with an empty one-time-prekey list, so re-registering is harmless.
     */
    override suspend fun ensureIdentity(deviceId: Int): RegisterDeviceRequestDto {
        if (identityStorage.identityKeyPairBytes != null) {
            val identityKeyPair = IdentityKeyPair(identityStorage.identityKeyPairBytes!!)
            val signedPreKey = store.loadSignedPreKeys().maxByOrNull { it.id } ?: error("Missing signed prekey")
            return RegisterDeviceRequestDto(
                deviceId = identityStorage.localDeviceId,
                registrationId = identityStorage.registrationId!!,
                identityPublicKey = b64(identityKeyPair.publicKey.serialize()),
                signedPreKeyId = signedPreKey.id,
                signedPreKeyPublic = b64(signedPreKey.keyPair.publicKey.serialize()),
                signedPreKeySignature = b64(signedPreKey.signature),
                oneTimePreKeys = emptyList()
            )
        }

        val identityKeyPair = IdentityKeyPair.generate()
        identityStorage.identityKeyPairBytes = identityKeyPair.serialize()
        identityStorage.registrationId = KeyHelper.generateRegistrationId(false)
        identityStorage.localDeviceId = deviceId

        val signedPreKey = newSignedPreKey(identityKeyPair, 1)
        store.storeSignedPreKey(signedPreKey.id, signedPreKey)
        val preKeys = (1..E2EEDefaults.ONE_TIME_PREKEY_BATCH_SIZE).map(::newPreKey)
        preKeys.forEach { store.storePreKey(it.id, it) }

        return RegisterDeviceRequestDto(
            deviceId = deviceId,
            registrationId = identityStorage.registrationId!!,
            identityPublicKey = b64(identityKeyPair.publicKey.serialize()),
            signedPreKeyId = signedPreKey.id,
            signedPreKeyPublic = b64(signedPreKey.keyPair.publicKey.serialize()),
            signedPreKeySignature = b64(signedPreKey.signature),
            oneTimePreKeys = preKeys.map { PreKeyEntryDto(it.id, b64(it.keyPair.publicKey.serialize())) }
        )
    }

    override suspend fun generateMorePreKeys(deviceId: Int, startId: Int, count: Int): List<PreKeyEntryDto> {
        val preKeys = (0 until count).map { newPreKey(startId + it) }
        preKeys.forEach { store.storePreKey(it.id, it) }
        return preKeys.map { PreKeyEntryDto(it.id, b64(it.keyPair.publicKey.serialize())) }
    }

    override suspend fun rotateSignedPreKey(deviceId: Int, keyId: Int): Triple<Int, String, String> {
        val identityKeyPair = IdentityKeyPair(identityStorage.identityKeyPairBytes!!)
        val signedPreKey = newSignedPreKey(identityKeyPair, keyId)
        store.storeSignedPreKey(signedPreKey.id, signedPreKey)
        return Triple(signedPreKey.id, b64(signedPreKey.keyPair.publicKey.serialize()), b64(signedPreKey.signature))
    }

    override suspend fun processPreKeyBundle(bundle: PreKeyBundleDto) {
        val address = SignalProtocolAddress(bundle.userId, bundle.deviceId)
        // The service currently publishes classical X3DH prekeys only. This valid but
        // unused KEM key satisfies libsignal's non-null constructor contract while the
        // sentinel id ensures it is not treated as an advertised Kyber prekey.
        val unusedKyberKey = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val preKeyBundle = PreKeyBundle(
            bundle.registrationId,
            bundle.deviceId,
            bundle.oneTimePreKey?.keyId ?: PreKeyBundle.NULL_PRE_KEY_ID,
            bundle.oneTimePreKey?.let { ECPublicKey(unb64(it.publicKey)) },
            bundle.signedPreKeyId,
            ECPublicKey(unb64(bundle.signedPreKeyPublic)),
            unb64(bundle.signedPreKeySignature),
            IdentityKey(unb64(bundle.identityPublicKey)),
            PreKeyBundle.NULL_PRE_KEY_ID,
            unusedKyberKey.publicKey,
            byteArrayOf()
        )
        SessionBuilder(store, store, store, store, address).process(preKeyBundle)
    }

    override fun hasSession(remoteUserId: String, remoteDeviceId: Int): Boolean =
        store.containsSession(SignalProtocolAddress(remoteUserId, remoteDeviceId))

    override suspend fun encrypt(remoteUserId: String, remoteDeviceId: Int, plaintext: ByteArray): OutgoingCiphertext {
        val cipher = SessionCipher(store, store, store, store, store, SignalProtocolAddress(remoteUserId, remoteDeviceId))
        val message = cipher.encrypt(plaintext)
        val type = if (message.type == CiphertextMessage.PREKEY_TYPE) {
            EnvelopeType.PREKEY_MESSAGE
        } else {
            EnvelopeType.WHISPER_MESSAGE
        }
        return OutgoingCiphertext(type, message.serialize())
    }

    override suspend fun decrypt(remoteUserId: String, remoteDeviceId: Int, envelopeType: EnvelopeType, ciphertext: ByteArray): ByteArray {
        val address = SignalProtocolAddress(remoteUserId, remoteDeviceId)
        val cipher = SessionCipher(store, store, store, store, store, address)
        return if (envelopeType == EnvelopeType.PREKEY_MESSAGE) {
            cipher.decrypt(PreKeySignalMessage(ciphertext))
        } else {
            cipher.decrypt(SignalMessage(ciphertext))
        }
    }

    override fun safetyNumber(remoteUserId: String, remoteDeviceId: Int): SafetyNumber {
        val localIdentity = IdentityKeyPair(identityStorage.identityKeyPairBytes!!).publicKey
        val remoteIdentity = store.getIdentity(SignalProtocolAddress(remoteUserId, remoteDeviceId))
            ?: return SafetyNumber("Not yet established")
        val generator = NumericFingerprintGenerator(5200)
        val fingerprint = generator.createFor(
            1, remoteUserId.toByteArray(), localIdentity, remoteUserId.toByteArray(), remoteIdentity
        )
        return SafetyNumber(fingerprint.displayableFingerprint.displayText)
    }

    override fun identityChanged(remoteUserId: String, remoteDeviceId: Int, newIdentityPublicKey: String): Boolean {
        val existing = store.getIdentity(SignalProtocolAddress(remoteUserId, remoteDeviceId)) ?: return false
        return existing.serialize().toList() != unb64(newIdentityPublicKey).toList()
    }

    override fun trustNewIdentity(remoteUserId: String, remoteDeviceId: Int, newIdentityPublicKey: String) {
        store.saveIdentity(SignalProtocolAddress(remoteUserId, remoteDeviceId), IdentityKey(unb64(newIdentityPublicKey), 0))
    }
}

object E2EEDefaults {
    const val ONE_TIME_PREKEY_BATCH_SIZE = 100
}

private fun newPreKey(id: Int) = PreKeyRecord(id, ECKeyPair.generate())

private fun newSignedPreKey(identityKeyPair: IdentityKeyPair, id: Int): SignedPreKeyRecord {
    val keyPair = ECKeyPair.generate()
    val signature = identityKeyPair.privateKey.calculateSignature(keyPair.publicKey.serialize())
    return SignedPreKeyRecord(id, System.currentTimeMillis(), keyPair, signature)
}
