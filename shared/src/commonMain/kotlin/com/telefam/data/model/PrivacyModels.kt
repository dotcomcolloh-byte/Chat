package com.telefam.data.model

/**
 * Pure identifiers only — no hardcoded English labels here. Every user-facing label
 * is resolved through com.telefam.ui.components.localizedLabel(...) via Compose
 * Multiplatform string resources, so it follows the system language automatically.
 */
enum class AccessLevel { ANYONE, CONTACTS, NOBODY }

enum class ChatsTheme(val colors: List<Long>) {
    LIGHT(listOf(0xFFE8ECF3, 0xFFB8C4D9)),
    DARK(listOf(0xFF1C1C1E, 0xFF3A3A3C)),
    BLUE(listOf(0xFFBFE0F5, 0xFF4FA9DE)),
    PURPLE(listOf(0xFFD9C7F0, 0xFF9B6FD9)),
    GREEN(listOf(0xFFC5E9D6, 0xFF5FBF8B)),
    PINK(listOf(0xFFF6C9D6, 0xFFE888A6))
}

enum class BubbleColour(val color: Long) {
    RED(0xFFD9463C),
    BLUE(0xFF3E8EDE),
    PURPLE(0xFF9B6FD9),
    GREEN(0xFF4FB98A),
    ORANGE(0xFFE0965B),
    GREY(0xFF7C8A9B)
}
