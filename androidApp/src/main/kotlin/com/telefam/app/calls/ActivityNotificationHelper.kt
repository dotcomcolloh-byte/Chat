package com.telefam.app.calls

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.telefam.app.R

/**
 * Activity-notification pushes (likes, comments, followers, payments, logins, …).
 *
 * The FCM data payload carries the title/body plus a deep-link target
 * (targetType/targetId/notificationId). Tapping the system notification opens
 * MainActivity with action [ACTION_OPEN]; MainActivity then redirects to the
 * origin (post, profile, wallet, subscriptions) or the notification centre.
 */
object ActivityNotificationHelper {
    const val CHANNEL_ID = "telefam_activity"
    const val ACTION_OPEN = "com.telefam.app.notifications.OPEN"

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Notifications", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Likes, comments, followers, payments and account activity"
            }
        )
    }

    fun show(
        context: Context,
        notificationId: String,
        title: String,
        body: String,
        targetType: String?,
        targetId: String?
    ) {
        ensureChannel(context)
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            action = ACTION_OPEN
            putExtra("notificationId", notificationId)
            putExtra("targetType", targetType)
            putExtra("targetId", targetId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val openPi = PendingIntent.getActivity(
            context, notificationId.hashCode(), open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_call_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openPi)
            .build()
        context.getSystemService(NotificationManager::class.java)
            ?.notify(0xAC00 or (notificationId.hashCode() and 0xFFFF), notification)
    }
}
