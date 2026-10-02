package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.data.model.Countries
import com.telefam.data.model.Country
import com.telefam.shared.generated.resources.*
import com.telefam.ui.components.*
import com.telefam.ui.theme.TelefamColors
import org.jetbrains.compose.resources.stringResource

data class ProfileSetupState(
    val fullName: String = "",
    val username: String = "",
    val country: Country = Countries.all.first { it.iso2 == "KE" },
    val phoneNumber: String = "",
    val gender: String? = null,
    val dateOfBirth: String? = null, // ISO yyyy-MM-dd, set by native date picker
    val bio: String = ""
)

private val genderOptionKeys = listOf(
    "male" to Res.string.gender_male,
    "female" to Res.string.gender_female,
    "non_binary" to Res.string.gender_non_binary,
    "prefer_not_to_say" to Res.string.gender_prefer_not_to_say
)

@Composable
fun ProfileSetupScreen(
    state: ProfileSetupState,
    onStateChange: (ProfileSetupState) -> Unit,
    profileImage: ImageBitmap?,
    onPickPhotoClick: () -> Unit,
    onBackClick: () -> Unit,
    onContinueClick: () -> Unit,
    onOpenDatePicker: () -> Unit, // native date picker; caller sets state.dateOfBirth on result
    isLoading: Boolean = false,
    errorMessage: String? = null
) {
    Column(Modifier.fillMaxSize().background(TelefamColors.White)) {

        Box(
            Modifier.fillMaxWidth().background(TelefamColors.PrimaryRed)
                .padding(top = 48.dp, bottom = 20.dp)
        ) {
            IconButton(onClick = onBackClick, modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp)) {
                Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(Res.string.back), tint = TelefamColors.White)
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(Res.string.profile_setup_title), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TelefamColors.White)
                Text(stringResource(Res.string.profile_setup_subtitle), color = TelefamColors.White.copy(alpha = 0.9f))
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(12.dp))
            Box(contentAlignment = Alignment.BottomEnd) {
                Box(
                    Modifier.size(110.dp).clip(CircleShape)
                        .background(TelefamColors.BackgroundWash)
                        .border(2.dp, TelefamColors.PrimaryRed, CircleShape)
                        .clickable(onClick = onPickPhotoClick),
                    contentAlignment = Alignment.Center
                ) {
                    if (profileImage != null) {
                        androidx.compose.foundation.Image(
                            bitmap = profileImage, contentDescription = stringResource(Res.string.upload_profile_photo),
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape)
                        )
                    } else {
                        Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = TelefamColors.PrimaryRed, modifier = Modifier.size(36.dp))
                    }
                }
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(TelefamColors.PrimaryRed)
                        .clickable(onClick = onPickPhotoClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.upload_profile_photo), tint = TelefamColors.White, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(Res.string.upload_profile_photo), fontWeight = FontWeight.Bold, color = TelefamColors.TextDark)
            Text(
                stringResource(Res.string.upload_profile_photo_desc),
                color = TelefamColors.TextMuted, textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))
            TelefamTextField(
                value = state.fullName, onValueChange = { onStateChange(state.copy(fullName = it)) },
                label = stringResource(Res.string.full_name_label), placeholder = stringResource(Res.string.full_name_placeholder), leadingIcon = Icons.Filled.Person
            )
            Spacer(Modifier.height(16.dp))
            TelefamTextField(
                value = state.username,
                onValueChange = { onStateChange(state.copy(username = it.filter { c -> c.isLetterOrDigit() || c == '_' }.lowercase())) },
                label = stringResource(Res.string.username_label), placeholder = stringResource(Res.string.username_placeholder), leadingIcon = Icons.Filled.AlternateEmail
            )

            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.phone_number_label), style = MaterialTheme.typography.labelLarge, color = TelefamColors.TextDark)
                Spacer(Modifier.height(6.dp))
                Row {
                    CountryPickerDropdown(
                        selected = state.country,
                        onSelect = { onStateChange(state.copy(country = it)) },
                        modifier = Modifier.height(56.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = state.phoneNumber,
                        onValueChange = { onStateChange(state.copy(phoneNumber = it.filter(Char::isDigit))) },
                        placeholder = { Text(stringResource(Res.string.phone_number_placeholder), color = TelefamColors.TextMuted) },
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = TelefamColors.FieldBorder,
                            focusedBorderColor = TelefamColors.PrimaryRed
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.gender_label), style = MaterialTheme.typography.labelLarge, color = TelefamColors.TextDark)
                Spacer(Modifier.height(6.dp))
                var expanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedTextField(
                        value = genderOptionKeys.firstOrNull { it.first == state.gender }?.let { stringResource(it.second) } ?: "",
                        onValueChange = {}, readOnly = true,
                        placeholder = { Text(stringResource(Res.string.gender_placeholder), color = TelefamColors.TextMuted) },
                        trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = TelefamColors.FieldBorder),
                        modifier = Modifier.fillMaxWidth().clickable { expanded = true }
                    )
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        genderOptionKeys.forEach { (value, labelRes) ->
                            DropdownMenuItem(text = { Text(stringResource(labelRes)) }, onClick = {
                                onStateChange(state.copy(gender = value)); expanded = false
                            })
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.dob_label), style = MaterialTheme.typography.labelLarge, color = TelefamColors.TextDark)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = state.dateOfBirth ?: "", onValueChange = {}, readOnly = true,
                    placeholder = { Text(stringResource(Res.string.dob_placeholder), color = TelefamColors.TextMuted) },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = TelefamColors.FieldBorder),
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenDatePicker)
                )
            }

            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.bio_label), style = MaterialTheme.typography.labelLarge, color = TelefamColors.TextDark)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = state.bio,
                    onValueChange = { if (it.length <= 150) onStateChange(state.copy(bio = it)) },
                    placeholder = { Text(stringResource(Res.string.bio_placeholder), color = TelefamColors.TextMuted) },
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = TelefamColors.FieldBorder),
                    modifier = Modifier.fillMaxWidth().height(100.dp)
                )
                Text("${state.bio.length}/150", color = TelefamColors.TextMuted,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)
            }

            if (errorMessage != null) {
                Spacer(Modifier.height(8.dp))
                Text(errorMessage, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(24.dp))
            TelefamPrimaryButton(
                text = stringResource(Res.string.continue_action),
                onClick = onContinueClick,
                loading = isLoading,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
