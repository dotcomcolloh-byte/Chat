package com.telefam.posts

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.Flow

/**
 * Platform hooks for the Feeds experience.
 *
 * Playback itself is native on both platforms (ExoPlayer on Android, AVPlayer on iOS)
 * behind [FeedVideoSurface]; everything ratio-related is handled by the composable's
 * ContentScale — the server keeps each video's native aspect ratio when encoding, so
 * portrait, landscape, square and ultrawide posts all render correctly.
 */

/** Full-bleed video surface with autoplay. [playing] gates playback (pager visibility),
 *  [speed] is the long-press 2x gesture, [muted] unused today but part of the contract. */
@Composable
expect fun FeedVideoSurface(
    url: String,
    cacheKey: String,
    playing: Boolean,
    speed: Float,
    modifier: Modifier = Modifier,
    onBuffering: (Boolean) -> Unit = {},
    onReady: () -> Unit = {},
    onEnded: () -> Unit = {},
    onError: (String) -> Unit = {}
)

/**
 * Offline video cache. Android backs this with ExoPlayer's SimpleCache (content-addressed
 * fragments — a partially cached video still plays its cached span with no network);
 * iOS backs it with file downloads into the Caches directory.
 */
expect object FeedMediaCache {
    /** Local path/uri if this cache key already has playable bytes, else null. */
    fun playableLocalUri(cacheKey: String): String?
    /** Fire-and-forget: make [url] fully playable offline. Used for the visible item. */
    fun ensureCached(cacheKey: String, url: String)
    /** Pre-buffer only the first chunk (~768KB) of upcoming items so swipe-to-next starts instantly. */
    fun preloadHead(cacheKey: String, url: String)
}

/** Real connectivity observer — drives the "No internet" banner and the auto-refresh on return. */
expect fun observeConnectivity(): Flow<Boolean>

/** Network quality tier for auto quality selection: 1 = constrained/metered, 2 = normal, 3 = fast. */
expect fun networkQualityTier(): Int

/** System share sheet — on Android the chooser shows installed social apps; on iOS UIActivityViewController. */
@Composable
expect fun rememberPostSharer(): (text: String) -> Unit

/** Downloads a post's video to user-visible storage (Movies/Telefam on Android, Documents on iOS).
 *  Returns a callback(url, suggestedName) -> Unit; errors are reported via [onDownloadEvent]. */
@Composable
expect fun rememberPostDownloader(onDownloadEvent: (String) -> Unit): (url: String, fileName: String) -> Unit
