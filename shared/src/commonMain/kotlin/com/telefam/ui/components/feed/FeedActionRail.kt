package com.telefam.ui.components.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.telefam.posts.FeedPostDto
import com.telefam.shared.generated.resources.Res
import com.telefam.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource

private val FeedRed = Color(0xFFD32323)

private fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 10_000 -> "%.1fK".format(n / 1_000.0)
    n >= 1_000 -> "%,d".format(n)
    else -> n.toString()
}

/**
 * Right-hand action rail from the reference: avatar with red follow +, like, comment
 * (Coming soon), reshare, save, and the 3-dot overflow. Every action is wired to a
 * real handler — nothing here is decorative.
 */
@Composable
fun FeedActionRail(
    post: FeedPostDto,
    avatarAbsoluteUrl: String?,
    onLike: () -> Unit,
    onComment: () -> Unit,
    onReshare: () -> Unit,
    onSave: () -> Unit,
    onFollow: () -> Unit,
    onMore: () -> Unit,
    onAvatarClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // The "+" badge is ONLY for creators the viewer has no relationship with yet:
    // hidden for your own posts, anyone you already follow, and (via the server
    // treating mutual follows / accepted chats as follows) your friends.
    val showFollowBadge = !post.isOwner && !post.viewerFollowing

    Column(
        modifier.width(64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // Avatar + follow badge
        Box(contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier.size(48.dp).clip(CircleShape)
                    .background(FeedRed.copy(alpha = 0.25f))
                    .border(1.dp, Color.White, CircleShape)
                    .clickable(onClick = onAvatarClick),
                contentAlignment = Alignment.Center
            ) {
                if (avatarAbsoluteUrl != null) {
                    AsyncImage(model = avatarAbsoluteUrl, contentDescription = null,
                        modifier = Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                } else {
                    Text(post.displayName.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            if (showFollowBadge) {
                Box(
                    Modifier.size(20.dp).offset(y = 8.dp).clip(CircleShape).background(FeedRed)
                        .clickable(onClick = onFollow),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.feed_follow), tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))

        RailAction(
            active = post.viewerLiked, activeTint = FeedRed, inactiveTint = Color.White,
            count = formatCount(post.likeCount),
            onClick = onLike
        ) { tint -> Icon(Icons.Filled.Favorite, contentDescription = stringResource(Res.string.feed_like), tint = tint, modifier = Modifier.size(32.dp)) }

        RailAction(count = formatCount(post.commentCount), onClick = onComment) {
            Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = stringResource(Res.string.feed_comments), tint = Color.White, modifier = Modifier.size(30.dp))
        }

        RailAction(count = formatCount(post.reshareCount), onClick = onReshare) {
            Icon(Icons.Outlined.Send, contentDescription = stringResource(Res.string.feed_reshare), tint = Color.White, modifier = Modifier.size(30.dp))
        }

        RailAction(
            active = post.viewerSaved, activeTint = FeedRed, inactiveTint = Color.White,
            count = formatCount(post.viewCount),
            onClick = onSave
        ) { tint ->
            Icon(
                if (post.viewerSaved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                contentDescription = stringResource(Res.string.feed_save), tint = tint, modifier = Modifier.size(30.dp)
            )
        }

        Icon(
            Icons.Outlined.MoreVert, contentDescription = stringResource(Res.string.more_options),
            tint = Color.White, modifier = Modifier.size(28.dp).clickable(onClick = onMore)
        )
    }
}

@Composable
private fun RailAction(
    count: String,
    onClick: () -> Unit,
    active: Boolean = false,
    activeTint: Color = Color.White,
    inactiveTint: Color = Color.White,
    icon: @Composable (tint: Color) -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            icon(if (active) activeTint else inactiveTint)
        }
        Text(count, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
