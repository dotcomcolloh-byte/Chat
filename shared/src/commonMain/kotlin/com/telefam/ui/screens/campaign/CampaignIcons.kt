package com.telefam.ui.screens.campaign

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Campaign goal types, in the exact order of the reference screen. */
enum class CampaignGoal(val apiType: String, val title: String, val subtitle: String) {
    PROFILE_BOOST("PROFILE_BOOST", "Profile Boost", "Grow your profile and get more followers"),
    GET_SALES("GET_SALES", "Get Sales", "Promote your products and increase sales"),
    SERIES_VIDEOS("SERIES_VIDEOS", "Series Videos", "Boost your video series and reach more viewers"),
    LIKES_COMMENTS("LIKES_COMMENTS", "Get Likes & Comments", "Increase engagement on your posts"),
    VIEWS("VIEWS", "Views", "Get more views on your content"),
    FOLLOWERS("FOLLOWERS", "Followers", "Grow your audience and reach new people")
}

/** Short tier section label per goal ("Choose a Tier (Views)"). */
fun CampaignGoal.tierLabel(): String = when (this) {
    CampaignGoal.PROFILE_BOOST -> "Profile Boost"
    CampaignGoal.GET_SALES -> "Get Sales"
    CampaignGoal.SERIES_VIDEOS -> "Series Videos"
    CampaignGoal.LIKES_COMMENTS -> "Likes & Comments"
    CampaignGoal.VIEWS -> "Views"
    CampaignGoal.FOLLOWERS -> "Followers"
}

private val Red = Color(0xFFD32323)
private val RedDark = Color(0xFFB31217)

/**
 * Red gradient "3D-look" glyph for a campaign goal — hand-drawn vector art in the
 * same style as the creator-menu icons. Pure Compose Canvas; no raster assets.
 */
@Composable
fun Goal3DIcon(goal: CampaignGoal, modifier: Modifier = Modifier) {
    Canvas(modifier.size(52.dp)) {
        val s = size.minDimension
        val body = Path().apply {
            val r = s * 0.30f
            addRoundRect(RoundRect(0f, 0f, s, s, r, r))
        }
        drawPath(body, Brush.verticalGradient(listOf(Color(0xFFED3B3B), RedDark)))
        drawPath(body, Color.White.copy(alpha = 0.18f), style = Stroke(width = s * 0.02f))

        val c = Color.White
        val stroke = Stroke(width = s * 0.07f)
        when (goal) {
            CampaignGoal.PROFILE_BOOST -> { // person + up arrow badge
                drawCircle(c, s * 0.10f, Offset(s * 0.44f, s * 0.36f))
                drawArc(c, 180f, 180f, true, Offset(s * 0.26f, s * 0.48f), Size(s * 0.36f, s * 0.26f))
                drawCircle(Color(0xFFFFD54A), s * 0.12f, Offset(s * 0.68f, s * 0.62f))
                val arrow = Path().apply {
                    moveTo(s * 0.68f, s * 0.55f); lineTo(s * 0.73f, s * 0.64f); lineTo(s * 0.695f, s * 0.64f)
                    lineTo(s * 0.695f, s * 0.70f); lineTo(s * 0.665f, s * 0.70f); lineTo(s * 0.665f, s * 0.64f)
                    lineTo(s * 0.63f, s * 0.64f); close()
                }
                drawPath(arrow, RedDark)
            }
            CampaignGoal.GET_SALES -> { // shopping bag
                val bag = Path().apply {
                    addRoundRect(RoundRect(s * 0.30f, s * 0.40f, s * 0.70f, s * 0.72f, s * 0.06f, s * 0.06f))
                }
                drawPath(bag, c)
                drawArc(c, 180f, 180f, false, Offset(s * 0.38f, s * 0.28f), Size(s * 0.24f, s * 0.24f), style = stroke)
                drawCircle(RedDark, s * 0.025f, Offset(s * 0.42f, s * 0.48f))
                drawCircle(RedDark, s * 0.025f, Offset(s * 0.58f, s * 0.48f))
            }
            CampaignGoal.SERIES_VIDEOS -> { // clapperboard + play
                drawRoundRect(c, Offset(s * 0.24f, s * 0.42f), Size(s * 0.52f, s * 0.30f), CornerRadius(s * 0.04f))
                val top = Path().apply {
                    moveTo(s * 0.24f, s * 0.38f); lineTo(s * 0.76f, s * 0.38f); lineTo(s * 0.72f, s * 0.28f); lineTo(s * 0.20f, s * 0.28f); close()
                }
                drawPath(top, c.copy(alpha = 0.85f))
                val play = Path().apply {
                    moveTo(s * 0.45f, s * 0.49f); lineTo(s * 0.45f, s * 0.65f); lineTo(s * 0.60f, s * 0.57f); close()
                }
                drawPath(play, RedDark)
            }
            CampaignGoal.LIKES_COMMENTS -> { // heart
                val heart = Path().apply {
                    moveTo(s * 0.50f, s * 0.68f)
                    cubicTo(s * 0.20f, s * 0.50f, s * 0.26f, s * 0.26f, s * 0.42f, s * 0.30f)
                    cubicTo(s * 0.47f, s * 0.32f, s * 0.50f, s * 0.36f, s * 0.50f, s * 0.38f)
                    cubicTo(s * 0.50f, s * 0.36f, s * 0.53f, s * 0.32f, s * 0.58f, s * 0.30f)
                    cubicTo(s * 0.74f, s * 0.26f, s * 0.80f, s * 0.50f, s * 0.50f, s * 0.68f)
                    close()
                }
                drawPath(heart, c)
            }
            CampaignGoal.VIEWS -> { // eye
                val eye = Path().apply {
                    moveTo(s * 0.22f, s * 0.50f)
                    cubicTo(s * 0.34f, s * 0.34f, s * 0.66f, s * 0.34f, s * 0.78f, s * 0.50f)
                    cubicTo(s * 0.66f, s * 0.66f, s * 0.34f, s * 0.66f, s * 0.22f, s * 0.50f)
                    close()
                }
                drawPath(eye, c)
                drawCircle(RedDark, s * 0.075f, Offset(s * 0.50f, s * 0.50f))
                drawCircle(c, s * 0.025f, Offset(s * 0.525f, s * 0.475f))
            }
            CampaignGoal.FOLLOWERS -> { // people group
                drawCircle(c, s * 0.085f, Offset(s * 0.40f, s * 0.40f))
                drawArc(c, 180f, 180f, true, Offset(s * 0.26f, s * 0.50f), Size(s * 0.28f, s * 0.20f))
                drawCircle(c.copy(alpha = 0.85f), s * 0.07f, Offset(s * 0.62f, s * 0.44f))
                drawArc(c.copy(alpha = 0.85f), 180f, 180f, true, Offset(s * 0.52f, s * 0.53f), Size(s * 0.20f, s * 0.15f))
            }
        }
    }
}

/** Gold 3D star with a highlight facet — used for prices and the balance pill. */
@Composable
fun Star3D(modifier: Modifier = Modifier, spinning: Boolean = false) {
    val angle: Float = if (spinning) {
        val transition = rememberInfiniteTransition(label = "starSpin")
        val a by transition.animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "starSpinAngle"
        )
        a
    } else 0f

    Canvas(modifier.size(28.dp)) {
        val s = size.minDimension
        fun starPath(scale: Float): Path {
            val p = Path()
            for (i in 0 until 10) {
                val a = PI * i / 5 - PI / 2
                val r = if (i % 2 == 0) s * 0.48f * scale else s * 0.20f * scale
                val x = (s / 2 + r * cos(a)).toFloat(); val y = (s / 2 + r * sin(a)).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close(); return p
        }
        rotate(angle, pivot = Offset(s / 2, s / 2)) {
            drawPath(starPath(1f), Brush.verticalGradient(listOf(Color(0xFFFFD54A), Color(0xFFF5A300))))
            // highlight facet for the 3D look
            drawPath(starPath(0.55f), Color(0xFFFFE89A).copy(alpha = 0.8f))
        }
    }
}

/**
 * Paystack brand mark — the four horizontal blue bars of the real logo, drawn as
 * vector shapes in Paystack's brand blues.
 */
@Composable
fun PaystackLogo(modifier: Modifier = Modifier) {
    Canvas(modifier.size(36.dp)) {
        val s = size.minDimension
        val blue = Color(0xFF0AA5E2) // Paystack cyan-blue
        val dark = Color(0xFF053A8C)
        val barH = s * 0.13f
        val gap = s * 0.075f
        val widths = listOf(0.62f, 0.94f, 0.78f, 0.42f)
        widths.forEachIndexed { i, w ->
            drawRoundRect(
                if (i % 2 == 0) blue else dark,
                Offset(s * 0.03f, s * 0.12f + i * (barH + gap)),
                Size(s * w, barH), CornerRadius(barH / 2)
            )
        }
    }
}

/**
 * PayPal brand mark — overlapping "P" monogram in PayPal's two official blues.
 */
@Composable
fun PayPalLogo(modifier: Modifier = Modifier) {
    Canvas(modifier.size(36.dp)) {
        val s = size.minDimension
        fun pGlyph(scale: Float, color: Color, dx: Float, alpha: Float) {
            val p = Path().apply {
                // Stylised italic P: stem + bowl.
                moveTo(s * (0.30f + dx), s * 0.86f)
                lineTo(s * (0.38f + dx), s * 0.14f)
                lineTo(s * (0.64f + dx), s * 0.14f)
                cubicTo(s * (0.82f + dx), s * 0.14f, s * (0.84f + dx), s * 0.42f, s * (0.62f + dx), s * 0.44f)
                lineTo(s * (0.50f + dx), s * 0.44f)
                lineTo(s * (0.46f + dx), s * 0.86f)
                close()
            }
            drawPath(p, color.copy(alpha = alpha))
        }
        pGlyph(1f, Color(0xFF003087), 0.10f, 0.9f)  // back P — PayPal dark blue
        pGlyph(1f, Color(0xFF009CDE), 0.0f, 1f)     // front P — PayPal bright blue
    }
}

/** Wallet glyph used in the balance pill. */
@Composable
fun WalletGlyph(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier.size(26.dp)) {
        val s = size.minDimension
        drawRoundRect(tint, Offset(s * 0.14f, s * 0.26f), Size(s * 0.72f, s * 0.52f), CornerRadius(s * 0.09f))
        drawRoundRect(Red, Offset(s * 0.55f, s * 0.42f), Size(s * 0.28f, s * 0.20f), CornerRadius(s * 0.05f))
        drawCircle(tint, s * 0.045f, Offset(s * 0.67f, s * 0.52f))
    }
}

/** Calendar glyph for the day-switcher card. */
@Composable
fun CalendarGlyph(modifier: Modifier = Modifier, tint: Color = Red) {
    Canvas(modifier.size(28.dp)) {
        val s = size.minDimension
        val stroke = Stroke(width = s * 0.08f)
        drawRoundRect(tint, Offset(s * 0.14f, s * 0.22f), Size(s * 0.72f, s * 0.62f), CornerRadius(s * 0.08f), style = stroke)
        drawLine(tint, Offset(s * 0.14f, s * 0.40f), Offset(s * 0.86f, s * 0.40f), stroke.width)
        drawLine(tint, Offset(s * 0.32f, s * 0.12f), Offset(s * 0.32f, s * 0.26f), stroke.width)
        drawLine(tint, Offset(s * 0.68f, s * 0.12f), Offset(s * 0.68f, s * 0.26f), stroke.width)
        // play triangle inside (video days)
        val play = Path().apply {
            moveTo(s * 0.44f, s * 0.48f); lineTo(s * 0.44f, s * 0.70f); lineTo(s * 0.62f, s * 0.59f); close()
        }
        drawPath(play, tint)
    }
}

/** Lightning bolt for the Boost Now button. */
@Composable
fun BoltGlyph(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier.size(22.dp)) {
        val s = size.minDimension
        val bolt = Path().apply {
            moveTo(s * 0.58f, s * 0.06f); lineTo(s * 0.22f, s * 0.56f); lineTo(s * 0.46f, s * 0.56f)
            lineTo(s * 0.40f, s * 0.94f); lineTo(s * 0.78f, s * 0.42f); lineTo(s * 0.52f, s * 0.42f); close()
        }
        drawPath(bolt, tint)
    }
}
