package com.telefam.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Poll
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import com.telefam.chat.ConversationSummary
import com.telefam.data.AuthSession
import com.telefam.shared.generated.resources.*
import com.telefam.ui.components.*
import com.telefam.ui.theme.TelefamColors
import org.jetbrains.compose.resources.stringResource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Conversation list with production-grade interactions:
 *  - Tap = open chat; tap avatar = open profile.
 *  - Long-press = multi-select mode with a contextual top bar
 *    (Archive / Mark as read / Delete), like mainstream messengers.
 *  - The search field filters the list live (name + preview) instead of being a dead shortcut.
 *  - Rows show a timestamp and an unread-count badge; drafts stay visibly marked.
 */
@Composable
fun HomeScreen(
    profileAvatarUrl: String?,
    conversations: List<ConversationSummary> = emptyList(),
    /** Pinned system conversations (Telefam Official + Notifications) from the backend. */
    systemInbox: com.telefam.data.api.SystemInboxDto? = null,
    onOpenOfficialChat: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    onOpenChat: (peerId: String, displayName: String) -> Unit = { _, _ -> },
    onNewChat: () -> Unit = {},
    onOpenPost: () -> Unit = {},
    onOpenPrivacy: () -> Unit = {},
    onOpenMessageRequests: () -> Unit = {},
    onOpenArchived: () -> Unit = {},
    onOpenBlocked: () -> Unit = {},
    onOpenFeeds: () -> Unit = {},
    /** Contacts bottom-nav tab → Discover / "People you may know". */
    onOpenContacts: () -> Unit = {},
    /** Overflow menu → Linked devices pairing screen. */
    onOpenLinkedDevices: () -> Unit = {},
    /** Overflow menu → full Settings & privacy screen. */
    onOpenSettings: () -> Unit = {},
    /** Tapping a conversation's avatar or name opens that person's profile. */
    onOpenProfile: (peerId: String) -> Unit = {},
    /** Selection-mode actions, applied to each selected peer id by the host. */
    onArchiveChats: (List<String>) -> Unit = {},
    onDeleteChats: (List<String>) -> Unit = {},
    onMarkChatsRead: (List<String>) -> Unit = {}
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var overflowExpanded by remember { mutableStateOf(false) }

    var selectedTab by remember { mutableStateOf(HomeTab.CHATS) }

    // --- Search: live, local, instant — no server round trip per keystroke ---
    var searchQuery by remember { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }
    val visibleConversations = remember(conversations, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) conversations
        else conversations.filter {
            it.displayName.contains(q, ignoreCase = true) ||
                (it.lastText?.contains(q, ignoreCase = true) == true) ||
                (it.draftText?.contains(q, ignoreCase = true) == true)
        }
    }

    // --- Multi-select (long-press) ---
    var selected by remember { mutableStateOf(setOf<String>()) }
    val selectionMode = selected.isNotEmpty()
    fun toggleSelect(peerId: String) {
        selected = if (peerId in selected) selected - peerId else selected + peerId
    }

    // --- Delete confirmation ---
    var pendingDelete by remember { mutableStateOf<List<String>?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            BottomNavBar(
                selectedTab = selectedTab,
                onTabSelected = { tab ->
                    selectedTab = tab
                    when (tab) {
                        HomeTab.POST -> onOpenPost()
                        HomeTab.FEEDS -> { selectedTab = HomeTab.CHATS; onOpenFeeds() }
                        HomeTab.CONTACTS -> { selectedTab = HomeTab.CHATS; onOpenContacts() }
                        HomeTab.CHATS -> Unit
                        HomeTab.PROFILE -> {
                            selectedTab = HomeTab.CHATS
                            AuthSession.currentUserId?.let { onOpenProfile(it) }
                        }
                    }
                },
                profileAvatarUrl = profileAvatarUrl,
                chatsLabel = stringResource(Res.string.nav_chats),
                contactsLabel = stringResource(Res.string.nav_contacts),
                postLabel = stringResource(Res.string.nav_post),
                feedsLabel = stringResource(Res.string.nav_feeds),
                profileLabel = stringResource(Res.string.nav_profile)
            )
        }
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {

            if (selectionMode) {
                // --- Contextual selection bar ---
                Row(
                    Modifier.fillMaxWidth().background(TelefamColors.PrimaryRed)
                        .padding(top = 20.dp, bottom = 20.dp, start = 8.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { selected = emptySet() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear selection", tint = TelefamColors.White)
                    }
                    Text(
                        "${selected.size}",
                        fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TelefamColors.White,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        val ids = selected.toList(); selected = emptySet(); onMarkChatsRead(ids)
                    }) {
                        Icon(Icons.Filled.DoneAll, contentDescription = "Mark as read", tint = TelefamColors.White)
                    }
                    IconButton(onClick = {
                        val ids = selected.toList(); selected = emptySet(); onArchiveChats(ids)
                    }) {
                        Icon(Icons.Filled.Archive, contentDescription = "Archive", tint = TelefamColors.White)
                    }
                    IconButton(onClick = { pendingDelete = selected.toList() }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = TelefamColors.White)
                    }
                }
            } else {
                // --- Top bar: red background, "Telefam" wordmark, 3-dot menu ---
                Box(
                    Modifier.fillMaxWidth().background(TelefamColors.PrimaryRed)
                        .padding(top = 20.dp, bottom = 20.dp, start = 20.dp, end = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TelefamLogo(size = 34.dp, circleColor = TelefamColors.White, planeColor = TelefamColors.PrimaryRed)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(Res.string.app_name),
                            fontSize = 30.sp, fontWeight = FontWeight.Bold, color = TelefamColors.White
                        )
                    }
                    IconButton(onClick = { overflowExpanded = true }, modifier = Modifier.align(Alignment.CenterEnd)) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(Res.string.more_options), tint = TelefamColors.White)
                    }
                    HomeOverflowMenu(
                        expanded = overflowExpanded,
                        onDismiss = { overflowExpanded = false },
                        onPrivacyClick = onOpenPrivacy,
                        onMessageRequestsClick = onOpenMessageRequests,
                        onArchivedClick = onOpenArchived,
                        onBlockedClick = onOpenBlocked,
                        onLinkedDevicesClick = onOpenLinkedDevices,
                        onSettingsClick = onOpenSettings
                    )
                }
            }

            // --- Search bar: live filtering; icon toggles the field on/off ---
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 16.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { searchActive = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Search, contentDescription = "Search conversations",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.size(22.dp).clickable {
                        if (searchActive) { searchActive = false; searchQuery = "" } else searchActive = true
                    }
                )
                Spacer(Modifier.width(10.dp))
                if (searchActive) {
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(TelefamColors.PrimaryRed),
                        modifier = Modifier.weight(1f)
                    )
                    if (searchQuery.isNotEmpty()) {
                        Icon(
                            Icons.Filled.Close, contentDescription = "Clear",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp).clickable { searchQuery = "" }
                        )
                    }
                } else {
                    Text(
                        stringResource(Res.string.search_hint),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }

            // --- Conversations ---
            if (visibleConversations.isEmpty() && systemInbox == null) {
                if (searchQuery.isNotBlank()) {
                    // Search with no hits — explicit empty state, never a dead list.
                    Column(
                        Modifier.fillMaxWidth().weight(1f).padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Filled.Search, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "No conversations match \"$searchQuery\"",
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    Column(
                        Modifier.fillMaxWidth().weight(1f).padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        ChatEmptyStateIllustration()
                        Spacer(Modifier.height(28.dp))
                        Text(
                            stringResource(Res.string.empty_title),
                            fontSize = 24.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(Res.string.empty_subtitle),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center,
                            lineHeight = 22.sp
                        )
                        Spacer(Modifier.height(24.dp))
                        TelefamPrimaryButton(
                            text = stringResource(Res.string.start_conversation),
                            onClick = onNewChat,
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = { Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, tint = TelefamColors.White) },
                            trailingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = TelefamColors.White) }
                        )
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    // --- Pinned system conversations: part of the conversation list,
                    //     not selectable/archivable/deletable. ---
                    if (systemInbox != null && searchQuery.isBlank()) {
                        item(key = "sys_notifications") {
                            SystemConversationRow(
                                title = "Notifications",
                                verified = true,
                                preview = systemInbox.notificationsLastBody?.let {
                                    val t = systemInbox.notificationsLastTitle
                                    if (t.isNullOrBlank()) it else "$t — $it"
                                } ?: "You have new notifications!",
                                lastAtIso = systemInbox.notificationsLastAt,
                                unreadCount = systemInbox.notificationsUnreadCount,
                                icon = {
                                    TelefamLogo(size = 52.dp, circleColor = TelefamColors.PrimaryRed, planeColor = TelefamColors.White)
                                },
                                onClick = onOpenNotifications
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                        item(key = "sys_official") {
                            SystemConversationRow(
                                title = systemInbox.officialName,
                                verified = systemInbox.officialVerified,
                                preview = systemInbox.officialLastMessage ?: "Welcome to Telefam! 🎉",
                                lastAtIso = systemInbox.officialLastAt,
                                unreadCount = systemInbox.officialUnreadCount,
                                icon = {
                                    TelefamLogo(size = 52.dp, circleColor = TelefamColors.PrimaryRed, planeColor = TelefamColors.White)
                                },
                                onClick = onOpenOfficialChat
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }
                    items(visibleConversations, key = { it.peerId }) { c ->
                        ConversationRow(
                            c,
                            selected = c.peerId in selected,
                            selectionMode = selectionMode,
                            onClick = {
                                if (selectionMode) toggleSelect(c.peerId)
                                else onOpenChat(c.peerId, c.displayName)
                            },
                            onLongClick = { toggleSelect(c.peerId) },
                            onAvatarClick = { if (!selectionMode) onOpenProfile(c.peerId) else toggleSelect(c.peerId) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }

    // Delete confirmation for the selected conversations.
    pendingDelete?.let { ids ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(if (ids.size == 1) "Delete this chat?" else "Delete ${ids.size} chats?") },
            text = { Text("Messages are removed from this device. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null; selected = emptySet(); onDeleteChats(ids)
                }) { Text("Delete", color = TelefamColors.PrimaryRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    c: ConversationSummary,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onAvatarClick: () -> Unit = {}
) {
    Row(
        Modifier.fillMaxWidth()
            .background(
                if (selected) TelefamColors.PrimaryRed.copy(alpha = 0.10f)
                else MaterialTheme.colorScheme.surface
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 20.dp, vertical = 14.dp), // comfortable 74dp-tall row
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape)
                .background(
                    if (selected) TelefamColors.PrimaryRed else TelefamColors.PrimaryRed.copy(alpha = 0.15f)
                )
                .clickable(onClick = onAvatarClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                c.displayName.take(1).uppercase(), fontWeight = FontWeight.Bold,
                color = if (selected) TelefamColors.White else TelefamColors.PrimaryRed
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.displayName, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (c.isVerified) {
                    Spacer(Modifier.width(5.dp))
                    com.telefam.ui.components.VerifiedBadge(size = 15.dp)
                }
            }
            if (!c.draftText.isNullOrBlank()) {
                // Clear draft indicator — never confused with a sent message preview.
                Row {
                    Text(
                        stringResource(Res.string.conv_draft_prefix),
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TelefamColors.PrimaryRed, maxLines = 1
                    )
                    Text(
                        c.draftText.replace('\n', ' '),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    previewIconFor(c)?.let { icon ->
                        Icon(
                            icon, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        previewFor(c), fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                formatConversationTime(c.lastAt),
                fontSize = 12.sp,
                color = if (c.unreadCount > 0) TelefamColors.PrimaryRed
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            if (c.unreadCount > 0) {
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier.defaultMinSize(minWidth = 20.dp).height(20.dp)
                        .clip(CircleShape).background(TelefamColors.PrimaryRed)
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (c.unreadCount > 99) "99+" else c.unreadCount.toString(),
                        fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TelefamColors.White
                    )
                }
            }
        }
    }
}

/** A pinned system conversation row (Telefam Official / Notifications) — tap to open, no long-press actions. */
@Composable
private fun SystemConversationRow(
    title: String,
    verified: Boolean,
    preview: String,
    lastAtIso: String?,
    unreadCount: Long,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    val epoch = lastAtIso?.let {
        runCatching {
            java.time.LocalDateTime.parse(it).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrDefault(0L)
    }
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(TelefamColors.PrimaryRed),
            contentAlignment = Alignment.Center
        ) { icon() }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (verified) {
                    Spacer(Modifier.width(5.dp))
                    com.telefam.ui.components.VerifiedBadge(size = 15.dp)
                }
            }
            Text(
                preview, fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                formatConversationTime(epoch),
                fontSize = 12.sp,
                color = if (unreadCount > 0) TelefamColors.PrimaryRed
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            if (unreadCount > 0) {
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier.defaultMinSize(minWidth = 20.dp).height(20.dp)
                        .clip(CircleShape).background(TelefamColors.PrimaryRed)
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (unreadCount > 99) "99+" else unreadCount.toString(),
                        fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TelefamColors.White
                    )
                }
            }
        }
    }
}

/** Messenger-style timestamp: HH:mm today, weekday this week, dd/MM/yyyy older. */
private fun formatConversationTime(epochMillis: Long?): String {
    if (epochMillis == null || epochMillis <= 0) return ""
    val now = System.currentTimeMillis()
    val dayMillis = 86_400_000L
    val sameDay = java.time.LocalDate.now() == java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    return when {
        sameDay -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMillis))
        now - epochMillis < 7 * dayMillis -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(epochMillis))
        else -> SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(epochMillis))
    }
}

@Composable
private fun previewFor(c: ConversationSummary): String {
    val body = when (c.lastCategory) {
        null -> return stringResource(Res.string.conv_no_messages)
        "IMAGE" -> if (c.lastViewOnce) stringResource(Res.string.chat_view_once) else stringResource(Res.string.conv_preview_photo)
        "VOICE" -> stringResource(Res.string.conv_preview_voice)
        "VIDEO" -> stringResource(Res.string.conv_preview_video)
        "POLL" -> stringResource(Res.string.conv_preview_poll)
        "CONTACT" -> stringResource(Res.string.conv_preview_contact)
        "LOCATION" -> stringResource(Res.string.conv_preview_location)
        "FILE" -> stringResource(Res.string.conv_preview_file)
        else -> c.lastText ?: ""
    }
    return if (c.lastOutgoing) stringResource(Res.string.conv_you_prefix, body) else body
}

/** Material icon shown in front of non-text conversation previews (replaces emoji glyphs). */
private fun previewIconFor(c: ConversationSummary): ImageVector? = when (c.lastCategory) {
    "IMAGE" -> Icons.Filled.Image
    "VOICE" -> Icons.Filled.Mic
    "VIDEO" -> Icons.Filled.PlayArrow
    "POLL" -> Icons.Filled.Poll
    "CONTACT" -> Icons.Filled.Person
    "LOCATION" -> Icons.Filled.LocationOn
    "FILE" -> Icons.Filled.Description
    else -> null
}
