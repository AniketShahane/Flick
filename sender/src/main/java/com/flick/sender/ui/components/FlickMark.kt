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
import androidx.compose.ui.unit.dp
import com.flick.sender.ui.theme.LocalFlickColors

/**
 * The Flick brand mark — the action colour's play triangle leaving three speed bars in the
 * accent. Drawn on a 64-unit grid scaled to the modifier; the bars are dropped below 24 dp,
 * where they would collapse into noise, and the triangle alone carries the mark.
 *
 * The two tints read the ambient palette rather than the light set's tokens, which is why
 * they are @Composable defaults: the mark used to be hardcoded blue-on-amber and drew its
 * triangle at 2.67:1 on the dark canvas — nearly invisible, and invisible to every contrast
 * rule too, because the component took no palette to measure. It is 12.46:1 there now.
 *
 * The bars are the measured cost: 2.25:1 and 4.20:1 in dark against the amber's old 3.01:1
 * and 6.46:1. They are decorative by this component's own design — dropped entirely below
 * 24 dp — and the mark's meaning is the triangle.
 *
 * res/drawable/ic_launcher_foreground.xml and ic_launcher_monochrome.xml restate this same
 * 64-unit geometry by hand, against @color/ic_launcher_mark and @color/ic_launcher_spark —
 * a vector drawable cannot read these values, so any change here has to be mirrored in
 * res/values/colors.xml and res/values-night/colors.xml or the launcher icon drifts.
 */
@Composable
fun FlickMark(
    modifier: Modifier,
    tint: Color = LocalFlickColors.current.primary,
    streakTint: Color = LocalFlickColors.current.spark,
    showStreaks: Boolean = true,
) {
    Canvas(modifier) {
        val s = size.minDimension / 64f
        fun off(x: Float, y: Float) = Offset(x * s, y * s)
        val r = CornerRadius(2.5f * s, 2.5f * s)

        if (showStreaks && size.minDimension.toDp() >= 24.dp) {
            drawRoundRect(streakTint.copy(alpha = 0.45f), off(9f, 22.5f), Size(13f * s, 5f * s), r)
            drawRoundRect(streakTint.copy(alpha = 0.75f), off(4f, 31.5f), Size(11f * s, 5f * s), r)
            drawRoundRect(streakTint.copy(alpha = 0.45f), off(9f, 40.5f), Size(13f * s, 5f * s), r)
        }

        val tri = Path().apply {
            moveTo(28f * s, 15f * s)
            lineTo(56f * s, 32f * s)
            lineTo(28f * s, 49f * s)
            close()
        }
        // Fill plus a matching round-join stroke is how the source SVG rounds the
        // silhouette; the stroke rides half its width outside the path.
        drawPath(tri, color = tint)
        drawPath(tri, color = tint, style = Stroke(width = 9f * s, join = StrokeJoin.Round))
    }
}
