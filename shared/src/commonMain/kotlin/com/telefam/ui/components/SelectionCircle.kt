package com.telefam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.telefam.ui.theme.TelefamColors

@Composable
fun SelectionCircle(selected: Boolean) {
    if (selected) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(TelefamColors.PrimaryRed),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = TelefamColors.White, modifier = Modifier.size(16.dp))
        }
    } else {
        Box(
            Modifier.size(24.dp).clip(CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
        )
    }
}
