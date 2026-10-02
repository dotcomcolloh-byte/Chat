package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.telefam.connect.ConnectUserDto
import com.telefam.connect.ContactsViewModel
import com.telefam.connect.FollowAction
import com.telefam.shared.generated.resources.Res
import com.telefam.shared.generated.resources.*
import com.telefam.ui.theme.TelefamColors
import org.jetbrains.compose.resources.stringResource

/**
 * Contacts / Discover screen — matches the reference: red Telefam header with a
 * search action, rounded white content sheet, search bar with a filter button,
 * "People you may know" suggestions with Follow buttons, pull-to-refresh,
 * infinite scroll, related-search chips, and an offline state with cached content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    viewModel: ContactsViewModel,
    onOpenProfile: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val online by viewModel.online.collectAsState()
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    var query by remember { mutableStateOf("") }
    var filterReason by remember { mutableStateOf<String?>(null) }
    var filterMenuOpen by remember { mutableStateOf(false) }
    val rateLimitedText = stringResource(Res.string.follow_rate_limited)

    LaunchedEffect(Unit) {
        viewModel.loadInitial()
        viewModel.syncDeviceContacts()
    }
    LaunchedEffect(state.rateLimitedUntilMs) {
        if (state.rateLimitedUntilMs > 0) snackbar.showSnackbar(rateLimitedText)
    }
    // Infinite scroll trigger.
    LaunchedEffect(listState, state.items.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { viewModel.ensureLoadedAhead(it) }
    }

    val visibleItems = remember(state.items, filterReason) {
        if (filterReason == null) state.items else state.items.filter { it.suggestionReason == filterReason }
    }
    val isSearching = query.trim().length >= 2

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = TelefamColors.PrimaryRed,
        modifier = modifier
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // --- Red header: wordmark + search affordance (matches reference) ---
            Box(
                Modifier.fillMaxWidth().background(TelefamColors.PrimaryRed)
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Text(
                    stringResource(Res.string.app_name),
                    fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TelefamColors.White
                )
                Icon(
                    Icons.Filled.Search, contentDescription = stringResource(Res.string.contacts_search_hint),
                    tint = TelefamColors.White, modifier = Modifier.align(Alignment.CenterEnd).size(28.dp)
                )
            }

            Column(
                Modifier.fillMaxSize()
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // --- Search bar + filter ---
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = query,
                        onValueChange = {
                            query = it
                            if (it.trim().length >= 2) viewModel.search(it) else viewModel.switchToSuggestions()
                        },
                        placeholder = { Text(stringResource(Res.string.contacts_search_hint)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                        ),
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Box {
                        IconButton(
                            onClick = { filterMenuOpen = true },
                            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Icon(Icons.Filled.Tune, contentDescription = stringResource(Res.string.contacts_filter))
                        }
                        DropdownMenu(expanded = filterMenuOpen, onDismissRequest = { filterMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.contacts_suggestions_title)) },
                                onClick = { filterReason = null; filterMenuOpen = false }
                            )
                            listOf(
                                "FROM_CONTACTS" to Res.string.contacts_from_contacts,
                                "FRIEND_OF_FRIEND" to Res.string.contacts_friend_of_friend,
                                "NEARBY" to Res.string.contacts_nearby,
                                "NEW_USER" to Res.string.contacts_new_user
                            ).forEach { (key, label) ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(label)) },
                                    onClick = { filterReason = key; filterMenuOpen = false }
                                )
                            }
                        }
                    }
                }

                // --- Offline notice (cached content stays visible underneath) ---
                if (!online || state.fromCache) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.CloudOff, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(if (state.items.isEmpty()) Res.string.no_internet else Res.string.offline_cached),
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // --- Related search chips ---
                if (state.relatedTerms.isNotEmpty() && isSearching) {
                    Text(
                        stringResource(Res.string.contacts_related_title),
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(state.relatedTerms) { _, term ->
                            AssistChip(onClick = {
                                val clean = term.removePrefix("@").removePrefix("#")
                                query = clean
                                viewModel.search(clean)
                            }, label = { Text(term) })
                        }
                    }
                }

                Text(
                    stringResource(if (isSearching) Res.string.contacts_search_results_title else Res.string.contacts_suggestions_title),
                    fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )

                PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = { viewModel.refresh() },
                    modifier = Modifier.fillMaxSize()
                ) {
                    when {
                        state.loadFailed -> Column(
                            Modifier.fillMaxSize().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Outlined.CloudOff, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                                modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(Res.string.no_internet),
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = { viewModel.refresh() }) {
                                Text(stringResource(Res.string.retry))
                            }
                        }
                        visibleItems.isEmpty() && !state.refreshing -> Box(
                            Modifier.fillMaxSize(), contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(if (isSearching) Res.string.contacts_search_empty else Res.string.contacts_empty),
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                                modifier = Modifier.padding(32.dp)
                            )
                        }
                        else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(visibleItems, key = { _, u -> u.userId }) { _, user ->
                                ConnectUserRow(
                                    user = user,
                                    avatarUrl = user.avatarUrl?.let { viewModelAbsoluteUrl(it) },
                                    onClick = { onOpenProfile(user.userId) },
                                    onFollowAction = {
                                        when (FollowAction.of(user.viewerFollowing, user.viewerFollowedBy, user.isFriend)) {
                                            FollowAction.FOLLOW, FollowAction.FOLLOW_BACK -> viewModel.follow(user)
                                            FollowAction.FOLLOWING, FollowAction.FRIENDS -> viewModel.unfollow(user)
                                        }
                                    }
                                )
                                HorizontalDivider(
                                    Modifier.padding(start = 86.dp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                                )
                            }
                            if (state.loadingMore) {
                                item {
                                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(
                                            color = TelefamColors.PrimaryRed,
                                            modifier = Modifier.size(28.dp), strokeWidth = 3.dp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun viewModelAbsoluteUrl(path: String): String =
    if (path.startsWith("http")) path
    else com.telefam.data.api.ApiConfig.baseUrl.trimEnd('/') + path

@Composable
fun ConnectUserRow(
    user: ConnectUserDto,
    avatarUrl: String?,
    onClick: () -> Unit,
    onFollowAction: () -> Unit
) {
    val action = FollowAction.of(user.viewerFollowing, user.viewerFollowedBy, user.isFriend)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar — tap opens the profile, same as the name.
        Box(
            Modifier.size(58.dp).clip(CircleShape)
                .background(TelefamColors.PrimaryRed.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            if (avatarUrl != null) {
                AsyncImage(
                    model = avatarUrl, contentDescription = user.displayName,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    user.displayName.take(1).uppercase(),
                    fontWeight = FontWeight.Bold, fontSize = 22.sp, color = TelefamColors.PrimaryRed
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    user.displayName, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (user.isVerified) {
                    Spacer(Modifier.width(5.dp))
                    com.telefam.ui.components.VerifiedBadge(size = 15.dp)
                }
            }
            if (user.displayHandle.isNotBlank()) {
                Text(
                    user.displayHandle, fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), maxLines = 1
                )
            }
            val subtitle = when {
                user.mutualConnections > 0 -> stringResource(Res.string.contacts_mutual, user.mutualConnections)
                user.suggestionReason == "FROM_CONTACTS" -> stringResource(Res.string.contacts_from_contacts)
                user.suggestionReason == "NEARBY" && user.distanceKm != null ->
                    "${stringResource(Res.string.contacts_nearby)} · ${user.distanceKm.toInt()} km"
                user.suggestionReason == "NEARBY" -> stringResource(Res.string.contacts_nearby)
                user.isNewUser -> stringResource(Res.string.contacts_new_user)
                user.suggestionReason == "FRIEND_OF_FRIEND" -> stringResource(Res.string.contacts_friend_of_friend)
                else -> null
            }
            if (subtitle != null) {
                Text(
                    subtitle, fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f), maxLines = 1
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        FollowActionButton(action, onFollowAction)
    }
}

@Composable
fun FollowActionButton(action: FollowAction, onClick: () -> Unit) {
    val (label, filled) = when (action) {
        FollowAction.FOLLOW -> stringResource(Res.string.follow) to true
        FollowAction.FOLLOW_BACK -> stringResource(Res.string.follow_back) to true
        FollowAction.FOLLOWING -> stringResource(Res.string.following_label) to false
        FollowAction.FRIENDS -> stringResource(Res.string.friends_label) to false
    }
    if (filled) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
            modifier = Modifier.heightIn(min = 44.dp)
        ) { Text(label, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
    } else {
        OutlinedButton(
            onClick = onClick,
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.heightIn(min = 44.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TelefamColors.PrimaryRed),
            border = androidx.compose.foundation.BorderStroke(1.dp, TelefamColors.PrimaryRed.copy(alpha = 0.5f))
        ) { Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
    }
}
