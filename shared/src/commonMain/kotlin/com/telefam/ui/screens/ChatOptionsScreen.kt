package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.shared.generated.resources.*
import com.telefam.ui.theme.TelefamColors
import org.jetbrains.compose.resources.stringResource

data class ChatOptionsSummary(
    val muteLabel: String,
    val disappearingLabel: String,
    val isBlocked: Boolean = false
)

@Composable
fun ChatOptionsScreen(
    summary: ChatOptionsSummary,
    onClose: () -> Unit,
    onVideoCallClick: () -> Unit,
    onAudioCallClick: () -> Unit,
    onViewProfileClick: () -> Unit,
    onMediaLinksDocsClick: () -> Unit,
    onMuteClick: () -> Unit,
    onBlockClick: () -> Unit,
    onReportClick: () -> Unit,
    onDisappearingMessagesClick: () -> Unit,
    onClearChatClick: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier.fillMaxWidth().background(TelefamColors.PrimaryRed)
                .padding(top = 44.dp, bottom = 20.dp, start = 16.dp, end = 16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.back), tint = TelefamColors.White) }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircleIconButton(Icons.Filled.Videocam) { onVideoCallClick() }
                Spacer(Modifier.width(28.dp))
                CircleIconButton(Icons.Filled.Call) { onAudioCallClick() }
            }
        }

        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            OptionRow(Icons.Filled.Person, stringResource(Res.string.menu_view_profile), null, onViewProfileClick)
            Divider()
            OptionRow(Icons.Filled.PermMedia, stringResource(Res.string.menu_media_links_docs), null, onMediaLinksDocsClick)
            Divider()
            OptionRow(Icons.Outlined.NotificationsOff, stringResource(Res.string.menu_mute), summary.muteLabel, onMuteClick)
            Divider()
            OptionRow(
                Icons.Filled.Block,
                stringResource(if (summary.isBlocked) Res.string.menu_unblock else Res.string.menu_block),
                stringResource(Res.string.menu_block_desc),
                onBlockClick
            )
            Divider()
            OptionRow(Icons.Filled.Report, stringResource(Res.string.menu_report), stringResource(Res.string.menu_report_desc), onReportClick)
            Divider()
            OptionRow(Icons.Filled.Timer, stringResource(Res.string.menu_disappearing_messages), summary.disappearingLabel, onDisappearingMessagesClick)
            Divider()
            OptionRow(Icons.Filled.Delete, stringResource(Res.string.menu_clear_chat), stringResource(Res.string.menu_clear_chat_desc), onClearChatClick)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CircleIconButton(icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(CircleShape)
            .background(TelefamColors.White.copy(alpha = 0.15f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = TelefamColors.White, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun OptionRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = TelefamColors.PrimaryRed, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(20.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
        }
    }
}
