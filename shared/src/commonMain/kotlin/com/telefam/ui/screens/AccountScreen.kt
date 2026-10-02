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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.telefam.data.api.AccountDetailsDto
import com.telefam.data.api.ApiConfig
import com.telefam.ui.components.ConfirmDialog
import com.telefam.ui.theme.TelefamColors

/**
 * Settings → Account. Shows the real account record (photo, username, name, bio,
 * email, phone, password, DOB, status) and routes every action: edit profile,
 * verified email change, phone update, password change, recovery, deactivate, delete.
 */
@Composable
fun AccountScreen(
    account: AccountDetailsDto?,
    loading: Boolean,
    onBack: () -> Unit,
    onEditProfile: () -> Unit,
    onChangeEmail: () -> Unit,
    onChangePhone: () -> Unit,
    onChangePassword: () -> Unit,
    onAccountRecovery: () -> Unit,
    onDeactivate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDeactivateConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // --- Top bar ---
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            Text("Account", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground)
        }

        when {
            loading && account == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TelefamColors.PrimaryRed)
            }
            account != null -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
            ) {
                Spacer(Modifier.height(8.dp))
                // --- Identity header ---
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(64.dp).clip(CircleShape)
                            .background(TelefamColors.PrimaryRed.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (account.avatarUrl != null) {
                            AsyncImage(
                                model = ApiConfig.baseUrl.trimEnd('/') + account.avatarUrl,
                                contentDescription = null, contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text((account.fullName ?: account.username ?: "T").take(1).uppercase(),
                                fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TelefamColors.PrimaryRed)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(account.fullName ?: "", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground)
                        Text("@${account.username ?: ""}", fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f))
                        Text(
                            when (account.accountStatus) {
                                "ACTIVE" -> "Account active"
                                "DEACTIVATED" -> "Deactivated"
                                else -> account.accountStatus
                            },
                            fontSize = 12.sp,
                            color = if (account.accountStatus == "ACTIVE") Color(0xFF2E7D32) else TelefamColors.PrimaryRed
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                AccountRow(Icons.Outlined.Edit, "Edit profile", "Photo, name, username, bio", onEditProfile)
                HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.07f))
                AccountRow(Icons.Outlined.AlternateEmail, "Email",
                    if (account.emailVerified) account.email else "${account.email} (unverified)", onChangeEmail)
                HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.07f))
                AccountRow(Icons.Outlined.Phone, "Phone number",
                    listOfNotNull(account.phoneCountryCode, account.phoneNumber).joinToString(" ").ifBlank { "Not set" },
                    onChangePhone)
                HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.07f))
                AccountRow(Icons.Outlined.Key, "Password", "Change your password", onChangePassword)
                HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.07f))
                AccountRow(Icons.Outlined.Cake, "Date of birth", account.dateOfBirth ?: "Not set") {}
                HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.07f))
                AccountRow(Icons.Outlined.LockReset, "Account recovery", "Email a sign-in link to your address", onAccountRecovery)

                Spacer(Modifier.height(24.dp))
                Text("Danger zone", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = TelefamColors.PrimaryRed, modifier = Modifier.padding(bottom = 6.dp))
                TextButton(onClick = { showDeactivateConfirm = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Deactivate account", color = TelefamColors.PrimaryRed,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                }
                TextButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete account permanently", color = TelefamColors.PrimaryRed,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (showDeactivateConfirm) {
        ConfirmDialog(
            title = "Deactivate account?",
            message = "Your profile, posts and messages will be hidden until you sign back in. You can reactivate any time by logging in.",
            confirmLabel = "Deactivate",
            onConfirm = { showDeactivateConfirm = false; onDeactivate() },
            onDismiss = { showDeactivateConfirm = false }
        )
    }
    if (showDeleteConfirm) {
        ConfirmDialog(
            title = "Delete account permanently?",
            message = "This permanently deletes your account, posts, messages and settings. This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = { showDeleteConfirm = false; onDelete() },
            onDismiss = { showDeleteConfirm = false }
        )
    }
}

@Composable
private fun AccountRow(icon: ImageVector, title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground)
            Text(value, fontSize = 13.sp, maxLines = 1,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f))
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f))
    }
}
