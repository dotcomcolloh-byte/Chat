package com.telefam.chat

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import java.io.File

actual class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAt = 0L

    actual val isRecording: Boolean get() = recorder != null

    actual fun start(): Boolean = try {
        val file = File(context.filesDir, "voice_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(64_000) // compressed at capture: voice-grade AAC, ~0.5MB/min
        r.setAudioSamplingRate(24_000)
        r.setOutputFile(file.absolutePath)
        r.prepare()
        r.start()
        recorder = r; outputFile = file; startedAt = System.currentTimeMillis()
        true
    } catch (e: Exception) {
        recorder?.release(); recorder = null; outputFile?.delete(); outputFile = null
        false
    }

    actual fun stop(): RecordedAudio? {
        val r = recorder ?: return null
        val file = outputFile
        val seconds = ((System.currentTimeMillis() - startedAt) / 1000).toInt().coerceAtLeast(1)
        return try {
            r.stop(); r.release(); recorder = null
            if (file != null && file.exists() && file.length() > 0) RecordedAudio(file.absolutePath, seconds) else null
        } catch (e: Exception) {
            // stop() throws if stopped too quickly (nothing captured)
            r.release(); recorder = null; file?.delete(); null
        } finally { outputFile = null }
    }

    actual fun cancel() {
        runCatching { recorder?.stop() }
        recorder?.release(); recorder = null
        outputFile?.delete(); outputFile = null
    }
}

actual class AudioPlayer {
    private var player: MediaPlayer? = null

    actual val isPlaying: Boolean get() = player?.isPlaying == true
    actual val positionMs: Int get() = player?.currentPosition ?: 0
    actual val durationMs: Int get() = player?.duration ?: 0

    actual fun play(filePath: String, onCompleted: () -> Unit) {
        stop()
        player = MediaPlayer().apply {
            setDataSource(filePath)
            setOnCompletionListener { onCompleted() }
            prepare()
            start()
        }
    }

    actual fun pause() { player?.pause() }
    actual fun resume() { player?.start() }
    actual fun stop() { player?.release(); player = null }
}

actual fun deleteLocalFile(path: String) { runCatching { File(path).delete() } }

actual fun readLocalFile(path: String): ByteArray? = runCatching { File(path).readBytes() }.getOrNull()
actual fun writeLocalFile(path: String, bytes: ByteArray): Boolean = runCatching { File(path).apply { parentFile?.mkdirs() }.writeBytes(bytes); true }.getOrDefault(false)
