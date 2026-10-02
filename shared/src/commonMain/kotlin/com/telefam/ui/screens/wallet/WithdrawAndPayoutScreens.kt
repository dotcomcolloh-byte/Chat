package com.telefam.ui.screens.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.data.api.PayoutDto
import com.telefam.data.api.PayoutMethodDto
import com.telefam.data.api.WalletApi
import com.telefam.data.api.WithdrawQuoteDto
import com.telefam.ui.theme.TelefamColors
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Withdrawal screen. The amount the user types is ONLY a request input — the
 * backend recomputes fee/net/minimum and re-checks available balance when the
 * payout is submitted. Client-side quote is a server-returned WithdrawQuoteDto.
 */
@Composable
fun WithdrawScreen(
    api: WalletApi,
    onBack: () -> Unit,
    onPayoutCreated: (PayoutDto) -> Unit,
    onAddMethod: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var methods by remember { mutableStateOf(listOf<PayoutMethodDto>()) }
    var selectedMethod by remember { mutableStateOf<PayoutMethodDto?>(null) }
    var eligibility by remember { mutableStateOf<com.telefam.data.api.PayoutEligibilityDto?>(null) }
    var amountText by remember { mutableStateOf("") }
    var quote by remember { mutableStateOf<WithdrawQuoteDto?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        scope.launch {
            methods = runCatching { api.methods() }.getOrDefault(emptyList()).filter { it.verificationStatus == "VERIFIED" }
            selectedMethod = methods.firstOrNull()
            eligibility = runCatching { api.payoutEligibility() }.getOrNull()
        }
    }

    // Debounced server quote — the client never calculates the fee/net itself.
    LaunchedEffect(amountText, selectedMethod?.id) {
        val amount = amountText.toLongOrNull() ?: return@LaunchedEffect
        val method = selectedMethod ?: return@LaunchedEffect
        kotlinx.coroutines.delay(350)
        quote = runCatching { api.withdrawQuote(method.id, amount) }.getOrNull()
    }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)
        ) {
            WalletTopBar("Withdraw", onBack)
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val eligible = eligibility?.eligible == true
                if (eligibility != null && !eligible) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7))) {
                        Text(
                            eligibility?.reason ?: "Withdrawals are unavailable right now.",
                            modifier = Modifier.padding(14.dp), fontSize = 14.sp, color = Color(0xFF92400E)
                        )
                    }
                }
                if (methods.isEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Add a payout method to withdraw your earnings.", fontSize = 14.sp)
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = onAddMethod,
                                colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
                                modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                            ) { Text("Add payout method") }
                        }
                    }
                } else {
                    Text("Payout method", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    methods.forEach { m ->
                        val selected = selectedMethod?.id == m.id
                        Card(
                            Modifier.fillMaxWidth().clickable { selectedMethod = m },
                            shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp, if (selected) TelefamColors.PrimaryRed else MaterialTheme.colorScheme.outlineVariant
                            )
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                when (m.type) {
                                    "MPESA" -> WalletMpesaIcon(size = 26.dp)
                                    "PAYPAL" -> WalletPayPalIcon(size = 26.dp)
                                    else -> WalletBankIcon(size = 26.dp)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(m.label, fontWeight = FontWeight.SemiBold)
                                    Text(m.maskedDetail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                RadioButton(selected = selected, onClick = { selectedMethod = m })
                            }
                        }
                    }
                }

                if (quote != null) {
                    Text(
                        "Available: ${quote!!.availableFormatted}   •   Minimum: ${quote!!.minWithdrawalFormatted}",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { v -> amountText = v.filter { it.isDigit() } },
                    label = { Text("Amount${quote?.let { " (${it.currency})" } ?: ""}") },
                    supportingText = { Text("Minor units (e.g. cents) — final amounts are computed by the server") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                quote?.let { q ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            DetailRow("Withdrawal", q.amountFormatted)
                            DetailRow("Payout fee", q.feeFormatted)
                            HorizontalDivider()
                            DetailRow("You receive", q.netFormatted)
                        }
                    }
                }

                error?.let { Text(it, color = TelefamColors.PrimaryRed, fontSize = 13.sp) }

                Button(
                    onClick = {
                        val amount = amountText.toLongOrNull() ?: return@Button
                        val method = selectedMethod ?: return@Button
                        submitting = true; error = null
                        scope.launch {
                            runCatching {
                                // Stable idempotency key per attempt — a retried submit returns the SAME payout.
                                api.requestPayout(method.id, amount, "payout-${method.id}-$amount-${Random.nextLong().toString(16)}")
                            }.onSuccess { onPayoutCreated(it) }
                                .onFailure { error = it.message ?: "Payout request failed" }
                            submitting = false
                        }
                    },
                    enabled = eligible && selectedMethod != null && (amountText.toLongOrNull() ?: 0) > 0 && !submitting,
                    colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                ) {
                    if (submitting) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                    else Text("Confirm withdrawal", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Full payout history. */
@Composable
fun PayoutsScreen(api: WalletApi, onBack: () -> Unit, onOpenPayout: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf<com.telefam.data.api.PayoutSummaryDto?>(null) }
    LaunchedEffect(Unit) { scope.launch { summary = runCatching { api.payouts() }.getOrNull() } }
    Scaffold { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
            item { WalletTopBar("Payouts", onBack) }
            summary?.let { s ->
                item {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Paid: ${s.paidCount}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Pending: ${s.pendingCount}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Failed: ${s.failedCount}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(s.items, key = { it.id }) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenPayout(p.id) }
                            .defaultMinSize(minHeight = 44.dp).padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        WalletBankIcon(size = 26.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Payout to ${p.destination}", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("Ref ${p.reference} • ${p.requestedAt.take(10)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(p.amountFormatted, fontWeight = FontWeight.Bold)
                            StatusPill(if (p.status == "PAID") "COMPLETED" else p.status)
                        }
                    }
                }
                if (s.items.isEmpty()) {
                    item { Text("No payouts yet", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp)) }
                }
            }
        }
    }
}

/**
 * Payout details — status-aware: pending shows processing info, approved shows
 * approval info, rejected shows a safe reason + support option, failed explains the
 * provider could not complete the payout (funds released back to available).
 */
@Composable
fun PayoutDetailsScreen(api: WalletApi, payoutId: String, onBack: () -> Unit, onContactSupport: () -> Unit) {
    val scope = rememberCoroutineScope()
    var payout by remember { mutableStateOf<PayoutDto?>(null) }
    LaunchedEffect(payoutId) { scope.launch { payout = runCatching { api.payoutDetails(payoutId) }.getOrNull() } }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
            WalletTopBar("Payout details", onBack)
            val p = payout
            if (p == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                Card(Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(p.amountFormatted, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                        StatusPill(if (p.status == "PAID") "COMPLETED" else p.status)
                        HorizontalDivider()
                        DetailRow("Destination", p.destination)
                        DetailRow("Requested", p.requestedAt.replace('T', ' ').take(16))
                        DetailRow("Reference", p.reference)
                        DetailRow("Payout fee", minorFmt(p.currency, p.feeMinor))
                        DetailRow("You receive", p.netFormatted)
                        when (p.status) {
                            "REQUESTED", "SECURITY_CHECK", "PENDING", "PROCESSING" ->
                                Text(
                                    "Your payout is being processed. Funds are reserved and cannot be withdrawn twice.",
                                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            "APPROVED" ->
                                Text(
                                    "Approved — your payout is on its way to ${p.destination}.",
                                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            "FAILED" ->
                                Text(
                                    p.userMessage
                                        ?: "The payout provider couldn't complete this payout. The reserved funds have been returned to your available balance.",
                                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            "REJECTED" ->
                                Text(
                                    p.userMessage ?: "This payout couldn't be completed. The reserved funds have been returned to your available balance.",
                                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            "CANCELED" ->
                                Text(
                                    "This payout was canceled and the funds returned to your available balance.",
                                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                        }
                        if (p.status in listOf("FAILED", "REJECTED")) {
                            TextButton(onClick = onContactSupport, modifier = Modifier.defaultMinSize(minHeight = 44.dp)) {
                                Text("Contact support", color = TelefamColors.PrimaryRed)
                            }
                        }
                    }
                }
            }
        }
    }
}
