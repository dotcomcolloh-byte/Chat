package com.telefam.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
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

private data class Faq(val question: String, val answer: String, val keywords: String)

private val FAQS = listOf(
    Faq("How do I change my username?", "Go to Settings → Account → Edit profile. Usernames can be changed once every 7 days.", "username change name handle"),
    Faq("How do I make my account private?", "Go to Settings → Privacy → Private account and turn it on. Only approved followers will see your posts.", "private account followers visibility"),
    Faq("How do I reset my password?", "On the login screen tap \"Forgot password\" and follow the email verification steps.", "password reset forgot login"),
    Faq("How do I enable two-step verification?", "Go to Settings → Security & permissions → Two-step verification. You'll confirm with a code sent to your email at your next login.", "two step 2fa verification security"),
    Faq("How do I block someone?", "Open their profile, tap the menu and choose Block. Manage blocked accounts in Settings → Blocked accounts.", "block blocked user"),
    Faq("Why can't I change my username again?", "Usernames can only be changed once every 7 days. The remaining time is shown on the Edit profile screen.", "username cooldown seven days"),
    Faq("How do I deactivate or delete my account?", "Go to Settings → Account and scroll to the Danger zone. Deactivation is reversible; deletion is permanent.", "deactivate delete account remove"),
    Faq("How do I reduce data usage?", "Go to Settings → Content preferences and turn on Data saver, or set Video quality to Low.", "data saver quality usage"),
)

@Composable
fun HelpSupportScreen(
    myReports: List<ProblemReportDto>,
    onBack: () -> Unit,
    onContactSupport: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) {
        if (query.isBlank()) FAQS
        else FAQS.filter { (it.question + " " + it.keywords).contains(query.trim(), ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize()) {
        SettingsTopBar("Help & support", onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    placeholder = { Text("Search help topics") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true, shape = RoundedCornerShape(24.dp),
                )
            }
            item {
                Text("Frequently asked questions", fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            items(filtered) { faq -> FaqItem(faq) }
            if (filtered.isEmpty()) {
                item {
                    Text("No results for \"$query\". Try different words or contact support below.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                }
            }
            item {
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onContactSupport,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
                ) { Text("Contact support") }
            }
            if (myReports.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(20.dp))
                    Text("My support requests", fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 20.dp))
                    Spacer(Modifier.height(6.dp))
                }
                items(myReports) { report ->
                    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(report.category.lowercase().replaceFirstChar { it.uppercase() },
                                    fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(report.description, fontSize = 12.sp, maxLines = 1,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                            }
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    report.status, fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FaqItem(faq: Faq) {
    var expanded by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(Modifier.clickable { expanded = !expanded }.padding(14.dp)) {
            Text(faq.question, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                Text(faq.answer, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
            }
        }
    }
}
