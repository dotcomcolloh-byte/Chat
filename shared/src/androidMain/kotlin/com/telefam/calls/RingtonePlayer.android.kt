package com.telefam.calls

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager

/**
 * Ringtone / ringback are played from the app's OWN bundled resources
 * (res/raw/incoming_ringtone, res/raw/outgoing_ringback). Nothing is streamed from the
 * backend; if the bundle is missing we fall back to the OS's own call sounds, which are
 * also local to the device.
 */
actual object RingtonePlayer {
    private var player: MediaPlayer? = null

    actual fun startIncoming() = play(
        bundledName = "incoming_ringtone",
        systemUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
        usage = AudioAttributes.USAGE_NOTIFICATION_RINGTONE
    )

    actual fun startOutgoing() = play(
        bundledName = "outgoing_ringback",
        systemUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
        usage = AudioAttributes.USAGE_VOICE_COMMUNICATION
    )

    private fun play(bundledName: String, systemUri: android.net.Uri?, usage: Int) {
        stop()
        val context = AndroidCallContext.appContext
        val resId = context.resources.getIdentifier(bundledName, "raw", context.packageName)
        val source: Any = if (resId != 0) {
            android.net.Uri.parse("android.resource://${context.packageName}/$resId")
        } else systemUri ?: return

        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            runCatching {
                setDataSource(context, source as android.net.Uri)
                isLooping = true
                prepare()
                start()
            }
        }
    }

    actual fun stop() {
        player?.runCatching { if (isPlaying) stop(); release() }
        player = null
    }
}

/** Starts/stops the foreground call service via the androidApp bridge (registered by the host). */
actual object CallServiceBridge {
    @Volatile var starter: ((callId: String, peerName: String, video: Boolean) -> Unit)? = null
    @Volatile var stopper: (() -> Unit)? = null

    actual fun onCallStarted(callId: String, peerName: String, video: Boolean) {
        starter?.invoke(callId, peerName, video)
    }

    actual fun onCallEnded() {
        stopper?.invoke()
    }
}
