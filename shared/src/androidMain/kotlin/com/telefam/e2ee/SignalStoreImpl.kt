package com.telefam.e2ee

import com.telefam.db.local.TelefamDatabase
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore

private fun addrKey(address: SignalProtocolAddress) = "${address.name}:${address.deviceId}"

/** Implements all four Signal Protocol store interfaces against our local SQLDelight DB + Keystore. */
class SignalStoreImpl(
    private val db: TelefamDatabase,
    private val identityStorage: SignalIdentityKeyStorage
) : IdentityKeyStore, PreKeyStore, SignedPreKeyStore, SessionStore, KyberPreKeyStore {

    // --- IdentityKeyStore ---
    override fun getIdentityKeyPair(): IdentityKeyPair {
        val bytes = identityStorage.identityKeyPairBytes
            ?: error("Identity key pair not generated yet — call SignalEngine.ensureIdentity() first")
        return IdentityKeyPair(bytes)
    }

    override fun getLocalRegistrationId(): Int =
        identityStorage.registrationId ?: error("Registration id not generated yet")

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val now = System.currentTimeMillis()
        val existing = db.signalStoreQueries.selectIdentity(addrKey(address)).executeAsOneOrNull()
        val changed = existing != null && existing.identityPublicKey != identityKey.serialize().toBase64()
        db.signalStoreQueries.upsertIdentity(addrKey(address), identityKey.serialize().toBase64(), now)
        return if (changed) IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        else IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
    }

    override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey, direction: IdentityKeyStore.Direction): Boolean {
        val existing = db.signalStoreQueries.selectIdentity(addrKey(address)).executeAsOneOrNull() ?: return true // trust-on-first-use
        return existing.identityPublicKey == identityKey.serialize().toBase64()
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        val row = db.signalStoreQueries.selectIdentity(addrKey(address)).executeAsOneOrNull() ?: return null
        return IdentityKey(row.identityPublicKey.fromBase64(), 0)
    }

    // --- PreKeyStore ---
    override fun loadPreKey(id: Int): PreKeyRecord =
        db.signalStoreQueries.selectPreKey(id.toLong()).executeAsOneOrNull()?.let { PreKeyRecord(it.record) }
            ?: throw org.signal.libsignal.protocol.InvalidKeyIdException("No such prekey: $id")

    override fun storePreKey(id: Int, record: PreKeyRecord) {
        db.signalStoreQueries.insertPreKey(id.toLong(), record.serialize())
    }

    override fun containsPreKey(id: Int): Boolean = db.signalStoreQueries.selectPreKey(id.toLong()).executeAsOneOrNull() != null
    override fun removePreKey(id: Int) { db.signalStoreQueries.deletePreKey(id.toLong()) }

    // --- SignedPreKeyStore ---
    override fun loadSignedPreKey(id: Int): SignedPreKeyRecord =
        db.signalStoreQueries.selectSignedPreKey(id.toLong()).executeAsOneOrNull()?.let { SignedPreKeyRecord(it.record) }
            ?: throw org.signal.libsignal.protocol.InvalidKeyIdException("No such signed prekey: $id")

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> =
        db.signalStoreQueries.selectAllSignedPreKeys().executeAsList().map { SignedPreKeyRecord(it.record) }

    override fun storeSignedPreKey(id: Int, record: SignedPreKeyRecord) {
        db.signalStoreQueries.insertSignedPreKey(id.toLong(), record.serialize())
    }

    override fun containsSignedPreKey(id: Int): Boolean = db.signalStoreQueries.selectSignedPreKey(id.toLong()).executeAsOneOrNull() != null
    override fun removeSignedPreKey(id: Int) { /* keep for now: SIGNED_PREKEY_ROTATION prunes explicitly */ }

    // --- SessionStore ---
    override fun loadSession(address: SignalProtocolAddress): SessionRecord =
        db.signalStoreQueries.selectSession(addrKey(address)).executeAsOneOrNull()?.let { SessionRecord(it.sessionRecord) }
            ?: SessionRecord()

    override fun getSubDeviceSessions(name: String): List<Int> = emptyList() // multi-device fan-out handled at the app layer via DeviceInfoDto list

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.filter(::containsSession).map(::loadSession)

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        db.signalStoreQueries.upsertSession(addrKey(address), record.serialize())
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        db.signalStoreQueries.selectSession(addrKey(address)).executeAsOneOrNull() != null

    override fun deleteSession(address: SignalProtocolAddress) { db.signalStoreQueries.deleteSession(addrKey(address)) }
    override fun deleteAllSessions(name: String) { /* per-address deletion is sufficient for our 1:1 + small-group model */ }

    // --- KyberPreKeyStore (PQXDH one-time key storage) ---
    override fun loadKyberPreKey(id: Int): KyberPreKeyRecord =
        db.signalStoreQueries.selectKyberPreKey(id.toLong()).executeAsOneOrNull()?.let { KyberPreKeyRecord(it.record) }
            ?: throw org.signal.libsignal.protocol.InvalidKeyIdException("No such Kyber prekey: $id")

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> =
        db.signalStoreQueries.selectAllKyberPreKeys().executeAsList().map { KyberPreKeyRecord(it.record) }

    override fun storeKyberPreKey(id: Int, record: KyberPreKeyRecord) {
        db.signalStoreQueries.insertKyberPreKey(id.toLong(), record.serialize())
    }

    override fun containsKyberPreKey(id: Int): Boolean =
        db.signalStoreQueries.selectKyberPreKey(id.toLong()).executeAsOneOrNull() != null

    override fun markKyberPreKeyUsed(id: Int, signedPreKeyId: Int, baseKey: org.signal.libsignal.protocol.ecc.ECPublicKey) {
        db.signalStoreQueries.deleteKyberPreKey(id.toLong())
    }
}

private fun ByteArray.toBase64(): String = android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)
private fun String.fromBase64(): ByteArray = android.util.Base64.decode(this, android.util.Base64.NO_WRAP)
