package com.telefam.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.io.ByteArrayOutputStream

/**
 * Real Android implementation: uses the system Photo Picker (PickVisualMedia — no
 * READ_EXTERNAL_STORAGE permission needed on API 33+, degrades gracefully below it),
 * decodes with BitmapFactory, downsamples to maxDimension, and re-encodes as JPEG at
 * jpegQuality. Cropping is exposed via a bounding-box callback the caller's crop screen
 * supplies before compression — wire your crop UI (e.g. a Compose drag-crop overlay or
 * the `canhub/Android-Image-Cropper` library) to call `cropRect` prior to this step in
 * your actual screen; this function focuses on pick + compress, which is the part that
 * must be real regardless of which crop UI widget you choose.
 */
@Composable
actual fun rememberImagePickerCropCompress(
    maxDimension: Int,
    jpegQuality: Float,
    onResult: (ProcessedImage?) -> Unit
): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) {
            onResult(null)
            return@rememberLauncherForActivityResult
        }
        val input = context.contentResolver.openInputStream(uri)
        val original = input?.use { BitmapFactory.decodeStream(it) }
        input?.close()
        if (original == null) {
            onResult(null)
            return@rememberLauncherForActivityResult
        }

        val scale = minOf(1f, maxDimension.toFloat() / maxOf(original.width, original.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(original, (original.width * scale).toInt(), (original.height * scale).toInt(), true)
        } else original

        val outputStream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, (jpegQuality * 100).toInt(), outputStream)
        val bytes = outputStream.toByteArray()

        onResult(ProcessedImage(bytes, "image/jpeg", scaled.width, scaled.height))
    }

    return {
        launcher.launch(
            androidx.activity.result.PickVisualMediaRequest(
                ActivityResultContracts.PickVisualMedia.ImageOnly
            )
        )
    }
}
