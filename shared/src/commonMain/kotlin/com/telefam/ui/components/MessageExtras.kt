package com.telefam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.chat.ChatFormat
import com.telefam.chat.EventData
import com.telefam.chat.FileMeta
import com.telefam.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** Quoted-message block rendered at the top of a bubble that is a reply. */
@Composable
fun QuoteBlock(senderLabel: String, preview: String, outgoing: Boolean, accent: Color, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (outgoing) Color.White.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Box(Modifier.width(3.dp).height(34.dp).clip(RoundedCornerShape(2.dp)).background(if (outgoing) Color.White else accent))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                senderLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = if (outgoing) Color.White else accent, maxLines = 1
            )
            Text(
                preview, fontSize = 12.sp, maxLines = 2,
                color = if (outgoing) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}

/** Reaction chips under a bubble: emoji + count, mine highlighted; tap toggles. */
@Composable
fun ReactionChips(
    summary: List<Pair<String, Int>>,
    myEmojis: Set<String>,
    outgoing: Boolean,
    accent: Color,
    onToggle: (String) -> Unit
) {
    if (summary.isEmpty()) return
    Row(
        modifier = Modifier.padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        summary.take(6).forEach { (emoji, count) ->
            val mine = emoji in myEmojis
            Row(
                Modifier.clip(RoundedCornerShape(12.dp))
                    .background(if (mine) accent.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onToggle(emoji) }
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(emoji, fontSize = 13.sp)
                if (count > 1) {
                    Spacer(Modifier.width(3.dp))
                    Text(count.toString(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }
    }
}

/** Strip shown above the input while composing a reply or editing. */
@Composable
fun ComposeContextBar(title: String, preview: String, accent: Color, onCancel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 14.dp, end = 4.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(3.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(accent))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = accent, maxLines = 1)
            Text(preview, fontSize = 13.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        }
        IconButton(onClick = onCancel) {
            Icon(Icons.Filled.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

/** Banner pinned to the top of the chat showing the pinned message; tap jumps to it. */
@Composable
fun PinnedBanner(preview: String, senderLabel: String, accent: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.PushPin, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.chat_pinned), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = accent)
            Text("$senderLabel: $preview", fontSize = 13.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        }
    }
}

/** Tombstone shown in place of a deleted-for-everyone message. */
@Composable
fun DeletedTombstone(time: String, outgoing: Boolean, avatarUrl: String?) {
    AlignedRowPublic(outgoing, avatarUrl) {
        Row(
            Modifier.clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(Res.string.chat_message_deleted),
                fontStyle = FontStyle.Italic, fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            Spacer(Modifier.width(8.dp))
            Text(time, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
        }
    }
}

/** Document/file bubble with icon, name, size. */
@Composable
fun FileBubble(meta: FileMeta?, time: String, outgoing: Boolean, accent: Color, deliveryState: String, avatarUrl: String?, onOpen: () -> Unit) {
    AlignedRowPublic(outgoing, avatarUrl) {
        Row(
            Modifier.widthIn(max = 260.dp).clip(RoundedCornerShape(16.dp))
                .background(if (outgoing) accent else MaterialTheme.colorScheme.surface)
                .clickable(onClick = onOpen).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (outgoing) Color.White.copy(alpha = 0.2f) else accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Description, contentDescription = null, tint = if (outgoing) Color.White else accent)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    meta?.name ?: "File", fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2,
                    color = if (outgoing) Color.White else MaterialTheme.colorScheme.onSurface
                )
                if (meta != null && meta.sizeBytes > 0) {
                    Text(
                        formatBytes(meta.sizeBytes), fontSize = 11.sp,
                        color = if (outgoing) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        time, fontSize = 11.sp,
                        color = if (outgoing) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    if (outgoing) { Spacer(Modifier.width(4.dp)); DeliveryTicks(deliveryState, Color.White.copy(alpha = 0.85f)) }
                }
            }
        }
    }
}

/** Event card bubble. */
@Composable
fun EventBubble(event: EventData?, time: String, outgoing: Boolean, accent: Color, deliveryState: String, avatarUrl: String?) {
    if (event == null) return
    AlignedRowPublic(outgoing, avatarUrl) {
        Column(
            Modifier.widthIn(max = 260.dp).clip(RoundedCornerShape(16.dp))
                .background(if (outgoing) accent else MaterialTheme.colorScheme.surface)
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (outgoing) Color.White.copy(alpha = 0.2f) else accent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Event, contentDescription = null, tint = if (outgoing) Color.White else accent)
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        event.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                        color = if (outgoing) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "${ChatFormat.numericDate(event.startsAtEpochMillis)} · ${ChatFormat.time(event.startsAtEpochMillis)}",
                        fontSize = 12.sp,
                        color = if (outgoing) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            event.locationLabel?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 13.sp, color = if (outgoing) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
            }
            event.notes?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, fontSize = 13.sp, color = if (outgoing) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
            }
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    time, fontSize = 11.sp,
                    color = if (outgoing) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                if (outgoing) { Spacer(Modifier.width(4.dp)); DeliveryTicks(deliveryState, Color.White.copy(alpha = 0.85f)) }
            }
        }
    }
}

/** Placeholder for blob media that hasn't been fetched yet — tap retries the download. */
@Composable
fun MediaRetryBubble(label: String, time: String, outgoing: Boolean, accent: Color, avatarUrl: String?, onRetry: () -> Unit) {
    AlignedRowPublic(outgoing, avatarUrl) {
        Row(
            Modifier.clip(RoundedCornerShape(16.dp))
                .background(if (outgoing) accent else MaterialTheme.colorScheme.surface)
                .clickable(onClick = onRetry).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Download, contentDescription = null, tint = if (outgoing) Color.White else accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(Res.string.media_download_retry) + " · " + label, fontSize = 13.sp,
                color = if (outgoing) Color.White else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** Public version of the row alignment helper so new bubbles can share it. */
@Composable
fun AlignedRowPublic(outgoing: Boolean, avatarUrl: String?, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
        if (!outgoing) { AvatarThumb(avatarUrl); Spacer(Modifier.width(8.dp)) }
        content()
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
