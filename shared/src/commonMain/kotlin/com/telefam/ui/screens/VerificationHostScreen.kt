package com.telefam.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.chat.InAppWebView
import com.telefam.connect.ProfileDetailsDto
import com.telefam.ui.theme.TelefamColors
import com.telefam.verification.VerificationViewModel

/**
 * Hosts the entire Get Verified flow, driven by the server-side state machine:
 *
 *   NONE            -> paywall (GetVerifiedScreen)
 *   (paying)        -> in-app checkout webview (Paystack or PayPal URL from the server)
 *   LIVENESS        -> live selfie movement challenge
 *   ID_CAPTURE      -> edge-detected front/back ID scan
 *   SUBMITTED       -> "Submitting / Submitted" progress
 *   PENDING_REVIEW,
 *   ADMIN_REVIEW    -> pending review (users never see which review path they're on)
 *   APPROVED        -> success + badge
 *   REJECTED        -> failure with reason, retry, and refund policy
 */
@Composable
fun VerificationHostScreen(
    viewModel: VerificationViewModel,
    profile: ProfileDetailsDto?,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.load() }

    var paying by remember { mutableStateOf(false) }

    when {
        paying && state.payment != null -> {
            // Real checkout (Paystack authorization_url / PayPal approve link) in-app.
            InAppWebView(
                url = state.payment!!.checkoutUrl,
                onClose = {
                    paying = false
                    state.payment?.let { viewModel.pollPayment(it.paymentId) }
                }
            )
        }

        state.status.state == "LIVENESS" && state.challenge != null -> LivenessScreen(
            challenge = state.challenge!!,
            onComplete = { ts, selfie -> viewModel.completeLiveness(ts, selfie) },
            onError = { viewModel.load() },
            onCancel = onBack
        )

        state.status.state == "LIVENESS" -> {
            // Fetch the server-issued challenge first.
            LaunchedEffect(Unit) { viewModel.startLiveness() }
            CenteredStatus("Preparing your liveness check…")
        }

        state.status.state == "ID_CAPTURE" -> IdCaptureScreen(
            onComplete = { front, back -> viewModel.submitId(front, back) },
            onError = { viewModel.load() },
            onCancel = onBack
        )

        state.status.state == "SUBMITTED" -> VerificationProgressScreen("SUBMITTED")

        state.status.state == "PENDING_REVIEW" || state.status.state == "ADMIN_REVIEW" ->
            PendingReviewScreen(onBack = onBack)

        state.status.state == "APPROVED" -> VerifiedSuccessScreen(
            expiresAt = state.status.badgeExpiresAt, onBack = onBack
        )

        state.status.state == "REJECTED" -> VerificationFailedScreen(
            reason = state.status.rejectionReason,
            canRetry = state.status.canRetry,
            refundEligible = state.status.refundEligible,
            refundPolicyDays = state.status.refundPolicyDays,
            refunded = false,
            onRetry = { viewModel.retry() },
            onRefund = { viewModel.refund() },
            onBack = onBack
        )

        else -> GetVerifiedScreen(
            profile = profile,
            pricing = state.pricing,
            loading = state.loading,
            error = state.error ?: state.paymentError,
            selectedPlan = state.selectedPlan,
            onSelectPlan = viewModel::selectPlan,
            onBack = onBack,
            onStartNow = {
                viewModel.startPayment { paying = true }
            }
        )
    }
}

@Composable
private fun CenteredStatus(text: String) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = TelefamColors.PrimaryRed)
        Spacer(Modifier.height(16.dp))
        Text(text, fontSize = 14.sp)
    }
}

@Composable
private fun VerifiedSuccessScreen(expiresAt: String?, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        com.telefam.ui.components.VerifiedBadge(size = 88.dp)
        Spacer(Modifier.height(24.dp))
        Text("You're verified!", fontWeight = FontWeight.Bold, fontSize = 24.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your badge is now live across Telefam — inbox, feeds, search, comments and your profile.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp
        )
        expiresAt?.let {
            Spacer(Modifier.height(8.dp))
            Text("Valid until ${it.take(10)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onBack,
            colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("Done", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold) }
    }
}
