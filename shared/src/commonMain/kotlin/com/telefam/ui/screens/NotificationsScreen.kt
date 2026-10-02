package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.data.api.NotificationDto
import com.telefam.notifications.NotificationFilter
import com.telefam.notifications.NotificationsViewModel
import com.telefam.ui.components.*
import com.telefam.ui.theme.TelefamColors
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Notification centre — matches the reference screen: title, subtitle tabs
 * (All / Comments / Mentions / Subscriptions), rows with an actor avatar or a
 * lucide-style type icon, bold action line + subtitle, relative time, and a
 * per-row 3-dot menu whose only action is Delete.
 *
 * Every row is clickable and redirects to the origin:
 *   POST / COMMENT -> the post in Feeds, PROFILE -> the actor's profile,
 *   WALLET -> the wallet, SUBSCRIPTIONS -> the subscriptions screen.
 * NEW_FOLLOWER rows can also carry a "Follow back" button.
 */
@Composable
fun NotificationsScreen(
    viewModel: NotificationsViewModel,
    onBack: () -> Unit = {},
    onOpenPost: (postId: String) -> Unit = {},
    onOpenProfile: (userId: String) -> Unit = {},
    onOpenWallet: () -> Unit = {},
    onOpenSubscriptions: () -> Unit = {},
    onFollowBack: (userId: String) -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { viewModel.refresh() }

    // Infinite scroll: load the next page near the end of the list.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .map { it ?: 0 }
            .distinctUntilChanged()
            .filter { it >= state.items.size - 4 }
            .collectLatest { viewModel.loadMore() }
    }

    fun openOrigin(n: NotificationDto) {
        viewModel.markRead(n.id)
        when (n.targetType) {
            "POST", "COMMENT" -> n.targetId?.let(onOpenPost)
            "PROFILE" -> (n.targetId ?: n.actorId)?.let(onOpenProfile)
            "WALLET" -> onOpenWallet()
            "SUBSCRIPTIONS" -> onOpenSubscriptions()
            else -> Unit
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // --- Header ---
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 20.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
            }
            Text(
                "Notifications", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.weight(1f))
            if (state.unreadCount > 0) {
                TextButton(onClick = { viewModel.markAllRead() }) {
                    Text("Mark all read", color = TelefamColors.PrimaryRed, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // --- Subtitle tabs: All / Comments / Mentions / Subscriptions ---
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            NotificationFilter.entries.forEach { f ->
                val selected = f == filter
                Box(
                    Modifier.clip(RoundedCornerShape(22.dp))
                        .background(
                            if (selected) TelefamColors.PrimaryRed
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable { viewModel.setFilter(f) }
                        .padding(horizontal = 18.dp, vertical = 9.dp)
                ) {
                    Text(
                        f.label,
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) TelefamColors.White
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }

        // --- List ---
        when {
            state.refreshing && state.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TelefamColors.PrimaryRed)
            }
            state.loadFailed -> Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                LucideBell(size = 44.dp, tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f))
                Spacer(Modifier.height(14.dp))
                Text("Couldn't load notifications", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = { viewModel.refresh() }) { Text("Retry", color = TelefamColors.PrimaryRed) }
            }
            state.items.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                LucideBell(size = 44.dp, tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f))
                Spacer(Modifier.height(14.dp))
                Text(
                    "No notifications yet",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
            else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(state.items, key = { it.id }) { n ->
                    NotificationRow(
                        n,
                        onClick = { openOrigin(n) },
                        onDelete = { viewModel.delete(n.id) },
                        onFollowBack = { n.actorId?.let(onFollowBack) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
                if (state.loadingMore) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = TelefamColors.PrimaryRed, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(
    n: NotificationDto,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onFollowBack: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .background(
                if (n.read) MaterialTheme.colorScheme.surface
                else TelefamColors.PrimaryRed.copy(alpha = 0.05f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Type icon (lucide-style) in a soft red circle.
        Box(
            Modifier.size(44.dp).clip(CircleShape)
                .background(TelefamColors.PrimaryRed.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            NotificationTypeIcon(n.type)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val actor = n.actorName
                if (actor != null) {
                    Text(
                        actor, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        n.body, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                } else {
                    Text(
                        n.title, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (n.actorName != null || n.body.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    if (n.actorName != null) n.title else n.body,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                formatNotificationTime(n.createdAt),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
            if (n.type == "NEW_FOLLOWER" && n.canFollowBack) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onFollowBack,
                    colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    modifier = Modifier.heightIn(min = 36.dp)
                ) {
                    Text("Follow back", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TelefamColors.White)
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        // Per-row 3-dot: delete this notification.
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.MoreVert, contentDescription = "Notification options",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp)
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LucideTrash(size = 18.dp, tint = TelefamColors.PrimaryRed)
                            Spacer(Modifier.width(10.dp))
                            Text("Delete", color = TelefamColors.PrimaryRed)
                        }
                    },
                    onClick = { menuOpen = false; onDelete() }
                )
            }
        }
    }
}

@Composable
private fun NotificationTypeIcon(type: String) {
    val red = TelefamColors.PrimaryRed
    when (type) {
        "LIKE" -> LucideHeart(size = 22.dp, tint = red)
        "COMMENT", "REPLY" -> LucideMessageCircle(size = 22.dp, tint = red)
        "MENTION", "TAG" -> LucideAtSign(size = 22.dp, tint = red)
        "NEW_FOLLOWER" -> LucideUserPlus(size = 22.dp, tint = red)
        "SUBSCRIPTION" -> LucideBadgeCheck(size = 22.dp, tint = red)
        "PAYMENT_SUCCESS", "WITHDRAWAL_SUCCESS" -> LucideCreditCard(size = 22.dp, tint = red)
        "NEW_LOGIN" -> LucideLogIn(size = 22.dp, tint = red)
        "REMOVED_POST" -> LucideShieldAlert(size = 22.dp, tint = red)
        else -> LucideBell(size = 22.dp, tint = red)
    }
}

private fun formatNotificationTime(iso: String): String {
    val epoch = runCatching {
        java.time.LocalDateTime.parse(iso).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrDefault(0L)
    if (epoch <= 0) return ""
    val diff = System.currentTimeMillis() - epoch
    val minutes = diff / 60_000
    val hours = diff / 3_600_000
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "$minutes minutes ago"
        hours < 24 -> if (hours == 1L) "1 hour ago" else "$hours hours ago"
        hours < 48 -> "Yesterday"
        else -> SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(epoch))
    }
}
