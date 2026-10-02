package com.telefam.ui.screens.wallet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.telefam.connect.ProfileDetailsDto
import com.telefam.data.api.WalletTransactionDto
import com.telefam.ui.components.NameWithBadge
import com.telefam.ui.components.VerifiedBadgeVariant
import com.telefam.ui.theme.TelefamColors
import com.telefam.wallet.WalletViewModel

/**
 * Wallet home — matches the reference screen exactly in hierarchy:
 * header (back + Wallet) → creator identity → balance card (Total/Available/Pending +
 * Withdraw) → Monetization Overview (4 sources) → Recent Earnings chart (7D/30D/90D/1Y)
 * → Transaction History (All/Earnings/Payouts/Refunds) → Payouts → Payment Methods.
 *
 * Every number is backend-ledger-derived (WalletViewModel → WalletApi). No demo data:
 * empty states render honest zeroes/empty lists when the ledger is empty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(
    profile: ProfileDetailsDto?,
    viewModel: WalletViewModel,
    onBack: () -> Unit,
    onOpenPending: () -> Unit,
    onOpenSourceEarnings: (String) -> Unit, // SUBSCRIPTION | MONETIZATION | STARS | OTHER
    onOpenTransaction: (String) -> Unit,
    onSeeAllTransactions: () -> Unit,
    onSeeAllPayouts: () -> Unit,
    onOpenPayout: (String) -> Unit,
    onWithdraw: () -> Unit,
    onAddPaymentMethod: () -> Unit,
    onOpenPaymentMethod: (String) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val dark = isSystemInDarkTheme()
    val cardColor = MaterialTheme.colorScheme.surface
    val wash = if (dark) TelefamColors.DarkSurface else Color(0xFFFFF5F5)
    val border = if (dark) TelefamColors.DarkFieldBorder else Color(0xFFF3E3E3)

    LaunchedEffect(Unit) { viewModel.load() }

    Scaffold { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // ---------- Header: back + Wallet (no extra icons) ----------
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Wallet",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            // ---------- Creator identity ----------
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(64.dp).clip(CircleShape)
                            .background(if (dark) TelefamColors.DarkFieldBorder else Color(0xFFF0F1F3)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (profile?.avatarUrl != null) {
                            AsyncImage(
                                model = profile.avatarUrl, contentDescription = null,
                                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                            )
                        } else {
                            Text(
                                (profile?.fullName ?: profile?.username ?: "?").take(1).uppercase(),
                                fontWeight = FontWeight.Bold, fontSize = 24.sp,
                                color = TelefamColors.PrimaryRed
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        NameWithBadge(
                            name = profile?.fullName ?: "",
                            isVerified = profile?.isVerified == true, // backend-granted badge only
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            "@${profile?.username ?: ""}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            color = wash, shape = RoundedCornerShape(50),
                            border = ButtonDefaults.outlinedButtonBorder
                        ) {
                            Text(
                                "Creator", color = TelefamColors.PrimaryRed,
                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }

            // ---------- Balance card ----------
            item {
                val bal = state.balance
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = wash),
                    border = androidx.compose.foundation.BorderStroke(1.dp, border)
                ) {
                    Row(Modifier.fillMaxWidth().padding(18.dp)) {
                        Column(Modifier.weight(1.1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Total Balance", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(4.dp))
                                WalletEyeIcon(size = 14.dp)
                            }
                            Text(
                                bal?.totalFormatted ?: "—",
                                fontSize = 30.sp, fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "Your total earnings from all sources",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Available Balance", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                bal?.availableFormatted ?: "—",
                                fontSize = 20.sp, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "Can be withdrawn anytime",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = onWithdraw,
                                enabled = bal?.canWithdraw == true,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
                                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp),
                                modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                            ) { Text("Withdraw", fontWeight = FontWeight.SemiBold) }
                            if (bal?.canWithdraw == false && bal.withdrawBlockedReason != null) {
                                Text(
                                    bal.withdrawBlockedReason,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                        Column(
                            Modifier
                                .weight(1f)
                                .clickable { onOpenPending() } // Pending breakdown details
                                .defaultMinSize(minHeight = 44.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Pending Balance", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(4.dp))
                                WalletClockIcon(size = 13.dp)
                            }
                            Text(
                                bal?.pendingFormatted ?: "—",
                                fontSize = 20.sp, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "Will be available after settlement period",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Box(Modifier.align(Alignment.CenterVertically).padding(start = 4.dp)) {
                            WalletHeroIcon(size = 72.dp)
                        }
                    }
                }
            }

            // ---------- Monetization Overview ----------
            item {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = cardColor),
                    border = androidx.compose.foundation.BorderStroke(1.dp, border)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Monetization Overview",
                                fontWeight = FontWeight.Bold, fontSize = 17.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            PeriodDropdown(
                                selected = state.overviewPeriodDays,
                                onSelect = { viewModel.setOverviewPeriod(it) }
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            state.overview.forEach { src ->
                                OverviewCard(
                                    modifier = Modifier.weight(1f),
                                    source = src.source,
                                    amount = src.amountFormatted,
                                    delta = src.deltaPercent,
                                    onClick = { onOpenSourceEarnings(src.source) }
                                )
                            }
                            if (state.overview.isEmpty() && !state.loading) {
                                // Backend ledger is authoritative — zero state renders as zero cards.
                                listOf("SUBSCRIPTION", "MONETIZATION", "STARS", "OTHER").forEach { src ->
                                    OverviewCard(
                                        modifier = Modifier.weight(1f), source = src, amount = "0",
                                        delta = null, onClick = { onOpenSourceEarnings(src) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---------- Recent Earnings chart ----------
            item {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = cardColor),
                    border = androidx.compose.foundation.BorderStroke(1.dp, border)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Recent Earnings", fontWeight = FontWeight.Bold, fontSize = 17.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(7 to "7D", 30 to "30D", 90 to "90D", 365 to "1Y").forEach { (days, label) ->
                                    val selected = state.chartPeriodDays == days
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (selected) TelefamColors.PrimaryRed
                                        else if (dark) TelefamColors.DarkFieldBorder else Color(0xFFF0F1F3),
                                        modifier = Modifier.clickable { viewModel.loadChart(days) }.defaultMinSize(minHeight = 44.dp)
                                    ) {
                                        Text(
                                            label,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        EarningsBarChart(
                            points = state.chart.map { it.label to it.amountMinor },
                            maxMinor = state.chartMaxMinor,
                            currency = state.balance?.currency ?: "USD"
                        )
                    }
                }
            }

            // ---------- Transaction History ----------
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Transaction History", fontWeight = FontWeight.Bold, fontSize = 17.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { onSeeAllTransactions() }
                            .defaultMinSize(minHeight = 44.dp).padding(vertical = 10.dp)
                    ) {
                        Text(
                            "See All",
                            color = TelefamColors.PrimaryRed, fontWeight = FontWeight.SemiBold, fontSize = 14.sp
                        )
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = TelefamColors.PrimaryRed, modifier = Modifier.size(18.dp))
                    }
                }
                Row(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("ALL" to "All", "EARNINGS" to "Earnings", "PAYOUTS" to "Payouts", "REFUNDS" to "Refunds")
                        .forEach { (key, label) ->
                            val selected = state.transactionFilter == key
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.loadTransactions(key, reset = true) },
                                label = { Text(label, fontSize = 13.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = TelefamColors.PrimaryRed.copy(alpha = 0.08f),
                                    selectedLabelColor = TelefamColors.PrimaryRed
                                )
                            )
                        }
                }
            }
            items(state.transactions.take(5), key = { it.id }) { tx ->
                TransactionRow(tx = tx, onClick = { onOpenTransaction(tx.id) })
            }
            if (state.transactions.isEmpty() && !state.loading) {
                item {
                    Text(
                        "No transactions yet",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                }
            }

            // ---------- Payouts ----------
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Payouts", fontWeight = FontWeight.Bold, fontSize = 17.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { onSeeAllPayouts() }
                            .defaultMinSize(minHeight = 44.dp).padding(vertical = 10.dp)
                    ) {
                        Text(
                            "See All",
                            color = TelefamColors.PrimaryRed, fontWeight = FontWeight.SemiBold, fontSize = 14.sp
                        )
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = TelefamColors.PrimaryRed, modifier = Modifier.size(18.dp))
                    }
                }
                val summary = state.payoutSummary
                val next = summary?.items?.firstOrNull {
                    it.status in listOf("REQUESTED", "SECURITY_CHECK", "PENDING", "APPROVED", "PROCESSING")
                }
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = wash),
                    border = androidx.compose.foundation.BorderStroke(1.dp, border)
                ) {
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = next != null) { next?.let { onOpenPayout(it.id) } }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        WalletBankIcon(size = 30.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            if (next == null) {
                                Text("No payout scheduled", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(
                                    "Your earnings will be available for withdrawal after the settlement period.",
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    "Payout ${next.amountFormatted} — ${next.status.lowercase().replaceFirstChar { it.uppercase() }}",
                                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp
                                )
                                Text(
                                    "Ref ${next.reference}",
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                }
            }

            // ---------- Payment Methods ----------
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Payment Methods", fontWeight = FontWeight.Bold, fontSize = 17.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "+ Add New",
                        color = TelefamColors.PrimaryRed, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        modifier = Modifier.clickable { onAddPaymentMethod() }
                            .defaultMinSize(minHeight = 44.dp).padding(vertical = 10.dp)
                    )
                }
            }
            items(state.methods, key = { it.id }) { method ->
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = cardColor),
                    border = androidx.compose.foundation.BorderStroke(1.dp, border)
                ) {
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { onOpenPaymentMethod(method.id) }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when (method.type) {
                            "MPESA" -> WalletMpesaIcon(size = 28.dp)
                            "PAYPAL" -> WalletPayPalIcon(size = 28.dp)
                            else -> WalletBankIcon(size = 28.dp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(method.label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text(
                                method.maskedDetail, fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Surface(
                                color = if (method.verificationStatus == "VERIFIED") Color(0xFFDCFCE7) else Color(0xFFFEF3C7),
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    if (method.verificationStatus == "VERIFIED") "Verified" else "Pending",
                                    fontSize = 11.sp,
                                    color = if (method.verificationStatus == "VERIFIED") Color(0xFF166534) else Color(0xFF92400E),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PeriodDropdown(selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = when (selected) { 7 -> "This Week"; 90 -> "90 Days"; 365 -> "This Year"; else -> "This Month" }
    Box {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.clickable { open = true }.defaultMinSize(minHeight = 44.dp)
        ) {
            Text(
                "$label  ▾", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(7 to "This Week", 30 to "This Month", 90 to "90 Days", 365 to "This Year").forEach { (d, l) ->
                DropdownMenuItem(text = { Text(l) }, onClick = { open = false; onSelect(d) })
            }
        }
    }
}

@Composable
private fun OverviewCard(
    modifier: Modifier, source: String, amount: String, delta: Double?, onClick: () -> Unit
) {
    val dark = isSystemInDarkTheme()
    val (iconBg, icon) = when (source) {
        "SUBSCRIPTION" -> Color(0xFFEDE9FE) to @Composable { WalletCrownIcon(size = 24.dp) }
        "MONETIZATION" -> Color(0xFFFFE4E6) to @Composable { WalletPlayIcon(size = 24.dp) }
        "STARS" -> Color(0xFFFEF3C7) to @Composable { WalletStarIcon(size = 24.dp) }
        else -> Color(0xFFDBEAFE) to @Composable { WalletBankIcon(size = 24.dp) }
    }
    val title = when (source) {
        "SUBSCRIPTION" -> "Subscriptions"
        "MONETIZATION" -> "Monetization"
        "STARS" -> "Stars"
        else -> "Other Earnings"
    }
    Card(
        modifier.clickable { onClick() }.defaultMinSize(minHeight = 44.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (dark) TelefamColors.DarkSurface else Color(0xFFFBFBFC)
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, if (dark) TelefamColors.DarkFieldBorder else Color(0xFFF0F0F2)
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(iconBg),
                contentAlignment = Alignment.Center
            ) { icon() }
            Spacer(Modifier.height(8.dp))
            Text(
                title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                amount, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (delta != null) {
                    val deltaColor = if (delta >= 0) Color(0xFF16A34A) else Color(0xFFDC2626)
                    Icon(
                        if (delta >= 0) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown,
                        contentDescription = null, tint = deltaColor, modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        "${kotlin.math.abs(delta).toInt()}%",
                        fontSize = 11.sp,
                        color = deltaColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Bar chart with red gradient bars matching the reference; values are backend aggregates only. */
@Composable
private fun EarningsBarChart(points: List<Pair<String, Long>>, maxMinor: Long, currency: String) {
    if (points.isEmpty()) {
        Text(
            "No earnings in this period",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
            modifier = Modifier.padding(vertical = 24.dp)
        )
        return
    }
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    Column {
        Row(Modifier.fillMaxWidth().height(160.dp)) {
            // Y axis labels
            Column(
                Modifier.fillMaxHeight().width(34.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                val steps = listOf(1.0, 0.75, 0.5, 0.25, 0.0)
                steps.forEach { frac ->
                    val v = (maxMinor * frac).toLong()
                    Text(
                        shortAmount(currency, v), fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Canvas(Modifier.fillMaxSize()) {
                    for (i in 0..4) {
                        val y = size.height * i / 4f
                        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    }
                }
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    points.forEach { (_, value) ->
                        val frac = if (maxMinor > 0) value.toFloat() / maxMinor else 0f
                        Box(
                            Modifier.width(28.dp)
                                .fillMaxHeight(frac.coerceAtLeast(0.02f))
                                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(TelefamColors.PrimaryRed, TelefamColors.PrimaryRed.copy(alpha = 0.35f))
                                    )
                                )
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 34.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            points.forEach { (label, _) ->
                Text(
                    label, fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(44.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

private fun shortAmount(currency: String, minor: Long): String {
    val symbol = when (currency) { "KES" -> "KSh"; "USD" -> "$"; "EUR" -> "€"; "GBP" -> "£"; else -> "$currency " }
    val major = minor / 100.0
    return if (major >= 1000) "$symbol${(major / 1000).toInt()}k" else "$symbol${major.toInt()}"
}

@Composable
internal fun TransactionRow(tx: WalletTransactionDto, onClick: () -> Unit) {
    val positive = tx.amountMinor >= 0
    Row(
        Modifier.fillMaxWidth()
            .clickable { onClick() }
            .defaultMinSize(minHeight = 44.dp)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val dark = isSystemInDarkTheme()
        Box(
            Modifier.size(42.dp).clip(CircleShape)
                .background(if (dark) TelefamColors.DarkSurface else Color(0xFFF5F5F7)),
            contentAlignment = Alignment.Center
        ) {
            when (tx.source) {
                "SUBSCRIPTION" -> WalletCrownIcon(size = 22.dp)
                "MONETIZATION" -> WalletPlayIcon(size = 22.dp)
                "STARS" -> WalletStarIcon(size = 22.dp)
                else -> if (tx.type.startsWith("PAYOUT")) WalletBankIcon(size = 22.dp) else WalletCardIcon(size = 22.dp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(tx.description, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(
                tx.reference, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                tx.amountFormatted, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                color = if (positive) Color(0xFF16A34A) else MaterialTheme.colorScheme.onSurface
            )
            Text(
                tx.createdAt.take(10), fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            StatusPill(tx.status)
        }
    }
}

@Composable
internal fun StatusPill(status: String) {
    val (bg, fg, label) = when (status) {
        "COMPLETED", "PAID" -> Triple(Color(0xFFDCFCE7), Color(0xFF166534), "Completed")
        "PROCESSING", "RESERVED", "PENDING", "PENDING_SETTLEMENT" ->
            Triple(Color(0xFFFEF3C7), Color(0xFF92400E), "Processing")
        "REFUNDED", "REVERSED" -> Triple(Color(0xFFEDE9FE), Color(0xFF5B21B6), "Refunded")
        "FAILED", "REJECTED" -> Triple(Color(0xFFFEE2E2), Color(0xFF991B1B), "Failed")
        else -> Triple(Color(0xFFF0F1F3), Color(0xFF525252), status.lowercase().replaceFirstChar { it.uppercase() })
    }
    Surface(color = bg, shape = RoundedCornerShape(50)) {
        Text(label, fontSize = 10.sp, color = fg, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}
