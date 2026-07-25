package com.flick.sender.ui.components

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flick.sender.ui.theme.FlickGradients
import com.flick.sender.ui.theme.Motion
import com.flick.sender.ui.theme.PillShape
import com.flick.sender.ui.theme.rememberReduceMotion

/**
 * The traveling light (design §6) — an amber mote crossing the hairline that joins
 * this phone to the TV while a handoff is in flight. Amber is the media itself; the
 * hairline is the link. It carries no content and never reports progress.
 *
 * [modifier] must size the component: it fills the span between the two device
 * glyphs, and the light is clipped to that span.
 */
@Composable
fun TravelingLight(
    modifier: Modifier,
    trackColor: Color,
    lightWidth: Dp = 70.dp,
    lightHeight: Dp = 10.dp,
) {
    val reduceMotion = rememberReduceMotion()
    // Kept as State and read inside the layer block: a 1050 ms loop unwrapped at
    // composition scope would re-subcompose this layout on every frame of the wait.
    val phase = if (reduceMotion) {
        null
    } else {
        val transition = rememberInfiniteTransition(label = "link")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(Motion.TravelMs, easing = Motion.Travel)),
            label = "phase",
        )
    }
    BoxWithConstraints(
        modifier = modifier.clipToBounds(),
        contentAlignment = Alignment.CenterStart,
    ) {
        val span = constraints.maxWidth.toFloat()
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(trackColor),
        )
        // The light enters and leaves off the ends of the hairline, so it fades at
        // both edges rather than popping at the glyphs.
        val start = -0.30f * span
        Box(
            Modifier
                .graphicsLayer {
                    val p = phase?.value ?: 0.5f
                    translationX = start + (span - start) * p
                    alpha = edgeFade(p)
                }
                .size(width = lightWidth, height = lightHeight)
                .clip(PillShape)
                .background(FlickGradients.travelLight),
        )
    }
}

private fun edgeFade(phase: Float): Float = when {
    phase < FADE -> phase / FADE
    phase > 1f - FADE -> (1f - phase) / FADE
    else -> 1f
}

private const val FADE = 0.18f
