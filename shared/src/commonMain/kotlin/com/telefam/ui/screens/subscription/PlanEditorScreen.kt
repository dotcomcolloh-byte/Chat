@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.telefam.ui.screens.subscription

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.data.api.SubscriptionPlanDto
import com.telefam.subscriptions.SubscriptionViewModel
import com.telefam.ui.theme.TelefamColors
import kotlinx.coroutines.launch

/**
 * Create / edit a subscription plan. The price is entered in the creator's own
 * currency (server-filled from the profile country, USD when unset) and stored
 * in minor units; fans are charged the plan price via their country's provider.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanEditorScreen(
    viewModel: SubscriptionViewModel,
    existing: SubscriptionPlanDto?,
    currency: String,
    onBack: () -> Unit
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var description by remember { mutableStateOf(existing?.description ?: "") }
    var interval by remember { mutableStateOf(existing?.interval ?: "MONTHLY") }
    var priceText by remember {
        mutableStateOf(existing?.let { "%.2f".format(it.priceMinor / 100.0).trimEnd('0').trimEnd('.') } ?: "")
    }
    var mostPopular by remember { mutableStateOf(existing?.isMostPopular ?: false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val priceMinor = priceText.toDoubleOrNull()?.let { (it * 100).toLong() }
    val valid = name.isNotBlank() && priceMinor != null && priceMinor in 50..100_000_000

    if (showDeleteConfirm && existing != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete plan?", fontWeight = FontWeight.Bold) },
            text = { Text("Fans won't be able to subscribe to \"${existing.name}\" anymore. Existing subscribers keep access until their current period ends.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    scope.launch {
                        if (viewModel.deletePlan(existing.id)) onBack()
                        else snackbar.showSnackbar("Couldn't delete the plan — check your connection.")
                    }
                }) { Text("Delete", color = TelefamColors.PrimaryRed) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Keep") } }
        )
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
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
                Text(if (existing == null) "Create Plan" else "Edit Plan",
                    fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (existing != null) {
                    IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete plan", tint = TelefamColors.PrimaryRed)
                    }
                }
            }

            Box(
                Modifier.padding(16.dp).size(64.dp).clip(CircleShape)
                    .background(TelefamColors.PrimaryRed.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) { Crown3DIcon(Modifier.size(40.dp)) }

            Text("Plan name", fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 16.dp))
            OutlinedTextField(
                value = name, onValueChange = { if (it.length <= 60) name = it },
                placeholder = { Text("e.g. Supporter") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(14.dp)
            )

            Text("Description", fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 10.dp))
            OutlinedTextField(
                value = description, onValueChange = { if (it.length <= 200) description = it },
                placeholder = { Text("Exclusive content, badges and more.") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(14.dp), minLines = 2
            )

            Text("Billing interval", fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 10.dp))
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                listOf("MONTHLY" to "Monthly", "WEEKLY" to "Weekly", "DAILY" to "Daily").forEach { (value, label) ->
                    FilterChip(
                        selected = interval == value,
                        onClick = { interval = value },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = TelefamColors.PrimaryRed,
                            selectedLabelColor = androidx.compose.ui.graphics.Color.White
                        ),
                        modifier = Modifier.padding(end = 8.dp).heightIn(min = 44.dp)
                    )
                }
            }

            Text("Price ($currency)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 10.dp))
            OutlinedTextField(
                value = priceText,
                onValueChange = { if (it.isEmpty() || it.matches(Regex("^\\d{0,7}(\\.\\d{0,2})?$"))) priceText = it },
                placeholder = { Text("e.g. 4.99") },
                singleLine = true,
                supportingText = {
                    Text("Charged every ${intervalLabel(interval)} in $currency. Fans pay with Paystack or PayPal depending on their country.")
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(14.dp)
            )

            if (existing != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Most Popular", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("Highlight this plan on your subscribe page",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Switch(checked = mostPopular, onCheckedChange = { mostPopular = it })
                }
            }

            error?.let {
                Text(it, color = TelefamColors.PrimaryRed, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }

            Button(
                onClick = {
                    val price = priceMinor ?: return@Button
                    saving = true; error = null
                    scope.launch {
                        val failure = if (existing == null)
                            viewModel.createPlan(name, description, interval, price)
                        else
                            viewModel.updatePlan(existing.id, name, description, price, mostPopular)
                        saving = false
                        if (failure == null) onBack()
                        else error = "Couldn't save the plan. Check your connection and try again."
                    }
                },
                enabled = valid && !saving,
                colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp)
            ) {
                if (saving) CircularProgressIndicator(color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(20.dp))
                else Text(if (existing == null) "Create Plan" else "Save Changes",
                    color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Manage Plans — every plan (active and archived) with edit + delete. */
@Composable
fun ManagePlansScreen(
    viewModel: SubscriptionViewModel,
    onBack: () -> Unit,
    onCreatePlan: () -> Unit,
    onEditPlan: (SubscriptionPlanDto) -> Unit
) {
    val state by viewModel.creator.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadCreator() }

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
                Text("Manage Plans", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
            if (state.plans.isEmpty() && !state.loading) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Crown3DIcon(Modifier.size(56.dp))
                    Spacer(Modifier.height(14.dp))
                    Text("No plans yet", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("Create a plan with a price your fans pay to subscribe.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
                    items(state.plans.size) { i ->
                        val plan = state.plans[i]
                        ListItem(
                            headlineContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(plan.name, fontWeight = FontWeight.SemiBold)
                                    if (!plan.isActive) {
                                        Spacer(Modifier.width(6.dp))
                                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(50)) {
                                            Text("Archived", fontSize = 10.sp,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                                        }
                                    }
                                }
                            },
                            supportingContent = {
                                Text("${plan.formattedPrice} / ${intervalLabel(plan.interval)} · ${plan.subscriberCount} subscribers")
                            },
                            leadingContent = {
                                Box(Modifier.size(42.dp).clip(CircleShape).background(TelefamColors.PrimaryRed.copy(alpha = 0.08f)),
                                    contentAlignment = Alignment.Center) { Crown3DIcon(Modifier.size(24.dp)) }
                            },
                            trailingContent = {
                                Text("Edit", color = TelefamColors.PrimaryRed, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            },
                            modifier = Modifier.clickable { onEditPlan(plan) }
                        )
                    }
                }
            }
            Button(
                onClick = onCreatePlan,
                colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp)
            ) { Text("Create New Plan", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold) }
        }
    }
}
