package com.telefam.ui.components.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.posts.FEED_REPORT_REASONS
import com.telefam.posts.FeedPostDto
import com.telefam.shared.generated.resources.Res
import com.telefam.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource

private val FeedRed = Color(0xFFD32323)

/**
 * The 3-dot overflow from the reference. Owner and viewer get different menus:
 *  - everyone: Share (system sheet with installed social apps), Share to chats,
 *    Report (structured form), Not interested, Block (not on own posts)
 *  - owner only: Edit post (prefilled editor), Restrict downloads toggle, Delete post
 * Download appears ONLY when the post's owner allows downloads AND the viewer has
 * connectivity — enabling "restrict downloads" hides it from everyone else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostActionsSheet(
    post: FeedPostDto,
    online: Boolean,
    onDismiss: () -> Unit,
    onShareExternal: () -> Unit,
    onShareToChats: () -> Unit,
    onDownload: () -> Unit,
    onReport: () -> Unit,
    onBlock: () -> Unit,
    onNotInterested: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleDownloads: (Boolean) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161616),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.3f)) }
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            ActionRow(Icons.Outlined.Share, stringResource(Res.string.feed_share)) { onShareExternal(); onDismiss() }
            ActionRow(Icons.Outlined.Send, stringResource(Res.string.feed_share_to_chats)) { onShareToChats(); onDismiss() }
            if (post.downloadsAllowed && !post.isOwner && online) {
                ActionRow(Icons.Outlined.Download, stringResource(Res.string.feed_download)) { onDownload(); onDismiss() }
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 8.dp))

            if (post.isOwner) {
                ActionRow(Icons.Outlined.Edit, stringResource(Res.string.feed_edit_post)) { onEdit(); onDismiss() }
                Row(
                    Modifier.fillMaxWidth().clickable { onToggleDownloads(!post.downloadsAllowed) }
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.FileDownloadOff, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(Res.string.feed_restrict_downloads), color = Color.White, fontSize = 16.sp)
                        Text(stringResource(Res.string.feed_restrict_downloads_desc), color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
                    }
                    Switch(
                        checked = !post.downloadsAllowed,
                        onCheckedChange = { onToggleDownloads(!it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = FeedRed)
                    )
                }
                ActionRow(Icons.Outlined.Delete, stringResource(Res.string.feed_delete_post), tint = FeedRed) { onDelete(); onDismiss() }
            } else {
                ActionRow(Icons.Outlined.Flag, stringResource(Res.string.feed_report)) { onReport(); onDismiss() }
                ActionRow(Icons.Outlined.Block, stringResource(Res.string.feed_block_user, post.displayHandle), tint = FeedRed) { onBlock(); onDismiss() }
                ActionRow(Icons.Outlined.VisibilityOff, stringResource(Res.string.feed_not_interested)) { onNotInterested(); onDismiss() }
            }
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(16.dp))
        Text(label, color = tint, fontSize = 16.sp)
    }
}

/** Report form: fixed reason set + free-text details; submits for real via FeedViewModel.report. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostReportSheet(post: FeedPostDto, submitting: Boolean, onSubmit: (reason: String, details: String?) -> Unit, onDismiss: () -> Unit) {
    var reason by remember { mutableStateOf(FEED_REPORT_REASONS.first()) }
    var details by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF161616)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text(stringResource(Res.string.feed_report_title), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(Res.string.feed_report_subtitle), color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
            Spacer(Modifier.height(16.dp))
            FEED_REPORT_REASONS.forEach { r ->
                Row(
                    Modifier.fillMaxWidth().clickable { reason = r }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = reason == r, onClick = { reason = r },
                        colors = RadioButtonDefaults.colors(selectedColor = FeedRed, unselectedColor = Color.White.copy(alpha = 0.5f)))
                    Spacer(Modifier.width(8.dp))
                    Text(r, color = Color.White, fontSize = 15.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = details, onValueChange = { details = it.take(1000) },
                label = { Text(stringResource(Res.string.feed_report_details_hint)) },
                modifier = Modifier.fillMaxWidth(), minLines = 2,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedBorderColor = FeedRed, unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                    focusedLabelColor = Color.White, unfocusedLabelColor = Color.White.copy(alpha = 0.6f)
                )
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onSubmit(reason, details.ifBlank { null }) },
                enabled = !submitting,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = FeedRed),
                shape = RoundedCornerShape(25.dp)
            ) {
                if (submitting) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                else Text(stringResource(Res.string.feed_report_submit), fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Minimal conversation target for "Share to chats". */
data class ShareTarget(val peerId: String, val displayName: String)

/** Share-to-chats inbox picker: real conversation list; selecting sends the post link as a chat message. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareToChatsSheet(targets: List<ShareTarget>, onPick: (ShareTarget) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF161616)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            Text(
                stringResource(Res.string.feed_share_to_chats),
                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            if (targets.isEmpty()) {
                Text(
                    stringResource(Res.string.feed_share_no_chats),
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(targets, key = { it.peerId }) { t ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(t) }.padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(44.dp).clip(CircleShape).background(FeedRed.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) { Text(t.displayName.take(1).uppercase(), color = FeedRed, fontWeight = FontWeight.Bold) }
                            Spacer(Modifier.width(14.dp))
                            Text(t.displayName, color = Color.White, fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    }
}
