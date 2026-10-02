package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.telefam.chat.*
import com.telefam.data.api.DirectoryUserDto
import com.telefam.shared.generated.resources.*
import com.telefam.ui.components.*
import com.telefam.ui.theme.ChatThemeScope
import com.telefam.ui.theme.TelefamColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Peer-controlled privacy, already evaluated against my relationship to them (cached locally so it holds offline). */
data class ChatPolicies(
    val allowScreenshot: Boolean = true,
    val allowCopy: Boolean = true,
    val allowForward: Boolean = true,
    val allowDownload: Boolean = true
)

enum class RestrictedAction { COPY, FORWARD, DOWNLOAD }

private sealed class ListRow {
    data class Msg(val m: CachedMessageItem) : ListRow()
    data class Day(val ts: Long) : ListRow()
}

private enum class VoiceState { IDLE, RECORDING, PREVIEW }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    peerName: String,
    peerAvatarUrl: String?,
    peerOnline: Boolean,
    peerStatus: String?,
    peerTyping: Boolean,
    currentUserId: String,
    messages: List<CachedMessageItem>, // newest first
    hasMoreOlder: Boolean,
    policies: ChatPolicies,
    pinnedMessages: List<CachedMessageItem>,
    voiceRecorder: VoiceRecorder,
    audioPlayer: AudioPlayer,
    ensureMicPermission: ((Boolean) -> Unit) -> Unit,
    setSecureScreen: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenOptions: () -> Unit,
    /** Tapping the peer's avatar or name in the header opens their profile. */
    onOpenProfile: () -> Unit = {},
    onLoadOlder: () -> Unit,
    onSendText: (String, CachedMessageItem?) -> Unit,
    onSendVoice: (RecordedAudio, Boolean) -> Unit,
    onSendEvent: (EventData) -> Unit,
    onAttachment: (AttachmentType) -> Unit,
    onVotePoll: (CachedMessageItem, PollData, List<Int>) -> Unit,
    onViewOnceConsumed: (String) -> Unit,
    onDeleteForMe: (CachedMessageItem) -> Unit,
    onDeleteForEveryone: (CachedMessageItem) -> Unit,
    onEditMessage: (CachedMessageItem, String) -> Unit,
    onReact: (CachedMessageItem, String) -> Unit,
    onTogglePin: (CachedMessageItem) -> Unit,
    onToggleStar: (CachedMessageItem) -> Unit,
    onForward: (CachedMessageItem) -> Unit,
    onRetryMedia: (CachedMessageItem) -> Unit,
    onTyping: () -> Unit,
    /** Searches the COMPLETE local history of this chat (the host runs it off the main thread). */
    onSearchMessages: suspend (String) -> List<CachedMessageItem>,
    /** All starred messages of this chat, straight from the local database (not just the loaded page). */
    onStarredMessages: suspend () -> List<CachedMessageItem>,
    /** Grows the loaded window until message [id] is part of [messages] (old search hits, quotes, pinned banner). */
    onEnsureMessageLoaded: (String) -> Unit,
    /** Resolves an @username to a Telefam user (null = no such user); may throw on network errors. */
    onResolveMention: suspend (String) -> DirectoryUserDto?,
    /** Opens (or starts) the chat with a user picked from a mention. */
    onMessageUser: (DirectoryUserDto) -> Unit,
    onRestrictedAttempt: (RestrictedAction) -> Unit,
    onContactSave: (ContactData) -> Unit,
    onContactOpen: (ContactData) -> Unit,
    onContactCall: (ContactData) -> Unit,
    /** Draft text saved for this conversation (restored into the input on entry). */
    initialDraft: String? = null,
    /** Called as the user types and on exit so drafts persist. Never sends anything. */
    onDraftChange: (String) -> Unit = {},
    /** Starts a P2P WebRTC video call with this peer. */
    onStartVideoCall: () -> Unit = {},
    /** Starts a P2P WebRTC voice call with this peer. */
    onStartAudioCall: () -> Unit = {},
    /** I blocked this peer — composer is replaced by an Unblock action. */
    peerBlockedByMe: Boolean = false,
    /** This peer blocked me — composer is replaced by a can't-contact notice. */
    peerBlockedMe: Boolean = false,
    /** Unblock action (only shown when peerBlockedByMe). */
    onUnblockPeer: () -> Unit = {},
    /** Official-account conversation: read-only (no composer) and no call buttons. */
    isOfficialAccount: Boolean = false
) {
    val theme by ThemeController.theme.collectAsState()
    val bubble by ThemeController.bubbleColour.collectAsState()

    ChatThemeScope(theme, bubble) {
        val accent = Color(bubble.color)
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val clipboard = LocalClipboardManager.current
        val listState = rememberLazyListState()

        val copyBlocked = stringResource(Res.string.chat_restricted_copy)
        val shareBlocked = stringResource(Res.string.chat_restricted_share)
        val micUnavailable = stringResource(Res.string.voice_mic_unavailable)
        val youLabel = stringResource(Res.string.chat_you)
        val otpCopied = stringResource(Res.string.otp_copied)
        val downloadBlocked = stringResource(Res.string.chat_restricted_download)
        val fileUnavailable = stringResource(Res.string.file_unavailable)
        val fileOpenFailed = stringResource(Res.string.file_open_failed)
        fun toast(text: String) { scope.launch { snackbar.showSnackbar(text) } }

        // --- Screenshot enforcement (cached peer policy, so it applies offline too) ---
        DisposableEffect(policies.allowScreenshot) {
            setSecureScreen(!policies.allowScreenshot)
            onDispose { setSecureScreen(false) }
        }

        // --- Rows with day dividers (list is reversed, so a Day row sits above its oldest message) ---
        val rows = remember(messages) {
            buildList<ListRow> {
                messages.forEachIndexed { i, m ->
                    add(ListRow.Msg(m))
                    val older = messages.getOrNull(i + 1)
                    if (older == null || ChatFormat.dayOf(older.createdAt) != ChatFormat.dayOf(m.createdAt)) add(ListRow.Day(m.createdAt))
                }
            }
        }

        // --- Lazy pagination: load the next older page as the user nears the top ---
        LaunchedEffect(rows.size, hasMoreOlder) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }.collect { last ->
                if (hasMoreOlder && last != null && last >= rows.size - 4) onLoadOlder()
            }
        }
        LaunchedEffect(messages.firstOrNull()?.id) {
            if (listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0)
        }

        // A jump target may be older than the loaded page: ask the host to load up to it, then scroll once it is in `rows`.
        var pendingScrollId by remember { mutableStateOf<String?>(null) }
        fun scrollToMessage(id: String) {
            val index = rows.indexOfFirst { it is ListRow.Msg && it.m.id == id }
            if (index >= 0) {
                pendingScrollId = null
                scope.launch { listState.animateScrollToItem(index) }
            } else {
                pendingScrollId = id
                onEnsureMessageLoaded(id)
            }
        }
        LaunchedEffect(pendingScrollId, rows) {
            val id = pendingScrollId ?: return@LaunchedEffect
            val index = rows.indexOfFirst { it is ListRow.Msg && it.m.id == id }
            if (index >= 0) {
                pendingScrollId = null
                listState.animateScrollToItem(index)
            }
        }

        // --- Voice state ---
        var voiceState by remember { mutableStateOf(VoiceState.IDLE) }
        var recElapsed by remember { mutableIntStateOf(0) }
        var recorded by remember { mutableStateOf<RecordedAudio?>(null) }
        var previewViewOnce by remember { mutableStateOf(false) }
        var playingId by remember { mutableStateOf<String?>(null) }
        var playProgress by remember { mutableFloatStateOf(0f) }

        LaunchedEffect(voiceState) {
            recElapsed = 0
            while (voiceState == VoiceState.RECORDING) { delay(1000); recElapsed++ }
        }
        var paused by remember { mutableStateOf(false) }

        fun finishPlayback() {
            val finished = playingId ?: return
            playingId = null; playProgress = 0f; paused = false
            messages.firstOrNull { it.id == finished && it.viewOnce && !it.viewOnceOpened }?.let { onViewOnceConsumed(it.id) }
        }

        LaunchedEffect(playingId) {
            while (playingId != null) {
                delay(120)
                val dur = audioPlayer.durationMs
                playProgress = if (dur > 0) audioPlayer.positionMs / dur.toFloat() else 0f
                if (!paused && !audioPlayer.isPlaying) finishPlayback()
            }
        }
        DisposableEffect(Unit) { onDispose { audioPlayer.stop(); if (voiceRecorder.isRecording) voiceRecorder.cancel() } }

        fun togglePlay(id: String, path: String) {
            if (playingId == id) {
                if (audioPlayer.isPlaying) { audioPlayer.pause(); paused = true } else { audioPlayer.resume(); paused = false }
                return
            }
            playProgress = 0f; paused = false
            audioPlayer.play(path) { finishPlayback() }
            playingId = id
        }

        // --- UI state ---
        var text by remember { mutableStateOf(initialDraft ?: "") }
        // Draft autosave: debounce, persist on every settled change and on leaving the screen.
        LaunchedEffect(text) {
            if (text == (initialDraft ?: "")) return@LaunchedEffect
            delay(400)
            onDraftChange(text)
        }
        DisposableEffect(Unit) { onDispose { onDraftChange(text) } }
        val entityActions = rememberEntityActions()
        var browserUrl by remember { mutableStateOf<String?>(null) }
        var phoneActionFor by remember { mutableStateOf<String?>(null) }
        var emailActionFor by remember { mutableStateOf<String?>(null) }
        var mentionFor by remember { mutableStateOf<String?>(null) }
        fun openFile(m: CachedMessageItem) {
            val path = m.mediaLocalPath
            if (path == null) { toast(fileUnavailable); return }
            // Opening hands the file to another app (an export), so the chat's download policy applies to received files.
            if (!m.outgoing && !policies.allowDownload) {
                toast(downloadBlocked); onRestrictedAttempt(RestrictedAction.DOWNLOAD); return
            }
            val meta = FileMeta.decode(m.textOrCaption ?: "")
            if (!entityActions.openFile(path, meta?.mimeType, meta?.name)) toast(fileOpenFailed)
        }
        var showAttachments by remember { mutableStateOf(false) }
        var menuFor by remember { mutableStateOf<CachedMessageItem?>(null) }
        var viewingImage by remember { mutableStateOf<CachedMessageItem?>(null) }
        var viewingVideo by remember { mutableStateOf<String?>(null) }
        var viewingLocation by remember { mutableStateOf<CachedMessageItem?>(null) }
        var replyTo by remember { mutableStateOf<CachedMessageItem?>(null) }
        var editing by remember { mutableStateOf<CachedMessageItem?>(null) }
        var emojiOpen by remember { mutableStateOf(false) }
        var showEventComposer by remember { mutableStateOf(false) }
        var deleteConfirm by remember { mutableStateOf<CachedMessageItem?>(null) }

        // --- In-chat search ---
        var searchActive by remember { mutableStateOf(false) }
        var searchQuery by remember { mutableStateOf("") }
        var starredOnly by remember { mutableStateOf(false) }
        var searchResults by remember { mutableStateOf(listOf<CachedMessageItem>()) }
        var searchIndex by remember { mutableIntStateOf(0) }

        // Queries hit the local database for the whole chat history. `messages` is deliberately NOT a key:
        // expanding the window to reach an old hit must not restart the search and reset the selected result.
        LaunchedEffect(searchQuery, starredOnly, searchActive) {
            if (!searchActive) return@LaunchedEffect
            delay(200)
            searchResults = when {
                starredOnly -> onStarredMessages()
                searchQuery.isNotBlank() -> onSearchMessages(searchQuery.trim())
                else -> emptyList()
            }
            searchIndex = 0
            searchResults.firstOrNull()?.let { scrollToMessage(it.id) }
        }

        fun handleEntity(e: MessageEntity) {
            when (e.type) {
                EntityType.URL -> browserUrl = e.normalized // stays inside Telefam's in-app browser
                EntityType.EMAIL -> emailActionFor = e.raw
                EntityType.PHONE -> phoneActionFor = e.raw
                EntityType.OTP -> clipboard.setText(AnnotatedString(e.raw))
                EntityType.MENTION -> mentionFor = e.normalized // username without the "@"
                EntityType.HASHTAG -> { // a hashtag is a search: find it across the whole local history of this chat
                    starredOnly = false
                    searchQuery = e.raw
                    searchActive = true
                }
            }
        }

        fun stepSearch(delta: Int) {
            if (searchResults.isEmpty()) return
            searchIndex = (searchIndex + delta + searchResults.size) % searchResults.size
            scrollToMessage(searchResults[searchIndex].id)
        }

        // Quoted-sender label: "You" always means the quoted message's author is the viewer.
        fun replyLabel(m: CachedMessageItem): String =
            when {
                m.replySender == "You" -> if (m.outgoing) youLabel else peerName
                else -> if (m.outgoing) peerName else youLabel
            }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                if (searchActive) {
                    ChatSearchBar(
                        query = searchQuery, onQueryChange = { searchQuery = it; starredOnly = false },
                        starredOnly = starredOnly, onToggleStarred = { starredOnly = !starredOnly },
                        matchText = if (searchResults.isEmpty()) "0/0" else "${searchIndex + 1}/${searchResults.size}",
                        onPrev = { stepSearch(1) }, // list is newest-first: "up" visually = newer
                        onNext = { stepSearch(-1) },
                        onClose = { searchActive = false; searchQuery = ""; starredOnly = false; searchResults = emptyList(); pendingScrollId = null }
                    )
                } else {
                    ChatHeader(
                        name = peerName, avatarUrl = peerAvatarUrl, online = peerOnline,
                        status = if (peerTyping) stringResource(Res.string.chat_typing) else peerStatus,
                        onBack = onBack, onVideo = onStartVideoCall, onAudio = onStartAudioCall,
                        onSearch = { searchActive = true }, onMore = onOpenOptions,
                        onOpenProfile = onOpenProfile,
                        showCallButtons = !isOfficialAccount
                    )
                }
            },
            bottomBar = {
                Column(Modifier.navigationBarsPadding().imePadding()) {
                    // Blocked states replace the composer entirely — messaging is impossible either way,
                    // and this matches the server-side drop in E2EEService.sendEnvelope.
                    when {
                        isOfficialAccount -> BlockedComposer(
                            message = "Only Telefam can send messages here",
                            actionLabel = null,
                            accent = accent,
                            onAction = {}
                        )
                        peerBlockedByMe -> BlockedComposer(
                            message = "You blocked this person",
                            actionLabel = "Unblock",
                            accent = accent,
                            onAction = onUnblockPeer
                        )
                        peerBlockedMe -> BlockedComposer(
                            message = "You can't contact this person",
                            actionLabel = null,
                            accent = accent,
                            onAction = {}
                        )
                        else -> {
                    replyTo?.let { r ->
                        ComposeContextBar(
                            title = "${stringResource(Res.string.chat_replying_to)} ${if (r.outgoing) youLabel else peerName}",
                            preview = r.replyPreview ?: r.textOrCaption ?: "",
                            accent = accent, onCancel = { replyTo = null }
                        )
                    }
                    editing?.let {
                        ComposeContextBar(
                            title = stringResource(Res.string.chat_editing_message),
                            preview = it.textOrCaption ?: "",
                            accent = accent, onCancel = { editing = null; text = "" }
                        )
                    }
                    when (voiceState) {
                        VoiceState.IDLE -> ChatInputBar(
                            text = text,
                            onTextChange = { text = it; if (it.isNotBlank()) onTyping() },
                            placeholder = stringResource(Res.string.chat_type_message), accent = accent,
                            emojiActive = emojiOpen,
                            onPlusClick = { showAttachments = true; emojiOpen = false },
                            onEmojiClick = { emojiOpen = !emojiOpen },
                            onMicClick = {
                                ensureMicPermission { granted ->
                                    if (granted && voiceRecorder.start()) voiceState = VoiceState.RECORDING else toast(micUnavailable)
                                }
                            },
                            onSendClick = {
                                val value = text.trim()
                                if (value.isEmpty()) return@ChatInputBar
                                val editingNow = editing
                                if (editingNow != null) {
                                    onEditMessage(editingNow, value)
                                    editing = null
                                } else {
                                    onSendText(value, replyTo)
                                    replyTo = null
                                    onDraftChange("") // message sent — the draft is consumed, never kept around
                                }
                                text = ""; emojiOpen = false
                            }
                        )
                        VoiceState.RECORDING -> VoiceRecordingBar(
                            accent = accent, elapsedSeconds = recElapsed,
                            onCancel = { voiceRecorder.cancel(); voiceState = VoiceState.IDLE },
                            onStop = {
                                val r = voiceRecorder.stop()
                                if (r != null) { recorded = r; previewViewOnce = false; voiceState = VoiceState.PREVIEW } else voiceState = VoiceState.IDLE
                            }
                        )
                        VoiceState.PREVIEW -> VoicePreviewBar(
                            accent = accent, durationSeconds = recorded?.durationSeconds ?: 0,
                            playing = playingId == "preview" && audioPlayer.isPlaying,
                            progress = if (playingId == "preview") playProgress else 0f,
                            viewOnce = previewViewOnce,
                            onPlayPause = { recorded?.let { togglePlay("preview", it.filePath) } },
                            onDelete = { audioPlayer.stop(); playingId = null; recorded?.let { deleteLocalFile(it.filePath) }; recorded = null; voiceState = VoiceState.IDLE },
                            onToggleViewOnce = { previewViewOnce = !previewViewOnce },
                            onSend = {
                                audioPlayer.stop(); playingId = null
                                recorded?.let { onSendVoice(it, previewViewOnce) }
                                recorded = null; voiceState = VoiceState.IDLE
                            }
                        )
                    }
                    if (emojiOpen && voiceState == VoiceState.IDLE) {
                        EmojiPickerPanel(onPick = { text += it })
                    }
                        } // end else (not blocked)
                    } // end when (blocked state)
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                pinnedMessages.firstOrNull()?.let { p ->
                    PinnedBanner(
                        preview = p.replyPreview ?: p.textOrCaption ?: p.contentCategory,
                        senderLabel = if (p.outgoing) youLabel else peerName,
                        accent = accent,
                        onClick = { scrollToMessage(p.id) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
                }
                LazyColumn(
                    state = listState, reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(rows, key = { r -> when (r) { is ListRow.Msg -> r.m.id; is ListRow.Day -> "day-${ChatFormat.dayOf(r.ts)}" } }) { row ->
                        when (row) {
                            is ListRow.Day -> DateDividerChip(
                                when (ChatFormat.dayKind(row.ts)) {
                                    DayKind.TODAY -> stringResource(Res.string.chat_today)
                                    DayKind.YESTERDAY -> stringResource(Res.string.chat_yesterday)
                                    DayKind.OTHER -> ChatFormat.numericDate(row.ts)
                                }
                            )
                            is ListRow.Msg -> {
                                val m = row.m
                                val time = ChatFormat.time(m.createdAt)
                                Column(Modifier.pointerInput(m.id) { detectTapGestures(onLongPress = { if (!m.deletedForEveryone) menuFor = m }) }) {
                                    MessageContent(
                                        m = m, time = time, accent = accent, avatarUrl = peerAvatarUrl, currentUserId = currentUserId, peerName = peerName,
                                        playingId = playingId, playProgress = playProgress,
                                        replyLabel = replyLabel(m),
                                        onPlayVoice = { m.mediaLocalPath?.let { p -> togglePlay(m.id, p) } },
                                        onOpenImage = { viewingImage = m },
                                        onPlayVideo = { m.mediaLocalPath?.let { viewingVideo = it } },
                                        onOpenLocation = { viewingLocation = m },
                                        onVotePoll = onVotePoll,
                                        onRetryMedia = { onRetryMedia(m) },
                                        onReact = { emoji -> onReact(m, emoji) },
                                        onQuoteClick = { m.replyToId?.let(::scrollToMessage) },
                                        onContactSave = onContactSave, onContactOpen = onContactOpen, onContactCall = onContactCall,
                                        onEntityClick = ::handleEntity,
                                        onOtpCopy = { code -> clipboard.setText(AnnotatedString(code)); toast(otpCopied) },
                                        onOpenFile = { openFile(m) }
                                    )
                                    DropdownMenu(expanded = menuFor?.id == m.id, onDismissRequest = { menuFor = null }) {
                                        Row(Modifier.padding(horizontal = 10.dp, vertical = 2.dp)) {
                                            EmojiData.quickReactions.forEach { emoji ->
                                                Text(
                                                    emoji, fontSize = 20.sp,
                                                    modifier = Modifier.clip(CircleShape)
                                                        .clickable { menuFor = null; onReact(m, emoji) }
                                                        .padding(5.dp)
                                                )
                                            }
                                        }
                                        HorizontalDivider()
                                        DropdownMenuItem(text = { Text(stringResource(Res.string.chat_menu_reply)) }, onClick = {
                                            menuFor = null; editing = null; replyTo = m
                                        })
                                        if (m.contentCategory == "TEXT" && !m.textOrCaption.isNullOrEmpty()) {
                                            DropdownMenuItem(text = { Text(stringResource(Res.string.chat_menu_copy)) }, onClick = {
                                                menuFor = null
                                                if (m.outgoing || policies.allowCopy) clipboard.setText(AnnotatedString(m.textOrCaption ?: ""))
                                                else { toast(copyBlocked); onRestrictedAttempt(RestrictedAction.COPY) }
                                            })
                                        }
                                        if (m.outgoing && m.contentCategory == "TEXT" && m.mediaRemoteId == null) {
                                            DropdownMenuItem(text = { Text(stringResource(Res.string.chat_menu_edit)) }, onClick = {
                                                menuFor = null; replyTo = null; editing = m; text = m.textOrCaption ?: ""
                                            })
                                        }
                                        DropdownMenuItem(text = { Text(stringResource(Res.string.chat_menu_forward)) }, onClick = {
                                            menuFor = null
                                            if (m.outgoing || policies.allowForward) onForward(m)
                                            else { toast(shareBlocked); onRestrictedAttempt(RestrictedAction.FORWARD) }
                                        })
                                        DropdownMenuItem(
                                            text = { Text(stringResource(if (m.starred) Res.string.chat_menu_unstar else Res.string.chat_menu_star)) },
                                            onClick = { menuFor = null; onToggleStar(m) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(if (m.pinned) Res.string.chat_menu_unpin else Res.string.chat_menu_pin)) },
                                            onClick = { menuFor = null; onTogglePin(m) }
                                        )
                                        DropdownMenuItem(text = { Text(stringResource(Res.string.chat_menu_delete_me)) }, onClick = {
                                            menuFor = null; onDeleteForMe(m)
                                        })
                                        if (m.outgoing) {
                                            DropdownMenuItem(text = {
                                                Text(stringResource(Res.string.chat_menu_delete_everyone), color = MaterialTheme.colorScheme.error)
                                            }, onClick = { menuFor = null; deleteConfirm = m })
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (hasMoreOlder) item(key = "loading-older") {
                        Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(Res.string.chat_load_more), fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }

        // --- Plus icon -> attachment sheet (every entry is functional) ---
        if (showAttachments) {
            ModalBottomSheet(onDismissRequest = { showAttachments = false }, containerColor = MaterialTheme.colorScheme.surface) {
                AttachmentSheet(onSelect = { type ->
                    showAttachments = false
                    if (type == AttachmentType.EVENT) showEventComposer = true else onAttachment(type)
                })
            }
        }

        if (showEventComposer) {
            EventComposerDialog(accent = accent, onDismiss = { showEventComposer = false }, onSend = { ev ->
                showEventComposer = false
                onSendEvent(ev)
            })
        }

        // --- Delete-for-everyone confirmation ---
        deleteConfirm?.let { m ->
            AlertDialog(
                onDismissRequest = { deleteConfirm = null },
                title = { Text(stringResource(Res.string.chat_menu_delete_everyone)) },
                text = { Text(stringResource(Res.string.chat_message_deleted)) },
                confirmButton = {
                    TextButton(onClick = { deleteConfirm = null; onDeleteForEveryone(m) }) {
                        Text(stringResource(Res.string.chat_menu_delete_everyone), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteConfirm = null }) { Text(stringResource(Res.string.back)) }
                }
            )
        }

        // --- View-once photo viewer (consumed on close, never reopenable) ---
        viewingImage?.let { m ->
            Dialog(onDismissRequest = { viewingImage = null; if (m.viewOnce) onViewOnceConsumed(m.id) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AsyncImage(model = m.mediaLocalPath, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    IconButton(
                        onClick = { viewingImage = null; if (m.viewOnce) onViewOnceConsumed(m.id) },
                        modifier = Modifier.align(Alignment.TopStart).padding(top = 36.dp, start = 8.dp)
                    ) { Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White) }
                }
            }
        }

        viewingVideo?.let { path -> VideoPlayerDialog(filePath = path, onDismiss = { viewingVideo = null }) }

        // --- Smart-entity surfaces: in-app browser, phone actions, email actions ---
        browserUrl?.let { u ->
            InAppBrowserDialog(url = u, accent = accent, onClose = { browserUrl = null })
        }
        phoneActionFor?.let { p ->
            PhoneActionsSheet(
                phone = p, accent = accent,
                onCall = { entityActions.callNumber(p) },
                onCopy = { clipboard.setText(AnnotatedString(p)) },
                onSaveContact = { entityActions.saveContact(p) },
                onDismiss = { phoneActionFor = null }
            )
        }
        mentionFor?.let { username ->
            MentionProfileSheet(
                username = username, accent = accent,
                resolve = onResolveMention,
                onMessage = { user -> mentionFor = null; onMessageUser(user) },
                onDismiss = { mentionFor = null }
            )
        }
        emailActionFor?.let { e ->
            EmailActionsSheet(
                email = e, accent = accent,
                onCompose = { entityActions.composeEmail(e) },
                onCopy = { clipboard.setText(AnnotatedString(e)) },
                onShare = { entityActions.shareText(e) },
                onDismiss = { emailActionFor = null }
            )
        }

        // --- In-app location viewer (never opens an external maps app) ---
        viewingLocation?.let { m ->
            val loc = m.textOrCaption?.let { LocationData.decode(it) }
            Dialog(onDismissRequest = { viewingLocation = null; if (m.viewOnce) onViewOnceConsumed(m.id) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    if (loc != null) InAppMapView(loc, modifier = Modifier.fillMaxSize())
                    IconButton(
                        onClick = { viewingLocation = null; if (m.viewOnce) onViewOnceConsumed(m.id) },
                        modifier = Modifier.align(Alignment.TopStart).padding(top = 36.dp, start = 8.dp)
                            .clip(CircleShape).background(Color.Black.copy(alpha = 0.5f))
                    ) { Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White) }
                    loc?.label?.let {
                        Text(
                            it, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp).clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Header: rounded-bottom red bar, back, avatar + online dot, name/status (or typing), search, call buttons, 3-dot. */
@Composable
private fun ChatHeader(
    name: String, avatarUrl: String?, online: Boolean, status: String?,
    onBack: () -> Unit, onVideo: () -> Unit, onAudio: () -> Unit, onSearch: () -> Unit, onMore: () -> Unit,
    onOpenProfile: () -> Unit = {},
    showCallButtons: Boolean = true
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
            .background(TelefamColors.PrimaryRed)
            .statusBarsPadding().padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null, tint = Color.White) }
        Box {
            Box(Modifier.size(52.dp).clip(CircleShape).border(2.dp, Color.White, CircleShape).background(Color.White.copy(alpha = 0.2f)).clickable(onClick = onOpenProfile)) {
                if (avatarUrl != null) AsyncImage(model = avatarUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (online) Box(
                Modifier.align(Alignment.BottomEnd).size(13.dp).clip(CircleShape).background(Color(0xFF34C759)).border(2.dp, TelefamColors.PrimaryRed, CircleShape)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).clickable(onClick = onOpenProfile)) {
            Text(name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp, maxLines = 1)
            if (status != null) Text(status, color = Color.White.copy(alpha = 0.92f), fontSize = 14.sp)
        }
        OutlinedCircle(Icons.Filled.Search, onSearch)
        if (showCallButtons) {
            Spacer(Modifier.width(10.dp))
            OutlinedCircle(Icons.Filled.Videocam, onVideo)
            Spacer(Modifier.width(10.dp))
            OutlinedCircle(Icons.Filled.Call, onAudio)
        }
        IconButton(onClick = onMore) { Icon(Icons.Filled.MoreVert, contentDescription = null, tint = Color.White) }
    }
}

/** Search-mode replacement header: field, match counter, up/down jump, starred filter, close. */
@Composable
private fun ChatSearchBar(
    query: String, onQueryChange: (String) -> Unit,
    starredOnly: Boolean, onToggleStarred: () -> Unit,
    matchText: String, onPrev: () -> Unit, onNext: () -> Unit, onClose: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
            .background(TelefamColors.PrimaryRed)
            .statusBarsPadding().padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, contentDescription = null, tint = Color.White) }
        Row(
            Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp))
                .background(Color.White.copy(alpha = 0.18f)).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty() && !starredOnly) {
                    Text(stringResource(Res.string.chat_search_in_chat), color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
                }
                BasicTextField(
                    value = query, onValueChange = onQueryChange, singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                    cursorBrush = SolidColor(Color.White), modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(Res.string.chat_starred_filter), fontSize = 12.sp,
            color = if (starredOnly) TelefamColors.PrimaryRed else Color.White,
            modifier = Modifier.clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = if (starredOnly) 0.95f else 0.18f))
                .clickable(onClick = onToggleStarred)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
        Text(matchText, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
        IconButton(onClick = onPrev) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, tint = Color.White) }
        IconButton(onClick = onNext) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Color.White) }
    }
}

@Composable
private fun OutlinedCircle(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).border(1.5.dp, Color.White.copy(alpha = 0.55f), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun MessageContent(
    m: CachedMessageItem, time: String, accent: Color, avatarUrl: String?, currentUserId: String, peerName: String,
    playingId: String?, playProgress: Float, replyLabel: String,
    onPlayVoice: () -> Unit, onOpenImage: () -> Unit, onPlayVideo: () -> Unit, onOpenLocation: () -> Unit,
    onVotePoll: (CachedMessageItem, PollData, List<Int>) -> Unit,
    onRetryMedia: () -> Unit,
    onReact: (String) -> Unit,
    onQuoteClick: () -> Unit,
    onContactSave: (ContactData) -> Unit, onContactOpen: (ContactData) -> Unit, onContactCall: (ContactData) -> Unit,
    onEntityClick: (MessageEntity) -> Unit = {},
    onOtpCopy: (String) -> Unit = {},
    onOpenFile: () -> Unit = {}
) {
    // Tombstone replaces everything once a message was deleted for everyone.
    if (m.deletedForEveryone) {
        DeletedTombstone(time, m.outgoing, avatarUrl)
        return
    }

    val opened = m.viewOnce && m.viewOnceOpened
    val awaitingBlob = m.mediaLocalPath == null && m.mediaRemoteId != null &&
        m.contentCategory in setOf("IMAGE", "VIDEO", "GIF", "STICKER", "VOICE", "AUDIO", "FILE")
    val reactionsSummary = Reactions.summary(m.reactions)
    val myEmojis = Reactions.decode(m.reactions).filter { (_, users) -> currentUserId in users }.keys

    // Quote + bubble stack, with reaction chips hanging off the bubble's bottom edge.
    Column(Modifier.fillMaxWidth()) {
        val bubbleContent: @Composable () -> Unit = {
            Column {
                if (m.forwarded) {
                    Row(
                        Modifier.padding(bottom = 2.dp),
                        horizontalArrangement = if (m.outgoing) Arrangement.End else Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(Res.string.chat_forwarded), fontSize = 11.sp,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                        )
                    }
                }
                when {
                    awaitingBlob -> MediaRetryBubble(m.contentCategory.lowercase(), time, m.outgoing, accent, avatarUrl, onRetry = onRetryMedia)
                    else -> RawBubble(m, time, accent, avatarUrl, currentUserId, peerName, playingId, playProgress, replyLabel, onPlayVoice, onOpenImage, onPlayVideo, onOpenLocation, onVotePoll, onQuoteClick, onContactSave, onContactOpen, onContactCall, onEntityClick, onOtpCopy, onOpenFile)
                }
            }
        }
        bubbleContent()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.outgoing) Arrangement.End else Arrangement.Start) {
            if (!m.outgoing) Spacer(Modifier.width(38.dp)) // aligns chips under the bubble, past the avatar
            ReactionChips(reactionsSummary, myEmojis, m.outgoing, accent, onToggle = onReact)
        }
    }
}

@Composable
private fun RawBubble(
    m: CachedMessageItem, time: String, accent: Color, avatarUrl: String?, currentUserId: String, peerName: String,
    playingId: String?, playProgress: Float, replyLabel: String,
    onPlayVoice: () -> Unit, onOpenImage: () -> Unit, onPlayVideo: () -> Unit, onOpenLocation: () -> Unit,
    onVotePoll: (CachedMessageItem, PollData, List<Int>) -> Unit,
    onQuoteClick: () -> Unit,
    onContactSave: (ContactData) -> Unit, onContactOpen: (ContactData) -> Unit, onContactCall: (ContactData) -> Unit,
    onEntityClick: (MessageEntity) -> Unit = {},
    onOtpCopy: (String) -> Unit = {},
    onOpenFile: () -> Unit = {}
) {
    val opened = m.viewOnce && m.viewOnceOpened
    val quote: (@Composable () -> Unit)? = m.replyPreview?.let { preview ->
        { QuoteBlock(replyLabel, preview, m.outgoing, accent, onClick = onQuoteClick) }
    }

    when (m.contentCategory) {
        "TEXT" -> QuotedBubbleWrapper(m, time, accent, avatarUrl, quote) {
            TextMessageBubble(
                m.textOrCaption ?: "",
                time, m.outgoing, accent, m.deliveryState, avatarUrl,
                editedAt = m.editedAt, readAvatarUrl = avatarUrl,
                onEntityClick = onEntityClick, onOtpCopy = onOtpCopy
            )
        }

        "FLAG" -> DateDividerChip(flagText(m.textOrCaption, peerName))

        "IMAGE" ->
            if (m.viewOnce) ViewOncePhotoBubble(m.mediaLocalPath, time, m.outgoing, opened && !m.outgoing, avatarUrl, onOpen = { if (!m.outgoing) onOpenImage() })
            else QuotedBubbleWrapper(m, time, accent, avatarUrl, quote) {
                AlignedRow(m.outgoing, avatarUrl) {
                    Box(Modifier.width(230.dp).height(170.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).clickable(onClick = onOpenImage)) {
                        AsyncImage(model = m.mediaLocalPath, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        Text(time, color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp))
                    }
                }
            }

        "GIF", "STICKER" -> QuotedBubbleWrapper(m, time, accent, avatarUrl, quote) {
            AlignedRow(m.outgoing, avatarUrl) {
                Box(
                    Modifier.width(if (m.contentCategory == "STICKER") 160.dp else 230.dp)
                        .clip(RoundedCornerShape(16.dp)).clickable(onClick = onOpenImage)
                ) {
                    AsyncImage(model = m.mediaLocalPath, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        "VIDEO" -> QuotedBubbleWrapper(m, time, accent, avatarUrl, quote) {
            VideoMessageBubble(null, (m.mediaDurationSeconds ?: 0).toInt(), time, m.outgoing, accent, m.deliveryState, avatarUrl, onPlay = onPlayVideo)
        }

        "VOICE", "AUDIO" ->
            if (opened) TextMessageBubble(stringResource(if (m.outgoing) Res.string.chat_view_once else Res.string.chat_opened), time, m.outgoing, accent, m.deliveryState, avatarUrl)
            else QuotedBubbleWrapper(m, time, accent, avatarUrl, quote) {
                VoiceNoteBubble(
                    messageId = m.id, durationSeconds = (m.mediaDurationSeconds ?: 0).toInt(),
                    playing = playingId == m.id, progress = if (playingId == m.id) playProgress else 0f,
                    timestamp = time, outgoing = m.outgoing, bubbleColor = accent, deliveryState = m.deliveryState,
                    avatarUrl = avatarUrl, onPlayPause = onPlayVoice
                )
            }

        "FILE" -> QuotedBubbleWrapper(m, time, accent, avatarUrl, quote) {
            FileBubble(FileMeta.decode(m.textOrCaption ?: ""), time, m.outgoing, accent, m.deliveryState, avatarUrl, onOpen = onOpenFile)
        }

        "EVENT" -> QuotedBubbleWrapper(m, time, accent, avatarUrl, quote) {
            EventBubble(EventData.decode(m.textOrCaption ?: ""), time, m.outgoing, accent, m.deliveryState, avatarUrl)
        }

        "POLL" -> PollData.decode(m.textOrCaption ?: "")?.let { poll ->
            AlignedRow(m.outgoing, avatarUrl) {
                PollBubble(poll, currentUserId, onVote = { sel -> onVotePoll(m, poll, sel) })
            }
        }

        "CONTACT" -> ContactData.decode(m.textOrCaption ?: "")?.let { c ->
            AlignedRow(m.outgoing, avatarUrl) {
                ContactCardBubble(c, onSave = { onContactSave(c) }, onOpen = { onContactOpen(c) }, onCall = { onContactCall(c) })
            }
        }

        "LOCATION" -> {
            val loc = LocationData.decode(m.textOrCaption ?: "")
            if (opened || loc == null) TextMessageBubble(stringResource(if (m.outgoing) Res.string.chat_view_once else Res.string.chat_opened), time, m.outgoing, accent, m.deliveryState, avatarUrl)
            else AlignedRow(m.outgoing, avatarUrl) {
                Column(Modifier.width(260.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(6.dp)) {
                    if (m.viewOnce) {
                        Box(Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onOpenLocation), contentAlignment = Alignment.Center) {
                            Text(stringResource(Res.string.attach_location) + " · " + stringResource(Res.string.chat_view_once), fontSize = 14.sp)
                        }
                    } else {
                        InAppMapView(loc, modifier = Modifier.fillMaxWidth().height(150.dp), onClick = onOpenLocation)
                    }
                    Text(time, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.align(Alignment.End).padding(top = 4.dp, end = 6.dp))
                }
            }
        }

        else -> {}
    }
}

/** Renders the quote block directly above the bubble it belongs to. */
@Composable
private fun QuotedBubbleWrapper(
    m: CachedMessageItem, time: String, accent: Color, avatarUrl: String?,
    quote: (@Composable () -> Unit)?, bubble: @Composable () -> Unit
) {
    if (quote == null) { bubble(); return }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 2.dp, start = if (m.outgoing) 0.dp else 38.dp),
            horizontalArrangement = if (m.outgoing) Arrangement.End else Arrangement.Start
        ) { Box(Modifier.widthIn(max = 240.dp)) { quote() } }
        bubble()
    }
}

/** Entity spans are now built inside TextMessageBubble via MessageParser. */
private fun buildAnnotatedText(text: String) = text

@Composable
private fun AlignedRow(outgoing: Boolean, avatarUrl: String?, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
        if (!outgoing) { AvatarThumb(avatarUrl); Spacer(Modifier.width(8.dp)) }
        content()
    }
}

@Composable
private fun flagText(code: String?, peerName: String): String {
    val parts = (code ?: "").split(":") // FLAG:<ACTION>:<ME|PEER>
    val action = parts.getOrNull(1); val who = parts.getOrNull(2)
    return when (who) {
        "ME" -> when (action) {
            "COPY" -> stringResource(Res.string.chat_flag_me_copy)
            "FORWARD" -> stringResource(Res.string.chat_flag_me_forward)
            else -> stringResource(Res.string.chat_flag_me_download)
        }
        else -> when (action) {
            "COPY" -> stringResource(Res.string.chat_flag_peer_copy, peerName)
            "FORWARD" -> stringResource(Res.string.chat_flag_peer_forward, peerName)
            else -> stringResource(Res.string.chat_flag_peer_download, peerName)
        }
    }
}


/**
 * Replaces the message composer when the conversation is blocked (either direction).
 * The block is also enforced server-side (E2EEService drops envelopes), so this bar is
 * honest UI: sending truly is impossible, not just hidden.
 */
@Composable
private fun BlockedComposer(
    message: String,
    actionLabel: String?,
    accent: Color,
    onAction: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            message,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (actionLabel != null) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onAction,
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
                border = androidx.compose.foundation.BorderStroke(1.dp, accent),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 10.dp),
                modifier = Modifier.heightIn(min = 44.dp) // touch target
            ) {
                Text(actionLabel, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}
