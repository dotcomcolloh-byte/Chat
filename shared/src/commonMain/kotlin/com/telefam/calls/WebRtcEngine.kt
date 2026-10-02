package com.telefam.calls

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Events the platform WebRTC engine reports back to [CallController]. */
interface WebRtcEngineListener {
    /** Locally-created SDP, ready to send to the peer. [kind] is "offer" or "answer". */
    fun onLocalSdp(kind: String, sdp: String)
    /** Locally-discovered ICE candidate, serialized as JSON {sdp, sdpMid, sdpMLineIndex}. */
    fun onLocalIceCandidate(candidateJson: String)
    /** ICE connected — media is flowing. */
    fun onConnected()
    /** ICE failed/disconnected for good — the controller should end the call. */
    fun onConnectionFailed()
    /** Remote video track became active/inactive (peer toggled camera or started/stopped sharing). */
    fun onRemoteVideoActive(active: Boolean)
    /** The remote peer asked us to show their camera-off state explicitly (data-channel hint). */
    fun onRemoteCameraOff(off: Boolean)
}

/**
 * Platform WebRTC engine (actual on Android wraps org.webrtc; on iOS wraps WebRTC.framework
 * through the Swift engine). One instance per call.
 *
 * All methods must be safe to call from any thread; implementations marshal internally.
 */
expect class WebRtcEngine() {
    var listener: WebRtcEngineListener?

    /** ICE servers fetched from GET /api/calls/ice-servers (STUN + optional TURN). */
    fun setIceServers(servers: List<IceServerConfig>)

    /** Starts mic capture and, when [video], the front camera. Idempotent. */
    fun startLocalMedia(video: Boolean)

    fun createOffer()
    fun createAnswer()
    fun setRemoteDescription(kind: String, sdp: String)
    fun addRemoteIceCandidate(candidateJson: String)

    fun setMicMuted(muted: Boolean)

    /** Camera on/off. Off = audio-only send plus a camera-off hint to the peer (avatar shown remotely). */
    fun setCameraEnabled(enabled: Boolean)

    /** Front <-> back camera. No-op while screen sharing or camera disabled. */
    fun switchCamera()

    /** On Android this triggers the MediaProjection consent prompt via the pending-intent hook. */
    fun startScreenShare()
    fun stopScreenShare()

    fun setSpeakerphone(on: Boolean)

    /** Tears down tracks, capturers and the peer connection. The instance is unusable afterwards. */
    fun close()
}

/** Full-bleed remote video (or the shared screen). Render nothing when the peer's camera is off. */
@Composable
expect fun RemoteVideoView(engine: WebRtcEngine, modifier: Modifier = Modifier)

/** Picture-in-picture local preview (camera or shared screen). */
@Composable
expect fun LocalVideoView(engine: WebRtcEngine, modifier: Modifier = Modifier)
