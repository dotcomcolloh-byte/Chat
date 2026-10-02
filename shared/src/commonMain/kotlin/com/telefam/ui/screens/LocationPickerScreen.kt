package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.telefam.chat.LocationData
import com.telefam.shared.generated.resources.*
import com.telefam.ui.components.InAppMapView
import com.telefam.ui.components.SettingsTopBar
import com.telefam.ui.components.TelefamPrimaryButton
import com.telefam.ui.theme.TelefamColors
import org.jetbrains.compose.resources.stringResource

enum class LocationDisappearOption(val seconds: Long?) { NEVER(null), HOUR_1(3600), HOURS_24(86400), WEEK_1(604800) }

@Composable
fun LocationPickerScreen(
    initialLocation: LocationData?,
    searchResults: List<LocationData>,
    onSearchQueryChange: (String) -> Unit,
    onUseCurrentLocation: () -> Unit,
    onBackClick: () -> Unit,
    onSend: (LocationData, viewOnce: Boolean, disappearAfterSeconds: Long?) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selected by remember(initialLocation) { mutableStateOf(initialLocation) }
    var viewOnce by remember { mutableStateOf(false) }
    var disappearOption by remember { mutableStateOf(LocationDisappearOption.NEVER) }
    var showingResults by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SettingsTopBar(title = stringResource(Res.string.location_pick_title), onBackClick = onBackClick)

        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; showingResults = it.isNotBlank(); onSearchQueryChange(it) },
                placeholder = { Text(stringResource(Res.string.location_search_placeholder)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TelefamColors.PrimaryRed, unfocusedBorderColor = TelefamColors.FieldBorder),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onUseCurrentLocation) {
                Icon(Icons.Filled.MyLocation, contentDescription = null, tint = TelefamColors.PrimaryRed)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.location_current), color = TelefamColors.PrimaryRed, fontWeight = FontWeight.Medium)
            }
        }

        if (showingResults && searchResults.isNotEmpty()) {
            LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp)) {
                items(searchResults) { result ->
                    Text(
                        result.label ?: "${result.latitude}, ${result.longitude}",
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).clickable {
                                selected = result; showingResults = false; query = result.label ?: ""
                            }
                    )
                    Divider()
                }
            }
        } else {
            Column(Modifier.weight(1f).padding(horizontal = 20.dp)) {
                selected?.let { loc ->
                    InAppMapView(loc, modifier = Modifier.fillMaxWidth().height(220.dp))
                    Spacer(Modifier.height(20.dp))

                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(Res.string.location_view_once), fontWeight = FontWeight.Medium)
                        Switch(checked = viewOnce, onCheckedChange = { viewOnce = it }, colors = SwitchDefaults.colors(checkedTrackColor = TelefamColors.PrimaryRed))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(Res.string.location_disappear_after), fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LocationDisappearOption.entries.forEach { option ->
                            FilterChip(
                                selected = disappearOption == option,
                                onClick = { disappearOption = option },
                                label = { Text(disappearLabel(option)) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = TelefamColors.PrimaryRed.copy(alpha = 0.15f))
                            )
                        }
                    }
                } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(Res.string.location_search_placeholder), color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                }
            }
        }

        TelefamPrimaryButton(
            text = stringResource(Res.string.location_send),
            enabled = selected != null,
            onClick = { selected?.let { onSend(it, viewOnce, disappearOption.seconds) } },
            modifier = Modifier.fillMaxWidth().padding(20.dp)
        )
    }
}

private fun disappearLabel(option: LocationDisappearOption) = when (option) {
    LocationDisappearOption.NEVER -> "Never"
    LocationDisappearOption.HOUR_1 -> "1 hour"
    LocationDisappearOption.HOURS_24 -> "24 hours"
    LocationDisappearOption.WEEK_1 -> "1 week"
}
