package com.telefam.ui.components.comments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.telefam.data.api.CampaignApi
import com.telefam.data.api.CommentsApi
import com.telefam.data.api.GiphyApi
import com.telefam.data.api.GiphyMode
import com.telefam.data.api.UserDirectoryApi
import com.telefam.posts.CommentDto
import com.telefam.posts.CommentsViewModel
import com.telefam.posts.CreateCommentRequest
import com.telefam.posts.FeedPostDto
import com.telefam.posts.InsufficientStarsException
import com.telefam.ui.components.ProcessedImage
import com.telefam.ui.components.rememberImagePickerCropCompress
import com.telefam.ui.theme.TelefamColors
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.datetime.toLocalDateTime

/**
 * The full comment sheet for a feed post (reference image 1), themed by the app/system
 * (dark and light). Wires together: paginated comments with skeletons + infinite scroll,
 * lazy threaded replies, comment likes, follow "+", read more/less, autolinks,
 * @mention autocomplete, photo/sticker/GIF comments with preview-before-send,
 * long-press actions (copy/edit/delete/pin/report), the 3-dots report form,
 * owner pin, the Send Stars sheet (reference image 2) with wallet-balance gating and
 * the Buy Stars redirect, and the 3D star celebration on a successful gift.
 */
@Composable
fun CommentsScreen(
    post: FeedPostDto,
    viewModel: CommentsViewModel,
    commentsApi: CommentsApi,
    giphyApi: GiphyApi?,
    directoryApi: UserDirectoryApi?,
    campaignApi: CampaignApi,
    onClose: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onFollow: (String) -> Unit,
    onBuyStars: () -> Unit,
    onHashtagClick: (String) -> Unit,
    onUrlClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val online by viewModel.online.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()

    var searchMode by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var replyingTo by remember { mutableStateOf<CommentDto?>(null) }
    var editing by remember { mutableStateOf<CommentDto?>(null) }
    var attachment by remember { mutableStateOf<PendingAttachment?>(null) }
    var stickerPanelOpen by remember { mutableStateOf(false) }
    var reportTarget by remember { mutableStateOf<CommentDto?>(null) }
    var reportSubmitting by remember { mutableStateOf(false) }
    var starsTarget by remember { mutableStateOf<CommentDto?>(null) }
    var starSending by remember { mutableStateOf(false) }
    var celebration by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var mediaViewerUrl by remember { mutableStateOf<String?>(null) }
    var mediaViewerReport by remember { mutableStateOf<CommentDto?>(null) }
    var deleteTarget by remember { mutableStateOf<CommentDto?>(null) }
    var showStars by remember { mutableStateOf(false) }

    LaunchedEffect(post.postId) { viewModel.load(post.postId) }

    // One-shot events -> snackbar
    val event by viewModel.events.collectAsState()
    LaunchedEffect(event) {
        event?.let { snackbar.showSnackbar(it); viewModel.consumeEvent() }
    }

    // Infinite scroll for top-level comments (and search results)
    LaunchedEffect(listState, state.comments.size, state.searchResults.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last ->
                val total = listState.layoutInfo.totalItemsCount
                if (total > 0 && last >= total - 4) viewModel.loadMore()
            }
    }

    // Photo pick -> native crop+compress pipeline -> staged preview (not sent yet)
    var photoError by remember { mutableStateOf<String?>(null) }
    val pickPhoto = rememberImagePickerCropCompress(maxDimension = 1280, jpegQuality = 0.85f) { processed ->
        if (processed != null) {
            attachment = PendingAttachment.Photo(processed)
            stickerPanelOpen = false
        }
    }

    val mediaAbsolute: (String) -> String = { commentsApi.absoluteUrl(it) }
    val openMention: (String) -> Unit = { handle ->
        scope.launch {
            val user = runCatching { directoryApi?.lookupByUsername(handle) }.getOrNull()
            if (user != null) onOpenProfile(user.userId)
        }
    }

    fun send(text: String) {
        val att = attachment
        val parent = replyingTo
        val editTarget = editing
        if (editTarget != null) {
            if (text.isBlank()) return
            viewModel.edit(editTarget, text)
            editing = null
            return
        }
        val body = text.ifBlank { null }
        val kind = when (att) {
            is PendingAttachment.Photo -> "PHOTO"
            is PendingAttachment.Sticker -> "STICKER"
            is PendingAttachment.Gif -> "GIF"
            null -> "TEXT"
        }
        if (kind == "TEXT" && body == null) return

        val tempId = "local-" + newLocalId()
        val now = kotlinx.datetime.Clock.System.now()
            .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).toString()
        val optimistic = CommentDto(
            commentId = tempId, postId = post.postId,
            parentId = parent?.commentId, rootId = parent?.rootId ?: tempId,
            authorId = "", authorUsername = null, authorFullName = "You",
            kind = kind, body = body,
            stickerUrl = when (att) {
                is PendingAttachment.Sticker -> att.item.sendUrl
                is PendingAttachment.Gif -> att.item.sendUrl
                else -> null
            },
            createdAt = now
        )

        scope.launch {
            // Photos go through the standard media upload pipeline first.
            var mediaId: String? = null
            if (att is PendingAttachment.Photo) {
                mediaId = runCatching { commentsApi.uploadPhoto(att.image.bytes, att.image.mimeType) }
                    .getOrElse {
                        snackbar.showSnackbar("Couldn't upload photo. Try again.")
                        return@launch
                    }
            }
            viewModel.send(
                CreateCommentRequest(
                    body = body,
                    parentId = parent?.commentId,
                    kind = kind,
                    mediaId = mediaId,
                    stickerUrl = optimistic.stickerUrl
                ),
                optimistic = optimistic.copy(mediaUrl = null),
                replyToRoot = parent?.rootId ?: parent?.commentId
            )
            attachment = null
            replyingTo = null
        }
    }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // Header: back, "Comments" + count, search, close (reference layout)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Comments", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "${formatCommentCount(state.totalCount)} comments",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = {
                    searchMode = !searchMode
                    if (!searchMode) { searchText = ""; viewModel.clearSearch() }
                }) {
                    Icon(Icons.Filled.Search, contentDescription = "Search comments",
                        tint = if (searchMode) TelefamColors.PrimaryRed else MaterialTheme.colorScheme.onSurface)
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }

            if (searchMode) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = searchText,
                        onValueChange = { searchText = it; viewModel.search(it) },
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
                        cursorBrush = SolidColor(TelefamColors.PrimaryRed),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                    )
                }
            }

            // The comment list (or search results)
            Box(Modifier.weight(1f)) {
                when {
                    state.loadingInitial && state.comments.isEmpty() -> {
                        Column { repeat(6) { CommentSkeletonRow() } }
                    }
                    state.loadFailed && state.comments.isEmpty() -> {
                        Column(
                            Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                if (!online) "No internet connection" else "Couldn't load comments",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { viewModel.refresh() },
                                colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed)
                            ) { Text("Retry", color = Color.White) }
                        }
                    }
                    else -> {
                        val searching = state.searchQuery != null
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            if (searching) {
                                if (state.searchResults.isEmpty() && !state.searchLoading) {
                                    item {
                                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                            Text("No comments match \"${state.searchQuery}\"",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                                items(state.searchResults.size, key = { "s_" + state.searchResults[it].commentId }) { i ->
                                    CommentRow(
                                        comment = state.searchResults[i],
                                        viewModel = viewModel, post = post,
                                        mediaAbsolute = mediaAbsolute,
                                        onOpenProfile = onOpenProfile, onFollow = onFollow,
                                        onReply = { c -> replyingTo = c; editing = null },
                                        onCopy = { c -> c.body?.let { clipboard.setText(AnnotatedString(it)) } },
                                        onEdit = { c -> editing = c; replyingTo = null },
                                        onDelete = { c -> deleteTarget = c },
                                        onPin = { c -> viewModel.setPinned(c, !c.pinnedByOwner) },
                                        onReport = { c -> reportTarget = c },
                                        onMediaClick = { c ->
                                            mediaViewerUrl = when (c.kind) {
                                                "PHOTO" -> c.mediaUrl?.let(mediaAbsolute)
                                                else -> c.stickerUrl
                                            }
                                            mediaViewerReport = c
                                        },
                                        onMentionClick = openMention,
                                        onHashtagClick = onHashtagClick,
                                        onUrlClick = onUrlClick
                                    )
                                }
                                if (state.searchLoading) {
                                    item { Column { repeat(3) { CommentSkeletonRow() } } }
                                }
                            } else {
                                // Pinned comment slot — only the owner can put one here.
                                state.pinned?.let { pinned ->
                                    item(key = "pinned") {
                                        Column {
                                            CommentRow(
                                                comment = pinned, viewModel = viewModel, post = post,
                                                mediaAbsolute = mediaAbsolute,
                                                onOpenProfile = onOpenProfile, onFollow = onFollow,
                                                onReply = { c -> replyingTo = c; editing = null },
                                                onCopy = { c -> c.body?.let { clipboard.setText(AnnotatedString(it)) } },
                                                onEdit = { c -> editing = c; replyingTo = null },
                                                onDelete = { c -> deleteTarget = c },
                                                onPin = { c -> viewModel.setPinned(c, false) },
                                                onReport = { c -> reportTarget = c },
                                                onMediaClick = { c ->
                                                    mediaViewerUrl = when (c.kind) {
                                                        "PHOTO" -> c.mediaUrl?.let(mediaAbsolute)
                                                        else -> c.stickerUrl
                                                    }
                                                    mediaViewerReport = c
                                                },
                                                onMentionClick = openMention,
                                                onHashtagClick = onHashtagClick,
                                                onUrlClick = onUrlClick
                                            )
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                        }
                                    }
                                }

                                if (state.comments.isEmpty() && !state.loadingInitial) {
                                    item {
                                        Column(
                                            Modifier.fillMaxWidth().padding(48.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text("No comments yet", fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface)
                                            Text("Be the first to share your thoughts",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                                        }
                                    }
                                }

                                items(state.comments.size, key = { state.comments[it].commentId }) { i ->
                                    val comment = state.comments[i]
                                    Column {
                                        CommentRow(
                                            comment = comment, viewModel = viewModel, post = post,
                                            mediaAbsolute = mediaAbsolute,
                                            onOpenProfile = onOpenProfile, onFollow = onFollow,
                                            onReply = { c -> replyingTo = c; editing = null },
                                            onCopy = { c -> c.body?.let { clipboard.setText(AnnotatedString(it)) } },
                                            onEdit = { c -> editing = c; replyingTo = null },
                                            onDelete = { c -> deleteTarget = c },
                                            onPin = { c -> viewModel.setPinned(c, !c.pinnedByOwner) },
                                            onReport = { c -> reportTarget = c },
                                            onMediaClick = { c ->
                                                mediaViewerUrl = when (c.kind) {
                                                    "PHOTO" -> c.mediaUrl?.let(mediaAbsolute)
                                                    else -> c.stickerUrl
                                                }
                                                mediaViewerReport = c
                                            },
                                            onMentionClick = openMention,
                                            onHashtagClick = onHashtagClick,
                                            onUrlClick = onUrlClick
                                        )

                                        // Lazy replies: "View N replies" expands the thread in place.
                                        val thread = state.threads[comment.commentId]
                                        if (!comment.deleted && comment.replyCount > 0) {
                                            if (thread?.expanded != true) {
                                                Box(
                                                    Modifier.padding(start = 58.dp, bottom = 6.dp)
                                                        .clip(RoundedCornerShape(14.dp))
                                                        .clickable { viewModel.toggleReplies(comment.commentId) }
                                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                                ) {
                                                    Text(
                                                        "View ${formatCommentCount(comment.replyCount)} " +
                                                            (if (comment.replyCount == 1L) "reply" else "replies"),
                                                        color = TelefamColors.PrimaryRed, fontSize = 13.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            }
                                        }
                                        if (thread?.expanded == true) {
                                            Column(Modifier.padding(start = 42.dp)) {
                                                when {
                                                    thread.loading -> repeat(2) { CommentSkeletonRow(isReply = true) }
                                                    thread.failed && thread.items.isEmpty() -> {
                                                        Text(
                                                            "Couldn't load replies. Tap to retry.",
                                                            color = TelefamColors.PrimaryRed, fontSize = 13.sp,
                                                            modifier = Modifier.clickable {
                                                                viewModel.toggleReplies(comment.commentId)
                                                                viewModel.toggleReplies(comment.commentId)
                                                            }.padding(12.dp)
                                                        )
                                                    }
                                                    else -> thread.items.forEach { reply ->
                                                        CommentRow(
                                                            comment = reply, isReply = true,
                                                            viewModel = viewModel, post = post,
                                                            mediaAbsolute = mediaAbsolute,
                                                            onOpenProfile = onOpenProfile, onFollow = onFollow,
                                                            onReply = { c -> replyingTo = c; editing = null },
                                                            onCopy = { c -> c.body?.let { clipboard.setText(AnnotatedString(it)) } },
                                                            onEdit = { c -> editing = c; replyingTo = null },
                                                            onDelete = { c -> deleteTarget = c },
                                                            onPin = { },
                                                            onReport = { c -> reportTarget = c },
                                                            onMediaClick = { c ->
                                                                mediaViewerUrl = when (c.kind) {
                                                                    "PHOTO" -> c.mediaUrl?.let(mediaAbsolute)
                                                                    else -> c.stickerUrl
                                                                }
                                                                mediaViewerReport = c
                                                            },
                                                            onMentionClick = openMention,
                                                            onHashtagClick = onHashtagClick,
                                                            onUrlClick = onUrlClick
                                                        )
                                                    }
                                                }
                                                // More replies pagination + collapse
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    if (thread.nextCursor != null) {
                                                        Text(
                                                            if (thread.loadingMore) "Loading…" else "View more replies",
                                                            color = TelefamColors.PrimaryRed, fontSize = 13.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            modifier = Modifier
                                                                .clip(RoundedCornerShape(14.dp))
                                                                .clickable(enabled = !thread.loadingMore) {
                                                                    viewModel.loadMoreReplies(comment.commentId)
                                                                }
                                                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                                        )
                                                    }
                                                    Spacer(Modifier.weight(1f))
                                                    Text(
                                                        "Hide replies",
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(14.dp))
                                                            .clickable { viewModel.toggleReplies(comment.commentId) }
                                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                if (state.loadingMore) {
                                    item { Column { repeat(2) { CommentSkeletonRow() } } }
                                }
                                if (!state.endReached && state.comments.isNotEmpty()) {
                                    item {
                                        LaunchedEffect(Unit) { viewModel.loadMore() }
                                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                            CircularProgressIndicator(Modifier.size(24.dp), color = TelefamColors.PrimaryRed, strokeWidth = 2.dp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Offline banner
                if (!online) {
                    Row(
                        Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                            .background(Color(0xFF2A0B0B))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("No internet connection", color = Color.White, fontSize = 13.sp)
                    }
                }
            }

            // Comments off notice / composer
            if (state.commenting == "OFF") {
                Box(
                    Modifier.fillMaxWidth().padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Comments are turned off for this post",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                }
            } else {
                CommentComposer(
                    enabled = state.viewerCanComment,
                    sending = state.sending,
                    replyingTo = replyingTo,
                    editing = editing,
                    attachment = attachment,
                    directoryApi = directoryApi,
                    onPickPhoto = { pickPhoto() },
                    onOpenStickers = { stickerPanelOpen = !stickerPanelOpen },
                    onOpenStars = {
                        // Stars go to the comment author the user is replying to, or the post creator.
                        starsTarget = replyingTo
                        starSending = false
                        showStars = true
                    },
                    onClearReply = { replyingTo = null },
                    onClearEdit = { editing = null },
                    onRemoveAttachment = { attachment = null },
                    onSendText = { text -> send(text) }
                )
                if (!state.viewerCanComment) {
                    Text(
                        "Only people ${post.displayName} follows can comment",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                if (stickerPanelOpen && giphyApi != null) {
                    StickerGifPanel(
                        api = giphyApi,
                        onPick = { item, mode ->
                            attachment = when (mode) {
                                GiphyMode.GIF, GiphyMode.CLIP -> PendingAttachment.Gif(item)
                                else -> PendingAttachment.Sticker(item)
                            }
                            stickerPanelOpen = false
                        },
                        onClose = { stickerPanelOpen = false }
                    )
                }
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp))
    }

    // ---- Overlays ----

    if (showStars) {
        val giftTarget = starsTarget
        val recipientName = giftTarget?.displayName ?: post.displayName
        val recipientHandle = giftTarget?.authorUsername ?: post.ownerUsername
        val recipientAvatar = giftTarget?.authorAvatarUrl?.let(mediaAbsolute)
            ?: post.ownerAvatarUrl?.let(mediaAbsolute)
        Dialog(
            onDismissRequest = { if (!starSending) showStars = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                SendStarsSheet(
                    creatorName = recipientName,
                    creatorUsername = recipientHandle,
                    creatorAvatarUrl = recipientAvatar,
                    campaignApi = campaignApi,
                    sending = starSending,
                    onSend = { stars ->
                        val target = giftTarget
                        // No comment selected: gift attaches to the post creator's top comment? No —
                        // stars always attach to a comment; without a reply target we gift the pinned
                        // comment if any, else we pick the creator's own first comment when present.
                        val commentId = target?.commentId
                            ?: state.pinned?.takeIf { it.authorId == post.ownerId }?.commentId
                            ?: state.comments.firstOrNull { it.authorId == post.ownerId }?.commentId
                        if (commentId == null) {
                            scope.launch { snackbar.showSnackbar("The creator has no comment to receive stars yet") }
                            return@SendStarsSheet
                        }
                        starSending = true
                        scope.launch {
                            when (val r = viewModel.giftStars(commentId, stars, newLocalId())) {
                                is CommentsViewModel.GiftResult.Success -> {
                                    showStars = false
                                    starSending = false
                                    celebration = stars to recipientName
                                }
                                is CommentsViewModel.GiftResult.Insufficient -> {
                                    starSending = false
                                    showStars = false
                                    onBuyStars() // reuse the campaign Buy Stars flow
                                }
                                is CommentsViewModel.GiftResult.Failed -> {
                                    starSending = false
                                    snackbar.showSnackbar(r.message)
                                }
                            }
                        }
                    },
                    onBuyStars = { showStars = false; onBuyStars() },
                    onClose = { if (!starSending) showStars = false }
                )
            }
        }
    }

    celebration?.let { (stars, name) ->
        StarCelebration(stars = stars, senderName = name, onDone = { celebration = null })
    }

    mediaViewerUrl?.let { url ->
        CommentMediaViewer(
            url = url,
            isAnimated = false,
            onDismiss = { mediaViewerUrl = null; mediaViewerReport = null },
            onDownloadEvent = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
            onReport = mediaViewerReport?.let { c -> { reportTarget = c; mediaViewerUrl = null } }
        )
    }

    reportTarget?.let { target ->
        CommentReportDialog(
            submitting = reportSubmitting,
            onSubmit = { reason, details ->
                reportSubmitting = true
                viewModel.report(target.commentId, reason, details) { ok ->
                    reportSubmitting = false
                    reportTarget = null
                    scope.launch {
                        snackbar.showSnackbar(
                            if (ok) "Thanks — our team will review this comment."
                            else "Couldn't send the report. Try again."
                        )
                    }
                }
            },
            onDismiss = { reportTarget = null }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete comment?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(target); deleteTarget = null }) {
                    Text("Delete", color = TelefamColors.PrimaryRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }

    photoError?.let {
        LaunchedEffect(it) { snackbar.showSnackbar(it); photoError = null }
    }
}

/** Internal row adapter: binds a CommentDto to CommentItem with follow-badge logic. */
@Composable
private fun CommentRow(
    comment: CommentDto,
    isReply: Boolean = false,
    viewModel: CommentsViewModel,
    post: FeedPostDto,
    mediaAbsolute: (String) -> String,
    onOpenProfile: (String) -> Unit,
    onFollow: (String) -> Unit,
    onReply: (CommentDto) -> Unit,
    onCopy: (CommentDto) -> Unit,
    onEdit: (CommentDto) -> Unit,
    onDelete: (CommentDto) -> Unit,
    onPin: (CommentDto) -> Unit,
    onReport: (CommentDto) -> Unit,
    onMediaClick: (CommentDto) -> Unit,
    onMentionClick: (String) -> Unit,
    onHashtagClick: (String) -> Unit,
    onUrlClick: (String) -> Unit
) {
    // The "+" badge shows only for people the viewer has no relationship with and who
    // aren't the viewer — the server hides follow state per-comment, so we surface the
    // badge for anyone but the viewer's own comments and let the server no-op repeats.
    val showFollow = !comment.viewerIsAuthor && comment.authorId.isNotBlank() && !comment.deleted
    CommentItem(
        comment = comment,
        isReply = isReply,
        showFollowBadge = showFollow,
        onLike = { viewModel.toggleLike(comment) },
        onReply = { onReply(comment) },
        onFollow = { onFollow(comment.authorId) },
        onAvatarClick = { if (comment.authorId.isNotBlank()) onOpenProfile(comment.authorId) },
        onCopy = { onCopy(comment) },
        onEdit = { onEdit(comment) },
        onDelete = { onDelete(comment) },
        onPin = { onPin(comment) },
        onReport = { onReport(comment) },
        onMediaClick = { onMediaClick(comment) },
        onMediaLongPress = { onMediaClick(comment) },
        onMentionClick = onMentionClick,
        onHashtagClick = onHashtagClick,
        onUrlClick = onUrlClick,
        mediaAbsoluteUrl = mediaAbsolute,
        modifier = Modifier.fillMaxWidth()
    )
}



/** Random 32-char idempotency/local id (same approach as CampaignViewModel). */
private fun newLocalId(): String {
    val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
    return (1..32).joinToString("") { chars[kotlin.random.Random.nextInt(chars.length)].toString() }
}
