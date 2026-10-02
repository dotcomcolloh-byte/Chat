package com.telefam.ui.components

import androidx.compose.runtime.Composable

enum class DevicePermission { CAMERA, MICROPHONE, PHOTOS, LOCATION }

/**
 * The real OS-level permission status ("GRANTED" | "DENIED" | "NOT_DETERMINED"),
 * recomputed whenever the app returns to the foreground (so returning from the
 * system settings screen shows the fresh value immediately).
 */
@Composable
expect fun rememberPermissionStatus(permission: DevicePermission): String

/** Returns a function that opens this app's page in the OS settings. */
@Composable
expect fun rememberOpenAppSettings(): () -> Unit
