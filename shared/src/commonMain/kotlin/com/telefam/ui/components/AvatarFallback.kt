package com.telefam.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter

/** Neutral person placeholder used as AsyncImage's error/placeholder painter. */
@Composable
fun avatarFallback(): Painter = rememberVectorPainter(Icons.Outlined.Person)
