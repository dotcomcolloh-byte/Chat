package com.telefam.calls

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView

/**
 * iOS video views: the Swift engine renders into UIView instances it owns (RTCEAGLVideoView /
 * RTCMTLVideoView). The bridge exposes them via [IosVideoViewProvider]; until the Swift side
 * is linked, these render an empty box so the rest of the UI (avatar fallback etc.) still works.
 */
interface IosVideoViewProvider {
    fun remoteVideoView(): platform.UIKit.UIView
    fun localVideoView(): platform.UIKit.UIView
}

object IosVideoViewRegistry {
    var provider: IosVideoViewProvider? = null
}

@Composable
actual fun RemoteVideoView(engine: WebRtcEngine, modifier: Modifier) {
    val provider = remember { IosVideoViewRegistry.provider }
    if (provider != null) {
        UIKitView(factory = { provider.remoteVideoView() }, modifier = modifier)
    }
}

@Composable
actual fun LocalVideoView(engine: WebRtcEngine, modifier: Modifier) {
    val provider = remember { IosVideoViewRegistry.provider }
    if (provider != null) {
        UIKitView(factory = { provider.localVideoView() }, modifier = modifier)
    }
}
