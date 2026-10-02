package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Settings & privacy — matches the reference layout: plain top bar with back +
 * centered title, section headers, and grouped cards of icon + title + subtitle
 * rows with chevrons. The Log out button sits at the bottom.
 */
@Composable
fun SettingsMainScreen(
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenAccountPrivacy: () -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenShareProfile: () -> Unit,
    onOpenContentPreferences: () -> Unit,
    onOpenTimeManagement: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenBlocked: () -> Unit,
    onOpenReportProblem: () -> Unit,
    onOpenSafetyCenter: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenTerms: () -> Unit,
    onOpenAbout: () -> Unit,
    onLogout: () -> Unit,
    onOpenLinkedDevices: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val groupBackground = Color(0xFFF7F7F9)
    Column(
        modifier.fillMaxSize().background(groupBackground)
    ) {
        // --- Top bar: back + centered title ---
        Box(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 10.dp)
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp).align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            Text(
                "Settings and privacy",
                fontSize = 20.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            SettingsSection("Account") {
                SettingsRow(Icons.Outlined.Person, "Account", "Profile, account info, password, recovery", onOpenAccount)
                SettingsRow(Icons.Outlined.Shield, "Privacy", "Private account, blocked accounts, activity", onOpenAccountPrivacy)
                SettingsRow(Icons.Outlined.VerifiedUser, "Security & permissions", "2-step verification, device management, permissions", onOpenSecurity)
                SettingsRow(Icons.Outlined.Devices, "Linked devices", "Pair a device by QR code, manage sessions", onOpenLinkedDevices)
                SettingsRow(Icons.Outlined.QrCode2, "Share profile", "QR code, link, copy profile URL", onOpenShareProfile)
            }

            SettingsSection("Content & activity") {
                SettingsRow(Icons.Outlined.FavoriteBorder, "Content preferences", "Suggested content, language, video quality", onOpenContentPreferences)
                SettingsRow(Icons.Outlined.Schedule, "Time management", "Screen time, daily limit, mindful mode", onOpenTimeManagement)
                SettingsRow(Icons.Outlined.AccessibilityNew, "Accessibility", "Captions, text size, reduced motion", onOpenAccessibility)
            }

            SettingsSection("Privacy and safety") {
                SettingsRow(Icons.Outlined.Lock, "Privacy", "Who can see your videos, likes, comments, following", onOpenPrivacy)
                SettingsRow(Icons.Outlined.Block, "Blocked accounts", "Manage blocked users", onOpenBlocked)
                SettingsRow(Icons.Outlined.Flag, "Report a problem", "Send feedback, report abuse", onOpenReportProblem)
                SettingsRow(Icons.Outlined.HealthAndSafety, "Safety Center", "Tips, tools, resources", onOpenSafetyCenter)
            }

            SettingsSection("Support & about") {
                SettingsRow(Icons.AutoMirrored.Filled.HelpOutline, "Help & support", "FAQs, contact us, report issue", onOpenHelp)
                SettingsRow(Icons.Outlined.Description, "Terms and policies", "Terms of Service, Privacy Policy, Community Guidelines", onOpenTerms)
                SettingsRow(Icons.Outlined.Info, "About", "Version, copyright, open source", onOpenAbout)
            }

            Spacer(Modifier.height(16.dp))

            // --- Log out ---
            OutlinedButton(
                onClick = onLogout,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Log out", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Text(
            title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
        ) { content() }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(onClick = onClick, color = Color.Transparent) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
        }
    }
}
