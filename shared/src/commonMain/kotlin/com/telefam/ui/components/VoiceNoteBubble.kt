package com.telefam.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.sin

/** Deterministic pseudo-waveform bar heights (0..1) seeded from the message id, so a given voice note always renders the same shape. */
private fun waveformFor(seed: String, bars: Int = 34): List<Float> {
    var h = seed.hashCode()
    return List(bars) { i ->
        h = h * 31 + i
        0.25f + 0.75f * abs(sin((h % 997) / 997f * 6.28f + i * 0.5f))
    }
}

@Composable
fun VoiceNoteBubble(
    messageId: String,
    durationSeconds: Int,
    playing: Boolean,
    progress: Float, // 0..1
    timestamp: String,
    outgoing: Boolean,
    bubbleColor: Color,
    deliveryState: String,
    avatarUrl: String? = null,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fg = if (outgoing) Color.White else MaterialTheme.colorScheme.onSurface
    val bars = waveformFor(messageId)

    Row(modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
        if (!outgoing) { AvatarThumb(avatarUrl); Spacer(Modifier.width(8.dp)) }

        Column(
            Modifier.width(260.dp).clip(RoundedCornerShape(16.dp))
                .background(if (outgoing) bubbleColor else MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape)
                        .background(if (outgoing) Color.White else bubbleColor)
                        .clickable(onClick = onPlayPause),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null,
                        tint = if (outgoing) bubbleColor else Color.White, modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Canvas(Modifier.weight(1f).height(32.dp)) {
                    val barWidth = size.width / (bars.size * 1.6f)
                    val gap = barWidth * 0.6f
                    bars.forEachIndexed { i, h ->
                        val x = i * (barWidth + gap)
                        val barH = size.height * h
                        val played = (i + 1f) / bars.size <= progress
                        drawRoundRect(
                            color = fg.copy(alpha = if (played) 1f else 0.55f),
                            topLeft = Offset(x, (size.height - barH) / 2f),
                            size = Size(barWidth, barH),
                            cornerRadius = CornerRadius(barWidth / 2f)
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${durationSeconds / 60}:${(durationSeconds % 60).toString().padStart(2, '0')}", fontSize = 11.sp, color = fg.copy(alpha = 0.85f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(timestamp, fontSize = 11.sp, color = fg.copy(alpha = 0.75f))
                    if (outgoing) { Spacer(Modifier.width(4.dp)); DeliveryTicks(deliveryState, Color.White.copy(alpha = 0.85f)) }
                }
            }
        }
    }
}
