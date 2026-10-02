package com.telefam.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.outlined.MenuBook
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.connect.ProfileDetailsDto
import com.telefam.creator.CreatorViewModel
import com.telefam.data.api.EligibilityRequirementDto
import com.telefam.data.api.MonetizationStatusDto
import com.telefam.ui.theme.TelefamColors
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val MGreen = Color(0xFF2E9E4F)
private val MPurple = Color(0xFF8E44AD)
private val MOrange = Color(0xFFF29900)
private val MBlue = Color(0xFF1E88E5)
private val MRed = Color(0xFFE53935)
private val MTeal = Color(0xFF00897B)

/**
 * Monetization hub. Invite-only with manual review: qualification requirements
 * first (reference: Qualification Requirements), then after applying the status
 * screens — Under Review, Approved and Not Approved (references provided).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonetizationScreen(
    profile: ProfileDetailsDto?,
    viewModel: CreatorViewModel,
    onBack: () -> Unit,
    onGoToDashboard: () -> Unit,
    onViewTerms: () -> Unit,
    onLearnMore: () -> Unit
) {
    var showHelp by remember { mutableStateOf(false) }
    val state by viewModel.monetization.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { viewModel.loadMonetization() }

    if (showHelp) {
        CreatorHelpDialog(
            title = "About Monetization",
            text = "Monetization lets approved creators earn from gifts, subscriptions, creator rewards and sponsored content. Meet every eligibility requirement, then apply. Applications are reviewed by our team — while under review, sit tight; we'll update your status here automatically. If an application isn't approved, you'll see exactly what to work on and can apply again once you've improved.",
            onDismiss = { showHelp = false }
        )
    }

    val status = state.status?.status ?: state.eligibility?.monetizationStatus ?: "NONE"
    val title = when (status) {
        "APPROVED" -> "Monetization Approved"
        "REJECTED" -> "Monetization Not Approved"
        "UNDER_REVIEW" -> "Monetization"
        else -> "Qualification Requirements"
    }

    Scaffold { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = { viewModel.loadMonetization() },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            Column(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState())
            ) {
                CreatorTopBar(title, onBack = onBack, onHelp = { showHelp = true })

                if (state.error) {
                    CreatorLoadError(onRetry = { viewModel.loadMonetization() })
                    return@Column
                }

                when (status) {
                    "APPROVED" -> ApprovedContent(onGoToDashboard = onGoToDashboard, onViewTerms = onViewTerms)
                    "REJECTED" -> RejectedContent(
                        status = state.status,
                        onLearnMore = onLearnMore,
                        onReapply = { scope.launch { viewModel.applyForMonetization() } },
                        eligibility = state.eligibility
                    )
                    "UNDER_REVIEW" -> UnderReviewContent(state.status)
                    else -> QualificationContent(
                        profile = profile,
                        eligibility = state.eligibility,
                        applying = state.applying,
                        onApply = { scope.launch { viewModel.applyForMonetization() } }
                    )
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- qualification

@Composable
private fun QualificationContent(
    profile: ProfileDetailsDto?,
    eligibility: com.telefam.data.api.EligibilityDto?,
    applying: Boolean,
    onApply: () -> Unit
) {
    CreatorProfileHeader(profile)

    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            MedalBadge(Color(0xFF8E44AD), Modifier.size(56.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Meet the requirements below to unlock monetization features and start earning.",
                    fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 21.sp)
                Spacer(Modifier.height(4.dp))
                Text("All requirements must be met.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        }
    }

    Text("Eligibility Requirements", fontWeight = FontWeight.Bold, fontSize = 18.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))

    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            eligibility?.requirements?.forEachIndexed { i, req ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                RequirementRow(req)
            } ?: repeat(3) { RequirementPlaceholder() }
        }
    }

    Text("Additional Criteria", fontWeight = FontWeight.Bold, fontSize = 18.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))

    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            eligibility?.additionalCriteria?.forEachIndexed { i, req ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                RequirementRow(req)
            } ?: repeat(2) { RequirementPlaceholder() }
        }
    }

    Spacer(Modifier.height(14.dp))

    // Apply footer card
    val allMet = eligibility?.allMet == true
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        shape = RoundedCornerShape(20.dp),
        color = MPurple.copy(alpha = 0.08f)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(MPurple.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.CardGiftcard, contentDescription = null, tint = MPurple)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "Once all requirements are met, you can apply for monetization and start earning!",
                fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = onApply,
                enabled = allMet && !applying,
                colors = ButtonDefaults.buttonColors(
                    containerColor = TelefamColors.PrimaryRed,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (applying) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                        color = Color.White)
                } else {
                    Text("Apply Now", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun RequirementPlaceholder() {
    Row(Modifier.padding(16.dp)) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth(0.5f).height(14.dp).clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant))
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth(0.8f).height(12.dp).clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant))
        }
    }
}

@Composable
private fun RequirementRow(req: EligibilityRequirementDto) {
    val (icon, tint) = when (req.key) {
        "FOLLOWERS" -> Icons.Outlined.People to MBlue
        "WATCH_TIME" -> Icons.Outlined.PlayCircle to MGreen
        "CONTENT" -> Icons.Outlined.Movie to MOrange
        "GUIDELINES" -> Icons.Outlined.Shield to MPurple
        "ACCOUNT_STATUS" -> Icons.Outlined.VerifiedUser to MTeal
        "ORIGINAL" -> Icons.Outlined.Star to MPurple
        "ENGAGEMENT" -> Icons.Outlined.FavoriteBorder to MRed
        else -> Icons.Outlined.CheckCircle to MGreen
    }
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(tint.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(req.title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(req.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp)
            if (req.target > 0) {
                Spacer(Modifier.height(8.dp))
                val progress = (req.current.toFloat() / req.target.toFloat()).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = tint,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${formatReqCount(req.current)} / ${formatReqCount(req.target)}${if (req.key == "WATCH_TIME") " minutes" else if (req.key == "CONTENT") " videos" else ""}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        when (req.status) {
            "MET" -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Met", color = MGreen, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MGreen, modifier = Modifier.size(18.dp))
            }
            "IN_PROGRESS" -> Surface(
                color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)
            ) {
                Text("In Progress", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
            else -> Surface(color = MRed.copy(alpha = 0.10f), shape = RoundedCornerShape(8.dp)) {
                Text("Not Met", color = MRed, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
    }
}

private fun formatReqCount(v: Long): String =
    if (v >= 1000) "%,d".format(v) else "$v"

// ---------------------------------------------------------------- under review

@Composable
private fun UnderReviewContent(status: MonetizationStatusDto?) {
    Spacer(Modifier.height(16.dp))
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        shape = RoundedCornerShape(20.dp),
        color = MOrange.copy(alpha = 0.08f)
    ) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(MOrange.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.HourglassTop, contentDescription = null, tint = MOrange,
                    modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text("Application under review", color = MOrange, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "Thanks for applying! Our team is reviewing your application. Monetization is invite-only, so there's nothing else you need to do — we'll update this page as soon as a decision is made.",
                fontSize = 14.sp, lineHeight = 21.sp, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            status?.submittedAt?.let {
                Spacer(Modifier.height(10.dp))
                Text("Submitted: ${it.take(10)}", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(MBlue.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Notifications, contentDescription = null, tint = MBlue)
            }
            Spacer(Modifier.width(12.dp))
            Text("You'll be notified the moment your review is complete.",
                fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

// ---------------------------------------------------------------- approved

@Composable
private fun ApprovedContent(onGoToDashboard: () -> Unit, onViewTerms: () -> Unit) {
    // Green congratulations card with 3D seal
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        color = MGreen.copy(alpha = 0.08f)
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            SealBadge3D(MGreen, Modifier.size(84.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text("Congratulations!", color = MGreen, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Spacer(Modifier.height(2.dp))
                Text("You're all set to start earning!", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Your account has been approved for monetization. You can now access all the monetization features and start earning from your content.",
                    fontSize = 13.sp, lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("What's Approved", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("You can now use the following monetization features:",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                ApprovedFeature(Icons.Outlined.CardGiftcard, MGreen, "Gifts", Modifier.weight(1f))
                ApprovedFeature(Icons.Outlined.Star, MBlue, "Subscriptions", Modifier.weight(1f))
                ApprovedFeature(Icons.Outlined.PlayCircle, MPurple, "Creator Rewards", Modifier.weight(1f))
                ApprovedFeature(Icons.Outlined.Campaign, MOrange, "Sponsored Content", Modifier.weight(1f))
            }
        }
    }

    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(MGreen.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.AccountBalanceWallet, contentDescription = null, tint = MGreen)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Start earning now!", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("Create great content, engage your audience, and grow your earnings.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
            }
            OutlinedButton(
                onClick = onGoToDashboard,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MGreen),
                border = androidx.compose.foundation.BorderStroke(1.dp, MGreen)
            ) {
                Text("Go to Dashboard", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null,
                    modifier = Modifier.size(12.dp))
            }
        }
    }

    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Text("Monetization Benefits", fontWeight = FontWeight.Bold, fontSize = 17.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            BenefitRow(Icons.Outlined.MonetizationOn, MGreen, "Earn from your content",
                "Get paid through gifts, subscriptions, rewards and more.")
            BenefitRow(Icons.AutoMirrored.Outlined.TrendingUp, MBlue, "Grow your income",
                "The more you create and engage, the more you earn.")
            BenefitRow(Icons.Outlined.Groups, MOrange, "Build your community",
                "Monetization helps you focus on what you love—creating!")
        }
    }

    // Safe-hands footer → Terms
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        color = MPurple.copy(alpha = 0.08f)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(MPurple.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Shield, contentDescription = null, tint = MPurple)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("You're in safe hands", color = MPurple, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("We ensure a fair and secure monetization experience as per our Terms of Service.",
                    fontSize = 12.sp, lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(
                onClick = onViewTerms,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MPurple),
                border = androidx.compose.foundation.BorderStroke(1.dp, MPurple)
            ) {
                Text("View Terms", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun ApprovedFeature(icon: ImageVector, tint: Color, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(tint.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.width(3.dp))
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MGreen, modifier = Modifier.size(13.dp))
        }
        Text("Enabled", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    }
}

@Composable
private fun BenefitRow(icon: ImageVector, tint: Color, title: String, subtitle: String) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(tint.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null,
            modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- rejected

@Composable
private fun RejectedContent(
    status: MonetizationStatusDto?,
    eligibility: com.telefam.data.api.EligibilityDto?,
    onLearnMore: () -> Unit,
    onReapply: () -> Unit
) {
    val reasons = status?.rejectionReasons.orEmpty()
    fun has(code: String) = reasons.isEmpty() || reasons.contains(code)

    // Red-tinted "not approved" card with sad cloud + X badge
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        color = MRed.copy(alpha = 0.06f)
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            SadCloudBadge(Modifier.size(84.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text("Not approved this time", color = MRed, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Spacer(Modifier.height(4.dp))
                Text("Don't lose hope!", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "We reviewed your application and found that your account isn't eligible for monetization yet.",
                    fontSize = 13.sp, lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Keep creating amazing content—you're on the right path, and we believe in you!",
                    fontSize = 13.sp, lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("Why it wasn't approved", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("Your account didn't meet one or more of our monetization requirements.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            if (has("ELIGIBILITY")) ReasonRow(Icons.Outlined.Shield, "Eligibility requirements not met",
                "Your account didn't meet our minimum criteria for followers, watch time or engagement.")
            if (has("CONTENT")) ReasonRow(Icons.Outlined.Description, "Content doesn't meet our guidelines",
                "Some of your content may not fully follow our community guidelines or monetization policies.")
            if (has("HISTORY")) ReasonRow(Icons.Outlined.AccessTime, "More history needed",
                "We need more time to review your content and account activity.")
        }
    }

    // Learn more → monetization requirements & policies
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        color = MPurple.copy(alpha = 0.08f)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(MPurple.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null, tint = MPurple)
            }
            Spacer(Modifier.width(12.dp))
            Text("Learn more about our monetization requirements and policies.",
                fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = onLearnMore,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MPurple),
                border = androidx.compose.foundation.BorderStroke(1.dp, MPurple)
            ) {
                Text("Learn More", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
            }
        }
    }

    // Your progress (snapshot from the application)
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Your progress", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("Keep going! You're close to unlocking monetization.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
            ProgressRow(Icons.Outlined.PersonOutline, MGreen, "Followers", "Grow your audience",
                status?.followersCurrent ?: 0, status?.followersTarget ?: 10000, "")
            Spacer(Modifier.height(14.dp))
            ProgressRow(Icons.Outlined.PlayCircle, MPurple, "Watch time", "Last 30 days",
                status?.watchHoursCurrent ?: 0, status?.watchHoursTarget ?: 4000, " hours")
            Spacer(Modifier.height(14.dp))
            val needsWork = status?.contentCompliance == "NEEDS_IMPROVEMENT"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(MOrange.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Outlined.TrendingUp, contentDescription = null, tint = MOrange,
                        modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Content compliance", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Follow our guidelines", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                Text(
                    if (needsWork) "Needs improvement" else "Looking good",
                    color = if (needsWork) MOrange else MGreen,
                    fontWeight = FontWeight.SemiBold, fontSize = 12.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { if (needsWork) 0.4f else 1f },
                modifier = Modifier.fillMaxWidth().padding(start = 52.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = if (needsWork) MOrange else MGreen,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }

    // Encouragement footer
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFFFFF6E0)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Color(0xFFFFE9A8)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Star, contentDescription = null, tint = MOrange)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Every creator starts somewhere.", color = Color(0xFFB07A00),
                    fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("Stay consistent, keep creating, and you'll get there! We can't wait to see what you do next.",
                    fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TrophyBadge(Modifier.size(48.dp))
        }
    }

    // Re-apply once requirements are met again
    if (eligibility?.allMet == true) {
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onReapply,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
            shape = RoundedCornerShape(12.dp)
        ) { Text("Apply Again", fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun ReasonRow(icon: ImageVector, title: String, subtitle: String) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(MRed.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MRed, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null,
            modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProgressRow(
    icon: ImageVector, tint: Color, title: String, subtitle: String,
    current: Long, target: Long, unitSuffix: String
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            Text("${formatReqCount(current)} / ${formatReqCount(target)}$unitSuffix",
                color = tint, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { (current.toFloat() / target.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(start = 52.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = tint,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

// ---------------------------------------------------------------- hand-drawn badges

/** 3D-look scalloped seal with a check — matches the approved reference badge. */
@Composable
fun SealBadge3D(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        val cx = s / 2f; val cy = s / 2f
        // Bottom shadow lip
        drawCircle(color.copy(alpha = 0.35f), s * 0.34f, Offset(cx, cy + s * 0.05f))
        // Scalloped seal with radial gradient
        val seal = Path()
        for (i in 0 until 24) {
            val a = PI * i / 12 - PI / 2
            val r = if (i % 2 == 0) s * 0.40f else s * 0.33f
            val x = (cx + r * cos(a)).toFloat(); val y = (cy + r * sin(a)).toFloat()
            if (i == 0) seal.moveTo(x, y) else seal.lineTo(x, y)
        }
        seal.close()
        drawPath(seal, Brush.radialGradient(listOf(color.copy(alpha = 0.9f), color), center = Offset(cx, cy * 0.8f), radius = s * 0.5f))
        drawPath(seal, Color.White.copy(alpha = 0.35f), style = Stroke(width = s * 0.015f))
        // White inner circle + check
        drawCircle(Color.White, s * 0.24f, Offset(cx, cy))
        val check = Path().apply {
            moveTo(cx - s * 0.11f, cy); lineTo(cx - s * 0.03f, cy + s * 0.08f); lineTo(cx + s * 0.12f, cy - s * 0.09f)
        }
        drawPath(check, color, style = Stroke(width = s * 0.05f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

/** Purple medal/award badge used on the qualification card. */
@Composable
private fun MedalBadge(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        val cx = s / 2f; val cy = s * 0.40f
        // Ribbon tails
        val ribbon = Path().apply {
            moveTo(cx - s * 0.12f, cy + s * 0.16f); lineTo(cx - s * 0.20f, s * 0.95f)
            lineTo(cx, s * 0.80f); lineTo(cx + s * 0.20f, s * 0.95f); lineTo(cx + s * 0.12f, cy + s * 0.16f)
            close()
        }
        drawPath(ribbon, color.copy(alpha = 0.55f))
        drawCircle(color, s * 0.30f, Offset(cx, cy))
        drawCircle(Color.White.copy(alpha = 0.25f), s * 0.30f, Offset(cx, cy), style = Stroke(width = s * 0.02f))
        // Star cutout
        val star = Path()
        for (i in 0 until 10) {
            val a = PI * i / 5 - PI / 2
            val r = if (i % 2 == 0) s * 0.16f else s * 0.07f
            val x = (cx + r * cos(a)).toFloat(); val y = (cy + r * sin(a)).toFloat()
            if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
        }
        star.close()
        drawPath(star, Color.White)
    }
}

/** Sad cloud with a red X badge — matches the not-approved reference illustration. */
@Composable
private fun SadCloudBadge(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        val cloud = Color(0xFFE8EAF2)
        val cx = s * 0.42f; val cy = s * 0.42f
        drawCircle(cloud, s * 0.20f, Offset(cx - s * 0.14f, cy))
        drawCircle(cloud, s * 0.26f, Offset(cx + s * 0.04f, cy - s * 0.04f))
        drawCircle(cloud, s * 0.18f, Offset(cx + s * 0.20f, cy + s * 0.04f))
        drawRoundRect(cloud, Offset(cx - s * 0.26f, cy), androidx.compose.ui.geometry.Size(s * 0.58f, s * 0.18f),
            androidx.compose.ui.geometry.CornerRadius(s * 0.09f))
        // Sad face
        val face = Color(0xFF9AA0B4)
        drawArc(face, 200f, 140f, false, Offset(cx - s * 0.10f, cy - s * 0.08f),
            androidx.compose.ui.geometry.Size(s * 0.08f, s * 0.08f), style = Stroke(width = s * 0.02f))
        drawArc(face, 200f, 140f, false, Offset(cx + s * 0.08f, cy - s * 0.08f),
            androidx.compose.ui.geometry.Size(s * 0.08f, s * 0.08f), style = Stroke(width = s * 0.02f))
        drawArc(face, 20f, 140f, false, Offset(cx - s * 0.04f, cy + s * 0.10f),
            androidx.compose.ui.geometry.Size(s * 0.14f, s * 0.12f), style = Stroke(width = s * 0.02f))
        // Red X circle
        val bx = s * 0.68f; val by = s * 0.62f
        drawCircle(Color(0xFFE53935), s * 0.20f, Offset(bx, by))
        drawCircle(Color.White.copy(alpha = 0.25f), s * 0.20f, Offset(bx, by), style = Stroke(width = s * 0.015f))
        val xo = s * 0.07f
        drawLine(Color.White, Offset(bx - xo, by - xo), Offset(bx + xo, by + xo), strokeWidth = s * 0.045f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(Color.White, Offset(bx - xo, by + xo), Offset(bx + xo, by - xo), strokeWidth = s * 0.045f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/** Gold trophy for the encouragement footer. */
@Composable
private fun TrophyBadge(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        val gold = Color(0xFFF5B301)
        val goldDark = Color(0xFFD19300)
        val cx = s / 2f
        // Cup
        val cup = Path().apply {
            moveTo(cx - s * 0.18f, s * 0.14f); lineTo(cx + s * 0.18f, s * 0.14f)
            lineTo(cx + s * 0.14f, s * 0.44f); quadraticTo(cx, s * 0.56f, cx - s * 0.14f, s * 0.44f)
            close()
        }
        drawPath(cup, gold)
        // Handles
        drawArc(gold, 200f, 150f, false, Offset(cx - s * 0.30f, s * 0.14f),
            androidx.compose.ui.geometry.Size(s * 0.18f, s * 0.20f), style = Stroke(width = s * 0.035f))
        drawArc(gold, 190f, 150f, false, Offset(cx + s * 0.12f, s * 0.14f),
            androidx.compose.ui.geometry.Size(s * 0.18f, s * 0.20f), style = Stroke(width = s * 0.035f))
        // Stem + base
        drawRect(goldDark, Offset(cx - s * 0.04f, s * 0.50f), androidx.compose.ui.geometry.Size(s * 0.08f, s * 0.16f))
        drawRoundRect(goldDark, Offset(cx - s * 0.16f, s * 0.66f), androidx.compose.ui.geometry.Size(s * 0.32f, s * 0.10f),
            androidx.compose.ui.geometry.CornerRadius(s * 0.03f))
        // Star on cup
        val star = Path()
        for (i in 0 until 10) {
            val a = PI * i / 5 - PI / 2
            val r = if (i % 2 == 0) s * 0.08f else s * 0.035f
            val x = (cx + r * cos(a)).toFloat(); val y = (s * 0.28f + r * sin(a)).toFloat()
            if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
        }
        star.close()
        drawPath(star, Color.White.copy(alpha = 0.85f))
    }
}
