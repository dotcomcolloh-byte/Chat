package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.telefam.data.api.ApiConfig
import com.telefam.ui.components.ProcessedImage
import com.telefam.ui.components.rememberImagePickerCropCompress
import com.telefam.ui.theme.TelefamColors

/**
 * Edit profile — photo (native picker → crop → client compress → server re-encode,
 * or remove), full name, username (server enforces one change every 7 days), bio,
 * public website link, and location. The screen owns no networking; the host
 * supplies [onSave].
 */
@Composable
fun EditProfileScreen(
    avatarUrl: String?,
    initialName: String,
    initialUsername: String,
    initialBio: String,
    initialWebsite: String = "",
    initialLocation: String = "",
    /** Days remaining before the username may change again; 0 = editable now. */
    usernameCooldownDays: Int,
    saving: Boolean,
    error: String?,
    onBack: () -> Unit,
    /** (name, username, bio, website, location, newPhoto, removeAvatar) — newPhoto null when unchanged. */
    onSave: (String, String, String, String, String, ProcessedImage?, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var name by remember { mutableStateOf(initialName) }
    var username by remember { mutableStateOf(initialUsername) }
    var bio by remember { mutableStateOf(initialBio) }
    var website by remember { mutableStateOf(initialWebsite) }
    var location by remember { mutableStateOf(initialLocation) }
    var newPhoto by remember { mutableStateOf<ProcessedImage?>(null) }
    var removePhoto by remember { mutableStateOf(false) }

    val pickImage = rememberImagePickerCropCompress(maxDimension = 1024, jpegQuality = 0.9f) { result ->
        if (result != null) { newPhoto = result; removePhoto = false }
    }

    val usernameEditable = usernameCooldownDays <= 0
    val dirty = name != initialName || username != initialUsername || bio != initialBio ||
        website != initialWebsite || location != initialLocation || newPhoto != null || removePhoto

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            Text("Edit profile", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground)
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(12.dp))

            // --- Photo: avatar circle with camera+plus badge (matches Profile Setup) ---
            Box(Modifier.size(120.dp)) {
                Box(
                    Modifier.fillMaxSize().clip(CircleShape)
                        .background(TelefamColors.PrimaryRed.copy(alpha = 0.1f))
                        .clickable(onClick = pickImage),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        newPhoto != null -> Icon(Icons.Filled.Check, contentDescription = "Photo selected",
                            tint = TelefamColors.PrimaryRed, modifier = Modifier.size(40.dp))
                        removePhoto -> Icon(Icons.Filled.CameraAlt, contentDescription = null,
                            tint = TelefamColors.PrimaryRed, modifier = Modifier.size(40.dp))
                        avatarUrl != null -> AsyncImage(
                            model = ImageRequest.Builder(LocalPlatformContext.current)
                                .data(ApiConfig.baseUrl.trimEnd('/') + avatarUrl).crossfade(true).build(),
                            contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(CircleShape)
                        )
                        else -> Icon(Icons.Filled.CameraAlt, contentDescription = null,
                            tint = TelefamColors.PrimaryRed, modifier = Modifier.size(40.dp))
                    }
                }
                Box(
                    Modifier.align(Alignment.BottomEnd).size(34.dp).clip(CircleShape)
                        .background(TelefamColors.PrimaryRed)
                        .border(2.dp, Color.White, CircleShape)
                        .clickable(onClick = pickImage),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Change photo", tint = Color.White,
                        modifier = Modifier.size(20.dp))
                }
            }
            if (newPhoto != null) {
                Spacer(Modifier.height(6.dp))
                Text("New photo selected", fontSize = 12.sp, color = TelefamColors.PrimaryRed)
            }
            // Remove photo — only offered when there is (or was) a photo and none is staged.
            if ((avatarUrl != null || removePhoto) && newPhoto == null) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { removePhoto = !removePhoto }) {
                    Text(
                        if (removePhoto) "Keep current photo" else "Remove photo",
                        fontSize = 13.sp,
                        color = if (removePhoto) MaterialTheme.colorScheme.onBackground else TelefamColors.PrimaryRed
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            EditField("Full Name", name, { name = it }, true)
            Spacer(Modifier.height(16.dp))

            EditField("Username", username, { username = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' } }, usernameEditable)
            if (!usernameEditable) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "You can change your username again in $usernameCooldownDays day${if (usernameCooldownDays == 1) "" else "s"}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = bio, onValueChange = { bio = it.take(150) },
                label = { Text("Bio") }, minLines = 3,
                supportingText = { Text("${bio.length}/150") },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TelefamColors.PrimaryRed,
                    focusedLabelColor = TelefamColors.PrimaryRed
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))

            val websiteTrimmed = website.trim()
            val websiteValid = websiteTrimmed.isEmpty() ||
                ((websiteTrimmed.startsWith("https://") || websiteTrimmed.startsWith("http://")) &&
                    websiteTrimmed.length <= 255 && websiteTrimmed.none { it.isWhitespace() })
            OutlinedTextField(
                value = website, onValueChange = { website = it.take(255) },
                label = { Text("Website") }, singleLine = true,
                placeholder = { Text("https://") },
                isError = !websiteValid,
                supportingText = { if (!websiteValid) Text("Enter a full http(s) link, or leave empty") },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TelefamColors.PrimaryRed,
                    focusedLabelColor = TelefamColors.PrimaryRed
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = location, onValueChange = { location = it.take(120) },
                label = { Text("Location") }, singleLine = true,
                placeholder = { Text("City, Country") },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TelefamColors.PrimaryRed,
                    focusedLabelColor = TelefamColors.PrimaryRed
                ),
                modifier = Modifier.fillMaxWidth()
            )

            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Text(error, color = TelefamColors.PrimaryRed, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    onSave(name.trim(), username.trim(), bio.trim(), websiteTrimmed, location.trim(), newPhoto, removePhoto)
                },
                enabled = dirty && !saving && name.isNotBlank() && username.length >= 3 && websiteValid,
                colors = ButtonDefaults.buttonColors(containerColor = TelefamColors.PrimaryRed),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            ) {
                if (saving) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                else Text("Save", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun EditField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean) {
    OutlinedTextField(
        value = value, onValueChange = onChange, enabled = enabled,
        label = { Text(label) }, singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = TelefamColors.PrimaryRed,
            focusedLabelColor = TelefamColors.PrimaryRed,
            disabledBorderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f),
            disabledLabelColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
            disabledTextColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        ),
        modifier = Modifier.fillMaxWidth()
    )
}
