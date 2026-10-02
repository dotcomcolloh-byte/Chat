package com.telefam.calls

import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Call signaling wire format. The server relays these frames verbatim between the two
 * call participants — SDP offers/answers and ICE candidates ride inside [payload] and are
 * opaque to the backend. WebRTC media (audio/video/screen share) NEVER touches this server;
 * it flows peer-to-peer over DTLS-SRTP. The ringtone is likewise never streamed — each
 * device plays its own bundled local resource.
 */
@Serializable
data class CallFrame(
    val type: String,
    val callId: String? = null,
    val to: String? = null,
    val from: String? = null,
    /** "video" | "audio" */
    val callType: String? = null,
    /** Display name shown on the incoming-call UI (set on invite). */
    val callerName: String? = null,
    /** Opaque WebRTC payload: SDP offer/answer or a serialized ICE candidate. */
    val payload: String? = null,
    /** Machine-readable end reason: completed | rejected | cancelled | busy | failed | timeout. */
    val reason: String? = null
)

/** Active call between two users, identified by the caller-generated [CallRoom.id]. */
private data class CallRoom(
    val id: String,
    val callerId: UUID,
    val calleeId: UUID,
    val callType: String,
    val startedAtMillis: Long
)

/**
 * Signaling hub for 1:1 WebRTC calls.
 *
 * Responsibilities (metadata only):
 *  - deliver invites, ringing state, accept/reject/cancel/end;
 *  - relay SDP / ICE between exactly the two participants of a call;
 *  - enforce one active call per user (a second invite gets "busy");
 *  - hand off to [CallPushNotifier] when the callee has no live call socket, so the
 *    call still rings on a locked, backgrounded or fully closed app;
 *  - time out unanswered invites so neither side rings forever.
 */
class CallSignalingService(private val pushNotifier: CallPushNotifier) {
    private class Conn(val userId: UUID, val session: WebSocketSession)

    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }
    private val conns = ConcurrentHashMap<UUID, CopyOnWriteArraySet<Conn>>()
    private val activeByCallId = ConcurrentHashMap<String, CallRoom>()
    private val activeByUser = ConcurrentHashMap<UUID, String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isConnected(userId: UUID): Boolean = conns.containsKey(userId)

    suspend fun join(userId: UUID, session: WebSocketSession) {
        conns.getOrPut(userId) { CopyOnWriteArraySet() } += Conn(userId, session)
    }

    suspend fun leave(userId: UUID, session: WebSocketSession) {
        val set = conns[userId]
        set?.removeIf { it.session === session }
        if (set != null && set.isEmpty()) conns.remove(userId)
        // If this user vanished mid-call (network drop), end the call for the other side.
        activeByUser[userId]?.let { callId ->
            activeByCallId[callId]?.let { room ->
                endRoom(room, reason = "failed", notify = otherOf(room, userId))
            }
        }
    }

    suspend fun handleClientMessage(userId: UUID, text: String) {
        val frame = runCatching { json.decodeFromString<CallFrame>(text) }.getOrNull() ?: return
        when (frame.type) {
            "invite" -> handleInvite(userId, frame)
            "accept" -> relayInCall(userId, frame)
            "reject" -> {
                relayInCall(userId, frame.copy(reason = frame.reason ?: "rejected"))
                frame.callId?.let { id -> activeByCallId.remove(id)?.let { teardown(it) } }
            }
            "cancel" -> {
                relayInCall(userId, frame.copy(reason = frame.reason ?: "cancelled"))
                frame.callId?.let { id -> activeByCallId.remove(id)?.let { teardown(it) } }
            }
            "busy" -> {
                relayInCall(userId, frame.copy(reason = "busy"))
                frame.callId?.let { id -> activeByCallId.remove(id)?.let { teardown(it) } }
            }
            "end" -> {
                relayInCall(userId, frame.copy(reason = frame.reason ?: "completed"))
                frame.callId?.let { id -> activeByCallId.remove(id)?.let { teardown(it) } }
            }
            // offer / answer / candidate — pure relay between the two participants.
            "signal" -> relayInCall(userId, frame)
        }
    }

    private fun handleInvite(callerId: UUID, frame: CallFrame) {
        val calleeId = frame.to?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return
        val callId = frame.callId ?: return
        val callType = if (frame.callType == "video") "video" else "audio"
        if (calleeId == callerId) return

        // The official Telefam account never takes or places calls.
        if (calleeId == com.telefam.official.OfficialAccountService.idOrNull() ||
            callerId == com.telefam.official.OfficialAccountService.idOrNull()) {
            sendTo(callerId, frame.copy(type = "busy", from = calleeId.toString(), to = null, reason = "unavailable"))
            return
        }

        // One active call per user — a second caller gets a busy signal, not a queue.
        if (activeByUser.containsKey(calleeId) || activeByUser.containsKey(callerId)) {
            sendTo(callerId, frame.copy(type = "busy", from = calleeId.toString(), to = null, reason = "busy"))
            return
        }

        val room = CallRoom(callId, callerId, calleeId, callType, System.currentTimeMillis())
        activeByCallId[callId] = room
        activeByUser[callerId] = callId
        activeByUser[calleeId] = callId

        val invite = frame.copy(
            from = callerId.toString(),
            to = null,
            callType = callType
        )
        if (isConnected(calleeId)) {
            sendTo(calleeId, invite)
        } else {
            // Locked / backgrounded / killed: the OS must wake the app via a high-priority
            // push so the full-screen incoming-call UI (ConnectionService / CallKit) shows.
            scope.launch {
                runCatching {
                    pushNotifier.notifyIncomingCall(
                        calleeId = calleeId,
                        callId = callId,
                        callType = callType,
                        callerId = callerId,
                        callerName = frame.callerName ?: "Telefam user"
                    )
                }
            }
            // Let the caller keep ringing — the callee may take a few seconds to wake.
            sendTo(callerId, CallFrame(type = "ringing-push", callId = callId, from = calleeId.toString()))
        }

        // Safety net: unanswered invites expire so no device rings forever.
        scope.launch {
            kotlinx.coroutines.delay(RING_TIMEOUT_MS)
            val current = activeByCallId[callId] ?: return@launch
            if (current.startedAtMillis != room.startedAtMillis) return@launch
            endRoom(current, reason = "timeout", notify = current.callerId, alsoNotify = current.calleeId)
        }
    }

    /** Relays a frame to the OTHER participant of the room identified by callId, if the sender belongs to it. */
    private fun relayInCall(userId: UUID, frame: CallFrame) {
        val callId = frame.callId ?: return
        val room = activeByCallId[callId] ?: return
        if (userId != room.callerId && userId != room.calleeId) return
        val other = otherOf(room, userId)
        sendTo(other, frame.copy(from = userId.toString(), to = null))
    }

    private fun otherOf(room: CallRoom, userId: UUID): UUID =
        if (room.callerId == userId) room.calleeId else room.callerId

    private fun endRoom(room: CallRoom, reason: String, notify: UUID, alsoNotify: UUID? = null) {
        activeByCallId.remove(room.id)
        teardown(room)
        sendTo(notify, CallFrame(type = "end", callId = room.id, reason = reason))
        alsoNotify?.let { sendTo(it, CallFrame(type = "end", callId = room.id, reason = reason)) }
    }

    private fun teardown(room: CallRoom) {
        activeByUser.remove(room.callerId, room.id)
        activeByUser.remove(room.calleeId, room.id)
    }

    private fun sendTo(userId: UUID, frame: CallFrame) {
        val targets = conns[userId]?.toList() ?: return
        val text = json.encodeToString(frame)
        for (c in targets) scope.launch { runCatching { c.session.send(Frame.Text(text)) } }
    }

    private companion object {
        const val RING_TIMEOUT_MS = 45_000L
    }
}
