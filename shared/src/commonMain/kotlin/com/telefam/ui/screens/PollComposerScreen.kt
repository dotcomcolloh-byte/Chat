package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.telefam.chat.PollData
import com.telefam.shared.generated.resources.*
import com.telefam.ui.components.SettingsTopBar
import com.telefam.ui.components.TelefamPrimaryButton
import com.telefam.ui.theme.TelefamColors
import org.jetbrains.compose.resources.stringResource

@Composable
fun PollComposerScreen(
    onBackClick: () -> Unit,
    onSend: (PollData) -> Unit
) {
    var question by remember { mutableStateOf("") }
    var options by remember { mutableStateOf(listOf("", "")) }
    var allowMultiple by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SettingsTopBar(title = stringResource(Res.string.poll_title), onBackClick = onBackClick)

        LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp)) {
            item {
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = question, onValueChange = { question = it },
                    placeholder = { Text(stringResource(Res.string.poll_question_placeholder)) },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TelefamColors.PrimaryRed, unfocusedBorderColor = TelefamColors.FieldBorder),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(20.dp))
            }

            itemsIndexed(options) { index, value ->
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { new -> options = options.toMutableList().also { it[index] = new } },
                        placeholder = { Text(stringResource(Res.string.poll_option_placeholder, (index + 1).toString())) },
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TelefamColors.PrimaryRed, unfocusedBorderColor = TelefamColors.FieldBorder),
                        modifier = Modifier.weight(1f)
                    )
                    if (options.size > 2) {
                        IconButton(onClick = { options = options.toMutableList().also { it.removeAt(index) } }) {
                            Icon(Icons.Filled.Close, contentDescription = null, tint = TelefamColors.TextMuted)
                        }
                    }
                }
            }

            item {
                TextButton(onClick = { if (options.size < 10) options = options + "" }) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = TelefamColors.PrimaryRed)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(Res.string.poll_add_option), color = TelefamColors.PrimaryRed)
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(Res.string.poll_allow_multiple), fontWeight = FontWeight.Medium)
                    Switch(
                        checked = allowMultiple, onCheckedChange = { allowMultiple = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = TelefamColors.PrimaryRed)
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        val validOptions = options.map { it.trim() }.filter { it.isNotEmpty() }
        TelefamPrimaryButton(
            text = stringResource(Res.string.poll_send),
            enabled = question.isNotBlank() && validOptions.size >= 2,
            onClick = {
                onSend(
                    PollData(
                        id = randomPollId(), question = question.trim(),
                        options = validOptions, allowMultiple = allowMultiple
                    )
                )
            },
            modifier = Modifier.fillMaxWidth().padding(20.dp)
        )
    }
}

private fun randomPollId(): String {
    val chars = "0123456789abcdef"
    return (1..24).map { chars.random() }.joinToString("")
}
