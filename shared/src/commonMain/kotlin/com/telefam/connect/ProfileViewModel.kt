package com.telefam.connect

import com.telefam.data.api.ConnectApi
import com.telefam.data.api.FollowRateLimitedException
import com.telefam.data.offline.ActionResult
import com.telefam.data.offline.OfflineActionRepository
import com.telefam.db.local.DatabaseDriverFactory
import com.telefam.db.local.LocalDatabase
import com.telefam.posts.observeConnectivity
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

enum class ConnectionListKind(val path: String) { FOLLOWERS("followers"), FOLLOWING("following"), FRIENDS("friends"), SUBSCRIBERS("subscribers") }

data class ProfileState(
    val profile: ProfileDetailsDto? = null,
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    val notFound: Boolean = false,
    val fromCache: Boolean = false,
    val rateLimitedUntilMs: Long = 0L
)

data class ConnectionListState(
    val items: List<ConnectUserDto> = emptyList(),
    val nextOffset: Int? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false
)

/**
 * Profile screen state: details (cached offline), follow/unfollow/block/report
 * actions with optimistic UI + outbox fallback, and the tappable
 * followers/following/friends lists.
 */
class ProfileViewModel(
    private val api: ConnectApi,
    private val offlineRepository: OfflineActionRepository,
    driverFactory: DatabaseDriverFactory,
    private val onQueuedForRetry: () -> Unit = {},
    connectivity: Flow<Boolean> = observeConnectivity(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = LocalDatabase.getInstance(driverFactory).appCacheQueries
    private val base get() = com.telefam.data.api.ApiConfig.baseUrl.trimEnd('/')

    private val _state = MutableStateFlow(ProfileState())
    val state: StateFlow<ProfileState> = _state

    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online

    /** Per-kind list states, loaded on demand when a stat is tapped. */
    private val _lists = MutableStateFlow<Map<ConnectionListKind, ConnectionListState>>(emptyMap())
    val lists: StateFlow<Map<ConnectionListKind, ConnectionListState>> = _lists

    var userId: String = ""
        private set

    init {
        scope.launch {
            var wasOffline = false
            connectivity.collect { up ->
                _online.value = up
                if (!up) wasOffline = true
                else if (wasOffline) { wasOffline = false; refresh(); onQueuedForRetry() }
            }
        }
    }

    fun load(userId: String) {
        this.userId = userId
        refresh()
    }

    fun refresh() {
        val id = userId.takeIf { it.isNotBlank() } ?: return
        scope.launch {
            _state.value = _state.value.copy(loading = true, loadFailed = false)
            try {
                val p = api.profile(id)
                if (p == null) {
                    _state.value = ProfileState(notFound = true)
                } else {
                    _state.value = ProfileState(profile = p)
                    persistCache(id, p)
                }
            } catch (_: Exception) {
                val cached = readCache(id)
                _state.value = if (cached != null) ProfileState(profile = cached, fromCache = true)
                else ProfileState(loadFailed = true)
            }
        }
    }

    private fun persistCache(id: String, p: ProfileDetailsDto) = runCatching {
        cache.upsertValue("profile_$id", json.encodeToString(ProfileDetailsDto.serializer(), p),
            kotlinx.datetime.Clock.System.now().toEpochMilliseconds())
    }

    private fun readCache(id: String): ProfileDetailsDto? = runCatching {
        cache.selectValue("profile_$id").executeAsOneOrNull()
            ?.let { json.decodeFromString(ProfileDetailsDto.serializer(), it.cachedValue) }
    }.getOrNull()

    // ---------- follow ----------

    private fun idempotencyKey(action: String) = ContactHash.sha256Hex("$userId:$action").take(24)

    fun follow() = setFollow(true)
    fun unfollow() = setFollow(false)

    private fun setFollow(follow: Boolean) {
        val current = _state.value.profile ?: return
        _state.value = _state.value.copy(profile = current.copy(
            viewerFollowing = follow,
            isFriend = follow && current.viewerFollowedBy,
            followerCount = (current.followerCount + if (follow) 1 else -1).coerceAtLeast(0)
        ))
        scope.launch {
            try {
                val s = if (follow) api.follow(userId, idempotencyKey("F")) else api.unfollow(userId, idempotencyKey("U"))
                _state.value.profile?.let { p ->
                    _state.value = _state.value.copy(profile = p.copy(
                        viewerFollowing = s.viewerFollowing, viewerFollowedBy = s.viewerFollowedBy,
                        isFriend = s.isFriend, followerCount = s.followerCount
                    ))
                }
            } catch (e: FollowRateLimitedException) {
                _state.value.profile?.let { p -> _state.value = _state.value.copy(profile = p.copy(
                    viewerFollowing = current.viewerFollowing, isFriend = current.isFriend, followerCount = current.followerCount
                )) }
                _state.value = _state.value.copy(
                    rateLimitedUntilMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds() + e.secondsRemaining * 1000
                )
            } catch (_: Exception) {
                val r = offlineRepository.performOrQueue(
                    "$base/api/connect/follow/$userId", if (follow) HttpMethod.Post else HttpMethod.Delete, null)
                if (r is ActionResult.QueuedForRetry) onQueuedForRetry()
            }
        }
    }

    /** Block via the existing RLS-protected social endpoint; queued offline like every other action. */
    fun block(onDone: (Boolean) -> Unit) {
        scope.launch {
            val r = offlineRepository.performOrQueue("$base/api/social/blocked", HttpMethod.Post, """{"userId":"$userId"}""")
            if (r is ActionResult.QueuedForRetry) onQueuedForRetry()
            _state.value.profile?.let { _state.value = _state.value.copy(profile = it.copy(isBlockedByViewer = true)) }
            onDone(true)
        }
    }

    fun unblock(onDone: (Boolean) -> Unit) {
        scope.launch {
            val r = offlineRepository.performOrQueue("$base/api/social/blocked/$userId", HttpMethod.Delete, null)
            if (r is ActionResult.QueuedForRetry) onQueuedForRetry()
            _state.value.profile?.let { _state.value = _state.value.copy(profile = it.copy(isBlockedByViewer = false)) }
            onDone(true)
        }
    }

    /** Report with a reason + free-text details, via the existing report endpoint (rate-limited server-side). */
    fun report(reason: String, details: String?, onDone: (Boolean) -> Unit) {
        scope.launch {
            val text = if (details.isNullOrBlank()) reason else "$reason — ${details.take(400)}"
            val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
            val r = offlineRepository.performOrQueue(
                "$base/api/reports", HttpMethod.Post,
                """{"reportedUserId":"$userId","reason":"$escaped"}"""
            )
            if (r is ActionResult.QueuedForRetry) onQueuedForRetry()
            onDone(true)
        }
    }

    // ---------- subscribe ("Subscribers" stat on the profile) ----------

    fun subscribe() = setSubscribe(true)
    fun unsubscribe() = setSubscribe(false)

    private fun setSubscribe(subscribe: Boolean) {
        val current = _state.value.profile ?: return
        _state.value = _state.value.copy(profile = current.copy(
            viewerSubscribed = subscribe,
            subscriberCount = (current.subscriberCount + if (subscribe) 1 else -1).coerceAtLeast(0)
        ))
        scope.launch {
            try {
                val s = api.setSubscribe(userId, subscribe)
                _state.value.profile?.let { p ->
                    _state.value = _state.value.copy(profile = p.copy(
                        viewerSubscribed = s.viewerSubscribed, subscriberCount = s.subscriberCount
                    ))
                }
            } catch (_: Exception) {
                val r = offlineRepository.performOrQueue(
                    "$base/api/connect/subscribe/$userId", if (subscribe) HttpMethod.Post else HttpMethod.Delete, null)
                if (r is ActionResult.QueuedForRetry) onQueuedForRetry()
            }
        }
    }

    // ---------- connection lists ----------

    fun listState(kind: ConnectionListKind): ConnectionListState = _lists.value[kind] ?: ConnectionListState()

    fun loadList(kind: ConnectionListKind) {
        if (listState(kind).items.isNotEmpty() || listState(kind).loading) return
        refreshList(kind)
    }

    fun refreshList(kind: ConnectionListKind) {
        val id = userId.takeIf { it.isNotBlank() } ?: return
        updateList(kind) { it.copy(loading = true) }
        scope.launch {
            try {
                val page = api.connectionList(id, kind.path, 0)
                updateList(kind) {
                    ConnectionListState(page.items, page.nextOffset, loading = false, endReached = page.nextOffset == null)
                }
            } catch (_: Exception) {
                updateList(kind) { it.copy(loading = false) }
            }
        }
    }

    fun loadMoreList(kind: ConnectionListKind) {
        val s = listState(kind)
        if (s.loading || s.loadingMore || s.endReached) return
        val offset = s.nextOffset ?: return
        val id = userId
        updateList(kind) { it.copy(loadingMore = true) }
        scope.launch {
            try {
                val page = api.connectionList(id, kind.path, offset)
                updateList(kind) {
                    it.copy(
                        items = (it.items + page.items).distinctBy { u -> u.userId },
                        nextOffset = page.nextOffset, loadingMore = false, endReached = page.nextOffset == null
                    )
                }
            } catch (_: Exception) {
                updateList(kind) { it.copy(loadingMore = false) }
            }
        }
    }

    /** Follow/unfollow from inside a list row; updates that row optimistically, then
     *  reconciles every loaded list + the profile header with the server's truth. */
    fun toggleFollowInList(kind: ConnectionListKind, user: ConnectUserDto) {
        val follow = !user.viewerFollowing
        updateList(kind) { s ->
            s.copy(items = s.items.map {
                if (it.userId == user.userId) it.copy(viewerFollowing = follow, isFriend = follow && it.viewerFollowedBy)
                else it
            })
        }
        scope.launch {
            try {
                val s = if (follow) api.follow(user.userId, ContactHash.sha256Hex("${user.userId}:LF").take(24))
                else api.unfollow(user.userId, ContactHash.sha256Hex("${user.userId}:LU").take(24))
                // Write the authoritative relationship into every list that already
                // shows this user, not just the one that was tapped.
                ConnectionListKind.entries.forEach { k ->
                    updateList(k) { st ->
                        st.copy(items = st.items.map {
                            if (it.userId == user.userId) it.copy(
                                viewerFollowing = s.viewerFollowing,
                                viewerFollowedBy = s.viewerFollowedBy,
                                isFriend = s.isFriend
                            ) else it
                        })
                    }
                }
                // Membership in Friends/Followers changed — drop those cached pages so
                // the next open reloads them instead of showing stale rows.
                if (kind != ConnectionListKind.FRIENDS) updateList(ConnectionListKind.FRIENDS) { ConnectionListState() }
                if (kind != ConnectionListKind.FOLLOWERS) updateList(ConnectionListKind.FOLLOWERS) { ConnectionListState() }
                // Keep the profile header consistent: on my own profile my following
                // count changed; on theirs, their follower count did.
                _state.value.profile?.let { p ->
                    _state.value = _state.value.copy(profile =
                        if (p.isOwner) p.copy(
                            followingCount = (p.followingCount + if (follow) 1 else -1).coerceAtLeast(0)
                        ) else if (p.userId == user.userId) p.copy(
                            viewerFollowing = s.viewerFollowing,
                            viewerFollowedBy = s.viewerFollowedBy,
                            isFriend = s.isFriend,
                            followerCount = s.followerCount
                        ) else p
                    )
                }
            } catch (_: Exception) {
                val r = offlineRepository.performOrQueue(
                    "$base/api/connect/follow/${user.userId}", if (follow) HttpMethod.Post else HttpMethod.Delete, null)
                if (r is ActionResult.QueuedForRetry) onQueuedForRetry()
            }
        }
    }

    private fun updateList(kind: ConnectionListKind, f: (ConnectionListState) -> ConnectionListState) {
        _lists.value = _lists.value.toMutableMap().apply { put(kind, f(listState(kind))) }
    }
}
