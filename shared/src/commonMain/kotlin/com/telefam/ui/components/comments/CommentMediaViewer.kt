package com.telefam.ui.components.comments

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.telefam.posts.COMMENT_REPORT_REASONS
import com.telefam.posts.rememberPostDownloader
import com.telefam.ui.theme.TelefamColors
import kotlinx.coroutines.launch

/**
 * Full-screen viewer for comment media (photo / sticker / GIF). Tapping the backdrop
 * dismisses; the download button saves via the platform downloader; long-press on the
 * media itself offers the same actions (Copy/Download/Report) as the comment row.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CommentMediaViewer(
    url: String,
    isAnimated: Boolean,
    onDismiss: () -> Unit,
    onDownloadEvent: (String) -> Unit,
    onReport: (() -> Unit)? = null
) {
    val downloader = rememberPostDownloader(onDownloadEvent)
    var menuOpen by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f))) {
            Box(Modifier.align(Alignment.Center)) {
                AsyncImage(
                    model = url,
                    contentDescription = "Full media",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .combinedClickable(onClick = {}, onLongClick = { menuOpen = true }),
                    contentScale = ContentScale.Fit
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Download") },
                        onClick = {
                            menuOpen = false
                            downloader(url, "telefam-comment-media")
                        }
                    )
                    if (onReport != null) {
                        DropdownMenuItem(text = { Text("Report") }, onClick = { menuOpen = false; onReport() })
                    }
                }
            }

            Row(
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f))
                        .clickable { downloader(url, "telefam-comment-media") },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Download, contentDescription = "Download", tint = Color.White)
                }
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }
}

/** Structured report form (reason picker + optional details) — matches the post report flow. */
@Composable
fun CommentReportDialog(
    submitting: Boolean,
    onSubmit: (reason: String, details: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf<String?>(null) }
    var details by remember { mutableStateOf("") }

    Dialog(onDismissRequest = { if (!submitting) onDismiss() }) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(20.dp)
        ) {
            Text("Report comment", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text("Your report is anonymous. Our moderation team reviews every report.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))

            COMMENT_REPORT_REASONS.forEach { reason ->
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { selected = reason }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selected == reason, onClick = { selected = reason })
                    Spacer(Modifier.width(6.dp))
                    Text(reason, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = details,
                onValueChange = { if (it.length <= 1000) details = it },
                placeholder = { Text("Add details (optional)") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                maxLines = 4
            )

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, enabled = !submitting) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { selected?.let { onSubmit(it, details.trim().ifBlank { null }) } },
                    enabled = selected != null && !submitting,
                    colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed)
                ) {
                    if (submitting) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    else Text("Submit report", color = Color.White)
                }
            }
        }
    }
}
