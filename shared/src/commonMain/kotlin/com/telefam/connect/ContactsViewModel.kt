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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

sealed class ContactsMode {
    object Suggestions : ContactsMode()
    data class Search(val query: String) : ContactsMode()
}

data class ContactsListState(
    val items: List<ConnectUserDto> = emptyList(),
    val nextOffset: Int? = null,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val loadFailed: Boolean = false,
    val fromCache: Boolean = false,
    /** Related search chips for the current query. */
    val relatedTerms: List<String> = emptyList(),
    /** Set when a follow attempt hit the server rate limit; UI shows a quiet hint. */
    val rateLimitedUntilMs: Long = 0L,
    val contactsSynced: Boolean = false,
    val contactsPermissionAsked: Boolean = false
)

/**
 * Contacts / Discover state machine ("People you may know" + search).
 *
 * - Infinite scroll + pull-to-refresh over offset pagination.
 * - Offline-first: the last successful page per mode is cached in AppCache and shown
 *   when the network is down (fromCache = true → the screen shows the offline state);
 *   when connectivity returns the list auto-refreshes and queued follow actions replay.
 * - Follow actions are optimistic, carry a stable idempotency key, and fall back to
 *   the outbox queue offline — the server treats replays as no-ops.
 */
class ContactsViewModel(
    private val api: ConnectApi,
    private val offlineRepository: OfflineActionRepository,
    private val deviceContacts: DeviceContacts?,
    driverFactory: DatabaseDriverFactory,
    private val onQueuedForRetry: () -> Unit = {},
    connectivity: Flow<Boolean> = observeConnectivity(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = LocalDatabase.getInstance(driverFactory).appCacheQueries
    private val base get() = com.telefam.data.api.ApiConfig.baseUrl.trimEnd('/')

    private val _state = MutableStateFlow(ContactsListState())
    val state: StateFlow<ContactsListState> = _state

    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online

    var mode: ContactsMode = ContactsMode.Suggestions
        private set

    private var loadGeneration = 0L
    private var searchJob: Job? = null

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

    private fun cacheKey(m: ContactsMode) = when (m) {
        is ContactsMode.Suggestions -> "connect_suggestions"
        is ContactsMode.Search -> "connect_search_${m.query.hashCode()}"
    }

    // ---------- loading ----------

    fun loadInitial() = refresh()

    fun refresh() {
        val generation = ++loadGeneration
        val m = mode
        scope.launch {
            _state.value = _state.value.copy(refreshing = true, loadFailed = false)
            try {
                val page = fetchPage(m, 0)
                if (generation != loadGeneration) return@launch
                _state.value = _state.value.copy(
                    items = page.items, nextOffset = page.nextOffset, refreshing = false,
                    endReached = page.nextOffset == null, fromCache = false, loadFailed = false
                )
                persistCache(m, page)
                if (m is ContactsMode.Search) loadRelated(m.query)
            } catch (e: Exception) {
                if (generation != loadGeneration) return@launch
                val cached = readCache(m)
                _state.value = _state.value.copy(
                    refreshing = false,
                    items = cached?.items ?: _state.value.items,
                    nextOffset = cached?.nextOffset,
                    fromCache = cached != null,
                    loadFailed = cached == null && _state.value.items.isEmpty()
                )
            }
        }
    }

    /** Debounced search-as-you-type. */
    fun search(query: String) {
        val q = query.trim()
        if (q.length < 2) { if (mode !is ContactsMode.Suggestions) switchToSuggestions() else return }
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(300)
            mode = ContactsMode.Search(q)
            refresh()
        }
    }

    fun switchToSuggestions() {
        if (mode is ContactsMode.Suggestions) return
        mode = ContactsMode.Suggestions
        refresh()
    }

    /** Lazy loading / infinite scroll: call with the last visible index. */
    fun ensureLoadedAhead(visibleIndex: Int) {
        val snapshot = _state.value
        if (snapshot.loadingMore || snapshot.refreshing || snapshot.endReached || snapshot.fromCache) return
        val offset = snapshot.nextOffset ?: return
        if (visibleIndex < snapshot.items.size - 4) return
        val m = mode
        scope.launch {
            if (_state.value.loadingMore || _state.value.nextOffset != offset) return@launch
            _state.value = _state.value.copy(loadingMore = true)
            try {
                val page = fetchPage(m, offset)
                if (mode != m || _state.value.nextOffset != offset) return@launch
                val merged = (_state.value.items + page.items).distinctBy { it.userId }
                _state.value = _state.value.copy(
                    items = merged, nextOffset = page.nextOffset,
                    loadingMore = false, endReached = page.nextOffset == null
                )
            } catch (_: Exception) {
                _state.value = _state.value.copy(loadingMore = false)
            }
        }
    }

    private suspend fun fetchPage(m: ContactsMode, offset: Int): ConnectPageDto = when (m) {
        is ContactsMode.Suggestions -> api.suggestions(offset)
        is ContactsMode.Search -> api.search(m.query, offset)
    }

    private suspend fun loadRelated(query: String) {
        runCatching { api.relatedSearch(query) }
            .onSuccess { _state.value = _state.value.copy(relatedTerms = it.terms) }
    }

    private fun persistCache(m: ContactsMode, page: ConnectPageDto) = runCatching {
        cache.upsertValue(cacheKey(m), json.encodeToString(ConnectPageDto.serializer(), page),
            kotlinx.datetime.Clock.System.now().toEpochMilliseconds())
    }

    private fun readCache(m: ContactsMode): ConnectPageDto? = runCatching {
        cache.selectValue(cacheKey(m)).executeAsOneOrNull()
            ?.let { json.decodeFromString(ConnectPageDto.serializer(), it.cachedValue) }
    }.getOrNull()

    // ---------- follow / unfollow ----------

    /** Stable idempotency key per (target, action) — retries and outbox replays collapse on the server. */
    private fun idempotencyKey(targetId: String, follow: Boolean) =
        ContactHash.sha256Hex("$targetId:${if (follow) "F" else "U"}").take(24)

    private fun mutate(userId: String, f: (ConnectUserDto) -> ConnectUserDto) {
        _state.value = _state.value.copy(items = _state.value.items.map { if (it.userId == userId) f(it) else it })
    }

    fun follow(user: ConnectUserDto) = setFollow(user, true)
    fun unfollow(user: ConnectUserDto) = setFollow(user, false)

    private fun setFollow(user: ConnectUserDto, follow: Boolean) {
        // Optimistic update: follow-back pairs become friends immediately.
        mutate(user.userId) {
            if (follow) it.copy(viewerFollowing = true, isFriend = it.viewerFollowedBy)
            else it.copy(viewerFollowing = false, isFriend = false)
        }
        scope.launch {
            try {
                val state = if (follow) api.follow(user.userId, idempotencyKey(user.userId, true))
                else api.unfollow(user.userId, idempotencyKey(user.userId, false))
                mutate(user.userId) {
                    it.copy(viewerFollowing = state.viewerFollowing,
                        viewerFollowedBy = state.viewerFollowedBy, isFriend = state.isFriend)
                }
            } catch (e: FollowRateLimitedException) {
                // Roll back the optimistic flip; the server is asking us to slow down.
                mutate(user.userId) {
                    if (follow) it.copy(viewerFollowing = false, isFriend = false) else it
                }
                _state.value = _state.value.copy(
                    rateLimitedUntilMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds() + e.secondsRemaining * 1000
                )
            } catch (_: Exception) {
                // Offline / server error: queue the exact idempotent action for silent replay.
                val path = "$base/api/connect/follow/${user.userId}"
                val result = offlineRepository.performOrQueue(path, if (follow) HttpMethod.Post else HttpMethod.Delete, null)
                when (result) {
                    is ActionResult.QueuedForRetry -> onQueuedForRetry()
                    is ActionResult.PermanentFailure -> mutate(user.userId) {
                        if (follow) it.copy(viewerFollowing = false, isFriend = false)
                        else it.copy(viewerFollowing = true)
                    }
                    is ActionResult.SentImmediately -> Unit
                }
            }
        }
    }

    // ---------- device contact sync ----------

    /** Ask for contacts permission once, then hash + upload and refresh suggestions. */
    fun syncDeviceContacts() {
        val contacts = deviceContacts ?: return
        if (_state.value.contactsPermissionAsked) return
        _state.value = _state.value.copy(contactsPermissionAsked = true)
        when (contacts.permissionStatus()) {
            ContactsPermissionStatus.GRANTED -> uploadHashes(contacts)
            else -> contacts.requestPermission { status ->
                if (status == ContactsPermissionStatus.GRANTED) uploadHashes(contacts)
            }
        }
    }

    private fun uploadHashes(contacts: DeviceContacts) {
        scope.launch {
            try {
                val entries = contacts.readContacts()
                val hashes = buildSet {
                    for (e in entries) {
                        e.phones.forEach { p -> ContactHash.phoneHash(p)?.let { add(ContactHashEntry("PHONE", it)) } }
                        e.emails.forEach { m -> ContactHash.emailHash(m)?.let { add(ContactHashEntry("EMAIL", it)) } }
                    }
                }.toList()
                if (hashes.isNotEmpty()) api.uploadContactHashes(hashes)
                _state.value = _state.value.copy(contactsSynced = true)
                refresh()
            } catch (_: Exception) {
                // Silent: suggestions still work from the graph/location signals.
            }
        }
    }
}
