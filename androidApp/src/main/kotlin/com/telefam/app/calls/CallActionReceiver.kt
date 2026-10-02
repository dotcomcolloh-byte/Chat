package com.telefam.app.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableSharedFlow

/** Accept / Decline / open-from-full-screen events while the app may not even be running yet. */
sealed interface CallIntentEvent {
    val callId: String
    val callerId: String
    val callerName: String
    val callType: String

    data class Accept(
        override val callId: String, override val callerId: String,
        override val callerName: String, override val callType: String
    ) : CallIntentEvent

    data class Decline(
        override val callId: String, override val callerId: String,
        override val callerName: String, override val callType: String
    ) : CallIntentEvent

    /** The user tapped the notification body / full-screen intent — open the ringing UI. */
    data class Open(
        override val callId: String, override val callerId: String,
        override val callerName: String, override val callType: String
    ) : CallIntentEvent
}

/** In-process bus: receivers/push handlers publish, MainActivity collects once alive. */
object CallIntentBus {
    val events = MutableSharedFlow<CallIntentEvent>(extraBufferCapacity = 8)

    fun emit(event: CallIntentEvent) {
        events.tryEmit(event)
    }
}

/** Handles the Accept/Decline notification actions without requiring the UI to be unlocked. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra(IncomingCallNotification.EXTRA_CALL_ID) ?: return
        val callerId = intent.getStringExtra(IncomingCallNotification.EXTRA_CALLER_ID) ?: return
        val callerName = intent.getStringExtra(IncomingCallNotification.EXTRA_CALLER_NAME) ?: "Telefam user"
        val callType = intent.getStringExtra(IncomingCallNotification.EXTRA_CALL_TYPE) ?: "audio"

        IncomingCallNotification.cancel(context)
        when (intent.action) {
            IncomingCallNotification.ACTION_ACCEPT -> {
                CallIntentBus.emit(CallIntentEvent.Accept(callId, callerId, callerName, callType))
                // Bring the app forward onto the call screen.
                val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                    action = "com.telefam.app.calls.INCOMING"
                    putExtra(IncomingCallNotification.EXTRA_CALL_ID, callId)
                    putExtra(IncomingCallNotification.EXTRA_CALLER_ID, callerId)
                    putExtra(IncomingCallNotification.EXTRA_CALLER_NAME, callerName)
                    putExtra(IncomingCallNotification.EXTRA_CALL_TYPE, callType)
                    putExtra("autoAccept", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                launch?.let { context.startActivity(it) }
            }
            IncomingCallNotification.ACTION_DECLINE ->
                CallIntentBus.emit(CallIntentEvent.Decline(callId, callerId, callerName, callType))
        }
    }
}
