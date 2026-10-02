package com.telefam.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.telefam.data.model.BubbleColour
import com.telefam.data.model.ChatsTheme

fun chatColorScheme(theme: ChatsTheme, accent: Color): ColorScheme = when (theme) {
    ChatsTheme.LIGHT -> lightColorScheme(
        primary = accent, background = Color(0xFFF7F8FB), surface = Color.White,
        surfaceVariant = Color(0xFFEEF0F4), onSurface = Color(0xFF1A1A1A), onBackground = Color(0xFF1A1A1A), outline = Color(0xFFDADDE3)
    )
    ChatsTheme.DARK -> darkColorScheme(
        primary = accent, background = Color(0xFF121212), surface = Color(0xFF1F1F22),
        surfaceVariant = Color(0xFF2A2A2E), onSurface = Color(0xFFF2F2F2), onBackground = Color(0xFFF2F2F2), outline = Color(0xFF3A3A3F)
    )
    ChatsTheme.BLUE -> lightColorScheme(
        primary = accent, background = Color(0xFFEAF4FB), surface = Color.White,
        surfaceVariant = Color(0xFFDCEBF7), onSurface = Color(0xFF14202B), onBackground = Color(0xFF14202B), outline = Color(0xFFC5DBEC)
    )
    ChatsTheme.PURPLE -> lightColorScheme(
        primary = accent, background = Color(0xFFF1EAFB), surface = Color.White,
        surfaceVariant = Color(0xFFE6DCF5), onSurface = Color(0xFF211A2B), onBackground = Color(0xFF211A2B), outline = Color(0xFFD7C9EC)
    )
    ChatsTheme.GREEN -> lightColorScheme(
        primary = accent, background = Color(0xFFEAF7F0), surface = Color.White,
        surfaceVariant = Color(0xFFDAEEE3), onSurface = Color(0xFF15261D), onBackground = Color(0xFF15261D), outline = Color(0xFFC3E0D0)
    )
    ChatsTheme.PINK -> lightColorScheme(
        primary = accent, background = Color(0xFFFCEBF0), surface = Color.White,
        surfaceVariant = Color(0xFFF6DCE4), onSurface = Color(0xFF2B1A20), onBackground = Color(0xFF2B1A20), outline = Color(0xFFEBC9D4)
    )
}

/** Wraps chat content so all bubbles/inputs/dialogs inside pick up the selected chat theme and bubble colour instantly. */
@Composable
fun ChatThemeScope(theme: ChatsTheme, bubbleColour: BubbleColour, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = chatColorScheme(theme, Color(bubbleColour.color)), content = content)
}
