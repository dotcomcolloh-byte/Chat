package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.data.api.SessionDto
import com.telefam.ui.components.ConfirmDialog
import com.telefam.ui.components.DevicePermission
import com.telefam.ui.components.rememberOpenAppSettings
import com.telefam.ui.components.rememberPermissionStatus
import com.telefam.ui.theme.TelefamColors

/**
 * Settings → Security & permissions: 2-step verification, login alerts, the real
 * device/session list (with per-device and "all other devices" sign-out), and the
 * live OS permission status for camera/mic/photos/location.
 */
@Composable
fun SecurityPermissionsScreen(
    twoFactorEnabled: Boolean,
    loginAlertsEnabled: Boolean,
    sessions: List<SessionDto>,
    loading: Boolean,
    onBack: () -> Unit,
    onChangePassword: () -> Unit,
    onToggleTwoFactor: (Boolean) -> Unit,
    onToggleLoginAlerts: (Boolean) -> Unit,
    onRevokeSession: (SessionDto) -> Unit,
    onRevokeOtherSessions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val openSettings = rememberOpenAppSettings()
    var showRevokeOthersConfirm by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            Text("Security & permissions", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground)
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(8.dp))

            // --- Login security ---
            SecurityHeader("Login security")
            RowLink(Icons.Outlined.Key, "Change password", "Update your account password", onChangePassword)
            RowSwitch(
                Icons.Outlined.Pin, "Two-step verification",
                "Require an email code after your password when signing in",
                twoFactorEnabled
            ) { onToggleTwoFactor(it) }
            RowSwitch(
                Icons.Outlined.NotificationsActive, "Login alerts",
                "Get notified when a new device signs in",
                loginAlertsEnabled
            ) { onToggleLoginAlerts(it) }

            Spacer(Modifier.height(20.dp))

            // --- Devices ---
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SecurityHeader("Devices", Modifier.weight(1f))
                if (sessions.count { !it.current } > 0) {
                    TextButton(onClick = { showRevokeOthersConfirm = true }) {
                        Text("Log out all other devices", color = TelefamColors.PrimaryRed, fontSize = 13.sp)
                    }
                }
            }
            if (loading && sessions.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TelefamColors.PrimaryRed, modifier = Modifier.size(26.dp), strokeWidth = 3.dp)
                }
            } else if (sessions.isEmpty()) {
                Text("No active sessions", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
            } else {
                sessions.forEach { session ->
                    SessionRow(session, onRevoke = { onRevokeSession(session) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.07f))
                }
            }

            Spacer(Modifier.height(20.dp))

            // --- Permissions (live OS status) ---
            SecurityHeader("Permissions")
            PermissionRow(Icons.Outlined.PhotoCamera, "Camera", rememberPermissionStatus(DevicePermission.CAMERA), openSettings)
            PermissionRow(Icons.Outlined.Mic, "Microphone", rememberPermissionStatus(DevicePermission.MICROPHONE), openSettings)
            PermissionRow(Icons.Outlined.PhotoLibrary, "Photos & files", rememberPermissionStatus(DevicePermission.PHOTOS), openSettings)
            PermissionRow(Icons.Outlined.Place, "Location", rememberPermissionStatus(DevicePermission.LOCATION), openSettings)

            Spacer(Modifier.height(40.dp))
        }
    }

    if (showRevokeOthersConfirm) {
        ConfirmDialog(
            title = "Log out all other devices?",
            message = "Every device except this one will be signed out immediately.",
            confirmLabel = "Log out all",
            onConfirm = { showRevokeOthersConfirm = false; onRevokeOtherSessions() },
            onDismiss = { showRevokeOthersConfirm = false }
        )
    }
}

@Composable
private fun SecurityHeader(title: String, modifier: Modifier = Modifier) {
    Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        modifier = modifier.padding(vertical = 8.dp))
}

@Composable
private fun RowLink(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
            modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
            Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f))
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f))
    }
}

@Composable
private fun RowSwitch(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
            modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
            Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f))
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = TelefamColors.PrimaryRed)
        )
    }
}

@Composable
private fun SessionRow(session: SessionDto, onRevoke: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(if (session.current) TelefamColors.PrimaryRed.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Smartphone, contentDescription = null,
                tint = if (session.current) TelefamColors.PrimaryRed else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                (session.deviceInfo?.takeIf { it.isNotBlank() } ?: "Unknown device") + if (session.current) " · This device" else "",
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground, maxLines = 1
            )
            Text("Signed in ${session.createdAt.take(10)}", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f))
        }
        if (!session.current) {
            TextButton(onClick = { confirm = true }) {
                Text("Log out", color = TelefamColors.PrimaryRed, fontSize = 13.sp)
            }
        }
    }
    if (confirm) {
        ConfirmDialog(
            title = "Log out this device?",
            message = "This device will be signed out immediately.",
            confirmLabel = "Log out",
            onConfirm = { confirm = false; onRevoke() },
            onDismiss = { confirm = false }
        )
    }
}

@Composable
private fun PermissionRow(icon: ImageVector, label: String, status: String, onManage: () -> Unit) {
    val granted = status == "GRANTED"
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onManage).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
            modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
        Text(
            if (granted) "Allowed" else "Not allowed",
            fontSize = 13.sp,
            color = if (granted) Color(0xFF2E7D32) else TelefamColors.PrimaryRed
        )
        Spacer(Modifier.width(4.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f))
    }
}
