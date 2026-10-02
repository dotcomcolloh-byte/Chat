package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.telefam.creator.CreatorViewModel
import com.telefam.ui.components.NameWithBadge

/** All Stars transactions ("See All" from Stars Earnings). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarTransactionsScreen(viewModel: CreatorViewModel, onBack: () -> Unit) {
    var showHelp by remember { mutableStateOf(false) }
    val state by viewModel.stars.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadStars(state.overview?.periodDays ?: 7) }

    if (showHelp) {
        CreatorHelpDialog(
            title = "About Transactions",
            text = "Every Star gift you've received, newest first — who sent it, how many Stars, and how much you earned. Pull down to refresh.",
            onDismiss = { showHelp = false }
        )
    }

    Scaffold { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = { viewModel.loadStars(state.overview?.periodDays ?: 7) },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            Column(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState())
            ) {
                CreatorTopBar("Transactions", onBack = onBack, onHelp = { showHelp = true })
                if (state.error) {
                    CreatorLoadError(onRetry = { viewModel.loadStars(state.overview?.periodDays ?: 7) })
                    return@Column
                }
                Surface(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp
                ) {
                    Column(Modifier.padding(16.dp)) {
                        if (state.transactions.isEmpty()) {
                            Text("No transactions yet. You haven't received any Stars.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 16.dp))
                        } else {
                            state.transactions.forEachIndexed { i, tx ->
                                if (i > 0) HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    AsyncImage(
                                        model = tx.fromAvatarUrl, contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(40.dp).clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        NameWithBadge(
                                            name = tx.fromName ?: tx.fromUsername ?: "A fan",
                                            isVerified = tx.fromVerified,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                        )
                                        Text(tx.createdAt.take(10),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Outlined.Star, contentDescription = null,
                                                tint = Color(0xFFF5B301), modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(3.dp))
                                            Text("${tx.stars}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        }
                                        Text("+$%,.2f".format(tx.amountCents / 100.0),
                                            color = Color(0xFF2E9E4F), fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Supporters list — fans ranked by total Stars sent. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarSupportersScreen(viewModel: CreatorViewModel, onBack: () -> Unit) {
    var showHelp by remember { mutableStateOf(false) }
    val state by viewModel.stars.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadStars(state.overview?.periodDays ?: 7) }

    if (showHelp) {
        CreatorHelpDialog(
            title = "About Supporters",
            text = "Your supporters are the fans who send you Stars, ranked by how many they've sent in total. Thanking your top supporters is a great way to grow a loyal community. Pull down to refresh.",
            onDismiss = { showHelp = false }
        )
    }

    Scaffold { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = { viewModel.loadStars(state.overview?.periodDays ?: 7) },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            Column(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState())
            ) {
                CreatorTopBar("Supporters", onBack = onBack, onHelp = { showHelp = true })
                if (state.error) {
                    CreatorLoadError(onRetry = { viewModel.loadStars(state.overview?.periodDays ?: 7) })
                    return@Column
                }
                Surface(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp
                ) {
                    Column(Modifier.padding(16.dp)) {
                        if (state.supporters.isEmpty()) {
                            Text("No supporters yet. When fans send you Stars, they'll show up here.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 16.dp))
                        } else {
                            state.supporters.forEachIndexed { i, s ->
                                if (i > 0) HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("#${i + 1}", fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.width(28.dp))
                                    AsyncImage(
                                        model = s.avatarUrl, contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(40.dp).clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        NameWithBadge(
                                            name = s.name ?: s.username ?: "A fan",
                                            isVerified = s.isVerified,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                        )
                                        Text("${s.giftCount} gift${if (s.giftCount == 1L) "" else "s"}",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.Star, contentDescription = null,
                                            tint = Color(0xFFF5B301), modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(3.dp))
                                        Text("${s.totalStars}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Earnings insights ("View Earnings Insights" from Stars Earnings). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarsInsightsScreen(viewModel: CreatorViewModel, initialPeriodDays: Int, onBack: () -> Unit) {
    var periodDays by remember { mutableStateOf(initialPeriodDays) }
    var showHelp by remember { mutableStateOf(false) }
    val state by viewModel.stars.collectAsState()

    LaunchedEffect(periodDays) { viewModel.loadStars(periodDays) }

    if (showHelp) {
        CreatorHelpDialog(
            title = "About Earnings Insights",
            text = "A deeper look at your Stars earnings for the selected period: how many Stars you received, how many unique fans supported you, and what you earn on an average day. Percentages compare with the previous period.",
            onDismiss = { showHelp = false }
        )
    }

    Scaffold { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = { viewModel.loadStars(periodDays) },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            Column(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState())
            ) {
                CreatorTopBar("Earnings Insights", onBack = onBack, onHelp = { showHelp = true })
                if (state.error) {
                    CreatorLoadError(onRetry = { viewModel.loadStars(periodDays) })
                    return@Column
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(Modifier.weight(1f))
                    PeriodDropdown(selectedDays = periodDays, onSelected = { periodDays = it })
                }
                val o = state.overview
                Surface(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Period totals", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth()) {
                            CreatorStatCell(Icons.Outlined.MonetizationOn, Color(0xFF8E44AD),
                                "$%,.2f".format((o?.earningsCents ?: 0) / 100.0), "Earnings",
                                o?.earningsDeltaPercent, modifier = Modifier.weight(1f))
                            CreatorStatCell(Icons.Outlined.Star, Color(0xFF2E9E4F),
                                "${o?.starsReceived ?: 0}", "Stars Received",
                                o?.starsDeltaPercent, modifier = Modifier.weight(1f))
                            CreatorStatCell(Icons.Outlined.People, Color(0xFF1E88E5),
                                "${o?.supporters ?: 0}", "Supporters",
                                o?.supportersDeltaPercent, modifier = Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth()) {
                            CreatorStatCell(Icons.AutoMirrored.Outlined.TrendingUp, Color(0xFFF29900),
                                "$%,.2f".format((o?.dailyAverageCents ?: 0) / 100.0), "Daily Average",
                                o?.dailyAvgDeltaPercent, modifier = Modifier.weight(1f))
                            CreatorStatCell(Icons.Outlined.Star, Color(0xFFF5B301),
                                "${o?.totalStarsAllTime ?: 0}", "Stars (all time)",
                                null, modifier = Modifier.weight(1f))
                            CreatorStatCell(Icons.Outlined.MonetizationOn, Color(0xFF2E9E4F),
                                "$%,.2f".format((o?.totalEarningsCentsAllTime ?: 0) / 100.0), "Earnings (all time)",
                                null, modifier = Modifier.weight(1f))
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
