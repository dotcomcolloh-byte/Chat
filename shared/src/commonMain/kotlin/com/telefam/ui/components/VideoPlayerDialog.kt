package com.telefam.ui.components

import androidx.compose.runtime.Composable

/** Fullscreen in-app video playback (never hands off to an external app). */
@Composable
expect fun VideoPlayerDialog(filePath: String, onDismiss: () -> Unit)
