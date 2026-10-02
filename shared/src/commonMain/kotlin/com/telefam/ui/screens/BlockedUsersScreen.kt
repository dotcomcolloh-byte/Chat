package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.telefam.shared.generated.resources.Res
import com.telefam.shared.generated.resources.blocked_empty
import com.telefam.shared.generated.resources.blocked_title
import com.telefam.shared.generated.resources.unblock
import com.telefam.ui.components.ConfirmDialog
import com.telefam.ui.components.SettingsTopBar
import com.telefam.ui.theme.TelefamColors
import org.jetbrains.compose.resources.stringResource

data class BlockedUserItem(
    val userId: String,
    val name: String,
    val username: String? = null,
    val avatarUrl: String? = null,
    val isVerified: Boolean = false,
)

@Composable
fun BlockedUsersScreen(
    users: List<BlockedUserItem>,
    onBackClick: () -> Unit,
    onUnblock: (String) -> Unit,
    onOpenProfile: (String) -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var confirmTarget by remember { mutableStateOf<BlockedUserItem?>(null) }
    val filtered = remember(users, query) {
        if (query.isBlank()) users
        else users.filter {
            it.name.contains(query.trim(), ignoreCase = true) ||
                (it.username?.contains(query.trim(), ignoreCase = true) == true)
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SettingsTopBar(title = stringResource(Res.string.blocked_title), onBackClick = onBackClick)

        if (users.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.blocked_empty), color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
            }
        } else {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search blocked accounts") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true, shape = RoundedCornerShape(24.dp),
            )
            val unblockLabel = stringResource(Res.string.unblock)
            LazyColumn {
                items(filtered, key = { it.userId }) { user ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenProfile(user.userId) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier.size(48.dp).clip(CircleShape)
                                .background(TelefamColors.PrimaryRed.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (user.avatarUrl != null) {
                                AsyncImage(
                                    model = if (user.avatarUrl.startsWith("http")) user.avatarUrl
                                    else com.telefam.data.api.ApiConfig.baseUrl.trimEnd('/') + user.avatarUrl,
                                    contentDescription = user.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Text(user.name.take(1).uppercase(), fontWeight = FontWeight.Bold,
                                    color = TelefamColors.PrimaryRed, fontSize = 18.sp)
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(user.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                if (user.isVerified) {
                                    Spacer(Modifier.width(5.dp))
                                    com.telefam.ui.components.VerifiedBadge(size = 14.dp)
                                }
                            }
                            if (!user.username.isNullOrBlank()) {
                                Text("@${user.username}", fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f))
                            }
                        }
                        OutlinedButton(
                            onClick = { confirmTarget = user },
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                        ) { Text(unblockLabel, fontSize = 13.sp) }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                }
                if (filtered.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                            Text("No results for \"$query\"",
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }

    confirmTarget?.let { target ->
        ConfirmDialog(
            title = "Unblock @${target.username ?: target.name}?",
            message = "They will be able to see your posts and message you again.",
            confirmLabel = stringResource(Res.string.unblock),
            onConfirm = {
                confirmTarget = null
                onUnblock(target.userId) // backend removal happens immediately (queued when offline)
            },
            onDismiss = { confirmTarget = null },
        )
    }
}
