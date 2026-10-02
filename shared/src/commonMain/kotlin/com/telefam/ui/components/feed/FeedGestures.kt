package com.telefam.ui.components.feed

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The gesture layer shared by every surface that shows feed videos (feeds tab,
 * search overlay, profile watch):
 *  - single tap: pause / resume
 *  - double tap: like (with the big heart burst, exactly like the reference)
 *  - long press: 2x speed while held, normal speed on release
 */
@Composable
fun FeedGestureLayer(
    onSingleTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var longPressing by remember { mutableStateOf(false) }
    Box(
        modifier.pointerInput(Unit) {
            detectTapGestures(
                onTap = { onSingleTap() },
                onDoubleTap = { onDoubleTap() },
                onLongPress = {
                    longPressing = true
                    onSpeedChange(2f)
                },
                onPress = {
                    tryAwaitRelease()
                    if (longPressing) {
                        longPressing = false
                        onSpeedChange(1f) // releasing returns to normal speed
                    }
                }
            )
        }
    ) {
        if (longPressing) {
            Text(
                "2×",
                color = Color.White,
                fontSize = 20.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .alpha(0.9f)
            )
        }
    }
}

/** Big animated heart that bursts on double-tap, then fades. */
@Composable
fun HeartBurst(trigger: Int, modifier: Modifier = Modifier) {
    if (trigger <= 0) return
    val scale = remember { Animatable(0f) }
    val alpha = remember { Animatable(1f) }
    androidx.compose.runtime.LaunchedEffect(trigger) {
        scale.snapTo(0f); alpha.snapTo(1f)
        launch {
            scale.animateTo(1.2f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
            scale.animateTo(1f)
        }
        launch { delay(450); alpha.animateTo(0f) }
    }
    Icon(
        Icons.Filled.Favorite, contentDescription = null,
        tint = Color(0xFFD32323),
        modifier = modifier.scale(scale.value).alpha(alpha.value)
    )
}

/** Centre pause glyph shown while paused (reference shows a play triangle when paused). */
@Composable
fun PausedOverlay(visible: Boolean, modifier: Modifier = Modifier) {
    if (!visible) return
    Icon(
        Icons.Filled.PlayArrow, contentDescription = null,
        tint = Color.White.copy(alpha = 0.85f),
        modifier = modifier.size(84.dp).alpha(0.85f)
    )
}
