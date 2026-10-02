package com.telefam.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.chat.PickKind
import com.telefam.chat.rememberMediaPicker
import com.telefam.posts.*
import com.telefam.ui.components.VideoPlayerDialog
import com.telefam.ui.components.post.*
import com.telefam.ui.theme.TelefamColors

private fun fmtMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}

/**
 * Create Post — matches the reference design exactly:
 * red header (X + "Create Post"), white rounded-top sheet containing the video
 * preview card (play overlay, remove X, duration badge), Upload video / Upload
 * thumbnail tiles, Trim video card (iframe strip + duration), then option rows:
 * Description, Tags (friends search by name/username), Commenting, Privacy,
 * Add song (public iTunes catalog), Add hashtags, Embed / iframe — and the red
 * Post button. During upload the button turns into a live progress card:
 * Uploading % -> Processing -> Uploaded.
 */
@Composable
fun CreatePostScreen(
    viewModel: CreatePostViewModel,
    onClose: () -> Unit,
    onOpenTrim: () -> Unit,
    onPosted: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val isPosting = state.upload.stage !in setOf(UploadStage.IDLE, UploadStage.FAILED)
    var showPlayer by remember { mutableStateOf(false) }
    var showDescription by remember { mutableStateOf(false) }
    var showHashtags by remember { mutableStateOf(false) }
    var showTags by remember { mutableStateOf(false) }
    var showCommenting by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    var showSong by remember { mutableStateOf(false) }
    var showEmbed by remember { mutableStateOf(false) }
    var postError by remember { mutableStateOf<String?>(null) }

    val videoPicker = rememberMediaPicker(PickKind.VIDEO) { file -> file?.let(viewModel::pickVideo) }
    val thumbPicker = rememberMediaPicker(PickKind.IMAGE) { file -> file?.let(viewModel::pickThumbnail) }

    Box(Modifier.fillMaxSize().background(TelefamColors.PrimaryRed)) {
        Column(Modifier.fillMaxSize()) {
            // --- Red header ---
            Box(
                Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(vertical = 12.dp, horizontal = 8.dp)
            ) {
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = TelefamColors.White, modifier = Modifier.size(28.dp))
                }
                Text(
                    "Create Post", color = TelefamColors.White, fontWeight = FontWeight.Bold,
                    fontSize = 22.sp, modifier = Modifier.align(Alignment.Center)
                )
            }

            // --- White rounded-top body ---
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // --- Video preview card ---
                    state.video?.let { video ->
                        Box(
                            Modifier.fillMaxWidth().height(180.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { showPlayer = true }
                        ) {
                            state.frameStrip.getOrNull(state.frameStrip.size / 2)?.let { frame ->
                                Image(
                                    bitmap = frame, contentDescription = null,
                                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                                )
                            }
                            Box(
                                Modifier.align(Alignment.Center).size(56.dp).clip(CircleShape)
                                    .background(TelefamColors.TextDark.copy(alpha = 0.75f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.PlayArrow, "Play", tint = TelefamColors.White, modifier = Modifier.size(34.dp))
                            }
                            IconButton(
                                onClick = { if (!isPosting) viewModel.clearVideo() },
                                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                                    .size(30.dp).clip(CircleShape).background(TelefamColors.TextDark.copy(alpha = 0.7f))
                            ) { Icon(Icons.Filled.Close, "Remove video", tint = TelefamColors.White, modifier = Modifier.size(16.dp)) }
                            Surface(
                                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                                shape = RoundedCornerShape(6.dp),
                                color = TelefamColors.TextDark.copy(alpha = 0.7f)
                            ) {
                                Text(
                                    fmtMs(state.durationMs), color = TelefamColors.White, fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    // --- Upload tiles ---
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        UploadTile(
                            Modifier.weight(1f),
                            icon = { Icon(Icons.Outlined.Videocam, null, tint = TelefamColors.PrimaryRed, modifier = Modifier.size(30.dp)) },
                            label = "Upload video",
                            onClick = { if (!isPosting) videoPicker() }
                        )
                        UploadTile(
                            Modifier.weight(1f),
                            icon = { Icon(Icons.Outlined.Image, null, tint = TelefamColors.PrimaryRed, modifier = Modifier.size(30.dp)) },
                            label = if (state.thumbnail == null) "Upload thumbnail" else "Thumbnail ready",
                            onClick = { if (!isPosting) thumbPicker() }
                        )
                    }

                    // --- Trim video card ---
                    if (state.video != null) {
                        Column(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                .clickable(enabled = !isPosting, onClick = onOpenTrim)
                                .padding(14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.ContentCut, null, tint = TelefamColors.PrimaryRed, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(12.dp))
                                Text("Trim video", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TelefamColors.TextDark)
                                Spacer(Modifier.weight(1f))
                                Text("›", color = TelefamColors.TextMuted, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // mini iframe strip with red handles
                                Row(
                                    Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Box(Modifier.width(10.dp).fillMaxHeight().background(TelefamColors.PrimaryRed))
                                    state.frameStrip.take(8).forEach { f ->
                                        Image(f, null, Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop)
                                    }
                                    Box(Modifier.width(10.dp).fillMaxHeight().background(TelefamColors.PrimaryRed))
                                }
                                Spacer(Modifier.width(14.dp))
                                Column {
                                    Text("Duration", color = TelefamColors.TextMuted, fontSize = 12.sp)
                                    Text(fmtMs(if (state.trimEndMs > 0) state.trimEndMs - state.trimStartMs else state.durationMs),
                                        fontWeight = FontWeight.Bold, fontSize = 20.sp, color = TelefamColors.TextDark)
                                    Text(
                                        if (state.trimStartMs == 0L && state.trimEndMs == 0L) "(Full video)" else "(Trimmed)",
                                        color = TelefamColors.TextMuted, fontSize = 12.sp
                                    )
                                }
                            }
                            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(fmtMs(state.trimStartMs), color = TelefamColors.TextMuted, fontSize = 12.sp)
                                Text(fmtMs(if (state.trimEndMs > 0) state.trimEndMs else state.durationMs),
                                    color = TelefamColors.TextMuted, fontSize = 12.sp)
                            }
                        }
                    }

                    // --- Option rows (exactly as the reference screen) ---
                    PostOptionRow(Icons.Outlined.Description, "Description",
                        state.description.ifBlank { "Add description..." }.take(24), onClick = { if (!isPosting) showDescription = true })
                    PostOptionRow(Icons.Outlined.Tag, "Tags",
                        if (state.taggedFriends.isEmpty()) "Add tags..." else "${state.taggedFriends.size} tagged",
                        onClick = { if (!isPosting) showTags = true })
                    PostOptionRow(Icons.Outlined.ChatBubbleOutline, "Commenting", state.commenting.label) { if (!isPosting) showCommenting = true }
                    PostOptionRow(Icons.Outlined.Public, "Privacy", state.privacy.label) { if (!isPosting) showPrivacy = true }
                    PostOptionRow(
                        Icons.Outlined.Lock,
                        "Subscribers only",
                        if (state.subscriberOnly) "Enabled" else "Off"
                    ) { if (!isPosting) viewModel.setSubscriberOnly(!state.subscriberOnly) }
                    PostOptionRow(Icons.Outlined.MusicNote, "Add song",
                        state.song?.trackName ?: "None") { if (!isPosting) showSong = true }
                    PostOptionRow(Icons.Outlined.Tag, "Add hashtags",
                        state.hashtags.take(2).joinToString(" ") { "#$it" }) { if (!isPosting) showHashtags = true }
                    PostOptionRow(Icons.Outlined.Code, "Embed / iframe", state.embed.label) { if (!isPosting) showEmbed = true }

                    postError?.let {
                        Text(it, color = TelefamColors.PrimaryRed, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
                    }

                    // --- Post button / progress ---
                    when (state.upload.stage) {
                        UploadStage.IDLE, UploadStage.FAILED -> {
                            Button(
                                enabled = state.video != null && !isPosting,
                                onClick = {
                                    postError = null
                                    if (state.video == null) {
                                        postError = "Pick a video first"
                                    } else {
                                        viewModel.post { ok, err -> if (ok) onPosted() else postError = err }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
                                shape = RoundedCornerShape(28.dp),
                                modifier = Modifier.fillMaxWidth().height(54.dp)
                            ) { Text("Post", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
                        }
                        UploadStage.COMPRESSING -> ProgressCard("Uploading…", null)
                        UploadStage.UPLOADING -> ProgressCard(
                            if (state.upload.fraction > 0f) "Uploading… ${(state.upload.fraction * 100).toInt()}%" else "Uploading…",
                            state.upload.fraction
                        )
                        UploadStage.PROCESSING -> ProgressCard("Processing…", null)
                        UploadStage.UPLOADED -> ProgressCard("Uploaded", 1f)
                    }
                    Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
                }
            }
        }
    }

    // --- Dialogs & sheets ---
    if (showPlayer && state.video != null) {
        VideoPlayerDialog(filePath = state.video!!.path, onDismiss = { showPlayer = false })
    }
    if (showDescription) DescriptionDialog(state.description, { showDescription = false }, viewModel::setDescription)
    if (showHashtags) HashtagsDialog(state.hashtags, { showHashtags = false }, viewModel::setHashtags)
    if (showTags) TagFriendsSheet(
        selected = state.taggedFriends,
        search = viewModel::searchFriends,
        onToggle = { f ->
            if (state.taggedFriends.any { it.userId == f.userId }) viewModel.removeTaggedFriend(f.userId)
            else viewModel.addTaggedFriend(f)
        },
        onDismiss = { showTags = false }
    )
    if (showCommenting) ChoiceSheet("Who can comment", CommentingOption.entries, state.commenting, { it.label }, viewModel::setCommenting) { showCommenting = false }
    if (showPrivacy) ChoiceSheet("Who can see this post", PostPrivacy.entries, state.privacy, { it.label }, viewModel::setPrivacy) { showPrivacy = false }
    if (showEmbed) ChoiceSheet("Embed / iframe", EmbedOption.entries, state.embed, { it.label }, viewModel::setEmbed) { showEmbed = false }
    if (showSong) SongPickerSheet(state.song, viewModel.songSearch, viewModel::setSong) { showSong = false }
}

@Composable
private fun UploadTile(modifier: Modifier, icon: @Composable () -> Unit, label: String, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp, horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        icon()
        Spacer(Modifier.width(10.dp))
        Text(label, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TelefamColors.TextDark)
    }
}

@Composable
private fun ProgressCard(label: String, fraction: Float?) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(label, fontWeight = FontWeight.Bold, color = TelefamColors.TextDark)
            Spacer(Modifier.height(10.dp))
            if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = TelefamColors.PrimaryRed)
            else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = TelefamColors.PrimaryRed)
        }
    }
}
