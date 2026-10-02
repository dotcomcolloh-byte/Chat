package com.telefam.chat

import com.telefam.data.model.BubbleColour
import com.telefam.data.model.ChatsTheme
import com.telefam.db.local.DatabaseDriverFactory
import com.telefam.db.local.LocalDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val KEY_THEME = "chats_theme"
private const val KEY_BUBBLE = "message_bubble_colour"

/**
 * Single reactive source of truth for the active chat theme + bubble colour. The
 * Save callback in ChatsThemeScreen/MessageBubbleColourScreen calls `update(...)`
 * immediately after a successful save, so any chat screen on-screen re-themes
 * instantly with no network round-trip - and because the value is also written to
 * AppCache, the same value is restored on next launch even fully offline.
 */
object ThemeController {
    private val _theme = MutableStateFlow(ChatsTheme.LIGHT)
    val theme: StateFlow<ChatsTheme> = _theme

    private val _bubbleColour = MutableStateFlow(BubbleColour.RED)
    val bubbleColour: StateFlow<BubbleColour> = _bubbleColour

    private var cache: AppCacheRepository? = null

    fun init(driverFactory: DatabaseDriverFactory) {
        cache = AppCacheRepository(driverFactory)
        cache?.get(KEY_THEME)?.let { runCatching { ChatsTheme.valueOf(it) }.getOrNull() }?.let { _theme.value = it }
        cache?.get(KEY_BUBBLE)?.let { runCatching { BubbleColour.valueOf(it) }.getOrNull() }?.let { _bubbleColour.value = it }
    }

    fun update(theme: ChatsTheme) {
        _theme.value = theme
        cache?.set(KEY_THEME, theme.name)
    }

    fun update(bubbleColour: BubbleColour) {
        _bubbleColour.value = bubbleColour
        cache?.set(KEY_BUBBLE, bubbleColour.name)
    }
}

class AppCacheRepository(driverFactory: DatabaseDriverFactory) {
    private val queries = LocalDatabase.getInstance(driverFactory).appCacheQueries

    fun get(key: String): String? = queries.selectValue(key).executeAsOneOrNull()?.cachedValue

    fun set(key: String, value: String) {
        queries.upsertValue(key, value, currentTimeMillis())
    }
}

expect fun currentTimeMillis(): Long
