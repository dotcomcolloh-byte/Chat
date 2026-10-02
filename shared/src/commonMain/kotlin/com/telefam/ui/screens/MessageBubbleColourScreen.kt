package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.data.model.BubbleColour
import com.telefam.shared.generated.resources.Res
import com.telefam.shared.generated.resources.privacy_title
import com.telefam.shared.generated.resources.save
import com.telefam.shared.generated.resources.setting_bubble_desc
import com.telefam.shared.generated.resources.setting_bubble_title
import com.telefam.ui.components.BubbleColourOptionRow
import com.telefam.ui.components.SettingsTopBar
import com.telefam.ui.components.TelefamPrimaryButton
import com.telefam.ui.components.localizedLabel
import com.telefam.ui.theme.TelefamColors
import org.jetbrains.compose.resources.stringResource

@Composable
fun MessageBubbleColourScreen(
    current: BubbleColour,
    isSaving: Boolean = false,
    onBackClick: () -> Unit,
    onSave: (BubbleColour) -> Unit
) {
    var selected by remember(current) { mutableStateOf(current) }

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
                    Icon(Icons.Filled.ColorLens, contentDescription = null, tint = TelefamColors.PrimaryRed, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(stringResource(Res.string.setting_bubble_title), fontWeight = FontWeight.Bold, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(Res.string.setting_bubble_desc), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }

            Spacer(Modifier.height(16.dp))
            Divider()

            BubbleColour.entries.forEach { colour ->
                BubbleColourOptionRow(
                    title = localizedLabel(colour),
                    colorValue = colour.color,
                    selected = selected == colour,
                    onClick = { selected = colour }
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
