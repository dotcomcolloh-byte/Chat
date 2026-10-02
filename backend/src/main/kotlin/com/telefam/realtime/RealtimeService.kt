package com.telefam.realtime

import com.telefam.privacy.PresenceService
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

/** Server -> client frames. Presence/typing are metadata only; message content NEVER crosses this channel. */
@Serializable
data class RealtimeFrame(
    val type: String,
    val userId: String? = null,
    val online: Boolean? = null,
    val lastSeenEpochMillis: Long? = null,
    /** presence only: true = the viewer is NOT allowed to see this user's status (client must show nothing). */
    val hidden: Boolean? = null,
    val from: String? = null
)

/**
 * Client -> server frames:
 *  - typing: ephemeral typing signal for a specific peer ([to]);
 *  - watch:  the set of users whose presence this connection wants ([ids], replaces the previous set);
 *  - state:  whether the app is in the foreground ([active]); a backgrounded app does not count as online.
 */
@Serializable
data class ClientFrame(
    val type: String,
    val to: String? = null,
    val ids: List<String>? = null,
    val active: Boolean? = null
)

/**
 * Realtime hub: WebSocket sessions per user, privacy-aware presence, typing relay and "mailbox changed"
 * hints so clients pull their E2EE inbox immediately instead of waiting for the next polling tick.
 *
 * Presence model:
 *  - a user is ONLINE while at least one of their connections is in the foreground;
 *  - going offline stamps last-seen (kept in memory and persisted, so it survives restarts);
 *  - clients receive presence only for users they explicitly watch, and only when [PresenceService] allows it.
 *
 * Everything here is metadata-only by design - message payloads still travel exclusively as Signal ciphertext.
 */
class RealtimeService(private val presence: PresenceService) {
    private class Conn(val userId: UUID, val session: WebSocketSession) {
        @Volatile var active: Boolean = true
        @Volatile var watching: Set<UUID> = emptySet()
    }

    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }
    private val conns = ConcurrentHashMap<UUID, CopyOnWriteArraySet<Conn>>()
    private val lastSeenCache = ConcurrentHashMap<UUID, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    fun isOnline(userId: UUID): Boolean = conns[userId]?.any { it.active } == true

    suspend fun join(userId: UUID, session: WebSocketSession) {
        applyAndPublish(userId) { conns.getOrPut(userId) { CopyOnWriteArraySet() } += Conn(userId, session) }
    }

    suspend fun leave(userId: UUID, session: WebSocketSession) {
        applyAndPublish(userId) {
            val set = conns[userId] ?: return@applyAndPublish
            set.removeIf { it.session === session }
            if (set.isEmpty()) conns.remove(userId)
        }
    }

    suspend fun handleClientMessage(userId: UUID, session: WebSocketSession, text: String) {
        val frame = runCatching { json.decodeFromString<ClientFrame>(text) }.getOrNull() ?: return
        val conn = conns[userId]?.firstOrNull { it.session === session } ?: return
        when (frame.type) {
            "typing" -> {
                val target = frame.to?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return
                // Typing is only relayed to someone who is allowed to see this user's activity.
                if (presence.canSee(target, userId)) sendTo(target, RealtimeFrame("typing", from = userId.toString()))
            }
            "state" -> {
                val active = frame.active ?: return
                applyAndPublish(userId) { conn.active = active }
            }
            "watch" -> {
                val ids = frame.ids.orEmpty().asSequence().take(MAX_WATCH)
                    .mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
                    .filter { it != userId }.toSet()
                conn.watching = ids
                // Snapshot: tell the client the current (permitted) status of everyone it just subscribed to.
                for (target in ids) runCatching { conn.session.sendJson(frameFor(userId, target)) }
            }
        }
    }

    /** A user changed a privacy setting that affects who may see their status: re-evaluate every watcher right away. */
    suspend fun onPrivacyChanged(userId: UUID) {
        presence.invalidate(userId)
        publishPresence(userId)
    }

    /** Called after a new envelope is stored: nudge the recipient to pull their mailbox now. */
    fun notifyMailbox(recipientId: UUID) {
        sendTo(recipientId, RealtimeFrame("mailbox"))
    }

    // --- presence internals ----------------------------------------------------------------------------

    /** Runs [mutate] atomically, and publishes presence only if the user's online state actually flipped. */
    private suspend fun applyAndPublish(userId: UUID, mutate: () -> Unit) {
        val (before, after) = synchronized(lock) {
            val b = isOnline(userId)
            mutate()
            b to isOnline(userId)
        }
        if (before == after) return
        if (!after) {
            val seen = System.currentTimeMillis()
            lastSeenCache[userId] = seen
            scope.launch { runCatching { presence.saveLastSeen(userId, seen) } }
        }
        publishPresence(userId)
    }

    private suspend fun frameFor(viewer: UUID, target: UUID): RealtimeFrame {
        if (!presence.canSee(viewer, target)) return RealtimeFrame("presence", userId = target.toString(), hidden = true)
        val online = isOnline(target)
        val seen = if (online) null else (lastSeenCache[target] ?: presence.loadLastSeen(target)?.also { lastSeenCache[target] = it })
        return RealtimeFrame("presence", userId = target.toString(), online = online, lastSeenEpochMillis = seen)
    }

    private fun publishPresence(target: UUID) {
        for ((viewer, set) in conns) {
            if (viewer == target) continue
            for (conn in set) {
                if (target !in conn.watching) continue
                scope.launch { runCatching { conn.session.sendJson(frameFor(viewer, target)) } }
            }
        }
    }

    private fun sendTo(userId: UUID, frame: RealtimeFrame) {
        val targets = conns[userId]?.toList() ?: return
        if (targets.isEmpty()) return
        val text = json.encodeToString(frame)
        for (c in targets) scope.launch { runCatching { c.session.send(Frame.Text(text)) } }
    }

    private suspend fun WebSocketSession.sendJson(frame: RealtimeFrame) {
        send(Frame.Text(json.encodeToString(frame)))
    }

    private companion object {
        const val MAX_WATCH = 200
    }
}
