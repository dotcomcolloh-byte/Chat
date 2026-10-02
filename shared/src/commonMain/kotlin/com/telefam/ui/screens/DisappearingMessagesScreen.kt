package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.telefam.shared.generated.resources.*
import com.telefam.ui.components.SettingsTopBar
import com.telefam.ui.components.TextOptionRow
import org.jetbrains.compose.resources.stringResource

/** Seconds for each duration - the actual TTL enforced by both devices' local expiry sweep. */
enum class DisappearingOption(val seconds: Long) {
    OFF(0),
    HOURS_24(24 * 3600L),
    DAYS_3(3 * 24 * 3600L),
    WEEK_1(7 * 24 * 3600L),
    MONTH_1(30 * 24 * 3600L)
}

@Composable
fun DisappearingMessagesScreen(
    current: DisappearingOption,
    onBackClick: () -> Unit,
    onSelect: (DisappearingOption) -> Unit
) {
    var selected by remember(current) { mutableStateOf(current) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SettingsTopBar(title = stringResource(Res.string.disappearing_title), onBackClick = onBackClick)
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(stringResource(Res.string.disappearing_subtitle), color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
            Spacer(Modifier.height(12.dp))
            listOf(
                DisappearingOption.OFF to stringResource(Res.string.disappearing_off),
                DisappearingOption.HOURS_24 to stringResource(Res.string.disappearing_24_hours),
                DisappearingOption.DAYS_3 to stringResource(Res.string.disappearing_3_days),
                DisappearingOption.WEEK_1 to stringResource(Res.string.disappearing_1_week),
                DisappearingOption.MONTH_1 to stringResource(Res.string.disappearing_1_month)
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
