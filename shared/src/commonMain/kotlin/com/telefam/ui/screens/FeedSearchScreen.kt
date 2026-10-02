package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.telefam.data.api.ApiConfig
import com.telefam.posts.FeedSource
import com.telefam.posts.FeedViewModel
import com.telefam.shared.generated.resources.Res
import com.telefam.shared.generated.resources.*
import com.telefam.ui.components.feed.FeedsPager
import com.telefam.ui.components.feed.ShareTarget
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private val FeedRed = Color(0xFFD32323)

/**
 * Feed search — supports hashtags (type "#nightvibes" or just "nightvibes"), free text
 * over descriptions, and everything attached to a post (tags/song are searchable via
 * the description text server-side). Results render as a thumbnail grid; tapping a
 * tile opens the SAME reusable FeedsPager as an in-place overlay at that index —
 * the user is NOT redirected to the feeds screen, and paging loads more results
 * from the same search query.
 */
@Composable
fun FeedSearchScreen(
    viewModelFactory: () -> FeedViewModel,
    initialQuery: String = "",
    shareTargets: List<ShareTarget>,
    onSendToChat: (com.telefam.posts.FeedPostDto, ShareTarget) -> Unit,
    onEditPost: (com.telefam.posts.FeedPostDto) -> Unit,
    onBack: () -> Unit,
    onOpenProfile: (userId: String) -> Unit = {},
    /** Comment sheet host: rendered as an overlay for the tapped post. */
    commentsSheet: @Composable (com.telefam.posts.FeedPostDto, () -> Unit) -> Unit = { _, _ -> }
) {
    var query by remember { mutableStateOf(initialQuery) }
    var submitted by remember { mutableStateOf(initialQuery) }
    var watchingIndex by remember { mutableStateOf<Int?>(null) }
    var commentsPost by remember { mutableStateOf<com.telefam.posts.FeedPostDto?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val comingSoon = stringResource(Res.string.coming_soon)

    val viewModel = remember { viewModelFactory() }
    val state by viewModel.state.collectAsState()

    // Debounced live search — 400ms after typing stops.
    var searchJob by remember { mutableStateOf<Job?>(null) }
    fun runSearch(q: String) {
        searchJob?.cancel()
        val normalized = q.trim()
        if (normalized.length < 2) { searchJob?.cancel(); submitted = ""; return }
        searchJob = scope.launch {
            delay(400)
            submitted = normalized
            viewModel.load(FeedSource.Search(normalized))
        }
    }
    LaunchedEffect(Unit) { if (initialQuery.isNotBlank()) viewModel.load(FeedSource.Search(initialQuery)) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // --- Search bar ---
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.browser_back),
                    tint = Color.White, modifier = Modifier.size(26.dp).clickable(onClick = onBack)
                )
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; runSearch(it) },
                    placeholder = { Text(stringResource(Res.string.feed_search_hint), color = Color.White.copy(alpha = 0.5f)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.6f)) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.cancel),
                                tint = Color.White, modifier = Modifier.clickable { query = ""; submitted = "" })
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(26.dp),
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedBorderColor = FeedRed, unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                        cursorColor = FeedRed
                    )
                )
            }

            // --- Hashtag quick filters for terms the user has actually engaged with ---
            val knownTags = state.items.flatMap { it.hashtags }.distinct().take(8)
            if (knownTags.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    knownTags.take(4).forEach { tag ->
                        Text(
                            "#$tag", color = FeedRed, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(14.dp))
                                .background(FeedRed.copy(alpha = 0.15f))
                                .clickable { query = "#$tag"; runSearch("#$tag") }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // --- Results grid ---
            when {
                submitted.isBlank() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(Res.string.feed_search_empty), color = Color.White.copy(alpha = 0.6f))
                }
                state.loadingInitial -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = FeedRed)
                }
                state.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(Res.string.feed_search_no_results), color = Color.White.copy(alpha = 0.6f))
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    itemsIndexed(state.items, key = { _, p -> p.postId }) { index, post ->
                        Box(
                            Modifier.aspectRatio(9f / 16f).clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF1C1C1C))
                                .clickable { watchingIndex = index }
                        ) {
                            val thumb = post.thumbnailAbsoluteUrl(ApiConfig.baseUrl)
                            if (thumb != null) {
                                AsyncImage(model = thumb, contentDescription = post.description,
                                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            }
                            Text(
                                "▶ ${post.viewCount}",
                                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                            )
                        }
                    }
                    // Infinite scroll inside the grid as well.
                    item {
                        LaunchedEffect(state.items.size) { viewModel.ensureLoadedAhead(state.items.size - 1) }
                        if (state.loadingMore) {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = FeedRed, modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        }

        // --- In-place watch overlay: NOT a navigation to FeedScreen.
        //     A dedicated pager VM pre-positioned at the tapped index keeps the grid's
        //     paging VM untouched, so closing the overlay restores scroll state exactly. ---
        watchingIndex?.let { index ->
            val watchViewModel = remember(index) {
                viewModelFactory().also { vm ->
                    vm.source = FeedSource.Search(submitted)
                    vm.adopt(state.items, state.nextCursor)
                }
            }
            key(index) {
                FeedsPager(
                    viewModel = watchViewModel,
                    suggestionLabelFor = { null },
                    shareTargets = shareTargets,
                    onSendToChat = onSendToChat,
                    onEditPost = onEditPost,
                    onHashtagClick = { tag -> query = "#$tag"; runSearch("#$tag"); watchingIndex = null },
                    onOpenComments = { post -> commentsPost = post },
                    onNotify = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
                    initialIndex = index,
                    onOwnerClick = { post -> onOpenProfile(post.ownerId) }
                )
            }
            Icon(
                Icons.Filled.Close, contentDescription = stringResource(Res.string.browser_close),
                tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp)
                    .size(40.dp).clip(RoundedCornerShape(20.dp)).background(Color(0x66000000))
                    .clickable { watchingIndex = null }.padding(8.dp)
            )
        }

        commentsPost?.let { post -> commentsSheet(post) { commentsPost = null } }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}
