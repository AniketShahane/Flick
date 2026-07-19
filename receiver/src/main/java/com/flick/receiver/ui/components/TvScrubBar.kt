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
import androidx.compose.ui.unit.dp
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

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp),
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
        // Ghost ○ (confirmed) — trailing ring, only while seeking
        if (seeking) {
            drawCircle(
                color = Color.White.copy(alpha = 0.55f),
                radius = 6.dp.toPx(),
                center = Offset(px(confirmedFrac), cy),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        // Target ● (solid) — with Link glow while seeking
        val sx = px(targetFrac)
        if (seeking) {
            drawCircle(FlickColor.FocusGlow, radius = 12.dp.toPx(), center = Offset(sx, cy))
            drawCircle(FlickColor.Link.copy(alpha = 0.30f), radius = 9.dp.toPx(), center = Offset(sx, cy))
        }
        drawCircle(Color.White, radius = 7.dp.toPx(), center = Offset(sx, cy))
    }
}

private fun frac(ms: Long, durationMs: Long): Float =
    if (durationMs <= 0L) 0f else (ms.coerceAtLeast(0L).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
