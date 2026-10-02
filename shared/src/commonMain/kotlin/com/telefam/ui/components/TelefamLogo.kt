package com.telefam.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.telefam.ui.theme.TelefamColors

/**
 * Telefam brand mark: a paper-plane in the spirit of messenger apps, rendered
 * in the brand red. Drawn as vector paths so it stays crisp at any size and
 * needs no image assets. Used in top bars, the splash/sign-up header and as
 * the in-app representation of the launcher icon.
 */
@Composable
fun TelefamLogo(
    size: Dp = 40.dp,
    circleColor: Color = TelefamColors.PrimaryRed,
    planeColor: Color = Color.White,
    withCircle: Boolean = true,
    modifier: Modifier = Modifier
) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        if (withCircle) {
            drawCircle(color = circleColor, radius = w / 2f, center = Offset(w / 2f, h / 2f))
        }
        // Paper-plane, normalized coordinates (Telegram-like silhouette, pointing up-right).
        fun px(x: Float) = x * w
        fun py(y: Float) = y * h
        val plane = Path().apply {
            moveTo(px(0.18f), py(0.52f))
            lineTo(px(0.84f), py(0.22f))
            lineTo(px(0.64f), py(0.82f))
            lineTo(px(0.50f), py(0.62f))
            close()
        }
        drawPath(plane, color = if (withCircle) planeColor else circleColor)
        // Fold line of the plane.
        val fold = Path().apply {
            moveTo(px(0.50f), py(0.62f))
            lineTo(px(0.84f), py(0.22f))
            lineTo(px(0.38f), py(0.58f))
            close()
        }
        drawPath(fold, color = (if (withCircle) planeColor else circleColor).copy(alpha = 0.75f))
    }
}
