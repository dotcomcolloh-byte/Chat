package com.telefam.posts

import com.telefam.data.api.ApiConfig
import com.telefam.data.api.FeedApi
import com.telefam.data.offline.OfflineActionRepository
import com.telefam.db.local.DatabaseDriverFactory
import com.telefam.db.local.LocalDatabase
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** A paged list of posts from any source (tab feed, search results, a user's profile posts). */
sealed class FeedSource {
    data class Tab(val tab: FeedTab) : FeedSource()
    data class Search(val query: String) : FeedSource()
    data class UserPosts(val userId: String) : FeedSource()
    /** Owner profile grid tab: "posts" | "reshared" | "locked" | "saved". */
    data class UserTab(val userId: String, val tab: String) : FeedSource()
}

/**
 * Content preferences that actually steer playback: set from the server-synced
 * app settings (Settings → Content preferences) and consulted by every
 * FeedViewModel when it picks a video variant.
 */
object PlaybackPrefs {
    @Volatile var dataSaver: Boolean = false
    /** AUTO | LOW | MEDIUM | HIGH */
    @Volatile var videoQuality: String = "AUTO"
    @Volatile var autoplay: Boolean = true

    fun tier(defaultTier: Int): Int = when {
        dataSaver -> 1
        videoQuality == "LOW" -> 1
        videoQuality == "MEDIUM" -> 2
        videoQuality == "HIGH" -> 3
        else -> defaultTier
    }
}

data class FeedListState(
    val items: List<FeedPostDto> = emptyList(),
    val nextCursor: String? = null,
    val loadingInitial: Boolean = false,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    /** True when the last network attempt failed AND we have no cached data. */
    val loadFailed: Boolean = false,
    /** True when the visible data came from the offline cache. */
    val fromCache: Boolean = false
)

/**
 * Feeds state machine. One instance per screen host (feeds tab / search overlay / profile watch).
 *
 * Production behaviors:
 *  - Infinite scroll: callers invoke [ensureLoadedAhead] from the pager; it prefetches the
 *    next page before the user reaches the end, so scrolling never hitches.
 *  - Offline: the last successful first page per source is persisted (JSON in AppCache) and
 *    served when the network is down; videos whose bytes are in the platform media cache
 *    still play. When connectivity returns, the current source refreshes automatically.
 *  - Mutations (like/save/reshare/not-interested/follow/view) update the UI optimistically
 *    and go through [OfflineActionRepository] — offline they land in the outbox and the
 *    platform's background worker (WorkManager / BGTaskScheduler) replays them silently.
 */
class FeedViewModel(
    private val feedApi: FeedApi,
    private val offlineRepository: OfflineActionRepository,
    driverFactory: DatabaseDriverFactory,
    /** Platform hook invoked after anything is queued (schedules WorkManager / BGTask). */
    private val onQueuedForRetry: () -> Unit = {},
    private val connectivity: Flow<Boolean> = observeConnectivity(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheDb = LocalDatabase.getInstance(driverFactory).appCacheQueries

    private val base get() = ApiConfig.baseUrl.trimEnd('/')

    private val _state = MutableStateFlow(FeedListState())
    val state: StateFlow<FeedListState> = _state

    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online

    /** The active source; settable before [load]/[adopt] so overlay pagers (search,
     *  profile) can inherit a query without re-fetching. */
    var source: FeedSource = FeedSource.Tab(FeedTab.FOR_YOU)

    private var loadJob: Job? = null
    private var loadGeneration: Long = 0L
    private val viewTimers = mutableMapOf<String, Job>()
    private val recordedViews = mutableSetOf<String>()

    init {
        scope.launch {
            var wasOffline = false
            connectivity.collect { up ->
                _online.value = up
                if (!up) {
                    wasOffline = true
                } else if (wasOffline) {
                    // Internet returned: refresh the feed and flush any queued likes/saves/reshares.
                    wasOffline = false
                    refresh()
                    onQueuedForRetry()
                }
            }
        }
    }

    private fun cacheKey(s: FeedSource) = when (s) {
        is FeedSource.Tab -> "feed_${s.tab.path}"
        is FeedSource.Search -> "feed_search_${s.query.hashCode()}"
        is FeedSource.UserPosts -> "feed_user_${s.userId}"
        is FeedSource.UserTab -> "feed_user_${s.userId}_${s.tab}"
    }

    // ---------- loading ----------

    fun load(s: FeedSource) {
        source = s
        val generation = ++loadGeneration
        loadJob?.cancel()
        loadJob = scope.launch { fetchInitial(s, generation) }
    }

    fun refresh() = load(source)

    private suspend fun fetchInitial(s: FeedSource, generation: Long) {
        _state.value = _state.value.copy(loadingInitial = true, loadFailed = false, fromCache = false)
        try {
            val page = fetchPage(s, null)
            if (generation != loadGeneration || source != s) return
            _state.value = FeedListState(
                items = page.items,
                nextCursor = page.nextCursor,
                endReached = page.nextCursor == null
            )
            persistCache(s, page.items, page.nextCursor)
            preloadAround(0)
        } catch (e: Exception) {
            if (generation != loadGeneration || source != s) return
            val cached = readCache(s)
            _state.value = if (cached != null) {
                FeedListState(items = cached.items, nextCursor = cached.nextCursor,
                    endReached = cached.nextCursor == null, fromCache = true)
            } else {
                FeedListState(loadFailed = true)
            }
        }
    }

    /** Infinite scroll: call from the pager with the currently visible index. */
    fun ensureLoadedAhead(visibleIndex: Int) {
        val snapshot = _state.value
        if (snapshot.loadingMore || snapshot.endReached || snapshot.fromCache || snapshot.nextCursor == null) return
        if (visibleIndex < snapshot.items.size - 3) {
            preloadAround(visibleIndex)
            return
        }

        val cursor = snapshot.nextCursor
        val sourceAtStart = source
        scope.launch {
            // Re-check after scheduling so rapid pager callbacks cannot start duplicate
            // requests with the same cursor.
            val current = _state.value
            if (current.loadingMore || current.endReached || current.nextCursor != cursor || source != sourceAtStart) return@launch
            _state.value = current.copy(loadingMore = true)
            try {
                val page = fetchPage(sourceAtStart, cursor)
                if (source != sourceAtStart || _state.value.nextCursor != cursor) return@launch
                val latest = _state.value
                val merged = (latest.items + page.items).distinctBy { it.postId }
                _state.value = latest.copy(
                    items = merged,
                    nextCursor = page.nextCursor,
                    loadingMore = false,
                    endReached = page.nextCursor == null
                )
                persistCache(sourceAtStart, merged, page.nextCursor)
            } catch (_: Exception) {
                if (source == sourceAtStart && _state.value.nextCursor == cursor) {
                    _state.value = _state.value.copy(loadingMore = false)
                }
            }
        }
        preloadAround(visibleIndex)
    }

    private suspend fun fetchPage(s: FeedSource, cursor: String?): FeedPageDto = when (s) {
        is FeedSource.Tab -> feedApi.feed(s.tab, cursor)
        is FeedSource.Search -> feedApi.search(s.query, cursor)
        is FeedSource.UserPosts -> feedApi.userPosts(s.userId, cursor)
        is FeedSource.UserTab -> feedApi.userTabPosts(s.userId, s.tab, cursor)
    }

    private fun persistCache(s: FeedSource, items: List<FeedPostDto>, nextCursor: String?) {
        runCatching {
            cacheDb.upsertValue(
                cacheKey(s),
                json.encodeToString(FeedPageDto.serializer(), FeedPageDto(items, nextCursor)),
                kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
            )
        }
    }

    private fun readCache(s: FeedSource): FeedPageDto? = runCatching {
        cacheDb.selectValue(cacheKey(s)).executeAsOneOrNull()
            ?.let { json.decodeFromString(FeedPageDto.serializer(), it.cachedValue) }
    }.getOrNull()

    /** Adopts an already-loaded page (e.g. the search overlay reusing grid results)
     *  without a network round-trip; infinite scroll continues against the adopted cursor. */
    fun adopt(items: List<FeedPostDto>, nextCursor: String? = null) {
        _state.value = FeedListState(items = items, nextCursor = nextCursor)
    }

    // ---------- playback ----------

    fun playableUrl(post: FeedPostDto): String? {
        val local = FeedMediaCache.playableLocalUri(post.postId)
        if (local != null) return local
        val remote = post.bestVariantUrl(PlaybackPrefs.tier(networkQualityTier())) ?: return null
        return feedApi.absoluteUrl(remote)
    }

    /** Preload the visible item fully (so it survives going offline) and the next
     *  two items' opening chunks (so swiping starts instantly). */
    fun preloadAround(index: Int) {
        // Data Saver: only ever preload the visible item, and never prefetch ahead.
        val items = _state.value.items
        items.getOrNull(index)?.let { p ->
            p.bestVariantUrl(PlaybackPrefs.tier(networkQualityTier()))?.let { FeedMediaCache.ensureCached(p.postId, feedApi.absoluteUrl(it)) }
        }
        if (PlaybackPrefs.dataSaver) return
        for (i in index + 1..index + 2) {
            items.getOrNull(i)?.let { p ->
                p.bestVariantUrl(PlaybackPrefs.tier(networkQualityTier()))?.let { FeedMediaCache.preloadHead(p.postId, feedApi.absoluteUrl(it)) }
            }
        }
    }

    /** Records a view after 3s of actual watch time (or on completion), once per session.
     *  Failed sends are queued to the outbox so views are never lost to a dead network. */
    fun onItemVisible(post: FeedPostDto) {
        viewTimers.values.forEach { it.cancel() }
        viewTimers.clear()
        if (post.postId in recordedViews) return
        viewTimers[post.postId] = scope.launch {
            delay(3000)
            if (recordedViews.add(post.postId)) recordView(post.postId, 3000, false)
        }
    }

    fun onItemCompleted(post: FeedPostDto, watchedMs: Long) {
        if (recordedViews.add(post.postId)) recordView(post.postId, watchedMs, true)
    }

    private fun recordView(postId: String, watchedMs: Long, completed: Boolean) {
        scope.launch {
            offlineRepository.performOrQueue(
                "$base/api/feeds/$postId/view", HttpMethod.Post,
                """{"watchedMs":$watchedMs,"completed":$completed}"""
            ).also { if (it is com.telefam.data.offline.ActionResult.QueuedForRetry) onQueuedForRetry() }
        }
    }

    // ---------- engagement (optimistic + outbox) ----------

    private fun mutate(postId: String, f: (FeedPostDto) -> FeedPostDto) {
        _state.value = _state.value.copy(items = _state.value.items.map { if (it.postId == postId) f(it) else it })
    }

    private fun queue(path: String, method: HttpMethod, body: String? = null) {
        scope.launch {
            val r = offlineRepository.performOrQueue(path, method, body)
            if (r is com.telefam.data.offline.ActionResult.QueuedForRetry) onQueuedForRetry()
        }
    }

    fun toggleLike(post: FeedPostDto) {
        val liked = !post.viewerLiked
        mutate(post.postId) { it.copy(viewerLiked = liked, likeCount = (it.likeCount + if (liked) 1 else -1).coerceAtLeast(0)) }
        queue("$base/api/feeds/${post.postId}/like", if (liked) HttpMethod.Post else HttpMethod.Delete)
    }

    fun toggleSave(post: FeedPostDto) {
        val saved = !post.viewerSaved
        mutate(post.postId) { it.copy(viewerSaved = saved) }
        queue("$base/api/feeds/${post.postId}/save", if (saved) HttpMethod.Post else HttpMethod.Delete)
    }

    fun reshare(post: FeedPostDto, channel: String) {
        mutate(post.postId) { it.copy(reshareCount = it.reshareCount + 1) }
        queue("$base/api/feeds/${post.postId}/reshare", HttpMethod.Post, """{"channel":"$channel"}""")
    }

    fun follow(post: FeedPostDto) {
        // The server rejects self-follow. Do not create a dead optimistic state for
        // the owner's own post.
        if (post.viewerFollowing || post.isOwner) return
        mutate(post.postId) { it.copy(viewerFollowing = true, ownerFollowerCount = it.ownerFollowerCount + 1) }
        queue("$base/api/feeds/${post.ownerId}/follow", HttpMethod.Post)
    }

    fun notInterested(post: FeedPostDto) {
        _state.value = _state.value.copy(items = _state.value.items.filterNot { it.postId == post.postId })
        queue("$base/api/feeds/${post.postId}/not-interested", HttpMethod.Post)
    }

    fun report(post: FeedPostDto, reason: String, details: String?, onDone: (Boolean) -> Unit) {
        scope.launch {
            val ok = runCatching {
                feedApi.report(post.postId, reason, details).status.value in 200..299
            }.getOrDefault(false)
            onDone(ok)
        }
    }

    fun blockOwner(post: FeedPostDto) {
        // Reuse the existing privacy block endpoint (RLS-protected) through the outbox.
        _state.value = _state.value.copy(items = _state.value.items.filterNot { it.ownerId == post.ownerId })
        queue("$base/api/social/blocked", HttpMethod.Post, """{"userId":"${post.ownerId}"}""")
    }

    fun setDownloadsAllowed(post: FeedPostDto, allowed: Boolean) {
        mutate(post.postId) { it.copy(downloadsAllowed = allowed) }
        queue("$base/api/posts/${post.postId}/downloads", HttpMethod.Put, """{"allowed":$allowed}""")
    }

    fun deletePost(post: FeedPostDto, onDone: (Boolean) -> Unit) {
        scope.launch {
            val ok = runCatching { feedApi.deletePost(post.postId).status.value in 200..299 }.getOrDefault(false)
            if (ok) _state.value = _state.value.copy(items = _state.value.items.filterNot { it.postId == post.postId })
            onDone(ok)
        }
    }

    fun editPost(postId: String, req: FeedEditPostRequest, onDone: (Boolean) -> Unit) {
        scope.launch {
            val ok = runCatching { feedApi.editPost(postId, req).status.value in 200..299 }.getOrDefault(false)
            if (ok) mutate(postId) {
                it.copy(
                    description = req.description ?: it.description,
                    hashtags = req.hashtags ?: it.hashtags,
                    commenting = req.commenting ?: it.commenting,
                    privacy = req.privacy ?: it.privacy,
                    downloadsAllowed = req.downloadsAllowed ?: it.downloadsAllowed
                )
            }
            onDone(ok)
        }
    }
}
