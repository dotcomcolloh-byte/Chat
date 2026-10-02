package com.telefam.calls

import com.telefam.data.api.ApiConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.random.Random

/**
 * 1:1 call state machine. Owns the signaling socket listener, the platform WebRTC engine
 * and the local ringtone, and exposes a single [state] flow for the Compose UI.
 *
 * Signaling sequence:
 *   caller: invite -> (ringing) -> offer ........... (ICE trickle both ways)
 *   callee:        <- invite ................ <- offer -> answer
 *
 * The backend only relays these frames; media stays peer-to-peer.
 */
class CallController(
    private val signaling: CallSignalingClient,
    private val httpClient: HttpClient,
    private val currentUserId: () -> String?,
    /** Resolve a user id to (displayName, avatarUrl) for the incoming-call UI. */
    private val resolvePeer: suspend (String) -> Pair<String, String?>
) : WebRtcEngineListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(CallUiState())
    val state: StateFlow<CallUiState> = _state.asStateFlow()

    private var engine: WebRtcEngine? = null
    private var collectJob: Job? = null
    private var iceServers: List<IceServerConfig> = emptyList()
    /** ICE candidates that arrived before the remote description — applied right after it. */
    private val pendingCandidates = mutableListOf<String>()
    @Volatile private var hasRemoteDescription = false
    /** True while we are the callee and the call arrived via push with no socket invite yet. */
    @Volatile private var pushPendingInvite: CallFrame? = null

    /** The live engine (for the Compose video views). Null outside of a call. */
    fun currentEngine(): WebRtcEngine? = engine

    fun start() {
        if (collectJob?.isActive == true) return
        signaling.connect()
        collectJob = scope.launch {
            signaling.frames.collect { frame -> handleFrame(frame) }
        }
        scope.launch { runCatching { iceServers = fetchIceServers() } }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
        endLocal(CallEndReason.LOCAL_HANGUP, notify = false)
    }

    // --- Outgoing call ----------------------------------------------------------------

    fun startOutgoingCall(peerId: String, peerName: String, peerAvatarUrl: String?, type: CallType) {
        if (_state.value.phase != CallPhase.IDLE) return
        val callId = randomUuid()
        _state.value = CallUiState(
            phase = CallPhase.OUTGOING_RINGING,
            callId = callId, callType = type,
            peerId = peerId, peerName = peerName, peerAvatarUrl = peerAvatarUrl,
            cameraOn = type == CallType.VIDEO
        )
        ensureEngine(type)
        signaling.send(
            CallFrame(
                type = "invite", callId = callId, to = peerId,
                callType = type.wire, callerName = currentUserId() ?: ""
            )
        )
        // Ringback is generated on-device — the backend never streams audio.
        RingtonePlayer.startOutgoing()
    }

    // --- Incoming call ----------------------------------------------------------------

    /** Entry point used by the push handler (app was killed/locked) once the UI is up. */
    fun attachIncomingFromPush(callId: String, callerId: String, callerName: String, type: CallType) {
        if (_state.value.phase != CallPhase.IDLE) return
        _state.value = CallUiState(
            phase = CallPhase.INCOMING_RINGING,
            callId = callId, callType = type,
            peerId = callerId, peerName = callerName, cameraOn = type == CallType.VIDEO
        )
        pushPendingInvite = CallFrame(type = "invite", callId = callId, from = callerId, callType = type.wire)
        RingtonePlayer.startIncoming()
    }

    fun acceptIncoming() {
        val s = _state.value
        if (s.phase != CallPhase.INCOMING_RINGING) return
        RingtonePlayer.stop()
        _state.update { it.copy(phase = CallPhase.CONNECTING) }
        ensureEngine(s.callType)
        signaling.send(CallFrame(type = "accept", callId = s.callId, to = s.peerId))
        CallServiceBridge.onCallStarted(s.callId ?: "", s.peerName, s.callType == CallType.VIDEO)
    }

    fun rejectIncoming() {
        val s = _state.value
        if (s.phase != CallPhase.INCOMING_RINGING) return
        signaling.send(CallFrame(type = "reject", callId = s.callId, to = s.peerId))
        endLocal(CallEndReason.REJECTED, notify = false)
    }

    // --- In-call controls --------------------------------------------------------------

    fun hangUp() {
        val s = _state.value
        when (s.phase) {
            CallPhase.OUTGOING_RINGING -> signaling.send(CallFrame(type = "cancel", callId = s.callId, to = s.peerId))
            CallPhase.CONNECTING, CallPhase.ACTIVE -> signaling.send(CallFrame(type = "end", callId = s.callId, to = s.peerId))
            else -> {}
        }
        endLocal(CallEndReason.LOCAL_HANGUP, notify = false)
    }

    fun toggleMic() {
        val muted = !_state.value.micMuted
        engine?.setMicMuted(muted)
        _state.update { it.copy(micMuted = muted) }
    }

    fun toggleCamera() {
        val on = !_state.value.cameraOn
        engine?.setCameraEnabled(on)
        _state.update { it.copy(cameraOn = on, screenSharing = false) }
        // Explicit hint so the peer swaps video <-> avatar instantly (track state lags slightly).
        val s = _state.value
        signaling.send(CallFrame(type = "signal", callId = s.callId, to = s.peerId, payload = "{\"cameraOff\":${!on}}"))
    }

    fun flipCamera() = engine?.switchCamera()

    fun toggleScreenShare() {
        if (_state.value.screenSharing) {
            engine?.stopScreenShare()
            _state.update { it.copy(screenSharing = false) }
        } else {
            engine?.startScreenShare()
            _state.update { it.copy(screenSharing = true) }
        }
    }

    fun toggleSpeaker() {
        val on = !_state.value.speakerOn
        engine?.setSpeakerphone(on)
        _state.update { it.copy(speakerOn = on) }
    }

    // --- Signaling frames ---------------------------------------------------------------

    private fun handleFrame(frame: CallFrame) {
        when (frame.type) {
            "invite" -> onInvite(frame)
            "accept" -> onAccepted(frame)
            "reject", "busy", "cancel" -> {
                if (frame.callId != _state.value.callId) return
                endLocal(CallEndReason.fromWire(frame.reason ?: frame.type), notify = false)
            }
            "end" -> {
                if (frame.callId != _state.value.callId) return
                endLocal(CallEndReason.fromWire(frame.reason), notify = false)
            }
            "ringing-push" -> { /* callee is being woken by push — keep ringing */ }
            "signal" -> onSignal(frame)
        }
    }

    private fun onInvite(frame: CallFrame) {
        val callerId = frame.from ?: return
        val current = _state.value
        // Same call arriving over the socket after a push wake — just refresh the display name.
        if (current.phase == CallPhase.INCOMING_RINGING && current.callId == frame.callId) return
        if (current.phase != CallPhase.IDLE) {
            // Genuinely busy in another call — tell the other caller immediately.
            signaling.send(CallFrame(type = "busy", callId = frame.callId, to = callerId))
            return
        }
        val type = CallType.fromWire(frame.callType)
        _state.value = CallUiState(
            phase = CallPhase.INCOMING_RINGING,
            callId = frame.callId, callType = type,
            peerId = callerId, peerName = "…",
            cameraOn = type == CallType.VIDEO
        )
        RingtonePlayer.startIncoming()
        scope.launch {
            val (name, avatar) = runCatching { resolvePeer(callerId) }.getOrDefault("Telefam user" to null)
            _state.update { it.copy(peerName = name, peerAvatarUrl = avatar) }
        }
    }

    private fun onAccepted(frame: CallFrame) {
        val s = _state.value
        if (frame.callId != s.callId || s.phase != CallPhase.OUTGOING_RINGING) return
        RingtonePlayer.stop()
        _state.update { it.copy(phase = CallPhase.CONNECTING) }
        CallServiceBridge.onCallStarted(s.callId ?: "", s.peerName, s.callType == CallType.VIDEO)
        engine?.createOffer()
    }

    private fun onSignal(frame: CallFrame) {
        if (frame.callId != _state.value.callId) return
        val payload = frame.payload ?: return
        val e = engine ?: return
        when {
            payload.startsWith("{\"sdpType\":\"offer\"") -> {
                e.setRemoteDescription("offer", payload)
                flushPendingCandidates()
                e.createAnswer()
            }
            payload.startsWith("{\"sdpType\":\"answer\"") -> {
                e.setRemoteDescription("answer", payload)
                flushPendingCandidates()
            }
            payload.startsWith("{\"candidate\"") -> {
                if (hasRemoteDescription) e.addRemoteIceCandidate(payload) else pendingCandidates += payload
            }
            // Camera-off hint carried over signaling so the peer can show our avatar instantly
            // (in addition to track-state detection, which lags by a frame or two).
            payload.startsWith("{\"cameraOff\"") -> {
                val off = payload.contains("true")
                _state.update { it.copy(remoteVideoOn = if (off) false else it.remoteVideoOn) }
            }
        }
    }

    // --- WebRtcEngineListener -------------------------------------------------------------

    override fun onLocalSdp(kind: String, sdp: String) {
        val s = _state.value
        signaling.send(CallFrame(type = "signal", callId = s.callId, to = s.peerId, payload = sdp))
    }

    override fun onLocalIceCandidate(candidateJson: String) {
        val s = _state.value
        signaling.send(CallFrame(type = "signal", callId = s.callId, to = s.peerId, payload = candidateJson))
    }

    override fun onConnected() {
        RingtonePlayer.stop()
        _state.update { it.copy(phase = CallPhase.ACTIVE, connectedAtMillis = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()) }
    }

    override fun onConnectionFailed() = endLocal(CallEndReason.FAILED, notify = true)

    override fun onRemoteVideoActive(active: Boolean) {
        _state.update { it.copy(remoteVideoOn = active) }
    }

    override fun onRemoteCameraOff(off: Boolean) {
        if (off) _state.update { it.copy(remoteVideoOn = false) }
    }

    // --- Internals -----------------------------------------------------------------------

    private fun ensureEngine(type: CallType) {
        if (engine != null) return
        val e = WebRtcEngine()
        e.listener = this
        e.setIceServers(iceServers.ifEmpty { listOf(IceServerConfig(urls = listOf("stun:stun.l.google.com:19302"))) })
        e.startLocalMedia(video = type == CallType.VIDEO)
        engine = e
    }

    private fun flushPendingCandidates() {
        hasRemoteDescription = true
        val e = engine ?: return
        val queued = pendingCandidates.toList()
        pendingCandidates.clear()
        queued.forEach { e.addRemoteIceCandidate(it) }
    }

    private fun endLocal(reason: CallEndReason, notify: Boolean) {
        val s = _state.value
        if (s.phase == CallPhase.IDLE) return
        if (notify && s.callId != null) {
            signaling.send(CallFrame(type = "end", callId = s.callId, to = s.peerId, reason = reason.wire))
        }
        RingtonePlayer.stop()
        CallServiceBridge.onCallEnded()
        engine?.listener = null
        engine?.close()
        engine = null
        hasRemoteDescription = false
        pendingCandidates.clear()
        pushPendingInvite = null
        _state.value = s.copy(phase = CallPhase.ENDED, endReason = reason)
        // Back to IDLE shortly after, so the UI can show the end reason first.
        scope.launch {
            delay(1_500)
            if (_state.value.phase == CallPhase.ENDED) _state.value = CallUiState()
        }
    }

    private suspend fun fetchIceServers(): List<IceServerConfig> {
        val base = ApiConfig.baseUrl.trimEnd('/')
        return httpClient.get("$base/api/calls/ice-servers").body<IceConfigResponse>().iceServers
    }

    companion object {
        fun randomUuid(): String {
            val r = Random
            val chars = "0123456789abcdef"
            fun block(n: Int) = (1..n).joinToString("") { chars[r.nextInt(16)].toString() }
            return "${block(8)}-${block(4)}-${block(4)}-${block(4)}-${block(12)}"
        }
    }
}
