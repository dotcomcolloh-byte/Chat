package com.telefam.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.unit.dp
import com.telefam.ui.theme.AppleBrandColors

/** Simplified Apple-logo silhouette (body + leaf), drawn as a filled path — the recognizable Apple glyph shape used on the real "Sign in with Apple" button. */
@Composable
private fun AppleGlyph(tint: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(18.dp)) {
        val w = size.width; val h = size.height
        val body = Path().apply {
            moveTo(w * 0.5f, h * 0.30f)
            cubicTo(w * 0.30f, h * 0.30f, w * 0.12f, h * 0.48f, w * 0.12f, h * 0.68f)
            cubicTo(w * 0.12f, h * 0.90f, w * 0.30f, h * 1.02f, w * 0.42f, h * 1.02f)
            cubicTo(w * 0.48f, h * 1.02f, w * 0.52f, h * 0.98f, w * 0.5f, h * 0.98f)
            cubicTo(w * 0.48f, h * 0.98f, w * 0.52f, h * 1.02f, w * 0.58f, h * 1.02f)
            cubicTo(w * 0.70f, h * 1.02f, w * 0.88f, h * 0.90f, w * 0.88f, h * 0.68f)
            cubicTo(w * 0.88f, h * 0.48f, w * 0.70f, h * 0.30f, w * 0.5f, h * 0.30f)
            close()
        }
        val leaf = Path().apply {
            moveTo(w * 0.52f, h * 0.28f)
            cubicTo(w * 0.52f, h * 0.15f, w * 0.62f, h * 0.02f, w * 0.72f, h * 0.0f)
            cubicTo(w * 0.72f, h * 0.14f, w * 0.62f, h * 0.28f, w * 0.52f, h * 0.28f)
            close()
        }
        drawPath(body, color = tint, style = Fill)
        drawPath(leaf, color = tint, style = Fill)
    }
}

@Composable
fun AppleSignInButton(text: String = "Continue with Apple", dark: Boolean = true, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg = if (dark) AppleBrandColors.ButtonBlack else AppleBrandColors.ButtonWhite
    val fg = if (dark) AppleBrandColors.ButtonWhite else AppleBrandColors.ButtonBlack
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = bg),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            AppleGlyph(tint = fg)
            Spacer(Modifier.width(10.dp))
            Text(text, color = fg)
        }
    }
}
