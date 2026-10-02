package com.telefam.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * QR scanner camera for linked-devices pairing. Streams the back camera through
 * the platform's on-device barcode detector (ML Kit on Android, AVFoundation
 * metadata output on iOS) and reports each decoded payload exactly once.
 *
 * [onDetected] is invoked on a background thread; implementations must guard so
 * a payload is only delivered once until [enabled] flips false → true again.
 */
@Composable
expect fun QrScannerCamera(
    enabled: Boolean,
    onDetected: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
)
