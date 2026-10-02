// TelefamSignalEngine.swift
//
// SETUP REQUIRED (cannot be completed from the KMP build alone):
//   1. In Xcode: File > Add Package Dependencies... > https://github.com/signalapp/libsignal
//      (this is Signal's own official Swift package, "LibSignalClient" product).
//   2. Add this file to the iosApp target.
//   3. Build `shared.framework` first so Xcode can see the Kotlin `SignalEngine` protocol
//      and related types (they're generated from shared/src/commonMain/.../E2EEModels.kt
//      by Kotlin/Native's Objective-C header generator).
//   4. Kotlin `suspend fun` in a common interface is exposed to Swift as a callback-based
//      method (`completionHandler:`) by default, not `async`. The signatures below assume
//      that translation; if you're on a newer Kotlin/Native that supports direct Swift
//      `async`/`await` interop (SKIE, or KT-... coroutines-native support), adjust to match
//      the actually-generated header rather than this file, since the exact generated
//      signatures depend on your Kotlin version and cannot be verified outside Xcode.
//   5. Wire an instance of `TelefamSignalEngine` into `MessageRepository` from your iOS
//      app's composition root, the same way `AndroidSignalEngine` is wired on Android.
//
// Everything below is real: LibSignalClient's actual API (SessionBuilder, SessionCipher,
// IdentityKeyStore/PreKeyStore/SignedPreKeyStore/SessionStore protocols, PreKeyBundle,
// KeyHelper-equivalents) — the class/protocol names mirror Signal's published Swift API,
// but should be checked against the exact installed package version's generated docs,
// since this file was written without an Xcode environment to compile against.

import Foundation
import LibSignalClient
import Security
import shared // the compiled KMP framework

final class TelefamSignalEngine: NSObject, SignalEngine {

    private let identityStore: TelefamIdentityKeyStore
    private let preKeyStore: TelefamPreKeyStore
    private let signedPreKeyStore: TelefamSignedPreKeyStore
    private let sessionStore: TelefamSessionStore

    init(identityStore: TelefamIdentityKeyStore,
         preKeyStore: TelefamPreKeyStore,
         signedPreKeyStore: TelefamSignedPreKeyStore,
         sessionStore: TelefamSessionStore) {
        self.identityStore = identityStore
        self.preKeyStore = preKeyStore
        self.signedPreKeyStore = signedPreKeyStore
        self.sessionStore = sessionStore
    }

    // MARK: - Identity & prekeys

    func ensureIdentity(deviceId: Int32) throws -> RegisterDeviceRequestDto {
        if try identityStore.identityKeyPairData() == nil {
            let identityKeyPair = IdentityKeyPair.generate()
            try identityStore.saveIdentityKeyPair(identityKeyPair.serialize())
            try identityStore.saveRegistrationId(UInt32.random(in: 1..<16380))
        }
        let identityKeyPair = try IdentityKeyPair(bytes: identityStore.identityKeyPairData()!)
        let registrationId = try identityStore.registrationId()

        let signedPreKeyId: UInt32 = 1
        let signedPreKeyPair = PrivateKey.generate()
        let signature = identityKeyPair.privateKey.generateSignature(message: signedPreKeyPair.publicKey.serialize())
        try signedPreKeyStore.storeSignedPreKey(id: signedPreKeyId, publicKey: signedPreKeyPair.publicKey.serialize(),
                                                 privateKey: signedPreKeyPair.serialize(), signature: signature)

        let oneTimePreKeys: [PreKeyEntryDto] = (1...100).map { keyId in
            let kp = PrivateKey.generate()
            try? preKeyStore.storePreKey(id: UInt32(keyId), publicKey: kp.publicKey.serialize(), privateKey: kp.serialize())
            return PreKeyEntryDto(keyId: Int32(keyId), publicKey: kp.publicKey.serialize().base64EncodedString())
        }

        return RegisterDeviceRequestDto(
            deviceId: deviceId,
            registrationId: Int32(registrationId),
            identityPublicKey: identityKeyPair.identityKey.publicKey.serialize().base64EncodedString(),
            signedPreKeyId: Int32(signedPreKeyId),
            signedPreKeyPublic: signedPreKeyPair.publicKey.serialize().base64EncodedString(),
            signedPreKeySignature: signature.base64EncodedString(),
            oneTimePreKeys: oneTimePreKeys,
            label: UIDevice.current.name
        )
    }

    // MARK: - Session establishment (X3DH)

    func processPreKeyBundle(bundle: PreKeyBundleDto) throws {
        let address = try ProtocolAddress(name: bundle.userId, deviceId: UInt32(bundle.deviceId))
        let identityKey = try IdentityKey(bytes: Data(base64Encoded: bundle.identityPublicKey)!)
        let signedPreKeyPublic = try PublicKey(Data(base64Encoded: bundle.signedPreKeyPublic)!)
        let oneTimePreKeyPublic = bundle.oneTimePreKey.flatMap { try? PublicKey(Data(base64Encoded: $0.publicKey)!) }

        let preKeyBundle = try PreKeyBundle(
            registrationId: UInt32(bundle.registrationId),
            deviceId: UInt32(bundle.deviceId),
            prekeyId: bundle.oneTimePreKey.map { UInt32($0.keyId) },
            prekey: oneTimePreKeyPublic,
            signedPrekeyId: UInt32(bundle.signedPreKeyId),
            signedPrekey: signedPreKeyPublic,
            signedPrekeySignature: Data(base64Encoded: bundle.signedPreKeySignature)!,
            identity: identityKey
        )

        try processPreKeyBundle(preKeyBundle, for: address,
                                 sessionStore: sessionStore, identityStore: identityStore, context: NullContext())
    }

    // MARK: - Encrypt / decrypt (Double Ratchet, handled entirely inside LibSignalClient)

    func encrypt(remoteUserId: String, remoteDeviceId: Int32, plaintext: KotlinByteArray) throws -> OutgoingCiphertext {
        let address = try ProtocolAddress(name: remoteUserId, deviceId: UInt32(remoteDeviceId))
        let message = try signalEncrypt(message: plaintext.toData(), for: address,
                                         sessionStore: sessionStore, identityStore: identityStore, context: NullContext())
        let type: EnvelopeType = (message.messageType == .preKey) ? .prekeyMessage : .whisperMessage
        return OutgoingCiphertext(envelopeType: type, ciphertext: message.serialize().toKotlinByteArray())
    }

    func decrypt(remoteUserId: String, remoteDeviceId: Int32, envelopeType: EnvelopeType, ciphertext: KotlinByteArray) throws -> KotlinByteArray {
        let address = try ProtocolAddress(name: remoteUserId, deviceId: UInt32(remoteDeviceId))
        let plaintext: Data
        if envelopeType == .prekeyMessage {
            let msg = try PreKeySignalMessage(bytes: ciphertext.toData())
            plaintext = try signalDecryptPreKey(message: msg, from: address, sessionStore: sessionStore,
                                                 identityStore: identityStore, preKeyStore: preKeyStore,
                                                 signedPreKeyStore: signedPreKeyStore, context: NullContext())
        } else {
            let msg = try SignalMessage(bytes: ciphertext.toData())
            plaintext = try signalDecrypt(message: msg, from: address, sessionStore: sessionStore,
                                           identityStore: identityStore, context: NullContext())
        }
        return plaintext.toKotlinByteArray()
    }

    // MARK: - Device verification (safety number)

    func safetyNumber(remoteUserId: String, remoteDeviceId: Int32) -> SafetyNumber {
        // Fingerprint.forDisplay(...) — Signal's real numeric safety-number generator.
        // Left as a documented call site: needs both parties' identity keys + stable
        // "version" byte, matching LibSignalClient's Fingerprint API exactly at build time.
        return SafetyNumber(displayCode: "Verify in-app after building against LibSignalClient's Fingerprint API")
    }
}

// TelefamIdentityKeyStore / TelefamPreKeyStore / TelefamSignedPreKeyStore / TelefamSessionStore:
// implement LibSignalClient's IdentityKeyStore / PreKeyStore / SignedPreKeyStore / SessionStore
// protocols, persisting via Keychain (identity key pair + registration id — mirrors
// SignalIdentityKeyStorage.kt on Android) and a local encrypted store for prekey/session
// records (mirrors SignalStoreImpl.kt). Not included here for length; same shape as the
// Android Kotlin versions, translated to Swift + Keychain (`SecItemAdd`/`SecItemCopyMatching`,
// as already used in TokenStorage.ios.kt) instead of EncryptedSharedPreferences.
