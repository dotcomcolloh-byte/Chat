package com.telefam.posts

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Android feed playback: ExoPlayer with a shared 512MB SimpleCache.
 *  - Offline playback is automatic: CacheDataSource serves cached spans even fully offline.
 *  - [FeedMediaCache.preloadHead] uses CacheWriter to fetch only the opening chunk of
 *    upcoming posts, so swiping starts instantly without downloading whole videos.
 *  - All aspect ratios are handled by PlayerView RESIZE_MODE_FIT — the server preserves
 *    each video's native ratio, so nothing is ever cropped or stretched.
 */

private object FeedCacheHolder {
    @Volatile private var cache: SimpleCache? = null

    fun cache(context: Context): Cache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            File(context.cacheDir, "feed_media"),
            androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(512L * 1024 * 1024),
            StandaloneDatabaseProvider(context)
        ).also { cache = it }
    }
}

/** Shared app context holder, set once at app start (MainActivity calls FeedMediaCache.init). */
internal object FeedAndroidContext {
    @Volatile var context: Context? = null
}

actual object FeedMediaCache {
    private val appContext: Context? get() = FeedAndroidContext.context
    private val scope = CoroutineScope(Dispatchers.IO)

    /** Must be called once at app start (MainActivity) before any playback. */
    fun init(context: Context) { FeedAndroidContext.context = context.applicationContext }

    private fun httpFactory() = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)

    private fun cacheDataSourceFactory(context: Context) =
        CacheDataSource.Factory().setCache(FeedCacheHolder.cache(context)).setUpstreamDataSourceFactory(httpFactory())

    /** ExoPlayer reads through the cache by URL; there is no file path to hand back. */
    actual fun playableLocalUri(cacheKey: String): String? = null

    actual fun ensureCached(cacheKey: String, url: String) {
        val context = appContext ?: return
        scope.launch {
            runCatching {
                CacheWriter(
                    cacheDataSourceFactory(context).createDataSource(),
                    DataSpec(Uri.parse(url)), null, null
                ).cache() // whole file: the visible post must survive going offline
            }
        }
    }

    actual fun preloadHead(cacheKey: String, url: String) {
        val context = appContext ?: return
        scope.launch {
            runCatching {
                val spec = DataSpec.Builder()
                    .setUri(Uri.parse(url))
                    .setPosition(0).setLength(768L * 1024) // opening chunk only
                    .build()
                CacheWriter(cacheDataSourceFactory(context).createDataSource(), spec, null, null).cache()
            }
        }
    }
}

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
    val context = LocalContext.current
    val cbBuffering = rememberUpdatedState(onBuffering)
    val cbReady = rememberUpdatedState(onReady)
    val cbEnded = rememberUpdatedState(onEnded)
    val cbError = rememberUpdatedState(onError)

    val player = remember(cacheKey, url) {
        val factory = CacheDataSource.Factory()
            .setCache(FeedCacheHolder.cache(context))
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true))
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(url))
                repeatMode = Player.REPEAT_MODE_ONE // feed videos loop like the reference
                playWhenReady = false
                prepare()
            }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> cbBuffering.value(true)
                    Player.STATE_READY -> { cbBuffering.value(false); cbReady.value() }
                    Player.STATE_ENDED -> cbEnded.value()
                    else -> Unit
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                cbBuffering.value(false)
                cbError.value(error.message ?: "playback error")
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // React to gesture state: tap pause/resume, long-press 2x, pager-driven autoplay.
    androidx.compose.runtime.LaunchedEffect(playing, speed) {
        player.playWhenReady = playing
        player.setPlaybackSpeed(if (playing) speed.coerceIn(0.25f, 3f) else 1f)
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { it.player = player },
        modifier = modifier
    )
}

actual fun observeConnectivity(): Flow<Boolean> = callbackFlow {
    val context = FeedAndroidContext.context
        ?: run { trySend(true); close(); return@callbackFlow }
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { trySend(true) }
        override fun onLost(network: Network) { trySend(cm.activeNetwork == null && currentCapabilities(cm) == null) }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            trySend(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        }
    }
    trySend(currentCapabilities(cm)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: true)
    cm.registerNetworkCallback(NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback)
    awaitClose { cm.unregisterNetworkCallback(callback) }
}

private fun currentCapabilities(cm: ConnectivityManager): NetworkCapabilities? =
    cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }

actual fun networkQualityTier(): Int {
    val context = FeedAndroidContext.context ?: return 2
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = currentCapabilities(cm) ?: return 1
    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return 1
    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
        val kbps = caps.linkDownstreamBandwidthKbps
        return if (kbps >= 4000) 3 else 2
    }
    return 1 // metered: be kind to the data plan
}

@Composable
actual fun rememberPostSharer(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { text ->
            // ACTION_SEND chooser shows every installed share target — WhatsApp, X,
            // Instagram, Telegram, SMS, copy-to-clipboard — nothing hardcoded.
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

@Composable
actual fun rememberPostDownloader(onDownloadEvent: (String) -> Unit): (String, String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { url, fileName ->
            runCatching {
                val req = DownloadManager.Request(Uri.parse(url))
                    .setTitle(fileName)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "Telefam/$fileName.mp4")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setAllowedOverMetered(true)
                (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                onDownloadEvent("download_started")
            }.onFailure { onDownloadEvent("download_failed") }
        }
    }
}
