package com.telefam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

@Composable
fun VideoMessageBubble(
    thumbnailPath: String?,
    durationSeconds: Int,
    timestamp: String,
    outgoing: Boolean,
    bubbleColor: Color,
    deliveryState: String,
    avatarUrl: String? = null,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
        if (!outgoing) { AvatarThumb(avatarUrl); Spacer(Modifier.width(8.dp)) }

        val shape = RoundedCornerShape(16.dp)
        Box(
            Modifier.width(280.dp).height(180.dp).clip(shape)
                .then(if (outgoing) Modifier.border(3.dp, bubbleColor, shape) else Modifier)
                .background(Color.Black)
                .clickable(onClick = onPlay)
        ) {
            if (thumbnailPath != null) {
                AsyncImage(model = thumbnailPath, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Box(
                Modifier.align(Alignment.Center).size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
            Text(
                "${durationSeconds / 60}:${(durationSeconds % 60).toString().padStart(2, '0')}",
                color = Color.White, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
            )
            Row(Modifier.align(Alignment.BottomEnd).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(timestamp, color = Color.White, fontSize = 11.sp)
                if (outgoing) { Spacer(Modifier.width(4.dp)); DeliveryTicks(deliveryState, Color.White) }
            }
        }
    }
}
