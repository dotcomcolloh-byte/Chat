package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Tablet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.devices.LinkedDevicesViewModel
import com.telefam.ui.components.ConfirmDialog
import com.telefam.ui.components.QrCode
import com.telefam.ui.components.QrCodeImage
import com.telefam.ui.components.QrScannerCamera
import com.telefam.ui.components.SettingsTopBar
import com.telefam.ui.theme.TelefamColors

/**
 * Linked devices — pair a companion device by QR code (auto-refreshing every
 * 30 seconds, every refresh invalidating the previous code), approve scans with
 * an explicit Accept that dies after 5 minutes, scan another device's code from
 * this device, and manage/remove already-linked sessions.
 */
@Composable
fun LinkedDevicesScreen(
    viewModel: LinkedDevicesViewModel,
    onBack: () -> Unit,
    /** Scanner side: the new session is ready and the host is adopting it. */
    onRedirectComplete: () -> Unit,
    /** False on a signed-out device (login screen entry): only the scanner is shown. */
    signedIn: Boolean = true,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    var deviceToRemove by remember { mutableStateOf<String?>(null) }
    var scanCameraEnabled by remember { mutableStateOf(true) }

    // Scanner side: once the redirect animation completes, the host swaps screens.
    LaunchedEffect(state.redirecting, state.redirectProgress) {
        if (state.redirecting && state.redirectProgress >= 1f) onRedirectComplete()
    }
    LaunchedEffect(Unit) {
        if (signedIn) viewModel.loadSessions() else viewModel.selectTab(LinkedDevicesViewModel.Tab.SCAN)
    }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            SettingsTopBar(if (signedIn) "Linked devices" else "Link this device", onBack)

            // --- Tab switcher (hidden on signed-out devices: scanning is the only action) ---
            val tabs = if (signedIn) listOf(
                LinkedDevicesViewModel.Tab.DEVICES to "Devices",
                LinkedDevicesViewModel.Tab.QR to "Link a device",
                LinkedDevicesViewModel.Tab.SCAN to "Scan QR"
            ) else listOf(LinkedDevicesViewModel.Tab.SCAN to "Scan QR")
            if (signedIn) {
                TabRow(
                    selectedTabIndex = tabs.indexOfFirst { it.first == state.tab },
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = TelefamColors.PrimaryRed,
                    divider = { HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f)) }
                ) {
                    tabs.forEach { (tab, label) ->
                        Tab(
                            selected = state.tab == tab,
                            onClick = { viewModel.selectTab(tab) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text(
                                label,
                                fontWeight = if (state.tab == tab) FontWeight.Bold else FontWeight.Normal,
                                color = if (state.tab == tab) TelefamColors.PrimaryRed
                                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                fontSize = 14.sp,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                    }
                }
            }

            when (state.tab) {
                LinkedDevicesViewModel.Tab.DEVICES -> DevicesTab(
                    state = state,
                    onRefresh = { viewModel.loadSessions() },
                    onRemove = { deviceToRemove = it },
                    onDismissError = { viewModel.clearSessionsError() }
                )
                LinkedDevicesViewModel.Tab.QR -> QrTab(
                    state = state,
                    onRefreshQr = { viewModel.refreshChallenge() },
                    onAccept = { viewModel.acceptLink() },
                    onDecline = { viewModel.declineLink() },
                    onRetry = { viewModel.refreshChallenge() }
                )
                LinkedDevicesViewModel.Tab.SCAN -> ScanTab(
                    state = state,
                    cameraEnabled = scanCameraEnabled,
                    onCameraEnabledChange = { scanCameraEnabled = it },
                    onDetected = { viewModel.onQrScanned(it) },
                    onCancelWaiting = { viewModel.cancelWaiting(); scanCameraEnabled = true },
                    onClearError = { viewModel.clearScanError() }
                )
            }
        }

        // --- Redirect overlay: session adopted, swapping into the account ---
        if (state.redirecting) {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.97f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(72.dp).clip(CircleShape).background(TelefamColors.PrimaryRed.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.Devices, contentDescription = null,
                            tint = TelefamColors.PrimaryRed, modifier = Modifier.size(36.dp))
                    }
                    Spacer(Modifier.height(24.dp))
                    Text("Device linked", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground)
                    Spacer(Modifier.height(8.dp))
                    Text("Signing you in on this device…", fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                    Spacer(Modifier.height(28.dp))
                    LinearProgressIndicator(
                        progress = { state.redirectProgress },
                        modifier = Modifier.width(220.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = TelefamColors.PrimaryRed,
                        trackColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f),
                        strokeCap = StrokeCap.Round
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("${(state.redirectProgress * 100).toInt()}%", fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold, color = TelefamColors.PrimaryRed)
                }
            }
        }
    }

    // --- Remove confirmation ---
    deviceToRemove?.let { id ->
        ConfirmDialog(
            title = "Remove this device?",
            message = "It will be signed out immediately and will need to link again to reconnect.",
            confirmLabel = "Remove",
            onConfirm = { viewModel.removeSession(id); deviceToRemove = null },
            onDismiss = { deviceToRemove = null }
        )
    }
}

// ---------------------------------------------------------------- devices tab

@Composable
private fun DevicesTab(
    state: LinkedDevicesViewModel.State,
    onRefresh: () -> Unit,
    onRemove: (String) -> Unit,
    onDismissError: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(18.dp))
        Text(
            "Devices currently signed in to your account. Removing a device signs it out instantly.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Spacer(Modifier.height(14.dp))

        state.sessionsError?.let { err ->
            InlineErrorCard(message = err, onRetry = { onDismissError(); onRefresh() })
            Spacer(Modifier.height(12.dp))
        }

        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            Column {
                if (state.sessionsLoading) {
                    Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = TelefamColors.PrimaryRed, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    }
                } else if (state.sessions.isEmpty() && state.sessionsError == null) {
                    Column(
                        Modifier.fillMaxWidth().padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Outlined.Devices, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                            modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("No devices found", fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                    }
                } else {
                    state.sessions.forEach { session ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(44.dp).clip(CircleShape)
                                    .background(TelefamColors.PrimaryRed.copy(alpha = 0.10f)),
                                contentAlignment = Alignment.Center
                            ) {
                                val label = session.deviceInfo?.lowercase() ?: ""
                                val icon = when {
                                    "tablet" in label || "ipad" in label -> Icons.Outlined.Tablet
                                    "iphone" in label || "android" in label -> Icons.Outlined.PhoneAndroid
                                    else -> Icons.Outlined.Smartphone
                                }
                                Icon(icon, contentDescription = null,
                                    tint = TelefamColors.PrimaryRed, modifier = Modifier.size(22.dp))
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        session.deviceInfo ?: "Unknown device",
                                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface, maxLines = 1
                                    )
                                    if (session.current) {
                                        Spacer(Modifier.width(8.dp))
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = TelefamColors.PrimaryRed.copy(alpha = 0.10f)
                                        ) {
                                            Text("This device", fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = TelefamColors.PrimaryRed,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                        }
                                    }
                                }
                                Text(
                                    "Last active ${session.lastActiveAt.take(10)}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                )
                            }
                            if (!session.current) {
                                IconButton(
                                    onClick = { onRemove(session.sessionId) },
                                    enabled = state.removingSessionId == null,
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    if (state.removingSessionId == session.sessionId) {
                                        CircularProgressIndicator(
                                            color = TelefamColors.PrimaryRed,
                                            modifier = Modifier.size(20.dp), strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "Remove device",
                                            tint = TelefamColors.PrimaryRed)
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f))
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "To add a device, open \"Link a device\" on this phone and scan the code with the device you want to connect.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Spacer(Modifier.height(32.dp))
    }
}

// ---------------------------------------------------------------- QR tab (owner)

@Composable
private fun QrTab(
    state: LinkedDevicesViewModel.State,
    onRefreshQr: () -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onRetry: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(22.dp))

        when {
            state.qrError != null -> {
                InlineErrorCard(message = state.qrError, onRetry = onRetry)
            }

            state.justLinked -> {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null,
                        tint = Color(0xFF2E9E5B), modifier = Modifier.size(72.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("Device linked", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground)
                    Spacer(Modifier.height(6.dp))
                    Text("The new device is now signed in to your account.", fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center)
                }
            }

            state.challenge == null || state.qrRefreshing -> {
                Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TelefamColors.PrimaryRed)
                }
            }

            else -> {
                // --- QR card with live countdown ring ---
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp
                ) {
                    Box(Modifier.padding(22.dp)) {
                        val matrix = remember(state.challenge.qrPayload) {
                            runCatching { QrCode.encode(state.challenge.qrPayload) }.getOrNull()
                        }
                        if (matrix != null) {
                            QrCodeImage(matrix, Modifier.fillMaxWidth())
                        } else {
                            Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                                Text("Couldn't render this code", fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            }
                        }
                        // Expired veil (brief window between countdown 0 and refresh completing).
                        if (state.qrSecondsRemaining <= 0 && !state.awaitingAccept) {
                            Box(
                                Modifier.matchParentSize()
                                    .background(Color.White.copy(alpha = 0.85f)),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = TelefamColors.PrimaryRed)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                // --- Countdown: ring + seconds; the code is invalidated server-side at 0 ---
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { (state.qrSecondsRemaining.coerceIn(0, 30) / 30f) },
                            modifier = Modifier.size(44.dp),
                            color = TelefamColors.PrimaryRed,
                            trackColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f),
                            strokeWidth = 4.dp,
                            strokeCap = StrokeCap.Round
                        )
                        Text("${state.qrSecondsRemaining.coerceAtLeast(0)}",
                            fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Code refreshes automatically", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground)
                        Text("Each refresh invalidates the previous code", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f))
                    }
                }

                Spacer(Modifier.height(14.dp))

                OutlinedButton(
                    onClick = onRefreshQr,
                    enabled = !state.qrRefreshing,
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null,
                        tint = TelefamColors.PrimaryRed, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Refresh code now", color = TelefamColors.PrimaryRed, fontWeight = FontWeight.SemiBold)
                }

                Spacer(Modifier.height(22.dp))

                // --- Accept prompt: another device scanned this code ---
                if (state.awaitingAccept) {
                    Surface(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = TelefamColors.PrimaryRed.copy(alpha = 0.06f)
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(44.dp).clip(CircleShape)
                                        .background(TelefamColors.PrimaryRed.copy(alpha = 0.12f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Outlined.PhoneAndroid, contentDescription = null,
                                        tint = TelefamColors.PrimaryRed, modifier = Modifier.size(22.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Link this device?", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface)
                                    Text(
                                        state.scannerLabel ?: "A new device",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            val mins = state.acceptSecondsRemaining / 60
                            val secs = state.acceptSecondsRemaining % 60
                            Text(
                                "This request expires in %d:%02d".format(mins, secs),
                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                color = TelefamColors.PrimaryRed
                            )
                            Spacer(Modifier.height(14.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(
                                    onClick = onDecline,
                                    enabled = !state.acceptBusy,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                ) {
                                    Text("Decline", color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.SemiBold)
                                }
                                Button(
                                    onClick = onAccept,
                                    enabled = !state.acceptBusy,
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                ) {
                                    if (state.acceptBusy) {
                                        CircularProgressIndicator(color = TelefamColors.White,
                                            modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    } else {
                                        Text("Accept", fontWeight = FontWeight.Bold, color = TelefamColors.White)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                }

                Text(
                    "Open Telefam on the device you want to link, go to Linked devices → Scan QR, and point it at this code.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

// ---------------------------------------------------------------- scan tab (new device)

@Composable
private fun ScanTab(
    state: LinkedDevicesViewModel.State,
    cameraEnabled: Boolean,
    onCameraEnabledChange: (Boolean) -> Unit,
    onDetected: (String) -> Unit,
    onCancelWaiting: () -> Unit,
    onClearError: () -> Unit
) {
    var manualCode by remember { mutableStateOf("") }
    var cameraError by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(22.dp))

        if (state.waitingForApproval) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = TelefamColors.PrimaryRed, modifier = Modifier.size(56.dp), strokeWidth = 5.dp)
                Spacer(Modifier.height(22.dp))
                Text("Waiting for approval", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tap Accept on the other device to finish linking. The request expires automatically after 5 minutes.",
                    fontSize = 14.sp, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(24.dp))
                OutlinedButton(
                    onClick = onCancelWaiting,
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)
                ) {
                    Text("Cancel", color = TelefamColors.PrimaryRed, fontWeight = FontWeight.SemiBold)
                }
            }
        } else {
            // --- Live scanner with viewfinder frame ---
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(0.9f).clip(RoundedCornerShape(24.dp))
                ) {
                    if (cameraError != null) {
                        Column(
                            Modifier.fillMaxSize().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Outlined.QrCodeScanner, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                                modifier = Modifier.size(44.dp))
                            Spacer(Modifier.height(12.dp))
                            Text(cameraError ?: "", fontSize = 13.sp, textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    } else {
                        QrScannerCamera(
                            enabled = cameraEnabled && !state.scanBusy,
                            onDetected = { raw ->
                                onCameraEnabledChange(false)
                                onDetected(raw)
                            },
                            onError = { cameraError = it },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (state.scanBusy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = TelefamColors.PrimaryRed, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Verifying code…", fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
                }
                Spacer(Modifier.height(12.dp))
            }

            state.scanError?.let { err ->
                InlineErrorCard(message = err, onRetry = {
                    onClearError()
                    onCameraEnabledChange(true)
                })
                Spacer(Modifier.height(14.dp))
            }

            // --- Manual fallback: paste a code instead of scanning ---
            Text("Or paste a device link code", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = manualCode,
                onValueChange = { manualCode = it },
                placeholder = { Text("telefam://link-device?…", fontSize = 13.sp) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { if (manualCode.isNotBlank()) onDetected(manualCode) },
                enabled = manualCode.isNotBlank() && !state.scanBusy,
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Icon(Icons.Outlined.QrCode2, contentDescription = null,
                    tint = TelefamColors.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Use this code", fontWeight = FontWeight.Bold, color = TelefamColors.White)
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

// ---------------------------------------------------------------- shared bits

@Composable
private fun InlineErrorCard(message: String, onRetry: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = TelefamColors.PrimaryRed.copy(alpha = 0.07f)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(message, fontSize = 13.sp, modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(10.dp))
            TextButton(onClick = onRetry) {
                Text("Retry", color = TelefamColors.PrimaryRed, fontWeight = FontWeight.Bold)
            }
        }
    }
}
