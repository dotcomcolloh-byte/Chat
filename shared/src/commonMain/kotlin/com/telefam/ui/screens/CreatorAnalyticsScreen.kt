package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.creator.CreatorViewModel
import com.telefam.ui.theme.TelefamColors

/** Detailed analytics opened from Dashboard → View Analytics. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatorAnalyticsScreen(
    viewModel: CreatorViewModel,
    initialPeriodDays: Int,
    onBack: () -> Unit
) {
    var periodDays by remember { mutableStateOf(initialPeriodDays) }
    var showHelp by remember { mutableStateOf(false) }
    val state by viewModel.analytics.collectAsState()

    LaunchedEffect(periodDays) { viewModel.loadAnalytics(periodDays) }

    if (showHelp) {
        CreatorHelpDialog(
            title = "About Analytics",
            text = "Analytics breaks down your performance over the selected period. The chart shows daily views, and the totals below cover likes, shares and engagement compared with the previous period. Pull down to refresh.",
            onDismiss = { showHelp = false }
        )
    }

    Scaffold { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = { viewModel.loadAnalytics(periodDays) },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            Column(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState())
            ) {
                CreatorTopBar("Analytics", onBack = onBack, onHelp = { showHelp = true })

                if (state.error) {
                    CreatorLoadError(onRetry = { viewModel.loadAnalytics(periodDays) })
                    return@Column
                }

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(Modifier.weight(1f))
                    PeriodDropdown(selectedDays = periodDays, onSelected = { periodDays = it })
                }

                // ---- Views chart ----
                Surface(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Views", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(Modifier.height(16.dp))
                        val series = state.data?.series.orEmpty()
                        if (series.isEmpty() || series.all { it.views == 0L }) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Outlined.BarChart, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(40.dp))
                                Spacer(Modifier.height(8.dp))
                                Text("No views in this period yet.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            }
                        } else {
                            ViewsLineChart(series.map { it.views }, Modifier.fillMaxWidth().height(160.dp))
                        }
                    }
                }

                // ---- Totals ----
                Surface(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Totals", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(Modifier.height(16.dp))
                        val t = state.data?.totals
                        Row(Modifier.fillMaxWidth()) {
                            CreatorStatCell(Icons.Outlined.Visibility, Color(0xFFE53935),
                                "${t?.views?.value ?: 0}", "Views", t?.views?.deltaPercent, modifier = Modifier.weight(1f))
                            CreatorStatCell(Icons.Outlined.People, Color(0xFF8E44AD),
                                "${t?.engagement?.value ?: 0}", "Engagement", t?.engagement?.deltaPercent, modifier = Modifier.weight(1f))
                            CreatorStatCell(Icons.Outlined.ThumbUp, Color(0xFF1E88E5),
                                "${t?.likes?.value ?: 0}", "Likes", t?.likes?.deltaPercent, modifier = Modifier.weight(1f))
                            CreatorStatCell(Icons.Outlined.Share, Color(0xFFF29900),
                                "${t?.shares?.value ?: 0}", "Shares", t?.shares?.deltaPercent, modifier = Modifier.weight(1f))
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Minimal line chart for the daily-views series (pure Canvas, no dependency). */
@Composable
private fun ViewsLineChart(values: List<Long>, modifier: Modifier = Modifier) {
    val lineColor = TelefamColors.PrimaryRed
    val fillColor = TelefamColors.PrimaryRed.copy(alpha = 0.12f)
    androidx.compose.foundation.Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
        val w = size.width
        val h = size.height
        val stepX = if (values.size > 1) w / (values.size - 1) else w
        val path = Path()
        val fill = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = h - (v / max) * (h * 0.9f)
            if (i == 0) { path.moveTo(x, y); fill.moveTo(x, h); fill.lineTo(x, y) }
            else { path.lineTo(x, y); fill.lineTo(x, y) }
        }
        fill.lineTo((values.size - 1) * stepX, h); fill.close()
        drawPath(fill, fillColor)
        drawPath(path, lineColor, style = Stroke(width = 4f))
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = h - (v / max) * (h * 0.9f)
            drawCircle(lineColor, 5f, Offset(x, y))
        }
    }
}

/** View All from the Dashboard — the full content-performance list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentPerformanceScreen(
    viewModel: CreatorViewModel,
    onBack: () -> Unit,
    onCreatePost: () -> Unit
) {
    var showHelp by remember { mutableStateOf(false) }
    val state by viewModel.content.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadContent() }

    if (showHelp) {
        CreatorHelpDialog(
            title = "About Content Performance",
            text = "Every published post with its views, likes and shares, newest first. Use this to see which content resonates most with your audience. Pull down to refresh.",
            onDismiss = { showHelp = false }
        )
    }

    Scaffold { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = { viewModel.loadContent() },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            Column(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState())
            ) {
                CreatorTopBar("Content Performance", onBack = onBack, onHelp = { showHelp = true })

                if (state.error) {
                    CreatorLoadError(onRetry = { viewModel.loadContent() })
                    return@Column
                }

                Surface(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp
                ) {
                    Column(Modifier.padding(16.dp)) {
                        if (state.items.isEmpty()) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    Modifier.size(56.dp).clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Outlined.Description, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(12.dp))
                                Text("No content data yet.", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Spacer(Modifier.height(4.dp))
                                Text("Create and share content to see performance insights.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                                Spacer(Modifier.height(16.dp))
                                Button(
                                    onClick = onCreatePost,
                                    colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Create Post", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        } else {
                            state.items.forEachIndexed { i, item ->
                                if (i > 0) HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                ContentPerformanceRow(
                                    title = item.description?.takeIf { it.isNotBlank() } ?: "Untitled post",
                                    views = item.views, likes = item.likes, shares = item.shares
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
