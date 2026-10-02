package com.telefam.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.data.api.AppSettingsDto
import com.telefam.ui.components.SettingsTopBar

@Composable
private fun TimeSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun TimeManagementScreen(
    settings: AppSettingsDto?,
    todayMinutes: Int,
    weeklyMinutes: List<Int>,
    onBack: () -> Unit,
    onUpdate: (AppSettingsDto) -> Unit,
) {
    val weekTotal = weeklyMinutes.sum()
    val weekAvg = if (weeklyMinutes.isEmpty()) 0 else weekTotal / weeklyMinutes.size
    val limit = settings?.dailyLimitMinutes ?: 0
    val progress = if (limit > 0) (todayMinutes.toFloat() / limit).coerceIn(0f, 1f) else 0f

    Column(Modifier.fillMaxSize()) {
        SettingsTopBar("Time management", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Spacer(Modifier.height(18.dp))
            Text("Your usage", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(6.dp))
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("Today", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                            Text("${todayMinutes} min", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                        }
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                            Text("Daily average (7 days)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                            Text("${weekAvg} min", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                        }
                    }
                    if (limit > 0) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (todayMinutes >= limit) "Daily limit reached" else "${(limit - todayMinutes)} min left today",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("Limits", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(6.dp))
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                Column {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text("Daily limit", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text("Block the app after this much usage each day.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0 to "Off", 30 to "30m", 60 to "1h", 120 to "2h", 240 to "4h").forEach { (v, label) ->
                                FilterChip(selected = (settings?.dailyLimitMinutes ?: 0) == v,
                                    onClick = { settings?.let { onUpdate(it.copy(dailyLimitMinutes = v)) } },
                                    label = { Text(label, fontSize = 12.sp) })
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text("Break reminders", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text("Nudge me to take a break after continuous usage.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0 to "Off", 15 to "15m", 30 to "30m", 60 to "1h").forEach { (v, label) ->
                                FilterChip(selected = (settings?.breakReminderMinutes ?: 0) == v,
                                    onClick = { settings?.let { onUpdate(it.copy(breakReminderMinutes = v)) } },
                                    label = { Text(label, fontSize = 12.sp) })
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    TimeSwitch("Quiet mode", "Mute push notifications while you focus or rest.",
                        settings?.quietModeEnabled ?: false) { v -> settings?.let { onUpdate(it.copy(quietModeEnabled = v)) } }
                }
            }
            Text(
                "Limits are enforced on this device immediately and synced to your account.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
        }
    }
}
