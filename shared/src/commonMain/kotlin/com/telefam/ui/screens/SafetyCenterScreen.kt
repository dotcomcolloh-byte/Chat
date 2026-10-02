package com.telefam.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.ui.components.SettingsTopBar

@Composable
private fun SafetyRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
    }
}

@Composable
fun SafetyCenterScreen(
    onBack: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenBlocked: () -> Unit,
    onOpenTimeManagement: () -> Unit,
    onOpenReportProblem: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        SettingsTopBar("Safety Center", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Spacer(Modifier.height(18.dp))
            Text("Protect your account", fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(6.dp))
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                Column {
                    SafetyRow(Icons.Outlined.Security, "Security & permissions",
                        "Two-step verification, login alerts and active sessions.", onOpenSecurity)
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    SafetyRow(Icons.Outlined.Lock, "Privacy controls",
                        "Choose who can see and interact with your content.", onOpenPrivacy)
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("Stay in control", fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(6.dp))
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                Column {
                    SafetyRow(Icons.Outlined.Block, "Blocked accounts",
                        "Review and manage accounts you've blocked.", onOpenBlocked)
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    SafetyRow(Icons.Outlined.Timer, "Time management",
                        "Set daily limits, break reminders and quiet mode.", onOpenTimeManagement)
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    SafetyRow(Icons.Outlined.Flag, "Report a problem",
                        "Tell us about bugs, abuse or harmful content.", onOpenReportProblem)
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "If you or someone you know is in immediate danger, contact your local emergency services first.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}
