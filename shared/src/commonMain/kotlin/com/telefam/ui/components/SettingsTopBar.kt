package com.telefam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.ui.theme.TelefamColors

@Composable
fun SettingsTopBar(
    title: String,
    onBackClick: () -> Unit,
    onOverflowClick: (() -> Unit)? = null
) {
    Box(
        Modifier.fillMaxWidth().background(TelefamColors.PrimaryRed)
            .padding(top = 44.dp, bottom = 16.dp, start = 4.dp, end = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBackClick) {
                Icon(Icons.Filled.ArrowBack, contentDescription = null, tint = TelefamColors.White)
            }
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TelefamColors.White, modifier = Modifier.weight(1f))
            if (onOverflowClick != null) {
                IconButton(onClick = onOverflowClick) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null, tint = TelefamColors.White)
                }
            } else {
                Spacer(Modifier.width(48.dp))
            }
        }
    }
}
