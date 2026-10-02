package com.telefam.calls

import com.telefam.data.api.ApiConfig
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persistent, auto-reconnecting WebSocket to the backend's call-signaling hub.
 * Only signaling frames cross it — SDP/ICE are relayed opaquely and call media
 * itself is strictly peer-to-peer (DTLS-SRTP between the two devices).
 */
class CallSignalingClient(private val client: HttpClient) {
    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _frames = MutableSharedFlow<CallFrame>(extraBufferCapacity = 64)
    val frames: SharedFlow<CallFrame> = _frames

    @Volatile private var session: DefaultClientWebSocketSession? = null

    /** Connected-state callbacks let the controller distinguish "user offline" from "we are offline". */
    @Volatile var onConnected: (() -> Unit)? = null
    @Volatile var onDisconnected: (() -> Unit)? = null

    fun connect() {
        if (job?.isActive == true) return
        job = scope.launch {
            var backoff = 1_000L
            while (isActive) {
                try {
                    val wsUrl = ApiConfig.baseUrl
                        .replace("https://", "wss://").replace("http://", "ws://")
                        .trimEnd('/') + "/api/calls/ws"
                    client.webSocket(urlString = wsUrl) {
                        session = this
                        backoff = 1_000L
                        onConnected?.invoke()
                        try {
                            for (frame in incoming) {
                                if (frame !is Frame.Text) continue
                                runCatching { json.decodeFromString<CallFrame>(frame.readText()) }
                                    .getOrNull()?.let { _frames.emit(it) }
                            }
                        } finally {
                            session = null
                            onDisconnected?.invoke()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Offline / server unreachable — retry with backoff.
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    fun send(frame: CallFrame) {
        val s = session ?: return
        val text = json.encodeToString(frame)
        s.launch { runCatching { s.send(Frame.Text(text)) } }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        session = null
    }
}
