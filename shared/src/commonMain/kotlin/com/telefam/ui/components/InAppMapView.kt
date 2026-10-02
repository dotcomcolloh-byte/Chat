package com.telefam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.telefam.chat.LocationData
import com.telefam.chat.MapTiles
import com.telefam.ui.theme.TelefamColors

/**
 * Renders real OSM map tiles (openstreetmap.org, free and keyless) composited into a
 * 3x3 grid via Coil, with a pin over the exact center - a real map image, never an
 * external maps app launch.
 */
@Composable
fun InAppMapView(
    location: LocationData,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val rows = MapTiles.centeredGridUrls(location.latitude, location.longitude)
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier

    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFFE8ECF3)).then(clickModifier)) {
        Column(Modifier.fillMaxSize()) {
            rows.forEach { row ->
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    row.forEach { url ->
                        AsyncImage(
                            model = url, contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                }
            }
        }
        Icon(
            Icons.Filled.LocationOn, contentDescription = location.label ?: "Location",
            tint = TelefamColors.PrimaryRed,
            modifier = Modifier.align(Alignment.Center).size(36.dp)
        )
    }
}
