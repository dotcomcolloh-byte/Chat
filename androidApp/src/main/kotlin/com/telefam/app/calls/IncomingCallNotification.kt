package com.telefam.app.calls

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.telefam.app.R

/**
 * Incoming-call notification for the locked / backgrounded / killed cases.
 *
 *  - `setFullScreenIntent(..., true)` makes Android launch the call UI over the lockscreen
 *    (this is the same mechanism the system's own dialer uses).
 *  - The ringtone is the app's OWN bundled resource (res/raw/incoming_ringtone) attached
 *    to the notification channel — played by the OS even if our process was just started
 *    by the push. Nothing is streamed from anywhere.
 *  - Accept/Decline actions work straight from the notification without unlocking first.
 */
object IncomingCallNotification {
    const val CHANNEL_ID = "telefam_incoming_call"
    const val NOTIFICATION_ID = 0xCA12
    const val ACTION_ACCEPT = "com.telefam.app.calls.ACTION_ACCEPT"
    const val ACTION_DECLINE = "com.telefam.app.calls.ACTION_DECLINE"
    const val EXTRA_CALL_ID = "callId"
    const val EXTRA_CALLER_ID = "callerId"
    const val EXTRA_CALLER_NAME = "callerName"
    const val EXTRA_CALL_TYPE = "callType"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ringtoneUri = bundledRingtoneUri(context)
        val channel = NotificationChannel(CHANNEL_ID, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Ringing incoming Telefam calls"
            setSound(
                ringtoneUri,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 400, 600)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            if (Build.VERSION.SDK_INT >= 29) setAllowBubbles(false)
        }
        nm.createNotificationChannel(channel)
    }

    /** Shows the ringing notification + full-screen intent. Safe to call from any thread/process. */
    fun show(context: Context, callId: String, callerId: String, callerName: String, callType: String) {
        ensureChannel(context)

        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            action = "com.telefam.app.calls.INCOMING"
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_CALLER_ID, callerId)
            putExtra(EXTRA_CALLER_NAME, callerName)
            putExtra(EXTRA_CALL_TYPE, callType)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val fullScreen = PendingIntent.getActivity(
            context, callId.hashCode(), launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val accept = actionIntent(context, ACTION_ACCEPT, callId, callerId, callerName, callType, 1)
        val decline = actionIntent(context, ACTION_DECLINE, callId, callerId, callerName, callType, 2)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_call_notification)
            .setContentTitle(callerName)
            .setContentText(if (callType == "video") "Incoming video call" else "Incoming voice call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreen, true) // <-- this is what appears over the lockscreen
            .setContentIntent(fullScreen)
            .addAction(0, "Decline", decline)
            .addAction(0, if (callType == "video") "Video" else "Accept", accept)
            .build()

        context.getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun actionIntent(
        context: Context, action: String, callId: String,
        callerId: String, callerName: String, callType: String, requestCode: Int
    ): PendingIntent {
        val intent = Intent(context, CallActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_CALLER_ID, callerId)
            putExtra(EXTRA_CALLER_NAME, callerName)
            putExtra(EXTRA_CALL_TYPE, callType)
        }
        return PendingIntent.getBroadcast(
            context, callId.hashCode() xor requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun bundledRingtoneUri(context: Context): Uri {
        val resId = context.resources.getIdentifier("incoming_ringtone", "raw", context.packageName)
        return if (resId != 0) Uri.parse("android.resource://${context.packageName}/$resId")
        else android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
    }
}
