package com.telefam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.chat.ContactData
import com.telefam.shared.generated.resources.Res
import com.telefam.shared.generated.resources.contact_action_call
import com.telefam.shared.generated.resources.contact_action_open
import com.telefam.shared.generated.resources.contact_action_save
import com.telefam.ui.theme.TelefamColors
import org.jetbrains.compose.resources.stringResource

@Composable
fun ContactCardBubble(
    contact: ContactData,
    onSave: () -> Unit,
    onOpen: () -> Unit,
    onCall: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.width(240.dp).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(TelefamColors.PrimaryRed.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Person, contentDescription = null, tint = TelefamColors.PrimaryRed)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(contact.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(contact.phoneNumber, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
        Divider()
        Row(Modifier.fillMaxWidth()) {
            CardActionButton(Icons.Filled.Save, stringResource(Res.string.contact_action_save), Modifier.weight(1f), onSave)
            VerticalDivider()
            CardActionButton(Icons.Filled.OpenInNew, stringResource(Res.string.contact_action_open), Modifier.weight(1f), onOpen)
            VerticalDivider()
            CardActionButton(Icons.Filled.Call, stringResource(Res.string.contact_action_call), Modifier.weight(1f), onCall)
        }
    }
}

@Composable
private fun VerticalDivider() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)))
}

@Composable
private fun CardActionButton(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.padding(vertical = 10.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = TelefamColors.PrimaryRed, modifier = Modifier.size(18.dp))
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
    }
}
