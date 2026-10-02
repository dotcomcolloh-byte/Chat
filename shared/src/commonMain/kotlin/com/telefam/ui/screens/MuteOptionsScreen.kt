package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.telefam.shared.generated.resources.*
import com.telefam.ui.components.SettingsTopBar
import com.telefam.ui.components.TextOptionRow
import org.jetbrains.compose.resources.stringResource

enum class MuteOption { OFF, EIGHT_HOURS, ONE_WEEK, ALWAYS }

@Composable
fun MuteOptionsScreen(
    current: MuteOption,
    onBackClick: () -> Unit,
    onSelect: (MuteOption) -> Unit
) {
    var selected by remember(current) { mutableStateOf(current) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SettingsTopBar(title = stringResource(Res.string.mute_title), onBackClick = onBackClick)
        Column(Modifier.padding(horizontal = 20.dp)) {
            listOf(
                MuteOption.OFF to stringResource(Res.string.mute_off),
                MuteOption.EIGHT_HOURS to stringResource(Res.string.mute_8_hours),
                MuteOption.ONE_WEEK to stringResource(Res.string.mute_1_week),
                MuteOption.ALWAYS to stringResource(Res.string.mute_always)
            ).forEach { (option, label) ->
                TextOptionRow(
                    title = label, description = "", selected = selected == option,
                    onClick = { selected = option; onSelect(option); onBackClick() }
                )
                Divider()
            }
        }
    }
}

/** Converts a chosen mute option to an epoch-seconds "muted until" value (null = unmuted). */
fun MuteOption.toMutedUntilEpochSeconds(nowEpochSeconds: Long): Long? = when (this) {
    MuteOption.OFF -> null
    MuteOption.EIGHT_HOURS -> nowEpochSeconds + 8 * 3600
    MuteOption.ONE_WEEK -> nowEpochSeconds + 7 * 24 * 3600
    MuteOption.ALWAYS -> Long.MAX_VALUE
}
