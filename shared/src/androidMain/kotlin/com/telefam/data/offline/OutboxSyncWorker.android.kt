package com.telefam.data.offline

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/** Minimal holder so the reflection-instantiated Worker below can reach the shared repository without a full DI framework. */
object OfflineDependencies {
    var repository: OfflineActionRepository? = null

    /** Retries chat messages still waiting to be encrypted+sent. Returns true if anything is still pending afterwards. */
    var chatRetry: (suspend () -> Boolean)? = null
}

/**
 * Silently replays the local outbox whenever WorkManager judges connectivity is
 * available, with its own exponential backoff on failure. No UI, no banner —
 * the caller of OfflineActionRepository never has to know this ran.
 */
class OutboxSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = OfflineDependencies.repository ?: return Result.success()
        return try {
            repository.flushPending()
            val chatStillPending = OfflineDependencies.chatRetry?.invoke() ?: false
            if (repository.pendingCount() == 0L && !chatStillPending) Result.success() else Result.retry()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "telefam_outbox_sync"

        /** Call after queuing any action, and on app start / network-regain, to schedule a silent retry. */
        fun scheduleOneTime(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<OutboxSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build()

            // APPEND_OR_REPLACE closes the race where a new action is inserted while
            // the current worker is finishing: KEEP can let that new action sit forever
            // with no future worker scheduled.
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request
            )
        }
    }
}
