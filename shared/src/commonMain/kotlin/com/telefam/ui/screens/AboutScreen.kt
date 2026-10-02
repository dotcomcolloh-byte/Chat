package com.telefam.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.ui.components.SettingsTopBar
import com.telefam.ui.theme.TelefamColors

object AppInfo {
    const val VERSION_NAME = "1.0.0"
    const val VERSION_CODE = 1
}

@Composable
fun AboutScreen(onBack: () -> Unit, onOpenTerms: () -> Unit, onOpenLicenses: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        SettingsTopBar("About", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            Surface(shape = CircleShape, color = TelefamColors.PrimaryRed, modifier = Modifier.size(84.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.PlayCircle, null, tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(44.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("Telefam", fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text("Version ${AppInfo.VERSION_NAME} (${AppInfo.VERSION_CODE})",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
            Spacer(Modifier.height(24.dp))
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                Column {
                    AboutRow("Terms of Service", onOpenTerms)
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    AboutRow("Open-source licenses", onOpenLicenses)
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("© 2026 Telefam. All rights reserved.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
            Text("Made with Kotlin Multiplatform.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
        }
    }
}

@Composable
private fun AboutRow(title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
    }
}
