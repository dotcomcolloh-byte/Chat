package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.ui.theme.TelefamColors

/**
 * Settings → Account → Change password. Verifies the current password server-side;
 * on success every existing session is revoked and the host signs the user in again.
 */
@Composable
fun ChangePasswordScreen(
    saving: Boolean,
    error: String?,
    onBack: () -> Unit,
    onSubmit: (current: String, new: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    val valid = new.length >= 8 && new.any { it.isDigit() } && new.any { it.isLetter() } && new == confirm && current.isNotBlank()

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            Text("Change password", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground)
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            PasswordField("Current password", current) { current = it }
            Spacer(Modifier.height(14.dp))
            PasswordField("New password", new) { new = it }
            Spacer(Modifier.height(14.dp))
            PasswordField("Confirm new password", confirm) { confirm = it }
            Spacer(Modifier.height(6.dp))
            Text("At least 8 characters with a letter and a number.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f))
            if (confirm.isNotEmpty() && new != confirm) {
                Text("Passwords do not match", fontSize = 12.sp, color = TelefamColors.PrimaryRed)
            }
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error, color = TelefamColors.PrimaryRed, fontSize = 13.sp)
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { onSubmit(current, new) },
                enabled = valid && !saving,
                colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            ) {
                if (saving) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                else Text("Update password", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PasswordField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        singleLine = true, visualTransformation = PasswordVisualTransformation(),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = TelefamColors.PrimaryRed,
            focusedLabelColor = TelefamColors.PrimaryRed
        ),
        modifier = Modifier.fillMaxWidth()
    )
}
