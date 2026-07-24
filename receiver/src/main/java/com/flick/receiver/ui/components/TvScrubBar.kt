package com.flick.receiver.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.flick.receiver.R
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickMotion
import com.flick.receiver.ui.theme.playheadBrush
import com.flick.receiver.ui.theme.rememberReducedMotion
import kotlin.math.abs

/**
 * Media-time delta that means the clock did NOT simply run: a seek landing. The
 * 10 Hz confirmed-position feed advances the bar by ~100 ms per tick, so anything
 * an order of magnitude larger is a discontinuity worth reconciling on a spring.
 */
private const val RECONCILE_JUMP_MS = 1_000L

/**
 * The TV scrub bar (receiver-expressive-spec.md §5.3 row 2). One session clock
 * drawn with the target/confirmed contract:
 *  - 8 dp pill track;
 *  - **buffered range** in translucent white ([bufferedMs]);
 *  - **played** = the amber `#FFB61E → #FFD87A` gradient, filled to the target;
 *  - **knob ●** = a 15 dp white circle inside a 3.5 dp `Spark` @ 34 % halo;
 *  - **confirmed ○** = a hollow white ghost ring, drawn only while [seeking]
 *    (trailing the target — "sync is invisible when healthy").
 *
 * When not seeking, [targetMs] == [confirmedMs] and only the knob shows.
 *
 * The drawn playhead tracks the confirmed clock **exactly**: a 10 Hz tick moves it
 * a fraction of a pixel, so animating it would only leave the amber fill
 * permanently trailing the film. Only a discontinuity — a seek landing, i.e. a
 * jump larger than [RECONCILE_JUMP_MS] of media — reconciles on
 * [FlickMotion.syncSpring], which is what "snap on release" reads as.
 */
@Composable
fun TvScrubBar(
    durationMs: Long,
    confirmedMs: Long,
    bufferedMs: Long,
    modifier: Modifier = Modifier,
    targetMs: Long = confirmedMs,
    seeking: Boolean = false,
) {
    val confirmedFrac = frac(confirmedMs, durationMs)
    val targetFrac = frac(targetMs, durationMs)
    val bufFrac = frac(bufferedMs, durationMs)
    val lagging = seeking && confirmedMs != targetMs
    val targetLabel = stringResource(R.string.scrub_target, clock(targetMs))
    val confirmedLabel = stringResource(R.string.scrub_confirmed, clock(confirmedMs))
    val syncingLabel = stringResource(R.string.syncing)
    val accessibilityLabel = if (lagging) "$targetLabel, $confirmedLabel" else confirmedLabel

    val reducedMotion = rememberReducedMotion()
    val headFrac = if (seeking) targetFrac else confirmedFrac
    val playhead = remember { Animatable(headFrac) }
    val liveFrac = rememberUpdatedState(headFrac)
    val jumpFrac = rememberUpdatedState(
        if (durationMs > 0L) RECONCILE_JUMP_MS.toFloat() / durationMs.toFloat() else Float.MAX_VALUE,
    )
    LaunchedEffect(playhead, reducedMotion) {
        // snapshotFlow conflates, so a spring that is still reconciling is never
        // cut short by the next position tick — it lands, then tracking resumes.
        snapshotFlow { liveFrac.value }.collect { f ->
            if (reducedMotion || abs(f - playhead.value) < jumpFrac.value) {
                playhead.snapTo(f)
            } else {
                playhead.animateTo(f, FlickMotion.syncSpring())
            }
        }
    }

    // Hoisted: the played fill redraws on every position tick, and a gradient
    // rebuilt inside the draw lambda would allocate on each one. The brush
    // resolves against the canvas width, so the played portion shows the left
    // part of one track-wide amber ramp.
    val playedBrush = playheadBrush()

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
            .semantics {
                contentDescription = accessibilityLabel
                if (lagging) stateDescription = syncingLabel
            },
    ) {
        val cy = size.height / 2f
        val barH = 8.dp.toPx()
        val r = barH / 2f
        val knobR = 7.5.dp.toPx()
        val haloR = knobR + 3.5.dp.toPx()
        fun px(f: Float) = (size.width * f).coerceIn(0f, size.width)

        drawRoundRect(
            color = FlickColor.TrackBase,
            topLeft = Offset(0f, cy - r),
            size = Size(size.width, barH),
            cornerRadius = CornerRadius(r, r),
        )
        if (bufFrac > 0f) {
            drawRoundRect(
                color = FlickColor.TrackBuffered,
                topLeft = Offset(0f, cy - r),
                size = Size(px(bufFrac), barH),
                cornerRadius = CornerRadius(r, r),
            )
        }
        // Read in the draw phase, not at composition, so the running clock
        // invalidates only this canvas.
        val fillW = px(playhead.value.coerceIn(0f, 1f))
        if (fillW > 0f) {
            drawRoundRect(
                brush = playedBrush,
                topLeft = Offset(0f, cy - r),
                size = Size(fillW, barH),
                cornerRadius = CornerRadius(r, r),
            )
        }

        // The gap between the confirmed position and the pending target is the
        // only thing that ever draws twice; it disappears the moment sync lands.
        if (lagging) {
            drawLine(
                color = FlickColor.SparkLight.copy(alpha = 0.8f),
                start = Offset(px(confirmedFrac), cy),
                end = Offset(px(targetFrac), cy),
                strokeWidth = 1.dp.toPx(),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.82f),
                radius = 6.dp.toPx(),
                center = Offset(px(confirmedFrac), cy),
                style = Stroke(width = 2.dp.toPx()),
            )
        }

        val sx = fillW
        drawCircle(FlickColor.FocusRingSoft, radius = haloR, center = Offset(sx, cy))
        drawCircle(Color.White, radius = knobR, center = Offset(sx, cy))
    }
}

private fun frac(ms: Long, durationMs: Long): Float =
    if (durationMs <= 0L) 0f else (ms.coerceAtLeast(0L).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

private fun clock(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / 1_000L
    val hours = seconds / 3_600L
    val minutes = (seconds % 3_600L) / 60L
    val remainder = seconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, remainder)
    else "%d:%02d".format(minutes, remainder)
}
