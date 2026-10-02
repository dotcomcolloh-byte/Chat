package com.telefam.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.telefam.ui.theme.GoogleBrandColors

/**
 * "G" mark drawn with real Google brand colors (Blue/Green/Yellow/Red) as four
 * arc segments — matches the proportions of Google's official multi-color G,
 * since we can't bundle Google's licensed SVG asset directly.
 */
@Composable
private fun GoogleGMark(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier = modifier.size(20.dp)) {
        val strokeWidth = size.minDimension * 0.22f
        val radius = (size.minDimension - strokeWidth) / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val arcSize = androidx.compose.ui.geometry.Size(radius * 2, radius * 2)
        val topLeft = Offset(center.x - radius, center.y - radius)
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Butt)

        drawArc(GoogleBrandColors.Red, startAngle = -90f, sweepAngle = 80f, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
        drawArc(GoogleBrandColors.Yellow, startAngle = -10f, sweepAngle = 100f, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
        drawArc(GoogleBrandColors.Green, startAngle = 90f, sweepAngle = 90f, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
        drawArc(GoogleBrandColors.Blue, startAngle = 180f, sweepAngle = 170f, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
        // crossbar of the G
        drawLine(
            GoogleBrandColors.Blue,
            start = Offset(center.x, center.y),
            end = Offset(center.x + radius + strokeWidth / 2, center.y),
            strokeWidth = strokeWidth
        )
    }
}

@Composable
fun GoogleSignInButton(text: String = "Continue with Google", onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = GoogleBrandColors.ButtonBackground),
        border = BorderStroke(1.dp, GoogleBrandColors.ButtonBorder),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            GoogleGMark()
            Spacer(Modifier.width(12.dp))
            Text(text, color = GoogleBrandColors.ButtonText)
        }
    }
}
