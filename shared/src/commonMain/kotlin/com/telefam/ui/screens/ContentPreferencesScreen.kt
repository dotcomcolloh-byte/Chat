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
private fun PrefSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(18.dp))
    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp))
    Spacer(Modifier.height(6.dp))
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(content = content)
    }
}

@Composable
private fun PrefSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
private fun PrefChoice(
    title: String, subtitle: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(label, fontSize = 12.sp) },
                )
            }
        }
    }
}

@Composable
fun ContentPreferencesScreen(settings: AppSettingsDto?, onBack: () -> Unit, onUpdate: (AppSettingsDto) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        SettingsTopBar("Content preferences", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            PrefSection("Language") {
                PrefChoice("Content language", "Preferred language for captions and suggestions.",
                    listOf("en" to "English", "sw" to "Swahili", "fr" to "French", "es" to "Spanish"),
                    settings?.contentLanguage ?: "en") { v -> settings?.let { onUpdate(it.copy(contentLanguage = v)) } }
            }
            PrefSection("Playback") {
                PrefChoice("Video quality", "Preferred playback resolution when available.",
                    listOf("AUTO" to "Auto", "LOW" to "Low", "MEDIUM" to "Medium", "HIGH" to "High"),
                    settings?.videoQuality ?: "AUTO") { v -> settings?.let { onUpdate(it.copy(videoQuality = v)) } }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                PrefSwitch("Autoplay videos", "Play videos automatically as you scroll.",
                    settings?.autoplay ?: true) { v -> settings?.let { onUpdate(it.copy(autoplay = v)) } }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                PrefSwitch("Data saver", "Lower video quality and pause prefetching on metered connections.",
                    settings?.dataSaver ?: false) { v -> settings?.let { onUpdate(it.copy(dataSaver = v)) } }
            }
            PrefSection("Feed") {
                PrefSwitch("Suggested content", "Show recommended posts in your feed.",
                    settings?.suggestedContent ?: true) { v -> settings?.let { onUpdate(it.copy(suggestedContent = v)) } }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                PrefSwitch("Sensitive content", "Allow posts marked as sensitive to appear in feeds.",
                    settings?.sensitiveContent ?: true) { v -> settings?.let { onUpdate(it.copy(sensitiveContent = v)) } }
            }
            Text(
                "Changes apply to playback and feed behavior immediately.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
        }
    }
}
