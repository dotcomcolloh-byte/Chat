package com.telefam.accessibility

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Accessibility settings applied app-wide. Backed by the server-synced
 * AppSettingsDto (Settings → Accessibility), applied in the composition root:
 *
 *  - textSize: scales the entire UI font density via LocalDensity.
 *  - reducedMotion: screens read [state] to skip non-essential animation.
 *  - highContrast: TelefamTheme switches to the high-contrast palette.
 *  - captionsEnabled: the video player renders captions when available.
 */
object AccessibilityController {

    data class State(
        val textSize: String = "MEDIUM",   // SMALL | MEDIUM | LARGE | XLARGE
        val captionsEnabled: Boolean = false,
        val reducedMotion: Boolean = false,
        val highContrast: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** Font-scale factor applied at the composition root. */
    val fontScale: Float
        get() = when (_state.value.textSize) {
            "SMALL" -> 0.85f
            "LARGE" -> 1.15f
            "XLARGE" -> 1.3f
            else -> 1.0f
        }

    fun update(
        textSize: String? = null,
        captionsEnabled: Boolean? = null,
        reducedMotion: Boolean? = null,
        highContrast: Boolean? = null
    ) {
        _state.value = _state.value.copy(
            textSize = textSize ?: _state.value.textSize,
            captionsEnabled = captionsEnabled ?: _state.value.captionsEnabled,
            reducedMotion = reducedMotion ?: _state.value.reducedMotion,
            highContrast = highContrast ?: _state.value.highContrast
        )
    }

    fun apply(dto: com.telefam.data.api.AppSettingsDto) {
        _state.value = State(dto.textSize, dto.captionsEnabled, dto.reducedMotion, dto.highContrast)
    }
}
