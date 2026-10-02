package com.telefam.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Lucide-style icon set (lucide.dev): 24x24 grid, 2px rounded strokes, no fills.
 * Drawn as Compose vector strokes so they tint cleanly and scale to any density.
 */
private const val G = 24f

private fun stroke(color: Color, gridScale: Float) = Stroke(
    width = 2f * gridScale, cap = StrokeCap.Round, join = StrokeJoin.Round
)

@Composable
private fun LucideIcon(
    modifier: Modifier,
    size: Dp,
    tint: Color,
    draw: androidx.compose.ui.graphics.drawscope.DrawScope.(Float, Stroke) -> Unit
) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / G
        draw(s, stroke(tint, s))
    }
}

/** lucide: bell */
@Composable
fun LucideBell(modifier: Modifier = Modifier, size: Dp = 22.dp, tint: Color = Color.Unspecified) {
    val c = if (tint == Color.Unspecified) Color(0xFF444444) else tint
    LucideIcon(modifier, size, c) { s, st ->
        val p = Path().apply {
            moveTo(6f * s, 9f * s)
            cubicTo(6f * s, 5.7f * s, 8.7f * s, 3f * s, 12f * s, 3f * s)
            cubicTo(15.3f * s, 3f * s, 18f * s, 5.7f * s, 18f * s, 9f * s)
            cubicTo(18f * s, 13f * s, 19.5f * s, 15f * s, 21f * s, 16f * s)
            lineTo(3f * s, 16f * s)
            cubicTo(4.5f * s, 15f * s, 6f * s, 13f * s, 6f * s, 9f * s)
            close()
        }
        drawPath(p, c, style = st)
        drawPath(Path().apply {
            moveTo(10.3f * s, 20f * s); cubicTo(10.8f * s, 21f * s, 13.2f * s, 21f * s, 13.7f * s, 20f * s)
        }, c, style = st)
    }
}

/** lucide: heart */
@Composable
fun LucideHeart(modifier: Modifier = Modifier, size: Dp = 22.dp, tint: Color = Color(0xFF444444)) {
    LucideIcon(modifier, size, tint) { s, st ->
        val p = Path().apply {
            moveTo(12f * s, 20.5f * s)
            cubicTo(7f * s, 16.5f * s, 3f * s, 13.3f * s, 3f * s, 9.2f * s)
            cubicTo(3f * s, 6.4f * s, 5.2f * s, 4.5f * s, 7.6f * s, 4.5f * s)
            cubicTo(9.3f * s, 4.5f * s, 10.9f * s, 5.4f * s, 12f * s, 7f * s)
            cubicTo(13.1f * s, 5.4f * s, 14.7f * s, 4.5f * s, 16.4f * s, 4.5f * s)
            cubicTo(18.8f * s, 4.5f * s, 21f * s, 6.4f * s, 21f * s, 9.2f * s)
            cubicTo(21f * s, 13.3f * s, 17f * s, 16.5f * s, 12f * s, 20.5f * s)
            close()
        }
        drawPath(p, tint, style = st)
    }
}

/** lucide: message-circle */
@Composable
fun LucideMessageCircle(modifier: Modifier = Modifier, size: Dp = 22.dp, tint: Color = Color(0xFF444444)) {
    LucideIcon(modifier, size, tint) { s, st ->
        drawCircle(tint, radius = 9f * s, center = Offset(12f * s, 11f * s), style = st)
        drawPath(Path().apply {
            moveTo(7.2f * s, 18.3f * s); lineTo(3.5f * s, 20.5f * s); lineTo(4.6f * s, 16.2f * s)
        }, tint, style = st)
    }
}

/** lucide: at-sign */
@Composable
fun LucideAtSign(modifier: Modifier = Modifier, size: Dp = 22.dp, tint: Color = Color(0xFF444444)) {
    LucideIcon(modifier, size, tint) { s, st ->
        drawCircle(tint, radius = 4f * s, center = Offset(12f * s, 12f * s), style = st)
        drawPath(Path().apply {
            moveTo(16f * s, 8f * s); lineTo(16f * s, 13f * s)
            cubicTo(16f * s, 14.7f * s, 17.3f * s, 16f * s, 19f * s, 16f * s)
            cubicTo(20.7f * s, 16f * s, 22f * s, 14.7f * s, 22f * s, 13f * s)
            lineTo(22f * s, 12f * s)
            cubicTo(22f * s, 6.5f * s, 17.5f * s, 2f * s, 12f * s, 2f * s)
            cubicTo(6.5f * s, 2f * s, 2f * s, 6.5f * s, 2f * s, 12f * s)
            cubicTo(2f * s, 17.5f * s, 6.5f * s, 22f * s, 12f * s, 22f * s)
            cubicTo(14.5f * s, 22f * s, 16.8f * s, 21.1f * s, 18.6f * s, 19.6f * s)
        }, tint, style = st)
    }
}

/** lucide: user-plus (followers / follow back) */
@Composable
fun LucideUserPlus(modifier: Modifier = Modifier, size: Dp = 22.dp, tint: Color = Color(0xFF444444)) {
    LucideIcon(modifier, size, tint) { s, st ->
        drawCircle(tint, radius = 4f * s, center = Offset(10f * s, 8f * s), style = st)
        drawPath(Path().apply {
            moveTo(2f * s, 21f * s); cubicTo(2f * s, 17f * s, 5.7f * s, 14f * s, 10f * s, 14f * s)
            cubicTo(12.2f * s, 14f * s, 14.2f * s, 14.8f * s, 15.7f * s, 16.2f * s)
        }, tint, style = st)
        drawLine(tint, Offset(19f * s, 8f * s), Offset(19f * s, 14f * s), strokeWidth = st.width, cap = StrokeCap.Round)
        drawLine(tint, Offset(16f * s, 11f * s), Offset(22f * s, 11f * s), strokeWidth = st.width, cap = StrokeCap.Round)
    }
}

/** lucide: credit-card (payments / withdrawals) */
@Composable
fun LucideCreditCard(modifier: Modifier = Modifier, size: Dp = 22.dp, tint: Color = Color(0xFF444444)) {
    LucideIcon(modifier, size, tint) { s, st ->
        drawRoundRect(tint, topLeft = Offset(2f * s, 5f * s), size = Size(20f * s, 14f * s),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * s, 2f * s), style = st)
        drawLine(tint, Offset(2f * s, 10f * s), Offset(22f * s, 10f * s), strokeWidth = st.width)
    }
}

/** lucide: log-in (new login detected) */
@Composable
fun LucideLogIn(modifier: Modifier = Modifier, size: Dp = 22.dp, tint: Color = Color(0xFF444444)) {
    LucideIcon(modifier, size, tint) { s, st ->
        drawPath(Path().apply {
            moveTo(15f * s, 3f * s); lineTo(19f * s, 3f * s)
            cubicTo(20.1f * s, 3f * s, 21f * s, 3.9f * s, 21f * s, 5f * s)
            lineTo(21f * s, 19f * s)
            cubicTo(21f * s, 20.1f * s, 20.1f * s, 21f * s, 19f * s, 21f * s)
            lineTo(15f * s, 21f * s)
        }, tint, style = st)
        drawLine(tint, Offset(3f * s, 12f * s), Offset(15f * s, 12f * s), strokeWidth = st.width, cap = StrokeCap.Round)
        drawPath(Path().apply {
            moveTo(10f * s, 7f * s); lineTo(15f * s, 12f * s); lineTo(10f * s, 17f * s)
        }, tint, style = st)
    }
}

/** lucide: trash-2 (per-notification delete) */
@Composable
fun LucideTrash(modifier: Modifier = Modifier, size: Dp = 20.dp, tint: Color = Color(0xFF444444)) {
    LucideIcon(modifier, size, tint) { s, st ->
        drawLine(tint, Offset(3f * s, 6f * s), Offset(21f * s, 6f * s), strokeWidth = st.width, cap = StrokeCap.Round)
        drawPath(Path().apply {
            moveTo(8f * s, 6f * s); lineTo(8f * s, 4f * s); lineTo(16f * s, 4f * s); lineTo(16f * s, 6f * s)
        }, tint, style = st)
        drawPath(Path().apply {
            moveTo(5f * s, 6f * s); lineTo(6f * s, 20f * s); lineTo(18f * s, 20f * s); lineTo(19f * s, 6f * s)
        }, tint, style = st)
        drawLine(tint, Offset(10f * s, 10f * s), Offset(10f * s, 16f * s), strokeWidth = st.width, cap = StrokeCap.Round)
        drawLine(tint, Offset(14f * s, 10f * s), Offset(14f * s, 16f * s), strokeWidth = st.width, cap = StrokeCap.Round)
    }
}

/** lucide: badge-check (subscriptions / official) */
@Composable
fun LucideBadgeCheck(modifier: Modifier = Modifier, size: Dp = 22.dp, tint: Color = Color(0xFF444444)) {
    LucideIcon(modifier, size, tint) { s, st ->
        val p = Path().apply {
            moveTo(12f * s, 2f * s); lineTo(14.4f * s, 4.1f * s); lineTo(17.5f * s, 4.3f * s)
            lineTo(18.3f * s, 7.3f * s); lineTo(20.9f * s, 9.1f * s); lineTo(20f * s, 12f * s)
            lineTo(20.9f * s, 14.9f * s); lineTo(18.3f * s, 16.7f * s); lineTo(17.5f * s, 19.7f * s)
            lineTo(14.4f * s, 19.9f * s); lineTo(12f * s, 22f * s); lineTo(9.6f * s, 19.9f * s)
            lineTo(6.5f * s, 19.7f * s); lineTo(5.7f * s, 16.7f * s); lineTo(3.1f * s, 14.9f * s)
            lineTo(4f * s, 12f * s); lineTo(3.1f * s, 9.1f * s); lineTo(5.7f * s, 7.3f * s)
            lineTo(6.5f * s, 4.3f * s); lineTo(9.6f * s, 4.1f * s); close()
        }
        drawPath(p, tint, style = st)
        drawPath(Path().apply {
            moveTo(8.5f * s, 12f * s); lineTo(11f * s, 14.5f * s); lineTo(15.5f * s, 9.5f * s)
        }, tint, style = st)
    }
}

/** lucide: shield-alert (security / removed posts) */
@Composable
fun LucideShieldAlert(modifier: Modifier = Modifier, size: Dp = 22.dp, tint: Color = Color(0xFF444444)) {
    LucideIcon(modifier, size, tint) { s, st ->
        drawPath(Path().apply {
            moveTo(12f * s, 2f * s); lineTo(20f * s, 5.5f * s); lineTo(20f * s, 11f * s)
            cubicTo(20f * s, 16.5f * s, 16.5f * s, 20.5f * s, 12f * s, 22f * s)
            cubicTo(7.5f * s, 20.5f * s, 4f * s, 16.5f * s, 4f * s, 11f * s)
            lineTo(4f * s, 5.5f * s); close()
        }, tint, style = st)
        drawLine(tint, Offset(12f * s, 8f * s), Offset(12f * s, 13f * s), strokeWidth = st.width, cap = StrokeCap.Round)
        drawCircle(tint, radius = st.width * 0.55f, center = Offset(12f * s, 16.5f * s))
    }
}

/** lucide: send (paper plane — the Telefam mark) */
@Composable
fun LucideSend(modifier: Modifier = Modifier, size: Dp = 22.dp, tint: Color = Color(0xFF444444)) {
    LucideIcon(modifier, size, tint) { s, st ->
        drawPath(Path().apply {
            moveTo(22f * s, 2f * s); lineTo(11f * s, 13f * s)
        }, tint, style = st)
        drawPath(Path().apply {
            moveTo(22f * s, 2f * s); lineTo(15f * s, 22f * s); lineTo(11f * s, 13f * s); lineTo(2f * s, 9f * s); close()
        }, tint, style = st)
    }
}
