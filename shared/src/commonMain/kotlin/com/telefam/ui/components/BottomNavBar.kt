package com.telefam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.telefam.ui.theme.TelefamColors

enum class HomeTab { CHATS, CONTACTS, POST, FEEDS, PROFILE }

@Composable
fun BottomNavBar(
    selectedTab: HomeTab,
    onTabSelected: (HomeTab) -> Unit,
    profileAvatarUrl: String?,
    profileOnline: Boolean = true,
    chatsLabel: String,
    contactsLabel: String,
    postLabel: String,
    feedsLabel: String,
    profileLabel: String
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top
        ) {
            NavItem(
                icon = Icons.Filled.ChatBubble, label = chatsLabel,
                selected = selectedTab == HomeTab.CHATS,
                onClick = { onTabSelected(HomeTab.CHATS) }
            )
            NavItem(
                icon = Icons.Outlined.Group, label = contactsLabel,
                selected = selectedTab == HomeTab.CONTACTS,
                onClick = { onTabSelected(HomeTab.CONTACTS) }
            )

            // Raised center "post" button.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .offset(y = (-14).dp)
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(TelefamColors.PrimaryRed)
                        .clickable { onTabSelected(HomeTab.POST) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Add, contentDescription = postLabel, tint = TelefamColors.White, modifier = Modifier.size(28.dp))
                }
            }

            NavItem(
                icon = Icons.Outlined.Videocam, label = feedsLabel,
                selected = selectedTab == HomeTab.FEEDS,
                onClick = { onTabSelected(HomeTab.FEEDS) }
            )

            // Profile tab with real avatar (Coil) + online dot, matching the reference.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onTabSelected(HomeTab.PROFILE) }
            ) {
                Box {
                    Box(
                        Modifier.size(28.dp).clip(CircleShape)
                            .border(1.5.dp, if (selectedTab == HomeTab.PROFILE) TelefamColors.PrimaryRed else MaterialTheme.colorScheme.outline, CircleShape)
                    ) {
                        if (profileAvatarUrl != null) {
                            AsyncImage(
                                model = profileAvatarUrl,
                                contentDescription = profileLabel,
                                modifier = Modifier.fillMaxSize().clip(CircleShape)
                            )
                        } else {
                            Icon(Icons.Filled.Person, contentDescription = profileLabel, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxSize())
                        }
                    }
                    if (profileOnline) {
                        Box(
                            Modifier.size(9.dp).align(Alignment.BottomEnd)
                                .clip(CircleShape).background(androidx.compose.ui.graphics.Color(0xFF34C759))
                                .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    profileLabel, fontSize = 12.sp,
                    color = if (selectedTab == HomeTab.PROFILE) TelefamColors.PrimaryRed else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    fontWeight = if (selectedTab == HomeTab.PROFILE) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) TelefamColors.PrimaryRed else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 12.sp, color = tint, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        if (selected) {
            Spacer(Modifier.height(3.dp))
            Box(Modifier.width(20.dp).height(2.dp).background(TelefamColors.PrimaryRed))
        }
    }
}
