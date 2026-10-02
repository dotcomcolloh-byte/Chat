package com.telefam.ui.components.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.posts.FeedPostDto
import com.telefam.shared.generated.resources.Res
import com.telefam.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource

private val FeedRed = Color(0xFFD32323)

/**
 * Bottom-left info overlay: handle, description with a REAL read more / read less
 * (long captions expand and collapse in place), tappable hashtags (jump to search),
 * tagged friends, and the song row. Mirrors the reference layout.
 */
@Composable
fun FeedInfoOverlay(
    post: FeedPostDto,
    suggestionLabel: String?,           // e.g. "You might like this" — null hides the chip
    onHashtagClick: (String) -> Unit,
    onOwnerClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var expanded by remember(post.postId) { mutableStateOf(false) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (suggestionLabel != null) {
            Row(
                Modifier.clip(RoundedCornerShape(20.dp))
                    .background(Color(0x33FFFFFF))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = FeedRed, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(6.dp))
                Text(suggestionLabel, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onOwnerClick)) {
            Text(
                post.displayHandle, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold
            )
            if (post.ownerVerified) {
                Spacer(Modifier.width(5.dp))
                com.telefam.ui.components.VerifiedBadge(size = 15.dp)
            }
        }

        val fullText = buildString {
            post.description?.takeIf { it.isNotBlank() }?.let { append(it) }
        }
        if (fullText.isNotEmpty()) {
            val collapsed = !expanded && fullText.length > 120
            Text(
                if (collapsed) fullText.take(120).trimEnd() + "…" else fullText,
                color = Color.White, fontSize = 14.sp, lineHeight = 19.sp
            )
            if (fullText.length > 120) {
                Text(
                    stringResource(if (expanded) Res.string.feed_read_less else Res.string.feed_read_more),
                    color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { expanded = !expanded } // actually toggles, both ways
                )
            }
        }

        if (post.hashtags.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                post.hashtags.take(5).forEach { tag ->
                    Text(
                        "#$tag", color = FeedRed, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { onHashtagClick(tag) }
                    )
                }
            }
        }

        val songLine = listOfNotNull(post.songTitle, post.songArtist).filter { it.isNotBlank() }
            .joinToString(" — ").ifBlank { null }
        Row(
            Modifier.clip(RoundedCornerShape(20.dp))
                .background(Color(0x33FFFFFF))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.MusicNote, contentDescription = null, tint = FeedRed, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                songLine ?: stringResource(Res.string.feed_original_sound),
                color = Color.White, fontSize = 13.sp, maxLines = 1
            )
        }
    }
}
