package com.telefam.calls

/**
 * Local ringtone / ringback playback. The ringtone NEVER comes from the network —
 * the app bundles it as a resource (Android: res/raw, iOS: main bundle) and the actuals
 * fall back to the OS's own call-notification ringtone if the bundle is missing.
 */
expect object RingtonePlayer {
    /** Incoming-call ringtone, looping until [stop]. */
    fun startIncoming()
    /** Outgoing ringback tone heard by the caller while the peer's phone rings. */
    fun startOutgoing()
    fun stop()
}

/**
 * Platform hook that keeps the call alive in the background:
 * Android actual starts/stops the foreground call service (mic/camera/screen types);
 * iOS actual is a no-op (CallKit holds the process).
 */
expect object CallServiceBridge {
    fun onCallStarted(callId: String, peerName: String, video: Boolean)
    fun onCallEnded()
}
