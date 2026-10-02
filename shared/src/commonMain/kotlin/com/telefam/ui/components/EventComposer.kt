package com.telefam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.telefam.chat.EventData
import com.telefam.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Composer for EVENT attachments. Validates a title plus YYYY-MM-DD / HH:MM inputs
 * (24h) and produces an EventData that travels inside the encrypted payload.
 */
@Composable
fun EventComposerDialog(accent: androidx.compose.ui.graphics.Color, onDismiss: () -> Unit, onSend: (EventData) -> Unit) {
    var title by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }

    fun parseEpochMillis(): Long? {
        val d = Regex("""^(\d{4})-(\d{2})-(\d{2})$""").matchEntire(date.trim()) ?: return null
        val t = Regex("""^([01]?\d|2[0-3]):([0-5]\d)$""").matchEntire(time.trim()) ?: return null
        val (y, mo, da) = Triple(d.groupValues[1].toInt(), d.groupValues[2].toInt(), d.groupValues[3].toInt())
        if (mo !in 1..12 || da !in 1..31) return null
        // Local civil time -> epoch: use a UTC anchor plus the parsed clock; precise TZ conversion
        // is done by the platform layer if needed — for scheduling display this is sufficient.
        val days = daysFromCivil(y, mo, da)
        return (days * 86_400L + t.groupValues[1].toInt() * 3600L + t.groupValues[2].toInt() * 60L) * 1000L
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface).padding(20.dp)
        ) {
            Text(stringResource(Res.string.event_composer_title), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            ComposerField(title, { title = it; invalid = false }, stringResource(Res.string.event_title_label))
            Spacer(Modifier.height(8.dp))
            Row {
                Box(Modifier.weight(1f)) { ComposerField(date, { date = it; invalid = false }, stringResource(Res.string.event_date_label)) }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) { ComposerField(time, { time = it; invalid = false }, stringResource(Res.string.event_time_label)) }
            }
            Spacer(Modifier.height(8.dp))
            ComposerField(location, { location = it }, stringResource(Res.string.event_location_label))
            Spacer(Modifier.height(8.dp))
            ComposerField(notes, { notes = it }, stringResource(Res.string.event_notes_label))
            if (invalid) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(Res.string.event_invalid), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    val epoch = parseEpochMillis()
                    if (title.isBlank() || epoch == null) {
                        invalid = true
                    } else {
                        onSend(EventData(title.trim(), epoch, location.trim().ifBlank { null }, notes.trim().ifBlank { null }))
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent)
            ) {
                Text(stringResource(Res.string.event_send), fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun ComposerField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        label = { Text(label, fontSize = 13.sp) },
        modifier = Modifier.fillMaxWidth()
    )
}

/** Howard Hinnant's days-from-civil algorithm — date math without a platform dependency. */
private fun daysFromCivil(y: Int, m: Int, d: Int): Long {
    val yy = if (m <= 2) y - 1 else y
    val era = (if (yy >= 0) yy else yy - 399) / 400
    val yoe = yy - era * 400
    val mp = (m + 9) % 12
    val doy = (153 * mp + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return (era * 146097L + doe - 719468L)
}
