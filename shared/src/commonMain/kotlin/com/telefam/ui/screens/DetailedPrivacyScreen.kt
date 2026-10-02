package com.telefam.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.data.api.PrivacySettingsDto
import com.telefam.ui.components.SettingsTopBar

@Composable
private fun PrivacyValueRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
    }
}

private fun accessLabel(v: String?): String = when (v) {
    "ANYONE" -> "Everyone"
    "CONTACTS" -> "Contacts"
    "NOBODY" -> "No one"
    else -> "Everyone"
}

@Composable
fun DetailedPrivacyScreen(
    settings: PrivacySettingsDto?,
    onBack: () -> Unit,
    onOpenOption: (field: String, title: String) -> Unit,
    onToggleBool: (field: String, value: Boolean) -> Unit,
) {
    val divider = @Composable { HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)) }

    Column(Modifier.fillMaxSize()) {
        SettingsTopBar("Privacy", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Spacer(Modifier.height(18.dp))
            Text("Visibility", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(6.dp))
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                Column {
                    PrivacyValueRow("Who can see my posts", accessLabel(settings?.whoCanSeePosts)) {
                        onOpenOption("whoCanSeePosts", "Who can see my posts")
                    }
                    divider()
                    PrivacyValueRow("Who can see my likes", accessLabel(settings?.whoCanSeeLikes)) {
                        onOpenOption("whoCanSeeLikes", "Who can see my likes")
                    }
                    divider()
                    PrivacyValueRow("Who can comment", accessLabel(settings?.whoCanComment)) {
                        onOpenOption("whoCanComment", "Who can comment")
                    }
                    divider()
                    PrivacyValueRow("Who can see my following", accessLabel(settings?.whoCanSeeFollowing)) {
                        onOpenOption("whoCanSeeFollowing", "Who can see my following")
                    }
                    divider()
                    PrivacyValueRow("Who can see my followers", accessLabel(settings?.whoCanSeeFollowers)) {
                        onOpenOption("whoCanSeeFollowers", "Who can see my followers")
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("Interactions", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(6.dp))
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                Column {
                    PrivacyValueRow("Who can message me", accessLabel(settings?.messageRequests)) {
                        onOpenOption("messageRequests", "Who can message me")
                    }
                    divider()
                    PrivacyValueRow("Who can mention me", accessLabel(settings?.whoCanMentionMe)) {
                        onOpenOption("whoCanMentionMe", "Who can mention me")
                    }
                    divider()
                    PrivacyValueRow("Who can tag me", accessLabel(settings?.whoCanTagMe)) {
                        onOpenOption("whoCanTagMe", "Who can tag me")
                    }
                    divider()
                    PrivacyValueRow("Who can follow me", accessLabel(settings?.whoCanFollowMe)) {
                        onOpenOption("whoCanFollowMe", "Who can follow me")
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("Account", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(6.dp))
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Private account", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("Only approved followers can see your posts.",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                        }
                        Switch(checked = settings?.privateAccount ?: false,
                            onCheckedChange = { onToggleBool("privateAccount", it) })
                    }
                    divider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Profile discovery", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("Allow others to find your profile in search and suggestions.",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                        }
                        Switch(checked = settings?.allowProfileDiscovery ?: true,
                            onCheckedChange = { onToggleBool("allowProfileDiscovery", it) })
                    }
                    divider()
                    PrivacyValueRow("Who can see my activity", accessLabel(settings?.whoCanSeeActivity)) {
                        onOpenOption("whoCanSeeActivity", "Who can see my activity")
                    }
                }
            }
            Text(
                "These settings control who can see and interact with your content across Telefam.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
        }
    }
}
