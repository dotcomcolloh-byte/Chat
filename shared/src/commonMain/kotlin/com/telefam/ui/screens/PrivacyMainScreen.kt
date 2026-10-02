package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.data.model.AccessLevel
import com.telefam.data.model.BubbleColour
import com.telefam.data.model.ChatsTheme
import com.telefam.shared.generated.resources.*
import com.telefam.ui.components.PrivacySettingRow
import com.telefam.ui.components.SettingsTopBar
import com.telefam.ui.components.localizedLabel
import org.jetbrains.compose.resources.stringResource

data class PrivacyMainState(
    val messageRequests: AccessLevel = AccessLevel.ANYONE,
    val whoCanCallMe: AccessLevel = AccessLevel.ANYONE,
    val whoCanScreenshotChats: AccessLevel = AccessLevel.ANYONE,
    val whoCanShareChats: AccessLevel = AccessLevel.ANYONE,
    val whoCanCopyMessages: AccessLevel = AccessLevel.ANYONE,
    val whoCanDownloadMedia: AccessLevel = AccessLevel.ANYONE,
    val whoCanSeeLastSeen: AccessLevel = AccessLevel.ANYONE,
    val chatsTheme: ChatsTheme = ChatsTheme.LIGHT,
    val messageBubbleColour: BubbleColour = BubbleColour.RED
)

@Composable
fun PrivacyMainScreen(
    state: PrivacyMainState,
    onBackClick: () -> Unit,
    onOpenMessageRequests: () -> Unit,
    onOpenCallMe: () -> Unit,
    onOpenScreenshot: () -> Unit,
    onOpenShare: () -> Unit,
    onOpenCopy: () -> Unit,
    onOpenDownload: () -> Unit,
    onOpenLastSeen: () -> Unit,
    onOpenTheme: () -> Unit,
    onOpenBubbleColour: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SettingsTopBar(title = stringResource(Res.string.privacy_title), onBackClick = onBackClick)

        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(stringResource(Res.string.privacy_title), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(Res.string.privacy_subtitle), color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
            }

            PrivacySettingRow(Icons.Filled.Chat, stringResource(Res.string.setting_message_requests_title), stringResource(Res.string.setting_message_requests_desc), localizedLabel(state.messageRequests), onOpenMessageRequests)
            Divider()
            PrivacySettingRow(Icons.Filled.Call, stringResource(Res.string.setting_call_title), stringResource(Res.string.setting_call_desc), localizedLabel(state.whoCanCallMe), onOpenCallMe)
            Divider()
            PrivacySettingRow(Icons.Filled.ScreenshotMonitor, stringResource(Res.string.setting_screenshot_title), stringResource(Res.string.setting_screenshot_desc), localizedLabel(state.whoCanScreenshotChats), onOpenScreenshot)
            Divider()
            PrivacySettingRow(Icons.Filled.Share, stringResource(Res.string.setting_share_title), stringResource(Res.string.setting_share_desc), localizedLabel(state.whoCanShareChats), onOpenShare)
            Divider()
            PrivacySettingRow(Icons.Filled.ContentCopy, stringResource(Res.string.setting_copy_title), stringResource(Res.string.setting_copy_desc), localizedLabel(state.whoCanCopyMessages), onOpenCopy)
            Divider()
            PrivacySettingRow(Icons.Filled.Download, stringResource(Res.string.setting_download_title), stringResource(Res.string.setting_download_desc), localizedLabel(state.whoCanDownloadMedia), onOpenDownload)
            Divider()
            PrivacySettingRow(Icons.Filled.Visibility, stringResource(Res.string.privacy_last_seen_title), stringResource(Res.string.privacy_last_seen_desc), localizedLabel(state.whoCanSeeLastSeen), onOpenLastSeen)
            Divider()
            PrivacySettingRow(Icons.Filled.Palette, stringResource(Res.string.setting_theme_title), stringResource(Res.string.setting_theme_desc), localizedLabel(state.chatsTheme), onOpenTheme)
            Divider()
            PrivacySettingRow(Icons.Filled.ColorLens, stringResource(Res.string.setting_bubble_title), stringResource(Res.string.setting_bubble_desc), localizedLabel(state.messageBubbleColour), onOpenBubbleColour)

            Spacer(Modifier.height(24.dp))
        }
    }
}
