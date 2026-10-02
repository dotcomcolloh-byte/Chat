package com.telefam.ui.components

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/** Rounded speech-bubble with a small pointed tail at the bottom, tailX in [0,1] of width. */
class ChatBubbleShape(private val tailX: Float = 0.28f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val cornerRadius = size.minDimension * 0.32f
        val tailWidth = size.width * 0.14f
        val tailHeight = size.height * 0.14f
        val bodyHeight = size.height - tailHeight

        val path = Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    left = 0f, top = 0f, right = size.width, bottom = bodyHeight,
                    radiusX = cornerRadius, radiusY = cornerRadius
                )
            )
            moveTo(size.width * tailX, bodyHeight - 2f)
            lineTo(size.width * tailX - tailWidth / 2, size.height)
            lineTo(size.width * tailX + tailWidth / 2, bodyHeight - 2f)
            close()
        }
        return Outline.Generic(path)
    }
}
