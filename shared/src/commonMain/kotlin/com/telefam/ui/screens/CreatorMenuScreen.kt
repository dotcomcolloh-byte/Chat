package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.telefam.connect.ProfileDetailsDto
import com.telefam.ui.components.NameWithBadge
import com.telefam.ui.components.VerifiedBadgeVariant
import com.telefam.ui.theme.TelefamColors
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/** The eight creator-menu entries from the reference screen. */
enum class CreatorMenuItem(val title: String, val subtitle: String) {
    DASHBOARD("Dashboard", "View your performance, analytics and insights"),
    STARS("Stars", "Get support from your fans"),
    SUBSCRIPTION("Subscription", "Manage your subscriptions and exclusive content"),
    MONETIZATION("Monetization", "Earn from your content and creator programs"),
    WALLET("Wallet", "View your balance and transaction history"),
    LINKED_DEVICE("Linked device", "Manage your linked devices"),
    CREATE_CAMPAIGN("Create campaign", "Boost your videos and reach new people"),
    MY_CAMPAIGNS("My campaigns", "Track, pause and manage your boosts and analytics"),
    GET_VERIFIED("Get Verified", "Apply for a verified badge and build more trust with your audience.")
}

/**
 * Owner-profile menu (3-dot → Menu). Matches the reference: red header card with the
 * owner's identity, then the eight entries. Every entry is an honest coming-soon
 * placeholder EXCEPT Get Verified, which opens the real verification flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatorMenuScreen(
    profile: ProfileDetailsDto?,
    onBack: () -> Unit,
    onOpenProfile: () -> Unit,
    onGetVerified: () -> Unit,
    onOpenItem: (CreatorMenuItem) -> Unit = {}
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
        ) {
            // ---- Red header with diagonal wave sheen (matches reference) ----
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            listOf(TelefamColors.PrimaryRedDark, TelefamColors.PrimaryRed, TelefamColors.PrimaryRedDark),
                            start = Offset(0f, 0f), end = Offset(900f, 500f)
                        ),
                        RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
                    )
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(bottom = 20.dp)
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                            Text("‹", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("Menu", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    // Profile card row → opens the owner's public profile
                    Surface(
                        onClick = onOpenProfile,
                        color = Color.Transparent,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = profile?.avatarUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(64.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f))
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                NameWithBadge(
                                    name = profile?.displayName ?: "",
                                    isVerified = profile?.isVerified == true,
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White,
                                    badgeVariant = VerifiedBadgeVariant.ON_RED
                                )
                                Text(profile?.displayHandle ?: "", color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
                                Text("Content Creator", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            CreatorMenuItem.entries.forEach { item ->
                MenuRow(
                    item = item,
                    onClick = {
                        when (item) {
                            CreatorMenuItem.GET_VERIFIED -> onGetVerified()
                            // Live screens: Dashboard, Stars, Subscriptions, Monetization, Wallet and Create Campaign.
                            CreatorMenuItem.DASHBOARD,
                            CreatorMenuItem.STARS,
                            CreatorMenuItem.SUBSCRIPTION,
                            CreatorMenuItem.MONETIZATION,
                            CreatorMenuItem.WALLET,
                            CreatorMenuItem.MY_CAMPAIGNS,
                            CreatorMenuItem.CREATE_CAMPAIGN -> onOpenItem(item)
                            else -> scope.launch { snackbar.showSnackbar("${item.title} is coming soon") }
                        }
                    }
                )
                Spacer(Modifier.height(12.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MenuRow(item: CreatorMenuItem, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 72.dp)
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Menu3DIcon(item)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(item.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 17.sp)
            }
            if (item == CreatorMenuItem.GET_VERIFIED) {
                // Red pill CTA, as in the reference — this one is live.
                Surface(color = TelefamColors.PrimaryRed, shape = RoundedCornerShape(50), onClick = onClick) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Get Verified", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            } else {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/**
 * Red gradient "3D-look" icon badge with a hand-drawn white glyph per entry.
 * Pure vector drawing — no emoji, no raster assets.
 */
@Composable
private fun Menu3DIcon(item: CreatorMenuItem) {
    androidx.compose.foundation.Canvas(Modifier.size(48.dp)) {
        val s = size.minDimension
        // Rounded-square body with a vertical gradient + bottom shadow lip for the 3D feel.
        val body = Path().apply {
            val r = s * 0.28f
            addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, s, s, r, r))
        }
        drawPath(body, Brush.verticalGradient(listOf(Color(0xFFED3B3B), TelefamColors.PrimaryRedDark)))
        drawPath(body, Color.White.copy(alpha = 0.18f), style = Stroke(width = s * 0.02f))

        val c = Color.White
        val stroke = Stroke(width = s * 0.07f)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(c, Offset(x1, y1), Offset(x2, y2), stroke.width)
        when (item) {
            CreatorMenuItem.DASHBOARD -> { // bar chart
                listOf(0.30f to 0.62f, 0.48f to 0.46f, 0.66f to 0.32f).forEach { (x, top) ->
                    drawRoundRect(c, Offset(s * x - s * 0.055f, s * top), androidx.compose.ui.geometry.Size(s * 0.11f, s * 0.68f - s * top),
                        androidx.compose.ui.geometry.CornerRadius(s * 0.03f))
                }
            }
            CreatorMenuItem.STARS -> { // star
                val p = Path()
                for (i in 0 until 10) {
                    val a = PI * i / 5 - PI / 2
                    val r = if (i % 2 == 0) s * 0.26f else s * 0.11f
                    val x = (s / 2 + r * cos(a)).toFloat(); val y = (s / 2 + r * sin(a)).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close(); drawPath(p, c)
            }
            CreatorMenuItem.SUBSCRIPTION -> { // crown
                val p = Path().apply {
                    moveTo(s * 0.26f, s * 0.64f); lineTo(s * 0.24f, s * 0.38f); lineTo(s * 0.38f, s * 0.50f)
                    lineTo(s * 0.50f, s * 0.32f); lineTo(s * 0.62f, s * 0.50f); lineTo(s * 0.76f, s * 0.38f)
                    lineTo(s * 0.74f, s * 0.64f); close()
                }
                drawPath(p, c)
            }
            CreatorMenuItem.MONETIZATION -> { // coin with "$" drawn as vector strokes (multiplatform-safe)
                drawCircle(c, s * 0.27f, Offset(s / 2, s / 2), style = stroke)
                // $ : vertical bar + S-curve approximated with two arcs
                line(s * 0.50f, s * 0.30f, s * 0.50f, s * 0.70f)
                drawArc(c, 250f, 260f, true, Offset(s * 0.36f, s * 0.32f), androidx.compose.ui.geometry.Size(s * 0.28f, s * 0.18f), style = stroke)
                drawArc(c, 70f, 260f, true, Offset(s * 0.36f, s * 0.50f), androidx.compose.ui.geometry.Size(s * 0.28f, s * 0.18f), style = stroke)
            }
            CreatorMenuItem.WALLET -> { // wallet
                drawRoundRect(c, Offset(s * 0.24f, s * 0.34f), androidx.compose.ui.geometry.Size(s * 0.52f, s * 0.34f),
                    androidx.compose.ui.geometry.CornerRadius(s * 0.06f))
                drawRoundRect(TelefamColors.PrimaryRed, Offset(s * 0.56f, s * 0.45f), androidx.compose.ui.geometry.Size(s * 0.20f, s * 0.12f),
                    androidx.compose.ui.geometry.CornerRadius(s * 0.04f))
                drawCircle(c, s * 0.028f, Offset(s * 0.66f, s * 0.51f))
            }
            CreatorMenuItem.LINKED_DEVICE -> { // two overlapping devices
                drawRoundRect(c, Offset(s * 0.26f, s * 0.30f), androidx.compose.ui.geometry.Size(s * 0.30f, s * 0.22f),
                    androidx.compose.ui.geometry.CornerRadius(s * 0.04f), style = stroke)
                drawRoundRect(c, Offset(s * 0.46f, s * 0.46f), androidx.compose.ui.geometry.Size(s * 0.24f, s * 0.26f),
                    androidx.compose.ui.geometry.CornerRadius(s * 0.04f))
            }
            CreatorMenuItem.CREATE_CAMPAIGN -> { // megaphone
                val p = Path().apply {
                    moveTo(s * 0.26f, s * 0.56f); lineTo(s * 0.26f, s * 0.44f); lineTo(s * 0.58f, s * 0.32f)
                    lineTo(s * 0.58f, s * 0.68f); close()
                }
                drawPath(p, c)
                line(s * 0.30f, s * 0.58f, s * 0.34f, s * 0.70f)
                drawArc(c, 300f, 120f, false, Offset(s * 0.58f, s * 0.36f), androidx.compose.ui.geometry.Size(s * 0.2f, s * 0.28f), style = stroke)
            }
            CreatorMenuItem.MY_CAMPAIGNS -> { // rising analytics chart with an arrow
                listOf(0.28f to 0.66f, 0.46f to 0.54f, 0.64f to 0.38f).forEach { (x, top) ->
                    drawRoundRect(c, Offset(s * x - s * 0.05f, s * top), androidx.compose.ui.geometry.Size(s * 0.10f, s * 0.72f - s * top),
                        androidx.compose.ui.geometry.CornerRadius(s * 0.03f))
                }
                val arrow = Path().apply {
                    moveTo(s * 0.26f, s * 0.42f); lineTo(s * 0.46f, s * 0.32f); lineTo(s * 0.60f, s * 0.38f); lineTo(s * 0.76f, s * 0.24f)
                }
                drawPath(arrow, TelefamColors.PrimaryRed, style = Stroke(width = s * 0.045f))
                val head = Path().apply {
                    moveTo(s * 0.76f, s * 0.24f); lineTo(s * 0.64f, s * 0.24f); moveTo(s * 0.76f, s * 0.24f); lineTo(s * 0.76f, s * 0.36f)
                }
                drawPath(head, TelefamColors.PrimaryRed, style = Stroke(width = s * 0.045f))
            }
            CreatorMenuItem.GET_VERIFIED -> { // scalloped seal + check
                val p = Path()
                for (i in 0 until 24) {
                    val a = PI * i / 12 - PI / 2
                    val r = if (i % 2 == 0) s * 0.27f else s * 0.22f
                    val x = (s / 2 + r * cos(a)).toFloat(); val y = (s / 2 + r * sin(a)).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close(); drawPath(p, c)
                val check = Path().apply {
                    moveTo(s * 0.38f, s * 0.51f); lineTo(s * 0.47f, s * 0.60f); lineTo(s * 0.64f, s * 0.40f)
                }
                drawPath(check, TelefamColors.PrimaryRed, style = Stroke(width = s * 0.05f))
            }
        }
    }
}
