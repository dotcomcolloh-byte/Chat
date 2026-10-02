package com.telefam.calls

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/** Process-wide Android context holder, initialised once from the host Application/Activity. */
object AndroidCallContext {
    lateinit var appContext: Context
        private set

    fun init(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }
}

/** Full-bleed remote video surface. */
@Composable
actual fun RemoteVideoView(engine: WebRtcEngine, modifier: Modifier) {
    val renderer = remember {
        SurfaceViewRenderer(AndroidCallContext.appContext).apply {
            init(WebRtcEngine.rootEglBase.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setEnableHardwareScaler(true)
        }
    }
    DisposableEffect(engine) {
        engine.attachRemoteSink(renderer)
        onDispose {
            engine.detachRemoteSink(renderer)
            renderer.release()
        }
    }
    AndroidView(factory = { renderer }, modifier = modifier)
}

/** Picture-in-picture local preview (mirrored front camera / shared screen). */
@Composable
actual fun LocalVideoView(engine: WebRtcEngine, modifier: Modifier) {
    val renderer = remember {
        SurfaceViewRenderer(AndroidCallContext.appContext).apply {
            init(WebRtcEngine.rootEglBase.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setMirror(true)
            setZOrderMediaOverlay(true) // floats above the remote surface
            setEnableHardwareScaler(true)
        }
    }
    DisposableEffect(engine) {
        engine.attachLocalSink(renderer)
        onDispose {
            engine.detachLocalSink(renderer)
            renderer.release()
        }
    }
    AndroidView(factory = { renderer }, modifier = modifier)
}
