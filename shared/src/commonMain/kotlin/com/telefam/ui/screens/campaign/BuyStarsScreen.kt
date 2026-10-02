@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.telefam.ui.screens.campaign

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.campaigns.CampaignViewModel
import com.telefam.chat.InAppWebView
import com.telefam.data.api.StarPackageDto
import com.telefam.ui.screens.CreatorLoadError
import com.telefam.ui.theme.TelefamColors

/**
 * Buy Stars — matches the reference: red header, the support banner, and the
 * server-priced package list (spinning gold stars, Popular badge, per-row Buy).
 *
 * Money never moves client-side: Buy -> server-initiated checkout (Paystack or
 * PayPal, the single provider the server decides by country) in an in-app web
 * view -> poll the server, which verifies the amount and currency against the
 * provider's own API before crediting the wallet. Idempotency-Keyed, so a
 * retried or double-tapped Buy can never charge twice.
 */
@Composable
fun BuyStarsScreen(
    viewModel: CampaignViewModel,
    onBack: () -> Unit,
    onCheckout: () -> Unit = {}
) {
    val state by viewModel.buyStars.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadBuyStars() }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
        ) {
            // ---- Red header (matches reference) ----
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
                        "Buy Stars", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(48.dp))
                }
            }

            when {
                state.loading -> Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TelefamColors.PrimaryRed)
                }
                state.error -> CreatorLoadError(onRetry = { viewModel.loadBuyStars() })
                else -> {
                    // ---- Support banner ----
                    Surface(
                        color = TelefamColors.PrimaryRed.copy(alpha = 0.06f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(48.dp).clip(CircleShape).background(Color(0xFFFFF3D6)),
                                contentAlignment = Alignment.Center
                            ) { Star3D(Modifier.size(34.dp)) }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Support your favorite creators and help them grow!",
                                    fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("Buy Stars and send them as a gift during live streams, or on their content.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp)
                            }
                        }
                    }

                    Text("Choose a package", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                        modifier = Modifier.padding(horizontal = 16.dp))

                    state.paymentError?.let {
                        Text(it, color = TelefamColors.PrimaryRed, fontSize = 13.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp))
                    }

                    Spacer(Modifier.height(8.dp))
                    state.data?.packages?.forEach { pkg ->
                        PackageRow(
                            pkg = pkg,
                            enabled = !state.offline,
                            onBuy = { viewModel.selectPackage(pkg.id); onCheckout() }
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun PackageRow(pkg: StarPackageDto, enabled: Boolean, onBuy: () -> Unit) {
    Box(Modifier.padding(horizontal = 16.dp)) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (pkg.popular) TelefamColors.PrimaryRed.copy(alpha = 0.05f) else MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(
                if (pkg.popular) 1.5.dp else 1.dp,
                if (pkg.popular) TelefamColors.PrimaryRed else MaterialTheme.colorScheme.outlineVariant
            ),
            modifier = Modifier.fillMaxWidth().padding(top = if (pkg.popular) 10.dp else 0.dp)
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(46.dp).clip(CircleShape).background(Color(0xFFFFF3D6)),
                    contentAlignment = Alignment.Center
                ) { Star3D(Modifier.size(32.dp), spinning = true) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("%,d".format(pkg.stars), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(pkg.formattedAmount, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
                Button(
                    onClick = onBuy,
                    enabled = enabled,
                    colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp),
                    modifier = Modifier.heightIn(min = 44.dp)
                ) { Text("Buy", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
            }
        }
        if (pkg.popular) {
            Surface(
                color = TelefamColors.PrimaryRed, shape = RoundedCornerShape(50),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Text("Popular", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp))
            }
        }
    }
}

/**
 * Checkout — matches the reference: the purchased package card, the single
 * server-decided payment method with its real brand mark, the security note,
 * and Proceed to Pay.
 */
@Composable
fun StarCheckoutScreen(
    viewModel: CampaignViewModel,
    onBack: () -> Unit,
    onPurchased: () -> Unit
) {
    val state by viewModel.buyStars.collectAsState()
    val pkg = state.pendingPackage
    val data = state.data

    when {
        // ---- In-app checkout (Paystack authorization_url / PayPal approve link) ----
        state.payment != null && state.paymentStatus == null -> {
            InAppWebView(
                url = state.payment!!.checkoutUrl,
                onClose = { viewModel.pollPurchase() }
            )
        }

        // ---- Verification interstitial (pending / paid / failed) ----
        state.paymentStatus != null -> {
            StarPaymentStatusScreen(
                status = state.paymentStatus!!.status,
                failureReason = state.paymentStatus!!.failureReason,
                providerLabel = data?.providerLabel ?: "the payment provider",
                stars = state.payment?.stars ?: 0,
                onDone = {
                    if (state.paymentStatus!!.status == "PAID") {
                        viewModel.clearPaymentError()
                        onPurchased()
                    } else {
                        viewModel.clearPaymentError() // back to checkout to try again
                    }
                },
                onClose = { viewModel.clearPaymentError(); onBack() }
            )
        }

        else -> Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
        ) {
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
                        "Checkout", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(48.dp))
                }
            }

            if (pkg != null) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(52.dp).clip(CircleShape).background(Color(0xFFFFF3D6)),
                            contentAlignment = Alignment.Center
                        ) { Star3D(Modifier.size(36.dp), spinning = true) }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("%,d Stars".format(pkg.stars), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(pkg.formattedAmount, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        }
                    }
                }
            }

            Text("Payment Method", fontWeight = FontWeight.Bold, fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(8.dp))

            // The server decides the single provider by country — the app never
            // offers a choice. Real brand marks, drawn as vectors.
            val provider = data?.provider ?: "PAYPAL"
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = TelefamColors.PrimaryRed.copy(alpha = 0.04f),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, TelefamColors.PrimaryRed),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (provider == "PAYSTACK") PaystackLogo(Modifier.size(36.dp)) else PayPalLogo(Modifier.size(36.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(data?.providerLabel ?: provider, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            if (provider == "PAYSTACK") "Pay securely with M-Pesa, cards and bank transfers"
                            else "Pay with your PayPal account or card",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 15.sp
                        )
                    }
                    Box(
                        Modifier.size(22.dp).clip(CircleShape)
                            .border(2.dp, TelefamColors.PrimaryRed, CircleShape),
                        contentAlignment = Alignment.Center
                    ) { Box(Modifier.size(12.dp).clip(CircleShape).background(TelefamColors.PrimaryRed)) }
                }
            }

            Spacer(Modifier.height(14.dp))
            Surface(
                color = TelefamColors.PrimaryRed.copy(alpha = 0.06f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Lock, contentDescription = null, tint = TelefamColors.PrimaryRed, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Your payment is secure and encrypted", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("We use industry-standard encryption to protect your information.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            }

            Button(
                onClick = { viewModel.startPurchase() },
                enabled = pkg != null && !state.startingPayment,
                colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(54.dp)
            ) {
                if (state.startingPayment) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(22.dp))
                else Text("Proceed to Pay", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
    }
}

/** Payment verification interstitial for star purchases. */
@Composable
private fun StarPaymentStatusScreen(
    status: String,
    providerLabel: String,
    stars: Long,
    failureReason: String? = null,
    onDone: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (status) {
            "PAID" -> {
                Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) { Star3D(Modifier.size(80.dp), spinning = true) }
                Spacer(Modifier.height(24.dp))
                Text("%,d Stars added".format(stars), fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Spacer(Modifier.height(8.dp))
                Text("Your payment was verified with $providerLabel and the stars are in your balance.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onDone,
                    colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Done", color = Color.White, fontWeight = FontWeight.Bold) }
            }
            "FAILED" -> {
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
                    onClick = onDone,
                    colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Try again", color = Color.White, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onClose) { Text("Back") }
            }
            else -> {
                Box(Modifier.size(88.dp).clip(CircleShape).background(TelefamColors.PrimaryRed.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TelefamColors.PrimaryRed, modifier = Modifier.size(40.dp))
                }
                Spacer(Modifier.height(24.dp))
                Text("Confirming your payment…", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Spacer(Modifier.height(8.dp))
                Text("We're verifying with $providerLabel. This usually takes a few seconds — don't close the app.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                TextButton(onClick = onClose) { Text("I'll check later") }
            }
        }
    }
}
