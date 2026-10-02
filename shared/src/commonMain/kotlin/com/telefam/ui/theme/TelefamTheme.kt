package com.telefam.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightScheme = lightColorScheme(
    primary = TelefamColors.PrimaryRed,
    onPrimary = TelefamColors.White,
    background = TelefamColors.White,
    surface = TelefamColors.White,
    onBackground = TelefamColors.TextDark,
    onSurface = TelefamColors.TextDark,
    surfaceVariant = TelefamColors.SearchBarLight,
    outline = TelefamColors.FieldBorder,
    error = Color(0xFFB3261E)
)

private val DarkScheme = darkColorScheme(
    primary = TelefamColors.PrimaryRed,
    onPrimary = TelefamColors.White,
    background = TelefamColors.DarkBackground,
    surface = TelefamColors.DarkSurface,
    onBackground = TelefamColors.White,
    onSurface = TelefamColors.White,
    surfaceVariant = TelefamColors.SearchBarDark,
    outline = TelefamColors.DarkFieldBorder,
    error = Color(0xFFF2B8B5)
)

/**
 * Follows the device's system light/dark setting by default (no manual toggle yet —
 * that's a future "settings" feature per the request). Wrap the whole app in this once,
 * at the root, in MainActivity / the iOS entry point.
 */
@Composable
fun TelefamTheme(useDarkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (useDarkTheme) DarkScheme else LightScheme,
        content = content
    )
}
