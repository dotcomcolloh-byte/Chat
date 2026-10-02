package com.telefam.app.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Chat notification actions (Reply / Mark as read) fired while the app may be
 * backgrounded or not running at all.
 *
 * The actual send/mark-read work runs through [ChatActionBus]: MainActivity collects
 * events and hands them to the shared ChatService (E2EE, optimistic local write,
 * outbox retry). If the process isn't alive yet, the app is cold-started by the
 * broadcast and MainActivity drains the pending event on launch.
 */
sealed interface ChatActionEvent {
    val peerId: String
    val peerName: String

    data class Reply(override val peerId: String, override val peerName: String, val text: String) : ChatActionEvent
    data class MarkRead(override val peerId: String, override val peerName: String) : ChatActionEvent
    /** Notification body tap — open that conversation. */
    data class Open(override val peerId: String, override val peerName: String) : ChatActionEvent
}

object ChatActionBus {
    /** Buffered so an action taken before MainActivity exists is never lost. */
    val events = MutableSharedFlow<ChatActionEvent>(extraBufferCapacity = 16)

    fun emit(event: ChatActionEvent) { events.tryEmit(event) }
}

class ChatActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val peerId = intent.getStringExtra(ChatNotificationHelper.EXTRA_PEER_ID) ?: return
        val peerName = intent.getStringExtra(ChatNotificationHelper.EXTRA_PEER_NAME) ?: "Telefam user"

        when (intent.action) {
            ChatNotificationHelper.ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(ChatNotificationHelper.KEY_REPLY_TEXT)?.toString()?.trim()
                if (!text.isNullOrEmpty()) {
                    ChatNotificationHelper.cancel(context, peerId)
                    ChatActionBus.emit(ChatActionEvent.Reply(peerId, peerName, text))
                    wakeApp(context, peerId, peerName)
                }
            }
            ChatNotificationHelper.ACTION_MARK_READ -> {
                ChatNotificationHelper.cancel(context, peerId)
                ChatActionBus.emit(ChatActionEvent.MarkRead(peerId, peerName))
            }
        }
    }

    private fun wakeApp(context: Context, peerId: String, peerName: String) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            action = "com.telefam.app.chat.OPEN"
            putExtra(ChatNotificationHelper.EXTRA_PEER_ID, peerId)
            putExtra(ChatNotificationHelper.EXTRA_PEER_NAME, peerName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        launch?.let { context.startActivity(it) }
    }
}
