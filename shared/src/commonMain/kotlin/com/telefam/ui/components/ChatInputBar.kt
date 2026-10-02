package com.telefam.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DateDividerChip(label: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.Center) {
        Text(
            label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )
    }
}

/**
 * The chat composer. Buttons are consistent 48dp circular actions (touch-friendly):
 * tonal icon buttons for auxiliary actions (attach/emoji), and a filled accent button
 * for the primary action (mic when empty, send when there's a draft) with a press
 * scale animation so taps feel responsive.
 */
@Composable
fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    placeholder: String,
    accent: Color,
    onPlusClick: () -> Unit,
    onEmojiClick: () -> Unit,
    onMicClick: () -> Unit,
    onSendClick: () -> Unit,
    emojiActive: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TonalCircleButton(Icons.Filled.Add, accent, onPlusClick, contentDescription = "Attach")
        Spacer(Modifier.width(8.dp))

        Row(
            Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f)) {
                if (text.isEmpty()) {
                    Text(placeholder, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
                }
                BasicTextField(
                    value = text, onValueChange = onTextChange, singleLine = false, maxLines = 4,
                    enabled = enabled,
                    textStyle = TextStyle(fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(accent),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Outlined.EmojiEmotions,
                contentDescription = "Emoji",
                tint = if (emojiActive) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.size(26.dp).clip(CircleShape).clickable(onClick = onEmojiClick).padding(2.dp)
            )
        }

        Spacer(Modifier.width(8.dp))
        PrimaryCircleButton(
            icon = if (text.isBlank()) Icons.Filled.Mic else Icons.Filled.Send,
            color = accent,
            onClick = if (text.isBlank()) onMicClick else onSendClick,
            contentDescription = if (text.isBlank()) "Record voice" else "Send"
        )
    }
}

/** Filled accent action button with a subtle press-scale animation. */
@Composable
fun PrimaryCircleButton(icon: ImageVector, color: Color, onClick: () -> Unit, contentDescription: String?) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, label = "press")
    Box(
        Modifier.size(48.dp).scale(scale).clip(CircleShape).background(color)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(23.dp))
    }
}

/** Tonal (soft accent) circular icon button for secondary actions. */
@Composable
fun TonalCircleButton(icon: ImageVector, accent: Color, onClick: () -> Unit, contentDescription: String?) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, label = "press")
    val bg by animateColorAsState(
        if (pressed) accent.copy(alpha = 0.28f) else accent.copy(alpha = 0.14f), label = "bg"
    )
    Box(
        Modifier.size(48.dp).scale(scale).clip(CircleShape).background(bg)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = accent, modifier = Modifier.size(24.dp))
    }
}
