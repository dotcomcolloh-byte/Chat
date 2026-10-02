package com.telefam.app.calls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.telefam.app.R

/**
 * Foreground service that keeps mic/camera/screen capture alive while the user leaves
 * the app mid-call (home button, screen off). Android 14+ requires the matching
 * foregroundServiceType for every capture source in use.
 */
class CallForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val peerName = intent.getStringExtra(EXTRA_PEER_NAME) ?: "Telefam call"
                val video = intent.getBooleanExtra(EXTRA_VIDEO, true)
                startAsForeground(peerName, video, screenShare = false)
            }
            ACTION_START_SCREEN_SHARE -> {
                // Must be running with mediaProjection type BEFORE the projection is created (Android 14+).
                val peerName = intent.getStringExtra(EXTRA_PEER_NAME) ?: "Telefam call"
                startAsForeground(peerName, video = true, screenShare = true)
            }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground(peerName: String, video: Boolean, screenShare: Boolean) {
        ensureChannel()
        val notification = buildNotification(peerName)
        val type = buildType(video, screenShare)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, type)
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildType(video: Boolean, screenShare: Boolean): Int {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (video) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (screenShare && Build.VERSION.SDK_INT >= 29) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        return type
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Ongoing calls", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a Telefam call is active"
                setSound(null, null)
            }
        )
    }

    private fun buildNotification(peerName: String): Notification {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_call_notification)
            .setContentTitle("Telefam call")
            .setContentText("In call with $peerName")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(openIntent)
            .build()
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "telefam_ongoing_call"
        private const val NOTIFICATION_ID = 0xCA11
        const val ACTION_START = "com.telefam.app.calls.START"
        const val ACTION_START_SCREEN_SHARE = "com.telefam.app.calls.START_SCREEN_SHARE"
        const val ACTION_STOP = "com.telefam.app.calls.STOP"
        const val EXTRA_PEER_NAME = "peerName"
        const val EXTRA_VIDEO = "video"

        fun start(context: Context, peerName: String, video: Boolean) {
            val intent = Intent(context, CallForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PEER_NAME, peerName)
                .putExtra(EXTRA_VIDEO, video)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun startScreenShare(context: Context, peerName: String) {
            val intent = Intent(context, CallForegroundService::class.java)
                .setAction(ACTION_START_SCREEN_SHARE)
                .putExtra(EXTRA_PEER_NAME, peerName)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CallForegroundService::class.java).setAction(ACTION_STOP))
        }
    }
}
