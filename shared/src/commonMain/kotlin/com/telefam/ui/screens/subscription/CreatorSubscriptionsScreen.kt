@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.telefam.ui.screens.subscription

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.AttachMoney
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.telefam.connect.ProfileDetailsDto
import com.telefam.data.api.SubscriberDto
import com.telefam.data.api.SubscriptionPlanDto
import com.telefam.subscriptions.SubscriptionViewModel
import com.telefam.ui.components.NameWithBadge
import com.telefam.ui.components.VerifiedBadgeVariant
import com.telefam.ui.screens.CreatorLoadError
import com.telefam.ui.screens.CreatorHelpDialog
import com.telefam.ui.screens.CreatorTopBar
import com.telefam.ui.screens.CreatorProfileHeader
import com.telefam.ui.theme.TelefamColors
import kotlinx.coroutines.launch

private val SubPurple = Color(0xFF7C3AED)
private val SubGreen = Color(0xFF16A34A)
private val SubBlue = Color(0xFF2563EB)
private val SubOrange = Color(0xFFEA8600)

internal fun intervalLabel(interval: String) = when (interval) {
    "DAILY" -> "day"
    "WEEKLY" -> "week"
    else -> "month"
}

/**
 * Creator "Subscriptions" home — matches the reference screen: profile header,
 * total earnings card with 3D crown, period-filtered overview metrics
 * (7 / 30 / 90 days / all), insights entry, plan management, recent subscribers
 * with See All, the "How Subscriptions Work" explainer and the (?) about dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatorSubscriptionsScreen(
    profile: ProfileDetailsDto?,
    viewModel: SubscriptionViewModel,
    onBack: () -> Unit,
    onOpenInsights: (Int) -> Unit,
    onCreatePlan: () -> Unit,
    onManagePlans: () -> Unit,
    onEditPlan: (SubscriptionPlanDto) -> Unit,
    onSeeAllSubscribers: () -> Unit,
    onOpenSubscriberProfile: (String) -> Unit = {}
) {
    val state by viewModel.creator.collectAsState()
    var showHelp by remember { mutableStateOf(false) }
    var refundCandidate by remember { mutableStateOf<com.telefam.data.api.CreatorSubscriptionPaymentDto?>(null) }
    var refundNotice by remember { mutableStateOf<String?>(null) }
    var refundInFlight by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { viewModel.loadCreator() }

    if (showHelp) {
        CreatorHelpDialog(
            title = "About Subscriptions",
            text = "Subscriptions let your biggest fans support you with a recurring payment. " +
                "Create a plan with your own monthly (or weekly/daily) price — the currency comes " +
                "from the country on your profile, and fans pay securely through Paystack or PayPal " +
                "depending on their country. Payments are verified by Telefam's servers before a " +
                "subscription becomes active. Earnings, new subscribers and cancellations are " +
                "tracked here; tap View Subscription Insights for the full breakdown.",
            onDismiss = { showHelp = false }
        )
    }
    refundCandidate?.let { payment ->
        AlertDialog(
            onDismissRequest = { if (!refundInFlight) refundCandidate = null },
            title = { Text("Issue full refund?") },
            text = {
                Text(
                    "Refund ${payment.formattedAmount} to ${payment.subscriberName ?: "this subscriber"}? " +
                        "If the refund covers their current paid period, access will end after the provider confirms it. " +
                        "An unclear provider response is reconciled without issuing another refund."
                )
            },
            confirmButton = {
                TextButton(enabled = !refundInFlight, onClick = {
                    refundInFlight = true
                    scope.launch {
                        refundNotice = viewModel.requestRefund(payment.paymentId)
                            ?.let { "Refund request failed: $it" }
                            ?: "Refund request recorded. The status will update as the provider processes it."
                        refundInFlight = false
                        refundCandidate = null
                    }
                }) { Text(if (refundInFlight) "Submitting…" else "Issue full refund", color = TelefamColors.PrimaryRed) }
            },
            dismissButton = {
                TextButton(enabled = !refundInFlight, onClick = { refundCandidate = null }) { Text("Keep payment") }
            }
        )
    }

    Scaffold { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = { viewModel.loadCreator() },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            Column(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState())
            ) {
                CreatorTopBar("Subscriptions", onBack = onBack, onHelp = { showHelp = true })

                if (state.error) {
                    CreatorLoadError(onRetry = { viewModel.loadCreator() })
                    return@Column
                }

                CreatorProfileHeader(profile)
                TotalEarningsCard(state.overview?.totalEarningsFormatted ?: "—")

                // ---- Overview ----
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Overview", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    SubPeriodDropdown(state.periodDays) { viewModel.loadCreator(it) }
                }
                state.overview?.let { o ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        SubStatCell(Modifier.weight(1f), tint = SubPurple, icon = { Icon(Icons.Outlined.AttachMoney, null, tint = SubPurple, modifier = Modifier.size(20.dp)) },
                            value = o.earningsFormatted, label = "Earnings", delta = o.earnings.deltaPercent, deltaTint = SubPurple)
                        SubStatCell(Modifier.weight(1f), tint = SubGreen, icon = { Icon(Icons.Outlined.Group, null, tint = SubGreen, modifier = Modifier.size(20.dp)) },
                            value = "${o.subscribers.value}", label = "Subscribers", delta = o.subscribers.deltaPercent, deltaTint = SubGreen)
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        SubStatCell(Modifier.weight(1f), tint = SubBlue, icon = { Icon(Icons.Outlined.PersonAdd, null, tint = SubBlue, modifier = Modifier.size(20.dp)) },
                            value = "${o.newSubscribers.value}", label = "New Subscribers", delta = o.newSubscribers.deltaPercent, deltaTint = SubBlue)
                        SubStatCell(Modifier.weight(1f), tint = SubOrange, icon = { Icon(Icons.Outlined.PersonRemove, null, tint = SubOrange, modifier = Modifier.size(20.dp)) },
                            value = "${o.canceled.value}", label = "Canceled", delta = o.canceled.deltaPercent, deltaTint = SubOrange)
                    }
                } ?: Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TelefamColors.PrimaryRed, modifier = Modifier.size(28.dp))
                }

                // ---- Insights entry ----
                Surface(
                    onClick = { onOpenInsights(state.periodDays) },
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(6.dp))
                        Chart3DIcon(Modifier.size(22.dp), TelefamColors.PrimaryRed)
                        Spacer(Modifier.width(12.dp))
                        Text("View Subscription Insights", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // ---- Plans ----
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Subscription Plans", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    Text("Manage Plans", color = TelefamColors.PrimaryRed, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onManagePlans).padding(8.dp))
                }
                state.plans.filter { it.isActive }.forEach { plan ->
                    PlanRow(plan, onClick = { onEditPlan(plan) })
                }
                if (state.plans.none { it.isActive }) {
                    Text(
                        "No plans yet — create your first one so fans can support you.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
                OutlinedButton(
                    onClick = onCreatePlan,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TelefamColors.PrimaryRed),
                    border = androidx.compose.foundation.BorderStroke(1.dp, TelefamColors.PrimaryRed.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(52.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Create New Plan", fontWeight = FontWeight.SemiBold)
                }

                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // ---- Recent subscribers ----
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Recent Subscribers", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    Text("See All", color = TelefamColors.PrimaryRed, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onSeeAllSubscribers).padding(8.dp))
                }
                if (state.recentSubscribers.isEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            Modifier.size(72.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.Group, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(30.dp))
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("No subscribers yet", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("When people subscribe, they'll appear here.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                } else {
                    state.recentSubscribers.forEach { sub ->
                        SubscriberRow(sub, onClick = { onOpenSubscriberProfile(sub.userId) })
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // ---- Recent subscription payments / refunds ----
                Text(
                    "Recent payments",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
                refundNotice?.let { notice ->
                    Text(
                        notice,
                        color = if (notice.startsWith("Refund request failed")) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                if (state.recentPayments.isEmpty()) {
                    Text(
                        "No payments yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                } else {
                    state.recentPayments.forEach { payment ->
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(payment.subscriberName ?: "Subscriber", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "${payment.planName} · ${payment.paidAt?.take(10) ?: payment.status}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(payment.formattedAmount, fontWeight = FontWeight.Bold)
                                    if (payment.refundStatus == null && payment.status == "PAID") {
                                        TextButton(onClick = { refundCandidate = payment }) { Text("Refund") }
                                    } else {
                                        Text(
                                            payment.refundStatus?.let { "Refund: $it" } ?: payment.status,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // ---- How it works ----
                Text("How Subscriptions Work", fontWeight = FontWeight.Bold, fontSize = 17.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                HowItWorksRow()
                Surface(
                    color = SubPurple.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(SubPurple.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) { StarDiscIcon(Modifier.size(18.dp), SubPurple) }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Keep creating amazing content to attract more subscribers and grow your income!",
                            fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** "Last 7 / 30 / 90 Days / Last Year" — full day-range filter for the overview. */
@Composable
private fun SubPeriodDropdown(selectedDays: Int, onSelected: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val options = listOf(7 to "Last 7 Days", 30 to "Last 30 Days", 90 to "Last 90 Days", 365 to "Last Year")
    Box {
        Surface(
            onClick = { open = true },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(options.firstOrNull { it.first == selectedDays }?.second ?: "Last 30 Days",
                    fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (days, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onSelected(days) })
            }
        }
    }
}

@Composable
private fun TotalEarningsCard(formatted: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Total Subscription Earnings", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text(formatted, fontWeight = FontWeight.Bold, fontSize = 30.sp)
                Spacer(Modifier.height(4.dp))
                Text("All time", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                GlowBackdrop(Modifier.matchParentSize(), SubPurple)
                Crown3DIcon(Modifier.size(56.dp))
            }
        }
    }
}

@Composable
private fun SubStatCell(
    modifier: Modifier,
    tint: Color,
    icon: @Composable () -> Unit,
    value: String,
    label: String,
    delta: Double?,
    deltaTint: Color
) {
    Column(modifier) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) { icon() }
        Spacer(Modifier.height(10.dp))
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (delta != null) {
                Icon(
                    if (delta >= 0) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown,
                    contentDescription = null, tint = deltaTint, modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(3.dp))
            }
            Text(
                delta?.let { "${kotlin.math.abs(it).toInt()}%" } ?: "— 0%",
                fontSize = 11.sp, color = deltaTint
            )
        }
    }
}

@Composable
private fun PlanRow(plan: SubscriptionPlanDto, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(SubPurple.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) { Crown3DIcon(Modifier.size(26.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(plan.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    if (plan.isMostPopular) {
                        Spacer(Modifier.width(6.dp))
                        Surface(color = TelefamColors.PrimaryRed, shape = RoundedCornerShape(50)) {
                            Text("Most Popular", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                    }
                }
                Text("${plan.formattedPrice} / ${intervalLabel(plan.interval)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                if (plan.description.isNotBlank()) {
                    Text(plan.description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${plan.subscriberCount}", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("Subscribers", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
internal fun SubscriberRow(sub: SubscriberDto, onClick: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = sub.avatarUrl?.let { if (it.startsWith("http")) it else com.telefam.data.api.ApiConfig.baseUrl.trimEnd('/') + it },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            NameWithBadge(
                name = sub.name ?: sub.username ?: "Subscriber",
                isVerified = sub.isVerified,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                badgeVariant = VerifiedBadgeVariant.ON_SURFACE,
                badgeSize = 15.dp
            )
            Text(
                "${sub.planName} · since ${sub.since.take(10)}" +
                    if (sub.status == "CANCELED") " · canceled" else "",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp
            )
        }
        if (sub.status != "CANCELED") {
            Surface(color = SubGreen.copy(alpha = 0.12f), shape = RoundedCornerShape(50)) {
                Text("Active", color = SubGreen, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
            }
        }
    }
}

@Composable
private fun HowItWorksRow() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        HowStep(Modifier.weight(1f), "1. Create a Plan", "Set a monthly price and offer exclusive perks.") {
            Box(Modifier.size(40.dp).clip(CircleShape).background(SubPurple.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Crown3DIcon(Modifier.size(22.dp))
            }
        }
        HowStep(Modifier.weight(1f), "2. Gain Subscribers", "Fans subscribe to support you and get access.") {
            Box(Modifier.size(40.dp).clip(CircleShape).background(SubGreen.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Group, contentDescription = null, tint = SubGreen, modifier = Modifier.size(22.dp))
            }
        }
        HowStep(Modifier.weight(1f), "3. Earn Monthly", "Earn recurring income from your subscribers.") {
            Box(Modifier.size(40.dp).clip(CircleShape).background(SubOrange.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Wallet3DIcon(Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun HowStep(modifier: Modifier, title: String, body: String, icon: @Composable () -> Unit) {
    Column(modifier.padding(end = 10.dp)) {
        icon()
        Spacer(Modifier.height(8.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.height(3.dp))
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 15.sp)
    }
}
