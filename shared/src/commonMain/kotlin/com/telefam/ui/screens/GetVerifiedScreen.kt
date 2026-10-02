package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.telefam.connect.ProfileDetailsDto
import com.telefam.ui.components.VerifiedBadge
import com.telefam.ui.theme.TelefamColors
import com.telefam.verification.PricingResponseDto

private data class Benefit(val title: String, val subtitle: String)

private val BENEFITS = listOf(
    Benefit("Download High-Quality Videos", "Let your fans download your videos in full HD quality. No more limits."),
    Benefit("Remove Ads", "Enjoy an ad-free experience across the app."),
    Benefit("Protection from Impersonation", "Get a verified badge to prevent fake accounts and build trust."),
    Benefit("Post Paid Content", "Share exclusive content and charge for your premium posts."),
    Benefit("Get Ranked", "Appear higher in search results, explore and recommendations."),
    Benefit("More Credibility", "Gain followers, collaborations and more opportunities."),
    Benefit("HD Uploads", "Share high quality photos and videos in full clarity."),
    Benefit("Send Paid Messages", "Charge for your messages and earn from your content."),
    Benefit("Exclusive Features", "Access early features, special tools and creator perks."),
    Benefit("Better Visibility", "Stand out in search, get featured and reach more people."),
    Benefit("Priority Support", "Get faster help and dedicated support.")
)

/**
 * Get Verified paywall — matches the reference screen. Pricing, currency and the
 * single offered payment method all come from the server (country-based); the app
 * shows exactly one payment method with no provider choice.
 */
@Composable
fun GetVerifiedScreen(
    profile: ProfileDetailsDto?,
    pricing: PricingResponseDto?,
    loading: Boolean,
    error: String?,
    selectedPlan: String,
    onSelectPlan: (String) -> Unit,
    onBack: () -> Unit,
    onStartNow: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ---- Red header ----
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(TelefamColors.PrimaryRedDark, TelefamColors.PrimaryRed, TelefamColors.PrimaryRedDark),
                        start = Offset(0f, 0f), end = Offset(900f, 400f)
                    ),
                    RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
                )
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(bottom = 16.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Text("‹", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f)) {
                    Text("Get Verified", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("Stand out. Be trusted.", color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
                }
                // "Start Now" with rocket — jumps straight to payment
                Surface(onClick = onStartNow, color = Color.Transparent) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                        RocketGlyph()
                        Spacer(Modifier.width(4.dp))
                        Text("Start Now", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(16.dp))
            // ---- Verified preview card (as in the reference: verified sample profile) ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    AsyncImage(
                        model = profile?.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(72.dp).clip(CircleShape)
                            .border(2.dp, TelefamColors.PrimaryRed, CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                    Box(Modifier.align(Alignment.BottomEnd)) { VerifiedBadge(size = 22.dp) }
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(profile?.displayName ?: "", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(Modifier.width(6.dp))
                        VerifiedBadge(size = 16.dp)
                    }
                    Text(profile?.displayHandle ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    Text(
                        "Verified for a safer, more trusted experience.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ---- Pricing row: Monthly | toggle | Annual ----
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            "${pricing?.monthly?.formatted ?: "…"} / month",
                            fontWeight = FontWeight.Bold, fontSize = 20.sp
                        )
                        Text("Billed monthly", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    // Monthly/Annual segmented toggle
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Row {
                            listOf("MONTHLY" to "Monthly", "ANNUAL" to "Annual").forEach { (key, label) ->
                                val selected = selectedPlan == key
                                Surface(
                                    onClick = { onSelectPlan(key) },
                                    color = if (selected) TelefamColors.PrimaryRed else Color.Transparent,
                                    shape = RoundedCornerShape(50)
                                ) {
                                    Text(
                                        label,
                                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${pricing?.annual?.formatted ?: "…"} / year",
                                fontWeight = FontWeight.Bold, fontSize = 16.sp
                            )
                            pricing?.annual?.takeIf { it.offPercent > 0 }?.let {
                                Spacer(Modifier.width(6.dp))
                                Surface(color = TelefamColors.PrimaryRed, shape = RoundedCornerShape(50)) {
                                    Text("${it.offPercent}% OFF", color = Color.White, fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                                }
                            }
                        }
                        pricing?.annual?.strikeFormatted?.let {
                            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                                textDecoration = TextDecoration.LineThrough)
                        }
                    }
                }
            }

            // The single server-selected payment method, disclosed without a choice.
            pricing?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Payment via ${it.providerLabel} · billed in ${it.currency}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp
                )
            }

            Spacer(Modifier.height(20.dp))
            Text("Verification Benefits", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text("More visibility. More control. More opportunities.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))

            BENEFITS.forEachIndexed { i, b ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    BenefitGlyph(i)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(b.title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(b.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 17.sp)
                    }
                }
                if (i < BENEFITS.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
            Spacer(Modifier.height(16.dp))
            error?.let {
                Text(it, color = TelefamColors.PrimaryRed, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
            }
        }

        // ---- CTA ----
        Column(Modifier.padding(20.dp)) {
            Button(
                onClick = onStartNow,
                enabled = !loading && pricing != null,
                colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                if (loading) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                else Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Get Verified Now", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Cancel anytime. Secure payment.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

/** Small red rocket glyph (vector) for the header's Start Now. */
@Composable
private fun RocketGlyph() {
    androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
        val s = size.minDimension
        val c = Color.White
        val body = androidx.compose.ui.graphics.Path().apply {
            moveTo(s * 0.5f, s * 0.08f)
            quadraticTo(s * 0.82f, s * 0.30f, s * 0.72f, s * 0.62f)
            lineTo(s * 0.28f, s * 0.62f)
            quadraticTo(s * 0.18f, s * 0.30f, s * 0.5f, s * 0.08f)
            close()
        }
        drawPath(body, c)
        drawCircle(TelefamColors.PrimaryRed, s * 0.09f, Offset(s * 0.5f, s * 0.38f))
        val flame = androidx.compose.ui.graphics.Path().apply {
            moveTo(s * 0.38f, s * 0.66f); lineTo(s * 0.5f, s * 0.92f); lineTo(s * 0.62f, s * 0.66f); close()
        }
        drawPath(flame, c)
    }
}

/** Red rounded-square benefit glyph (vector per benefit index — matches the reference order). */
@Composable
private fun BenefitGlyph(index: Int) {
    androidx.compose.foundation.Canvas(Modifier.size(40.dp)) {
        val s = size.minDimension
        val rr = androidx.compose.ui.graphics.Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, s, s, s * 0.30f, s * 0.30f))
        }
        drawPath(rr, TelefamColors.PrimaryRed.copy(alpha = 0.12f))
        val c = TelefamColors.PrimaryRed
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = s * 0.07f)
        when (index % 11) {
            0 -> { // download
                drawLine(c, Offset(s * 0.5f, s * 0.25f), Offset(s * 0.5f, s * 0.58f), stroke.width)
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(s * 0.32f, s * 0.48f); lineTo(s * 0.5f, s * 0.66f); lineTo(s * 0.68f, s * 0.48f)
                }
                drawPath(p, c, style = stroke)
                drawLine(c, Offset(s * 0.30f, s * 0.74f), Offset(s * 0.70f, s * 0.74f), stroke.width)
            }
            1 -> { // no-ads circle
                drawCircle(c, s * 0.26f, Offset(s / 2, s / 2), style = stroke)
                drawLine(c, Offset(s * 0.33f, s * 0.67f), Offset(s * 0.67f, s * 0.33f), stroke.width)
            }
            2 -> { // shield
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(s * 0.5f, s * 0.22f); lineTo(s * 0.74f, s * 0.32f)
                    quadraticTo(s * 0.74f, s * 0.62f, s * 0.5f, s * 0.78f)
                    quadraticTo(s * 0.26f, s * 0.62f, s * 0.26f, s * 0.32f)
                    close()
                }
                drawPath(p, c, style = stroke)
            }
            3 -> { // paid content: doc + lock
                drawRoundRect(c, Offset(s * 0.30f, s * 0.22f), androidx.compose.ui.geometry.Size(s * 0.32f, s * 0.44f),
                    androidx.compose.ui.geometry.CornerRadius(s * 0.05f), style = stroke)
                drawRoundRect(c, Offset(s * 0.52f, s * 0.52f), androidx.compose.ui.geometry.Size(s * 0.24f, s * 0.20f),
                    androidx.compose.ui.geometry.CornerRadius(s * 0.04f))
            }
            4 -> { // ranked: rising arrow
                drawLine(c, Offset(s * 0.26f, s * 0.70f), Offset(s * 0.68f, s * 0.30f), stroke.width)
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(s * 0.52f, s * 0.28f); lineTo(s * 0.72f, s * 0.28f); lineTo(s * 0.72f, s * 0.48f)
                }
                drawPath(p, c, style = stroke)
            }
            5 -> { // people
                drawCircle(c, s * 0.10f, Offset(s * 0.36f, s * 0.36f))
                drawCircle(c, s * 0.10f, Offset(s * 0.64f, s * 0.36f))
                drawArc(c, 180f, 180f, true, Offset(s * 0.22f, s * 0.50f), androidx.compose.ui.geometry.Size(s * 0.28f, s * 0.24f), style = stroke)
                drawArc(c, 180f, 180f, true, Offset(s * 0.50f, s * 0.50f), androidx.compose.ui.geometry.Size(s * 0.28f, s * 0.24f), style = stroke)
            }
            6 -> { // HD
                drawRoundRect(c, Offset(s * 0.22f, s * 0.30f), androidx.compose.ui.geometry.Size(s * 0.56f, s * 0.40f),
                    androidx.compose.ui.geometry.CornerRadius(s * 0.06f), style = stroke)
                drawLine(c, Offset(s * 0.34f, s * 0.40f), Offset(s * 0.34f, s * 0.60f), stroke.width)
                drawLine(c, Offset(s * 0.46f, s * 0.40f), Offset(s * 0.46f, s * 0.60f), stroke.width)
                drawLine(c, Offset(s * 0.34f, s * 0.50f), Offset(s * 0.46f, s * 0.50f), stroke.width)
                drawArc(c, 270f, 180f, true, Offset(s * 0.52f, s * 0.40f), androidx.compose.ui.geometry.Size(s * 0.16f, s * 0.20f), style = stroke)
            }
            7 -> { // paid messages: bubble with coin
                drawRoundRect(c, Offset(s * 0.24f, s * 0.26f), androidx.compose.ui.geometry.Size(s * 0.52f, s * 0.36f),
                    androidx.compose.ui.geometry.CornerRadius(s * 0.10f), style = stroke)
                drawCircle(c, s * 0.08f, Offset(s * 0.5f, s * 0.44f))
            }
            8 -> { // star
                val p = androidx.compose.ui.graphics.Path()
                for (i in 0 until 10) {
                    val a = Math.PI * i / 5 - Math.PI / 2
                    val r = if (i % 2 == 0) s * 0.26f else s * 0.11f
                    val x = (s / 2 + r * kotlin.math.cos(a)).toFloat(); val y = (s / 2 + r * kotlin.math.sin(a)).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close(); drawPath(p, c)
            }
            9 -> { // visibility eye
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(s * 0.22f, s * 0.5f)
                    quadraticTo(s * 0.5f, s * 0.24f, s * 0.78f, s * 0.5f)
                    quadraticTo(s * 0.5f, s * 0.76f, s * 0.22f, s * 0.5f)
                    close()
                }
                drawPath(p, c, style = stroke)
                drawCircle(c, s * 0.07f, Offset(s * 0.5f, s * 0.5f))
            }
            else -> { // priority support: headset
                drawArc(c, 180f, 180f, true, Offset(s * 0.26f, s * 0.26f), androidx.compose.ui.geometry.Size(s * 0.48f, s * 0.42f), style = stroke)
                drawRoundRect(c, Offset(s * 0.24f, s * 0.48f), androidx.compose.ui.geometry.Size(s * 0.10f, s * 0.16f),
                    androidx.compose.ui.geometry.CornerRadius(s * 0.03f))
                drawRoundRect(c, Offset(s * 0.66f, s * 0.48f), androidx.compose.ui.geometry.Size(s * 0.10f, s * 0.16f),
                    androidx.compose.ui.geometry.CornerRadius(s * 0.03f))
            }
        }
    }
}
