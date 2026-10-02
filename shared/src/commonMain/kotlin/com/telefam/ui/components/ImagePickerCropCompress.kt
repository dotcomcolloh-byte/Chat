package com.telefam.ui.components

import androidx.compose.runtime.Composable

/** Result of the platform image picker + crop flow: already-cropped, already-compressed bytes ready to upload. */
data class ProcessedImage(val bytes: ByteArray, val mimeType: String, val width: Int, val height: Int)

/**
 * Launches the platform-native picker -> crop UI -> client-side compression pipeline.
 * Real implementations live in androidMain/iosMain (expect/actual) since image picking
 * and cropping UI are inherently platform APIs. The server independently re-validates,
 * re-encodes and re-compresses everything regardless of what the client sends (see
 * MediaProcessor.kt on the backend) — client-side compression here is only to save
 * the user's bandwidth, never trusted as the security boundary.
 */
@Composable
expect fun rememberImagePickerCropCompress(
    maxDimension: Int = 1024,
    jpegQuality: Float = 0.85f,
    onResult: (ProcessedImage?) -> Unit
): () -> Unit
