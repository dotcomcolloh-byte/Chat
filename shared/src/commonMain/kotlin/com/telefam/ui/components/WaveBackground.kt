package com.telefam.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.Outline

/** Top wash: a gentle S-curve at the bottom edge, matching the pink wash behind the logo in the reference. */
class TopWaveShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path().apply {
            lineTo(0f, size.height * 0.82f)
            cubicTo(size.width * 0.25f, size.height * 1.05f, size.width * 0.75f, size.height * 0.6f, size.width, size.height * 0.85f)
            lineTo(size.width, 0f)
            close()
        }
        return Outline.Generic(path)
    }
}

/** Bottom wave: matches the deep-red curved footer in the reference sign-up screen. */
class BottomWaveShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path().apply {
            moveTo(0f, size.height * 0.28f)
            cubicTo(size.width * 0.3f, size.height * -0.05f, size.width * 0.7f, size.height * 0.5f, size.width, size.height * 0.18f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        return Outline.Generic(path)
    }
}
