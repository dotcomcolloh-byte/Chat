package com.telefam.ui.components.comments

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.telefam.posts.CommentDto
import com.telefam.ui.components.avatarFallback
import com.telefam.ui.theme.TelefamColors

/**
 * One comment row (top-level or reply), matching the reference layout: avatar with a
 * red "+" follow badge, username + relative time, autolinked body with Read more/less,
 * sticker-sized media, heart like + count, Reply, and a 3-dots affordance for report.
 * Long-press (anywhere on the row or on the media) opens the full action menu:
 * Copy, Edit/Delete (author), Delete (post owner moderating), Pin/Unpin (post owner),
 * Report (everyone else).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CommentItem(
    comment: CommentDto,
    isReply: Boolean = false,
    showFollowBadge: Boolean = false,
    onLike: () -> Unit,
    onReply: () -> Unit,
    onFollow: () -> Unit,
    onAvatarClick: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPin: () -> Unit,
    onReport: () -> Unit,
    onMediaClick: () -> Unit,
    onMediaLongPress: () -> Unit,
    onMentionClick: (String) -> Unit,
    onHashtagClick: (String) -> Unit,
    onUrlClick: (String) -> Unit,
    mediaAbsoluteUrl: (String) -> String,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val contentColor = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = {}, onLongClick = { menuOpen = true })
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Avatar + follow badge
            Box(contentAlignment = Alignment.BottomCenter) {
                Box(
                    Modifier.size(if (isReply) 34.dp else 42.dp).clip(CircleShape)
                        .background(TelefamColors.PrimaryRed.copy(alpha = 0.25f))
                        .clickable(onClick = onAvatarClick),
                    contentAlignment = Alignment.Center
                ) {
                    val avatar = comment.authorAvatarUrl?.let(mediaAbsoluteUrl)
                    if (avatar != null) {
                        AsyncImage(
                            model = avatar, contentDescription = null,
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                            contentScale = ContentScale.Crop,
                            placeholder = avatarFallback(), error = avatarFallback()
                        )
                    } else {
                        Text(
                            comment.displayName.take(1).uppercase(),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold, fontSize = 15.sp
                        )
                    }
                }
                if (showFollowBadge && !comment.deleted) {
                    Box(
                        Modifier.size(18.dp).offset(y = 6.dp).clip(CircleShape)
                            .background(TelefamColors.PrimaryRed)
                            .clickable(onClick = onFollow),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Follow", tint = Color.White, modifier = Modifier.size(12.dp))
                    }
                }
            }

            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        comment.authorUsername ?: comment.displayName,
                        color = contentColor, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        maxLines = 1, modifier = Modifier.clickable(onClick = onAvatarClick)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(relativeTime(comment.createdAt), color = muted, fontSize = 12.sp)
                    if (comment.pinnedByOwner) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Outlined.PushPin, contentDescription = "Pinned", tint = TelefamColors.PrimaryRed, modifier = Modifier.size(13.dp))
                        Text("Pinned", color = TelefamColors.PrimaryRed, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(Modifier.height(2.dp))

                if (comment.deleted) {
                    Text(
                        "This comment was deleted",
                        color = muted, fontSize = 14.sp, fontStyle = FontStyle.Italic
                    )
                } else {
                    comment.body?.let { body ->
                        ExpandableAutoLinkText(
                            text = body,
                            style = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
                            color = contentColor,
                            expanded = expanded,
                            onToggle = { expanded = !expanded },
                            onUrlClick = onUrlClick,
                            onMentionClick = onMentionClick,
                            onHashtagClick = onHashtagClick
                        )
                        if (comment.edited) {
                            Text("edited", color = muted, fontSize = 11.sp)
                        }
                    }

                    // Media: sticker-sized inline thumbnail; tap opens the full viewer.
                    val mediaModel = when (comment.kind) {
                        "PHOTO" -> comment.mediaUrl?.let(mediaAbsoluteUrl)
                        "STICKER", "GIF" -> comment.stickerUrl
                        else -> null
                    }
                    if (mediaModel != null) {
                        Spacer(Modifier.height(6.dp))
                        AsyncImage(
                            model = mediaModel,
                            contentDescription = "Comment media",
                            modifier = Modifier
                                .size(width = 120.dp, height = 120.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .combinedClickable(onClick = onMediaClick, onLongClick = onMediaLongPress),
                            contentScale = if (comment.kind == "PHOTO") ContentScale.Crop else ContentScale.Fit
                        )
                    }

                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Reply",
                            color = muted, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(onClick = onReply)
                                .padding(horizontal = 4.dp, vertical = 4.dp)
                        )
                        if (comment.starTotal > 0) {
                            Spacer(Modifier.width(12.dp))
                            Icon(Icons.Filled.Star, contentDescription = null, tint = Color(0xFFFFC107), modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(2.dp))
                            Text(
                                formatCommentCount(comment.starTotal),
                                color = Color(0xFFFFC107), fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Like column + 3-dots report affordance (reference layout)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (!comment.deleted) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onLike)
                            .padding(6.dp)
                    ) {
                        Icon(
                            if (comment.viewerLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = "Like",
                            tint = if (comment.viewerLiked) TelefamColors.PrimaryRed else muted,
                            modifier = Modifier.size(18.dp)
                        )
                        if (comment.likeCount > 0) {
                            Text(formatCommentCount(comment.likeCount), color = muted, fontSize = 11.sp)
                        }
                    }
                }
                Icon(
                    Icons.Filled.MoreVert, contentDescription = "Report comment",
                    tint = muted.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onReport)
                        .padding(1.dp)
                )
            }
        }

        // Long-press action menu
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (!comment.deleted && comment.body != null) {
                DropdownMenuItem(text = { Text("Copy") }, onClick = { menuOpen = false; onCopy() })
            }
            if (!comment.deleted && comment.viewerIsAuthor && comment.kind == "TEXT") {
                DropdownMenuItem(text = { Text("Edit") }, onClick = { menuOpen = false; onEdit() })
            }
            if (!comment.deleted && (comment.viewerIsAuthor || comment.viewerIsPostOwner)) {
                DropdownMenuItem(
                    text = { Text("Delete", color = TelefamColors.PrimaryRed) },
                    onClick = { menuOpen = false; onDelete() }
                )
            }
            if (!comment.deleted && comment.viewerIsPostOwner && !comment.viewerIsAuthor && !isReply) {
                DropdownMenuItem(
                    text = { Text(if (comment.pinnedByOwner) "Unpin from top" else "Pin to top") },
                    onClick = { menuOpen = false; onPin() }
                )
            }
            if (!comment.deleted && !comment.viewerIsAuthor) {
                DropdownMenuItem(text = { Text("Report") }, onClick = { menuOpen = false; onReport() })
            }
        }
    }
}

/** Shimmer-free skeleton row used while pages load. */
@Composable
fun CommentSkeletonRow(isReply: Boolean = false) {
    val shimmer = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Row(
        Modifier.fillMaxWidth()
            .padding(start = if (isReply) 56.dp else 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.size(if (isReply) 34.dp else 42.dp).clip(CircleShape).background(shimmer))
        Spacer(Modifier.width(10.dp))
        Column {
            Box(Modifier.width(120.dp).height(13.dp).clip(RoundedCornerShape(4.dp)).background(shimmer))
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth(0.85f).height(13.dp).clip(RoundedCornerShape(4.dp)).background(shimmer))
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth(0.55f).height(13.dp).clip(RoundedCornerShape(4.dp)).background(shimmer))
        }
    }
}
