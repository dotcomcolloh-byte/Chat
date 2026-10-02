package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.shared.generated.resources.*
import com.telefam.ui.components.SettingsTopBar
import org.jetbrains.compose.resources.stringResource

data class MediaLinkDocItem(
    val id: String,
    val outgoing: Boolean,
    val label: String, // filename, link URL, or a short caption
    val createdAt: String
)

private enum class MldTab { MEDIA, LINKS, DOCS }

@Composable
fun MediaLinksDocsScreen(
    media: List<MediaLinkDocItem>,
    links: List<MediaLinkDocItem>,
    docs: List<MediaLinkDocItem>,
    onBackClick: () -> Unit
) {
    var tab by remember { mutableStateOf(MldTab.MEDIA) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SettingsTopBar(title = stringResource(Res.string.media_links_docs_title), onBackClick = onBackClick)

        TabRow(selectedTabIndex = tab.ordinal) {
            Tab(selected = tab == MldTab.MEDIA, onClick = { tab = MldTab.MEDIA }, text = { Text(stringResource(Res.string.tab_media)) })
            Tab(selected = tab == MldTab.LINKS, onClick = { tab = MldTab.LINKS }, text = { Text(stringResource(Res.string.tab_links)) })
            Tab(selected = tab == MldTab.DOCS, onClick = { tab = MldTab.DOCS }, text = { Text(stringResource(Res.string.tab_docs)) })
        }

        val (items, emptyRes) = when (tab) {
            MldTab.MEDIA -> media to Res.string.no_media
            MldTab.LINKS -> links to Res.string.no_links
            MldTab.DOCS -> docs to Res.string.no_docs
        }

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(emptyRes), color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
            }
        } else {
            val sent = items.filter { it.outgoing }
            val received = items.filter { !it.outgoing }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                if (received.isNotEmpty()) {
                    item { SectionHeader(stringResource(Res.string.received)) }
                    items(received, key = { it.id }) { MldRow(it) }
                }
                if (sent.isNotEmpty()) {
                    item { SectionHeader(stringResource(Res.string.sent_by_me)) }
                    items(sent, key = { it.id }) { MldRow(it) }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text, fontWeight = FontWeight.Bold, fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)
    )
}

@Composable
private fun MldRow(item: MediaLinkDocItem) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(item.label, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        Text(item.createdAt, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
    }
}
