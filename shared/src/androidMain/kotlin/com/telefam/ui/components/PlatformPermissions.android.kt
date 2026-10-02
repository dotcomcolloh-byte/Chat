package com.telefam.ui.components

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

private fun manifestFor(p: DevicePermission): String = when (p) {
    DevicePermission.CAMERA -> android.Manifest.permission.CAMERA
    DevicePermission.MICROPHONE -> android.Manifest.permission.RECORD_AUDIO
    DevicePermission.PHOTOS ->
        if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES
        else android.Manifest.permission.READ_EXTERNAL_STORAGE
    DevicePermission.LOCATION -> android.Manifest.permission.ACCESS_FINE_LOCATION
}

@Composable
actual fun rememberPermissionStatus(permission: DevicePermission): String {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var status by remember(permission) {
        mutableStateOf(
            if (ContextCompat.checkSelfPermission(context, manifestFor(permission)) == PackageManager.PERMISSION_GRANTED)
                "GRANTED" else "DENIED"
        )
    }
    // Re-check when the app resumes — the user may have just flipped it in system settings.
    DisposableEffect(lifecycleOwner, permission) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                status = if (ContextCompat.checkSelfPermission(context, manifestFor(permission)) == PackageManager.PERMISSION_GRANTED)
                    "GRANTED" else "DENIED"
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return status
}

@Composable
actual fun rememberOpenAppSettings(): () -> Unit {
    val context = LocalContext.current
    return {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
