@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.telefam.ui.screens.subscription

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.subscriptions.SubscriptionViewModel
import com.telefam.ui.screens.CreatorLoadError
import com.telefam.ui.theme.TelefamColors

/** "See All" — the creator's full subscribers list, paged and offline-cached. */
@Composable
fun SubscribersListScreen(
    viewModel: SubscriptionViewModel,
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit = {}
) {
    val state by viewModel.subscribers.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { viewModel.loadSubscribers() }

    // Load the next page when the end of the list becomes visible.
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 3
        }.collect { nearEnd -> if (nearEnd) viewModel.loadMoreSubscribers() }
    }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text("Subscribers", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
            when {
                state.error -> CreatorLoadError(onRetry = { viewModel.loadSubscribers() })
                state.items.isEmpty() && !state.loading -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Crown3DIcon(Modifier.size(56.dp))
                    Spacer(Modifier.height(14.dp))
                    Text("No subscribers yet", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("When people subscribe to your plans, they'll appear here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(state.items.size, key = { state.items[it].subscriptionId }) { i ->
                        SubscriberRow(state.items[i], onClick = { onOpenProfile(state.items[i].userId) })
                    }
                    if (state.loadingMore || state.loading) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = TelefamColors.PrimaryRed, modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Subscription Insights — the screen behind "View Subscription Insights":
 * totals for the selected period plus a per-day earnings bar chart, with the
 * full day-range filter (7 / 30 / 90 days / year).
 */
@Composable
fun SubscriptionInsightsScreen(
    viewModel: SubscriptionViewModel,
    initialPeriodDays: Int,
    onBack: () -> Unit
) {
    val state by viewModel.insights.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadInsights(initialPeriodDays) }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text("Subscription Insights", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                InsightsPeriodDropdown(state.periodDays) { viewModel.loadInsights(it) }
            }
            if (state.error) {
                CreatorLoadError(onRetry = { viewModel.loadInsights(state.periodDays) })
                return@Column
            }
            val data = state.data
            if (data == null) {
                Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TelefamColors.PrimaryRed)
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    InsightStatCard(Modifier.weight(1f), "Earnings", data.totalEarningsFormatted, TelefamColors.PrimaryRed)
                    Spacer(Modifier.width(10.dp))
                    InsightStatCard(Modifier.weight(1f), "New", "${data.newSubscribers}", Color(0xFF2563EB))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    InsightStatCard(Modifier.weight(1f), "Active", "${data.activeSubscribers}", Color(0xFF16A34A))
                    Spacer(Modifier.width(10.dp))
                    InsightStatCard(Modifier.weight(1f), "Canceled", "${data.canceled}", Color(0xFFEA8600))
                }

                Text("Earnings per day", fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                EarningsBarChart(
                    values = data.series.map { it.earningsMinor },
                    labels = data.series.map { it.date.takeLast(2) },
                    modifier = Modifier.fillMaxWidth().height(200.dp).padding(horizontal = 16.dp)
                )
                Text("New subscribers per day", fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                EarningsBarChart(
                    values = data.series.map { it.newSubscribers },
                    labels = data.series.map { it.date.takeLast(2) },
                    barColor = Color(0xFF2563EB),
                    modifier = Modifier.fillMaxWidth().height(200.dp).padding(horizontal = 16.dp)
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun InsightsPeriodDropdown(selectedDays: Int, onSelected: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val options = listOf(7 to "7D", 30 to "30D", 90 to "90D", 365 to "1Y")
    Box {
        Surface(
            onClick = { open = true },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(options.firstOrNull { it.first == selectedDays }?.second ?: "30D", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(7 to "Last 7 days", 30 to "Last 30 days", 90 to "Last 90 days", 365 to "Last year").forEach { (d, t) ->
                DropdownMenuItem(text = { Text(t) }, onClick = { open = false; onSelected(d) })
            }
        }
    }
}

@Composable
private fun InsightStatCard(modifier: Modifier, label: String, value: String, tint: Color) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
    ) {
        Column(Modifier.padding(14.dp)) {
            Box(Modifier.size(10.dp).background(tint, RoundedCornerShape(50)))
            Spacer(Modifier.height(10.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

@Composable
private fun EarningsBarChart(
    values: List<Long>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    barColor: Color = TelefamColors.PrimaryRed
) {
    if (values.isEmpty()) return
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Canvas(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 8.dp)) {
            val w = size.width; val h = size.height
            val n = values.size
            val slot = w / n
            val barW = (slot * 0.55f).coerceAtMost(28.dp.toPx())
            drawLine(axisColor, Offset(0f, h), Offset(w, h), 2f)
            values.forEachIndexed { i, v ->
                val bh = (v.toFloat() / max) * (h * 0.85f)
                val x = i * slot + (slot - barW) / 2
                drawRoundRect(
                    barColor.copy(alpha = if (v > 0) 1f else 0.25f),
                    Offset(x, h - bh),
                    Size(barW, bh.coerceAtLeast(3.dp.toPx())),
                    androidx.compose.ui.geometry.CornerRadius(6f, 6f)
                )
            }
        }
    }
}
