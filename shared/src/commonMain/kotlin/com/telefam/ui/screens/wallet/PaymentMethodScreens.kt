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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.data.api.MethodChangeStartDto
import com.telefam.data.api.PayoutMethodDto
import com.telefam.data.api.WalletApi
import com.telefam.ui.theme.TelefamColors
import kotlinx.coroutines.launch

/**
 * Payment methods management. Adding/changing/removing a method goes through the
 * backend security flow: pending change → OTP sent to the account's VERIFIED email
 * (never client-chosen) → device/risk checks → applied. Only country-supported
 * methods are offered.
 */
@Composable
fun PaymentMethodDetailScreen(
    api: WalletApi,
    methodId: String,
    onBack: () -> Unit,
    onRemoved: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var method by remember { mutableStateOf<PayoutMethodDto?>(null) }
    var removeChange by remember { mutableStateOf<MethodChangeStartDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(methodId) {
        scope.launch {
            method = runCatching { api.methods() }.getOrDefault(emptyList()).firstOrNull { it.id == methodId }
        }
    }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
            WalletTopBar("Payment method", onBack)
            val m = method
            if (m == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (removeChange != null) {
                OtpConfirmCard(
                    change = removeChange!!,
                    onConfirm = { code ->
                        scope.launch {
                            val ok = runCatching { api.confirmMethodChange(removeChange!!.changeId, code) }.getOrDefault(false)
                            if (ok) onRemoved() else error = "Couldn't verify that code"
                        }
                    },
                    onCancel = { removeChange = null }
                )
            } else {
                Card(Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            when (m.type) {
                                "MPESA" -> WalletMpesaIcon(size = 30.dp)
                                "PAYPAL" -> WalletPayPalIcon(size = 30.dp)
                                else -> WalletBankIcon(size = 30.dp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(m.label, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                        DetailRow("Method", m.type)
                        DetailRow("Account", m.maskedDetail)
                        DetailRow("Status", if (m.verificationStatus == "VERIFIED") "Verified" else "Pending verification")
                        error?.let { Text(it, color = TelefamColors.PrimaryRed, fontSize = 13.sp) }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    runCatching { api.startMethodChange("REMOVE", methodId = m.id) }
                                        .onSuccess { removeChange = it }
                                        .onFailure { error = it.message }
                                }
                            },
                            modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                        ) { Text("Remove this method", color = TelefamColors.PrimaryRed) }
                    }
                }
            }
        }
    }
}

/** Add a payout method — only country-supported types (returned by the backend). */
@Composable
fun AddPaymentMethodScreen(
    api: WalletApi,
    onBack: () -> Unit,
    onAdded: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var supported by remember { mutableStateOf<List<String>>(emptyList()) }
    var type by remember { mutableStateOf<String?>(null) }
    var label by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf("") }
    var change by remember { mutableStateOf<MethodChangeStartDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        scope.launch {
            runCatching { api.supportedMethods() }.onSuccess { supported = it.types; type = it.types.firstOrNull() }
        }
    }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)
        ) {
            WalletTopBar(if (change == null) "Add payment method" else "Verify it's you", onBack)
            val pending = change
            if (pending != null) {
                OtpConfirmCard(
                    change = pending,
                    onConfirm = { code ->
                        busy = true; error = null
                        scope.launch {
                            val ok = runCatching { api.confirmMethodChange(pending.changeId, code) }.getOrDefault(false)
                            busy = false
                            if (ok) onAdded() else error = "Couldn't verify that code"
                        }
                    },
                    onCancel = { change = null }
                )
                error?.let { Text(it, color = TelefamColors.PrimaryRed, modifier = Modifier.padding(horizontal = 20.dp)) }
            } else {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Choose a method supported in your country.", fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    supported.forEach { t ->
                        val selected = type == t
                        Card(
                            Modifier.fillMaxWidth().clickable { type = t },
                            shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp, if (selected) TelefamColors.PrimaryRed else MaterialTheme.colorScheme.outlineVariant
                            )
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                when (t) {
                                    "MPESA" -> WalletMpesaIcon(size = 26.dp)
                                    "PAYPAL" -> WalletPayPalIcon(size = 26.dp)
                                    else -> WalletBankIcon(size = 26.dp)
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    when (t) { "MPESA" -> "M-Pesa"; "PAYPAL" -> "PayPal"; else -> "Bank account" },
                                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
                                )
                                RadioButton(selected = selected, onClick = { type = t })
                            }
                        }
                    }
                    OutlinedTextField(
                        value = label, onValueChange = { label = it },
                        label = { Text("Label (e.g. bank name)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = detail, onValueChange = { detail = it },
                        label = {
                            Text(
                                when (type) {
                                    "MPESA" -> "M-Pesa phone number"
                                    "PAYPAL" -> "PayPal email"
                                    else -> "Account number"
                                }
                            )
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (type == "PAYPAL") KeyboardType.Email else KeyboardType.Number
                        ),
                        supportingText = { Text("Stored encrypted; only a masked version is shown afterwards.") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    error?.let { Text(it, color = TelefamColors.PrimaryRed, fontSize = 13.sp) }
                    Button(
                        onClick = {
                            val t = type ?: return@Button
                            busy = true; error = null
                            scope.launch {
                                runCatching {
                                    api.startMethodChange(
                                        "ADD", type = t,
                                        label = label.ifBlank {
                                            when (t) { "MPESA" -> "M-Pesa"; "PAYPAL" -> "PayPal"; else -> "Bank account" }
                                        },
                                        detail = detail
                                    )
                                }.onSuccess { change = it }
                                    .onFailure { error = it.message ?: "Couldn't start verification" }
                                busy = false
                            }
                        },
                        enabled = type != null && detail.isNotBlank() && !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                    ) { Text("Continue", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

/** OTP card: the code is sent to the account's verified email (shown masked). */
@Composable
private fun OtpConfirmCard(
    change: MethodChangeStartDto,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit
) {
    var code by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Security check", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                "We sent a verification code to your account email (${change.maskedEmail}). Enter it to confirm this change.",
                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(6) },
                label = { Text("6-digit code") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onCancel, modifier = Modifier.defaultMinSize(minHeight = 44.dp)) { Text("Cancel") }
                Button(
                    onClick = { onConfirm(code) },
                    enabled = code.length == 6,
                    colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
                    modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                ) { Text("Confirm") }
            }
        }
    }
}
