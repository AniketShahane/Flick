package com.flick.receiver.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
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

/**
 * The Flick icon set: 24-unit grid, 1.8-unit stroke, round caps/joins. Icons are
 * drawn white and tinted at the call site (via `Icon(tint = …)`), so the same
 * vector serves any accent.
 *
 * The 24 dp default size is the grid's coordinate space, not a render size —
 * every call site sizes its `Icon` explicitly, so the TV re-scale happens there
 * and the grid stays put. A glyph's stroke is therefore `renderedSize * 0.075`,
 * which is what puts a floor under how small these can usefully be drawn.
 *
 * [Replay10] / [Forward10] mix a stroked ring with a filled arrowhead — at
 * transport-glyph size a 1.8-unit outline cannot read as solid.
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

    /** Ring (stroked) + arrowhead (filled) + the "10" numerals (lighter stroke). */
    private fun seekTen(ring: String, head: String, digits: String): ImageVector =
        ImageVector.Builder(
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = PathParser().parsePathString(ring).toNodes(),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = PathParser().parsePathString(head).toNodes(),
                fill = SolidColor(Color.White),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1f,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = PathParser().parsePathString(digits).toNodes(),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()

    // ── Transport ───────────────────────────────────────────────────────────

    val Play: ImageVector = filled("M9 6v12l10 -6z")
    val Pause: ImageVector = stroked("M9 5.5v13 M15 5.5v13")
    val Next: ImageVector = stroked("M18 5.5v13 M5.5 5.5l9 6.5 -9 6.5z")
    val Previous: ImageVector = stroked("M6 5.5v13 M18.5 5.5l-9 6.5 9 6.5z")

    /** Counter-clockwise ring, arrowhead at the top right, "10" inside. */
    val Replay10: ImageVector = seekTen(
        ring = "M9.6 5.4a7 7 0 1 0 4.8 0",
        head = "M12 4.5L15.1 3.5L13.7 7.3Z",
        digits = "M9 11.3l1.1 -0.9v5.6 " +
            "M13.6 10.4a1.6 2.8 0 0 1 0 5.6a1.6 2.8 0 0 1 0 -5.6z",
    )

    /** Clockwise ring, arrowhead at the top left, "10" inside. */
    val Forward10: ImageVector = seekTen(
        ring = "M14.4 5.4a7 7 0 1 1 -4.8 0",
        head = "M12 4.5L8.9 3.5L10.3 7.3Z",
        digits = "M9 11.3l1.1 -0.9v5.6 " +
            "M13.6 10.4a1.6 2.8 0 0 1 0 5.6a1.6 2.8 0 0 1 0 -5.6z",
    )

    val Volume: ImageVector = stroked(
        "M4.5 9.5v5H8l4.5 4v-13L8 9.5H4.5z M15.5 9a4.4 4.4 0 0 1 0 6 M18 6.7a8 8 0 0 1 0 10.6",
    )

    /** Subtitles — the framed "cc" plate. */
    val ClosedCaption: ImageVector = stroked(
        "M4.2 6.5a2 2 0 0 1 2 -2h11.6a2 2 0 0 1 2 2v11a2 2 0 0 1 -2 2H6.2a2 2 0 0 1 -2 -2z " +
            "M10.3 10.9a2.7 2.7 0 1 0 0 4.2 M16.6 10.9a2.7 2.7 0 1 0 0 4.2",
    )

    /** Stream metrics — a framed rising trend line. */
    val Monitoring: ImageVector = stroked(
        "M4.5 4.5v13.2a1.8 1.8 0 0 0 1.8 1.8h13.2 M8.4 15.4l3.4 -4.6 3 2.4 4.2 -5.8",
    )

    // ── Chrome & status ─────────────────────────────────────────────────────

    val Close: ImageVector = stroked("M6.6 6.6L17.4 17.4 M17.4 6.6L6.6 17.4")

    /** Countdown / rotation timer — a clock with a crown stem. */
    val Timer: ImageVector = stroked(
        "M12 5.9a7.3 7.3 0 0 1 0 14.6a7.3 7.3 0 0 1 0 -14.6z " +
            "M12 9.6v3.6l2.6 1.6 M9.7 3.2h4.6 M12 3.2v2.7",
    )

    /** End session — a broken chain link. */
    val LinkOff: ImageVector = stroked(
        "M10.4 7.6H7.4a4.4 4.4 0 0 0 0 8.8h3 M13.6 7.6h3a4.4 4.4 0 0 1 0 8.8h-3 " +
            "M8.8 12h1.8 M13.4 12h1.8 M4.4 4.4L19.6 19.6",
    )

    /** Selected row marker. */
    val CheckCircle: ImageVector = stroked(
        "M12 4.4a7.6 7.6 0 0 1 0 15.2a7.6 7.6 0 0 1 0 -15.2z M8.3 12.2l2.6 2.6 4.8 -5.6",
    )

    /** Unselected row marker. */
    val RadioButtonUnchecked: ImageVector = stroked(
        "M12 4.4a7.6 7.6 0 0 1 0 15.2a7.6 7.6 0 0 1 0 -15.2z",
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
 * The brand mark: a rounded play triangle + three amber motion streaks — the
 * flick resolving into playback. Geometry is the design's, on the 64-unit grid:
 * streaks at (9, 22.5, 13×5), (4, 31.5, 11×5) and (9, 40.5, 13×5) with r = 2.5
 * and alphas 0.5 / 0.85 / 0.5; triangle `M28,15 L56,32 L28,49` filled and
 * round-join-stroked at 9 units.
 *
 * The streaks are always [FlickColor.Spark] — amber is the mark's constant. Only
 * the triangle takes [tint]: brand blue on dark chrome (the default), the deeper
 * [FlickColor.Primary] on the white QR card, near-white when the mark sits on a
 * saturated pill. Streaks drop below ~24 dp; the triangle survives alone to 16 dp.
 */
@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    size: Dp = 35.dp,
    tint: Color = FlickColor.PrimaryOnDark,
    streakTint: Color = FlickColor.Spark,
    withStreaks: Boolean = true,
) {
    Canvas(modifier = modifier.size(size)) {
        val f = this.size.minDimension / 64f
        fun p(x: Float, y: Float) = Offset(x * f, y * f)

        if (withStreaks) {
            val streaks = listOf(
                Triple(9f, 22.5f, Triple(13f, 5f, 0.5f)),
                Triple(4f, 31.5f, Triple(11f, 5f, 0.85f)),
                Triple(9f, 40.5f, Triple(13f, 5f, 0.5f)),
            )
            for ((x, y, whA) in streaks) {
                val (w, h, a) = whA
                drawRoundRect(
                    color = streakTint.copy(alpha = a),
                    topLeft = p(x, y),
                    size = Size(w * f, h * f),
                    cornerRadius = CornerRadius(2.5f * f, 2.5f * f),
                )
            }
        }

        val tri = Path().apply {
            moveTo(28f * f, 15f * f)
            lineTo(56f * f, 32f * f)
            lineTo(28f * f, 49f * f)
            close()
        }
        drawPath(tri, color = tint)
        drawPath(tri, color = tint, style = Stroke(width = 9f * f, join = StrokeJoin.Round))
    }
}
