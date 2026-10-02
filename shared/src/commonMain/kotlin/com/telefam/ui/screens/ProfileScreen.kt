package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.telefam.connect.ConnectionListKind
import com.telefam.connect.FollowAction
import com.telefam.connect.ProfileDetailsDto
import com.telefam.connect.ProfileViewModel
import com.telefam.posts.FeedPostDto
import com.telefam.posts.FeedSource
import com.telefam.posts.FeedViewModel
import com.telefam.shared.generated.resources.Res
import com.telefam.shared.generated.resources.*
import com.telefam.ui.components.ConfirmDialog
import com.telefam.ui.components.feed.FeedsPager
import com.telefam.ui.components.feed.ShareTarget
import com.telefam.ui.theme.TelefamColors
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private val PROFILE_REPORT_REASONS = listOf(
    "Spam or fake account",
    "Harassment or hate speech",
    "Impersonation",
    "Inappropriate content",
    "Scam or fraud",
    "Something else"
)

enum class ProfilePostTab(val path: String) { POSTS("posts"), RESHARED("reshared"), LOCKED("locked"), SAVED("saved") }

fun formatCount(count: Long): String = when {
    count >= 1_000_000 -> "${"%.1f".format(count / 1_000_000.0).removeSuffix(".0")}M"
    count >= 1_000 -> "${"%.1f".format(count / 1_000.0).removeSuffix(".0")}K"
    else -> count.toString()
}

/**
 * Profile screen — matches the reference: centered name + back + 3-dot top bar,
 * ringed tappable avatar, handle, tappable stats, Follow / Message / Subscribe
 * actions, bio + location, and a posts grid with view counts that opens the
 * shared FeedsPager for vertical video watching.
 *
 * Owner mode: Followers / Following / Subscribers stats, Edit profile + Settings
 * buttons, four tabs (Posts / Reshared / Locked / Saved) each backed by its own
 * FeedViewModel, and a coming-soon 3-dot menu.
 */
@Composable
fun ProfileScreen(
    userId: String,
    viewModel: ProfileViewModel,
    feedViewModelFactory: () -> FeedViewModel,
    shareTargets: List<ShareTarget>,
    onSendToChat: (FeedPostDto, ShareTarget) -> Unit,
    onEditPost: (FeedPostDto) -> Unit,
    onOpenSearch: (String) -> Unit,
    onOpenList: (userId: String, kind: ConnectionListKind) -> Unit,
    onMessage: (userId: String, displayName: String) -> Unit,
    onBack: () -> Unit,
    onEditProfile: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onShareProfile: () -> Unit = {},
    /** Owner 3-dot menu: opens the creator Menu screen (Dashboard, Wallet, Get Verified, …). */
    onOpenCreatorMenu: () -> Unit = {},
    /** Fan action: opens the paid-subscription page (plans, secure checkout) for this creator. */
    onSubscribe: () -> Unit = {},
    /** Comment sheet host: rendered as an overlay for the tapped post. */
    commentsSheet: @Composable (FeedPostDto, () -> Unit) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val online by viewModel.online.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    val subscribeComingSoon = stringResource(Res.string.profile_subscribe_coming)
    val urlCopied = stringResource(Res.string.profile_url_copied)
    val reportThanks = stringResource(Res.string.profile_report_thanks)
    val comingSoon = stringResource(Res.string.coming_soon)

    var menuOpen by remember { mutableStateOf(false) }
    var showBlockConfirm by remember { mutableStateOf(false) }
    var showUnfollowConfirm by remember { mutableStateOf(false) }
    var showReportForm by remember { mutableStateOf(false) }
    var showFullPhoto by remember { mutableStateOf(false) }
    var watchStartIndex by remember { mutableStateOf<Int?>(null) }
    var commentsPost by remember { mutableStateOf<FeedPostDto?>(null) }
    var selectedTab by remember(userId) { mutableStateOf(ProfilePostTab.POSTS) }

    // One shared FeedViewModel per tab so grid and video pager stay one source of truth.
    val tabViewModels = remember(userId) {
        ProfilePostTab.entries.associateWith { feedViewModelFactory() }
    }
    val activeViewModel = tabViewModels.getValue(selectedTab)
    val postsState by activeViewModel.state.collectAsState()

    LaunchedEffect(userId) {
        viewModel.load(userId)
        tabViewModels.getValue(ProfilePostTab.POSTS).load(FeedSource.UserPosts(userId))
    }
    // Load a tab the first time it is selected.
    val loadedTabs = remember(userId) { mutableStateMapOf(ProfilePostTab.POSTS to true) }
    LaunchedEffect(selectedTab) {
        if (loadedTabs[selectedTab] != true) {
            loadedTabs[selectedTab] = true
            tabViewModels.getValue(selectedTab).load(FeedSource.UserTab(userId, selectedTab.path))
        }
    }

    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState, postsState.items.size, selectedTab) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { activeViewModel.ensureLoadedAhead(it) }
    }

    val profile = state.profile
    val isOwner = profile?.isOwner == true

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            // --- Top bar: back, centered name, brand mark, 3-dot ---
            Row(
                Modifier.fillMaxWidth().statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground)
                }
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        profile?.displayName ?: "",
                        fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    if (profile?.isVerified == true) {
                        Spacer(Modifier.width(6.dp))
                        com.telefam.ui.components.VerifiedBadge(size = 17.dp)
                    }
                }
                // Brand mark doubles as the Share profile shortcut (QR + link).
                Box(
                    Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                        .background(Brush.linearGradient(listOf(TelefamColors.PrimaryRed, TelefamColors.PrimaryRedDark)))
                        .clickable(onClick = onShareProfile),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Send, contentDescription = stringResource(Res.string.profile_share),
                        tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Box {
                    // The official account has no 3-dot settings menu — just the account.
                    if (profile?.isOfficialAccount != true) {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(Res.string.more_options),
                            tint = MaterialTheme.colorScheme.onBackground)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        // Share profile (QR + link) is real for both owner and public profiles.
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.profile_share)) },
                            leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                            onClick = { menuOpen = false; onShareProfile() }
                        )
                        if (isOwner) {
                            // Owner menu opens the real creator Menu screen.
                            DropdownMenuItem(
                                text = { Text("Menu") },
                                leadingIcon = { Icon(Icons.Outlined.Menu, contentDescription = null) },
                                onClick = { menuOpen = false; onOpenCreatorMenu() }
                            )
                        } else {
                            if (profile?.isBlockedByViewer == true) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.profile_unblock)) },
                                    leadingIcon = { Icon(Icons.Outlined.Block, contentDescription = null) },
                                    onClick = { menuOpen = false; viewModel.unblock { } }
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.profile_block)) },
                                    leadingIcon = { Icon(Icons.Outlined.Block, contentDescription = null) },
                                    onClick = { menuOpen = false; showBlockConfirm = true }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.profile_report)) },
                                leadingIcon = { Icon(Icons.Outlined.Flag, contentDescription = null) },
                                onClick = { menuOpen = false; showReportForm = true }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.profile_copy_url)) },
                                leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    profile?.profileUrl?.let { clipboard.setText(AnnotatedString(it)) }
                                    scope.launch { snackbar.showSnackbar(urlCopied) }
                                }
                            )
                        }
                    }
                    }
                }
            }

            when {
                state.loading && profile == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TelefamColors.PrimaryRed)
                }
                state.notFound || (state.loadFailed && profile == null) -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Outlined.PersonOff, contentDescription = null,
                        modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f))
                    Spacer(Modifier.height(14.dp))
                    Text(
                        stringResource(if (state.notFound) Res.string.profile_not_found else Res.string.no_internet),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), textAlign = TextAlign.Center
                    )
                    if (state.loadFailed) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { viewModel.refresh() }) { Text(stringResource(Res.string.retry)) }
                    }
                }
                profile != null -> ProfileBody(
                    profile = profile,
                    viewModel = viewModel,
                    postsState = postsState,
                    gridState = gridState,
                    offline = !online || state.fromCache,
                    refreshing = state.loading || postsState.loadingInitial,
                    onRefresh = {
                        viewModel.refresh()
                        activeViewModel.refresh()
                    },
                    selectedTab = selectedTab,
                    onSelectTab = { selectedTab = it },
                    onAvatarClick = { showFullPhoto = true },
                    onStatClick = { kind -> onOpenList(userId, kind) },
                    onFollowClick = {
                        val action = FollowAction.of(profile.viewerFollowing, profile.viewerFollowedBy, profile.isFriend)
                        when (action) {
                            FollowAction.FOLLOW, FollowAction.FOLLOW_BACK -> viewModel.follow()
                            else -> showUnfollowConfirm = true
                        }
                    },
                    onMessageClick = { onMessage(userId, profile.displayName) },
                    onSubscribeClick = {
                        // Paid subscriptions: opens the creator's plan picker with
                        // server-verified Paystack/PayPal checkout.
                        onSubscribe()
                    },
                    onEditProfile = onEditProfile,
                    onOpenSettings = onOpenSettings,
                    onPostClick = { index -> watchStartIndex = index },
                    avatarAbsolute = { path -> if (path.startsWith("http")) path else com.telefam.data.api.ApiConfig.baseUrl.trimEnd('/') + path }
                )
            }

            SnackbarHost(snackbar, Modifier.align(Alignment.CenterHorizontally))
        }

        // --- Full-screen vertical video pager overlay (the shared FeedsPager) ---
        watchStartIndex?.let { start ->
            key(userId, selectedTab) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    FeedsPager(
                        viewModel = activeViewModel,
                        suggestionLabelFor = { null },
                        shareTargets = shareTargets,
                        onSendToChat = onSendToChat,
                        onEditPost = onEditPost,
                        onHashtagClick = { tag -> onOpenSearch("#$tag") },
                        onOpenComments = { post -> commentsPost = post },
                        onNotify = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
                        initialIndex = start
                    )
                    IconButton(
                        onClick = { watchStartIndex = null },
                        modifier = Modifier.statusBarsPadding().padding(8.dp).size(48.dp)
                            .clip(CircleShape).background(Color.Black.copy(alpha = 0.4f))
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White)
                    }
                }
            }
        }
    }

    // Comment sheet overlay
    commentsPost?.let { post -> commentsSheet(post) { commentsPost = null } }

    // --- Dialogs ---
    if (showFullPhoto && profile?.avatarUrl != null) {
        Dialog(onDismissRequest = { showFullPhoto = false }) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp))
                    .background(Color.Black).clickable { showFullPhoto = false },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = if (profile.avatarUrl.startsWith("http")) profile.avatarUrl
                    else com.telefam.data.api.ApiConfig.baseUrl.trimEnd('/') + profile.avatarUrl,
                    contentDescription = stringResource(Res.string.profile_view_photo),
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    if (showBlockConfirm && profile != null) {
        ConfirmDialog(
            title = stringResource(Res.string.profile_block_confirm_title, profile.displayName),
            message = stringResource(Res.string.profile_block_confirm_message),
            confirmLabel = stringResource(Res.string.profile_block),
            onConfirm = { showBlockConfirm = false; viewModel.block { } },
            onDismiss = { showBlockConfirm = false }
        )
    }

    if (showUnfollowConfirm && profile != null) {
        ConfirmDialog(
            title = stringResource(Res.string.profile_unfollow_confirm_title, profile.displayName),
            message = "",
            confirmLabel = stringResource(Res.string.unfollow),
            onConfirm = { showUnfollowConfirm = false; viewModel.unfollow() },
            onDismiss = { showUnfollowConfirm = false }
        )
    }

    if (showReportForm && profile != null) {
        var reason by remember { mutableStateOf(PROFILE_REPORT_REASONS.first()) }
        var details by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showReportForm = false },
            title = { Text(stringResource(Res.string.profile_report_title)) },
            text = {
                Column {
                    Text(stringResource(Res.string.profile_report_subtitle), fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Spacer(Modifier.height(10.dp))
                    PROFILE_REPORT_REASONS.forEach { r ->
                        Row(
                            Modifier.fillMaxWidth().clickable { reason = r }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = reason == r, onClick = { reason = r })
                            Text(r, fontSize = 14.sp)
                        }
                    }
                    OutlinedTextField(
                        value = details, onValueChange = { details = it.take(400) },
                        placeholder = { Text(stringResource(Res.string.feed_report_details_hint)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showReportForm = false
                    viewModel.report(reason, details) { ok ->
                        scope.launch { snackbar.showSnackbar(if (ok) reportThanks else comingSoon) }
                    }
                }) { Text(stringResource(Res.string.feed_report_submit), color = TelefamColors.PrimaryRed) }
            },
            dismissButton = {
                TextButton(onClick = { showReportForm = false }) { Text(stringResource(Res.string.cancel)) }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileBody(
    profile: ProfileDetailsDto,
    viewModel: ProfileViewModel,
    postsState: com.telefam.posts.FeedListState,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    offline: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    selectedTab: ProfilePostTab,
    onSelectTab: (ProfilePostTab) -> Unit,
    onAvatarClick: () -> Unit,
    onStatClick: (ConnectionListKind) -> Unit,
    onFollowClick: () -> Unit,
    onMessageClick: () -> Unit,
    onSubscribeClick: () -> Unit,
    onEditProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onPostClick: (Int) -> Unit,
    avatarAbsolute: (String) -> String
) {
    val isOwner = profile.isOwner

    Column(Modifier.fillMaxSize()) {
        if (offline) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.CloudOff, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.offline_cached), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        }

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(8.dp))
                    // --- Ringed avatar (tappable → full view) ---
                    Box(
                        Modifier.size(110.dp).clip(CircleShape)
                            .border(
                                4.dp,
                                Brush.linearGradient(listOf(TelefamColors.PrimaryRed, Color(0xFFFF8A65))),
                                CircleShape
                            )
                            .clickable(onClick = onAvatarClick)
                            .padding(5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier.fillMaxSize().clip(CircleShape)
                                .background(TelefamColors.PrimaryRed.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (profile.avatarUrl != null) {
                                AsyncImage(
                                    model = avatarAbsolute(profile.avatarUrl),
                                    contentDescription = stringResource(Res.string.profile_view_photo),
                                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Text(profile.displayName.take(1).uppercase(), fontSize = 36.sp,
                                    fontWeight = FontWeight.Bold, color = TelefamColors.PrimaryRed)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(profile.displayHandle, fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f))
                    Spacer(Modifier.height(14.dp))

                    // --- Stats row ---
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (profile.isOfficialAccount) {
                            // Followers-only profile: the official account follows nobody.
                            StatCell(formatCount(profile.followerCount), stringResource(Res.string.profile_followers),
                                Modifier.weight(1f).clickable { onStatClick(ConnectionListKind.FOLLOWERS) })
                        } else if (isOwner) {
                            StatCell(formatCount(profile.followerCount), stringResource(Res.string.profile_followers),
                                Modifier.weight(1f).clickable { onStatClick(ConnectionListKind.FOLLOWERS) })
                            Box(Modifier.width(1.dp).height(36.dp).background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f)))
                            StatCell(formatCount(profile.followingCount), stringResource(Res.string.profile_following),
                                Modifier.weight(1f).clickable { onStatClick(ConnectionListKind.FOLLOWING) })
                            Box(Modifier.width(1.dp).height(36.dp).background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f)))
                            StatCell(formatCount(profile.subscriberCount), "Subscribers",
                                Modifier.weight(1f).clickable { onStatClick(ConnectionListKind.SUBSCRIBERS) })
                        } else {
                            StatCell(formatCount(profile.followingCount), stringResource(Res.string.profile_following),
                                Modifier.weight(1f).clickable { onStatClick(ConnectionListKind.FOLLOWING) })
                            Box(Modifier.width(1.dp).height(36.dp).background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f)))
                            StatCell(formatCount(profile.followerCount), stringResource(Res.string.profile_followers),
                                Modifier.weight(1f).clickable { onStatClick(ConnectionListKind.FOLLOWERS) })
                            Box(Modifier.width(1.dp).height(36.dp).background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f)))
                            // Third stat is Friends — the value the backend already computes.
                            StatCell(formatCount(profile.friendsCount), stringResource(Res.string.profile_friends),
                                Modifier.weight(1f).clickable { onStatClick(ConnectionListKind.FRIENDS) })
                        }
                    }
                    Spacer(Modifier.height(14.dp))

                    if (profile.isBlockedByViewer) {
                        Text(stringResource(Res.string.profile_blocked_notice),
                            color = TelefamColors.PrimaryRed, fontSize = 14.sp)
                    } else if (isOwner) {
                        // --- Owner actions: Edit profile + Settings ---
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = onEditProfile,
                                colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            ) {
                                Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Edit profile", fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = onOpenSettings,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.heightIn(min = 48.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp)
                            ) {
                                Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                            }
                        }
                    } else if (profile.isOfficialAccount) {
                        // --- Official account: Follow only. No Message, no Subscribe, no calls. ---
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            val action = FollowAction.of(profile.viewerFollowing, profile.viewerFollowedBy, profile.isFriend)
                            val followLabel = when (action) {
                                FollowAction.FOLLOW -> stringResource(Res.string.follow)
                                FollowAction.FOLLOW_BACK -> stringResource(Res.string.follow_back)
                                FollowAction.FOLLOWING -> stringResource(Res.string.following_label)
                                FollowAction.FRIENDS -> stringResource(Res.string.following_label)
                            }
                            Button(
                                onClick = onFollowClick,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (action == FollowAction.FOLLOWING || action == FollowAction.FRIENDS)
                                        MaterialTheme.colorScheme.surfaceVariant else TelefamColors.PrimaryRed,
                                    contentColor = if (action == FollowAction.FOLLOWING || action == FollowAction.FRIENDS)
                                        MaterialTheme.colorScheme.onSurface else Color.White
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            ) {
                                if (action == FollowAction.FOLLOW || action == FollowAction.FOLLOW_BACK) {
                                    Icon(Icons.Outlined.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(followLabel, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        // --- Follow / Message / Subscribe ---
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            val action = FollowAction.of(profile.viewerFollowing, profile.viewerFollowedBy, profile.isFriend)
                            val followLabel = when (action) {
                                FollowAction.FOLLOW -> stringResource(Res.string.follow)
                                FollowAction.FOLLOW_BACK -> stringResource(Res.string.follow_back)
                                FollowAction.FOLLOWING -> stringResource(Res.string.following_label)
                                FollowAction.FRIENDS -> stringResource(Res.string.friends_label)
                            }
                            Button(
                                onClick = onFollowClick,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (action == FollowAction.FOLLOWING || action == FollowAction.FRIENDS)
                                        MaterialTheme.colorScheme.surfaceVariant else TelefamColors.PrimaryRed,
                                    contentColor = if (action == FollowAction.FOLLOWING || action == FollowAction.FRIENDS)
                                        MaterialTheme.colorScheme.onSurface else Color.White
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            ) {
                                if (action == FollowAction.FOLLOW || action == FollowAction.FOLLOW_BACK) {
                                    Icon(Icons.Outlined.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(followLabel, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = onMessageClick,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(Res.string.profile_message), fontWeight = FontWeight.SemiBold)
                            }
                            Button(
                                onClick = onSubscribeClick,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (profile.viewerSubscribed)
                                        MaterialTheme.colorScheme.surfaceVariant else TelefamColors.PrimaryRed,
                                    contentColor = if (profile.viewerSubscribed)
                                        MaterialTheme.colorScheme.onSurface else Color.White
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            ) {
                                Icon(Icons.Outlined.WorkspacePremium, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (profile.viewerSubscribed) "Subscribed" else stringResource(Res.string.profile_subscribe),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // --- Bio + location ---
                    if (!profile.bio.isNullOrBlank()) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            profile.bio, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 21.sp,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                    }
                    if (!profile.website.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Link, contentDescription = null,
                                tint = TelefamColors.PrimaryRed, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(profile.website.removePrefix("https://").removePrefix("http://"),
                                fontSize = 14.sp, color = TelefamColors.PrimaryRed)
                        }
                    }
                    if (!profile.locationName.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Place, contentDescription = null,
                                tint = TelefamColors.PrimaryRed, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(profile.locationName, fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f))
                        }
                    }

                    // --- Tabs: owner gets Posts / Reshared / Locked / Saved ---
                    Spacer(Modifier.height(16.dp))
                    if (isOwner) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            ProfilePostTab.entries.forEach { tab ->
                                val selected = selectedTab == tab
                                Column(
                                    Modifier.weight(1f).clickable { onSelectTab(tab) }.padding(vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        when (tab) {
                                            ProfilePostTab.POSTS -> Icons.Outlined.GridOn
                                            ProfilePostTab.RESHARED -> Icons.Outlined.Repeat
                                            ProfilePostTab.LOCKED -> Icons.Outlined.Lock
                                            ProfilePostTab.SAVED -> Icons.Outlined.Bookmark
                                        },
                                        contentDescription = tab.name.lowercase().replaceFirstChar { it.uppercase() },
                                        tint = if (selected) MaterialTheme.colorScheme.onBackground
                                        else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Box(
                                        Modifier.width(28.dp).height(3.dp)
                                            .background(
                                                if (selected) TelefamColors.PrimaryRed else Color.Transparent,
                                                RoundedCornerShape(2.dp)
                                            )
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
                    } else {
                        Column(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.GridOn, contentDescription = null,
                                        modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onBackground)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(Res.string.profile_posts), fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Box(Modifier.width(90.dp).height(3.dp).padding(start = 16.dp)
                                .background(TelefamColors.PrimaryRed, RoundedCornerShape(2.dp)))
                            HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
                        }
                    }
                }
            }

            if (postsState.loadFailed && postsState.items.isEmpty()) {
                // Distinct failure state: not the same as an empty profile.
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                    Column(
                        Modifier.fillMaxWidth().padding(40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Outlined.CloudOff, contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f))
                        Spacer(Modifier.height(10.dp))
                        Text(stringResource(Res.string.profile_posts_load_failed),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = onRefresh) { Text(stringResource(Res.string.retry)) }
                    }
                }
            } else if (postsState.items.isEmpty() && !postsState.loadingInitial) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(Res.string.profile_no_posts),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                    }
                }
            }

            itemsIndexed(postsState.items, key = { _, p -> p.postId }) { index, post ->
                PostGridCell(
                    post = post,
                    badge = when (selectedTab) {
                        ProfilePostTab.LOCKED -> GridBadge.LOCKED
                        ProfilePostTab.RESHARED -> GridBadge.RESHARED
                        ProfilePostTab.SAVED -> GridBadge.SAVED
                        ProfilePostTab.POSTS -> null
                    },
                    onClick = { onPostClick(index) }
                )
            }

            if (postsState.loadingMore) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = TelefamColors.PrimaryRed,
                            modifier = Modifier.size(26.dp), strokeWidth = 3.dp)
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun StatCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f))
    }
}

private enum class GridBadge { LOCKED, RESHARED, SAVED }

@Composable
private fun PostGridCell(post: FeedPostDto, badge: GridBadge?, onClick: () -> Unit) {
    Box(
        Modifier.aspectRatio(3f / 4f).clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
    ) {
        val thumb = post.thumbnailUrl?.let {
            if (it.startsWith("http")) it else com.telefam.data.api.ApiConfig.baseUrl.trimEnd('/') + it
        }
        if (thumb != null) {
            AsyncImage(
                model = thumb, contentDescription = post.description,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.PlayCircle, contentDescription = null,
                    tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(36.dp))
            }
        }
        if (badge != null) {
            val (icon, label) = when (badge) {
                GridBadge.LOCKED -> Icons.Outlined.Lock to "Locked"
                GridBadge.RESHARED -> Icons.Outlined.Repeat to "Reshared"
                GridBadge.SAVED -> Icons.Outlined.Bookmark to "Saved"
            }
            Row(
                Modifier.align(Alignment.TopStart).padding(6.dp)
                    .clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp))
                Spacer(Modifier.width(3.dp))
                Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        // View count overlay — bottom-left, like the reference.
        Row(
            Modifier.align(Alignment.BottomStart)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
                .fillMaxWidth().padding(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(3.dp))
            Text(formatCount(post.viewCount), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
