package com.telefam.calls

/**
 * iOS-side implementation of the WebRTC engine, provided by Swift (iosApp/Calls/TelefamCallEngine.swift)
 * because WebRTC.framework ships as an ObjC framework that is far more natural to drive from Swift.
 * The Swift side registers an instance here at app launch; every method below just forwards.
 */
interface IosCallBridge {
    fun setListener(listener: WebRtcEngineListener?)
    fun setIceServers(servers: List<IceServerConfig>)
    fun startLocalMedia(video: Boolean)
    fun createOffer()
    fun createAnswer()
    fun setRemoteDescription(kind: String, sdp: String)
    fun addRemoteIceCandidate(candidateJson: String)
    fun setMicMuted(muted: Boolean)
    fun setCameraEnabled(enabled: Boolean)
    fun switchCamera()
    fun startScreenShare()
    fun stopScreenShare()
    fun setSpeakerphone(on: Boolean)
    fun close()
}

object IosCallBridgeRegistry {
    var bridge: IosCallBridge? = null
}

actual class WebRtcEngine actual constructor() {
    private val bridge: IosCallBridge?
        get() = IosCallBridgeRegistry.bridge

    actual var listener: WebRtcEngineListener? = null
        set(value) {
            field = value
            bridge?.setListener(value)
        }

    actual fun setIceServers(servers: List<IceServerConfig>) = bridge?.setIceServers(servers) ?: Unit
    actual fun startLocalMedia(video: Boolean) = bridge?.startLocalMedia(video) ?: Unit
    actual fun createOffer() = bridge?.createOffer() ?: Unit
    actual fun createAnswer() = bridge?.createAnswer() ?: Unit
    actual fun setRemoteDescription(kind: String, sdp: String) = bridge?.setRemoteDescription(kind, sdp) ?: Unit
    actual fun addRemoteIceCandidate(candidateJson: String) = bridge?.addRemoteIceCandidate(candidateJson) ?: Unit
    actual fun setMicMuted(muted: Boolean) = bridge?.setMicMuted(muted) ?: Unit
    actual fun setCameraEnabled(enabled: Boolean) = bridge?.setCameraEnabled(enabled) ?: Unit
    actual fun switchCamera() = bridge?.switchCamera() ?: Unit
    actual fun startScreenShare() = bridge?.startScreenShare() ?: Unit
    actual fun stopScreenShare() = bridge?.stopScreenShare() ?: Unit
    actual fun setSpeakerphone(on: Boolean) = bridge?.setSpeakerphone(on) ?: Unit
    actual fun close() = bridge?.close() ?: Unit
}
