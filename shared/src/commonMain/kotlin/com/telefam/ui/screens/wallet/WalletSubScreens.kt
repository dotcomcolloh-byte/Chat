package com.telefam.ui.screens.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.data.api.WalletApi
import com.telefam.data.api.WalletTransactionDto
import com.telefam.ui.theme.TelefamColors
import kotlinx.coroutines.launch

@Composable
internal fun WalletTopBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

/** Pending Balance breakdown — why earnings are pending, expected settlement dates. */
@Composable
fun PendingEarningsScreen(api: WalletApi, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var breakdown by remember { mutableStateOf<com.telefam.data.api.WalletPendingBreakdownDto?>(null) }
    LaunchedEffect(Unit) { scope.launch { breakdown = runCatching { api.pendingBreakdown() }.getOrNull() } }

    Scaffold { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
            item { WalletTopBar("Pending earnings", onBack) }
            item {
                val b = breakdown
                Card(
                    Modifier.fillMaxWidth().padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(b?.pendingFormatted ?: "—", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                        Text(
                            "${b?.pendingCount ?: 0} pending transaction${if ((b?.pendingCount ?: 0) == 1L) "" else "s"}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            b?.explanation ?: "",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            items(breakdown?.items ?: emptyList(), key = { it.earningId }) { item ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        when (item.source) {
                            "SUBSCRIPTION" -> WalletCrownIcon(size = 20.dp)
                            "MONETIZATION" -> WalletPlayIcon(size = 20.dp)
                            "STARS" -> WalletStarIcon(size = 20.dp)
                            else -> WalletBankIcon(size = 20.dp)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (item.source) {
                                "SUBSCRIPTION" -> "Subscription earnings"
                                "MONETIZATION" -> "Monetization earnings"
                                "STARS" -> "Stars earnings"
                                else -> "Other earnings"
                            },
                            fontWeight = FontWeight.SemiBold, fontSize = 15.sp
                        )
                        Text(
                            "Settles ${item.settlementAt.take(10)}",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(item.netFormatted, fontWeight = FontWeight.Bold)
                        StatusPill("PENDING")
                    }
                }
            }
        }
    }
}

/** Per-source earnings (Subscriptions / Monetization / Stars / Other), server-filtered + paginated. */
@Composable
fun SourceEarningsScreen(api: WalletApi, source: String, onBack: () -> Unit, onOpenTransaction: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(listOf<WalletTransactionDto>()) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var hasMore by remember { mutableStateOf(false) }
    fun load(reset: Boolean) {
        scope.launch {
            runCatching { api.sourceEarnings(source, if (reset) null else cursor) }.onSuccess { page ->
                items = if (reset) page.items else items + page.items
                cursor = page.nextCursor
                hasMore = page.hasMore
            }
        }
    }
    LaunchedEffect(source) { load(true) }
    val title = when (source) {
        "SUBSCRIPTION" -> "Subscription earnings"
        "MONETIZATION" -> "Monetization earnings"
        "STARS" -> "Stars earnings"
        else -> "Other earnings"
    }
    Scaffold { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
            item { WalletTopBar(title, onBack) }
            items(items, key = { it.id }) { tx -> TransactionRow(tx = tx, onClick = { onOpenTransaction(tx.id) }) }
            if (items.isEmpty()) {
                item {
                    Text(
                        "No earnings yet", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp)
                    )
                }
            }
            if (hasMore) {
                item {
                    TextButton(
                        onClick = { load(false) },
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp)
                    ) { Text("Load more") }
                }
            }
        }
    }
}

/** Full transaction history with filters, pull-to-refresh-style reload and pagination. */
@Composable
fun TransactionsScreen(api: WalletApi, onBack: () -> Unit, onOpenTransaction: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf("ALL") }
    var items by remember { mutableStateOf(listOf<WalletTransactionDto>()) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    fun load(reset: Boolean) {
        scope.launch {
            loading = true
            runCatching { api.transactions(filter, if (reset) null else cursor) }.onSuccess { page ->
                items = if (reset) page.items else items + page.items
                cursor = page.nextCursor
                hasMore = page.hasMore
            }
            loading = false
        }
    }
    LaunchedEffect(filter) { load(true) }
    Scaffold { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
            item {
                WalletTopBar("Transactions", onBack)
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ALL" to "All", "EARNINGS" to "Earnings", "PAYOUTS" to "Payouts", "REFUNDS" to "Refunds")
                        .forEach { (key, label) ->
                            FilterChip(selected = filter == key, onClick = { filter = key }, label = { Text(label) })
                        }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { load(true) }, modifier = Modifier.defaultMinSize(minHeight = 44.dp)) {
                        Text("Refresh")
                    }
                }
            }
            items(items, key = { it.id }) { tx -> TransactionRow(tx = tx, onClick = { onOpenTransaction(tx.id) }) }
            if (items.isEmpty() && !loading) {
                item { Text("No transactions", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp)) }
            }
            if (hasMore) {
                item {
                    TextButton(onClick = { load(false) }, modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp)) {
                        Text("Load more")
                    }
                }
            }
        }
    }
}

/** Transaction details: gross / platform fee / net / source / status / settlement / reference. */
@Composable
fun TransactionDetailsScreen(api: WalletApi, transactionId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var tx by remember { mutableStateOf<WalletTransactionDto?>(null) }
    LaunchedEffect(transactionId) { scope.launch { tx = runCatching { api.transactionDetails(transactionId) }.getOrNull() } }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
            WalletTopBar("Transaction", onBack)
            val t = tx
            if (t == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                Card(
                    Modifier.fillMaxWidth().padding(16.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(t.amountFormatted, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                        StatusPill(t.status)
                        HorizontalDivider()
                        DetailRow("Description", t.description)
                        DetailRow("Type", t.type.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() })
                        t.source?.let { DetailRow("Source", it.lowercase().replaceFirstChar { c -> c.uppercase() }) }
                        t.grossMinor?.let { DetailRow("Gross amount", minorFmt(t.currency, it)) }
                        t.feeMinor?.let { DetailRow("Telefam platform fee", minorFmt(t.currency, it)) }
                        DetailRow("Net amount", minorFmt(t.currency, t.amountMinor))
                        DetailRow("Date", t.createdAt.replace('T', ' ').take(16))
                        t.settlementAt?.let { DetailRow("Settlement date", it.take(10)) }
                        t.payoutStatus?.let { DetailRow("Payout status", it) }
                        DetailRow("Reference", t.reference)
                    }
                }
            }
        }
    }
}

@Composable
internal fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

internal fun minorFmt(currency: String, minor: Long): String {
    val symbol = when (currency) { "KES" -> "KSh"; "USD" -> "$"; "EUR" -> "€"; "GBP" -> "£"; else -> "$currency " }
    val sign = if (minor < 0) "−" else ""
    return "$sign$symbol${"%,.2f".format(kotlin.math.abs(minor) / 100.0)}"
}
