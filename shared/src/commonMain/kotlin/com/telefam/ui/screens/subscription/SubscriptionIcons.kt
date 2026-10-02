package com.telefam.ui.screens.subscription

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 3D-look vector icons for the subscription surfaces — pure Canvas drawing with
 * gradient bodies + highlight/shadow lips (no raster assets, no emoji), matching
 * the crowned reference art. Theme-aware: every variant also works on the app's
 * dark surfaces because the tints are supplied by the caller.
 */

/** The gradient crown from the creator Subscriptions reference (violet → blue, sparkles). */
@Composable
fun Crown3DIcon(modifier: Modifier = Modifier, palette: List<Color> = listOf(Color(0xFFB06CFF), Color(0xFF5E8BFF))) {
    Canvas(modifier) {
        val s = size.minDimension
        // Crown body
        val p = Path().apply {
            moveTo(s * 0.18f, s * 0.72f)
            lineTo(s * 0.14f, s * 0.34f)
            lineTo(s * 0.34f, s * 0.50f)
            lineTo(s * 0.50f, s * 0.26f)
            lineTo(s * 0.66f, s * 0.50f)
            lineTo(s * 0.86f, s * 0.34f)
            lineTo(s * 0.82f, s * 0.72f)
            close()
        }
        drawPath(p, Brush.verticalGradient(palette))
        // Base band (darker lip = 3D depth)
        drawRoundRect(
            Brush.verticalGradient(listOf(palette.last(), palette.last().copy(alpha = 0.85f))),
            Offset(s * 0.18f, s * 0.72f), Size(s * 0.64f, s * 0.10f), CornerRadius(s * 0.05f)
        )
        // Tip spheres
        listOf(0.14f to 0.34f, 0.50f to 0.26f, 0.86f to 0.34f).forEach { (x, y) ->
            drawCircle(palette.first(), s * 0.055f, Offset(s * x, s * y))
            drawCircle(Color.White.copy(alpha = 0.7f), s * 0.018f, Offset(s * (x - 0.012f), s * (y - 0.012f)))
        }
        // Sparkles
        listOf(0.08f to 0.16f, 0.90f to 0.12f).forEach { (x, y) ->
            val c = Offset(s * x, s * y); val r = s * 0.05f
            drawLine(Color(0xFFD8B4FE), Offset(c.x - r, c.y), Offset(c.x + r, c.y), s * 0.02f)
            drawLine(Color(0xFFD8B4FE), Offset(c.x, c.y - r), Offset(c.x, c.y + r), s * 0.02f)
        }
    }
}

/** The gold crown on red disc from the fan "Support" banner reference. */
@Composable
fun CrownDiscIcon(modifier: Modifier = Modifier, discColor: Color, crownColors: List<Color> = listOf(Color(0xFFFFD54A), Color(0xFFF5A300))) {
    Box(modifier.clip(CircleShape).background(discColor), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val s = size.minDimension
            val p = Path().apply {
                moveTo(s * 0.28f, s * 0.62f)
                lineTo(s * 0.25f, s * 0.40f)
                lineTo(s * 0.40f, s * 0.50f)
                lineTo(s * 0.50f, s * 0.34f)
                lineTo(s * 0.60f, s * 0.50f)
                lineTo(s * 0.75f, s * 0.40f)
                lineTo(s * 0.72f, s * 0.62f)
                close()
            }
            drawPath(p, Brush.verticalGradient(crownColors))
            drawRoundRect(crownColors.last(), Offset(s * 0.28f, s * 0.62f), Size(s * 0.44f, s * 0.07f), CornerRadius(s * 0.03f))
        }
    }
}

/** White star on a coloured disc (plan cards in the fan reference). */
@Composable
fun StarDiscIcon(modifier: Modifier = Modifier, discColor: Color) {
    Box(modifier.clip(CircleShape).background(discColor), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val s = size.minDimension
            val p = Path()
            for (i in 0 until 10) {
                val a = PI * i / 5 - PI / 2
                val r = if (i % 2 == 0) s * 0.24f else s * 0.10f
                val x = (s / 2 + r * cos(a)).toFloat(); val y = (s / 2 + r * sin(a)).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close(); drawPath(p, Color.White)
        }
    }
}

/** 3D wallet glyph (orange gradient, "Earn Monthly" step). */
@Composable
fun Wallet3DIcon(modifier: Modifier = Modifier, colors: List<Color> = listOf(Color(0xFFFFB25E), Color(0xFFF2820A))) {
    Canvas(modifier) {
        val s = size.minDimension
        drawRoundRect(
            Brush.verticalGradient(colors),
            Offset(s * 0.16f, s * 0.30f), Size(s * 0.68f, s * 0.44f), CornerRadius(s * 0.10f)
        )
        drawRoundRect(
            Color.White.copy(alpha = 0.35f),
            Offset(s * 0.16f, s * 0.30f), Size(s * 0.68f, s * 0.10f), CornerRadius(s * 0.05f)
        )
        drawRoundRect(colors.last(), Offset(s * 0.56f, s * 0.44f), Size(s * 0.28f, s * 0.18f), CornerRadius(s * 0.06f))
        drawCircle(Color.White, s * 0.035f, Offset(s * 0.66f, s * 0.53f))
    }
}

/** 3D bar-chart glyph (insights row). */
@Composable
fun Chart3DIcon(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier) {
        val s = size.minDimension
        val stroke = Stroke(width = s * 0.08f)
        // axis
        drawLine(color, Offset(s * 0.18f, s * 0.18f), Offset(s * 0.18f, s * 0.80f), stroke.width)
        drawLine(color, Offset(s * 0.18f, s * 0.80f), Offset(s * 0.84f, s * 0.80f), stroke.width)
        listOf(0.30f to 0.58f, 0.50f to 0.42f, 0.70f to 0.26f).forEach { (x, top) ->
            drawRoundRect(
                Brush.verticalGradient(listOf(color.copy(alpha = 0.9f), color)),
                Offset(s * x - s * 0.055f, s * top), Size(s * 0.11f, s * 0.80f - s * top), CornerRadius(s * 0.03f)
            )
        }
    }
}

/** A soft radial glow behind floating 3D art (used on the earnings card). */
@Composable
fun GlowBackdrop(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier) {
        drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = 0.20f), Color.Transparent)),
            radius = size.minDimension / 2f
        )
    }
}
