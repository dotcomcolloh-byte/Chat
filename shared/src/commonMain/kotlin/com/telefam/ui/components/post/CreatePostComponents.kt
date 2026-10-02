package com.telefam.ui.components.post

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.telefam.posts.CommentingOption
import com.telefam.posts.EmbedOption
import com.telefam.posts.PostPrivacy
import com.telefam.posts.SongResult
import com.telefam.posts.TagFriend
import com.telefam.ui.theme.TelefamColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One tappable settings row in the Create Post list (icon + label + trailing value + chevron). */
@Composable
fun PostOptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String?,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = TelefamColors.TextDark, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TelefamColors.TextDark)
        Spacer(Modifier.weight(1f))
        if (!value.isNullOrBlank()) {
            Text(value, color = TelefamColors.TextMuted, fontSize = 14.sp, maxLines = 1)
            Spacer(Modifier.width(6.dp))
        }
        Text("›", color = TelefamColors.TextMuted, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

/** Description editor — multiline dialog. */
@Composable
fun DescriptionDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Description", fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { if (it.length <= 2000) text = it },
                placeholder = { Text("Add description...") },
                minLines = 4, maxLines = 8,
                supportingText = { Text("${text.length}/2000") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text); onDismiss() }) { Text("Save", color = TelefamColors.PrimaryRed, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Hashtags editor — space/comma separated, normalized to #tags. */
@Composable
fun HashtagsDialog(initial: List<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    var text by remember { mutableStateOf(initial.joinToString(" ") { "#$it" }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add hashtags", fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text("#travel #nature #adventure") },
                minLines = 2, modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(text.split(Regex("[\\s,]+")).map { it.removePrefix("#") }.filter { it.isNotBlank() })
                onDismiss()
            }) { Text("Save", color = TelefamColors.PrimaryRed, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Generic single-choice sheet used for Commenting / Privacy / Embed-iframe. */
@Composable
fun <T> ChoiceSheet(
    title: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                options.forEach { opt ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(opt); onDismiss() }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (opt == selected) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = if (opt == selected) TelefamColors.PrimaryRed else TelefamColors.TextMuted
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(labelOf(opt), fontSize = 16.sp)
                    }
                }
            }
        },
        confirmButton = {}
    )
}

/** Tag friends sheet — search friends by name or username, multi-select with chips. */
@Composable
fun TagFriendsSheet(
    selected: List<TagFriend>,
    search: suspend (String) -> List<TagFriend>,
    onToggle: (TagFriend) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<TagFriend>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query) {
        delay(300) // debounce
        results = if (query.isBlank()) emptyList() else search(query)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tag friends", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                if (selected.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        selected.forEach { f ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = TelefamColors.PrimaryRed.copy(alpha = 0.12f)
                            ) {
                                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(f.displayName, fontSize = 13.sp, color = TelefamColors.PrimaryRed, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.width(4.dp))
                                    Icon(Icons.Filled.Close, null, tint = TelefamColors.PrimaryRed,
                                        modifier = Modifier.size(14.dp).clickable { onToggle(f) })
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search by name or @username") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn {
                    items(results, key = { it.userId }) { friend ->
                        val isSel = selected.any { it.userId == friend.userId }
                        Row(
                            Modifier.fillMaxWidth().clickable { onToggle(friend) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).background(TelefamColors.PrimaryRed.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) { Text(friend.displayName.take(1).uppercase(), color = TelefamColors.PrimaryRed, fontWeight = FontWeight.Bold) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(friend.displayName, fontWeight = FontWeight.SemiBold)
                                friend.username?.let { Text("@$it", fontSize = 12.sp, color = TelefamColors.TextMuted) }
                            }
                            Icon(
                                if (isSel) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                                null, tint = if (isSel) TelefamColors.PrimaryRed else TelefamColors.TextMuted
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done", color = TelefamColors.PrimaryRed, fontWeight = FontWeight.Bold) }
        }
    )
}

/** Song picker — public iTunes catalog search with 30s preview snippets. */
@Composable
fun SongPickerSheet(
    current: SongResult?,
    searchApi: com.telefam.posts.SongSearchApi,
    onSelect: (SongResult?) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SongResult>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        delay(400)
        if (query.isNotBlank()) {
            loading = true
            results = searchApi.search(query)
            loading = false
        } else results = emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add song", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    placeholder = { Text("Search songs or artists") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                if (current != null) {
                    TextButton(onClick = { onSelect(null); onDismiss() }) {
                        Text("Remove current song (${current.trackName})", color = TelefamColors.PrimaryRed)
                    }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = TelefamColors.PrimaryRed)
                LazyColumn {
                    items(results, key = { it.trackId }) { song ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onSelect(song); onDismiss() }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = song.artworkUrl100, contentDescription = null,
                                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(song.trackName, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(song.artistName, fontSize = 12.sp, color = TelefamColors.TextMuted, maxLines = 1)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}
