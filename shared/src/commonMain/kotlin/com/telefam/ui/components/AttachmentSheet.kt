package com.telefam.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.shared.generated.resources.*
import com.telefam.ui.theme.TelefamColors
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

enum class AttachmentType {
    GALLERY, CAMERA, DOCUMENT, LOCATION, CONTACT, POLL, AUDIO, CLIP, GIF, STICKER, EVENT, MORE
}

private data class AttachmentEntry(val type: AttachmentType, val icon: ImageVector, val labelRes: StringResource)

private val ENTRIES = listOf(
    AttachmentEntry(AttachmentType.GALLERY, Icons.Outlined.Image, Res.string.attach_gallery),
    AttachmentEntry(AttachmentType.CAMERA, Icons.Outlined.PhotoCamera, Res.string.attach_camera),
    AttachmentEntry(AttachmentType.DOCUMENT, Icons.Outlined.Description, Res.string.attach_document),
    AttachmentEntry(AttachmentType.LOCATION, Icons.Outlined.LocationOn, Res.string.attach_location),
    AttachmentEntry(AttachmentType.CONTACT, Icons.Outlined.Person, Res.string.attach_contact),
    AttachmentEntry(AttachmentType.POLL, Icons.Outlined.Poll, Res.string.attach_poll),
    AttachmentEntry(AttachmentType.AUDIO, Icons.Outlined.Mic, Res.string.attach_audio),
    AttachmentEntry(AttachmentType.CLIP, Icons.Outlined.MovieFilter, Res.string.attach_clip),
    AttachmentEntry(AttachmentType.GIF, Icons.Outlined.Gif, Res.string.attach_gif),
    AttachmentEntry(AttachmentType.STICKER, Icons.Outlined.EmojiEmotions, Res.string.attach_sticker),
    AttachmentEntry(AttachmentType.EVENT, Icons.Outlined.Event, Res.string.attach_event),
    AttachmentEntry(AttachmentType.MORE, Icons.Outlined.MoreHoriz, Res.string.attach_more)
)

@Composable
fun AttachmentSheet(onSelect: (AttachmentType) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
        ) {
            items(ENTRIES) { entry -> AttachmentIcon(entry.icon, stringResource(entry.labelRes)) { onSelect(entry.type) } }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            PageDot(active = true)
            Spacer(Modifier.width(6.dp))
            PageDot(active = false)
        }
    }
}

@Composable
private fun AttachmentIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 4.dp)
    ) {
        Icon(icon, contentDescription = label, tint = TelefamColors.PrimaryRed, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PageDot(active: Boolean) {
    Canvas(Modifier.size(if (active) 7.dp else 6.dp)) {
        drawCircle(color = if (active) TelefamColors.PrimaryRed else TelefamColors.TextMuted.copy(alpha = 0.4f))
    }
}
