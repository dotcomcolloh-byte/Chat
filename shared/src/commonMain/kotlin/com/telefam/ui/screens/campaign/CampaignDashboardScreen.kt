@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.telefam.ui.screens.campaign

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.campaigns.CampaignViewModel
import com.telefam.data.api.CampaignAnalyticsDto
import com.telefam.data.api.CampaignDto
import com.telefam.ui.screens.CreatorLoadError
import com.telefam.ui.theme.TelefamColors
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

private fun fmt(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}

private fun goalTitleOf(apiType: String): String =
    CampaignGoal.entries.firstOrNull { it.apiType == apiType }?.title ?: apiType

/** "2026-10-02T12:30:00" -> remaining whole days from now (>= 0). */
private fun daysRemaining(endsAt: String): Long = runCatching {
    val end = LocalDateTime.parse(endsAt).toInstant(TimeZone.currentSystemDefault())
    val now = kotlinx.datetime.Clock.System.now()
    ((end - now).inWholeHours / 24).coerceAtLeast(0)
}.getOrDefault(0L)

private fun endDateLabel(endsAt: String): String = runCatching {
    val dt = LocalDateTime.parse(endsAt)
    val month = dt.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
    "$month ${dt.dayOfMonth}, ${dt.year}"
}.getOrDefault(endsAt.take(10))

/**
 * My Campaigns — the advertiser dashboard. Tabs: Active (running + paused) and
 * Completed (exhausted / expired / canceled). Each card shows live delivery stats
 * (unique reach, clicks, CTR, stars spent, remaining time), a progress bar against
 * the tier's reach cap, and management actions: Analytics, Pause / Resume, Cancel
 * (cancel refunds the unused full days, per the refund policy, via the server).
 */
@Composable
fun CampaignDashboardScreen(
    viewModel: CampaignViewModel,
    onBack: () -> Unit,
    onBuyStars: () -> Unit,
    onCreateCampaign: () -> Unit
) {
    val state by viewModel.myCampaigns.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var analyticsFor by remember { mutableStateOf<String?>(null) }
    var cancelFor by remember { mutableStateOf<CampaignDto?>(null) }

    LaunchedEffect(Unit) { viewModel.loadMyCampaigns() }

    // ---- Analytics sheet ----
    if (analyticsFor != null) {
        AnalyticsDialog(
            campaign = state.campaigns.firstOrNull { it.id == analyticsFor },
            analytics = state.analytics,
            loading = state.analyticsLoading,
            onDismiss = { analyticsFor = null; viewModel.dismissAnalytics() }
        )
    }

    // ---- Cancel confirmation ----
    cancelFor?.let { c ->
        AlertDialog(
            onDismissRequest = { cancelFor = null },
            title = { Text("Cancel campaign?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "The campaign stops immediately. Unused full days are refunded to your star balance " +
                        "automatically; the current day is already consumed."
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.cancelCampaign(c.id); cancelFor = null },
                    colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed)
                ) { Text("Cancel Campaign", color = Color.White, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { cancelFor = null }) { Text("Keep Running") } }
        )
    }

    state.actionError?.let {
        AlertDialog(
            onDismissRequest = viewModel::clearActionError,
            title = { Text("Something went wrong", fontWeight = FontWeight.Bold) },
            text = { Text(it) },
            confirmButton = {
                TextButton(onClick = viewModel::clearActionError) {
                    Text("OK", color = TelefamColors.PrimaryRed, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    val active = state.campaigns.filter { it.status == "ACTIVE" || it.status == "PAUSED" }
    val completed = state.campaigns.filter { it.status != "ACTIVE" && it.status != "PAUSED" }
    val shown = if (tab == 0) active else completed

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // ---- Header ----
            Box(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(TelefamColors.PrimaryRed, TelefamColors.PrimaryRedDark)))
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Text(
                        "My Campaigns", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
                    )
                    BalancePill(balance = state.balanceStars, onClick = onBuyStars, modifier = Modifier.padding(end = 10.dp))
                }
            }


            // ---- Tabs ----
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                listOf("Active (${active.size})", "History (${completed.size})").forEachIndexed { i, label ->
                    val selected = tab == i
                    Surface(
                        onClick = { tab = i },
                        shape = RoundedCornerShape(50),
                        color = if (selected) TelefamColors.PrimaryRed else MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (selected) TelefamColors.PrimaryRed else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier.padding(end = 8.dp).heightIn(min = 44.dp).weight(1f)
                    ) {
                        Box(Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                            Text(
                                label, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            when {
                state.loading -> Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TelefamColors.PrimaryRed)
                }
                state.error -> CreatorLoadError(onRetry = { viewModel.loadMyCampaigns() })
                shown.isEmpty() -> EmptyCampaigns(tab, onCreateCampaign)
                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(shown, key = { it.id }) { campaign ->
                        CampaignCard(
                            campaign = campaign,
                            busy = state.busyId == campaign.id,
                            onAnalytics = {
                                analyticsFor = campaign.id
                                viewModel.loadAnalytics(campaign.id)
                            },
                            onPause = { viewModel.pauseCampaign(campaign.id) },
                            onResume = { viewModel.resumeCampaign(campaign.id) },
                            onCancel = { cancelFor = campaign }
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun EmptyCampaigns(tab: Int, onCreateCampaign: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Star3D(Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            if (tab == 0) "No active campaigns" else "No past campaigns yet",
            fontWeight = FontWeight.Bold, fontSize = 16.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (tab == 0) "Boost a video to reach new people — it will show up here with live stats."
            else "Finished and canceled campaigns will appear here.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, textAlign = TextAlign.Center
        )
        if (tab == 0) {
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onCreateCampaign,
                colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Create Campaign", color = Color.White, fontWeight = FontWeight.Bold) }
        }
    }
}

// ---------------------------------------------------------------- campaign card

@Composable
private fun CampaignCard(
    campaign: CampaignDto,
    busy: Boolean,
    onAnalytics: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    val statusColor = when (campaign.status) {
        "ACTIVE" -> Color(0xFF1E8E3E)
        "PAUSED" -> Color(0xFFF29900)
        "EXHAUSTED" -> TelefamColors.PrimaryRed
        "EXPIRED" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusLabel = when (campaign.status) {
        "ACTIVE" -> "Active"
        "PAUSED" -> "Paused"
        "EXHAUSTED" -> "Reach met"
        "EXPIRED" -> "Expired"
        "CANCELED" -> "Canceled"
        else -> campaign.status
    }
    val cap = if (campaign.maxReach < 0) Long.MAX_VALUE else campaign.maxReach
    val progress = if (cap == Long.MAX_VALUE) 0f
    else (campaign.servedImpressions.toFloat() / cap.toFloat()).coerceIn(0f, 1f)
    val ctr = if (campaign.servedImpressions > 0)
        campaign.clicks.toDouble() / campaign.servedImpressions * 100 else 0.0

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Goal3DIcon(
                    CampaignGoal.entries.firstOrNull { it.apiType == campaign.type } ?: CampaignGoal.VIEWS,
                    Modifier.size(36.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(goalTitleOf(campaign.type), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        campaign.audience.summary(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = statusColor.copy(alpha = 0.12f)
                ) {
                    Text(
                        statusLabel, color = statusColor, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Reach progress
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Unique reach", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)
                )
                Text(
                    "${fmt(campaign.servedImpressions)} of ${campaign.reachLabel}",
                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                color = TelefamColors.PrimaryRed,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            // Stats row
            Row(Modifier.fillMaxWidth()) {
                StatCell("Impressions", fmt(campaign.servedImpressions), Modifier.weight(1f))
                StatCell("Clicks", fmt(campaign.clicks), Modifier.weight(1f))
                StatCell("CTR", "%.1f%%".format(ctr), Modifier.weight(1f))
                StatCell(
                    "Stars spent",
                    fmt(campaign.totalStars - campaign.refundedStars) +
                        if (campaign.refundedStars > 0) " (−${fmt(campaign.refundedStars)})" else "",
                    Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (campaign.status) {
                        "ACTIVE" -> "${daysRemaining(campaign.endsAt)}d left · ends ${endDateLabel(campaign.endsAt)}"
                        "PAUSED" -> "Paused · ends ${endDateLabel(campaign.endsAt)}"
                        else -> "Ended ${endDateLabel(campaign.endsAt)}"
                    },
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(6.dp))

            // Actions
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onAnalytics, modifier = Modifier.heightIn(min = 44.dp)) {
                    Text("Analytics", color = TelefamColors.PrimaryRed, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Spacer(Modifier.weight(1f))
                if (busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = TelefamColors.PrimaryRed, strokeWidth = 2.dp)
                } else when (campaign.status) {
                    "ACTIVE" -> {
                        TextButton(onClick = onPause, modifier = Modifier.heightIn(min = 44.dp)) {
                            Text("Pause", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 44.dp)) {
                            Text("Cancel", color = TelefamColors.PrimaryRed, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    }
                    "PAUSED" -> {
                        TextButton(onClick = onResume, modifier = Modifier.heightIn(min = 44.dp)) {
                            Text("Resume", color = Color(0xFF1E8E3E), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 44.dp)) {
                            Text("Cancel", color = TelefamColors.PrimaryRed, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- analytics dialog

@Composable
private fun AnalyticsDialog(
    campaign: CampaignDto?,
    analytics: CampaignAnalyticsDto?,
    loading: Boolean,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Analytics — ${campaign?.let { goalTitleOf(it.type) } ?: "Campaign"}",
                fontWeight = FontWeight.Bold, fontSize = 17.sp
            )
        },
        text = {
            when {
                loading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TelefamColors.PrimaryRed)
                }
                analytics == null -> Text(
                    "Couldn't load analytics. Check your connection and try again.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> Column {
                    FunnelRow("Impressions (unique viewers)", analytics.impressions)
                    FunnelRow("CTA clicks", analytics.clicks)
                    when (campaign?.type) {
                        "GET_SALES" -> FunnelRow("Destination actions", analytics.destinationClicks)
                        "FOLLOWERS", "PROFILE_BOOST" -> {
                            FunnelRow("Profile visits", analytics.profileVisits)
                            FunnelRow("Follows", analytics.follows)
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    FunnelRow("CTR", 0, valueText = "%.2f%%".format(analytics.ctr * 100))
                    FunnelRow("Stars spent", analytics.starsSpent)
                    if (analytics.impressions == 0L) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "No delivery yet — sponsored items appear in viewers' feeds after every 4 videos.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 15.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = TelefamColors.PrimaryRed, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun FunnelRow(label: String, value: Long, valueText: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(valueText ?: fmt(value), fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}
