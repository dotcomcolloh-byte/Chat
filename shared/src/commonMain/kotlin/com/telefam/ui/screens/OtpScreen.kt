package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.shared.generated.resources.*
import com.telefam.ui.components.TelefamPrimaryButton
import com.telefam.ui.theme.TelefamColors
import org.jetbrains.compose.resources.stringResource

/**
 * Client only ever renders `secondsUntilResend` as a plain countdown — the server
 * (OtpService) is what actually enforces the 60s x 5 then 5hr rule; the UI has no
 * idea whether this is attempt #1 or #5, it just gets a number of seconds each time.
 */
@Composable
fun OtpScreen(
    email: String,
    secondsUntilResend: Long,
    onVerifyClick: (code: String) -> Unit,
    onResendClick: () -> Unit,
    onBackClick: () -> Unit,
    isLoading: Boolean = false,
    errorMessage: String? = null
) {
    var code by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().background(TelefamColors.White).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(64.dp))
        Text(stringResource(Res.string.verify_email_title), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TelefamColors.PrimaryRed)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(Res.string.verify_email_subtitle, email), color = TelefamColors.TextMuted, textAlign = TextAlign.Center)

        Spacer(Modifier.height(32.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) code = it },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 28.sp, textAlign = TextAlign.Center, letterSpacing = 8.sp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TelefamColors.PrimaryRed, unfocusedBorderColor = TelefamColors.FieldBorder),
            modifier = Modifier.fillMaxWidth()
        )

        if (errorMessage != null) {
            Spacer(Modifier.height(8.dp))
            Text(errorMessage, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(24.dp))
        TelefamPrimaryButton(
            text = stringResource(Res.string.verify_action),
            onClick = { onVerifyClick(code) },
            enabled = code.length == 6,
            loading = isLoading,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(20.dp))
        if (secondsUntilResend > 0) {
            Text(stringResource(Res.string.resend_code_in, "${secondsUntilResend}s"), color = TelefamColors.TextMuted)
        } else {
            Text(stringResource(Res.string.resend_code), color = TelefamColors.PrimaryRed, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(onClick = onResendClick))
        }

        Spacer(Modifier.weight(1f))
        Text(stringResource(Res.string.back), color = TelefamColors.TextMuted, modifier = Modifier.clickable(onClick = onBackClick))
        Spacer(Modifier.height(16.dp))
    }
}
