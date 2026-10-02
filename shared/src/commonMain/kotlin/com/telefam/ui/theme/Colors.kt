package com.telefam.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Colors sampled directly from the provided Telefam reference screens
 * (Sign up / Profile Setup). Not invented — the deep red matches the
 * paper-plane logo, "Telefam" wordmark, primary buttons and bottom wave.
 */
object TelefamColors {
    val PrimaryRed = Color(0xFFD32323)       // main brand red (logo, wordmark, buttons, wave)
    val PrimaryRedDark = Color(0xFFB31217)   // gradient-dark edge of the logo circle
    val BackgroundWash = Color(0xFFFBEAEA)   // top curved wash behind the logo
    val TextDark = Color(0xFF1A1A1A)         // "Full Name", "Username" labels
    val TextMuted = Color(0xFF767676)        // placeholder / subtitle text
    val FieldBorder = Color(0xFFF0D6D6)      // input outline
    val White = Color(0xFFFFFFFF)

    // Dark-theme counterparts. Same red identity, dark surfaces per Material guidance.
    val PrimaryRedLightAccent = Color(0xFFFF6B6B) // used sparingly on dark surfaces for contrast
    val DarkBackground = Color(0xFF121212)
    val DarkSurface = Color(0xFF1E1E1E)
    val DarkFieldBorder = Color(0xFF3A2E2E)
    val TextMutedDark = Color(0xFFB0B0B0)
    val SearchBarLight = Color(0xFFF0F1F3)
    val SearchBarDark = Color(0xFF2A2A2A)
}

/** Official Google brand colors (per Google's own Sign-In branding guidelines) — used only on the "G" mark, never on our own buttons. */
object GoogleBrandColors {
    val Blue = Color(0xFF4285F4)
    val Green = Color(0xFF34A853)
    val Yellow = Color(0xFFFBBC05)
    val Red = Color(0xFFEA4335)
    val ButtonBackground = Color(0xFFFFFFFF)
    val ButtonBorder = Color(0xFFDADCE0)
    val ButtonText = Color(0xFF3C4043) // Google's specified "dark gray" button text
}

/** Apple's official Sign in with Apple button colors (black button / white button per Apple HIG). */
object AppleBrandColors {
    val ButtonBlack = Color(0xFF000000)
    val ButtonWhite = Color(0xFFFFFFFF)
    val ButtonBorderOnWhite = Color(0xFF000000)
}
