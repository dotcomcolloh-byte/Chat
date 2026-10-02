package com.telefam.data.offline

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTask
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSinceNow

/**
 * Real BGTaskScheduler integration. Register the task identifier in Info.plist under
 * `BGTaskSchedulerPermittedIdentifiers` (see iosApp/Info.plist.snippet.xml) and call
 * `register()` once at app launch, before `application(_:didFinishLaunchingWithOptions:)`
 * returns, per Apple's requirement. Entirely silent — iOS decides when to actually run it.
 */
@OptIn(ExperimentalForeignApi::class)
class OutboxSyncScheduler(private val repository: OfflineActionRepository) {

    companion object {
        const val TASK_IDENTIFIER = "com.telefam.app.outbox-sync"
    }

    fun register() {
        BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(
            TASK_IDENTIFIER, using = null
        ) { task ->
            handleTask(task as BGTask)
        }
    }

    fun scheduleNext() {
        val request = BGAppRefreshTaskRequest(TASK_IDENTIFIER)
        request.earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(15 * 60.0) // iOS still governs actual timing
        try {
            BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null)
        } catch (e: Exception) {
            // Scheduling can legitimately fail (e.g. too many pending requests) — safe to ignore, no UI impact.
        }
    }

    private fun handleTask(task: BGTask) {
        scheduleNext() // always re-schedule the next silent attempt
        val job = CoroutineScope(Dispatchers.Default).launch {
            try {
                repository.flushPending()
                task.setTaskCompletedWithSuccess(true)
            } catch (e: Exception) {
                task.setTaskCompletedWithSuccess(false)
            }
        }
        task.expirationHandler = { job.cancel() }
    }
}
