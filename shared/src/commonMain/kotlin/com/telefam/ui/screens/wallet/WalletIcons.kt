package com.telefam.ui.screens.wallet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
 * Wallet icon set — 3D-style vector icons drawn in Compose (no raster assets).
 * Each icon uses a radial top-light gradient + specular highlight + soft drop
 * shade to get the dimensional "3D icon" look from the Wallet reference screen,
 * and adapts to the app theme (colors supplied by the caller's tinted container).
 */
object WalletIconPalette {
    val Purple = Color(0xFF7B61FF)
    val PurpleDark = Color(0xFF5A41D6)
    val Red = Color(0xFFD32323)
    val RedDark = Color(0xFFB31217)
    val Amber = Color(0xFFFFB020)
    val AmberDark = Color(0xFFE08E00)
    val Blue = Color(0xFF2E7CF6)
    val BlueDark = Color(0xFF1D5BC4)
    val Green = Color(0xFF22B07D)
    val GreenDark = Color(0xFF17855E)
    val PayPalBlue = Color(0xFF003087)
    val PayPalLight = Color(0xFF009CDE)
    val Specular = Color.White.copy(alpha = 0.55f)
}

private fun Brush.Companion.topLight(base: Color, dark: Color, size: Size) =
    Brush.radialGradient(
        listOf(base.lighter(), base, dark),
        center = Offset(size.width * 0.35f, size.height * 0.25f),
        radius = size.minDimension * 0.95f
    )

private fun Color.lighter(): Color =
    Color(
        red = (red + (1f - red) * 0.35f).coerceIn(0f, 1f),
        green = (green + (1f - green) * 0.35f).coerceIn(0f, 1f),
        blue = (blue + (1f - blue) * 0.35f).coerceIn(0f, 1f),
        alpha = alpha
    )

/** 3D crown — Subscriptions. */
@Composable
fun WalletCrownIcon(modifier: Modifier = Modifier, size: Dp = 26.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val crown = Path().apply {
            moveTo(w * 0.14f, h * 0.78f)
            lineTo(w * 0.10f, h * 0.32f)
            lineTo(w * 0.32f, h * 0.52f)
            lineTo(w * 0.50f, h * 0.18f)
            lineTo(w * 0.68f, h * 0.52f)
            lineTo(w * 0.90f, h * 0.32f)
            lineTo(w * 0.86f, h * 0.78f)
            close()
        }
        drawPath(crown, Brush.topLight(WalletIconPalette.Purple, WalletIconPalette.PurpleDark, this.size))
        // Specular highlight band
        drawRoundRect(
            WalletIconPalette.Specular,
            topLeft = Offset(w * 0.16f, h * 0.60f), size = Size(w * 0.68f, h * 0.09f),
            cornerRadius = CornerRadius(h * 0.05f)
        )
        // Jewels
        listOf(0.10f to 0.32f, 0.50f to 0.18f, 0.90f to 0.32f).forEach { (fx, fy) ->
            drawCircle(Color.White.copy(alpha = 0.9f), radius = w * 0.05f, center = Offset(w * fx, h * fy))
        }
    }
}

/** 3D play button — Monetization. */
@Composable
fun WalletPlayIcon(modifier: Modifier = Modifier, size: Dp = 26.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        drawRoundRect(
            Brush.topLight(WalletIconPalette.Red, WalletIconPalette.RedDark, this.size),
            size = Size(w, h), cornerRadius = CornerRadius(w * 0.22f)
        )
        val play = Path().apply {
            moveTo(w * 0.38f, h * 0.28f)
            lineTo(w * 0.74f, h * 0.50f)
            lineTo(w * 0.38f, h * 0.72f)
            close()
        }
        drawPath(play, Color.White)
        drawRoundRect(
            WalletIconPalette.Specular,
            topLeft = Offset(w * 0.10f, h * 0.08f), size = Size(w * 0.8f, h * 0.14f),
            cornerRadius = CornerRadius(h * 0.07f)
        )
    }
}

/** 3D star — Stars. */
@Composable
fun WalletStarIcon(modifier: Modifier = Modifier, size: Dp = 26.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val cx = w / 2; val cy = h / 2
        val star = Path()
        for (i in 0 until 10) {
            val angle = PI * i / 5 - PI / 2
            val r = if (i % 2 == 0) w * 0.48f else w * 0.21f
            val x = (cx + r * cos(angle)).toFloat()
            val y = (cy + r * sin(angle)).toFloat()
            if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
        }
        star.close()
        drawPath(star, Brush.topLight(WalletIconPalette.Amber, WalletIconPalette.AmberDark, this.size))
        drawCircle(WalletIconPalette.Specular, radius = w * 0.12f, center = Offset(cx - w * 0.12f, cy - h * 0.14f))
    }
}

/** 3D bank — Other Earnings / bank payout method. */
@Composable
fun WalletBankIcon(modifier: Modifier = Modifier, size: Dp = 26.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val brush = Brush.topLight(WalletIconPalette.Blue, WalletIconPalette.BlueDark, this.size)
        // Roof
        val roof = Path().apply {
            moveTo(w * 0.50f, h * 0.12f); lineTo(w * 0.92f, h * 0.38f); lineTo(w * 0.08f, h * 0.38f); close()
        }
        drawPath(roof, brush)
        // Columns
        for (i in 0..2) {
            val x = w * (0.20f + i * 0.24f)
            drawRoundRect(brush, topLeft = Offset(x, h * 0.44f), size = Size(w * 0.12f, h * 0.34f), cornerRadius = CornerRadius(w * 0.03f))
        }
        // Base
        drawRoundRect(brush, topLeft = Offset(w * 0.08f, h * 0.82f), size = Size(w * 0.84f, h * 0.10f), cornerRadius = CornerRadius(h * 0.04f))
        drawRoundRect(WalletIconPalette.Specular, topLeft = Offset(w * 0.12f, h * 0.36f), size = Size(w * 0.76f, h * 0.05f), cornerRadius = CornerRadius(h * 0.025f))
    }
}

/** 3D wallet hero illustration — red wallet with clasp and coins (balance card). */
@Composable
fun WalletHeroIcon(modifier: Modifier = Modifier, size: Dp = 88.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        // Coins behind
        drawCircle(
            Brush.topLight(WalletIconPalette.Amber, WalletIconPalette.AmberDark, this.size),
            radius = w * 0.16f, center = Offset(w * 0.72f, h * 0.18f)
        )
        drawCircle(
            Brush.topLight(WalletIconPalette.Amber, WalletIconPalette.AmberDark, this.size),
            radius = w * 0.13f, center = Offset(w * 0.20f, h * 0.72f)
        )
        drawCircle(Color(0xFFFFF3D6), radius = w * 0.10f, center = Offset(w * 0.72f, h * 0.18f))
        drawCircle(Color(0xFFFFF3D6), radius = w * 0.08f, center = Offset(w * 0.20f, h * 0.72f))
        // Wallet body
        drawRoundRect(
            Brush.topLight(WalletIconPalette.Red, WalletIconPalette.RedDark, this.size),
            topLeft = Offset(w * 0.22f, h * 0.30f), size = Size(w * 0.62f, h * 0.50f),
            cornerRadius = CornerRadius(w * 0.10f)
        )
        // Flap
        drawRoundRect(
            Brush.topLight(WalletIconPalette.RedDark, Color(0xFF8E0E12), this.size),
            topLeft = Offset(w * 0.22f, h * 0.30f), size = Size(w * 0.62f, h * 0.20f),
            cornerRadius = CornerRadius(w * 0.10f)
        )
        // Clasp
        drawRoundRect(
            Brush.topLight(WalletIconPalette.Amber, WalletIconPalette.AmberDark, this.size),
            topLeft = Offset(w * 0.70f, h * 0.46f), size = Size(w * 0.18f, h * 0.16f),
            cornerRadius = CornerRadius(w * 0.06f)
        )
        drawCircle(Color.White, radius = w * 0.03f, center = Offset(w * 0.79f, h * 0.54f))
        // Logo mark
        val mark = Path().apply {
            moveTo(w * 0.40f, h * 0.68f); lineTo(w * 0.47f, h * 0.50f); lineTo(w * 0.54f, h * 0.68f); close()
        }
        drawPath(mark, Color.White.copy(alpha = 0.92f))
        // Specular
        drawRoundRect(
            WalletIconPalette.Specular,
            topLeft = Offset(w * 0.26f, h * 0.33f), size = Size(w * 0.54f, h * 0.06f),
            cornerRadius = CornerRadius(h * 0.03f)
        )
    }
}

/** 3D M-Pesa-style phone icon. */
@Composable
fun WalletMpesaIcon(modifier: Modifier = Modifier, size: Dp = 26.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        drawRoundRect(
            Brush.topLight(WalletIconPalette.Green, WalletIconPalette.GreenDark, this.size),
            topLeft = Offset(w * 0.26f, h * 0.06f), size = Size(w * 0.48f, h * 0.88f),
            cornerRadius = CornerRadius(w * 0.12f)
        )
        drawRoundRect(
            Color.White, topLeft = Offset(w * 0.34f, h * 0.20f), size = Size(w * 0.32f, h * 0.44f),
            cornerRadius = CornerRadius(w * 0.04f)
        )
        drawCircle(Color.White, radius = w * 0.05f, center = Offset(w * 0.50f, h * 0.78f))
        drawRoundRect(WalletIconPalette.Specular, topLeft = Offset(w * 0.30f, h * 0.10f), size = Size(w * 0.40f, h * 0.05f), cornerRadius = CornerRadius(h * 0.025f))
    }
}

/** 3D PayPal "P" monogram. */
@Composable
fun WalletPayPalIcon(modifier: Modifier = Modifier, size: Dp = 26.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val back = Path().apply {
            moveTo(w * 0.40f, h * 0.10f); lineTo(w * 0.78f, h * 0.10f)
            cubicTo(w * 0.98f, h * 0.10f, w * 0.98f, h * 0.42f, w * 0.76f, h * 0.46f)
            lineTo(w * 0.52f, h * 0.46f); lineTo(w * 0.44f, h * 0.90f); lineTo(w * 0.24f, h * 0.90f)
            close()
        }
        drawPath(back, Brush.topLight(WalletIconPalette.PayPalLight, WalletIconPalette.PayPalBlue, this.size))
        val front = Path().apply {
            moveTo(w * 0.30f, h * 0.10f); lineTo(w * 0.66f, h * 0.10f)
            cubicTo(w * 0.86f, h * 0.10f, w * 0.86f, h * 0.40f, w * 0.64f, h * 0.44f)
            lineTo(w * 0.42f, h * 0.44f); lineTo(w * 0.34f, h * 0.86f); lineTo(w * 0.14f, h * 0.86f)
            close()
        }
        drawPath(front, WalletIconPalette.PayPalBlue)
        drawCircle(WalletIconPalette.Specular, radius = w * 0.06f, center = Offset(w * 0.52f, h * 0.20f))
    }
}

/** 3D card icon (generic payout method). */
@Composable
fun WalletCardIcon(modifier: Modifier = Modifier, size: Dp = 26.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        drawRoundRect(
            Brush.topLight(WalletIconPalette.Blue, WalletIconPalette.BlueDark, this.size),
            topLeft = Offset(w * 0.06f, h * 0.18f), size = Size(w * 0.88f, h * 0.64f),
            cornerRadius = CornerRadius(w * 0.10f)
        )
        drawRect(Color(0xFF1D2B53), topLeft = Offset(w * 0.06f, h * 0.32f), size = Size(w * 0.88f, h * 0.10f))
        drawRoundRect(Color.White.copy(alpha = 0.85f), topLeft = Offset(w * 0.14f, h * 0.56f), size = Size(w * 0.30f, h * 0.08f), cornerRadius = CornerRadius(h * 0.04f))
    }
}

/** Small clock glyph for the Pending Balance header affordance. */
@Composable
fun WalletClockIcon(modifier: Modifier = Modifier, size: Dp = 14.dp, tint: Color = Color(0xFF767676)) {
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f
        drawCircle(tint, radius = r, style = Stroke(width = r * 0.22f))
        val cx = this.size.width / 2; val cy = this.size.height / 2
        val hands = Path().apply {
            moveTo(cx, cy); lineTo(cx, cy - r * 0.55f)
            moveTo(cx, cy); lineTo(cx + r * 0.42f, cy + r * 0.15f)
        }
        drawPath(hands, tint, style = Stroke(width = r * 0.2f))
    }
}

/** Eye glyph for balance visibility affordance. */
@Composable
fun WalletEyeIcon(modifier: Modifier = Modifier, size: Dp = 14.dp, tint: Color = Color(0xFF767676), visible: Boolean = true) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val eye = Path().apply {
            moveTo(w * 0.06f, h * 0.5f)
            cubicTo(w * 0.3f, h * 0.1f, w * 0.7f, h * 0.1f, w * 0.94f, h * 0.5f)
            cubicTo(w * 0.7f, h * 0.9f, w * 0.3f, h * 0.9f, w * 0.06f, h * 0.5f)
        }
        drawPath(eye, tint, style = Stroke(width = h * 0.1f))
        drawCircle(tint, radius = h * 0.16f, center = Offset(w / 2, h / 2))
        if (!visible) {
            drawLine(Color.White, Offset(w * 0.1f, h * 0.9f), Offset(w * 0.9f, h * 0.1f), strokeWidth = h * 0.14f)
            drawLine(tint, Offset(w * 0.1f, h * 0.9f), Offset(w * 0.9f, h * 0.1f), strokeWidth = h * 0.09f)
        }
    }
}
