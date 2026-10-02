package com.telefam.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.PathEffect
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/**
 * The Telefam verified badge — a vector (SVG-equivalent) scalloped seal with a check,
 * drawn in Compose (no raster asset). Brand red by default; adapts for dark surfaces
 * and offers a white variant for use on the red header. The badge is only rendered
 * when the backend says the user holds an unexpired badge — it is a display element,
 * never a grant.
 */
object VerifiedBadgeColors {
    val Red = Color(0xFFD32323)
    val RedDark = Color(0xFFB31217)
    val White = Color(0xFFFFFFFF)
}

@Composable
fun VerifiedBadge(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    /** ON_SURFACE (red), ON_RED (white badge for red headers) */
    variant: VerifiedBadgeVariant = VerifiedBadgeVariant.ON_SURFACE
) {
    val dark = isSystemInDarkTheme()
    val fill = when (variant) {
        VerifiedBadgeVariant.ON_RED -> VerifiedBadgeColors.White
        VerifiedBadgeVariant.ON_SURFACE -> if (dark) VerifiedBadgeColors.Red else VerifiedBadgeColors.Red
    }
    val checkColor = when (variant) {
        VerifiedBadgeVariant.ON_RED -> VerifiedBadgeColors.Red
        VerifiedBadgeVariant.ON_SURFACE -> Color.White
    }
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        // 12-scallop seal outline — a continuous star-scallop path (matches the reference icon).
        val scallops = 12
        val path = Path()
        for (i in 0 until scallops * 2) {
            val angle = PI * i / scallops - PI / 2
            val radius = if (i % 2 == 0) r else r * 0.82f
            val x = (cx + radius * cos(angle)).toFloat()
            val y = (cy + radius * sin(angle)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, fill)
        // Check mark
        val check = Path().apply {
            moveTo(cx - r * 0.42f, cy + r * 0.02f)
            lineTo(cx - r * 0.10f, cy + r * 0.32f)
            lineTo(cx + r * 0.46f, cy - r * 0.30f)
        }
        drawPath(check, checkColor, style = Stroke(width = r * 0.22f, pathEffect = PathEffect.cornerPathEffect(r * 0.1f)))
    }
}

enum class VerifiedBadgeVariant { ON_SURFACE, ON_RED }

/**
 * Name + badge row. Use everywhere a display name appears: inbox, contacts, feeds,
 * comments, profiles, lists, search. Badge shown only when [isVerified] (from the server).
 */
@Composable
fun NameWithBadge(
    name: String,
    isVerified: Boolean,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
    badgeVariant: VerifiedBadgeVariant = VerifiedBadgeVariant.ON_SURFACE,
    badgeSize: Dp = 15.dp,
    maxLines: Int = 1
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = style, color = color, maxLines = maxLines)
        if (isVerified) {
            Spacer(Modifier.width(4.dp))
            VerifiedBadge(size = badgeSize, variant = badgeVariant)
        }
    }
}
