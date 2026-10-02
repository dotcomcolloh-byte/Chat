package com.telefam.notifications

import com.telefam.data.api.NotificationDto
import com.telefam.data.api.NotificationsApi
import com.telefam.data.api.SystemInboxDto
import com.telefam.data.api.SystemMessageDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Notification-centre tabs, matching the reference screen. */
enum class NotificationFilter(val wireValue: String, val label: String) {
    ALL("all", "All"),
    COMMENTS("comments", "Comments"),
    MENTIONS("mentions", "Mentions"),
    SUBSCRIPTIONS("subscriptions", "Subscriptions")
}

data class NotificationsState(
    val items: List<NotificationDto> = emptyList(),
    val nextOffset: Int? = null,
    val unreadCount: Long = 0,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val loadFailed: Boolean = false
)

/**
 * Notification centre state machine: tabbed list (All / Comments / Mentions /
 * Subscriptions), infinite scroll, per-row delete, mark-all-read, and follow-back.
 */
class NotificationsViewModel(
    private val api: NotificationsApi,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    private val _filter = MutableStateFlow(NotificationFilter.ALL)
    val filter: StateFlow<NotificationFilter> = _filter

    private val _state = MutableStateFlow(NotificationsState())
    val state: StateFlow<NotificationsState> = _state

    fun setFilter(f: NotificationFilter) {
        if (_filter.value == f) return
        _filter.value = f
        refresh()
    }

    fun refresh() {
        if (_state.value.refreshing) return
        _state.value = _state.value.copy(refreshing = true, loadFailed = false)
        scope.launch {
            try {
                val page = api.list(_filter.value.wireValue, 0, 30)
                _state.value = NotificationsState(
                    items = page.items, nextOffset = page.nextOffset,
                    unreadCount = page.unreadCount
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(refreshing = false, loadFailed = _state.value.items.isEmpty())
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        val offset = s.nextOffset ?: return
        if (s.loadingMore || s.refreshing) return
        _state.value = s.copy(loadingMore = true)
        scope.launch {
            try {
                val page = api.list(_filter.value.wireValue, offset, 30)
                _state.value = _state.value.copy(
                    items = _state.value.items + page.items,
                    nextOffset = page.nextOffset,
                    unreadCount = page.unreadCount,
                    loadingMore = false
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loadingMore = false)
            }
        }
    }

    /** The per-row 3-dot "Delete". */
    fun delete(id: String) {
        val snapshot = _state.value
        _state.value = snapshot.copy(items = snapshot.items.filterNot { it.id == id })
        scope.launch {
            runCatching { api.delete(id) }
                .onFailure { _state.value = snapshot } // restore on failure
        }
    }

    fun markAllRead() {
        _state.value = _state.value.copy(
            unreadCount = 0,
            items = _state.value.items.map { it.copy(read = true) }
        )
        scope.launch { runCatching { api.markAllRead() } }
    }

    fun markRead(id: String) {
        _state.value = _state.value.copy(
            items = _state.value.items.map { if (it.id == id) it.copy(read = true) else it }
        )
        scope.launch { runCatching { api.markRead(id) } }
    }
}

/**
 * The official-account conversation: server-side, read-only messages
 * (only Telefam can send; there is no composer and no calls).
 */
class OfficialInboxViewModel(
    private val api: NotificationsApi,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    private val _messages = MutableStateFlow<List<SystemMessageDto>>(emptyList())
    val messages: StateFlow<List<SystemMessageDto>> = _messages

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    fun refresh() {
        if (_loading.value) return
        _loading.value = true
        scope.launch {
            try {
                val page = api.systemMessages()
                // Newest last for chat-style rendering.
                _messages.value = page.items.reversed()
                runCatching { api.markSystemMessagesRead() }
            } finally {
                _loading.value = false
            }
        }
    }
}

/** Fetches the two pinned system-conversation headers for the inbox list. */
class SystemInboxViewModel(
    private val api: NotificationsApi,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    private val _inbox = MutableStateFlow<SystemInboxDto?>(null)
    val inbox: StateFlow<SystemInboxDto?> = _inbox

    fun refresh() {
        scope.launch {
            runCatching { api.systemInbox() }.onSuccess { _inbox.value = it }
        }
    }
}
