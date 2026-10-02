package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.chat.ConversationSummary
import com.telefam.shared.generated.resources.*
import com.telefam.ui.theme.TelefamColors
import org.jetbrains.compose.resources.stringResource

/**
 * Multi-select conversation picker used by Forward. Lists existing conversations
 * (from the local peer cache — works offline) and returns the chosen peer ids.
 */
@Composable
fun ForwardPickerScreen(
    conversations: List<ConversationSummary>,
    excludePeerId: String? = null,
    sending: Boolean = false,
    onBack: () -> Unit,
    onSend: (List<String>) -> Unit
) {
    var selected by remember { mutableStateOf(setOf<String>()) }
    val candidates = conversations.filter { it.peerId != excludePeerId }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                    .background(TelefamColors.PrimaryRed)
                    .statusBarsPadding().padding(horizontal = 4.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null, tint = Color.White) }
                Text(
                    stringResource(Res.string.forward_title), color = Color.White,
                    fontWeight = FontWeight.Bold, fontSize = 19.sp, modifier = Modifier.weight(1f)
                )
            }
            LazyColumn(Modifier.weight(1f)) {
                items(candidates, key = { it.peerId }) { conv ->
                    val isSelected = conv.peerId in selected
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            selected = if (isSelected) selected - conv.peerId else selected + conv.peerId
                        }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(46.dp).clip(CircleShape).background(TelefamColors.PrimaryRed.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                conv.displayName.firstOrNull()?.uppercase() ?: "?",
                                color = TelefamColors.PrimaryRed, fontWeight = FontWeight.Bold, fontSize = 18.sp
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(conv.displayName, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        Icon(
                            if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                            contentDescription = null,
                            tint = if (isSelected) TelefamColors.PrimaryRed else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                }
            }
            if (selected.isNotEmpty()) {
                Button(
                    onClick = { onSend(selected.toList()) },
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed)
                ) {
                    if (sending) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("${stringResource(Res.string.forward_send)} (${selected.size})")
                    }
                }
            }
        }
    }
}
