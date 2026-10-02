package com.telefam.realtime

import com.telefam.data.api.ApiConfig
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Events surfaced to the UI layer. */
sealed interface RealtimeEvent {
    /**
     * Presence of a watched peer. [hidden] = the peer's privacy settings (or a block) forbid showing their status:
     * the UI must show nothing at all. Otherwise [online], or the time they were last seen ([lastSeenEpochMillis], may be null if unknown).
     */
    data class Presence(val userId: String, val online: Boolean, val lastSeenEpochMillis: Long?, val hidden: Boolean = false) : RealtimeEvent
    /** A peer is typing right now (ephemeral — the UI should auto-expire it after a few seconds). */
    data class Typing(val fromUserId: String) : RealtimeEvent
    /** Our mailbox changed; pull pending envelopes immediately instead of waiting for the poll tick. */
    data object MailboxHint : RealtimeEvent
}

@Serializable
private data class ServerFrame(
    val type: String,
    val userId: String? = null,
    val online: Boolean? = null,
    val lastSeenEpochMillis: Long? = null,
    val hidden: Boolean? = null,
    val from: String? = null
)

/**
 * Persistent WebSocket to the backend's realtime hub (presence, typing, mailbox hints).
 * Content never flows here — a MailboxHint just triggers the normal encrypted pull.
 * Reconnects with backoff; while disconnected, the app's regular polling remains the
 * fallback so nothing is ever missed.
 */
class RealtimeClient(private val client: HttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<RealtimeEvent> = _events

    @Volatile private var session: DefaultClientWebSocketSession? = null
    // Remembered so they are replayed after every reconnect: the server forgets them with the dropped connection.
    @Volatile private var watched: Set<String> = emptySet()
    @Volatile private var appActive: Boolean = true
    private var lastTypingMark = kotlin.time.TimeSource.Monotonic.markNow() - kotlin.time.Duration.parse("10s")

    fun connect() {
        if (job?.isActive == true) return
        job = scope.launch {
            var backoff = 1_000L
            while (isActive) {
                try {
                    val wsUrl = ApiConfig.baseUrl
                        .replace("https://", "wss://").replace("http://", "ws://")
                        .trimEnd('/') + "/api/realtime/ws"
                    client.webSocket(urlString = wsUrl) {
                        session = this
                        backoff = 1_000L
                        sendState(this); sendWatch(this)
                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Text) handleFrame(frame.readText())
                            }
                        } finally {
                            session = null
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // offline / server down — polling fallback covers us; retry with backoff
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private suspend fun handleFrame(text: String) {
        val frame = runCatching { json.decodeFromString<ServerFrame>(text) }.getOrNull() ?: return
        when (frame.type) {
            "presence" -> frame.userId?.let {
                _events.emit(RealtimeEvent.Presence(it, frame.online == true, frame.lastSeenEpochMillis, frame.hidden == true))
            }
            "typing" -> frame.from?.let { _events.emit(RealtimeEvent.Typing(it)) }
            "mailbox" -> _events.emit(RealtimeEvent.MailboxHint)
        }
    }

    /** Ephemeral typing signal; throttled so a fast typist produces at most one frame per 3s. */
    fun sendTyping(peerId: String) {
        val s = session ?: return
        val now = kotlin.time.TimeSource.Monotonic.markNow()
        if (now - lastTypingMark < kotlin.time.Duration.parse("3s")) return
        lastTypingMark = now
        s.launch { runCatching { s.send(Frame.Text("""{"type":"typing","to":"$peerId"}""")) } }
    }

    /** Subscribes to the presence of exactly these users (replaces the previous set); the server answers with a snapshot. */
    fun watch(userIds: Collection<String>) {
        val set = userIds.toSet()
        if (set == watched) return
        watched = set
        session?.let { sendWatch(it) }
    }

    /** Foreground/background: a backgrounded app must not count as "online" for the people watching. */
    fun setActive(active: Boolean) {
        if (active == appActive) return
        appActive = active
        session?.let { sendState(it) }
    }

    private fun sendWatch(s: DefaultClientWebSocketSession) {
        val frame = buildJsonObject {
            put("type", "watch")
            put("ids", JsonArray(watched.map { JsonPrimitive(it) }))
        }
        s.launch { runCatching { s.send(Frame.Text(frame.toString())) } }
    }

    private fun sendState(s: DefaultClientWebSocketSession) {
        val frame = buildJsonObject { put("type", "state"); put("active", appActive) }
        s.launch { runCatching { s.send(Frame.Text(frame.toString())) } }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        session = null
    }
}
