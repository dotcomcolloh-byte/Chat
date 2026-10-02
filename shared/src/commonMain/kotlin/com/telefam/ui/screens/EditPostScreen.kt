package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.data.api.FeedApi
import com.telefam.posts.CommentingOption
import com.telefam.posts.FeedEditPostRequest
import com.telefam.posts.FeedPostDto
import com.telefam.posts.FeedViewModel
import com.telefam.posts.PostPrivacy
import com.telefam.shared.generated.resources.Res
import com.telefam.shared.generated.resources.*
import com.telefam.ui.theme.TelefamColors
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Edit post — opened from the owner's 3-dot menu. Loads the real post server-side
 * and pre-fills every editable field (video itself is immutable: it's already been
 * sanitized/re-encoded, so editing re-upload is a new post by design).
 */
@Composable
fun EditPostScreen(
    postId: String,
    feedApi: FeedApi,
    viewModel: FeedViewModel?,
    onClose: () -> Unit,
    onSaved: () -> Unit
) {
    var post by remember { mutableStateOf<FeedPostDto?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val saveFailedText = stringResource(Res.string.feed_edit_save_failed)

    var description by remember { mutableStateOf("") }
    var hashtagsText by remember { mutableStateOf("") }
    var commenting by remember { mutableStateOf(CommentingOption.EVERYONE) }
    var privacy by remember { mutableStateOf(PostPrivacy.PUBLIC) }
    var embedAllowed by remember { mutableStateOf(true) }
    var downloadsAllowed by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(postId) {
        try {
            val p = feedApi.getOwnedPost(postId)
            post = p
            description = p.description ?: ""
            hashtagsText = p.hashtags.joinToString(" ") { "#$it" }
            commenting = runCatching { CommentingOption.valueOf(p.commenting) }.getOrDefault(CommentingOption.EVERYONE)
            privacy = runCatching { PostPrivacy.valueOf(p.privacy) }.getOrDefault(PostPrivacy.PUBLIC)
            downloadsAllowed = p.downloadsAllowed
        } catch (e: Exception) {
            loadFailed = true
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        // Red header, matching Create Post
        Box(
            Modifier.fillMaxWidth().background(TelefamColors.PrimaryRed)
                .padding(horizontal = 8.dp, vertical = 14.dp)
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.cancel), tint = TelefamColors.White)
            }
            Text(
                stringResource(Res.string.feed_edit_title),
                color = TelefamColors.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        when {
            loadFailed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.feed_load_failed), color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
            }
            post == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TelefamColors.PrimaryRed)
            }
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(2000) },
                    label = { Text(stringResource(Res.string.feed_edit_description)) },
                    modifier = Modifier.fillMaxWidth(), minLines = 3
                )
                OutlinedTextField(
                    value = hashtagsText,
                    onValueChange = { hashtagsText = it },
                    label = { Text(stringResource(Res.string.feed_edit_hashtags)) },
                    supportingText = { Text(stringResource(Res.string.feed_edit_hashtags_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )

                Text(stringResource(Res.string.feed_edit_commenting), fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CommentingOption.entries.forEach { opt ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = commenting == opt, onClick = { commenting = opt },
                                colors = RadioButtonDefaults.colors(selectedColor = TelefamColors.PrimaryRed))
                            Text(opt.label)
                        }
                    }
                }

                Text(stringResource(Res.string.feed_edit_privacy), fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PostPrivacy.entries.forEach { opt ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = privacy == opt, onClick = { privacy = opt },
                                colors = RadioButtonDefaults.colors(selectedColor = TelefamColors.PrimaryRed))
                            Text(opt.label)
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(Res.string.feed_edit_embed), fontWeight = FontWeight.SemiBold)
                    }
                    Switch(checked = embedAllowed, onCheckedChange = { embedAllowed = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = TelefamColors.PrimaryRed))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(Res.string.feed_restrict_downloads), fontWeight = FontWeight.SemiBold)
                        Text(stringResource(Res.string.feed_restrict_downloads_desc),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                    }
                    Switch(checked = !downloadsAllowed, onCheckedChange = { downloadsAllowed = !it },
                        colors = SwitchDefaults.colors(checkedTrackColor = TelefamColors.PrimaryRed))
                }

                error?.let { Text(it, color = TelefamColors.PrimaryRed) }

                Button(
                    onClick = {
                        saving = true; error = null
                        val req = FeedEditPostRequest(
                            description = description.ifBlank { null },
                            hashtags = hashtagsText.split(' ', ',').map { it.removePrefix("#") }.filter { it.isNotBlank() },
                            commenting = commenting.name,
                            privacy = privacy.name,
                            embedAllowed = embedAllowed,
                            downloadsAllowed = downloadsAllowed
                        )
                        scope.launch {
                            if (viewModel != null) {
                                viewModel.editPost(postId, req) { ok ->
                                    saving = false
                                    if (ok) onSaved() else error = saveFailedText
                                }
                            } else {
                                val ok = runCatching { feedApi.editPost(postId, req).status.value in 200..299 }.getOrDefault(false)
                                saving = false
                                if (ok) onSaved() else error = saveFailedText
                            }
                        }
                    },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
                    shape = RoundedCornerShape(26.dp)
                ) {
                    if (saving) CircularProgressIndicator(Modifier.size(20.dp), color = TelefamColors.White, strokeWidth = 2.dp)
                    else Text(stringResource(Res.string.feed_edit_save), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}
