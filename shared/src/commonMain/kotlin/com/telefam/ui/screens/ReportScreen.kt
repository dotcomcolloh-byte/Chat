package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.telefam.shared.generated.resources.*
import com.telefam.ui.components.SettingsTopBar
import com.telefam.ui.components.TelefamPrimaryButton
import com.telefam.ui.theme.TelefamColors
import org.jetbrains.compose.resources.stringResource

@Composable
fun ReportScreen(
    onBackClick: () -> Unit,
    onSubmit: (reason: String) -> Unit,
    isSubmitting: Boolean = false,
    submitted: Boolean = false
) {
    var reason by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SettingsTopBar(title = stringResource(Res.string.report_title), onBackClick = onBackClick)
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(Res.string.report_subtitle), color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f))
            Spacer(Modifier.height(16.dp))

            if (submitted) {
                Text(stringResource(Res.string.report_submitted), color = TelefamColors.PrimaryRed, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            } else {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    placeholder = { Text(stringResource(Res.string.report_reason_placeholder)) },
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = TelefamColors.FieldBorder, focusedBorderColor = TelefamColors.PrimaryRed),
                    modifier = Modifier.fillMaxWidth().height(160.dp)
                )
                Spacer(Modifier.height(20.dp))
                TelefamPrimaryButton(
                    text = stringResource(Res.string.report_submit),
                    onClick = { onSubmit(reason) },
                    enabled = reason.isNotBlank(),
                    loading = isSubmitting,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
