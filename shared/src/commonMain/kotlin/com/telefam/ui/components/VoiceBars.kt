package com.telefam.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource

private fun fmt(seconds: Int) = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

@Composable
fun VoiceRecordingBar(
    accent: Color,
    elapsedSeconds: Int,
    onCancel: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pulse by rememberInfiniteTransition(label = "rec").animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "recPulse"
    )
    Row(
        modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.surface).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onCancel),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.Delete, contentDescription = stringResource(Res.string.voice_cancel), tint = accent) }

        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(Color.Red).alpha(pulse))
            Spacer(Modifier.width(10.dp))
            Text(fmt(elapsedSeconds), fontSize = 17.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        }

        Box(
            Modifier.size(48.dp).clip(CircleShape).background(accent).clickable(onClick = onStop),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.Stop, contentDescription = stringResource(Res.string.voice_stop), tint = Color.White) }
    }
}

@Composable
fun VoicePreviewBar(
    accent: Color,
    durationSeconds: Int,
    playing: Boolean,
    progress: Float,
    viewOnce: Boolean,
    onPlayPause: () -> Unit,
    onDelete: () -> Unit,
    onToggleViewOnce: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.surface).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onDelete),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.Delete, contentDescription = stringResource(Res.string.voice_delete), tint = accent) }

        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(accent).clickable(onClick = onPlayPause),
            contentAlignment = Alignment.Center
        ) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White) }

        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) }, color = accent,
                trackColor = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().height(4.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(fmt(durationSeconds), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }

        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(if (viewOnce) accent.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onToggleViewOnce),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (viewOnce) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                contentDescription = stringResource(Res.string.view_once),
                tint = if (viewOnce) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(accent).clickable(onClick = onSend),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.Send, contentDescription = stringResource(Res.string.voice_send), tint = Color.White) }
    }
}
