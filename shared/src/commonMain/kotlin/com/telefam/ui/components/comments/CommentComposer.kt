@file:OptIn(org.jetbrains.compose.resources.ExperimentalResourceApi::class)

package com.telefam.ui.components.comments

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.telefam.data.api.DirectoryUserDto
import com.telefam.data.api.GiphyApi
import com.telefam.data.api.GiphyItem
import com.telefam.data.api.GiphyMode
import com.telefam.data.api.UserDirectoryApi
import com.telefam.posts.CommentDto
import com.telefam.ui.components.ProcessedImage
import com.telefam.ui.theme.TelefamColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import org.jetbrains.compose.resources.decodeToImageBitmap

/** One staged attachment waiting in the composer's preview strip before sending. */
sealed class PendingAttachment {
    data class Photo(val image: ProcessedImage) : PendingAttachment()
    data class Sticker(val item: GiphyItem) : PendingAttachment()
    data class Gif(val item: GiphyItem) : PendingAttachment()
}

/**
 * The bottom composer (reference image 1): photo picker (native crop+compress pipeline),
 * emoji shortcut, the autolinking text field with @mention autocomplete, the star icon
 * that opens the Send Stars sheet, and the round send button.
 *
 * Nothing is sent directly: photos and stickers/GIFs are staged in a preview strip the
 * user must confirm (tap send) — "tap to review before sending". Text can be reviewed
 * and edited before send; in edit mode the composer is pre-filled with the existing body.
 */
@Composable
fun CommentComposer(
    enabled: Boolean,
    sending: Boolean,
    replyingTo: CommentDto?,
    editing: CommentDto?,
    attachment: PendingAttachment?,
    directoryApi: UserDirectoryApi?,
    onPickPhoto: () -> Unit,
    onOpenStickers: () -> Unit,
    onOpenStars: () -> Unit,
    onClearReply: () -> Unit,
    onClearEdit: () -> Unit,
    onRemoveAttachment: () -> Unit,
    onSendText: (String) -> Unit,
    onMentionSuggestions: (List<DirectoryUserDto>) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<DirectoryUserDto>>(emptyList()) }

    // Edit mode: pre-fill with the existing body.
    LaunchedEffect(editing?.commentId) {
        if (editing != null) text = editing.body ?: ""
    }
    LaunchedEffect(replyingTo?.commentId) {
        if (replyingTo != null) {
            val handle = replyingTo.authorUsername
            if (handle != null && !text.startsWith("@$handle")) text = "@$handle "
        }
    }

    // @mention autocomplete: detect the handle fragment at the caret-ish (end of text).
    LaunchedEffect(text) {
        val match = Regex("""@([A-Za-z0-9_.]{1,32})$""").find(text)
        if (match != null && directoryApi != null) {
            delay(250) // debounce
            val q = match.groupValues[1]
            suggestions = runCatching { directoryApi.search(q).take(5) }.getOrDefault(emptyList())
            onMentionSuggestions(suggestions)
        } else {
            suggestions = emptyList()
            onMentionSuggestions(emptyList())
        }
    }

    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        // @mention suggestions
        if (suggestions.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                suggestions.forEach { user ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                text = text.replace(
                                    Regex("""@[A-Za-z0-9_.]{1,32}$"""),
                                    "@${user.username ?: user.fullName ?: ""} "
                                )
                                suggestions = emptyList()
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(30.dp).clip(CircleShape)
                                .background(TelefamColors.PrimaryRed.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                (user.fullName ?: user.username ?: "?").take(1).uppercase(),
                                fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(user.fullName ?: "", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface)
                            user.username?.let {
                                Text("@$it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        // Reply-to / editing banners
        if (replyingTo != null) {
            ComposerBanner(
                label = "Replying to ${replyingTo.authorUsername ?: replyingTo.displayName}",
                onClear = onClearReply
            )
        }
        if (editing != null) {
            ComposerBanner(label = "Editing comment", onClear = { onClearEdit(); text = "" })
        }

        // Attachment preview strip: review (and remove) before sending.
        if (attachment != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    when (attachment) {
                        is PendingAttachment.Photo -> {
                            val bmp = remember(attachment.image.bytes) {
                                runCatching { attachment.image.bytes.decodeToImageBitmap() }.getOrNull()
                            }
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp, contentDescription = "Photo preview",
                                    modifier = Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                        is PendingAttachment.Sticker -> AsyncImage(
                            model = attachment.item.previewUrl, contentDescription = "Sticker preview",
                            modifier = Modifier.size(96.dp).clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentScale = ContentScale.Fit
                        )
                        is PendingAttachment.Gif -> AsyncImage(
                            model = attachment.item.previewUrl, contentDescription = "GIF preview",
                            modifier = Modifier.size(96.dp).clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentScale = ContentScale.Fit
                        )
                    }
                    Icon(
                        Icons.Filled.Close, contentDescription = "Remove",
                        tint = Color.White,
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                            .size(22.dp).clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f))
                            .clickable(onClick = onRemoveAttachment)
                            .padding(3.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    when (attachment) {
                        is PendingAttachment.Photo -> "Photo ready — tap send to post"
                        is PendingAttachment.Sticker -> "Sticker ready — tap send to post"
                        is PendingAttachment.Gif -> "GIF ready — tap send to post"
                    },
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Input row: [photo] [emoji] [field ........ ⭐] [send]
        Row(
            Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPickPhoto, enabled = enabled && !sending) {
                Icon(Icons.Outlined.Image, contentDescription = "Add photo", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onOpenStickers, enabled = enabled && !sending) {
                Icon(Icons.Outlined.EmojiEmotions, contentDescription = "Stickers and GIFs", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (text.isEmpty()) {
                            Text(
                                if (enabled) "Add a comment..." else "Comments are turned off",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp
                            )
                        }
                        BasicTextField(
                            value = text,
                            onValueChange = { if (it.length <= 2000) text = it },
                            enabled = enabled && !sending,
                            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
                            cursorBrush = SolidColor(TelefamColors.PrimaryRed),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Icon(
                        Icons.Filled.Star, contentDescription = "Send stars",
                        tint = Color(0xFFFFC107),
                        modifier = Modifier.size(24.dp).clickable(enabled = enabled && !sending, onClick = onOpenStars)
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            val canSend = enabled && !sending && (attachment != null || text.isNotBlank())
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .background(if (canSend) TelefamColors.PrimaryRed else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = canSend) {
                        if (editing != null) onSendText(text.trim())
                        else if (attachment != null) onSendText(text.trim().ifBlank { null } ?: "")
                        else onSendText(text.trim())
                        text = ""
                    },
                contentAlignment = Alignment.Center
            ) {
                if (sending) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Send, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun ComposerBanner(label: String, onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Icon(
            Icons.Filled.Close, contentDescription = "Cancel",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp).clickable(onClick = onClear)
        )
    }
}

/**
 * Inline sticker/GIF picker panel (same GIPHY source as chats): tabs, debounced search,
 * infinite-scrolling grid; tapping a tile stages it in the composer preview — nothing
 * is sent until the user confirms.
 */
@Composable
fun StickerGifPanel(
    api: GiphyApi,
    onPick: (GiphyItem, GiphyMode) -> Unit,
    onClose: () -> Unit
) {
    var mode by remember { mutableStateOf(GiphyMode.STICKER) }
    var query by remember { mutableStateOf("") }
    var debounced by remember { mutableStateOf("") }
    var items by remember { mutableStateOf(listOf<GiphyItem>()) }
    var loading by remember { mutableStateOf(false) }
    var endReached by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(query) { delay(350); debounced = query.trim() }

    suspend fun loadPage(reset: Boolean) {
        if (loading) return
        loading = true
        val page = runCatching { api.page(mode, debounced, if (reset) 0 else items.size) }.getOrDefault(emptyList())
        items = if (reset) page else items + page.filter { p -> items.none { it.id == p.id } }
        endReached = page.size < 24
        loading = false
    }
    LaunchedEffect(mode, debounced) { loadPage(reset = true) }

    // Infinite scroll
    LaunchedEffect(gridState, items.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (!endReached && last >= items.size - 6) loadPage(reset = false) }
    }

    Column(
        Modifier.fillMaxWidth().height(320.dp)
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(GiphyMode.STICKER to "Stickers", GiphyMode.GIF to "GIFs").forEach { (m, label) ->
                val selected = m == mode
                Box(
                    Modifier.clip(RoundedCornerShape(18.dp))
                        .background(if (selected) TelefamColors.PrimaryRed else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { mode = m }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(label, color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(8.dp))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 14.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query, onValueChange = { query = it },
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
                cursorBrush = SolidColor(TelefamColors.PrimaryRed),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )
        }

        if (items.isEmpty() && loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TelefamColors.PrimaryRed)
            }
        } else if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing found", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    AsyncImage(
                        model = item.previewUrl, contentDescription = item.title,
                        modifier = Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onPick(item, mode) },
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
    }
}
