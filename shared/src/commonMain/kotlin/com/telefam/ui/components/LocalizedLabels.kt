package com.telefam.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.material.icons.filled.*
import com.telefam.data.model.AccessLevel
import com.telefam.data.model.BubbleColour
import com.telefam.data.model.ChatsTheme
import com.telefam.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun localizedLabel(level: AccessLevel): String = when (level) {
    AccessLevel.ANYONE -> stringResource(Res.string.access_anyone)
    AccessLevel.CONTACTS -> stringResource(Res.string.access_friends)
    AccessLevel.NOBODY -> stringResource(Res.string.access_nobody)
}

/** Per-setting phrasing (e.g. "Anyone can send you message requests.") matching the reference exactly, keyed by the same field id used across the app. */
@Composable
fun localizedAccessDescriptions(field: String): List<String> {
    val (anyoneRes, friendsRes, nobodyRes) = when (field) {
        "messageRequests" -> Triple(Res.string.desc_requests_anyone, Res.string.desc_requests_friends, Res.string.desc_requests_nobody)
        "whoCanCallMe" -> Triple(Res.string.desc_call_anyone, Res.string.desc_call_friends, Res.string.desc_call_nobody)
        "whoCanScreenshotChats" -> Triple(Res.string.desc_screenshot_anyone, Res.string.desc_screenshot_friends, Res.string.desc_screenshot_nobody)
        "whoCanShareChats" -> Triple(Res.string.desc_share_anyone, Res.string.desc_share_friends, Res.string.desc_share_nobody)
        "whoCanCopyMessages" -> Triple(Res.string.desc_copy_anyone, Res.string.desc_copy_friends, Res.string.desc_copy_nobody)
        "whoCanSeeLastSeen" -> Triple(Res.string.desc_last_seen_anyone, Res.string.desc_last_seen_friends, Res.string.desc_last_seen_nobody)
        else -> Triple(Res.string.desc_download_anyone, Res.string.desc_download_friends, Res.string.desc_download_nobody)
    }
    return listOf(stringResource(anyoneRes), stringResource(friendsRes), stringResource(nobodyRes))
}

@Composable
fun localizedTitle(field: String): String = when (field) {
    "messageRequests" -> stringResource(Res.string.setting_message_requests_title)
    "whoCanCallMe" -> stringResource(Res.string.setting_call_title)
    "whoCanScreenshotChats" -> stringResource(Res.string.setting_screenshot_title)
    "whoCanShareChats" -> stringResource(Res.string.setting_share_title)
    "whoCanCopyMessages" -> stringResource(Res.string.setting_copy_title)
    "whoCanSeeLastSeen" -> stringResource(Res.string.privacy_last_seen_title)
    else -> stringResource(Res.string.setting_download_title)
}

@Composable
fun localizedDescription(field: String): String = when (field) {
    "messageRequests" -> stringResource(Res.string.setting_message_requests_desc)
    "whoCanCallMe" -> stringResource(Res.string.setting_call_desc)
    "whoCanScreenshotChats" -> stringResource(Res.string.setting_screenshot_desc)
    "whoCanShareChats" -> stringResource(Res.string.setting_share_desc)
    "whoCanCopyMessages" -> stringResource(Res.string.setting_copy_desc)
    "whoCanSeeLastSeen" -> stringResource(Res.string.privacy_last_seen_desc)
    else -> stringResource(Res.string.setting_download_desc)
}

@Composable
fun localizedLabel(theme: ChatsTheme): String = when (theme) {
    ChatsTheme.LIGHT -> stringResource(Res.string.theme_light)
    ChatsTheme.DARK -> stringResource(Res.string.theme_dark)
    ChatsTheme.BLUE -> stringResource(Res.string.theme_blue)
    ChatsTheme.PURPLE -> stringResource(Res.string.theme_purple)
    ChatsTheme.GREEN -> stringResource(Res.string.theme_green)
    ChatsTheme.PINK -> stringResource(Res.string.theme_pink)
}

@Composable
fun localizedLabel(colour: BubbleColour): String = when (colour) {
    BubbleColour.RED -> stringResource(Res.string.bubble_red)
    BubbleColour.BLUE -> stringResource(Res.string.bubble_blue)
    BubbleColour.PURPLE -> stringResource(Res.string.bubble_purple)
    BubbleColour.GREEN -> stringResource(Res.string.bubble_green)
    BubbleColour.ORANGE -> stringResource(Res.string.bubble_orange)
    BubbleColour.GREY -> stringResource(Res.string.bubble_grey)
}

/** Icon per privacy-setting field id, shared by the main list and the option screen so they always match. */
fun iconForPrivacyField(field: String): androidx.compose.ui.graphics.vector.ImageVector = when (field) {
    "messageRequests" -> androidx.compose.material.icons.Icons.Filled.Chat
    "whoCanCallMe" -> androidx.compose.material.icons.Icons.Filled.Call
    "whoCanScreenshotChats" -> androidx.compose.material.icons.Icons.Filled.ScreenshotMonitor
    "whoCanShareChats" -> androidx.compose.material.icons.Icons.Filled.Share
    "whoCanCopyMessages" -> androidx.compose.material.icons.Icons.Filled.ContentCopy
    "whoCanSeeLastSeen" -> androidx.compose.material.icons.Icons.Filled.Visibility
    else -> androidx.compose.material.icons.Icons.Filled.Download
}
