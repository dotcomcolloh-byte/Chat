package com.telefam.verification

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Platform camera contracts for verification. All detection is REAL and on-device:
 *
 *  - [FaceTrackingCamera]: streams live camera frames through the platform face
 *    detector (ML Kit on Android, Vision on iOS) and reports head yaw/pitch in
 *    degrees. The liveness screen drives the server-issued movement sequence from
 *    these angles — there is no way to "pass" without actually moving your head.
 *  - [DocumentCamera]: real document capture with automatic edge detection
 *    (ML Kit Document Scanner on Android, VisionKit VNDocumentCamera on iOS).
 *    The shutter fires only when all four edges of the document are detected.
 */

@Composable
expect fun FaceTrackingCamera(
    onFaceAngles: (yawDeg: Float, pitchDeg: Float) -> Unit,
    onCaptureStill: (jpegBytes: ByteArray) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
)

@Composable
expect fun DocumentCamera(
    onDocumentCaptured: (jpegBytes: ByteArray) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
)

/** Common-side head-movement classification shared by both platforms. */
object HeadPoseClassifier {
    const val THRESHOLD_DEG = 18f   // must be a clear movement
    const val RETURN_DEG = 9f       // must return near-neutral between movements

    /**
     * Returns the movement whose threshold the current pose satisfies, or null.
     * yaw: +right/-left (ML Kit headEulerAngleY), pitch: +down/-up (headEulerAngleX).
     */
    fun classify(yaw: Float, pitch: Float): String? = when {
        pitch < -THRESHOLD_DEG -> "UP"
        yaw > THRESHOLD_DEG -> "RIGHT"
        yaw < -THRESHOLD_DEG -> "LEFT"
        else -> null
    }

    fun isNeutral(yaw: Float, pitch: Float): Boolean =
        kotlin.math.abs(yaw) < RETURN_DEG && kotlin.math.abs(pitch) < RETURN_DEG
}
