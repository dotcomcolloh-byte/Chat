package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.telefam.data.api.DirectoryUserDto
import com.telefam.shared.generated.resources.*
import com.telefam.ui.components.SettingsTopBar
import com.telefam.ui.theme.TelefamColors
import org.jetbrains.compose.resources.stringResource

@Composable
fun NewChatScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<DirectoryUserDto>,
    onBackClick: () -> Unit,
    onPick: (DirectoryUserDto) -> Unit
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SettingsTopBar(title = stringResource(Res.string.new_chat_title), onBackClick = onBackClick)

        OutlinedTextField(
            value = query, onValueChange = onQueryChange,
            placeholder = { Text(stringResource(Res.string.new_chat_search_placeholder)) },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TelefamColors.PrimaryRed, unfocusedBorderColor = TelefamColors.FieldBorder),
            modifier = Modifier.fillMaxWidth().padding(20.dp)
        )

        when {
            query.trim().length < 3 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.new_chat_hint), color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
            }
            results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.new_chat_no_results), color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
            }
            else -> LazyColumn {
                items(results, key = { it.userId }) { u ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(u) }.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(46.dp).clip(CircleShape).background(TelefamColors.PrimaryRed.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) { Text((u.fullName ?: u.username ?: "?").take(1).uppercase(), fontWeight = FontWeight.Bold, color = TelefamColors.PrimaryRed) }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(u.fullName ?: u.username ?: "Unknown", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            if (u.username != null) Text("@${u.username}", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                        }
                    }
                    Divider()
                }
            }
        }
    }
}
