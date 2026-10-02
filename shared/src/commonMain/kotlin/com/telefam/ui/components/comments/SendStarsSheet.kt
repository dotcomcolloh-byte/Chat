package com.telefam.ui.components.comments

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.telefam.data.api.CampaignApi
import com.telefam.data.api.StarPackagesDto
import com.telefam.posts.CommentsViewModel
import com.telefam.ui.components.avatarFallback
import com.telefam.ui.theme.TelefamColors
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Continuously spinning star (rotateY 3D flip + slow Z spin), size/tier aware. */
@Composable
fun SpinningStar(sizeDp: Int, tint: Color = Color(0xFFFFC107), modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "starSpin")
    val rotationY by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "spinY"
    )
    val rotationZ by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "spinZ"
    )
    Icon(
        Icons.Filled.Star, contentDescription = null, tint = tint,
        modifier = modifier.size(sizeDp.dp).graphicsLayer {
            this.rotationY = rotationY
            this.rotationZ = rotationZ
            cameraDistance = 12f * density
        }
    )
}

/**
 * Send Stars sheet (reference image 2): creator header, live balance chip, and the
 * fixed star tiers with prices converted to the viewer's own currency — the server
 * prices packages from the profile's country (same catalog as the campaign star store,
 * so Buy Stars reuses that UI without duplication).
 */
@Composable
fun SendStarsSheet(
    creatorName: String,
    creatorUsername: String?,
    creatorAvatarUrl: String?,
    campaignApi: CampaignApi,
    sending: Boolean,
    onSend: (stars: Long) -> Unit,
    onBuyStars: () -> Unit,
    onClose: () -> Unit
) {
    var packages by remember { mutableStateOf<StarPackagesDto?>(null) }
    var balance by remember { mutableStateOf<Long?>(null) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        failed = false
        runCatching {
            val pkgs = campaignApi.packages()
            val bal = campaignApi.balance()
            packages = pkgs
            balance = bal.balanceStars
        }.onFailure { failed = true }
    }

    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
    ) {
        // Header
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Send Stars", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f)
            )
            // Live balance chip
            Row(
                Modifier.clip(RoundedCornerShape(20.dp))
                    .background(TelefamColors.PrimaryRed)
                    .clickable(onClick = onBuyStars)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = Color(0xFFFFC107), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    "Balance: ${balance?.let { "%,d".format(it) } ?: "…"}",
                    color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(8.dp))
        }

        // Creator card
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .background(TelefamColors.PrimaryRed.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                if (creatorAvatarUrl != null) {
                    AsyncImage(
                        model = creatorAvatarUrl, contentDescription = null,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        contentScale = ContentScale.Crop,
                        placeholder = avatarFallback(), error = avatarFallback()
                    )
                } else {
                    Text(creatorName.take(1).uppercase(), fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(creatorName, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                creatorUsername?.let {
                    Text("@$it", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SpinningStar(sizeDp = 34)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Select stars to send", fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface)
                Text("Show your support and help them grow!", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("1 star = 1 appreciation", fontSize = 11.sp,
                    color = TelefamColors.PrimaryRed, fontWeight = FontWeight.SemiBold)
            }
        }

        when {
            failed -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("Couldn't load star options. Check your connection and try again.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            packages == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TelefamColors.PrimaryRed)
            }
            else -> LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 420.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(packages!!.packages, key = { it.id }) { pkg ->
                    val affordable = (balance ?: 0) >= pkg.stars
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SpinningStar(sizeDp = 30)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("%,d".format(pkg.stars), fontWeight = FontWeight.Bold, fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text(pkg.formattedAmount, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box(
                            Modifier.clip(RoundedCornerShape(22.dp))
                                .background(
                                    if (sending) MaterialTheme.colorScheme.surfaceVariant
                                    else TelefamColors.PrimaryRed
                                )
                                .clickable(enabled = !sending) {
                                    if (affordable) onSend(pkg.stars) else onBuyStars()
                                }
                                .padding(horizontal = 28.dp, vertical = 10.dp)
                        ) {
                            Text(
                                "Send", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

/**
 * Full-screen 3D star celebration after a successful gift: a large rotating star with
 * the star value, wrapped in glittering sparkles. Higher star tiers add more sparkles,
 * a halo ring and faster spin — the more you give, the more unique the burst.
 */
@Composable
fun StarCelebration(stars: Long, senderName: String, onDone: () -> Unit) {
    val tier = when {
        stars >= 10_000 -> 3
        stars >= 1_000 -> 2
        stars >= 100 -> 1
        else -> 0
    }
    val sparkleCount = 12 + tier * 10
    val sparkles = remember(stars) {
        List(sparkleCount) {
            Triple(Random.nextFloat() * 360f, 60f + Random.nextFloat() * 140f, 6 + Random.nextInt(10 + tier * 4))
        }
    }
    val transition = rememberInfiniteTransition(label = "celebration")
    val spin by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween((2600 - tier * 500).coerceAtLeast(900), easing = LinearEasing)),
        label = "bigSpin"
    )
    val orbit by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4000 - tier * 700, easing = LinearEasing)),
        label = "orbit"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.85f, targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(700), repeatMode = androidx.compose.animation.core.RepeatMode.Reverse),
        label = "pulse"
    )

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)).clickable(onClick = onDone),
        contentAlignment = Alignment.Center
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Glittering sparkles orbiting the star
            sparkles.forEachIndexed { i, (angle, radius, size) ->
                val a = Math.toRadians((angle + orbit * (if (i % 2 == 0) 1 else -1)).toDouble())
                val x = (kotlin.math.cos(a) * radius).toFloat()
                val y = (kotlin.math.sin(a) * radius).toFloat()
                Icon(
                    Icons.Filled.Star, contentDescription = null,
                    tint = when (i % 3) {
                        0 -> Color(0xFFFFC107)
                        1 -> Color(0xFFFFE082)
                        else -> Color(0xFFFF8A65)
                    },
                    modifier = Modifier
                        .offset(x.dp, y.dp)
                        .size(size.dp)
                        .graphicsLayer { alpha = 0.6f + 0.4f * kotlin.math.sin((orbit + angle).toDouble()).toFloat() }
                )
            }
            // Halo ring for big gifts
            if (tier >= 2) {
                Box(
                    Modifier.size(190.dp).clip(CircleShape)
                        .graphicsLayer { scaleX = pulse; scaleY = pulse; alpha = 0.5f }
                        .background(Color(0x22FFC107))
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.Star, contentDescription = null, tint = Color(0xFFFFC107),
                    modifier = Modifier.size((110 + tier * 20).dp).graphicsLayer {
                        rotationY = spin
                        rotationZ = spin / 3f
                        scaleX = pulse; scaleY = pulse
                        cameraDistance = 8f * density
                    }
                )
                Text(
                    "%,d".format(stars),
                    color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp
                )
                Text(
                    "stars sent to $senderName",
                    color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp
                )
            }
        }
    }
}
