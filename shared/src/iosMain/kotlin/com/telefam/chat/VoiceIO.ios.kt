package com.telefam.chat

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVEncoderAudioQualityKey
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.Foundation.NSDate
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970

@OptIn(ExperimentalForeignApi::class)
actual class VoiceRecorder {
    private var recorder: AVAudioRecorder? = null
    private var path: String? = null
    private var startedAt = 0.0

    actual val isRecording: Boolean get() = recorder?.recording == true

    actual fun start(): Boolean {
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayAndRecord, error = null)
        session.setActive(true, error = null)

        val filePath = NSTemporaryDirectory() + "voice_${NSDate().timeIntervalSince1970.toLong()}.m4a"
        val settings = mapOf<Any?, Any?>(
            AVFormatIDKey to kAudioFormatMPEG4AAC,
            AVSampleRateKey to 24000.0,
            AVNumberOfChannelsKey to 1,
            AVEncoderAudioQualityKey to 32 // AVAudioQuality.medium
        )
        val r = AVAudioRecorder(uRL = NSURL.fileURLWithPath(filePath), settings = settings, error = null)
        if (r.prepareToRecord() && r.record()) {
            recorder = r; path = filePath; startedAt = NSDate().timeIntervalSince1970
            return true
        }
        return false
    }

    actual fun stop(): RecordedAudio? {
        val r = recorder ?: return null
        r.stop(); recorder = null
        val p = path ?: return null
        val seconds = (NSDate().timeIntervalSince1970 - startedAt).toInt().coerceAtLeast(1)
        path = null
        return RecordedAudio(p, seconds)
    }

    actual fun cancel() {
        recorder?.stop(); recorder?.deleteRecording(); recorder = null; path = null
    }
}

@OptIn(ExperimentalForeignApi::class)
actual class AudioPlayer {
    private var player: AVAudioPlayer? = null

    actual val isPlaying: Boolean get() = player?.playing == true
    actual val positionMs: Int get() = ((player?.currentTime ?: 0.0) * 1000).toInt()
    actual val durationMs: Int get() = ((player?.duration ?: 0.0) * 1000).toInt()

    actual fun play(filePath: String, onCompleted: () -> Unit) {
        stop()
        val p = AVAudioPlayer(contentsOfURL = NSURL.fileURLWithPath(filePath), error = null)
        p.prepareToPlay(); p.play()
        player = p
        // Completion is polled by the UI progress ticker (isPlaying turns false at end), so no delegate needed here.
    }

    actual fun pause() { player?.pause() }
    actual fun resume() { player?.play() }
    actual fun stop() { player?.stop(); player = null }
}

actual fun deleteLocalFile(path: String) {
    platform.Foundation.NSFileManager.defaultManager.removeItemAtPath(path, error = null)
}

@OptIn(ExperimentalForeignApi::class)
actual fun readLocalFile(path: String): ByteArray? {
    val data = platform.Foundation.NSData.dataWithContentsOfFile(path) ?: return null
    val out = ByteArray(data.length.toInt())
    if (out.isNotEmpty()) out.usePinned { platform.posix.memcpy(it.addressOf(0), data.bytes, data.length) }
    return out
}

@OptIn(ExperimentalForeignApi::class)
actual fun writeLocalFile(path: String, bytes: ByteArray): Boolean {
    val data = if (bytes.isEmpty()) platform.Foundation.NSData()
    else bytes.usePinned { platform.Foundation.NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    return data.writeToFile(path, atomically = true)
}
