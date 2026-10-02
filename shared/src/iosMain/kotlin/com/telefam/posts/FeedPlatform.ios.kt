package com.telefam.posts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.addPeriodicTimeObserverForInterval
import platform.AVFoundation.currentItem
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.rate
import platform.AVFoundation.seekToTime
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataTaskWithURL
import platform.Foundation.writeToFile
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import platform.darwin.DISPATCH_QUEUE_PRIORITY_BACKGROUND
import platform.darwin.dispatch_async

/**
 * iOS feed playback: AVPlayer on an AVPlayerLayer (resizeAspect = letterboxed, never
 * cropped or stretched — any aspect ratio renders correctly). Offline playback is a
 * real file cache: completed downloads in the Caches directory play with zero network;
 * upcoming items are prefetched in the background.
 */

@OptIn(ExperimentalForeignApi::class)
actual object FeedMediaCache {
    private val scope = CoroutineScope(Dispatchers.Default)

    private fun cacheDir(): String {
        val base = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
        val dir = "$base/feed_media"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        return dir
    }

    private fun fileFor(cacheKey: String) = "${cacheDir()}/$cacheKey.mp4"

    actual fun playableLocalUri(cacheKey: String): String? =
        fileFor(cacheKey).takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }

    actual fun ensureCached(cacheKey: String, url: String) {
        if (playableLocalUri(cacheKey) != null) return
        scope.launch { download(cacheKey, url) }
    }

    /** iOS prefetch: the mp4s are +faststart, so a completed small download is the reliable
     *  way to guarantee instant next-item playback; done at background priority. */
    actual fun preloadHead(cacheKey: String, url: String) = ensureCached(cacheKey, url)

    private fun download(cacheKey: String, url: String) {
        val dest = fileFor(cacheKey)
        val tmp = "$dest.part"
        val nsUrl = NSURL.URLWithString(url) ?: return
        val session = NSURLSession.sessionWithConfiguration(NSURLSessionConfiguration.defaultSessionConfiguration)
        session.dataTaskWithURL(nsUrl) { data, _, error ->
            if (data != null && error == null) {
                (data as NSData).writeToFile(tmp, atomically = true)
                NSFileManager.defaultManager.moveItemAtPath(tmp, toPath = dest, error = null)
            }
        }.resume()
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun FeedVideoSurface(
    url: String,
    cacheKey: String,
    playing: Boolean,
    speed: Float,
    modifier: Modifier,
    onBuffering: (Boolean) -> Unit,
    onReady: () -> Unit,
    onEnded: () -> Unit,
    onError: (String) -> Unit
) {
    val local = remember(cacheKey) { FeedMediaCache.playableLocalUri(cacheKey) }
    val cbBuffering = rememberUpdatedState(onBuffering)
    val cbReady = rememberUpdatedState(onReady)
    val cbEnded = rememberUpdatedState(onEnded)

    val player = remember(cacheKey, url) {
        val nsUrl = if (local != null) NSURL.fileURLWithPath(local) else NSURL.URLWithString(url)!!
        AVPlayer(uRL = nsUrl)
    }

    DisposableEffect(player) {
        val endObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            AVPlayerItemDidPlayToEndTimeNotification, `object` = player.currentItem,
            queue = NSOperationQueue.mainQueue
        ) {
            player.seekToTime(CMTimeMake(0, 1)) // loop like the reference feed
            player.play()
            cbEnded.value()
        }
        // Periodic observer drives buffering/ready callbacks (~4x per second is plenty for UI).
        val timeObserver = player.addPeriodicTimeObserverForInterval(
            CMTimeMakeWithSeconds(0.25, 600), queue = dispatch_get_main_queue()
        ) { _ ->
            val item = player.currentItem
            if (item != null) {
                val waiting = player.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
                cbBuffering.value(waiting)
                if (!waiting) cbReady.value()
            }
        }
        onDispose {
            NSNotificationCenter.defaultCenter.removeObserver(endObserver)
            player.removeTimeObserver(timeObserver)
            player.pause()
        }
    }

    LaunchedEffect(playing, speed) {
        if (playing) {
            player.play()
            player.rate = speed.coerceIn(0.25f, 3f)
        } else {
            player.pause()
        }
    }

    UIKitView(
        factory = {
            val view = UIView()
            view.backgroundColor = UIColor.blackColor
            val layer = AVPlayerLayer.playerLayerWithPlayer(player)
            layer.videoGravity = AVLayerVideoGravityResizeAspect
            layer.frame = view.bounds
            view.layer.addSublayer(layer)
            view
        },
        modifier = modifier
    )
}

@OptIn(ExperimentalForeignApi::class)
private object IosNetworkMonitor {
    private val monitor = nw_path_monitor_create()

    @Volatile var lastSatisfied: Boolean = true
    @Volatile var lastExpensive: Boolean = false

    private var started = false
    fun start() {
        if (started) return
        started = true
        nw_path_monitor_set_queue(monitor, dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_BACKGROUND.toLong(), 0u))
        nw_path_monitor_set_update_handler(monitor) { path ->
            if (path != null) {
                lastSatisfied = nw_path_get_status(path) == nw_path_status_satisfied
                lastExpensive = nw_path_is_expensive(path)
            }
        }
        nw_path_monitor_start(monitor)
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun observeConnectivity(): Flow<Boolean> = callbackFlow {
    IosNetworkMonitor.start()
    trySend(IosNetworkMonitor.lastSatisfied)
    var last = IosNetworkMonitor.lastSatisfied
    // Poll the monitor's latest path snapshot — NWPathMonitor updates it in real time.
    val job = launch {
        while (true) {
            kotlinx.coroutines.delay(1000)
            val now = IosNetworkMonitor.lastSatisfied
            if (now != last) { last = now; trySend(now) }
        }
    }
    awaitClose { job.cancel() }
}

@OptIn(ExperimentalForeignApi::class)
actual fun networkQualityTier(): Int {
    IosNetworkMonitor.start()
    if (!IosNetworkMonitor.lastSatisfied) return 1
    return if (IosNetworkMonitor.lastExpensive) 1 else 3
}

@Composable
actual fun rememberPostSharer(): (String) -> Unit = remember {
    { text ->
        // UIActivityViewController presents every installed share target (WhatsApp, X, etc.).
        val vc = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
        val root = UIApplication.sharedApplication.keyWindow?.rootViewController
        var top = root
        while (top?.presentedViewController != null) top = top?.presentedViewController
        top?.presentViewController(vc, animated = true, completion = null)
    }
}

@Composable
actual fun rememberPostDownloader(onDownloadEvent: (String) -> Unit): (String, String) -> Unit = remember {
    { url, fileName ->
        val nsUrl = NSURL.URLWithString(url)
        if (nsUrl == null) {
            onDownloadEvent("download_failed")
        } else {
            dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_BACKGROUND.toLong(), 0u)) {
                val docs = NSSearchPathForDirectoriesInDomains(
                    platform.Foundation.NSDocumentDirectory, NSUserDomainMask, true
                ).first() as String
                val dest = "$docs/$fileName.mp4"
                val data = NSData.dataWithContentsOfURL(nsUrl)
                val ok = data?.writeToFile(dest, atomically = true) ?: false
                dispatch_async(dispatch_get_main_queue()) {
                    onDownloadEvent(if (ok) "download_finished" else "download_failed")
                }
            }
            onDownloadEvent("download_started")
        }
    }
}
