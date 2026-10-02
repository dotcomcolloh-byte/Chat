package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.notifications.OfficialInboxViewModel
import com.telefam.ui.components.TelefamLogo
import com.telefam.ui.components.VerifiedBadge
import com.telefam.ui.theme.TelefamColors

/**
 * The official Telefam conversation.
 *
 * Read-only: there is NO message composer and there are NO call buttons —
 * only the backend (as the official account) can send here. Tapping the header
 * opens the official profile; everything else just reads.
 */
@Composable
fun OfficialChatScreen(
    viewModel: OfficialInboxViewModel,
    officialName: String = "Telefam Official",
    onBack: () -> Unit = {},
    onOpenProfile: () -> Unit = {}
) {
    val messages by viewModel.messages.collectAsState()
    val loading by viewModel.loading.collectAsState()

    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // --- Header: avatar + verified name. No call buttons, no 3-dot menu. ---
        Row(
            Modifier.fillMaxWidth()
                .background(TelefamColors.PrimaryRed)
                .padding(top = 20.dp, bottom = 14.dp, start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TelefamColors.White)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(RoundedCornerShape(24.dp))
                    .clickable(onClick = onOpenProfile)
                    .padding(end = 8.dp)
            ) {
                TelefamLogo(size = 38.dp, circleColor = TelefamColors.White, planeColor = TelefamColors.PrimaryRed)
                Spacer(Modifier.width(10.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            officialName, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                            color = TelefamColors.White
                        )
                        Spacer(Modifier.width(5.dp))
                        VerifiedBadge(size = 16.dp)
                    }
                    Text(
                        "Official account · messages only",
                        fontSize = 12.sp, color = TelefamColors.White.copy(alpha = 0.8f)
                    )
                }
            }
        }

        if (messages.isEmpty() && !loading) {
            Column(
                Modifier.fillMaxSize().padding(40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                TelefamLogo(size = 64.dp, circleColor = TelefamColors.PrimaryRed, planeColor = TelefamColors.White)
                Spacer(Modifier.height(18.dp))
                Text(
                    "Welcome to Telefam!",
                    fontWeight = FontWeight.Bold, fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Official news and updates will appear here.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages, key = { it.id }) { m ->
                    // Incoming-only bubble: no composer exists on the other side.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                        Box(
                            Modifier.widthIn(max = 300.dp)
                                .clip(RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Text(
                                m.text, fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                lineHeight = 21.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
