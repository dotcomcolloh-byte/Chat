package com.telefam.chat

/** Result of a finished recording: absolute file path + duration. */
data class RecordedAudio(val filePath: String, val durationSeconds: Int)

/** Records AAC/m4a audio to a private app file. Android: MediaRecorder. iOS: AVAudioRecorder. */
expect class VoiceRecorder {
    fun start(): Boolean          // false if mic permission/hardware unavailable
    fun stop(): RecordedAudio?    // null if nothing was recorded
    fun cancel()                  // discards the file
    val isRecording: Boolean
}

/** Plays a local audio file. Android: MediaPlayer. iOS: AVAudioPlayer. */
expect class AudioPlayer {
    fun play(filePath: String, onCompleted: () -> Unit)
    fun pause()
    fun resume()
    fun stop()
    val isPlaying: Boolean
    val positionMs: Int
    val durationMs: Int
}

/** Deletes a file from app-private storage (discarded recordings, consumed view-once media). */
expect fun deleteLocalFile(path: String)

/** App-private base directory for decrypted media. Set once at startup by the host (Android: filesDir, iOS: Documents). */
object AppFiles { var baseDir: String = "" }

expect fun readLocalFile(path: String): ByteArray?
expect fun writeLocalFile(path: String, bytes: ByteArray): Boolean
