package com.telefam.calls

import kotlinx.serialization.Serializable

/** Mirrors the backend's CallFrame — signaling metadata only, never media. */
@Serializable
data class CallFrame(
    val type: String,
    val callId: String? = null,
    val to: String? = null,
    val from: String? = null,
    val callType: String? = null,
    val callerName: String? = null,
    val payload: String? = null,
    val reason: String? = null
)

enum class CallType(val wire: String) {
    VIDEO("video"), AUDIO("audio");

    companion object {
        fun fromWire(value: String?): CallType = if (value == "video") VIDEO else AUDIO
    }
}

/** Why a call ended; drives the toast/label the UI shows afterwards. */
enum class CallEndReason(val wire: String) {
    COMPLETED("completed"),
    REJECTED("rejected"),
    CANCELLED("cancelled"),
    BUSY("busy"),
    FAILED("failed"),
    TIMEOUT("timeout"),
    LOCAL_HANGUP("local");

    companion object {
        fun fromWire(value: String?): CallEndReason = entries.firstOrNull { it.wire == value } ?: FAILED
    }
}

@Serializable
data class IceServerConfig(val urls: List<String>, val username: String? = null, val credential: String? = null)

@Serializable
data class IceConfigResponse(val iceServers: List<IceServerConfig>, val ttlSeconds: Long = 86_400)

/** Snapshot the Compose UI renders. */
data class CallUiState(
    val phase: CallPhase = CallPhase.IDLE,
    val callId: String? = null,
    val callType: CallType = CallType.VIDEO,
    /** The other participant. */
    val peerId: String = "",
    val peerName: String = "",
    val peerAvatarUrl: String? = null,
    val connectedAtMillis: Long? = null,
    val micMuted: Boolean = false,
    val cameraOn: Boolean = true,
    /** Remote side's camera state — when false the UI shows the peer avatar instead of video. */
    val remoteVideoOn: Boolean = false,
    val screenSharing: Boolean = false,
    val frontCamera: Boolean = true,
    val speakerOn: Boolean = false,
    val endReason: CallEndReason? = null
)

enum class CallPhase { IDLE, OUTGOING_RINGING, INCOMING_RINGING, CONNECTING, ACTIVE, ENDED }
