package com.telefam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TextOptionRow(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SelectionCircle(selected)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

/** Small rounded preview showing 2 bars in the option's colors, mimicking a mini chat preview like the reference. */
@Composable
private fun ThemeSwatchPreview(colors: List<Long>) {
    Box(
        Modifier.size(width = 64.dp, height = 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(colors.first())),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(8.dp)) {
            Box(Modifier.width(30.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(colors.last())))
            Spacer(Modifier.height(4.dp))
            Box(Modifier.width(20.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(colors.last())))
        }
    }
}

@Composable
fun ThemeOptionRow(title: String, previewColors: List<Long>, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SelectionCircle(selected)
        Spacer(Modifier.width(16.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        ThemeSwatchPreview(previewColors)
    }
}

/** Bubble-colour preview: two little rounded "message bubble" bars in the swatch color. */
@Composable
private fun BubbleSwatchPreview(color: Long) {
    Box(
        Modifier.size(width = 64.dp, height = 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.Start, modifier = Modifier.padding(8.dp)) {
            Box(Modifier.width(32.dp).height(7.dp).clip(RoundedCornerShape(4.dp)).background(Color(color)))
            Spacer(Modifier.height(5.dp))
            Box(Modifier.width(22.dp).height(7.dp).clip(RoundedCornerShape(4.dp)).background(Color(color)))
        }
    }
}

@Composable
fun BubbleColourOptionRow(title: String, colorValue: Long, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SelectionCircle(selected)
        Spacer(Modifier.width(16.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        BubbleSwatchPreview(colorValue)
    }
}
