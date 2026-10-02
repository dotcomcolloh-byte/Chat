package com.telefam.app.calls

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.telefam.app.R

/**
 * Chat message notifications for the backgrounded/killed app.
 *
 * Flow: the backend sends a content-free FCM data message -> TelefamFirebaseMessagingService
 * pulls the encrypted mailbox, decrypts locally, then calls [show] with the real text.
 *
 * Actions (work straight from the notification, no unlock required):
 *  - **Reply**: inline RemoteInput -> ChatActionReceiver sends the encrypted reply.
 *  - **Mark as read**: ChatActionReceiver marks the conversation read and sends receipts.
 */
object ChatNotificationHelper {
    const val CHANNEL_ID = "telefam_chat_messages"
    const val ACTION_REPLY = "com.telefam.app.chat.ACTION_REPLY"
    const val ACTION_MARK_READ = "com.telefam.app.chat.ACTION_MARK_READ"
    const val KEY_REPLY_TEXT = "key_reply_text"
    const val EXTRA_PEER_ID = "peerId"
    const val EXTRA_PEER_NAME = "peerName"

    private fun notificationIdFor(peerId: String) = 0xCA00 or (peerId.hashCode() and 0xFFFF)

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New Telefam messages"
                enableVibration(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE // content hidden on lockscreen
            }
        )
    }

    /** Shows (or updates, one per conversation) a message notification with Reply / Mark-as-read. */
    fun show(context: Context, peerId: String, peerName: String, text: String) {
        ensureChannel(context)

        // Tapping the body opens that conversation.
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            action = "com.telefam.app.chat.OPEN"
            putExtra(EXTRA_PEER_ID, peerId)
            putExtra(EXTRA_PEER_NAME, peerName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val openPi = PendingIntent.getActivity(
            context, peerId.hashCode(), open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Inline reply: system collects the text and broadcasts it to ChatActionReceiver.
        val replyIntent = Intent(context, ChatActionReceiver::class.java).apply {
            action = ACTION_REPLY
            putExtra(EXTRA_PEER_ID, peerId)
            putExtra(EXTRA_PEER_NAME, peerName)
        }
        val replyPi = PendingIntent.getBroadcast(
            context, peerId.hashCode() xor 1, replyIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val replyAction = NotificationCompat.Action.Builder(
            0, "Reply", replyPi
        ).addRemoteInput(RemoteInput.Builder(KEY_REPLY_TEXT).setLabel("Reply…").build()).build()

        val markReadIntent = Intent(context, ChatActionReceiver::class.java).apply {
            action = ACTION_MARK_READ
            putExtra(EXTRA_PEER_ID, peerId)
            putExtra(EXTRA_PEER_NAME, peerName)
        }
        val markReadPi = PendingIntent.getBroadcast(
            context, peerId.hashCode() xor 2, markReadIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_call_notification)
            .setContentTitle(peerName)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(openPi)
            .addAction(replyAction)
            .addAction(0, "Mark as read", markReadPi)
            .build()

        context.getSystemService(NotificationManager::class.java)
            ?.notify(notificationIdFor(peerId), notification)
    }

    fun cancel(context: Context, peerId: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(notificationIdFor(peerId))
    }
}
