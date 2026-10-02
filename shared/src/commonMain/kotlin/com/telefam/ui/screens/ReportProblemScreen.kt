@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.telefam.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.data.api.ProblemReportDto
import com.telefam.ui.components.SettingsTopBar
import com.telefam.ui.theme.TelefamColors

val PROBLEM_CATEGORIES = listOf(
    "ACCOUNT" to "Account", "LOGIN" to "Login", "PROFILE" to "Profile", "POSTS" to "Posts",
    "FEED" to "Feed", "MESSAGES" to "Messages", "CONTACTS" to "Contacts",
    "SETTINGS" to "Settings", "OTHER" to "Other",
)

@Composable
fun ReportProblemScreen(
    submitting: Boolean,
    submitted: ProblemReportDto?,
    error: String?,
    onBack: () -> Unit,
    onSubmit: (category: String, description: String) -> Unit,
) {
    var category by remember { mutableStateOf<String?>(null) }
    var description by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        SettingsTopBar("Report a problem", onBack)
        if (submitted != null) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Outlined.CheckCircle, null, tint = TelefamColors.PrimaryRed, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(16.dp))
                Text("Report submitted", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Thanks for letting us know. Reference ID: ${submitted.reportId.take(8)}.\nWe review every report and will follow up if needed.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed)) {
                    Text("Done")
                }
            }
            return
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text("What's the problem about?", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PROBLEM_CATEGORIES.forEach { (value, label) ->
                    FilterChip(selected = category == value, onClick = { category = value },
                        label = { Text(label, fontSize = 12.sp) })
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("Describe the issue", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { if (it.length <= 2000) description = it },
                modifier = Modifier.fillMaxWidth().height(160.dp),
                placeholder = { Text("Tell us what happened, what you expected, and any steps to reproduce it.") },
                shape = RoundedCornerShape(12.dp),
            )
            Text("${description.length}/2000", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.align(Alignment.End))
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { category?.let { onSubmit(it, description.trim()) } },
                enabled = !submitting && category != null && description.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
            ) {
                if (submitting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary)
                else Text("Submit report")
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Your report includes your account ID, category, description, app version and a timestamp. Attach screenshots from the chat with support if requested.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
}
