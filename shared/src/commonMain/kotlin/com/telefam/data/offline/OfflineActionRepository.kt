package com.telefam.data.offline

import com.telefam.db.local.DatabaseDriverFactory
import com.telefam.db.local.LocalDatabase
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.datetime.Clock
import kotlin.random.Random

sealed class ActionResult {
    object SentImmediately : ActionResult()
    object QueuedForRetry : ActionResult() // transient failure; it will sync silently later
    object PermanentFailure : ActionResult() // server rejected the action; never retry it forever
}

/**
 * Every mutating privacy/social action (toggle a privacy setting, accept/decline a
 * request, block, archive) goes through here instead of calling the API directly:
 *   1. Attempt the real network call right away. `client` carries Ktor's real
 *      Auth/bearer plugin (see createTelefamHttpClient), so the current access
 *      token is attached automatically and transparently refreshed on a 401 —
 *      this repository doesn't need to know about tokens at all.
 *   2. If it fails for ANY reason (no connection, timeout, 5xx, or a refresh that
 *      itself failed) — persist the exact request to the local outbox and return
 *      QueuedForRetry. The caller updates its UI optimistically either way; there
 *      is no offline banner or error toast for this.
 *   3. `flushPending()` replays the queue in order and is invoked by the platform's
 *      background scheduler (WorkManager on Android, BGTaskScheduler on iOS) whenever
 *      connectivity returns — entirely silently.
 */
class OfflineActionRepository(
    private val client: HttpClient,
    driverFactory: DatabaseDriverFactory
) {
    private val db = LocalDatabase.getInstance(driverFactory)
    private val queries = db.outboxQueueQueries

    suspend fun performOrQueue(path: String, method: HttpMethod, bodyJson: String?): ActionResult {
        when (trySend(path, method, bodyJson)) {
            SendResult.SENT -> return ActionResult.SentImmediately
            SendResult.PERMANENT_FAILURE -> return ActionResult.PermanentFailure
            SendResult.RETRY -> Unit
        }

        queries.insertAction(
            id = randomId(),
            path = path,
            method = method.value,
            bodyJson = bodyJson,
            createdAt = Clock.System.now().toEpochMilliseconds()
        )
        return ActionResult.QueuedForRetry
    }

    /** Called by the background worker/task. Replays oldest-first; stops cleanly if still offline. */
    suspend fun flushPending() {
        val pending = queries.selectAllOrdered().executeAsList()
        for (action in pending) {
            when (trySend(action.path, HttpMethod.parse(action.method), action.bodyJson)) {
                SendResult.SENT, SendResult.PERMANENT_FAILURE -> queries.deleteAction(action.id)
                SendResult.RETRY -> {
                    queries.incrementRetry(action.id)
                    // Stop on the first transient failure to preserve ordering.
                    return
                }
            }
        }
    }

    fun pendingCount(): Long = queries.countPending().executeAsOne()

    private enum class SendResult { SENT, RETRY, PERMANENT_FAILURE }

    private suspend fun trySend(path: String, method: HttpMethod, bodyJson: String?): SendResult = try {
        val response: HttpResponse = client.request(path) {
            this.method = method
            if (bodyJson != null) {
                contentType(ContentType.Application.Json)
                setBody(bodyJson)
            }
        }
        when {
            response.status.value in 200..299 -> SendResult.SENT
            response.status.value == 408 ||
                response.status.value == 425 ||
                response.status.value == 429 ||
                response.status.value >= 500 -> SendResult.RETRY
            else -> SendResult.PERMANENT_FAILURE
        }
    } catch (_: Exception) {
        // Transport failures (DNS, timeout, connection reset, etc.) are transient.
        SendResult.RETRY
    }

    private fun randomId(): String {
        val chars = "0123456789abcdef"
        return (1..32).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }
}
