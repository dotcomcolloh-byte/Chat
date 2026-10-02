@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.telefam.ui.screens.subscription

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.telefam.data.api.SubscriptionApi
import com.telefam.data.api.MySubscriptionDto
import com.telefam.data.api.SubscriptionPaymentHistoryDto
import com.telefam.ui.theme.TelefamColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MySubscriptionsScreen(api: SubscriptionApi, onBack: () -> Unit) {
    var subscriptions by remember { mutableStateOf<List<MySubscriptionDto>>(emptyList()) }
    var payments by remember { mutableStateOf<List<SubscriptionPaymentHistoryDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingCancel by remember { mutableStateOf<MySubscriptionDto?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        loading = true
        error = null
        runCatching {
            subscriptions = api.mySubscriptions()
            payments = api.myPaymentHistory()
        }.onFailure { error = it.message ?: "Could not load subscriptions" }
        loading = false
    }
    LaunchedEffect(Unit) { reload() }

    pendingCancel?.let { sub ->
        AlertDialog(
            onDismissRequest = { pendingCancel = null },
            title = { Text("Cancel renewal?") },
            text = { Text("You'll keep subscriber access through ${sub.renewsAt?.take(10) ?: "the end of your paid period"}. No new renewal charge will be made.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingCancel = null
                    scope.launch {
                        runCatching { api.unsubscribe(sub.subscriptionId) }
                            .onSuccess { reload() }
                            .onFailure { error = it.message ?: "Could not cancel renewal" }
                    }
                }) { Text("Cancel renewal", color = TelefamColors.PrimaryRed) }
            },
            dismissButton = { TextButton(onClick = { pendingCancel = null }) { Text("Keep it") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My subscriptions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        when {
            loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TelefamColors.PrimaryRed)
            }
            error != null -> Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { scope.launch { reload() } }) { Text("Retry") }
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { Text("Subscriptions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                if (subscriptions.isEmpty()) item { Text("No subscriptions yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(subscriptions, key = { it.subscriptionId }) { sub ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(sub.creatorName ?: sub.creatorUsername ?: "Creator", fontWeight = FontWeight.Bold)
                            Text("${sub.planName} · ${sub.formattedPrice} / ${sub.interval.lowercase()}")
                            Text(
                                when {
                                    sub.cancelAtPeriodEnd -> "Access through ${sub.renewsAt?.take(10) ?: "period end"} · renewal canceled"
                                    sub.status == "PAST_DUE" -> "Payment issue · access may be limited after the grace period"
                                    sub.autoRenew -> "Next billing ${sub.renewsAt?.take(10) ?: "date unavailable"}"
                                    else -> "Access through ${sub.renewsAt?.take(10) ?: "period end"} · no automatic renewal"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (sub.status in listOf("ACTIVE", "PAST_DUE") && sub.autoRenew && !sub.cancelAtPeriodEnd) {
                                TextButton(onClick = { pendingCancel = sub }) {
                                    Text("Cancel renewal", color = TelefamColors.PrimaryRed)
                                }
                            }
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ReceiptLong, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Payment history", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
                if (payments.isEmpty()) item { Text("No payment records yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(payments, key = { it.paymentId }) { payment ->
                    PaymentHistoryRow(payment)
                }
            }
        }
    }
}

@Composable
private fun PaymentHistoryRow(payment: SubscriptionPaymentHistoryDto) {
    ListItem(
        headlineContent = { Text(payment.creatorName ?: payment.planName, fontWeight = FontWeight.SemiBold) },
        supportingContent = {
            Text(
                buildString {
                    append("${payment.planName} · ${payment.paidAt?.take(10) ?: payment.createdAt.take(10)} · ${payment.status}")
                    payment.refundStatus?.let { append(" · refund $it") }
                }
            )
        },
        trailingContent = { Text(payment.formattedAmount, fontWeight = FontWeight.Bold) }
    )
    HorizontalDivider()
}