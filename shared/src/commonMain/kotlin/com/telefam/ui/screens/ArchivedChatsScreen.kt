package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.telefam.shared.generated.resources.Res
import com.telefam.shared.generated.resources.archived_empty
import com.telefam.shared.generated.resources.archived_title
import com.telefam.shared.generated.resources.unarchive
import com.telefam.ui.components.SettingsTopBar
import com.telefam.ui.components.SimpleListRow
import org.jetbrains.compose.resources.stringResource

data class ArchivedChatItem(val chatId: String, val title: String, val lastMessagePreview: String?)

@Composable
fun ArchivedChatsScreen(
    chats: List<ArchivedChatItem>,
    onBackClick: () -> Unit,
    onUnarchive: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SettingsTopBar(title = stringResource(Res.string.archived_title), onBackClick = onBackClick)

        if (chats.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.archived_empty), color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
            }
        } else {
            val unarchiveLabel = stringResource(Res.string.unarchive)
            LazyColumn {
                items(chats, key = { it.chatId }) { chat ->
                    SimpleListRow(chat.title, chat.lastMessagePreview, unarchiveLabel) { onUnarchive(chat.chatId) }
                    Divider()
                }
            }
        }
    }
}
