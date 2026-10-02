@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.telefam.ui.screens.subscription

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import com.telefam.chat.InAppWebView
import com.telefam.data.api.ApiConfig
import com.telefam.data.api.MySubscriptionDto
import com.telefam.data.api.SubscribePageDto
import com.telefam.data.api.SubscriptionPlanDto
import com.telefam.subscriptions.SubscriptionViewModel
import com.telefam.ui.components.VerifiedBadge
import com.telefam.ui.screens.CreatorLoadError
import com.telefam.ui.theme.TelefamColors

/**
 * Fan-facing Subscribe screen — matches the reference: red header, creator card
 * with verified badge, "Support" banner with the gold crown, plan cards (Most
 * Popular highlighted), the six-perk "What You'll Get" grid, the Subscribe Now
 * CTA, and the provider note (Paystack or PayPal, server-decided by country).
 *
 * Payment flow: Subscribe -> server-initiated checkout in an in-app web view ->
 * server-side provider verification (polled until PAID / FAILED) -> subscribed
 * state. Unsubscribe lives on the subscribed state with a confirm dialog.
 */
@Composable
fun SubscribeToCreatorScreen(
    creatorId: String,
    viewModel: SubscriptionViewModel,
    onBack: () -> Unit,
    onMySubscriptions: () -> Unit = {}
) {
    val state by viewModel.fan.collectAsState()
    var paying by remember { mutableStateOf(false) }
    var showUnsubConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(creatorId) { viewModel.loadCreatorPage(creatorId) }

    val page = state.page

    when {
        // ---- In-app checkout (Paystack authorization_url / PayPal approve link) ----
        paying && state.payment != null -> {
            InAppWebView(
                url = state.payment!!.checkoutUrl,
                onClose = {
                    paying = false
                    state.payment?.let { viewModel.pollPayment(it.paymentId, creatorId) }
                }
            )
        }

        // ---- Payment verification interstitial (pending / paid / failed) ----
        state.paymentStatus != null && state.paymentStatus!!.status != "PAID" -> {
            PaymentStatusScreen(
                status = state.paymentStatus!!.status,
                failureReason = state.paymentStatus!!.failureReason,
                providerLabel = page?.providerLabel ?: "the payment provider",
                onRetry = {
                    viewModel.clearPaymentError()
                    viewModel.loadCreatorPage(creatorId)
                },
                onClose = onBack
            )
        }

        else -> {
            Scaffold { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding)
                        .background(MaterialTheme.colorScheme.background)
                        .verticalScroll(rememberScrollState())
                ) {
                    // ---- Red header (matches reference) ----
                    Box(
                        Modifier.fillMaxWidth()
                            .background(
                                Brush.verticalGradient(listOf(TelefamColors.PrimaryRed, TelefamColors.PrimaryRedDark))
                            )
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
                                "Subscribe", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onMySubscriptions) {
                                Text("My subscriptions", color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }

                    if (state.error) {
                        CreatorLoadError(onRetry = { viewModel.loadCreatorPage(creatorId) })
                        return@Column
                    }
                    if (page == null) {
                        Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = TelefamColors.PrimaryRed)
                        }
                        return@Column
                    }

                    CreatorCard(page)

                    // ---- Subscribed state (manage / unsubscribe) ----
                    val existing = page.viewerSubscription
                    if (existing != null && existing.status in listOf("ACTIVE", "PAST_DUE")) {
                        ActiveSubscriptionCard(
                            sub = existing,
                            unsubscribing = state.unsubscribing,
                            onUnsubscribe = { showUnsubConfirm = true }
                        )
                    } else {
                        SupportBanner(page.name ?: "this creator")
                        PlansSection(
                            plans = page.plans,
                            selectedPlanId = state.selectedPlanId,
                            startingPayment = state.startingPayment,
                            onSelect = viewModel::selectPlan,
                            onSubscribePlan = {
                                viewModel.selectPlan(it)
                                viewModel.startPayment { paying = true }
                            }
                        )
                        PerksGrid()
                    }

                    state.paymentError?.let {
                        Text(
                            it, color = TelefamColors.PrimaryRed, fontSize = 13.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
                        )
                    }

                    // ---- Bottom CTA ----
                    if (existing?.status == "ACTIVE") {
                        OutlinedButton(
                            onClick = onBack,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(56.dp)
                        ) { Text("Back to profile", fontWeight = FontWeight.Bold) }
                    } else {
                        Button(
                            onClick = { viewModel.startPayment { paying = true } },
                            enabled = state.selectedPlanId != null && !state.startingPayment && page.plans.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(56.dp)
                        ) {
                            if (state.startingPayment) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(22.dp))
                            } else {
                                Text("Subscribe Now", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                        }
                        if (page.plans.isEmpty()) {
                            Text(
                                "This creator hasn't set up any plans yet — check back soon.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                            )
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 28.dp, top = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Lock, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Your payment is secure and encrypted. Powered by ${page.providerLabel}.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }

    if (showUnsubConfirm) {
        AlertDialog(
            onDismissRequest = { showUnsubConfirm = false },
            title = { Text("Unsubscribe?", fontWeight = FontWeight.Bold) },
            text = { Text("Auto-renewal will stop. You'll keep subscriber access through ${page?.viewerSubscription?.renewsAt?.take(10) ?: "the end of your paid period"}.") },
            confirmButton = {
                TextButton(onClick = {
                    showUnsubConfirm = false
                    scope.launch {
                        page?.viewerSubscription?.let { viewModel.unsubscribe(it.subscriptionId, creatorId) }
                    }
                }) { Text("Cancel renewal", color = TelefamColors.PrimaryRed) }
            },
            dismissButton = { TextButton(onClick = { showUnsubConfirm = false }) { Text("Keep renewal") } }
        )
    }
}

@Composable
private fun CreatorCard(page: SubscribePageDto) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Box {
            AsyncImage(
                model = page.avatarUrl?.let { if (it.startsWith("http")) it else ApiConfig.baseUrl.trimEnd('/') + it },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
            )
            if (page.isVerified) {
                Box(Modifier.align(Alignment.BottomEnd)) { VerifiedBadge(size = 22.dp) }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(page.name ?: page.username ?: "Creator", fontWeight = FontWeight.Bold, fontSize = 19.sp)
            Text("@${page.username ?: ""}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            if (!page.bio.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(page.bio, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
private fun SupportBanner(name: String) {
    Surface(
        color = TelefamColors.PrimaryRed.copy(alpha = 0.06f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            CrownDiscIcon(Modifier.size(44.dp), TelefamColors.PrimaryRed)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Support $name", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("Subscribe to get exclusive content, behind the scenes, private updates and more!",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp)
            }
            Crown3DIcon(Modifier.size(44.dp), palette = listOf(Color(0xFFFFD54A), Color(0xFFF5A300)))
        }
    }
}

@Composable
private fun PlansSection(
    plans: List<SubscriptionPlanDto>,
    selectedPlanId: String?,
    startingPayment: Boolean,
    onSelect: (String) -> Unit,
    onSubscribePlan: (String) -> Unit
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Choose a Subscription Plan", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("Select a plan that works for you. Cancel anytime.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        plans.forEach { plan ->
            PlanCard(
                plan = plan,
                selected = plan.id == selectedPlanId,
                enabled = !startingPayment,
                onSelect = { onSelect(plan.id) },
                onSubscribe = { onSubscribePlan(plan.id) }
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun PlanCard(
    plan: SubscriptionPlanDto,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onSubscribe: () -> Unit
) {
    Box {
        Surface(
            onClick = onSelect,
            shape = RoundedCornerShape(16.dp),
            color = if (plan.isMostPopular) TelefamColors.PrimaryRed.copy(alpha = 0.05f) else MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(
                if (selected || plan.isMostPopular) 1.5.dp else 1.dp,
                if (selected || plan.isMostPopular) TelefamColors.PrimaryRed else MaterialTheme.colorScheme.outlineVariant
            ),
            modifier = Modifier.fillMaxWidth().padding(top = if (plan.isMostPopular) 12.dp else 0.dp)
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                StarDiscIcon(Modifier.size(46.dp), TelefamColors.PrimaryRed)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(plan.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(plan.formattedPrice, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(" / ${intervalLabel(plan.interval)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                    if (plan.description.isNotBlank()) {
                        Text(plan.description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
                Button(
                    onClick = onSubscribe,
                    enabled = enabled,
                    colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                    modifier = Modifier.heightIn(min = 44.dp)
                ) { Text("Subscribe", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
            }
        }
        if (plan.isMostPopular) {
            Surface(
                color = TelefamColors.PrimaryRed,
                shape = RoundedCornerShape(50),
                modifier = Modifier.align(Alignment.TopStart).padding(start = 16.dp)
            ) {
                Text("Most Popular", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun PerksGrid() {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("What You'll Get", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(10.dp))
        val perks = listOf(
            PerkDef({ CrownDiscIcon(Modifier.size(40.dp), TelefamColors.PrimaryRed) }, "Exclusive Content",
                "Access to subscriber-only posts, photos and videos."),
            PerkDef({ Disc(Modifier.size(40.dp)) { Icon(Icons.Outlined.PlayCircle, null, tint = Color.White, modifier = Modifier.size(22.dp)) } }, "Behind the Scenes",
                "Get a closer look at their creative process and daily life."),
            PerkDef({ Disc(Modifier.size(40.dp)) { Icon(Icons.Outlined.Forum, null, tint = Color.White, modifier = Modifier.size(22.dp)) } }, "Early Access",
                "Be the first to see new content and updates."),
            PerkDef({ Disc(Modifier.size(40.dp)) { Icon(Icons.AutoMirrored.Filled.Chat, null, tint = Color.White, modifier = Modifier.size(22.dp)) } }, "Private Chats",
                "Occasional updates and special messages."),
            PerkDef({ Disc(Modifier.size(40.dp)) { Icon(Icons.Outlined.Star, null, tint = Color.White, modifier = Modifier.size(22.dp)) } }, "Direct Support",
                "Help keep the creator going and grow their community."),
            PerkDef({ Disc(Modifier.size(40.dp)) { Icon(Icons.Outlined.Favorite, null, tint = Color.White, modifier = Modifier.size(22.dp)) } }, "Show Your Support",
                "Make a real difference in their journey.")
        )
        perks.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { perk ->
                    Row(Modifier.weight(1f).padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                        perk.icon()
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(perk.title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(perk.body, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 15.sp)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private data class PerkDef(val icon: @Composable () -> Unit, val title: String, val body: String)

@Composable
private fun Disc(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.clip(CircleShape).background(TelefamColors.PrimaryRed), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun ActiveSubscriptionCard(
    sub: MySubscriptionDto,
    unsubscribing: Boolean,
    onUnsubscribe: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF16A34A).copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = Color(0xFF16A34A), modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (sub.status == "PAST_DUE") "Payment needs attention" else "You're subscribed",
                        fontWeight = FontWeight.Bold, fontSize = 16.sp
                    )
                    Text("${sub.planName} · ${sub.formattedPrice} / ${intervalLabel(sub.interval)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    sub.renewsAt?.let {
                        Text(
                            if (sub.status == "PAST_DUE") "Payment retry in progress · access through ${it.take(10)}"
                            else if (sub.cancelAtPeriodEnd) "Access ends ${it.take(10)} · renewal canceled"
                            else if (sub.autoRenew) "Next billing ${it.take(10)}"
                            else "Access until ${it.take(10)} · automatic renewal unavailable",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (sub.autoRenew && !sub.cancelAtPeriodEnd) {
                OutlinedButton(
                    onClick = onUnsubscribe,
                    enabled = !unsubscribing,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TelefamColors.PrimaryRed),
                    border = androidx.compose.foundation.BorderStroke(1.dp, TelefamColors.PrimaryRed.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    if (unsubscribing) CircularProgressIndicator(color = TelefamColors.PrimaryRed, modifier = Modifier.size(18.dp))
                    else Text("Cancel renewal", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Payment verification interstitial — PENDING keeps polling, FAILED shows the provider reason. */
@Composable
private fun PaymentStatusScreen(
    status: String,
    failureReason: String?,
    providerLabel: String,
    onRetry: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (status == "FAILED") {
            Box(Modifier.size(88.dp).clip(CircleShape).background(TelefamColors.PrimaryRed.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = TelefamColors.PrimaryRed, modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text("Payment not completed", fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                failureReason ?: "$providerLabel couldn't confirm the payment. You were not charged for unconfirmed attempts.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("Try again", color = Color.White, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onClose) { Text("Back to profile") }
        } else {
            Box(Modifier.size(88.dp).clip(CircleShape).background(TelefamColors.PrimaryRed.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TelefamColors.PrimaryRed, modifier = Modifier.size(40.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text("Confirming your payment…", fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "We're verifying with $providerLabel. This usually takes a few seconds — don't close the app.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onClose) { Text("I'll check later") }
        }
    }
}
