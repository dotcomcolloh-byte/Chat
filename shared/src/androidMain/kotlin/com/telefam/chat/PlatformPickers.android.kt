package com.telefam.chat

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

private fun copyIntoAppStorage(context: android.content.Context, uri: Uri, prefix: String): PickedFile? {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri)
    var displayName: String? = null
    var size = 0L
    runCatching {
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (nameIdx >= 0) displayName = cursor.getString(nameIdx)
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }
    }
    val ext = displayName?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }
        ?: mime?.substringAfterLast('/')?.replace("jpeg", "jpg")?.take(5)
        ?: "bin"
    val out = File(AppFiles.baseDir, "${prefix}_${System.currentTimeMillis()}.$ext")
    val ok = runCatching {
        resolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } != null
    }.getOrDefault(false)
    if (!ok) { out.delete(); return null }
    val duration = if (mime?.startsWith("video") == true || mime?.startsWith("audio") == true) {
        runCatching {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(out.absolutePath)
            val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            mmr.release()
            ms?.let { (it / 1000).toInt() }
        }.getOrNull()
    } else null
    return PickedFile(out.absolutePath, displayName, mime, if (size > 0) size else out.length(), duration)
}

@Composable
actual fun rememberMediaPicker(kind: PickKind, onPicked: (PickedFile?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        onPicked(uri?.let { copyIntoAppStorage(context, it, kind.name.lowercase()) })
    }
    val mimeTypes = when (kind) {
        PickKind.IMAGE -> arrayOf("image/*")
        PickKind.VIDEO -> arrayOf("video/*")
        PickKind.AUDIO -> arrayOf("audio/*")
        PickKind.DOCUMENT -> arrayOf(
            "application/pdf", "text/*", "application/zip",
            "application/msword", "application/vnd.openxmlformats-officedocument.*",
            "application/vnd.ms-excel", "application/octet-stream"
        )
    }
    return { launcher.launch(mimeTypes) }
}

@Composable
actual fun rememberCameraCapture(onCaptured: (PickedFile?) -> Unit): () -> Unit {
    val context = LocalContext.current
    var pendingFile: File? = null
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = pendingFile
        if (success && file != null && file.exists()) {
            onCaptured(PickedFile(file.absolutePath, file.name, "image/jpeg", file.length()))
        } else {
            file?.delete()
            onCaptured(null)
        }
        pendingFile = null
    }
    return {
        val file = File(AppFiles.baseDir, "camera_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        pendingFile = file
        launcher.launch(uri)
    }
}
