package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.data.model.AccessLevel
import com.telefam.shared.generated.resources.Res
import com.telefam.shared.generated.resources.privacy_title
import com.telefam.shared.generated.resources.save
import com.telefam.ui.components.SettingsTopBar
import com.telefam.ui.components.TelefamPrimaryButton
import com.telefam.ui.components.TextOptionRow
import com.telefam.ui.components.localizedAccessDescriptions
import com.telefam.ui.components.localizedLabel
import com.telefam.ui.theme.TelefamColors
import org.jetbrains.compose.resources.stringResource

/**
 * One generic screen reused for all six identical-pattern settings (Message Requests,
 * Call, Screenshot, Share, Copy, Download). `field` selects the exact per-setting
 * wording via localizedAccessDescriptions(field), matching the reference screenshots
 * word-for-word while staying fully translatable.
 */
@Composable
fun PrivacyOptionScreen(
    field: String,
    icon: ImageVector,
    title: String,
    description: String,
    current: AccessLevel,
    isSaving: Boolean = false,
    onBackClick: () -> Unit,
    onSave: (AccessLevel) -> Unit
) {
    var selected by remember(current) { mutableStateOf(current) }
    val descriptions = localizedAccessDescriptions(field)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SettingsTopBar(title = stringResource(Res.string.privacy_title), onBackClick = onBackClick)

        Column(Modifier.weight(1f).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(Res.string.privacy_title), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(TelefamColors.PrimaryRed.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = TelefamColors.PrimaryRed, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }

            Spacer(Modifier.height(20.dp))
            Divider()

            AccessLevel.entries.forEachIndexed { index, level ->
                TextOptionRow(
                    title = localizedLabel(level),
                    description = descriptions[index],
                    selected = selected == level,
                    onClick = { selected = level }
                )
                Divider()
            }
        }

        TelefamPrimaryButton(
            text = stringResource(Res.string.save),
            onClick = { onSave(selected) },
            loading = isSaving,
            modifier = Modifier.fillMaxWidth().padding(20.dp)
        )
    }
}
