package com.telefam.calls

import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.Foundation.NSBundle

/**
 * iOS ringtone / ringback: played from the app bundle ("incoming_ringtone.caf" /
 * "outgoing_ringback.caf"). When the app is killed, iOS itself rings via CallKit's
 * incoming-call UI (the system ringtone) — nothing is ever streamed from the backend.
 */
actual object RingtonePlayer {
    private var player: AVAudioPlayer? = null

    actual fun startIncoming() = play("incoming_ringtone")
    actual fun startOutgoing() = play("outgoing_ringback")

    private fun play(name: String) {
        stop()
        val url = NSBundle.mainBundle.URLForResource(name, withExtension = "caf")
            ?: NSBundle.mainBundle.URLForResource(name, withExtension = "mp3")
            ?: return
        AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, error = null)
        player = AVAudioPlayer(contentsOfURL = url, error = null).apply {
            numberOfLoops = -1
            prepareToPlay()
            play()
        }
    }

    actual fun stop() {
        player?.stop()
        player = null
    }
}

/** On iOS, CallKit keeps the process alive during calls — no service bridge needed. */
actual object CallServiceBridge {
    actual fun onCallStarted(callId: String, peerName: String, video: Boolean) {}
    actual fun onCallEnded() {}
}
