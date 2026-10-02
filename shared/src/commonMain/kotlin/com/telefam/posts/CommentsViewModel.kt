package com.telefam.posts

import com.telefam.data.api.CommentsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Per-thread lazy-reply state, keyed by the thread's root comment id. */
data class ReplyThreadState(
    val items: List<CommentDto> = emptyList(),
    val nextCursor: String? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val expanded: Boolean = false,
    val endReached: Boolean = false,
    val failed: Boolean = false,
    /** True once the first server page has been fetched (items may hold only local optimistic rows before that). */
    val loaded: Boolean = false
)

data class CommentsState(
    val comments: List<CommentDto> = emptyList(),
    val pinned: CommentDto? = null,
    val nextCursor: String? = null,
    val totalCount: Long = 0,
    val commenting: String = "EVERYONE",
    val viewerCanComment: Boolean = true,
    val viewerIsPostOwner: Boolean = false,
    val loadingInitial: Boolean = false,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val loadFailed: Boolean = false,
    val sending: Boolean = false,
    /** Reply threads keyed by root comment id. */
    val threads: Map<String, ReplyThreadState> = emptyMap(),
    /** Search mode: non-null query replaces the comment stream with results. */
    val searchQuery: String? = null,
    val searchCursor: String? = null,
    val searchLoading: Boolean = false,
    val searchEndReached: Boolean = false,
    val searchResults: List<CommentDto> = emptyList()
)

/**
 * Comments state machine, one instance per open comment sheet.
 *
 * Production behaviors:
 *  - Keyset-paginated infinite scroll for both top-level comments and per-thread replies
 *    (replies load lazily only when the user expands a thread).
 *  - All mutations (like, delete, edit, pin) are optimistic with rollback on failure.
 *  - Connectivity-driven: when offline, mutations are rejected with a snackbar and the
 *    stream shows the no-internet banner; on reconnect the stream refreshes itself.
 */
class CommentsViewModel(
    private val api: CommentsApi,
    private val connectivity: Flow<Boolean> = observeConnectivity(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    private val _state = MutableStateFlow(CommentsState())
    val state: StateFlow<CommentsState> = _state

    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online

    /** One-shot UI messages (snackbar text). */
    private val _events = MutableStateFlow<String?>(null)
    val events: StateFlow<String?> = _events

    var postId: String = ""
        private set
    private var loadJob: Job? = null
    private var searchJob: Job? = null

    init {
        scope.launch {
            var wasOffline = false
            connectivity.collect { up ->
                _online.value = up
                if (!up) wasOffline = true
                else if (wasOffline) {
                    wasOffline = false
                    if (postId.isNotEmpty()) refresh()
                }
            }
        }
    }

    fun consumeEvent() { _events.value = null }

    // ------------------------------------------------------------- loading

    fun load(postId: String) {
        if (this.postId == postId && _state.value.comments.isNotEmpty()) return
        this.postId = postId
        _state.value = CommentsState(loadingInitial = true)
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                val page = api.list(postId)
                _state.value = CommentsState(
                    comments = page.items,
                    pinned = page.pinned,
                    nextCursor = page.nextCursor,
                    totalCount = page.totalCount,
                    commenting = page.commenting,
                    viewerCanComment = page.viewerCanComment,
                    viewerIsPostOwner = page.viewerIsPostOwner,
                    endReached = page.nextCursor == null
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loadingInitial = false, loadFailed = true)
            }
        }
    }

    fun refresh(keepThreads: Boolean = false) {
        if (postId.isEmpty()) return
        val keep = _state.value
        loadJob?.cancel()
        loadJob = scope.launch {
            _state.value = keep.copy(loadingInitial = keep.comments.isEmpty())
            try {
                val page = api.list(postId)
                _state.value = _state.value.copy(
                    comments = page.items,
                    pinned = page.pinned,
                    nextCursor = page.nextCursor,
                    totalCount = page.totalCount,
                    commenting = page.commenting,
                    viewerCanComment = page.viewerCanComment,
                    viewerIsPostOwner = page.viewerIsPostOwner,
                    endReached = page.nextCursor == null,
                    loadingInitial = false,
                    loadFailed = false,
                    loadingMore = false,
                    threads = if (keepThreads) _state.value.threads else emptyMap()
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loadingInitial = false, loadFailed = _state.value.comments.isEmpty())
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.searchQuery != null) return loadMoreSearch()
        if (s.loadingInitial || s.loadingMore || s.endReached || s.nextCursor == null) return
        _state.value = s.copy(loadingMore = true)
        loadJob = scope.launch { // tracked so refresh()/load() cancel a stale page
            try {
                val page = api.list(postId, s.nextCursor)
                _state.value = _state.value.copy(
                    comments = (_state.value.comments + page.items).distinctBy { it.commentId },
                    nextCursor = page.nextCursor,
                    totalCount = page.totalCount,
                    loadingMore = false,
                    endReached = page.nextCursor == null
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loadingMore = false)
            }
        }
    }

    // ------------------------------------------------------------- lazy replies

    /** Expand a thread (fetching the first reply page) or collapse it. */
    fun toggleReplies(rootId: String) {
        val thread = _state.value.threads[rootId]
        if (thread?.expanded == true) {
            _state.value = _state.value.copy(
                threads = _state.value.threads + (rootId to thread.copy(expanded = false))
            )
            return
        }
        if (thread != null && thread.loaded) {
            _state.value = _state.value.copy(
                threads = _state.value.threads + (rootId to thread.copy(expanded = true))
            )
            return
        }
        _state.value = _state.value.copy(
            threads = _state.value.threads + (rootId to ReplyThreadState(loading = true, expanded = true))
        )
        scope.launch {
            try {
                val page = api.replies(postId, rootId)
                val t = _state.value.threads[rootId] ?: ReplyThreadState()
                _state.value = _state.value.copy(
                    threads = _state.value.threads + (rootId to t.copy(
                        items = page.items, nextCursor = page.nextCursor,
                        loading = false, expanded = true, endReached = page.nextCursor == null, loaded = true
                    ))
                )
            } catch (e: Exception) {
                val t = _state.value.threads[rootId] ?: ReplyThreadState()
                _state.value = _state.value.copy(
                    threads = _state.value.threads + (rootId to t.copy(loading = false, failed = true, expanded = true))
                )
            }
        }
    }

    fun loadMoreReplies(rootId: String) {
        val t = _state.value.threads[rootId] ?: return
        if (t.loadingMore || t.endReached || t.nextCursor == null) return
        _state.value = _state.value.copy(
            threads = _state.value.threads + (rootId to t.copy(loadingMore = true))
        )
        scope.launch {
            try {
                val page = api.replies(postId, rootId, t.nextCursor)
                val cur = _state.value.threads[rootId] ?: return@launch
                _state.value = _state.value.copy(
                    threads = _state.value.threads + (rootId to cur.copy(
                        items = (cur.items + page.items).distinctBy { it.commentId },
                        nextCursor = page.nextCursor,
                        loadingMore = false,
                        endReached = page.nextCursor == null
                    ))
                )
            } catch (e: Exception) {
                val cur = _state.value.threads[rootId] ?: return@launch
                _state.value = _state.value.copy(
                    threads = _state.value.threads + (rootId to cur.copy(loadingMore = false))
                )
            }
        }
    }

    // ------------------------------------------------------------- search

    fun search(query: String) {
        val q = query.trim()
        searchJob?.cancel()
        if (q.length < 2) {
            _state.value = _state.value.copy(
                searchQuery = null, searchResults = emptyList(), searchCursor = null, searchEndReached = false
            )
            return
        }
        _state.value = _state.value.copy(searchQuery = q, searchLoading = true)
        searchJob = scope.launch {
            try {
                val page = api.search(postId, q)
                _state.value = _state.value.copy(
                    searchResults = page.items, searchCursor = page.nextCursor,
                    searchLoading = false, searchEndReached = page.nextCursor == null
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(searchLoading = false)
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _state.value = _state.value.copy(
            searchQuery = null, searchResults = emptyList(), searchCursor = null, searchEndReached = false
        )
    }

    private fun loadMoreSearch() {
        val s = _state.value
        val q = s.searchQuery ?: return
        if (s.searchLoading || s.searchEndReached || s.searchCursor == null) return
        _state.value = s.copy(searchLoading = true)
        scope.launch {
            try {
                val page = api.search(postId, q, s.searchCursor)
                _state.value = _state.value.copy(
                    searchResults = (_state.value.searchResults + page.items).distinctBy { it.commentId },
                    searchCursor = page.nextCursor,
                    searchLoading = false,
                    searchEndReached = page.nextCursor == null
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(searchLoading = false)
            }
        }
    }

    // ------------------------------------------------------------- mutations

    /** Optimistic create; on failure the pending comment is removed and an event fires. */
    fun send(req: CreateCommentRequest, optimistic: CommentDto, replyToRoot: String?) {
        if (!_online.value) { _events.value = "No internet connection"; return }
        if (_state.value.sending) return
        _state.value = _state.value.copy(sending = true)
        if (replyToRoot == null) {
            _state.value = _state.value.copy(
                comments = listOf(optimistic) + _state.value.comments,
                totalCount = _state.value.totalCount + 1
            )
        } else {
            val t = _state.value.threads[replyToRoot]
            // Only append locally when the thread's server page is already loaded; otherwise
            // the real thread is fetched after the send (avoids a thread showing only my reply).
            _state.value = _state.value.copy(
                threads = if (t != null && t.loaded) _state.value.threads +
                    (replyToRoot to t.copy(items = t.items + optimistic, expanded = true))
                else _state.value.threads,
                comments = _state.value.comments.map {
                    if (it.commentId == replyToRoot) it.copy(replyCount = it.replyCount + 1) else it
                }
            )
        }
        scope.launch {
            try {
                val saved = api.create(postId, req)
                replaceComment(optimistic.commentId, saved)
                _state.value = _state.value.copy(sending = false)
                if (replyToRoot != null && _state.value.threads[replyToRoot]?.loaded != true) {
                    _state.value = _state.value.copy(threads = _state.value.threads - replyToRoot)
                    toggleReplies(replyToRoot) // fetch the full thread, expanded
                }
            } catch (e: Exception) {
                removeComment(optimistic.commentId, replyToRoot)
                _state.value = _state.value.copy(sending = false)
                _events.value = "Couldn't send. Try again."
            }
        }
    }

    fun toggleLike(comment: CommentDto) {
        if (!_online.value) { _events.value = "No internet connection"; return }
        val liked = !comment.viewerLiked
        val optimistic = comment.copy(
            viewerLiked = liked,
            likeCount = (comment.likeCount + if (liked) 1 else -1).coerceAtLeast(0)
        )
        replaceComment(comment.commentId, optimistic)
        scope.launch {
            try {
                val res = if (liked) api.like(comment.commentId) else api.unlike(comment.commentId)
                replaceComment(comment.commentId, optimistic.copy(likeCount = res.likeCount))
            } catch (e: Exception) {
                replaceComment(comment.commentId, comment) // rollback
                _events.value = "Couldn't update like"
            }
        }
    }

    fun edit(comment: CommentDto, newBody: String) {
        if (!_online.value) { _events.value = "No internet connection"; return }
        val before = comment
        replaceComment(comment.commentId, comment.copy(body = newBody, edited = true))
        scope.launch {
            try {
                val saved = api.edit(comment.commentId, newBody)
                replaceComment(comment.commentId, saved)
            } catch (e: Exception) {
                replaceComment(comment.commentId, before)
                _events.value = "Couldn't edit comment"
            }
        }
    }

    fun delete(comment: CommentDto) {
        if (!_online.value) { _events.value = "No internet connection"; return }
        val deleted = comment.copy(deleted = true, body = null, mediaUrl = null, stickerUrl = null, pinnedByOwner = false)
        replaceComment(comment.commentId, deleted)
        adjustCounts(comment, -1)
        if (_state.value.pinned?.commentId == comment.commentId) {
            _state.value = _state.value.copy(pinned = null)
        }
        scope.launch {
            try {
                api.delete(comment.commentId)
            } catch (e: Exception) {
                replaceComment(comment.commentId, comment)
                adjustCounts(comment, +1)
                _events.value = "Couldn't delete comment"
            }
        }
    }

    fun setPinned(comment: CommentDto, pinned: Boolean) {
        if (!_online.value) { _events.value = "No internet connection"; return }
        scope.launch {
            try {
                if (pinned) api.pin(comment.commentId) else api.unpin(comment.commentId)
                refresh(keepThreads = true)
            } catch (e: Exception) {
                _events.value = "Couldn't update pin"
            }
        }
    }

    fun report(commentId: String, reason: String, details: String?, onDone: (Boolean) -> Unit) {
        if (!_online.value) { _events.value = "No internet connection"; onDone(false); return }
        scope.launch {
            val ok = runCatching { api.report(commentId, reason, details) }.isSuccess
            onDone(ok)
        }
    }

    // ------------------------------------------------------------- stars

    sealed class GiftResult {
        data class Success(val balanceStars: Long, val commentStarTotal: Long) : GiftResult()
        data class Insufficient(val balanceStars: Long, val requiredStars: Long) : GiftResult()
        data class Failed(val message: String) : GiftResult()
    }

    suspend fun giftStars(commentId: String, stars: Long, idempotencyKey: String): GiftResult {
        if (!_online.value) return GiftResult.Failed("No internet connection")
        return try {
            val res = api.giftStars(commentId, stars, idempotencyKey)
            // Reflect the new total on the comment immediately.
            updateComment(commentId) { it.copy(starTotal = res.commentStarTotal) }
            GiftResult.Success(res.balanceStars, res.commentStarTotal)
        } catch (e: InsufficientStarsException) {
            GiftResult.Insufficient(e.balanceStars, e.requiredStars)
        } catch (e: Exception) {
            GiftResult.Failed("Couldn't send stars. Try again.")
        }
    }

    // ------------------------------------------------------------- helpers

    /** Keep totalCount / root replyCount in sync with live (non-deleted) comments. */
    private fun adjustCounts(c: CommentDto, delta: Int) {
        val s = _state.value
        _state.value = s.copy(
            totalCount = (s.totalCount + delta).coerceAtLeast(0),
            comments = if (c.parentId == null) s.comments else s.comments.map {
                if (it.commentId == c.rootId) it.copy(replyCount = (it.replyCount + delta).coerceAtLeast(0)) else it
            }
        )
    }

    private fun replaceComment(id: String, updated: CommentDto) {
        updateComment(id) { updated }
    }

    private fun updateComment(id: String, transform: (CommentDto) -> CommentDto) {
        val s = _state.value
        _state.value = s.copy(
            comments = s.comments.map { if (it.commentId == id) transform(it) else it },
            pinned = s.pinned?.let { if (it.commentId == id) transform(it) else it },
            searchResults = s.searchResults.map { if (it.commentId == id) transform(it) else it },
            threads = s.threads.mapValues { (_, t) ->
                if (t.items.any { it.commentId == id })
                    t.copy(items = t.items.map { if (it.commentId == id) transform(it) else it })
                else t
            }
        )
    }

    private fun removeComment(id: String, rootId: String?) {
        val s = _state.value
        if (rootId == null) {
            _state.value = s.copy(
                comments = s.comments.filterNot { it.commentId == id },
                totalCount = (s.totalCount - 1).coerceAtLeast(0)
            )
        } else {
            val t = s.threads[rootId]
            _state.value = s.copy(
                threads = if (t != null) s.threads + (rootId to t.copy(items = t.items.filterNot { it.commentId == id }))
                else s.threads,
                comments = s.comments.map {
                    if (it.commentId == rootId) it.copy(replyCount = (it.replyCount - 1).coerceAtLeast(0)) else it
                }
            )
        }
    }
}
