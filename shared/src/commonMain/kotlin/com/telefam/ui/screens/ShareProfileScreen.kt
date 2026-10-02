package com.telefam.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.telefam.connect.ProfileDetailsDto
import com.telefam.data.api.ApiConfig
import com.telefam.data.api.joinBaseUrl
import com.telefam.chat.rememberEntityActions
import com.telefam.ui.components.QrCode
import com.telefam.ui.components.QrCodeImage
import com.telefam.ui.components.SettingsTopBar
import com.telefam.ui.components.avatarFallback
import com.telefam.ui.theme.TelefamColors

@Composable
fun ShareProfileScreen(profile: ProfileDetailsDto?, onBack: () -> Unit, onSaveQr: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val entityActions = rememberEntityActions()
    val url = profile?.profileUrl ?: ""
    var copied by remember { mutableStateOf(false) }
    val qrMatrix = remember(url) { if (url.isBlank()) null else QrCode.encode(url) }

    Column(Modifier.fillMaxSize()) {
        SettingsTopBar("Share profile", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(12.dp))
            AsyncImage(
                model = profile?.avatarUrl?.let { joinBaseUrl(ApiConfig.baseUrl.trimEnd('/'), it) },
                contentDescription = null,
                modifier = Modifier.size(96.dp).clip(CircleShape),
                error = avatarFallback(),
                placeholder = avatarFallback(),
            )
            Spacer(Modifier.height(12.dp))
            Text(profile?.fullName?.ifBlank { "Profile" } ?: "Profile", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("@${profile?.username ?: ""}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
            Spacer(Modifier.height(24.dp))

            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (qrMatrix != null) QrCodeImage(qrMatrix, Modifier.size(200.dp))
                    else Text("QR code unavailable for this profile link.", fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Outlined.Link, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        Text(url, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(url)); copied = true
                }, shape = RoundedCornerShape(24.dp)) {
                    Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp)); Text(if (copied) "Copied" else "Copy link")
                }
                Button(
                    onClick = { if (url.isNotBlank()) entityActions.shareText("Check out my Telefam profile: $url") },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
                ) {
                    Icon(Icons.Outlined.Share, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp)); Text("Share")
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onSaveQr) {
                Icon(Icons.Outlined.Download, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp)); Text("Save QR code")
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Anyone who scans this code or opens the link will land on your profile.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
}
