package com.telefam.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.ui.components.SettingsTopBar
import com.telefam.ui.theme.TelefamColors

/**
 * Two-step email change: request a code for the new address, then confirm it.
 * The backend only swaps the email after the code verifies, and revokes all sessions.
 */
@Composable
fun ChangeEmailScreen(
    currentEmail: String,
    saving: Boolean,
    error: String?,
    info: String?,
    onBack: () -> Unit,
    onRequestCode: (newEmail: String) -> Unit,
    onConfirm: (newEmail: String, code: String) -> Unit,
) {
    var step by remember { mutableStateOf(1) }
    var newEmail by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val emailValid = remember(newEmail) {
        Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$").matches(newEmail.trim())
    }

    Column(Modifier.fillMaxSize()) {
        SettingsTopBar("Change email", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            if (step == 1) {
                Text("Current email", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth())
                Text(currentEmail, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = newEmail,
                    onValueChange = { newEmail = it },
                    label = { Text("New email address") },
                    singleLine = true,
                    isError = newEmail.isNotBlank() && !emailValid,
                    supportingText = {
                        if (newEmail.isNotBlank() && !emailValid) Text("Enter a valid email address")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "We'll send a 6-digit verification code to the new address. Your email only changes after you confirm it, and you'll be signed in again everywhere.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
                if (info != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(info, color = TelefamColors.PrimaryRed, fontSize = 13.sp)
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { onRequestCode(newEmail.trim()); step = 2 },
                    enabled = !saving && emailValid && newEmail.trim() != currentEmail,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
                ) {
                    if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary)
                    else Text("Send verification code")
                }
            } else {
                Text("Enter the 6-digit code we sent to", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                Text(newEmail.trim(), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    label = { Text("Verification code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { onConfirm(newEmail.trim(), code) },
                    enabled = !saving && code.length == 6,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
                ) {
                    if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary)
                    else Text("Confirm new email")
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { step = 1; code = "" }, enabled = !saving) {
                    Text("Use a different email")
                }
            }
        }
    }
}
