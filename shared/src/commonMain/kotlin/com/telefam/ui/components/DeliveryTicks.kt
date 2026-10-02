package com.telefam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * Delivery state indicator for outgoing messages:
 *  SENDING   -> clock (optimistic send in flight / queued offline)
 *  SENT      -> single tick (server accepted the envelope)
 *  DELIVERED -> double tick (peer's device pulled it)
 *  READ      -> the peer's avatar (or accent double tick when no avatar is available)
 *  FAILED    -> error glyph (tap the message menu to retry)
 */
@Composable
fun DeliveryTicks(
    state: String,
    tint: Color,
    modifier: Modifier = Modifier,
    readAvatarUrl: String? = null,
    readTint: Color = MaterialTheme.colorScheme.primary
) {
    when (state) {
        "SENDING" -> Icon(Icons.Filled.Schedule, contentDescription = null, tint = tint, modifier = modifier.size(13.dp))
        "SENT" -> Icon(Icons.Filled.Done, contentDescription = null, tint = tint, modifier = modifier.size(14.dp))
        "DELIVERED" -> Icon(Icons.Filled.DoneAll, contentDescription = null, tint = tint, modifier = modifier.size(14.dp))
        "READ" ->
            if (readAvatarUrl != null) {
                Box(
                    modifier.size(15.dp).clip(CircleShape)
                        .border(1.dp, tint.copy(alpha = 0.6f), CircleShape)
                        .background(Color.White.copy(alpha = 0.2f))
                ) {
                    AsyncImage(model = readAvatarUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(15.dp))
                }
            } else {
                Icon(Icons.Filled.DoneAll, contentDescription = null, tint = readTint, modifier = modifier.size(14.dp))
            }
        "FAILED" -> Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = modifier.size(14.dp))
        else -> {}
    }
}
