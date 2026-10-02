package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.connect.ProfileDetailsDto
import com.telefam.creator.CreatorViewModel
import com.telefam.ui.theme.TelefamColors

private val IconRed = Color(0xFFE53935)
private val IconPurple = Color(0xFF8E44AD)
private val IconBlue = Color(0xFF1E88E5)
private val IconOrange = Color(0xFFF29900)

/**
 * Creator Dashboard — matches the Dashboard reference screen: profile header with
 * verified badge, period selector (7/30/365 days), Overview stats, View Analytics,
 * Content Performance (with Create Post), Audience Insights, and a coaching tip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    profile: ProfileDetailsDto?,
    viewModel: CreatorViewModel,
    onBack: () -> Unit,
    onCreatePost: () -> Unit,
    onViewAnalytics: (Int) -> Unit,
    onViewAllContent: () -> Unit
) {
    var periodDays by remember { mutableStateOf(7) }
    var showHelp by remember { mutableStateOf(false) }
    val state by viewModel.dashboard.collectAsState()

    LaunchedEffect(periodDays) { viewModel.loadDashboard(periodDays) }

    if (showHelp) {
        CreatorHelpDialog(
            title = "About your Dashboard",
            text = "The Dashboard shows how your content is performing: views, engagement, likes and shares for the selected period (7 days, 30 days or the last year), your top content, and who your audience is. Percentages compare against the previous period. Pull down to refresh.",
            onDismiss = { showHelp = false }
        )
    }

    Scaffold { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = { viewModel.loadDashboard(periodDays) },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            Column(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState())
            ) {
                CreatorTopBar("Dashboard", onBack = onBack, onHelp = { showHelp = true })
                CreatorProfileHeader(profile)


                if (state.error) {
                    CreatorLoadError(onRetry = { viewModel.loadDashboard(periodDays) })
                    return@Column
                }

                // ---- Overview card ----
                Surface(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.weight(1f))
                            PeriodDropdown(selectedDays = periodDays, onSelected = { periodDays = it })
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Overview", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(Modifier.height(16.dp))
                        val o = state.data?.overview
                        Row(Modifier.fillMaxWidth()) {
                            CreatorStatCell(Icons.Outlined.Visibility, IconRed,
                                "${o?.views?.value ?: 0}", "Views", o?.views?.deltaPercent,
                                modifier = Modifier.weight(1f))
                            CreatorStatCell(Icons.Outlined.People, IconPurple,
                                "${o?.engagement?.value ?: 0}", "Engagement", o?.engagement?.deltaPercent,
                                modifier = Modifier.weight(1f))
                            CreatorStatCell(Icons.Outlined.ThumbUp, IconBlue,
                                "${o?.likes?.value ?: 0}", "Likes", o?.likes?.deltaPercent,
                                modifier = Modifier.weight(1f))
                            CreatorStatCell(Icons.Outlined.Share, IconOrange,
                                "${o?.shares?.value ?: 0}", "Shares", o?.shares?.deltaPercent,
                                modifier = Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onViewAnalytics(periodDays) }
                                .padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Outlined.BarChart, contentDescription = null, tint = TelefamColors.PrimaryRed)
                            Spacer(Modifier.width(8.dp))
                            Text("View Analytics", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Spacer(Modifier.weight(1f))
                            Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null,
                                modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // ---- Content Performance ----
                Surface(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Content Performance", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                                modifier = Modifier.weight(1f))
                            Text(
                                "View All", color = TelefamColors.PrimaryRed, fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                    .clickable { onViewAllContent() }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                        val content = state.data?.topContent.orEmpty()
                        if (content.isEmpty()) {
                            Spacer(Modifier.height(24.dp))
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
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
                                Text(
                                    "Create and share content to see performance insights.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                                    textAlign = TextAlign.Center
                                )
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
                            Spacer(Modifier.height(16.dp))
                        } else {
                            Spacer(Modifier.height(8.dp))
                            content.forEach { item ->
                                ContentPerformanceRow(
                                    title = item.description?.takeIf { it.isNotBlank() } ?: "Untitled post",
                                    views = item.views, likes = item.likes, shares = item.shares
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            }
                        }
                    }
                }

                // ---- Audience Insights ----
                Surface(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Audience Insights", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(Modifier.height(16.dp))
                        val audience = state.data?.audience
                        Row(Modifier.fillMaxWidth()) {
                            AudienceCell(Icons.Outlined.Language, IconRed, "Top Countries",
                                audience?.topCountries?.takeIf { it.isNotEmpty() }?.joinToString(", "),
                                Modifier.weight(1f))
                            AudienceCell(Icons.Outlined.People, IconPurple, "Age Range",
                                audience?.topAgeRange, Modifier.weight(1f))
                            AudienceCell(Icons.Outlined.AccessTime, IconOrange, "Top Active Time",
                                audience?.topActiveTime, Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).background(IconRed.copy(alpha = 0.10f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Outlined.EmojiObjects, contentDescription = null,
                                    tint = IconRed, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Keep it up!", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text(
                                    "Consistent posting and engagement helps grow your audience.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                                    lineHeight = 17.sp
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

@Composable
private fun AudienceCell(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    title: String,
    value: String?,
    modifier: Modifier = Modifier
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        }
        Spacer(Modifier.height(16.dp))
        if (value == null) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.People, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text("No data yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        } else {
            Text(value, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun ContentPerformanceRow(title: String, views: Long, likes: Long, shares: Long) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2)
        Spacer(Modifier.height(6.dp))
        Row {
            Text("$views views", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Spacer(Modifier.width(16.dp))
            Text("$likes likes", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Spacer(Modifier.width(16.dp))
            Text("$shares shares", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}
