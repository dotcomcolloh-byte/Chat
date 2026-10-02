package com.telefam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.data.model.Country
import com.telefam.data.model.Countries
import com.telefam.shared.generated.resources.Res
import com.telefam.shared.generated.resources.search_country
import com.telefam.ui.theme.TelefamColors
import org.jetbrains.compose.resources.stringResource

@Composable
fun CountryPickerDropdown(
    selected: Country,
    onSelect: (Country) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    Box(
        modifier = modifier
            .border(1.dp, TelefamColors.FieldBorder, RoundedCornerShape(12.dp))
            .background(TelefamColors.White, RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickableCompat { expanded = true }
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(selected.iso2, color = TelefamColors.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp))
            Text(selected.dialCode, color = TelefamColors.TextDark)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Choose country", tint = TelefamColors.TextMuted)
        }

        if (expanded) {
            Popup(onDismiss = { expanded = false }) {
                Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
                    Column(Modifier.width(300.dp).heightIn(max = 420.dp).padding(12.dp)) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text(stringResource(Res.string.search_country)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        val filtered = remember(query) {
                            Countries.all.filter {
                                it.name.contains(query, ignoreCase = true) || it.dialCode.contains(query)
                            }
                        }
                        LazyColumn {
                            items(filtered) { country ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickableCompat {
                                            onSelect(country)
                                            expanded = false
                                            query = ""
                                        }
                                        .padding(vertical = 10.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(country.iso2, color = TelefamColors.TextMuted, fontSize = 12.sp, modifier = Modifier.width(30.dp))
                                    Text(country.name, modifier = Modifier.weight(1f), color = TelefamColors.TextDark)
                                    Text(country.dialCode, color = TelefamColors.TextMuted)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Thin wrapper so this file has no direct platform-specific clickable import surprises across targets. */
private fun Modifier.clickableCompat(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

@Composable
private fun Popup(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    androidx.compose.ui.window.Popup(onDismissRequest = onDismiss) { content() }
}
