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

@Composable
fun ChangePhoneScreen(
    currentCountryCode: String?,
    currentNumber: String?,
    saving: Boolean,
    error: String?,
    onBack: () -> Unit,
    onSave: (countryCode: String, number: String) -> Unit,
) {
    var countryCode by remember { mutableStateOf(currentCountryCode ?: "+") }
    var number by remember { mutableStateOf(currentNumber ?: "") }
    val numberValid = number.filter(Char::isDigit).length in 6..15
    val codeValid = Regex("^\\+\\d{1,4}$").matches(countryCode.trim())

    Column(Modifier.fillMaxSize()) {
        SettingsTopBar("Change phone number", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = countryCode,
                    onValueChange = { countryCode = it.take(5) },
                    label = { Text("Code") },
                    singleLine = true,
                    isError = countryCode.isNotBlank() && !codeValid,
                    modifier = Modifier.width(110.dp),
                    shape = RoundedCornerShape(12.dp),
                )
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it.filter(Char::isDigit).take(15) },
                    label = { Text("Phone number") },
                    singleLine = true,
                    isError = number.isNotBlank() && !numberValid,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Your number is used for account recovery and is only visible according to your privacy settings.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { onSave(countryCode.trim(), number.filter(Char::isDigit)) },
                enabled = !saving && codeValid && numberValid,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary)
                else Text("Save")
            }
        }
    }
}
