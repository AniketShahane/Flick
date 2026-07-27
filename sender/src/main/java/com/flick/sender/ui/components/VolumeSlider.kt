package com.flick.sender.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.Motion
import com.flick.sender.ui.theme.rememberFlickTouchHaptics
import com.flick.sender.ui.theme.rememberReduceMotion
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Continuous TV volume. A 13dp track with a bright fill and the amber blade thumb;
 * the trailing [percentLabel] is fixed-width so the digits never shift the track.
 * The visual band is short, but the gesture/semantics box stays a legal target.
 *
 * Track and blade swell under the finger, on the same spec and in the same direction
 * as the scrub bar's grab — one remote must not speak two slider languages.
 */
@Composable
fun VolumeSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalFlickColors.current.onSurfaceDim,
    percentLabel: String? = null,
    accessibilityLabel: String? = null,
    valueDescription: String? = null,
    adjustableActionLabel: String? = null,
) {
    val colors = LocalFlickColors.current
    val haptics = rememberFlickTouchHaptics()
    val scope = rememberCoroutineScope()
    // The swell lives in an Animatable rather than animateDpAsState so it plays
    // entirely in the draw phase; the level itself writes at pointer rate and must
    // not drag a recomposition along with it.
    val swell = remember { Animatable(0f) }
    // Hoisted because reading the motion scheme is a composition read and the gesture
    // scope is not composable. A spring, so a release that interrupts the grab
    // retargets from the velocity the swell already carries.
    val swellSpec = Motion.orSnap(
        rememberReduceMotion(),
        MaterialTheme.motionScheme.fastSpatialSpec<Float>(),
    )
    // The gesture handler is keyed on Unit, so the level it started from has to be
    // read through a state holder rather than captured at that composition.
    val currentValue = rememberUpdatedState(value)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = FlickIcons.Volume,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Box(
            Modifier
                .weight(1f)
                .height(48.dp)
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(0f, 1f), 0f..1f)
                    accessibilityLabel?.let { contentDescription = it }
                    valueDescription?.let { stateDescription = it }
                    setProgress(adjustableActionLabel) { target ->
                        onValueChange(target.coerceIn(0f, 1f))
                        true
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val w = { size.width.toFloat().coerceAtLeast(1f) }
                        // Seeded from the level before the gesture: a tap on the thumb
                        // must stay silent, one that jumps the level must tick once.
                        var step = stepOf(currentValue.value)
                        val report = { fraction: Float ->
                            val next = stepOf(fraction)
                            if (next != step) {
                                step = next
                                haptics.sliderStep()
                            }
                            onValueChange(fraction)
                        }
                        scope.launch { swell.animateTo(1f, swellSpec) }
                        try {
                            report((down.position.x / w()).coerceIn(0f, 1f))
                            down.consume()
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: event.changes.firstOrNull()
                                if (change == null) break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                report((change.position.x / w()).coerceIn(0f, 1f))
                                change.consume()
                            }
                        } finally {
                            // Pointer cancellation kills this coroutine, so the shrink
                            // is launched on the composition scope, not this one.
                            scope.launch { swell.animateTo(0f, swellSpec) }
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                // Read in the draw scope: a volume drag repaints and nothing above it.
                val t = swell.value
                val cy = size.height / 2f
                val track = lerp(TrackHeight.toPx(), TrackDragHeight.toPx(), t)
                val r = track / 2f
                drawRoundRect(
                    color = colors.fillTrack,
                    topLeft = Offset(0f, cy - r),
                    size = Size(size.width, track),
                    cornerRadius = CornerRadius(r, r),
                )
                val fillW = value.coerceIn(0f, 1f) * size.width
                if (fillW > 0f) {
                    drawRoundRect(
                        color = colors.onSurface,
                        topLeft = Offset(0f, cy - r),
                        size = Size(fillW, track),
                        cornerRadius = CornerRadius(r, r),
                    )
                }
                val bladeW = lerp(ThumbWidth.toPx(), ThumbDragWidth.toPx(), t)
                val bladeH = lerp(ThumbHeight.toPx(), ThumbDragHeight.toPx(), t)
                val bx = fillW.coerceIn(bladeW / 2f, (size.width - bladeW / 2f).coerceAtLeast(bladeW / 2f))
                drawRoundRect(
                    color = colors.spark,
                    topLeft = Offset(bx - bladeW / 2f, cy - bladeH / 2f),
                    size = Size(bladeW, bladeH),
                    // Half the blade's own width, so it stays a blade rather than
                    // squaring off as it grows.
                    cornerRadius = CornerRadius(bladeW / 2f, bladeW / 2f),
                )
            }
        }
        if (percentLabel != null) {
            Spacer(Modifier.width(14.dp))
            Text(
                text = percentLabel,
                style = FlickText.monoSmall.copy(color = tint, textAlign = TextAlign.End),
                modifier = Modifier.width(36.dp),
            )
        }
    }
}

private val TrackHeight = 13.dp
private val ThumbWidth = 6.dp
private val ThumbHeight = 26.dp

// Grabbed geometry. The blade tops out at 34dp inside a 48dp box, so the swell never
// paints past the touch target it lives in.
private val TrackDragHeight = 18.dp
private val ThumbDragWidth = 9.dp
private val ThumbDragHeight = 34.dp

/**
 * Detents the haptic ticks sit on. The level itself stays continuous — this only
 * spaces the pulses, so a slow sweep of the track ratchets instead of buzzing.
 */
private const val HapticSteps = 20

private fun stepOf(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * HapticSteps).roundToInt()
