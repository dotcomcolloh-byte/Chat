package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.telefam.connect.ProfileDetailsDto
import com.telefam.ui.components.NameWithBadge
import com.telefam.ui.components.VerifiedBadgeVariant
import com.telefam.ui.theme.TelefamColors

/** Top bar matching the creator reference screens: back arrow, bold title, help (?). */
@Composable
fun CreatorTopBar(title: String, onBack: () -> Unit, onHelp: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onBackground)
        }
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
        IconButton(onClick = onHelp, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.HelpOutline, contentDescription = "About this screen",
                tint = MaterialTheme.colorScheme.onBackground)
        }
    }
}

/** The (?) explanation dialog shown on every creator screen. */
@Composable
fun CreatorHelpDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(text, fontSize = 14.sp, lineHeight = 20.sp) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Got it", color = TelefamColors.PrimaryRed) }
        }
    )
}

/** Avatar + name (+ verified badge when enabled) + @handle + Creator chip — shared header. */
@Composable
fun CreatorProfileHeader(profile: ProfileDetailsDto?, modifier: Modifier = Modifier) {
    Row(modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = profile?.avatarUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(88.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Spacer(Modifier.width(16.dp))
        Column {
            NameWithBadge(
                name = profile?.displayName ?: "",
                isVerified = profile?.isVerified == true,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
                badgeVariant = VerifiedBadgeVariant.ON_SURFACE,
                badgeSize = 18.dp
            )
            Spacer(Modifier.height(2.dp))
            Text(profile?.displayHandle ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Surface(
                color = TelefamColors.PrimaryRed.copy(alpha = 0.08f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Creator", color = TelefamColors.PrimaryRed, fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
    }
}

/** Period dropdown: Last 7 Days / Last 30 Days / Last Year. */
@Composable
fun PeriodDropdown(selectedDays: Int, onSelected: (Int) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val label = when (selectedDays) {
        30 -> "Last 30 Days"
        365 -> "Last Year"
        else -> "Last 7 Days"
    }
    Box(modifier) {
        Surface(
            onClick = { open = true },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(7 to "Last 7 Days", 30 to "Last 30 Days", 365 to "Last Year").forEach { (days, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onSelected(days) })
            }
        }
    }
}

/** One tinted-icon stat cell from the reference (icon circle, big value, label, delta). */
@Composable
fun CreatorStatCell(
    icon: ImageVector,
    tint: Color,
    value: String,
    label: String,
    deltaPercent: Double?,
    deltaColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier
) {
    Column(modifier, horizontalAlignment = Alignment.Start) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (deltaPercent != null) {
                Icon(
                    if (deltaPercent >= 0) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown,
                    contentDescription = null, tint = deltaColor, modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(3.dp))
            }
            Text(
                deltaPercent?.let { p -> "${kotlin.math.abs(p).toInt()}%" } ?: "— 0%",
                fontSize = 11.sp, color = deltaColor
            )
        }
    }
}

/** Error card with a retry action, used when there's no cached data and the load failed. */
@Composable
fun CreatorLoadError(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Couldn't load this right now.", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("Check your connection and try again.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
            shape = RoundedCornerShape(12.dp)
        ) { Text("Retry") }
    }
}
