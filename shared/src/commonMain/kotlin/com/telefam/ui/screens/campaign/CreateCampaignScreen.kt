@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.telefam.ui.screens.campaign

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.telefam.campaigns.CampaignViewModel
import com.telefam.data.api.ApiConfig
import com.telefam.data.api.CampaignTierDto
import com.telefam.data.model.Countries
import com.telefam.posts.FeedPostDto
import com.telefam.ui.screens.CreatorLoadError
import com.telefam.ui.theme.TelefamColors

private fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}

/** The production flow: Goal → Content → Audience → Budget → Review → Pay. */
private val STEP_TITLES = listOf("Goal", "Content", "Audience", "Budget", "Review")

/** Interest tags an advertiser can target (must mirror the server catalog). */
private val TARGETABLE_INTERESTS = listOf(
    "MUSIC", "COMEDY", "DANCE", "SPORTS", "GAMING", "FOOD", "TRAVEL", "FASHION",
    "BEAUTY", "FITNESS", "TECH", "EDUCATION", "BUSINESS", "ART", "NEWS", "LIFESTYLE"
)

/**
 * Create Campaign — a five-step wizard:
 *   1. Goal      — six objective cards (Profile Boost, Get Sales, Series, Engagement, Views, Followers)
 *   2. Content   — pick the video to promote (See All expands the full grid)
 *   3. Audience  — Automatic, or custom: countries / age range / gender / interests
 *   4. Budget    — server-priced reach tiers + duration stepper
 *   5. Review    — full summary with balance-after-campaign, then Start Campaign
 *
 * All prices and reach tiers come from the server (never hardcoded); boosting
 * requires connectivity and a sufficient star balance — a short balance routes
 * to the real Buy Stars flow.
 */
@Composable
fun CreateCampaignScreen(
    viewModel: CampaignViewModel,
    userId: String,
    onBack: () -> Unit,
    onBuyStars: () -> Unit,
    onOpenSeries: () -> Unit,
    onBoosted: () -> Unit = {}
) {
    val state by viewModel.campaign.collectAsState()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var showAllVideos by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(userId) { viewModel.loadCampaign(userId) }

    // Success: brief confirmation, then route to the campaign dashboard.
    if (state.boosted != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearBoostResult(); onBoosted() },
            title = { Text("Campaign submitted", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Your ${goalOf(state.selectedType).title} campaign is live for ${state.boosted!!.days} day(s). " +
                        "It ends automatically when the time is up or the estimated unique reach is met. " +
                        "Track it anytime in My Campaigns."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clearBoostResult(); onBoosted() }) {
                    Text("View My Campaigns", color = TelefamColors.PrimaryRed, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // Insufficient balance: route to the real purchase flow.
    if (state.insufficient != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearBoostResult() },
            title = { Text("Not enough stars", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This campaign needs ${state.insufficient!!.second} stars and your balance is " +
                        "${state.insufficient!!.first}. Buy stars to continue."
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.clearBoostResult(); onBuyStars() },
                    colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed)
                ) { Text("Buy Stars", color = Color.White, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { viewModel.clearBoostResult() }) { Text("Cancel") } }
        )
    }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // ---- Red header: back + title + balance pill ----
            Box(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(TelefamColors.PrimaryRed, TelefamColors.PrimaryRedDark)))
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { if (step > 0) step-- else onBack() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Text(
                        "Create Campaign", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
                    )
                    BalancePill(
                        balance = state.config?.balanceStars,
                        onClick = onBuyStars,
                        modifier = Modifier.padding(end = 10.dp)
                    )
                }
            }


            when {
                state.loading -> Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TelefamColors.PrimaryRed)
                }
                state.error -> CreatorLoadError(onRetry = { viewModel.loadCampaign(userId) })
                else -> {
                    StepIndicator(current = step)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        when (step) {
                            0 -> GoalStep(
                                viewModel = viewModel,
                                state = state,
                                onSelect = { goal ->
                                    if (goal == CampaignGoal.SERIES_VIDEOS) onOpenSeries() else viewModel.selectType(goal.apiType)
                                }
                            )
                            1 -> ContentStep(
                                state = state,
                                showAll = showAllVideos,
                                onToggleShowAll = { showAllVideos = !showAllVideos },
                                onSelect = viewModel::selectPost
                            )
                            2 -> AudienceStep(viewModel, state)
                            3 -> BudgetStep(state, viewModel)
                            4 -> ReviewStep(state, viewModel)
                        }
                        state.boostError?.let {
                            Text(
                                "Couldn't start the campaign. Check your connection and try again.",
                                color = TelefamColors.PrimaryRed, fontSize = 13.sp, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    // ---- Bottom navigation ----
                    val canContinue = when (step) {
                        0 -> state.selectedType != "GET_SALES" || state.linkUrl.isNotBlank()
                        1 -> state.selectedPostId != null
                        3 -> state.selectedTierId != null
                        else -> true
                    }
                    Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
                        Row(
                            Modifier.fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.navigationBars)
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            if (step > 0) {
                                OutlinedButton(
                                    onClick = { step-- },
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.height(56.dp)
                                ) { Text("Back", fontWeight = FontWeight.Bold) }
                                Spacer(Modifier.width(12.dp))
                            }
                            Button(
                                onClick = { if (step < STEP_TITLES.lastIndex) step++ else viewModel.boost() },
                                enabled = canContinue && !state.boosting && !state.offline,
                                colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.weight(1f).height(56.dp)
                            ) {
                                if (step == STEP_TITLES.lastIndex && state.boosting) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(22.dp))
                                } else {
                                    if (step == STEP_TITLES.lastIndex) {
                                        BoltGlyph()
                                        Spacer(Modifier.width(10.dp))
                                    }
                                    val total = viewModel.selectedTotalStars()
                                    Text(
                                        when (step) {
                                            STEP_TITLES.lastIndex ->
                                                if (total > 0) "Start Campaign · ${formatCount(total)} stars" else "Start Campaign"
                                            else -> "Continue"
                                        },
                                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun goalOf(apiType: String): CampaignGoal =
    CampaignGoal.entries.firstOrNull { it.apiType == apiType } ?: CampaignGoal.PROFILE_BOOST

// ---------------------------------------------------------------- step indicator

@Composable
private fun StepIndicator(current: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        STEP_TITLES.forEachIndexed { index, title ->
            val active = index == current
            val done = index < current
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape)
                        .background(
                            when {
                                active -> TelefamColors.PrimaryRed
                                done -> TelefamColors.PrimaryRed.copy(alpha = 0.15f)
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (done) {
                        Icon(
                            Icons.Filled.Check, contentDescription = null,
                            tint = if (active) Color.White else TelefamColors.PrimaryRed,
                            modifier = Modifier.size(14.dp)
                        )
                    } else {
                        Text(
                            "${index + 1}",
                            color = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    title, fontSize = 9.sp,
                    color = if (active) TelefamColors.PrimaryRed else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                )
            }
            if (index < STEP_TITLES.lastIndex) {
                Box(
                    Modifier.weight(1f).height(2.dp).padding(horizontal = 4.dp)
                        .background(
                            if (index < current) TelefamColors.PrimaryRed
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                )
            }
        }
    }
}

// ---------------------------------------------------------------- header pieces

@Composable
fun BalancePill(balance: Long?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        color = Color.White.copy(alpha = 0.15f),
        shape = RoundedCornerShape(50),
        modifier = modifier.heightIn(min = 44.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WalletGlyph()
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Balance", color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Star3D(Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        balance?.let { formatCount(it) } ?: "—",
                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- step 1: goal

@Composable
private fun GoalStep(
    viewModel: CampaignViewModel,
    state: CampaignViewModel.CampaignUiState,
    onSelect: (CampaignGoal) -> Unit
) {
    Column {
        Text(
            "What do you want to achieve?",
            fontWeight = FontWeight.Bold, fontSize = 18.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
        GoalGrid(selected = goalOf(state.selectedType), onSelect = onSelect)
        if (state.selectedType == "PROFILE_BOOST") {
            InfoNote(
                "Profile Boost promotes one of your videos as the creative — viewers tap through to your " +
                    "profile, where they can follow you. The video is what people see; your profile is the destination."
            )
        }
        if (state.selectedType == "GET_SALES") {
            SalesLinkSection(
                linkUrl = state.linkUrl,
                linkAction = state.linkAction,
                onLinkChange = viewModel::setLinkUrl,
                onActionChange = viewModel::setLinkAction
            )
        }
    }
}

@Composable
private fun InfoNote(text: String) {
    Surface(
        color = TelefamColors.PrimaryRed.copy(alpha = 0.06f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Outlined.Info, contentDescription = null,
                tint = TelefamColors.PrimaryRed, modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun GoalGrid(selected: CampaignGoal, onSelect: (CampaignGoal) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        CampaignGoal.entries.toList().chunked(3).forEach { rowGoals ->
            Row(Modifier.fillMaxWidth()) {
                rowGoals.forEach { goal ->
                    GoalCard(
                        goal = goal,
                        selected = goal == selected,
                        onClick = { onSelect(goal) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(3 - rowGoals.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun GoalCard(goal: CampaignGoal, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.padding(horizontal = 4.dp)) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(16.dp),
            color = if (selected) TelefamColors.PrimaryRed.copy(alpha = 0.06f) else MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(
                if (selected) 1.5.dp else 1.dp,
                if (selected) TelefamColors.PrimaryRed else MaterialTheme.colorScheme.outlineVariant
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
        ) {
            Column(
                Modifier.padding(horizontal = 8.dp, vertical = 14.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Goal3DIcon(goal)
                Spacer(Modifier.height(8.dp))
                Text(
                    goal.title, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    goal.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp,
                    lineHeight = 13.sp, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (selected) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp)
                    .clip(CircleShape).background(TelefamColors.PrimaryRed),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp)) }
        }
    }
}

// ---------------------------------------------------------------- Get Sales extras

@Composable
private fun SalesLinkSection(
    linkUrl: String,
    linkAction: String,
    onLinkChange: (String) -> Unit,
    onActionChange: (String) -> Unit
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Surface(
            color = TelefamColors.PrimaryRed.copy(alpha = 0.06f),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Goal3DIcon(CampaignGoal.GET_SALES, Modifier.size(44.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Drive real people to your business", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        "Get more visits, sales, downloads and customer actions on your link.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("Add Your Link", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = linkUrl,
            onValueChange = onLinkChange,
            placeholder = { Text("https://yourwebsite.com/product") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(14.dp))
        Text("Choose the action for your link", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        val actions = listOf(
            "VISIT" to ("Visit" to "Get more website visits"),
            "DOWNLOAD" to ("Download" to "Get more app downloads"),
            "BUY" to ("Buy" to "Drive product purchases"),
            "GET_IN_TOUCH" to ("Get in Touch" to "Get more messages/leads"),
            "SIGN_UP" to ("Sign Up" to "Increase sign ups"),
            "WATCH" to ("Watch" to "Drive traffic to your video or page")
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            actions.forEach { (key, labelSub) ->
                val selected = linkAction == key
                Surface(
                    onClick = { onActionChange(key) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) TelefamColors.PrimaryRed.copy(alpha = 0.06f) else MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        if (selected) 1.5.dp else 1.dp,
                        if (selected) TelefamColors.PrimaryRed else MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier.padding(end = 8.dp).width(104.dp).heightIn(min = 44.dp)
                ) {
                    Column(
                        Modifier.padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(labelSub.first, fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.Center)
                        Text(
                            labelSub.second, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp, lineHeight = 12.sp, textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- step 2: content

@Composable
private fun ContentStep(
    state: CampaignViewModel.CampaignUiState,
    showAll: Boolean,
    onToggleShowAll: () -> Unit,
    onSelect: (String) -> Unit
) {
    Column(Modifier.padding(vertical = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Select Your Video to Boost", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            // Wired: toggles between the horizontal preview row and the full grid.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggleShowAll).padding(6.dp)
            ) {
                Text(
                    if (showAll) "Show Less" else "See All",
                    color = TelefamColors.PrimaryRed, fontWeight = FontWeight.SemiBold, fontSize = 13.sp
                )
                Icon(
                    if (showAll) Icons.AutoMirrored.Filled.KeyboardArrowLeft else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null, tint = TelefamColors.PrimaryRed, modifier = Modifier.size(16.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        when {
            state.videosLoading -> Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TelefamColors.PrimaryRed)
            }
            state.videos.isEmpty() -> Text(
                "Publish a video first — your videos appear here and can be boosted.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            showAll -> {
                // Full "all videos" grid, three thumbs per row.
                Column(Modifier.padding(horizontal = 16.dp)) {
                    state.videos.chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth()) {
                            row.forEach { video ->
                                Box(Modifier.weight(1f).padding(end = 10.dp, bottom = 10.dp)) {
                                    VideoThumb(
                                        video = video,
                                        selected = video.postId == state.selectedPostId,
                                        onClick = { onSelect(video.postId) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            else -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
                items(state.videos, key = { it.postId }) { video ->
                    VideoThumb(
                        video = video,
                        selected = video.postId == state.selectedPostId,
                        onClick = { onSelect(video.postId) }
                    )
                    Spacer(Modifier.width(10.dp))
                }
            }
        }
    }
}

@Composable
private fun VideoThumb(
    video: FeedPostDto,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier.then(Modifier.size(width = 96.dp, height = 128.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = video.thumbnailAbsoluteUrl(ApiConfig.baseUrl),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // Selection circle — red filled check when selected, hollow otherwise.
        Box(
            Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp)
                .clip(CircleShape)
                .background(if (selected) TelefamColors.PrimaryRed else Color.Black.copy(alpha = 0.35f))
                .border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
        }
        Row(
            Modifier.align(Alignment.BottomStart).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            Text(formatCount(video.viewCount), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ---------------------------------------------------------------- step 3: audience

@Composable
private fun AudienceStep(
    viewModel: CampaignViewModel,
    state: CampaignViewModel.CampaignUiState
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("Who should see this campaign?", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "Targeting is enforced on the server — only viewers who match will ever be shown your ad.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp
        )
        Spacer(Modifier.height(12.dp))

        // ---- Mode: Automatic vs Custom ----
        Row(Modifier.fillMaxWidth()) {
            AudienceModeCard(
                title = "Automatic",
                subtitle = "Telefam picks the most relevant viewers for your goal.",
                selected = state.audienceMode == "AUTO",
                onClick = { viewModel.setAudienceMode("AUTO") },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            AudienceModeCard(
                title = "Custom",
                subtitle = "Choose countries, age, gender and interests yourself.",
                selected = state.audienceMode == "CUSTOM",
                onClick = { viewModel.setAudienceMode("CUSTOM") },
                modifier = Modifier.weight(1f)
            )
        }

        if (state.audienceMode == "CUSTOM") {
            Spacer(Modifier.height(18.dp))
            CountryPickerSection(
                selected = state.audienceCountries,
                onToggle = viewModel::toggleAudienceCountry
            )

            Spacer(Modifier.height(18.dp))
            AgeRangeSection(
                enabled = state.audienceAgeEnabled,
                min = state.audienceAgeMin,
                max = state.audienceAgeMax,
                onEnabledChange = viewModel::setAudienceAgeEnabled,
                onRangeChange = viewModel::setAudienceAgeRange
            )

            Spacer(Modifier.height(18.dp))
            GenderSection(selected = state.audienceGender, onSelect = viewModel::setAudienceGender)

            Spacer(Modifier.height(18.dp))
            InterestSection(
                selected = state.audienceInterests,
                onToggle = viewModel::toggleAudienceInterest
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AudienceModeCard(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) TelefamColors.PrimaryRed.copy(alpha = 0.06f) else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) TelefamColors.PrimaryRed else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier.heightIn(min = 44.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(20.dp).clip(CircleShape)
                        .background(if (selected) TelefamColors.PrimaryRed else MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) { if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp)) }
            }
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun CountryPickerSection(selected: Set<String>, onToggle: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Countries", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(
                if (selected.isEmpty()) "Worldwide" else "${selected.size} selected",
                color = TelefamColors.PrimaryRed, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
            )
        }
        Text(
            "Leave empty to target every country.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; expanded = true },
            placeholder = { Text("Search ${Countries.all.size} countries…") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        )
        // Selected chips
        if (selected.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                selected.forEach { iso ->
                    val country = Countries.all.firstOrNull { it.iso2 == iso }
                    FilterChip(
                        selected = true,
                        onClick = { onToggle(iso) },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(country?.name ?: iso, fontSize = 12.sp)
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Filled.Close, contentDescription = "Remove", modifier = Modifier.size(14.dp))
                            }
                        },
                        modifier = Modifier.padding(end = 8.dp).heightIn(min = 44.dp)
                    )
                }
            }
        }
        // Search results / popular list
        val matches = remember(query, expanded) {
            if (!expanded) emptyList()
            else Countries.all.filter {
                query.isBlank() || it.name.contains(query.trim(), true) || it.iso2.equals(query.trim(), true)
            }.take(8)
        }
        matches.forEach { country ->
            val isSel = country.iso2 in selected
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onToggle(country.iso2); query = "" }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(country.name, modifier = Modifier.weight(1f), fontSize = 14.sp)
                Box(
                    Modifier.size(20.dp).clip(CircleShape)
                        .background(if (isSel) TelefamColors.PrimaryRed else MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) { if (isSel) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp)) }
            }
        }
    }
}

@Composable
private fun AgeRangeSection(
    enabled: Boolean,
    min: Int,
    max: Int,
    onEnabledChange: (Boolean) -> Unit,
    onRangeChange: (Int, Int) -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Age range", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    if (enabled) "$min – ${if (max >= 65) "65+" else "$max"} years" else "All ages",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp
                )
            }
            Switch(
                checked = enabled, onCheckedChange = onEnabledChange,
                colors = SwitchDefaults.colors(checkedTrackColor = TelefamColors.PrimaryRed)
            )
        }
        if (enabled) {
            RangeSlider(
                value = min.toFloat()..max.toFloat(),
                onValueChange = { r -> onRangeChange(r.start.toInt(), r.endInclusive.toInt()) },
                valueRange = 13f..65f,
                colors = SliderDefaults.colors(
                    thumbColor = TelefamColors.PrimaryRed,
                    activeTrackColor = TelefamColors.PrimaryRed
                )
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("13", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("65+", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun GenderSection(selected: String?, onSelect: (String?) -> Unit) {
    Column {
        Text("Gender", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            listOf<Pair<String?, String>>(null to "All", "MALE" to "Male", "FEMALE" to "Female").forEach { (key, label) ->
                val isSel = selected == key
                Surface(
                    onClick = { onSelect(key) },
                    shape = RoundedCornerShape(50),
                    color = if (isSel) TelefamColors.PrimaryRed else MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isSel) TelefamColors.PrimaryRed else MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier.padding(end = 8.dp).heightIn(min = 44.dp)
                ) {
                    Text(
                        label,
                        color = if (isSel) Color.White else MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun InterestSection(selected: Set<String>, onToggle: (String) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Interests", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(
                if (selected.isEmpty()) "Any" else "${selected.size}/8",
                color = TelefamColors.PrimaryRed, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
            )
        }
        Text(
            "Matched against your video's hashtags. Leave empty to reach any interest.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        TARGETABLE_INTERESTS.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { interest ->
                    val isSel = interest in selected
                    Surface(
                        onClick = { onToggle(interest) },
                        shape = RoundedCornerShape(50),
                        color = if (isSel) TelefamColors.PrimaryRed.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSel) TelefamColors.PrimaryRed else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier.weight(1f).padding(end = 6.dp, bottom = 8.dp).heightIn(min = 44.dp)
                    ) {
                        Text(
                            interest.lowercase().replaceFirstChar { it.uppercase() },
                            color = if (isSel) TelefamColors.PrimaryRed else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 11.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f).padding(end = 6.dp, bottom = 8.dp)) }
            }
        }
    }
}

// ---------------------------------------------------------------- step 4: budget

@Composable
private fun BudgetStep(state: CampaignViewModel.CampaignUiState, viewModel: CampaignViewModel) {
    Column {
        TierSection(
            goal = goalOf(state.selectedType),
            tiers = state.config?.tiers?.get(state.selectedType).orEmpty(),
            selectedTierId = state.selectedTierId,
            days = state.days,
            onSelect = viewModel::selectTier
        )
        DayStepperCard(
            days = state.days,
            maxDays = state.config?.maxDays ?: 30,
            onDaysChange = viewModel::setDays
        )
        InfoNote(
            "Estimated reach — the estimated number of people who may see your content. " +
                "Actual results may vary. Your campaign stops when the duration ends or the reach limit is reached."
        )
    }
}

// ---------------------------------------------------------------- step 5: review

@Composable
private fun ReviewStep(state: CampaignViewModel.CampaignUiState, viewModel: CampaignViewModel) {
    val goal = goalOf(state.selectedType)
    val tier = state.config?.tiers?.get(state.selectedType)?.firstOrNull { it.id == state.selectedTierId }
    val video = state.videos.firstOrNull { it.postId == state.selectedPostId }
    val total = viewModel.selectedTotalStars()
    val balance = state.config?.balanceStars ?: 0
    val after = balance - total

    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("Review your campaign", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "Check your campaign details before spending your Stars.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp
        )
        Spacer(Modifier.height(12.dp))

        // Creative preview
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (video != null) {
                    Box(
                        Modifier.size(width = 64.dp, height = 86.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        AsyncImage(
                            model = video.thumbnailAbsoluteUrl(ApiConfig.baseUrl),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                }
                Column {
                    Text(goal.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        goal.subtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 15.sp
                    )
                    if (state.selectedType == "GET_SALES" && state.linkUrl.isNotBlank()) {
                        Text(
                            state.linkUrl, color = TelefamColors.PrimaryRed, fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // Summary card
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("Campaign Summary", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                SummaryRow("Goal", goal.title)
                SummaryRow("Audience", viewModel.audienceSummary())
                SummaryRow("Estimated unique reach", tier?.reachLabel ?: "—")
                SummaryRow("Duration", "${state.days} day${if (state.days > 1) "s" else ""}")
                SummaryRow("Daily cost", "${formatCount(tier?.starsPerDay ?: 0)} stars")
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SummaryRow("Total", "${formatCount(total)} stars", emphasize = true)
                SummaryRow("Current balance", "${formatCount(balance)} stars")
                SummaryRow(
                    "Balance after campaign", "${formatCount(after)} stars",
                    valueColor = if (after < 0) TelefamColors.PrimaryRed else Color(0xFF1E8E3E),
                    emphasize = true
                )
            }
        }

        if (after < 0) {
            Spacer(Modifier.height(10.dp))
            InfoNote("Not enough Stars — purchase more Stars to continue with this campaign.")
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SummaryRow(
    label: String,
    value: String,
    emphasize: Boolean = false,
    valueColor: Color = Color.Unspecified
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.SemiBold,
            fontSize = if (emphasize) 14.sp else 13.sp,
            color = valueColor,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.4f)
        )
    }
}

// ---------------------------------------------------------------- tiers

@Composable
private fun TierSection(
    goal: CampaignGoal,
    tiers: List<CampaignTierDto>,
    selectedTierId: String?,
    days: Int,
    onSelect: (String) -> Unit
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Choose a Tier (${goal.tierLabel()})",
                fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f)
            )
            Icon(Icons.Outlined.Info, contentDescription = null, tint = TelefamColors.PrimaryRed, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                if (goal == CampaignGoal.GET_SALES) "Higher tiers = more traffic and potential sales"
                else "Higher tiers = more reach",
                color = TelefamColors.PrimaryRed, fontSize = 11.sp
            )
        }
        Text(
            "Ranges are estimated unique reach.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp
        )
        Spacer(Modifier.height(10.dp))
        tiers.chunked(3).forEach { rowTiers ->
            Row(Modifier.fillMaxWidth()) {
                rowTiers.forEach { tier ->
                    TierCard(
                        tier = tier,
                        days = days,
                        perDay = goal == CampaignGoal.GET_SALES,
                        selected = tier.id == selectedTierId,
                        onClick = { onSelect(tier.id) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(3 - rowTiers.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun TierCard(
    tier: CampaignTierDto,
    days: Int,
    perDay: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier.padding(horizontal = 4.dp)) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(16.dp),
            color = if (selected) TelefamColors.PrimaryRed.copy(alpha = 0.06f) else MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(
                if (selected) 1.5.dp else 1.dp,
                if (selected) TelefamColors.PrimaryRed else MaterialTheme.colorScheme.outlineVariant
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 14.dp)) {
                Text(tier.reachLabel, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(TelefamColors.PrimaryRed.copy(alpha = 0.10f)),
                        contentAlignment = Alignment.Center
                    ) { Star3D(Modifier.size(20.dp), spinning = selected) }
                    Spacer(Modifier.width(6.dp))
                    Text(formatCount(tier.starsPerDay * days.coerceAtLeast(1)), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Text(
                    if (perDay) "stars per day" else "stars",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp
                )
            }
        }
        if (selected) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp)
                    .clip(CircleShape).background(TelefamColors.PrimaryRed),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp)) }
        }
    }
}

// ---------------------------------------------------------------- day stepper

@Composable
private fun DayStepperCard(days: Int, maxDays: Int, onDaysChange: (Int) -> Unit) {
    Surface(
        color = TelefamColors.PrimaryRed.copy(alpha = 0.06f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Color.White),
                contentAlignment = Alignment.Center
            ) { CalendarGlyph() }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Switch days and multiply stars", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    "Choose more days to get more reach and better results.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 15.sp
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepperButton("−", enabled = days > 1) { onDaysChange(days - 1) }
                Text(
                    "$days Day${if (days > 1) "s" else ""}",
                    fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 10.dp)
                )
                StepperButton("+", enabled = days < maxDays) { onDaysChange(days + 1) }
            }
        }
    }
}

@Composable
private fun StepperButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .background(if (enabled) TelefamColors.PrimaryRed.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, color = if (enabled) TelefamColors.PrimaryRed else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 20.sp, fontWeight = FontWeight.Bold
        )
    }
}
