package com.telefam.ui.components

import androidx.compose.runtime.Composable
import com.telefam.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Header status line for a peer: "online", "last seen 5 min ago", ... or null when nothing may/should be shown
 * (hidden by the peer's privacy settings, or no presence information yet).
 */
@Composable
fun presenceLabel(online: Boolean, lastSeenEpochMillis: Long?, nowEpochMillis: Long): String? {
    if (online) return stringResource(Res.string.presence_online)
    val seen = lastSeenEpochMillis ?: return null
    val diff = (nowEpochMillis - seen).coerceAtLeast(0)
    val minutes = (diff / 60_000).toInt()
    val hours = (diff / 3_600_000).toInt()
    val days = (diff / 86_400_000).toInt()
    return when {
        minutes < 1 -> stringResource(Res.string.presence_last_seen_just_now)
        minutes < 60 -> stringResource(Res.string.presence_last_seen_minutes, minutes)
        hours < 24 -> stringResource(Res.string.presence_last_seen_hours, hours)
        days == 1 -> stringResource(Res.string.presence_last_seen_yesterday)
        else -> stringResource(Res.string.presence_last_seen_days, days)
    }
}
