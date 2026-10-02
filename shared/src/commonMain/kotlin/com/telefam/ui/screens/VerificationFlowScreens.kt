package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.ui.theme.TelefamColors
import com.telefam.verification.FaceTrackingCamera
import com.telefam.verification.HeadPoseClassifier
import com.telefam.verification.LivenessChallengeDto
import com.telefam.verification.DocumentCamera
import kotlinx.coroutines.delay

private fun movementLabel(m: String) = when (m) {
    "UP" -> "Move your head up"
    "LEFT" -> "Turn your head left"
    "RIGHT" -> "Turn your head right"
    else -> m
}

/**
 * Step 2 — live selfie liveness. The movement sequence comes from the server
 * (randomised per attempt, HMAC-signed). The camera feed is a real live stream;
 * each movement is confirmed by the on-device face tracker and must be clearly
 * performed (and returned to neutral) before the next prompt. Nothing is simulated:
 * if the face tracker reports nothing, nothing advances.
 */
@Composable
fun LivenessScreen(
    challenge: LivenessChallengeDto,
    onComplete: (movementTimestampsMs: List<Long>, selfieBytes: ByteArray) -> Unit,
    onError: (String) -> Unit,
    onCancel: () -> Unit
) {
    var step by remember { mutableStateOf(0) }
    var awaitingNeutral by remember { mutableStateOf(false) }
    var timestamps by remember { mutableStateOf(listOf<Long>()) }
    var selfieBytes by remember { mutableStateOf<ByteArray?>(null) }
    var finished by remember { mutableStateOf(false) }
    val startMs = remember { kotlinx.datetime.Clock.System.now().toEpochMilliseconds() }

    // Challenge expiry — server enforces it too; mirror it client-side.
    LaunchedEffect(challenge.challengeId) {
        delay(challenge.expiresInSeconds * 1000)
        if (!finished) onError("The liveness session expired. Please try again.")
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        FaceTrackingCamera(
            onFaceAngles = { yaw, pitch ->
                if (finished) return@FaceTrackingCamera
                val expected = challenge.movements.getOrNull(step) ?: return@FaceTrackingCamera
                if (awaitingNeutral) {
                    if (HeadPoseClassifier.isNeutral(yaw, pitch)) awaitingNeutral = false
                    return@FaceTrackingCamera
                }
                if (HeadPoseClassifier.classify(yaw, pitch) == expected) {
                    val t = kotlinx.datetime.Clock.System.now().toEpochMilliseconds() - startMs
                    timestamps = timestamps + t
                    step += 1
                    awaitingNeutral = true
                }
            },
            onCaptureStill = { if (selfieBytes == null) selfieBytes = it },
            onError = onError,
            modifier = Modifier.fillMaxSize()
        )

        // Face oval guide
        Box(
            Modifier.align(Alignment.Center).size(260.dp, 330.dp)
                .clip(RoundedCornerShape(50))
        ) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                drawOval(
                    color = Color.White.copy(alpha = 0.85f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f)
                )
            }
        }

        // Prompt
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 72.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(color = Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(50)) {
                Text(
                    if (step < challenge.movements.size) movementLabel(challenge.movements[step])
                    else "Hold still…",
                    color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Step ${step.coerceAtMost(challenge.movements.size)} of ${challenge.movements.size}",
                color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp
            )
            if (awaitingNeutral && step < challenge.movements.size) {
                Text("…now face forward", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
            }
        }

        TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
            Text("Cancel", color = Color.White)
        }
    }

    // All movements done -> take the real still frame from the live stream and submit.
    LaunchedEffect(step, selfieBytes) {
        if (step >= challenge.movements.size && !finished) {
            delay(600) // let the user settle facing forward
            if (selfieBytes != null) {
                finished = true
                onComplete(timestamps, selfieBytes!!)
            }
        }
    }
}

/**
 * Step 3 — ID capture with live edge detection (front then back). The platform
 * document scanner auto-captures only when all four edges are verified in frame.
 */
@Composable
fun IdCaptureScreen(
    onComplete: (frontBytes: ByteArray, backBytes: ByteArray) -> Unit,
    onError: (String) -> Unit,
    onCancel: () -> Unit
) {
    var front by remember { mutableStateOf<ByteArray?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(front) {
        // Once the front is saved we show "Saving…" briefly, then the back capture.
        if (front != null && !saving) { saving = true; delay(400); saving = false }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (front == null || !saving) {
            DocumentCamera(
                onDocumentCaptured = { bytes ->
                    if (front == null) front = bytes else onComplete(front!!, bytes)
                },
                onError = onError,
                modifier = Modifier.fillMaxSize()
            )
        }
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 72.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(color = Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(50)) {
                Text(
                    when {
                        front == null -> "Scan the FRONT of your ID"
                        saving -> "Saving…"
                        else -> "Scan the BACK of your ID"
                    },
                    color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Fit all four corners inside the frame — capture is automatic",
                color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp
            )
        }
        TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
            Text("Cancel", color = Color.White)
        }
    }
}

/** Step 4 — submission progress: Submitting → Submitted → Pending review. */
@Composable
fun VerificationProgressScreen(state: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = TelefamColors.PrimaryRed)
        Spacer(Modifier.height(24.dp))
        Text(
            when (state) {
                "SUBMITTED" -> "Submitted"
                else -> "Submitting…"
            },
            fontWeight = FontWeight.Bold, fontSize = 22.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            when (state) {
                "SUBMITTED" -> "Your documents are in. Review is starting."
                else -> "Securely uploading your verification…"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp
        )
    }
}

/** Pending review — shown until the decision arrives. Users never see how review happens. */
@Composable
fun PendingReviewScreen(onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(Modifier.size(88.dp).clip(CircleShape).background(TelefamColors.PrimaryRed.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = TelefamColors.PrimaryRed, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("Pending review", fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your verification is being reviewed. We'll notify you as soon as it's complete — this usually takes a few minutes.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.height(48.dp)) { Text("Back to profile") }
    }
}

/** Failure — shows the user-safe reason, retry when allowed, and the refund policy. */
@Composable
fun VerificationFailedScreen(
    reason: String?,
    canRetry: Boolean,
    refundEligible: Boolean,
    refundPolicyDays: Long,
    refunded: Boolean,
    onRetry: () -> Unit,
    onRefund: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(Modifier.size(88.dp).clip(CircleShape).background(TelefamColors.PrimaryRed.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Close, contentDescription = null, tint = TelefamColors.PrimaryRed, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("Verification failed", fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            reason ?: "We couldn't complete your verification.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp
        )
        Spacer(Modifier.height(24.dp))
        if (canRetry) {
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("Try again", color = Color.White, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(12.dp))
        }
        if (refundEligible && !refunded) {
            Text(
                "Your verification has been unresolved for $refundPolicyDays days. Under our refund policy you're eligible for a full refund.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRefund, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Request refund", color = TelefamColors.PrimaryRed)
            }
        } else if (!refundEligible && !canRetry) {
            Text(
                "If your verification stays unresolved for $refundPolicyDays days, our refund policy applies automatically.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp
            )
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onBack) { Text("Back to profile") }
    }
}
