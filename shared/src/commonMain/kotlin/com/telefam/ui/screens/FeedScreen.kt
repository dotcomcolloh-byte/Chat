package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.posts.FeedSource
import com.telefam.posts.FeedTab
import com.telefam.posts.FeedViewModel
import com.telefam.shared.generated.resources.Res
import com.telefam.shared.generated.resources.*
import com.telefam.ui.components.feed.FeedsPager
import com.telefam.ui.components.feed.ShareTarget
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private val FeedRed = Color(0xFFD32323)

/**
 * Feeds screen — matches the reference: dark always, tab row (For You / Friends /
 * Following, red dot on Following), "New creators" pill, search icon top-right, and
 * a full-bleed vertical video pager underneath.
 *
 * Each tab owns its own FeedViewModel (independent pagination + instant switching);
 * the view models are provided by a factory so the host platform can share the same
 * offline repository and outbox scheduler across all of them.
 */
@Composable
fun FeedScreen(
    viewModelFactory: () -> FeedViewModel,
    shareTargets: List<ShareTarget>,
    onSendToChat: (com.telefam.posts.FeedPostDto, ShareTarget) -> Unit,
    onEditPost: (com.telefam.posts.FeedPostDto) -> Unit,
    onOpenSearch: (initialQuery: String) -> Unit,
    onBack: () -> Unit,
    /** Tapping a creator's avatar or handle anywhere in the pager opens their profile. */
    onOpenProfile: (userId: String) -> Unit = {},
    /** Comment sheet host: rendered as an overlay for the tapped post. */
    commentsSheet: @Composable (com.telefam.posts.FeedPostDto, () -> Unit) -> Unit = { _, _ -> },
    /** Sponsored boost items: impression when settled, CTA taps routed to the host. */
    onSponsoredImpression: (com.telefam.posts.FeedPostDto) -> Unit = {},
    onSponsoredAction: (com.telefam.posts.FeedPostDto) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(FeedTab.FOR_YOU) }
    var commentsPost by remember { mutableStateOf<com.telefam.posts.FeedPostDto?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val comingSoon = stringResource(Res.string.coming_soon)
    val noInternetText = stringResource(Res.string.feed_no_internet_banner)

    // One ViewModel per tab — feeds keep their own scroll position, cursor and cache.
    val viewModels = remember { mutableMapOf<FeedTab, FeedViewModel>() }
    fun vmFor(tab: FeedTab): FeedViewModel = viewModels.getOrPut(tab) {
        viewModelFactory().also { it.load(FeedSource.Tab(tab)) }
    }
    val activeViewModel = remember(selectedTab) { vmFor(selectedTab) }
    val online by activeViewModel.online.collectAsState()

    Box(modifier.fillMaxSize().background(Color.Black)) {
        key(selectedTab) {
            FeedsPager(
                viewModel = activeViewModel,
                suggestionLabelFor = { post ->
                    when {
                        selectedTab == FeedTab.NEW_CREATORS -> "New creator"
                        !post.viewerFollowing && !post.isOwner -> "You might like this"
                        else -> null
                    }
                },
                shareTargets = shareTargets,
                onSendToChat = onSendToChat,
                onEditPost = onEditPost,
                onHashtagClick = { tag -> onOpenSearch("#$tag") },
                onOpenComments = { post -> commentsPost = post },
                onNotify = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
                onOwnerClick = { post -> onOpenProfile(post.ownerId) },
                onSponsoredImpression = onSponsoredImpression,
                onSponsoredAction = onSponsoredAction
            )
        }

        // --- Top chrome: tabs + search, floating over the video like the reference ---
        Column(
            Modifier.fillMaxWidth()
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
                .statusBarsPadding()
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    listOf(FeedTab.FOR_YOU, FeedTab.FRIENDS, FeedTab.FOLLOWING).forEach { tab ->
                        val selected = tab == selectedTab
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { selectedTab = tab }.padding(vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    tab.label,
                                    color = if (selected) Color.White else Color.White.copy(alpha = 0.6f),
                                    fontSize = 17.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                                if (tab == FeedTab.FOLLOWING) {
                                    Spacer(Modifier.width(4.dp))
                                    Box(Modifier.size(6.dp).clip(CircleShape).background(FeedRed))
                                }
                            }
                            if (selected) {
                                Spacer(Modifier.height(3.dp))
                                Box(Modifier.width(28.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(FeedRed))
                            }
                        }
                    }
                }
                Icon(
                    Icons.Outlined.Search, contentDescription = stringResource(Res.string.feed_search),
                    tint = Color.White,
                    modifier = Modifier.size(28.dp).clickable { onOpenSearch("") }.padding(2.dp)
                )
            }

            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(
                    Modifier.clip(RoundedCornerShape(20.dp))
                        .background(if (selectedTab == FeedTab.NEW_CREATORS) FeedRed.copy(alpha = 0.35f) else Color(0x26FFFFFF))
                        .clickable { selectedTab = FeedTab.NEW_CREATORS }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Group, contentDescription = null, tint = FeedRed, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.feed_new_creators), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }

        // --- No internet: transient snackbar instead of a pinned banner ---
        LaunchedEffect(online) {
            if (!online) snackbar.showSnackbar(noInternetText)
        }

        commentsPost?.let { post -> commentsSheet(post) { commentsPost = null } }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp))
    }
}
