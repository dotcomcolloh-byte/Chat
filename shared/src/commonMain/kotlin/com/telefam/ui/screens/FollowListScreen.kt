package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.connect.ConnectionListKind
import com.telefam.connect.ProfileViewModel
import com.telefam.shared.generated.resources.Res
import com.telefam.shared.generated.resources.*
import com.telefam.ui.theme.TelefamColors
import org.jetbrains.compose.resources.stringResource

/**
 * Tappable stats destination: Followers / Following / Friends lists with tabs.
 * Rows navigate to the profile; the trailing button carries the real action state
 * (Follow / Follow back / Following / Friends) and toggles it optimistically.
 */
@Composable
fun FollowListScreen(
    userId: String,
    initialKind: ConnectionListKind,
    viewModel: ProfileViewModel,
    onOpenProfile: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selected by remember { mutableStateOf(initialKind) }
    val lists by viewModel.lists.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(userId) { viewModel.load(userId) }
    LaunchedEffect(selected) { viewModel.loadList(selected) }
    LaunchedEffect(listState, selected) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { idx ->
                val s = lists[selected]
                if (s != null && idx >= s.items.size - 4) viewModel.loadMoreList(selected)
            }
    }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            Text(
                stringResource(Res.string.profile_friends),
                fontSize = 20.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        TabRow(
            selectedTabIndex = selected.ordinal,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = TelefamColors.PrimaryRed
        ) {
            ConnectionListKind.entries.forEach { kind ->
                Tab(
                    selected = selected == kind,
                    onClick = { selected = kind },
                    text = {
                        Text(
                            when (kind) {
                                ConnectionListKind.FOLLOWERS -> stringResource(Res.string.profile_followers)
                                ConnectionListKind.FOLLOWING -> stringResource(Res.string.profile_following)
                                ConnectionListKind.FRIENDS -> stringResource(Res.string.profile_friends)
                                ConnectionListKind.SUBSCRIBERS -> "Subscribers"
                            },
                            fontWeight = if (selected == kind) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        val s = lists[selected]
        when {
            s == null || (s.loading && s.items.isEmpty()) -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TelefamColors.PrimaryRed)
            }
            s.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.list_empty),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
            }
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(s.items, key = { _, u -> u.userId }) { _, user ->
                    ConnectUserRow(
                        user = user,
                        avatarUrl = user.avatarUrl?.let {
                            if (it.startsWith("http")) it else com.telefam.data.api.ApiConfig.baseUrl.trimEnd('/') + it
                        },
                        onClick = { onOpenProfile(user.userId) },
                        onFollowAction = { viewModel.toggleFollowInList(selected, user) }
                    )
                    HorizontalDivider(
                        Modifier.padding(start = 86.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                    )
                }
                if (s.loadingMore) {
                    item {
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
