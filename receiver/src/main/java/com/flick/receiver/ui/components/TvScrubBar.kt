package com.flick.receiver.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.flick.receiver.R
import com.flick.receiver.ui.theme.FlickColor

/**
 * The TV cinematic scrub bar (design-tokens.md §7 + §8, Part 4). One session
 * clock drawn with the target/confirmed contract:
 *  - 5dp track;
 *  - **buffered range** in translucent white ([bufferedMs]);
 *  - **played** = Spark gradient, filled to the target;
 *  - **target ●** = solid white dot (with a Link glow while [seeking]);
 *  - **confirmed ○** = a hollow white ghost ring, drawn only while [seeking]
 *    (trailing the target — "sync is invisible when healthy").
 *
 * When not seeking, [targetMs] == [confirmedMs] and only the solid dot shows.
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

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .semantics {
                contentDescription = accessibilityLabel
                if (lagging) stateDescription = syncingLabel
            },
    ) {
        val cy = size.height / 2f
        val barH = 5.dp.toPx()
        val r = barH / 2f
        fun px(f: Float) = (size.width * f).coerceIn(0f, size.width)

        // Track
        drawRoundRect(
            color = FlickColor.TrackBase,
            topLeft = Offset(0f, cy - r),
            size = Size(size.width, barH),
            cornerRadius = CornerRadius(r, r),
        )
        // Buffered range
        if (bufFrac > 0f) {
            drawRoundRect(
                color = FlickColor.TrackBuffered,
                topLeft = Offset(0f, cy - r),
                size = Size(px(bufFrac), barH),
                cornerRadius = CornerRadius(r, r),
            )
        }
        // Played fill to target (Spark gradient)
        val fillW = px(targetFrac)
        if (fillW > 0f) {
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(FlickColor.SparkLight, FlickColor.Spark),
                    startX = 0f,
                    endX = fillW.coerceAtLeast(1f),
                ),
                topLeft = Offset(0f, cy - r),
                size = Size(fillW, barH),
                cornerRadius = CornerRadius(r, r),
            )
        }
        // Cyan only describes the live sync gap; it is never a second playhead.
        if (lagging) {
            drawLine(
                color = FlickColor.Link.copy(alpha = 0.8f),
                start = Offset(px(confirmedFrac), cy),
                end = Offset(px(targetFrac), cy),
                strokeWidth = 1.dp.toPx(),
            )
            // Ghost ○ (confirmed) — pale, hollow, and distinct from the coral target.
            drawCircle(
                color = FlickColor.OnSurface.copy(alpha = 0.82f),
                radius = 6.dp.toPx(),
                center = Offset(px(confirmedFrac), cy),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        // Target ● is always solid coral. It only receives a cyan halo while the
        // network-confirmed position trails, preserving the target/confirmed split.
        val sx = px(targetFrac)
        if (lagging) {
            drawCircle(FlickColor.FocusGlow, radius = 12.dp.toPx(), center = Offset(sx, cy))
        }
        drawCircle(FlickColor.Spark, radius = 7.dp.toPx(), center = Offset(sx, cy))
        drawCircle(
            color = Color.White.copy(alpha = 0.88f),
            radius = 7.dp.toPx(),
            center = Offset(sx, cy),
            style = Stroke(width = 1.dp.toPx()),
        )
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
