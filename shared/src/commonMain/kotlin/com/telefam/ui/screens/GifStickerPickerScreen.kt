package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.telefam.data.api.GiphyApi
import com.telefam.data.api.GiphyItem
import com.telefam.data.api.GiphyMode
import com.telefam.shared.generated.resources.*
import com.telefam.ui.components.PrimaryCircleButton
import com.telefam.ui.theme.TelefamColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * GIPHY-powered picker for GIFs, stickers and clips: tabbed categories, debounced
 * search, infinite scrolling (offset pagination), tap a tile for a full preview,
 * then Send hands the item to the caller which downloads the bytes and pushes them
 * through the normal end-to-end-encrypted pipeline.
 */
@Composable
fun GifStickerPickerScreen(
    api: GiphyApi,
    initialMode: GiphyMode,
    onBack: () -> Unit,
    onSend: (GiphyItem, GiphyMode) -> Unit
) {
    var mode by remember { mutableStateOf(initialMode) }
    var query by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }
    var items by remember { mutableStateOf(listOf<GiphyItem>()) }
    var loading by remember { mutableStateOf(false) }
    var endReached by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<GiphyItem?>(null) }
    var sending by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(query) {
        delay(350) // debounce keystrokes
        debouncedQuery = query.trim()
    }

    suspend fun loadPage(reset: Boolean) {
        if (loading) return
        loading = true
        val offset = if (reset) 0 else items.size
        val page = api.page(mode, debouncedQuery, offset)
        items = if (reset) page else items + page.filter { p -> items.none { it.id == p.id } }
        endReached = page.size < 24
        loading = false
    }

    LaunchedEffect(mode, debouncedQuery) { loadPage(reset = true) }

    // Infinite scroll: when the last visible tile nears the end, fetch the next page.
    LaunchedEffect(items.size, mode) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }.collect { last ->
            if (!loading && !endReached && last != null && last >= items.size - 8) loadPage(reset = false)
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Header
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                    .background(TelefamColors.PrimaryRed)
                    .statusBarsPadding().padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null, tint = Color.White) }
                Row(
                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp))
                        .background(Color.White.copy(alpha = 0.18f)).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text(stringResource(Res.string.giphy_search_hint), color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
                        BasicTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                            cursorBrush = SolidColor(Color.White), modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (query.isNotEmpty()) {
                        Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(20.dp).clip(CircleShape).clickable { query = "" })
                    }
                }
                Spacer(Modifier.width(8.dp))
            }

            // Mode tabs
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GiphyMode.entries.forEach { m ->
                    val selected = m == mode
                    val label = stringResource(
                        when (m) {
                            GiphyMode.GIF -> Res.string.attach_gif
                            GiphyMode.STICKER -> Res.string.attach_sticker
                            GiphyMode.CLIP -> Res.string.attach_clip
                        }
                    )
                    Text(
                        label, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.clip(RoundedCornerShape(18.dp))
                            .background(if (selected) TelefamColors.PrimaryRed else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { mode = m }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            if (items.isEmpty() && !loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(Res.string.giphy_empty), color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                }
            } else {
                LazyVerticalGrid(
                    state = gridState, columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)
                ) {
                    itemsIndexed(items, key = { _, it -> it.id }) { _, item ->
                        Box(
                            Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { preview = item }
                        ) {
                            AsyncImage(
                                model = item.previewUrl, contentDescription = item.title,
                                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    if (loading) {
                        item {
                            Box(Modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = TelefamColors.PrimaryRed)
                            }
                        }
                    }
                }
            }

            // Required provider attribution
            Text(
                stringResource(Res.string.giphy_attribution), fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 6.dp)
            )
        }
    }

    // Tap -> full preview -> send
    preview?.let { item ->
        Dialog(onDismissRequest = { if (!sending) preview = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)).clickable { if (!sending) preview = null }) {
                Column(
                    Modifier.align(Alignment.Center).clickable(enabled = false) {},
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AsyncImage(
                        model = if (item.isVideo) item.previewUrl else item.sendUrl,
                        contentDescription = item.title, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(24.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    if (sending) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
                    } else {
                        PrimaryCircleButton(Icons.Filled.Send, TelefamColors.PrimaryRed, {
                            sending = true
                            scope.launch {
                                onSend(item, mode)
                                sending = false
                                preview = null
                            }
                        }, stringResource(Res.string.giphy_preview_send))
                    }
                }
                IconButton(
                    onClick = { if (!sending) preview = null },
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 12.dp)
                ) { Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White) }
            }
        }
    }
}
