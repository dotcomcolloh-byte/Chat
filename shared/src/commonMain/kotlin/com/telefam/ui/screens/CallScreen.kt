package com.telefam.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.telefam.calls.*
import com.telefam.ui.components.avatarFallback
import kotlinx.coroutines.delay

/**
 * In-call screen: full-bleed remote video (or the peer's avatar when their camera is off),
 * local PiP preview, and the control row — mute, camera, flip, screen share, speaker, hang up.
 * Works on phones (375dp+) and desktop windows: controls never exceed the viewport.
 */
@Composable
fun CallScreen(
    state: CallUiState,
    engine: WebRtcEngine?,
    onHangUp: () -> Unit,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit,
    onFlipCamera: () -> Unit,
    onToggleScreenShare: () -> Unit,
    onToggleSpeaker: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF101418))
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // --- Remote layer: video when live, avatar card when the peer's camera is off ---
        if (state.remoteVideoOn && engine != null) {
            RemoteVideoView(engine, Modifier.fillMaxSize())
        } else {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CallAvatar(state.peerName, state.peerAvatarUrl, size = 128)
                Spacer(Modifier.height(20.dp))
                Text(state.peerName, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    callStatusLine(state),
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Status overlay (name + timer) when video is showing
        if (state.remoteVideoOn) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))
                    )
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Text(state.peerName, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(callStatusLine(state), color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
            }
        }

        // --- Local PiP: camera preview, or screen-share badge, or own-avatar chip ---
        if (engine != null && (state.phase == CallPhase.ACTIVE || state.phase == CallPhase.CONNECTING)) {
            Box(
                Modifier
                    .align(if (state.remoteVideoOn) Alignment.TopEnd else Alignment.BottomEnd)
                    .padding(end = 16.dp, top = if (state.remoteVideoOn) 72.dp else 0.dp, bottom = if (state.remoteVideoOn) 0.dp else 132.dp)
                    .size(width = 104.dp, height = 152.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF22282E))
            ) {
                when {
                    state.screenSharing -> {
                        Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.ScreenShare, null, tint = Color.White)
                            Text("Sharing", color = Color.White, fontSize = 11.sp)
                        }
                    }
                    state.cameraOn -> LocalVideoView(engine, Modifier.fillMaxSize())
                    else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CallAvatar(state.peerName, null, size = 44)
                    }
                }
            }
        }

        // --- Controls ---
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                .padding(bottom = 28.dp, top = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CallControlButton(
                    icon = if (state.micMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    label = if (state.micMuted) "Unmute" else "Mute",
                    active = state.micMuted,
                    onClick = onToggleMic
                )
                if (state.callType == CallType.VIDEO) {
                    CallControlButton(
                        icon = if (state.cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                        label = if (state.cameraOn) "Camera" else "Camera off",
                        active = !state.cameraOn,
                        onClick = onToggleCamera
                    )
                    CallControlButton(
                        icon = Icons.Filled.FlipCameraAndroid,
                        label = "Flip",
                        active = false,
                        enabled = state.cameraOn && !state.screenSharing,
                        onClick = onFlipCamera
                    )
                    CallControlButton(
                        icon = if (state.screenSharing) Icons.Filled.StopScreenShare else Icons.Filled.ScreenShare,
                        label = if (state.screenSharing) "Stop share" else "Share",
                        active = state.screenSharing,
                        onClick = onToggleScreenShare
                    )
                }
                CallControlButton(
                    icon = if (state.speakerOn) Icons.Filled.VolumeUp else Icons.Outlined.VolumeUp,
                    label = "Speaker",
                    active = state.speakerOn,
                    onClick = onToggleSpeaker
                )
                // Hang up — always last, always red, >= 56dp touch target
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFD32323))
                            .clickable(onClick = onHangUp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.CallEnd, contentDescription = "Hang up", tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("End", color = Color.White, fontSize = 11.sp)
                }
            }
        }
    }
}

/** Incoming-call screen: also rendered by the OS wake-up path (full-screen intent / CallKit). */
@Composable
fun IncomingCallScreen(
    state: CallUiState,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val infinite = rememberInfiniteTransition(label = "pulse")
    val pulse by infinite.animateFloat(
        initialValue = 1f, targetValue = 1.18f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulseScale"
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF101418))
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(Modifier.height(1.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center) {
                    // Pulsing ring behind the avatar while ringing
                    Box(
                        Modifier
                            .size(150.dp * pulse)
                            .clip(CircleShape)
                            .background(Color(0xFFD32323).copy(alpha = 0.18f))
                    )
                    CallAvatar(state.peerName, state.peerAvatarUrl, size = 120)
                }
                Spacer(Modifier.height(24.dp))
                Text(state.peerName, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (state.callType == CallType.VIDEO) "Incoming video call" else "Incoming voice call",
                    color = Color.White.copy(alpha = 0.75f), fontSize = 15.sp
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(bottom = 56.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(68.dp).clip(CircleShape).background(Color(0xFFD32323)).clickable(onClick = onDecline),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Filled.CallEnd, "Decline", tint = Color.White, modifier = Modifier.size(32.dp)) }
                    Spacer(Modifier.height(6.dp))
                    Text("Decline", color = Color.White, fontSize = 13.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(68.dp).clip(CircleShape).background(Color(0xFF2E7D32)).clickable(onClick = onAccept),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (state.callType == CallType.VIDEO) Icons.Filled.Videocam else Icons.Filled.Call,
                            "Accept", tint = Color.White, modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Accept", color = Color.White, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun CallAvatar(name: String, avatarUrl: String?, size: Int) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color(0xFF2A3138)),
        contentAlignment = Alignment.Center
    ) {
        if (avatarUrl != null) {
            AsyncImage(
                model = avatarUrl, contentDescription = name,
                placeholder = avatarFallback(), error = avatarFallback(),
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                name.firstOrNull()?.uppercase() ?: "?",
                color = Color.White, fontSize = (size / 2.4).sp, fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun CallControlButton(
    icon: ImageVector, label: String, active: Boolean,
    enabled: Boolean = true, onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(56.dp) // >= 44dp touch target
                .clip(CircleShape)
                .background(
                    when {
                        !enabled -> Color.White.copy(alpha = 0.08f)
                        active -> Color.White
                        else -> Color.White.copy(alpha = 0.16f)
                    }
                )
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon, contentDescription = label,
                tint = when {
                    !enabled -> Color.White.copy(alpha = 0.35f)
                    active -> Color(0xFF101418)
                    else -> Color.White
                },
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White.copy(alpha = if (enabled) 1f else 0.4f), fontSize = 11.sp)
    }
}

@Composable
private fun callStatusLine(state: CallUiState): String = when (state.phase) {
    CallPhase.OUTGOING_RINGING -> "Ringing…"
    CallPhase.CONNECTING -> "Connecting…"
    CallPhase.ACTIVE -> {
        var now by remember { mutableStateOf(kotlinx.datetime.Clock.System.now().toEpochMilliseconds()) }
        LaunchedEffect(Unit) { while (true) { delay(1_000); now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds() } }
        val secs = ((now - (state.connectedAtMillis ?: now)) / 1000).coerceAtLeast(0)
        "%02d:%02d".format(secs / 60, secs % 60)
    }
    CallPhase.ENDED -> when (state.endReason) {
        CallEndReason.REJECTED -> "Call declined"
        CallEndReason.BUSY -> "User is busy"
        CallEndReason.CANCELLED -> "Call cancelled"
        CallEndReason.TIMEOUT -> "No answer"
        CallEndReason.FAILED -> "Connection failed"
        else -> "Call ended"
    }
    else -> ""
}
