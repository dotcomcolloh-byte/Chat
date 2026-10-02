package com.telefam.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.telefam.ui.theme.TelefamColors

@Composable
fun ChatEmptyStateIllustration(modifier: Modifier = Modifier) {
    Box(modifier.size(220.dp)) {

        // Spark lines radiating around the front bubble, matching the reference.
        Canvas(Modifier.matchParentSize()) {
            val strokeWidth = size.minDimension * 0.02f
            val sparks = listOf(
                Offset(0.14f, 0.34f) to Offset(0.02f, 0.22f),
                Offset(0.20f, 0.20f) to Offset(0.12f, 0.06f),
                Offset(0.66f, 0.10f) to Offset(0.60f, -0.02f),
                Offset(0.80f, 0.20f) to Offset(0.92f, 0.10f)
            )
            sparks.forEach { (from, to) ->
                drawLine(
                    color = TelefamColors.PrimaryRed,
                    start = Offset(from.x * size.width, from.y * size.height),
                    end = Offset(to.x * size.width, to.y * size.height),
                    strokeWidth = strokeWidth, cap = StrokeCap.Round
                )
            }
        }

        // Back (lighter) bubble, offset to the right — the pale bubble behind, per the reference.
        Box(
            Modifier.size(130.dp).align(Alignment.CenterEnd)
                .clip(ChatBubbleShape(tailX = 0.7f))
                .background(TelefamColors.PrimaryRed.copy(alpha = 0.22f))
        )

        // Front (solid red) bubble with the three-dot ellipsis — matches the reference exactly.
        Box(
            Modifier.size(150.dp).align(Alignment.CenterStart)
                .clip(ChatBubbleShape(tailX = 0.28f))
                .background(TelefamColors.PrimaryRed),
            contentAlignment = Alignment.Center
        ) {
            Row {
                repeat(3) { index ->
                    if (index > 0) Spacer(Modifier.size(8.dp))
                    Box(Modifier.size(14.dp).clip(CircleShape).background(TelefamColors.White))
                }
            }
        }
    }
}
