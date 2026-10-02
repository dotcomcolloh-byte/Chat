package com.telefam.chat

import androidx.compose.runtime.Composable

/** Kinds of files the attachment sheet can ask the platform picker for. */
enum class PickKind { IMAGE, VIDEO, DOCUMENT, AUDIO }

/** A file the user picked, already copied into app-private storage by the platform layer. */
data class PickedFile(
    val path: String,
    val displayName: String?,
    val mimeType: String?,
    val sizeBytes: Long,
    val durationSeconds: Int? = null
)

/**
 * Returns a launch lambda. Calling it opens the platform picker for [kind];
 * the result lands in [onPicked] (null = cancelled). The picked content is copied
 * into app-private storage before the callback fires, so the path is stable.
 */
@Composable
expect fun rememberMediaPicker(kind: PickKind, onPicked: (PickedFile?) -> Unit): () -> Unit

/** Same contract, but opens the camera to capture a fresh photo. */
@Composable
expect fun rememberCameraCapture(onCaptured: (PickedFile?) -> Unit): () -> Unit
