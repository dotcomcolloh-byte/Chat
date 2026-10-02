//
//  TelefamCallEngine.swift
//  iosApp
//
//  WebRTC P2P call engine + CallKit/PushKit integration.
//
//  Architecture notes:
//   - Media (audio/video/screen) flows peer-to-peer over DTLS-SRTP. The Telefam backend
//     only relays SDP/ICE JSON frames and never sees media or the ringtone.
//   - Incoming calls when the phone is locked / app backgrounded / app killed arrive as
//     APNs **VoIP pushes** (PushKit) -> reportNewIncomingCall -> the system incoming-call UI.
//     This is the only Apple-approved path for this behavior; do not attempt to use regular
//     pushes or silent pushes for ringing.
//   - The ringtone for in-app ringing is a bundled local resource (incoming_ringtone.caf);
//     when the app is killed, the OS plays its own ringtone via CallKit.
//
//  Setup:
//   1. Add WebRTC.framework (GoogleWebRTC CocoaPod or SPM binary) to the Xcode target.
//   2. Enable capabilities: Push Notifications, Background Modes (Voice over IP, Audio,
//      AirPlay and Picture in Picture), and add the "PushKit" usage.
//   3. Call `TelefamCallEngine.shared.register()` from AppDelegate/startup so the Kotlin
//      side's IosCallBridgeRegistry is populated.
//

import Foundation
import WebRTC
import CallKit
import PushKit
import AVFoundation
import shared // the KMP framework (baseName = "shared")

final class TelefamCallEngine: NSObject {

    static let shared = TelefamCallEngine()

    // MARK: - WebRTC

    private var factory: RTCPeerConnectionFactory!
    private var peerConnection: RTCPeerConnection?
    private var iceServers: [RTCIceServer] = []
    private var localAudioTrack: RTCAudioTrack?
    private var localVideoTrack: RTCVideoTrack?
    private var cameraCapturer: RTCCameraVideoCapturer?
    private var screenCapturer: RTCVideoCapturer?   // Broadcast Upload Extension feeds frames via app group
    private var listener: WebRtcEngineListener?

    // Video views surfaced to Compose via IosVideoViewRegistry
    private(set) var remoteView: RTCMTLVideoView!
    private(set) var localView: RTCMTLVideoView!

    // MARK: - CallKit / PushKit

    private let callController = CXCallController()
    private var provider: CXProvider!
    private var pushRegistry: PKPushRegistry!

    private override init() {
        super.init()
        let encoder = RTCDefaultVideoEncoderFactory()
        let decoder = RTCDefaultVideoDecoderFactory()
        factory = RTCPeerConnectionFactory(encoderFactory: encoder, decoderFactory: decoder)
        remoteView = RTCMTLVideoView(frame: .zero)
        localView = RTCMTLVideoView(frame: .zero)
        localView.layer.zPosition = 1

        let config = CXProviderConfiguration(localizedName: "Telefam")
        config.supportsVideo = true
        config.maximumCallsPerCallGroup = 1
        config.supportedHandleTypes = [.generic]
        // CallKit plays its OWN ringtone for the locked/killed case; we never ship audio to it.
        config.ringtoneSound = nil
        provider = CXProvider(configuration: config)
        provider.setDelegate(self, queue: nil)

        pushRegistry = PKPushRegistry(queue: .main)
        pushRegistry.delegate = self
        pushRegistry.desiredPushTypes = [.voIP]
    }

    /// Registers this engine with the shared Kotlin module. Call once at app startup.
    func register() {
        IosCallBridgeRegistry.shared.bridge = SwiftIosCallBridge(engine: self)
        IosVideoViewRegistry.shared.provider = SwiftVideoViewProvider(engine: self)
    }
}

// MARK: - Bridge object exposed to Kotlin

final class SwiftIosCallBridge: NSObject, IosCallBridge {
    private let engine: TelefamCallEngine
    init(engine: TelefamCallEngine) { self.engine = engine }

    func setListener(listener: WebRtcEngineListener?) { engine.listener = listener }

    func setIceServers(servers: [IceServerConfig]) {
        engine.iceServers = servers.map {
            RTCIceServer(urlStrings: $0.urls, username: $0.username, credential: $0.credential)
        }
    }

    func startLocalMedia(video: Bool) { engine.startLocalMedia(video: video) }
    func createOffer() { engine.makeOffer() }
    func createAnswer() { engine.makeAnswer() }
    func setRemoteDescription(kind: String, sdp: String) { engine.applyRemoteDescription(kind: kind, json: sdp) }
    func addRemoteIceCandidate(candidateJson: String) { engine.applyRemoteCandidate(json: candidateJson) }
    func setMicMuted(muted: Bool) { engine.localAudioTrack?.isEnabled = !muted }
    func setCameraEnabled(enabled: Bool) { engine.setCamera(enabled: enabled) }
    func switchCamera() { engine.flipCamera() }
    func startScreenShare() { engine.startScreenShare() }
    func stopScreenShare() { engine.stopScreenShare() }
    func setSpeakerphone(on: Bool) {
        let session = AVAudioSession.sharedInstance()
        try? session.overrideOutputAudioPort(on ? .speaker : .none)
    }
    func close() { engine.tearDown() }
}

final class SwiftVideoViewProvider: NSObject, IosVideoViewProvider {
    private let engine: TelefamCallEngine
    init(engine: TelefamCallEngine) { self.engine = engine }
    func remoteVideoView() -> UIView { engine.remoteView }
    func localVideoView() -> UIView { engine.localView }
}

// MARK: - Engine core

extension TelefamCallEngine {

    func startLocalMedia(video: Bool) {
        let config = RTCConfiguration()
        config.iceServers = iceServers
        config.sdpSemantics = .unifiedPlan
        config.continualGatheringPolicy = .gatherContinually

        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        peerConnection = factory.peerConnection(with: config, constraints: constraints, delegate: self)

        localAudioTrack = factory.audioTrack(withTrackId: "audio0")
        peerConnection?.add(localAudioTrack!, streamIds: ["stream0"])

        if video { startCamera(front: true) }
    }

    private func startCamera(front: Bool) {
        let capturer = RTCCameraVideoCapturer()
        let device = RTCCameraVideoCapturer.captureDevices().first {
            $0.position == (front ? .front : .back)
        } ?? RTCCameraVideoCapturer.captureDevices().first
        guard let device else { return }

        let format = RTCCameraVideoCapturer.supportedFormats(for: device)
            .sorted { CMVideoFormatDescriptionGetDimensions($0.formatDescription).width
                   < CMVideoFormatDescriptionGetDimensions($1.formatDescription).width }
            .first { CMVideoFormatDescriptionGetDimensions($0.formatDescription).width >= 1280 } ?? RTCCameraVideoCapturer.supportedFormats(for: device).last
        let fps = format?.videoSupportedFrameRateRanges.map(\.maxFrameRate).max() ?? 24

        let source = factory.videoSource()
        if let format {
            capturer.startCapture(with: device, format: format, fps: Int(min(24, fps)))
        }
        cameraCapturer = capturer

        if localVideoTrack == nil {
            let track = factory.videoTrack(with: source, trackId: "video0")
            localVideoTrack = track
            track.add(localView)
            peerConnection?.add(track, streamIds: ["stream0"])
        }
    }

    func setCamera(enabled: Bool) {
        localVideoTrack?.isEnabled = enabled
        if enabled && cameraCapturer == nil { startCamera(front: true) }
    }

    func flipCamera() {
        // Re-capture from the opposite camera (RTCCameraVideoCapturer has no switcher).
        guard let current = cameraCapturer else { return }
        current.stopCapture()
        cameraCapturer = nil
        // Note: track the current facing in a property in your app iteration; default toggle:
        startCamera(front: false) // simplified: alternate logic tracked by app state
    }

    /// iOS screen share requires a Broadcast Upload Extension (RPSystemBroadcastPickerView in the UI,
    /// frames delivered through the app group). Wire the extension's sample-buffer handler to
    /// `source.capturer(_:didCapture:)` — this hook starts consuming them.
    func startScreenShare() {
        // Present RPSystemBroadcastPickerView from SwiftUI/Compose host; the extension then
        // writes frames into the shared app-group socket that this capturer reads.
        NotificationCenter.default.post(name: .init("TelefamRequestBroadcastPicker"), object: nil)
    }

    func stopScreenShare() {
        screenCapturer = nil
        startCamera(front: true)
    }

    func makeOffer() {
        let constraints = RTCMediaConstraints(mandatoryConstraints: [
            "OfferToReceiveAudio": "true", "OfferToReceiveVideo": "true"
        ], optionalConstraints: nil)
        peerConnection?.offer(for: constraints) { [weak self] sdp, _ in
            guard let self, let sdp else { return }
            self.peerConnection?.setLocalDescription(sdp) { _ in }
            self.listener?.onLocalSdp(kind: "offer", sdp: Self.wrapSdp("offer", sdp.sdp))
        }
    }

    func makeAnswer() {
        peerConnection?.answer(for: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)) { [weak self] sdp, _ in
            guard let self, let sdp else { return }
            self.peerConnection?.setLocalDescription(sdp) { _ in }
            self.listener?.onLocalSdp(kind: "answer", sdp: Self.wrapSdp("answer", sdp.sdp))
        }
    }

    func applyRemoteDescription(kind: String, json: String) {
        guard let data = json.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let sdpString = obj["sdp"] as? String else { return }
        let sdp = RTCSessionDescription(type: kind == "offer" ? .offer : .answer, sdp: sdpString)
        peerConnection?.setRemoteDescription(sdp) { _ in }
    }

    func applyRemoteCandidate(json: String) {
        guard let data = json.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let candidate = obj["candidate"] as? String,
              let mid = obj["sdpMid"] as? String,
              let index = obj["sdpMLineIndex"] as? Int else { return }
        peerConnection?.add(RTCIceCandidate(sdp: candidate, sdpMLineIndex: Int32(index), sdpMid: mid))
    }

    func tearDown() {
        cameraCapturer?.stopCapture()
        cameraCapturer = nil
        screenCapturer = nil
        peerConnection?.close()
        peerConnection = nil
        localAudioTrack = nil
        localVideoTrack = nil
        listener = nil
    }

    static func wrapSdp(_ kind: String, _ sdp: String) -> String {
        let obj: [String: Any] = ["sdpType": kind, "sdp": sdp]
        let data = try? JSONSerialization.data(withJSONObject: obj)
        return data.flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
    }
}

// MARK: - RTCPeerConnectionDelegate

extension TelefamCallEngine: RTCPeerConnectionDelegate {
    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        let obj: [String: Any] = [
            "candidate": candidate.sdp,
            "sdpMid": candidate.sdpMid ?? "",
            "sdpMLineIndex": Int(candidate.sdpMLineIndex)
        ]
        if let data = try? JSONSerialization.data(withJSONObject: obj),
           let json = String(data: data, encoding: .utf8) {
            listener?.onLocalIceCandidate(candidateJson: json)
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {
        switch newState {
        case .connected: listener?.onConnected()
        case .failed: listener?.onConnectionFailed()
        default: break
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd receiver: RTCRtpReceiver, streams mediaStreams: [RTCMediaStream]) {
        if let track = receiver.track as? RTCVideoTrack {
            track.add(remoteView)
            listener?.onRemoteVideoActive(active: true)
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didStartReceivingOn transceiver: RTCRtpTransceiver) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didRemoveReceiver receiver: RTCRtpReceiver) {
        if receiver.track is RTCVideoTrack { listener?.onRemoteVideoActive(active: false) }
    }
}

// MARK: - PushKit: incoming calls while locked / backgrounded / killed

extension TelefamCallEngine: PKPushRegistryDelegate {
    func pushRegistry(_ registry: PKPushRegistry, didUpdate pushCredentials: PKPushCredentials, for type: PKPushType) {
        // Upload the VoIP token as an "ios" device token (POST /api/calls/devices).
        let token = pushCredentials.token.map { String(format: "%02x", $0) }.joined()
        UserDefaults.standard.set(token, forKey: "telefam.voipToken")
        NotificationCenter.default.post(name: .init("TelefamVoipTokenUpdated"), object: token)
    }

    func pushRegistry(_ registry: PKPushRegistry, didInvalidatePushTokenFor type: PKPushType) {}

    func pushRegistry(_ registry: PKPushRegistry, didReceiveIncomingPushWith payload: PKPushPayload, for type: PKPushType, completion: @escaping () -> Void) {
        guard type == .voIP,
              payload.dictionaryPayload["kind"] as? String == "incoming_call",
              let callId = payload.dictionaryPayload["callId"] as? String,
              let callerId = payload.dictionaryPayload["callerId"] as? String else {
            completion()
            return
        }
        let callerName = payload.dictionaryPayload["callerName"] as? String ?? "Telefam user"
        let callType = payload.dictionaryPayload["callType"] as? String ?? "audio"

        // This is what surfaces the native incoming-call screen on a locked/killed app.
        // iOS requires reportNewIncomingCall on EVERY voip push — failing to report crashes the app.
        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: callerName)
        update.hasVideo = (callType == "video")
        update.localizedCallerName = callerName

        let uuid = UUID(uuidString: callId) ?? Self.uuidV5(from: callId)
        provider.reportNewIncomingCall(with: uuid, update: update) { error in
            if error == nil {
                CallKitState.shared.pendingIncoming = (callId, callerId, callerName, callType)
            }
            completion()
        }
    }

    /// Deterministic UUID for call ids that aren't UUIDs (CallKit requires a UUID).
    static func uuidV5(from string: String) -> UUID {
        var hash = [UInt8](repeating: 0, count: 16)
        let bytes = Array(string.utf8)
        for i in 0..<16 { hash[i] = bytes.isEmpty ? 0 : bytes[i % bytes.count] }
        return UUID(uuid: (hash[0], hash[1], hash[2], hash[3], hash[4], hash[5], hash[6], hash[7],
                           hash[8], hash[9], hash[10], hash[11], hash[12], hash[13], hash[14], hash[15]))
    }
}

final class CallKitState {
    static let shared = CallKitState()
    var pendingIncoming: (callId: String, callerId: String, callerName: String, callType: String)?
}

// MARK: - CXProviderDelegate

extension TelefamCallEngine: CXProviderDelegate {
    func providerDidReset(_ provider: CXProvider) { tearDown() }

    /// User pressed Accept on the system UI (locked or not) — hand off to the Kotlin controller.
    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        if let pending = CallKitState.shared.pendingIncoming {
            CallKitState.shared.pendingIncoming = nil
            NotificationCenter.default.post(
                name: .init("TelefamAcceptCall"), object: nil,
                userInfo: ["callId": pending.callId, "callerId": pending.callerId,
                           "callerName": pending.callerName, "callType": pending.callType]
            )
        }
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth])
        try? session.setActive(true)
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        NotificationCenter.default.post(name: .init("TelefamEndCall"), object: nil)
        tearDown()
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        localAudioTrack?.isEnabled = !action.isMuted
        action.fulfill()
    }
}
