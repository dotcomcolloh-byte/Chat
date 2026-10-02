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
fun AccessibilityScreen(settings: AppSettingsDto?, onBack: () -> Unit, onUpdate: (AppSettingsDto) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        SettingsTopBar("Accessibility", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Spacer(Modifier.height(18.dp))
            Text("Display", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(6.dp))
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                Column {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text("Text size", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text("Scales text across the whole app immediately.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("SMALL" to "Small", "MEDIUM" to "Default", "LARGE" to "Large", "XLARGE" to "XL")
                                .forEach { (v, label) ->
                                    FilterChip(selected = (settings?.textSize ?: "MEDIUM") == v,
                                        onClick = { settings?.let { onUpdate(it.copy(textSize = v)) } },
                                        label = { Text(label, fontSize = 12.sp) })
                                }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("Preview: The quick brown fox jumps over the lazy dog.", fontSize = 14.sp)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("High contrast", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("Increase color contrast for better readability.",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                        }
                        Switch(checked = settings?.highContrast ?: false,
                            onCheckedChange = { v -> settings?.let { onUpdate(it.copy(highContrast = v)) } })
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("Motion & media", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(6.dp))
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Reduced motion", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("Minimize animations and auto-playing effects.",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                        }
                        Switch(checked = settings?.reducedMotion ?: false,
                            onCheckedChange = { v -> settings?.let { onUpdate(it.copy(reducedMotion = v)) } })
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Captions", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("Show captions on videos when available.",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                        }
                        Switch(checked = settings?.captionsEnabled ?: false,
                            onCheckedChange = { v -> settings?.let { onUpdate(it.copy(captionsEnabled = v)) } })
                    }
                }
            }
            Text(
                "Screen reader: Telefam supports your device's screen reader (TalkBack / VoiceOver). Enable it in system settings; all controls in this app expose labels automatically.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
        }
    }
}
