package com.telefam.ui.components.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.posts.FeedPostDto
import com.telefam.posts.FeedVideoSurface
import com.telefam.posts.rememberPostSharer
import com.telefam.posts.rememberPostDownloader
import com.telefam.posts.FeedViewModel
import com.telefam.shared.generated.resources.Res
import com.telefam.shared.generated.resources.*
import com.telefam.ui.components.ConfirmDialog
import org.jetbrains.compose.resources.stringResource

/**
 * The reusable full-screen vertical video pager — the same component renders the
 * For You / Friends / Following / New creators feeds, search-result playback (opened
 * as an overlay, NOT a navigation to the feeds screen), and a user's posts from their
 * profile. All playback chrome, gestures and post actions live here so every surface
 * behaves identically.
 *
 * @param overlayBottomContent slot rendered above the pager bottom (e.g. nav bar space).
 */
@Composable
fun FeedsPager(
    viewModel: FeedViewModel,
    suggestionLabelFor: (FeedPostDto) -> String?,
    shareTargets: List<ShareTarget>,
    onSendToChat: (FeedPostDto, ShareTarget) -> Unit,
    onEditPost: (FeedPostDto) -> Unit,
    onHashtagClick: (String) -> Unit,
    /** Opens the full comment sheet for the post. */
    onOpenComments: (FeedPostDto) -> Unit,
    onNotify: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialIndex: Int = 0,
    /** Tapping the creator's avatar or name anywhere in the pager opens their profile. */
    onOwnerClick: (FeedPostDto) -> Unit = {},
    /** Sponsored item settled on screen — the host records the (deduped) impression. */
    onSponsoredImpression: (FeedPostDto) -> Unit = {},
    /** Sponsored CTA tapped (Watch / Visit / Follow / Buy / ...). */
    onSponsoredAction: (FeedPostDto) -> Unit = {},
    overlayBottomContent: @Composable () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val online by viewModel.online.collectAsState()

    val shareTextTemplate = stringResource(Res.string.feed_share_text)
    val downloadStartedText = stringResource(Res.string.feed_download_started)
    val downloadDoneText = stringResource(Res.string.feed_download_done)
    val downloadFailedText = stringResource(Res.string.feed_download_failed)
    val reportThanksText = stringResource(Res.string.feed_report_thanks)
    val reportFailedText = stringResource(Res.string.feed_report_failed)
    val sentToChatText = stringResource(Res.string.feed_sent_to_chat)
    val deleteFailedText = stringResource(Res.string.feed_delete_failed)

    val share = rememberPostSharer()
    val download = rememberPostDownloader { event ->
        when (event) {
            "download_started" -> onNotify(downloadStartedText)
            "download_finished" -> onNotify(downloadDoneText)
            else -> onNotify(downloadFailedText)
        }
    }

    var actionsPost by remember { mutableStateOf<FeedPostDto?>(null) }
    var reportPost by remember { mutableStateOf<FeedPostDto?>(null) }
    var reportSubmitting by remember { mutableStateOf(false) }
    var shareChatsPost by remember { mutableStateOf<FeedPostDto?>(null) }
    var deletePostTarget by remember { mutableStateOf<FeedPostDto?>(null) }
    var heartBursts by remember { mutableStateOf(mapOf<String, Int>()) }

    if (state.loadingInitial) {
        Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color(0xFFD32323))
        }
        return
    }

    if (state.items.isEmpty()) {
        Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text(
                stringResource(
                    when {
                        !online -> Res.string.feed_no_internet_empty
                        state.loadFailed -> Res.string.feed_load_failed
                        else -> Res.string.feed_empty
                    }
                ),
                color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp
            )
        }
        return
    }

    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, state.items.lastIndex)) { state.items.size }

    // Autoplay the settled page; pause everything else. Preload ahead + record views.
    LaunchedEffect(pagerState.settledPage, state.items.size) {
        val post = state.items.getOrNull(pagerState.settledPage) ?: return@LaunchedEffect
        viewModel.onItemVisible(post)
        if (post.sponsored) onSponsoredImpression(post)
        viewModel.ensureLoadedAhead(pagerState.settledPage)
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val post = state.items.getOrNull(page) ?: return@VerticalPager
            FeedPage(
                post = post,
                isCurrent = page == pagerState.settledPage,
                online = online,
                viewModel = viewModel,
                heartBurst = heartBursts[post.postId] ?: 0,
                onDoubleTapLike = {
                    heartBursts = heartBursts + (post.postId to (heartBursts[post.postId] ?: 0) + 1)
                    if (!post.viewerLiked) viewModel.toggleLike(post)
                },
                onLike = { viewModel.toggleLike(post) },
                onSave = { viewModel.toggleSave(post) },
                onFollow = { viewModel.follow(post) },
                onComment = { onOpenComments(post) },
                onReshare = {
                    share("$shareTextTemplate ${post.shareUrl}")
                    viewModel.reshare(post, "EXTERNAL")
                },
                onMore = { actionsPost = post },
                suggestionLabel = suggestionLabelFor(post),
                onHashtagClick = onHashtagClick,
                onOwnerClick = { onOwnerClick(post) },
                onSponsoredAction = { onSponsoredAction(post) }
            )
        }

        // Bottom scrim so captions stay readable on bright videos.
        Box(
            Modifier.fillMaxWidth().height(160.dp).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
        )
        overlayBottomContent()
    }

    // --- Sheets & dialogs ---
    actionsPost?.let { post ->
        PostActionsSheet(
            post = post,
            online = online,
            onDismiss = { actionsPost = null },
            onShareExternal = {
                share("$shareTextTemplate ${post.shareUrl}")
                viewModel.reshare(post, "EXTERNAL")
            },
            onShareToChats = { shareChatsPost = post },
            onDownload = {
                viewModel.playableUrl(post)?.let { download(it, "telefam_${post.postId}") }
            },
            onReport = { reportPost = post },
            onBlock = { viewModel.blockOwner(post) },
            onNotInterested = { viewModel.notInterested(post) },
            onEdit = { onEditPost(post) },
            onDelete = { deletePostTarget = post },
            onToggleDownloads = { allowed -> viewModel.setDownloadsAllowed(post, allowed) }
        )
    }

    reportPost?.let { post ->
        PostReportSheet(
            post = post,
            submitting = reportSubmitting,
            onSubmit = { reason, details ->
                reportSubmitting = true
                viewModel.report(post, reason, details) { ok ->
                    reportSubmitting = false
                    reportPost = null
                    onNotify(if (ok) reportThanksText else reportFailedText)
                }
            },
            onDismiss = { if (!reportSubmitting) reportPost = null }
        )
    }

    shareChatsPost?.let { post ->
        ShareToChatsSheet(
            targets = shareTargets,
            onPick = { target ->
                onSendToChat(post, target)
                viewModel.reshare(post, "CHAT")
                shareChatsPost = null
                onNotify(sentToChatText)
            },
            onDismiss = { shareChatsPost = null }
        )
    }

    deletePostTarget?.let { post ->
        ConfirmDialog(
            title = stringResource(Res.string.feed_delete_confirm_title),
            message = stringResource(Res.string.feed_delete_confirm_message),
            confirmLabel = stringResource(Res.string.feed_delete_post),
            onConfirm = {
                viewModel.deletePost(post) { ok -> if (!ok) onNotify(deleteFailedText) }
                deletePostTarget = null
            },
            onDismiss = { deletePostTarget = null }
        )
    }
}

@Composable
private fun FeedPage(
    post: FeedPostDto,
    isCurrent: Boolean,
    online: Boolean,
    viewModel: FeedViewModel,
    heartBurst: Int,
    onDoubleTapLike: () -> Unit,
    onLike: () -> Unit,
    onSave: () -> Unit,
    onFollow: () -> Unit,
    onComment: () -> Unit,
    onReshare: () -> Unit,
    onMore: () -> Unit,
    suggestionLabel: String?,
    onHashtagClick: (String) -> Unit,
    onOwnerClick: () -> Unit = {},
    onSponsoredAction: () -> Unit = {}
) {
    var paused by remember(post.postId) { mutableStateOf(false) }
    var speed by remember(post.postId) { mutableStateOf(1f) }
    var buffering by remember(post.postId) { mutableStateOf(true) }
    var failed by remember(post.postId) { mutableStateOf(false) }

    val playing = isCurrent && !paused
    val url = remember(post.postId, post.variants) { viewModel.playableUrl(post) }

    Box(Modifier.fillMaxSize()) {
        if (url != null && !failed) {
            FeedVideoSurface(
                url = url,
                cacheKey = post.postId,
                playing = playing,
                speed = speed,
                modifier = Modifier.fillMaxSize(),
                onBuffering = { buffering = it },
                onReady = { buffering = false },
                onEnded = { viewModel.onItemCompleted(post, post.durationMs) },
                onError = { failed = true; buffering = false }
            )
        }

        if (url == null || failed) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (post.subscriberOnly && !post.isOwner && url == null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("Subscribers only", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Text(
                        stringResource(if (!online) Res.string.feed_no_internet_video else Res.string.feed_video_unavailable),
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
        }

        FeedGestureLayer(
            onSingleTap = { paused = !paused },
            onDoubleTap = onDoubleTapLike,
            onSpeedChange = { speed = it },
            modifier = Modifier.fillMaxSize()
        )

        HeartBurst(heartBurst, Modifier.align(Alignment.Center))
        PausedOverlay(visible = isCurrent && paused, Modifier.align(Alignment.Center))

        if (buffering && isCurrent && !failed && url != null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(44.dp), color = Color(0xFFD32323), strokeWidth = 3.dp)
        }

        // Right action rail
        FeedActionRail(
            post = post,
            avatarAbsoluteUrl = post.ownerAvatarUrl?.let {
                if (it.startsWith("http")) it else com.telefam.data.api.ApiConfig.baseUrl.trimEnd('/') + it
            },
            onLike = onLike,
            onComment = onComment,
            onReshare = onReshare,
            onSave = onSave,
            onFollow = onFollow,
            onMore = onMore,
            onAvatarClick = onOwnerClick,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 96.dp)
        )

        // Bottom-left info
        FeedInfoOverlay(
            post = post,
            suggestionLabel = if (post.sponsored) null else suggestionLabel,
            onHashtagClick = onHashtagClick,
            onOwnerClick = onOwnerClick,
            modifier = Modifier.align(Alignment.BottomStart)
                .padding(start = 16.dp, end = 86.dp, bottom = 96.dp)
                .fillMaxWidth()
        )

        // Sponsored chrome: "Sponsored" pill + the campaign's CTA (Watch / Visit /
        // Follow / Buy / ...). Server-decided label and behavior; tapping records a
        // deduped click via the host callback.
        if (post.sponsored && post.sponsoredCta != null) {
            Column(
                Modifier.align(Alignment.BottomStart)
                    .padding(start = 16.dp, end = 86.dp, bottom = 170.dp)
            ) {
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.18f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text("Sponsored", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(Color(0xFFD32323))
                        .clickable(onClick = onSponsoredAction)
                        .padding(horizontal = 22.dp, vertical = 11.dp)
                ) {
                    Text(post.sponsoredCta, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
