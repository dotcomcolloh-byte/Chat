package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.shared.generated.resources.*
import com.telefam.ui.components.SettingsTopBar
import com.telefam.ui.theme.TelefamColors
import org.jetbrains.compose.resources.stringResource

data class MessageRequestItem(
    val id: String,
    val fromName: String,
    val previewText: String?,
    val fromVerified: Boolean = false
)

@Composable
fun MessageRequestsScreen(
    requests: List<MessageRequestItem>,
    onBackClick: () -> Unit,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SettingsTopBar(title = stringResource(Res.string.requests_title), onBackClick = onBackClick)

        if (requests.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.requests_empty), color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
                items(requests, key = { it.id }) { req ->
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(1.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(44.dp).clip(CircleShape).background(TelefamColors.PrimaryRed.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(req.fromName.take(1).uppercase(), fontWeight = FontWeight.Bold, color = TelefamColors.PrimaryRed)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(req.fromName, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                                        if (req.fromVerified) {
                                            Spacer(Modifier.width(5.dp))
                                            com.telefam.ui.components.VerifiedBadge(size = 15.dp)
                                        }
                                    }
                                    if (req.previewText != null) {
                                        Text(req.previewText, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                    }
                                }
                            }
                            Spacer(Modifier.height(14.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(onClick = { onDecline(req.id) }, modifier = Modifier.weight(1f)) {
                                    Text(stringResource(Res.string.decline))
                                }
                                Button(onClick = { onAccept(req.id) }, modifier = Modifier.weight(1f)) {
                                    Text(stringResource(Res.string.accept))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
