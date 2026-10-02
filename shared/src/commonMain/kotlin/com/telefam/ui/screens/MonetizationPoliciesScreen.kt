package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.ui.theme.TelefamColors

/** Monetization requirements & policies — opened from "Learn More" on the status screens. */
@Composable
fun MonetizationPoliciesScreen(onBack: () -> Unit) {
    var showHelp by remember { mutableStateOf(false) }

    if (showHelp) {
        CreatorHelpDialog(
            title = "About these policies",
            text = "This page explains exactly what we look at when reviewing a monetization application, and the rules that keep the program fair for every creator.",
            onDismiss = { showHelp = false }
        )
    }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
        ) {
            CreatorTopBar("Monetization Policies", onBack = onBack, onHelp = { showHelp = true })

            PolicySection("Eligibility requirements", listOf(
                "Followers — reach at least 1,000 followers on your account.",
                "Watch time — accumulate at least 4,000 minutes of watch time on your content in the last 60 days.",
                "Content — publish at least 10 public videos.",
                "Community Guidelines — your account must have no unresolved violations.",
                "Account status — your account must be in good standing."
            ))
            PolicySection("Additional criteria", listOf(
                "Original content — your videos must be your own, original and authentic creations.",
                "Authentic engagement — engagement must come from real people; bought or botted engagement disqualifies an application."
            ))
            PolicySection("How review works", listOf(
                "Monetization is invite-based. Applying adds you to the review queue — there is no automatic approval.",
                "A reviewer checks your content, account history and engagement signals.",
                "You'll see the decision on the Monetization screen. If you're not approved, we show what to improve and you can apply again."
            ))
            PolicySection("Keeping monetization", listOf(
                "Approved creators must keep following the Community Guidelines and monetization policies.",
                "Repeated or severe violations can remove monetization access.",
                "Payouts follow the payment terms in the Terms of Service."
            ))
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun PolicySection(title: String, bullets: List<String>) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.height(10.dp))
            bullets.forEach { b ->
                Row(Modifier.padding(vertical = 6.dp)) {
                    Box(
                        Modifier.padding(top = 6.dp).size(6.dp).clip(CircleShape)
                            .background(TelefamColors.PrimaryRed)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(b, fontSize = 13.sp, lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
