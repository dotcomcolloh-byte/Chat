@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.telefam.ui.screens.campaign

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.telefam.campaigns.CampaignViewModel
import com.telefam.data.api.ApiConfig
import com.telefam.data.api.SeriesDto
import com.telefam.ui.screens.CreatorLoadError
import com.telefam.ui.theme.TelefamColors

/**
 * Create Series Videos — matches the reference: red header with balance, the
 * "Create a Series" benefit card, the owner's existing series row, video
 * selection from the app's own published videos (or the real upload flow via
 * Create Post), the Series Details form with live character counts, the
 * per-day cost row, and the red create CTA.
 *
 * Series cost stars per day, debited atomically server-side; a short balance
 * routes to the real Buy Stars flow. Everything is offline-cached.
 */
@Composable
fun SeriesVideosScreen(
    viewModel: CampaignViewModel,
    userId: String,
    onBack: () -> Unit,
    onBuyStars: () -> Unit,
    onCreatePost: () -> Unit
) {
    val state by viewModel.series.collectAsState()
    var showVideoPicker by remember { mutableStateOf(false) }

    LaunchedEffect(userId) { viewModel.loadSeries(userId) }

    if (state.created != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearSeriesResult(); viewModel.loadSeries(userId) },
            title = { Text("Series created", fontWeight = FontWeight.Bold) },
            text = { Text("\"${state.created!!.name}\" is live for 24 hours. Add more stars to keep it active.") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearSeriesResult(); viewModel.loadSeries(userId) }) {
                    Text("Done", color = TelefamColors.PrimaryRed, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (state.insufficient != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearSeriesResult() },
            title = { Text("Not enough stars", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Starting a series needs ${state.insufficient!!.second} stars and your balance is " +
                        "${state.insufficient!!.first}. Buy stars to continue."
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.clearSeriesResult(); onBuyStars() },
                    colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed)
                ) { Text("Buy Stars", color = Color.White, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { viewModel.clearSeriesResult() }) { Text("Cancel") } }
        )
    }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // ---- Red header: back + title/subtitle + balance (matches reference) ----
            Box(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(TelefamColors.PrimaryRed, TelefamColors.PrimaryRedDark)))
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Create Series Videos", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Turn your content into a series and grow your audience",
                            color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    BalancePill(balance = state.balanceStars, onClick = onBuyStars, modifier = Modifier.padding(end = 10.dp))
                }
            }


            when {
                state.loading -> Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TelefamColors.PrimaryRed)
                }
                state.error -> CreatorLoadError(onRetry = { viewModel.loadSeries(userId) })
                else -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {

                    // ---- Create a Series benefit card ----
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Goal3DIcon(CampaignGoal.SERIES_VIDEOS, Modifier.size(64.dp))
                                Spacer(Modifier.width(14.dp))
                                Column {
                                    Text("Create a Series", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                    Text(
                                        "Turn your videos into a series, keep your audience coming back and earn more stars.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp
                                    )
                                }
                            }
                            Spacer(Modifier.height(14.dp))
                            Row(Modifier.fillMaxWidth()) {
                                SeriesBenefit("Build your audience", "Get more views and followers", Modifier.weight(1f))
                                SeriesBenefit("More engagement", "Keep viewers coming back", Modifier.weight(1f))
                                SeriesBenefit("Better visibility", "Featured in series recommendations", Modifier.weight(1f))
                                SeriesBenefit("Earn more", "Get stars for your content", Modifier.weight(1f))
                            }
                        }
                    }

                    // ---- Your Series ----
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Your Series", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                        Text("Manage Series", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    if (state.series.isEmpty()) {
                        Text(
                            "No series yet — create your first one below.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    } else {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
                            items(state.series, key = { it.id }) { series ->
                                SeriesCard(series)
                                Spacer(Modifier.width(10.dp))
                            }
                        }
                    }

                    // ---- Add Content ----
                    Text("Add Content to Your Series", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    Surface(
                        onClick = { showVideoPicker = true },
                        shape = RoundedCornerShape(16.dp),
                        color = TelefamColors.PrimaryRed.copy(alpha = 0.04f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, TelefamColors.PrimaryRed.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(48.dp).clip(CircleShape).background(Color.White),
                                contentAlignment = Alignment.Center
                            ) { Goal3DIcon(CampaignGoal.SERIES_VIDEOS, Modifier.size(40.dp)) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Select Videos", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text(
                                    if (state.selectedPostIds.isEmpty())
                                        "Choose videos from your gallery or upload new ones to add to your series."
                                    else "${state.selectedPostIds.size} video(s) selected",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 15.sp
                                )
                            }
                            Button(
                                onClick = { showVideoPicker = true },
                                colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.heightIn(min = 44.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Choose Videos", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Spacer(Modifier.width(4.dp))
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }

                    // ---- Series Details ----
                    Text("Series Details", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Series Name", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = state.name,
                                onValueChange = viewModel::setSeriesName,
                                placeholder = { Text("e.g. Travel Diaries, Cooking Made Easy, etc.") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                supportingText = { Text("${state.name.length}/50", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("Description (Optional)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = state.description,
                                onValueChange = viewModel::setSeriesDescription,
                                placeholder = { Text("Tell your audience what this series is about...") },
                                minLines = 3,
                                shape = RoundedCornerShape(12.dp),
                                supportingText = { Text("${state.description.length}/200", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // ---- Cost per day ----
                    Surface(
                        color = TelefamColors.PrimaryRed.copy(alpha = 0.06f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(44.dp).clip(CircleShape).background(Color.White),
                                contentAlignment = Alignment.Center
                            ) { Star3D(Modifier.size(30.dp)) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Cost per day", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(
                                    "Add stars to start your series and keep it active for 24 hours.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 15.sp
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Star3D(Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("%,d".format(state.costPerDayStars), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                }
                                Text("stars per day", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                            }
                        }
                    }

                    state.createError?.let {
                        Text(
                            "Couldn't create the series. Check your connection and try again.",
                            color = TelefamColors.PrimaryRed, fontSize = 13.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
                        )
                    }

                    // ---- CTA ----
                    val canCreate = !state.creating && !state.offline &&
                        state.name.isNotBlank() && state.selectedPostIds.isNotEmpty()
                    Button(
                        onClick = { viewModel.createSeries() },
                        enabled = canCreate,
                        colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(56.dp)
                    ) {
                        if (state.creating) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(22.dp))
                        } else {
                            Star3D(Modifier.size(22.dp), spinning = true)
                            Spacer(Modifier.width(10.dp))
                            Text("Add ${state.costPerDayStars} Stars & Create Series",
                                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }

    // ---- Video picker sheet: the owner's published videos, or upload a new one ----
    if (showVideoPicker) {
        AlertDialog(
            onDismissRequest = { showVideoPicker = false },
            title = { Text("Choose Videos", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    if (state.videos.isEmpty()) {
                        Text("You have no published videos yet. Upload one first.", fontSize = 14.sp)
                    } else {
                        LazyRow {
                            items(state.videos, key = { it.postId }) { video ->
                                val selected = video.postId in state.selectedPostIds
                                Box(
                                    Modifier.size(width = 88.dp, height = 120.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .border(
                                            if (selected) 2.dp else 0.dp,
                                            TelefamColors.PrimaryRed, RoundedCornerShape(10.dp)
                                        )
                                        .clickable { viewModel.toggleSeriesVideo(video.postId) }
                                ) {
                                    AsyncImage(
                                        model = video.thumbnailAbsoluteUrl(ApiConfig.baseUrl),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    if (selected) {
                                        Box(
                                            Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp)
                                                .clip(CircleShape).background(TelefamColors.PrimaryRed),
                                            contentAlignment = Alignment.Center
                                        ) { Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp)) }
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { showVideoPicker = false; onCreatePost() },
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, tint = TelefamColors.PrimaryRed)
                        Spacer(Modifier.width(6.dp))
                        Text("Upload a new video", color = TelefamColors.PrimaryRed)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showVideoPicker = false }) {
                    Text("Done", color = TelefamColors.PrimaryRed, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@Composable
private fun SeriesBenefit(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(TelefamColors.PrimaryRed.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) { Star3D(Modifier.size(24.dp)) }
        Spacer(Modifier.height(6.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp,
            lineHeight = 12.sp, textAlign = TextAlign.Center, maxLines = 3)
    }
}

@Composable
private fun SeriesCard(series: SeriesDto) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.width(150.dp)
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))) {
                AsyncImage(
                    model = series.coverThumbnailUrl?.let {
                        if (it.startsWith("http")) it else ApiConfig.baseUrl.trimEnd('/') + it
                    },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)
                )
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                ) {
                    Text("${series.videoCount} videos", color = Color.White, fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                }
                Icon(
                    Icons.Outlined.PlayCircle, contentDescription = null, tint = Color.White,
                    modifier = Modifier.align(Alignment.Center).size(28.dp)
                )
            }
            Column(Modifier.padding(10.dp)) {
                Text(series.name, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (series.status == "ACTIVE") "Active until ${series.activeUntil.take(10)}" else "Expired",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp
                )
                Spacer(Modifier.height(6.dp))
                Surface(color = TelefamColors.PrimaryRed, shape = RoundedCornerShape(50)) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Star3D(Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("${series.costPerDayStars}/day", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
