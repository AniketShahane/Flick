package com.flick.receiver.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text

/**
 * The Flick icon set (design-tokens.md §5): 24dp grid, 1.8px stroke, round
 * caps/joins. Icons are drawn white and tinted at the call site (via
 * `Icon(tint = …)`), so the same vector serves any accent.
 *
 * Glyphs that carry a numeral ("back-10"/"fwd-10") are composables, not vectors,
 * because the "10" is set text — see [SkipGlyph].
 */
object FlickIcons {

    private fun stroked(path: String): ImageVector =
        ImageVector.Builder(
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = PathParser().parsePathString(path).toNodes(),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()

    private fun filled(path: String): ImageVector =
        ImageVector.Builder(
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = PathParser().parsePathString(path).toNodes(),
                fill = SolidColor(Color.White),
            )
        }.build()

    val Play: ImageVector = filled("M9 6v12l10 -6z")
    val Pause: ImageVector = stroked("M9 5.5v13 M15 5.5v13")
    val Next: ImageVector = stroked("M18 5.5v13 M5.5 5.5l9 6.5 -9 6.5z")
    val Previous: ImageVector = stroked("M6 5.5v13 M18.5 5.5l-9 6.5 9 6.5z")
    val Volume: ImageVector = stroked(
        "M4.5 9.5v5H8l4.5 4v-13L8 9.5H4.5z M15.5 9a4.4 4.4 0 0 1 0 6 M18 6.7a8 8 0 0 1 0 10.6",
    )
    val Cast: ImageVector = stroked(
        "M2.5 8.5V6.4a2 2 0 0 1 2 -2h15a2 2 0 0 1 2 2v11.2a2 2 0 0 1 -2 2h-5.8 " +
            "M2.5 16.5a5 5 0 0 1 5 5 M2.5 12.5a9 9 0 0 1 9 9",
    )
    val Wifi: ImageVector = stroked(
        "M3 9.5a13 13 0 0 1 18 0 M6.2 13a8.5 8.5 0 0 1 11.6 0 M9.4 16.4a4 4 0 0 1 5.2 0",
    )
    val Private: ImageVector = stroked(
        "M6 12a1.5 1.5 0 0 1 1.5 -1.5h9A1.5 1.5 0 0 1 18 12v6a1.5 1.5 0 0 1 -1.5 1.5h-9" +
            "A1.5 1.5 0 0 1 6 18z M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5",
    )
    val Settings: ImageVector = stroked(
        "M12 8.8a3.2 3.2 0 1 0 0 6.4 3.2 3.2 0 1 0 0 -6.4 " +
            "M12 3.5v2.6 M12 17.9v2.6 M3.5 12h2.6 M17.9 12h2.6 " +
            "M6 6l1.8 1.8 M16.2 16.2L18 18 M18 6l-1.8 1.8 M7.8 16.2L6 18",
    )
}

/**
 * The brand mark (§1.1): a rounded play triangle + three motion streaks — the
 * flick resolving into playback. Streaks drop below ~24dp; the triangle survives
 * alone to 16dp.
 */
@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    tint: Color = FlickColor.Spark,
    withStreaks: Boolean = true,
) {
    Canvas(modifier = modifier.size(size)) {
        val f = this.size.minDimension / 64f
        fun p(x: Float, y: Float) = Offset(x * f, y * f)

        if (withStreaks) {
            val streaks = listOf(
                Triple(9f, 22.5f, Triple(13f, 5f, 0.85f)),
                Triple(5f, 31.5f, Triple(10f, 5f, 0.5f)),
                Triple(11f, 40.5f, Triple(7f, 5f, 0.28f)),
            )
            for ((x, y, whA) in streaks) {
                val (w, h, a) = whA
                drawRoundRect(
                    color = tint.copy(alpha = a),
                    topLeft = p(x, y),
                    size = Size(w * f, h * f),
                    cornerRadius = CornerRadius(2.5f * f, 2.5f * f),
                )
            }
        }

        val tri = Path().apply {
            moveTo(28f * f, 17f * f)
            lineTo(51f * f, 32f * f)
            lineTo(28f * f, 47f * f)
            close()
        }
        drawPath(tri, color = tint)
        drawPath(tri, color = tint, style = Stroke(width = 6f * f, join = StrokeJoin.Round))
    }
}

/**
 * back-10 / fwd-10 transport glyph: a circular arrow with the numeral "10". The
 * arc curls the opposite way for [forward].
 */
@Composable
fun SkipGlyph(
    forward: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 26.dp,
    tint: Color = FlickColor.OnSurface,
) {
    val monoStyle = remember(size) { FlickType.monoTabular(sizeSp = (size.value * 0.34f).toInt().coerceAtLeast(8)) }
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val s = this.size.minDimension
            val stroke = Stroke(width = s * 0.075f, cap = StrokeCap.Round)
            val inset = s * 0.14f
            val arcSize = Size(s - inset * 2, s - inset * 2)
            // ~270° open arc; the gap sits at the top where the arrowhead lands.
            val start = if (forward) -40f else 220f
            val sweep = if (forward) 300f else -300f
            drawArc(
                color = tint,
                startAngle = start,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = stroke,
            )
            // Arrowhead at the open end (top).
            val cx = s / 2f
            val headY = inset
            val hx = if (forward) cx + s * 0.06f else cx - s * 0.06f
            val head = Path().apply {
                moveTo(hx, headY - s * 0.09f)
                lineTo(hx + (if (forward) -s * 0.11f else s * 0.11f), headY)
                lineTo(hx, headY + s * 0.09f)
                close()
            }
            drawPath(head, color = tint)
        }
        Text(text = "10", style = monoStyle, color = tint)
    }
}
