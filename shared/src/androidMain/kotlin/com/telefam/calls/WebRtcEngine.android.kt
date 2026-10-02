package com.telefam.calls

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import org.json.JSONObject
import org.webrtc.*

/**
 * Android WebRTC engine. Media is strictly peer-to-peer over DTLS-SRTP; only SDP/ICE
 * metadata goes through the signaling socket. Threading: everything is marshalled onto
 * a single worker thread per WebRTC best practice.
 */
actual class WebRtcEngine actual constructor() {

    actual var listener: WebRtcEngineListener? = null

    private val appContext: Context = AndroidCallContext.appContext

    private lateinit var factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var iceServers: List<IceServerConfig> = emptyList()

    private var audioSource: AudioSource? = null
    private var videoSource: VideoSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null

    private var cameraCapturer: CameraVideoCapturer? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var localSink: VideoSink? = null
    private var remoteSink: VideoSink? = null
    private var remoteVideoTrack: VideoTrack? = null

    @Volatile private var videoCall = true
    @Volatile private var sharingScreen = false
    @Volatile private var closed = false

    private val thread = android.os.HandlerThread("telefam-webrtc").apply { start() }
    private val handler = android.os.Handler(thread.looper)

    private fun post(block: () -> Unit) {
        if (closed) return
        handler.post {
            if (!closed) runCatching { block() }
        }
    }

    actual fun setIceServers(servers: List<IceServerConfig>) {
        iceServers = servers
    }

    actual fun startLocalMedia(video: Boolean) {
        videoCall = video
        post {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            val encoderFactory = DefaultVideoEncoderFactory(
                rootEglBase.eglBaseContext, /* enableIntelVp8Encoder */ true, /* enableH264HighProfile */ true
            )
            val decoderFactory = DefaultVideoDecoderFactory(rootEglBase.eglBaseContext)
            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()

            // Mic
            audioSource = factory.createAudioSource(MediaConstraints())
            localAudioTrack = factory.createAudioTrack("audio0", audioSource)

            // Front camera by default (or nothing for a voice call — camera starts on toggle).
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase.eglBaseContext)
            videoSource = factory.createVideoSource(false)
            if (video) startCameraCapture(front = true)

            createPeerConnection()
        }
    }

    private fun startCameraCapture(front: Boolean) {
        val enumerator = Camera2Enumerator(appContext)
        val deviceName = enumerator.deviceNames.firstOrNull { name ->
            if (front) enumerator.isFrontFacing(name) else enumerator.isBackFacing(name)
        } ?: enumerator.deviceNames.firstOrNull() ?: return
        val capturer = enumerator.createCapturer(deviceName, null) ?: return
        capturer.initialize(surfaceTextureHelper, appContext, videoSource!!.capturerObserver)
        capturer.startCapture(1280, 720, 24)
        cameraCapturer?.let { old ->
            runCatching { old.stopCapture(); old.dispose() }
        }
        cameraCapturer = capturer
        if (localVideoTrack == null) {
            localVideoTrack = factory.createVideoTrack("video0", videoSource)
            peerConnection?.addTrack(localVideoTrack, listOf("stream0"))
            localSink?.let { localVideoTrack?.addSink(it) }
        }
    }

    private fun createPeerConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(
            iceServers.map {
                PeerConnection.IceServer.builder(it.urls)
                    .setUsername(it.username ?: "")
                    .setPassword(it.credential ?: "")
                    .createIceServer()
            }
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                val json = JSONObject()
                    .put("candidate", candidate.sdp)
                    .put("sdpMid", candidate.sdpMid)
                    .put("sdpMLineIndex", candidate.sdpMLineIndex)
                    .toString()
                listener?.onLocalIceCandidate(json)
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> listener?.onConnected()
                    PeerConnection.PeerConnectionState.FAILED -> listener?.onConnectionFailed()
                    else -> {}
                }
            }

            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                (receiver.track() as? VideoTrack)?.let { track ->
                    remoteVideoTrack = track
                    remoteSink?.let { track.addSink(it) }
                    listener?.onRemoteVideoActive(true)
                }
            }

            override fun onRemoveTrack(receiver: RtpReceiver) {
                if ((receiver.track() as? VideoTrack) != null) listener?.onRemoteVideoActive(false)
            }

            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        })

        // Add tracks BEFORE the offer so the SDP advertises them.
        peerConnection?.addTrack(localAudioTrack, listOf("stream0"))
        localVideoTrack?.let { peerConnection?.addTrack(it, listOf("stream0")) }
    }

    actual fun createOffer() = post {
        peerConnection?.createOffer(sdpObserver { sdp ->
            peerConnection?.setLocalDescription(sdpObserver {}, sdp)
            listener?.onLocalSdp("offer", wrapSdp("offer", sdp.description))
        }, MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        })
    }

    actual fun createAnswer() = post {
        peerConnection?.createAnswer(sdpObserver { sdp ->
            peerConnection?.setLocalDescription(sdpObserver {}, sdp)
            listener?.onLocalSdp("answer", wrapSdp("answer", sdp.description))
        }, MediaConstraints())
    }

    actual fun setRemoteDescription(kind: String, sdp: String) = post {
        val desc = SessionDescription(
            if (kind == "offer") SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER,
            unwrapSdp(sdp)
        )
        peerConnection?.setRemoteDescription(sdpObserver {}, desc)
    }

    actual fun addRemoteIceCandidate(candidateJson: String) = post {
        runCatching {
            val obj = JSONObject(candidateJson)
            peerConnection?.addIceCandidate(
                IceCandidate(obj.getString("sdpMid"), obj.getInt("sdpMLineIndex"), obj.getString("candidate"))
            )
        }
    }

    actual fun setMicMuted(muted: Boolean) {
        post { localAudioTrack?.setEnabled(!muted) }
    }

    actual fun setCameraEnabled(enabled: Boolean) {
        post {
            if (enabled && cameraCapturer == null && !sharingScreen) startCameraCapture(front = true)
            localVideoTrack?.setEnabled(enabled)
            if (!enabled && sharingScreen) stopScreenShare()
        }
    }

    actual fun switchCamera() {
        post { if (!sharingScreen) cameraCapturer?.switchCamera(null) }
    }

    actual fun startScreenShare() {
        // The MediaProjection consent dialog must come from an Activity — the host wires
        // [screenSharePermissionRequester]; on grant it calls [onScreenSharePermissionResult].
        screenSharePermissionRequester?.invoke()
    }

    /** Called by the host Activity with the MediaProjection consent result. */
    fun onScreenSharePermissionResult(resultCode: Int, data: Intent?) {
        if (data == null) return
        startScreenShareService?.invoke() // FGS must be up before the projection starts (Android 14+)
        post {
            sharingScreen = true
            cameraCapturer?.let { runCatching { it.stopCapture() } }
            val capturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
                override fun onStop() {
                    post { if (sharingScreen) stopScreenShare() }
                }
            })
            screenCapturer = capturer
            capturer.initialize(surfaceTextureHelper, appContext, videoSource!!.capturerObserver)
            capturer.startCapture(1280, 720, 15)
            localVideoTrack?.setEnabled(true)
        }
    }

    actual fun stopScreenShare() {
        post {
            sharingScreen = false
            screenCapturer?.let { runCatching { it.stopCapture(); it.dispose() } }
            screenCapturer = null
            // Return to the camera if the user still wants video.
            startCameraCapture(front = true)
        }
    }

    actual fun setSpeakerphone(on: Boolean) {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        am.isSpeakerphoneOn = on
    }

    actual fun close() {
        if (closed) return
        closed = true
        if (activeEngine === this) activeEngine = null
        handler.post {
            runCatching {
                remoteVideoTrack?.let { t -> remoteSink?.let(t::removeSink) }
                localVideoTrack?.let { t -> localSink?.let(t::removeSink) }
                cameraCapturer?.stopCapture(); cameraCapturer?.dispose()
                screenCapturer?.stopCapture(); screenCapturer?.dispose()
                peerConnection?.close(); peerConnection?.dispose()
                audioSource?.dispose(); videoSource?.dispose()
                surfaceTextureHelper?.dispose()
                factory.dispose()
            }
            thread.quitSafely()
        }
    }

    // --- Video sink wiring (used by the Compose views below) ---

    internal fun attachLocalSink(sink: VideoSink) {
        post { localSink = sink; localVideoTrack?.addSink(sink) }
    }

    internal fun detachLocalSink(sink: VideoSink) {
        post { if (localSink === sink) localSink = null; localVideoTrack?.removeSink(sink) }
    }

    internal fun attachRemoteSink(sink: VideoSink) {
        post { remoteSink = sink; remoteVideoTrack?.addSink(sink) }
    }

    internal fun detachRemoteSink(sink: VideoSink) {
        post { if (remoteSink === sink) remoteSink = null; remoteVideoTrack?.removeSink(sink) }
    }

    private fun wrapSdp(kind: String, sdp: String): String =
        JSONObject().put("sdpType", kind).put("sdp", sdp).toString()

    private fun unwrapSdp(json: String): String =
        runCatching { JSONObject(json).getString("sdp") }.getOrDefault(json)

    private inline fun sdpObserver(crossinline onSuccess: (SessionDescription) -> Unit = {}) =
        object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription) = onSuccess(p0)
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }

    companion object {
        /** Shared EGL context for all capturers/renderers of the process. */
        val rootEglBase: EglBase by lazy { EglBase.create() }

        /** Set by the host Activity: launches the MediaProjection consent dialog. */
        @Volatile var screenSharePermissionRequester: (() -> Unit)? = null

        /** Set by the host: starts the FGS with mediaProjection type (required on Android 14+ before capture). */
        @Volatile var startScreenShareService: (() -> Unit)? = null

        /** Last engine instance — the host routes the consent result back through this. */
        @Volatile var activeEngine: WebRtcEngine? = null
    }

    init {
        activeEngine = this
    }
}
