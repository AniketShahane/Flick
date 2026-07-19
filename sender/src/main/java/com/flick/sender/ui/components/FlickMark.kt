package com.flick.sender.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import com.flick.sender.ui.theme.Spark

/**
 * The Flick brand mark — a rounded play triangle with three motion streaks (the
 * flick resolving into playback). Streaks drop below ~24px; the triangle alone
 * survives to 16px (design §1.1). Drawn on a 64-unit grid scaled to the modifier.
 */
@Composable
fun FlickMark(
    modifier: Modifier,
    tint: Color = Spark,
    showStreaks: Boolean = true,
) {
    Canvas(modifier) {
        val s = size.minDimension / 64f
        fun off(x: Float, y: Float) = Offset(x * s, y * s)
        val r = CornerRadius(2.5f * s, 2.5f * s)

        if (showStreaks && size.minDimension >= 24f) {
            drawRoundRect(tint.copy(alpha = 0.85f), off(9f, 22.5f), Size(13f * s, 5f * s), r)
            drawRoundRect(tint.copy(alpha = 0.5f), off(5f, 31.5f), Size(10f * s, 5f * s), r)
            drawRoundRect(tint.copy(alpha = 0.28f), off(11f, 40.5f), Size(7f * s, 5f * s), r)
        }

        val tri = Path().apply {
            moveTo(28f * s, 17f * s)
            lineTo(51f * s, 32f * s)
            lineTo(28f * s, 47f * s)
            close()
        }
        // Fill + a matching round-join stroke gives the "rounded play" silhouette.
        drawPath(tri, color = tint)
        drawPath(tri, color = tint, style = Stroke(width = 6f * s, join = StrokeJoin.Round))
    }
}
