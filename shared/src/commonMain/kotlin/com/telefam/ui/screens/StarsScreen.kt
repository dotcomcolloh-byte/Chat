package com.telefam.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.telefam.connect.ProfileDetailsDto
import com.telefam.creator.CreatorViewModel
import com.telefam.ui.components.NameWithBadge
import com.telefam.ui.theme.TelefamColors
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val SPurple = Color(0xFF8E44AD)
private val SGreen = Color(0xFF2E9E4F)
private val SBlue = Color(0xFF1E88E5)
private val SOrange = Color(0xFFF29900)
private val SYellow = Color(0xFFF5B301)

/** Stars Earnings — matches the Stars reference screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarsScreen(
    profile: ProfileDetailsDto?,
    viewModel: CreatorViewModel,
    onBack: () -> Unit,
    onViewInsights: (Int) -> Unit,
    onSeeAllTransactions: () -> Unit,
    onOpenSupporters: () -> Unit
) {
    var periodDays by remember { mutableStateOf(7) }
    var showHelp by remember { mutableStateOf(false) }
    var showGoalSheet by remember { mutableStateOf(false) }
    val state by viewModel.stars.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(periodDays) { viewModel.loadStars(periodDays) }

    if (showHelp) {
        CreatorHelpDialog(
            title = "About Stars",
            text = "Stars are gifts your fans send during your content. Each Star you receive earns you money — the overview shows your earnings for the selected period, your supporters, and your daily average. Set a Stars goal to track a target, and check Recent Transactions to see every gift. Pull down to refresh.",
            onDismiss = { showHelp = false }
        )
    }

    if (showGoalSheet) {
        val o = state.overview
        StarGoalDialog(
            initialTitle = o?.goalTitle ?: "",
            initialTarget = o?.goalTargetStars?.toString() ?: "",
            onDismiss = { showGoalSheet = false },
            onSave = { title, target ->
                scope.launch {
                    viewModel.setStarGoal(title, target)
                    showGoalSheet = false
                }
            }
        )
    }

    Scaffold { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = { viewModel.loadStars(periodDays) },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            Column(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState())
            ) {
                CreatorTopBar("Stars Earnings", onBack = onBack, onHelp = { showHelp = true })
                CreatorProfileHeader(profile)

                if (state.error) {
                    CreatorLoadError(onRetry = { viewModel.loadStars(periodDays) })
                    return@Column
                }

                val o = state.overview

                // ---- Total earnings card ----
                Surface(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp
                ) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Total Stars Earnings",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(formatUsd(o?.totalEarningsCentsAllTime ?: 0),
                                    fontSize = 30.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Outlined.Star, contentDescription = null,
                                    tint = SYellow, modifier = Modifier.size(22.dp))
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("All time", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        StarBurst3D(Modifier.size(88.dp))
                    }
                }

                // ---- Earnings overview ----
                Surface(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Earnings Overview", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                                modifier = Modifier.weight(1f))
                            PeriodDropdown(selectedDays = periodDays, onSelected = { periodDays = it })
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth()) {
                            CreatorStatCell(Icons.Outlined.MonetizationOn, SPurple,
                                formatUsd(o?.earningsCents ?: 0), "Earnings", o?.earningsDeltaPercent,
                                deltaColor = SPurple, modifier = Modifier.weight(1f))
                            CreatorStatCell(Icons.Outlined.Star, SGreen,
                                "${o?.starsReceived ?: 0}", "Stars Received", o?.starsDeltaPercent,
                                deltaColor = SGreen, modifier = Modifier.weight(1f))
                            CreatorStatCell(Icons.Outlined.People, SBlue,
                                "${o?.supporters ?: 0}", "Supporters", o?.supportersDeltaPercent,
                                deltaColor = SBlue, modifier = Modifier.weight(1f))
                            CreatorStatCell(Icons.AutoMirrored.Outlined.TrendingUp, SOrange,
                                formatUsd(o?.dailyAverageCents ?: 0), "Daily Average", o?.dailyAvgDeltaPercent,
                                deltaColor = SOrange, modifier = Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onViewInsights(periodDays) }
                                .padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Outlined.BarChart, contentDescription = null, tint = TelefamColors.PrimaryRed)
                            Spacer(Modifier.width(8.dp))
                            Text("View Earnings Insights", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Spacer(Modifier.weight(1f))
                            Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null,
                                modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // ---- Stars goal ----
                Surface(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Stars Goal", fontWeight = FontWeight.Bold, fontSize = 17.sp,
                                modifier = Modifier.weight(1f))
                            TextButton(onClick = { showGoalSheet = true }) {
                                Text(if (o?.goalTargetStars != null) "Edit Goal" else "Set Goal",
                                    color = TelefamColors.PrimaryRed, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        if (o?.goalTargetStars != null) {
                            val target = o.goalTargetStars
                            val progress = o.goalProgressStars
                            if (!o.goalTitle.isNullOrBlank()) {
                                Text(o.goalTitle, fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(6.dp))
                            }
                            LinearProgressIndicator(
                                progress = { (progress.toFloat() / target.toFloat()).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                color = SYellow,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                            Spacer(Modifier.height(6.dp))
                            Text("$progress / $target Stars",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text("Set a Stars goal to motivate your fans and track your progress.",
                                fontSize = 13.sp, lineHeight = 18.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // ---- Supporters ----
                Surface(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { onOpenSupporters() },
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(SBlue.copy(alpha = 0.10f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.People, contentDescription = null, tint = SBlue)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Supporters", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("See the fans who send you the most Stars",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null,
                            modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // ---- Recent transactions ----
                Surface(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Recent Transactions", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                                modifier = Modifier.weight(1f))
                            Text(
                                "See All", color = TelefamColors.PrimaryRed, fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                    .clickable { onSeeAllTransactions() }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                        val txs = state.transactions
                        if (txs.isEmpty()) {
                            Spacer(Modifier.height(20.dp))
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    Modifier.size(56.dp).clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Outlined.Receipt, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(12.dp))
                                Text("No transactions yet", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Spacer(Modifier.height(4.dp))
                                Text("You haven't received any Stars yet.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            }
                            Spacer(Modifier.height(12.dp))
                        } else {
                            txs.take(5).forEachIndexed { i, tx ->
                                if (i > 0) HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                StarTransactionRow(tx)
                            }
                        }
                    }
                }

                // ---- How Stars Works ----
                Surface(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("How Stars Works", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth()) {
                            HowItWorksCell(Icons.Outlined.Star, SYellow, "Fans send you Stars",
                                "Your fans purchase Stars and send them during your content.",
                                Modifier.weight(1f))
                            HowItWorksCell(Icons.Outlined.AccountBalanceWallet, TelefamColors.PrimaryRed,
                                "You earn from Stars",
                                "You earn money for the Stars you receive from fans.",
                                Modifier.weight(1f))
                            HowItWorksCell(Icons.Outlined.AccountBalance, SGreen, "Get paid",
                                "Your earnings are added to your balance and paid out.",
                                Modifier.weight(1f))
                        }
                    }
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun StarTransactionRow(tx: com.telefam.data.api.StarTransactionDto) {
    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = tx.fromAvatarUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(40.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            NameWithBadge(
                name = tx.fromName ?: tx.fromUsername ?: "A fan",
                isVerified = tx.fromVerified,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(tx.createdAt.take(10), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Star, contentDescription = null, tint = SYellow, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(3.dp))
                Text("${tx.stars}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Text("+${formatUsd(tx.amountCents)}", color = SGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun HowItWorksCell(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color, title: String, subtitle: String, modifier: Modifier = Modifier
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(tint.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
            lineHeight = 16.sp, textAlign = TextAlign.Center)
    }
}

/** Goal editor dialog. */
@Composable
private fun StarGoalDialog(
    initialTitle: String,
    initialTarget: String,
    onDismiss: () -> Unit,
    onSave: (title: String, target: Long) -> Unit
) {
    var title by remember { mutableStateOf(initialTitle) }
    var target by remember { mutableStateOf(initialTarget) }
    val targetLong = target.toLongOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set a Stars Goal", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = title, onValueChange = { title = it.take(120) },
                    label = { Text("Goal title") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = target, onValueChange = { target = it.filter { c -> c.isDigit() }.take(9) },
                    label = { Text("Target Stars") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (targetLong != null && targetLong > 0) onSave(title.trim(), targetLong) },
                enabled = targetLong != null && targetLong > 0,
                colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed)
            ) { Text("Save Goal") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** 3D-look yellow star burst for the total-earnings card. */
@Composable
fun StarBurst3D(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        val cx = s / 2f; val cy = s / 2f
        // Soft halo
        drawCircle(SYellow.copy(alpha = 0.12f), s * 0.48f, Offset(cx, cy))
        // Sparkles
        drawCircle(SYellow.copy(alpha = 0.6f), s * 0.03f, Offset(s * 0.12f, s * 0.22f))
        drawCircle(SYellow.copy(alpha = 0.6f), s * 0.02f, Offset(s * 0.88f, s * 0.30f))
        drawCircle(SYellow.copy(alpha = 0.5f), s * 0.025f, Offset(s * 0.80f, s * 0.85f))
        // Star with vertical gradient + shadow lip for depth
        val star = Path()
        for (i in 0 until 10) {
            val a = PI * i / 5 - PI / 2
            val r = if (i % 2 == 0) s * 0.34f else s * 0.145f
            val x = (cx + r * cos(a)).toFloat(); val y = (cy + r * sin(a)).toFloat()
            if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
        }
        star.close()
        drawPath(star, Color(0xFFD19300).copy(alpha = 0.35f))
        drawPath(star, Brush.verticalGradient(listOf(Color(0xFFFFD54F), SYellow)))
    }
}

private fun formatUsd(cents: Long): String = "$%,.2f".format(cents / 100.0)
