package com.telefam.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.ui.theme.TelefamColors

private fun fmtMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}

/**
 * Full trim editor: the timeline is a strip of frames decoded at evenly-spaced
 * points (keyframe/iframe-snapped on both platforms for speed) with a red-handled
 * range slider on top. Live duration readout updates as the handles move.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimVideoScreen(
    durationMs: Long,
    frames: List<ImageBitmap>,
    initialStartMs: Long,
    initialEndMs: Long, // 0 = full length
    onCancel: () -> Unit,
    onApply: (startMs: Long, endMs: Long) -> Unit
) {
    var range by remember {
        mutableStateOf(
            (initialStartMs.toFloat() / durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)..
                ((if (initialEndMs > 0) initialEndMs else durationMs).toFloat() / durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
        )
    }
    val startMs = (range.start * durationMs).toLong()
    val endMs = (range.endInclusive * durationMs).toLong()

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Top bar
        Box(
            Modifier.fillMaxWidth().background(TelefamColors.PrimaryRed)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(vertical = 14.dp, horizontal = 8.dp)
        ) {
            IconButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = TelefamColors.White)
            }
            Text(
                "Trim video", color = TelefamColors.White, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            // Frame strip (iframes)
            Row(
                Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (frames.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Loading frames…", color = TelefamColors.TextMuted, fontSize = 13.sp)
                    }
                } else {
                    frames.forEach { f ->
                        Image(
                            bitmap = f, contentDescription = null,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))

            // Red-handled range slider over the strip
            RangeSlider(
                value = range,
                onValueChange = { range = it },
                colors = SliderDefaults.colors(
                    thumbColor = TelefamColors.PrimaryRed,
                    activeTrackColor = TelefamColors.PrimaryRed,
                    inactiveTrackColor = TelefamColors.PrimaryRed.copy(alpha = 0.25f)
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmtMs(startMs), color = TelefamColors.TextMuted, fontSize = 13.sp)
                Text(fmtMs(endMs), color = TelefamColors.TextMuted, fontSize = 13.sp)
            }

            Spacer(Modifier.height(24.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Selected duration", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text(fmtMs(endMs - startMs), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                }
            }

            Spacer(Modifier.height(28.dp))
            Button(
                onClick = { onApply(startMs, endMs) },
                colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("Apply trim", fontWeight = FontWeight.Bold, fontSize = 17.sp) }
        }
    }
}
