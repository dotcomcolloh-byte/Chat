package com.telefam.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.ui.components.SettingsTopBar

data class LegalDoc(val key: String, val title: String, val subtitle: String)

val LEGAL_DOCS = listOf(
    LegalDoc("terms", "Terms of Service", "The rules for using Telefam."),
    LegalDoc("privacy", "Privacy Policy", "What we collect and how we use it."),
    LegalDoc("guidelines", "Community Guidelines", "What's allowed on Telefam."),
    LegalDoc("data", "Data Policy", "Your data rights and retention details."),
    LegalDoc("licenses", "Open-source Licenses", "Third-party software we build on."),
)

@Composable
fun TermsPoliciesScreen(onBack: () -> Unit, onOpenDoc: (LegalDoc) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        SettingsTopBar("Terms and policies", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Spacer(Modifier.height(18.dp))
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                Column {
                    LEGAL_DOCS.forEachIndexed { i, doc ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenDoc(doc) }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.Outlined.Description, null,
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            Column(Modifier.weight(1f)) {
                                Text(doc.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(doc.subtitle, fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                            }
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LegalDocScreen(doc: LegalDoc, content: String?, loading: Boolean, error: String?, onBack: () -> Unit, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        SettingsTopBar(doc.title, onBack)
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Couldn't load this document", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(6.dp))
                Text(error, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onRetry) { Text("Retry") }
            }
            else -> Text(
                content.orEmpty(),
                fontSize = 14.sp,
                lineHeight = 22.sp,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            )
        }
    }
}
