package com.flick.sender.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The Flick icon set — 24dp grid, round caps/joins, tracking Material Symbols
 * Rounded. Glyphs the mock shows solid are authored as fills; the rest keep the
 * 1.8–2.0 stroke. Built as [ImageVector]s so callers tint them via
 * `Icon(tint = …)`; no icon-font or drawable dependency is involved. The brand
 * mark is a Canvas composable in ui/components (it carries streaks a vector path
 * can't). back-10 / fwd-10 here are rings only; the "10" inside them is set in
 * type by the transport cluster.
 */
object FlickIcons {
    val PlayCircle: ImageVector = fillIcon("PlayCircle", evenOdd = true) {
        circle(12f, 12f, 9.6f)
        moveTo(9.8f, 7.4f); lineTo(16.8f, 12f); lineTo(9.8f, 16.6f); close()
    }

    /**
     * Replay ring: 300° of an r=8.3 circle on the grid's centre, opening into a 60° gap at
     * the upper left. The head sits at twelve o'clock pointing the way the seek runs, its
     * base straddling the arc's own end — a triangle whose incircle is narrower than the
     * stroke, so it fills solid instead of outlining. The bore left inside is the caller's
     * clearance for the "10".
     */
    val Back10: ImageVector = strokeIcon("Back10") {
        moveTo(12f, 3.7f)
        arc(8.3f, 4.81f, 7.85f, clockwise = true, major = true)
        moveTo(9.6f, 3.7f); lineTo(11.84f, 5.39f); lineTo(11.84f, 2.01f); close()
    }

    /** [Back10] mirrored about x = 12 — every point below is its twin's 24 − x. */
    val Fwd10: ImageVector = strokeIcon("Fwd10") {
        moveTo(12f, 3.7f)
        arc(8.3f, 19.19f, 7.85f, clockwise = false, major = true)
        moveTo(14.4f, 3.7f); lineTo(12.16f, 5.39f); lineTo(12.16f, 2.01f); close()
    }

    val Volume: ImageVector = strokeIcon("Volume") {
        // Speaker body.
        moveTo(4.5f, 9.5f); lineTo(4.5f, 14.5f); lineTo(8f, 14.5f)
        lineTo(12.5f, 18.5f); lineTo(12.5f, 5.5f); lineTo(8f, 9.5f); close()
        // Two sound waves.
        moveTo(15.5f, 9f); quadTo(18f, 12f, 15.5f, 15f)
        moveTo(18f, 6.7f); quadTo(22f, 12f, 18f, 17.3f)
    }

    val Cast: ImageVector = strokeIcon("Cast") {
        // Screen.
        moveTo(2.5f, 8.5f); lineTo(2.5f, 6.4f)
        quadTo(2.5f, 4.4f, 4.5f, 4.4f); lineTo(19.5f, 4.4f)
        quadTo(21.5f, 4.4f, 21.5f, 6.4f); lineTo(21.5f, 17.6f)
        quadTo(21.5f, 19.6f, 19.5f, 19.6f); lineTo(13.7f, 19.6f)
        // Broadcast arcs.
        moveTo(2.5f, 16.5f); quadTo(7.5f, 16.5f, 7.5f, 21.5f)
        moveTo(2.5f, 12.5f); quadTo(11.5f, 12.5f, 11.5f, 21.5f)
    }

    val Tv: ImageVector = fillIcon("Tv") {
        roundRect(2.4f, 4.6f, 21.6f, 17.6f, 3f)
        roundRect(8.6f, 19f, 15.4f, 20.9f, 0.95f)
    }

    val TvOff: ImageVector = strokeIcon("TvOff", width = 1.9f) {
        moveTo(5.4f, 4.9f); lineTo(18.6f, 4.9f)
        quadTo(21.3f, 4.9f, 21.3f, 7.6f); lineTo(21.3f, 14.9f)
        quadTo(21.3f, 17.6f, 18.6f, 17.6f); lineTo(5.4f, 17.6f)
        quadTo(2.7f, 17.6f, 2.7f, 14.9f); lineTo(2.7f, 7.6f)
        quadTo(2.7f, 4.9f, 5.4f, 4.9f); close()
        moveTo(9f, 20.7f); lineTo(15f, 20.7f)
        moveTo(3.6f, 3.6f); lineTo(20.4f, 20.4f)
    }

    val Wifi: ImageVector = strokeIcon("Wifi") {
        moveTo(3f, 9.5f); quadTo(12f, 3f, 21f, 9.5f)
        moveTo(6.2f, 13f); quadTo(12f, 9f, 17.8f, 13f)
        moveTo(9.4f, 16.4f); quadTo(12f, 14.6f, 14.6f, 16.4f)
        // Dot.
        moveTo(12f, 18.2f)
        curveTo(12.66f, 18.2f, 13.2f, 18.74f, 13.2f, 19.4f)
        curveTo(13.2f, 20.06f, 12.66f, 20.6f, 12f, 20.6f)
        curveTo(11.34f, 20.6f, 10.8f, 20.06f, 10.8f, 19.4f)
        curveTo(10.8f, 18.74f, 11.34f, 18.2f, 12f, 18.2f)
        close()
    }

    val Signal: ImageVector = fillIcon("Signal") {
        roundRect(4f, 14f, 7.6f, 20.2f, 1.1f)
        roundRect(10.2f, 9f, 13.8f, 20.2f, 1.1f)
        roundRect(16.4f, 3.8f, 20f, 20.2f, 1.1f)
    }

    /** Padlock — solid, matching the mock's filled lock on the privacy pill. */
    val Private: ImageVector = fillIcon("Private") {
        // Shackle stops exactly on the body's top edge so the two subpaths meet
        // without overlapping; a NonZero union of opposed windings would hollow out.
        moveTo(8f, 10.4f)
        lineTo(8f, 8.4f)
        curveTo(8f, 3.7f, 16f, 3.7f, 16f, 8.4f)
        lineTo(16f, 10.4f)
        lineTo(13.9f, 10.4f)
        lineTo(13.9f, 8.4f)
        curveTo(13.9f, 6.3f, 10.1f, 6.3f, 10.1f, 8.4f)
        lineTo(10.1f, 10.4f)
        close()
        roundRect(4.4f, 10.4f, 19.6f, 20.4f, 2.8f)
    }

    val Tune: ImageVector = fillIcon("Tune") {
        roundRect(3f, 6.1f, 21f, 7.9f, 0.9f)
        circle(8.6f, 7f, 2.7f)
        roundRect(3f, 11.1f, 21f, 12.9f, 0.9f)
        circle(15.4f, 12f, 2.7f)
        roundRect(3f, 16.1f, 21f, 17.9f, 0.9f)
        circle(8.6f, 17f, 2.7f)
    }

    val GridView: ImageVector = fillIcon("GridView") {
        roundRect(3.4f, 3.4f, 10.6f, 10.6f, 2.3f)
        roundRect(13.4f, 3.4f, 20.6f, 10.6f, 2.3f)
        roundRect(3.4f, 13.4f, 10.6f, 20.6f, 2.3f)
        roundRect(13.4f, 13.4f, 20.6f, 20.6f, 2.3f)
    }

    val Warning: ImageVector = fillIcon("Warning", evenOdd = true) {
        moveTo(10.6f, 3.5f)
        quadTo(12f, 2.7f, 13.4f, 3.5f)
        lineTo(22.2f, 18.6f)
        quadTo(23f, 20f, 21.4f, 20f)
        lineTo(2.6f, 20f)
        quadTo(1f, 20f, 1.8f, 18.6f)
        close()
        roundRect(11f, 8.4f, 13f, 14.6f, 1f)
        circle(12f, 17.1f, 1.25f)
    }

    val CheckCircle: ImageVector = fillIcon("CheckCircle", evenOdd = true) {
        circle(12f, 12f, 9.6f)
        moveTo(6.6f, 12.4f)
        lineTo(10.6f, 16.4f)
        lineTo(17.4f, 9f)
        lineTo(16f, 7.6f)
        lineTo(10.6f, 13.5f)
        lineTo(8f, 10.9f)
        close()
    }

    val Captions: ImageVector = strokeIcon("Captions", width = 1.9f) {
        moveTo(5.2f, 4.6f); lineTo(18.8f, 4.6f)
        quadTo(21.4f, 4.6f, 21.4f, 7.2f); lineTo(21.4f, 16.8f)
        quadTo(21.4f, 19.4f, 18.8f, 19.4f); lineTo(5.2f, 19.4f)
        quadTo(2.6f, 19.4f, 2.6f, 16.8f); lineTo(2.6f, 7.2f)
        quadTo(2.6f, 4.6f, 5.2f, 4.6f); close()
        moveTo(10.2f, 9.9f); quadTo(6.4f, 8.4f, 6.4f, 12f); quadTo(6.4f, 15.6f, 10.2f, 14.1f)
        moveTo(17.6f, 9.9f); quadTo(13.8f, 8.4f, 13.8f, 12f); quadTo(13.8f, 15.6f, 17.6f, 14.1f)
    }

    val AudioTrack: ImageVector = fillIcon("AudioTrack") {
        roundRect(2.6f, 10f, 4.8f, 14f, 1.1f)
        roundRect(7f, 6.4f, 9.2f, 17.6f, 1.1f)
        roundRect(11.4f, 3.4f, 13.6f, 20.6f, 1.1f)
        roundRect(15.8f, 6.4f, 18f, 17.6f, 1.1f)
        roundRect(20.2f, 10f, 22.4f, 14f, 1.1f)
    }

    val Speed: ImageVector = strokeIcon("Speed", width = 1.9f) {
        moveTo(3.6f, 17.6f)
        curveTo(3.6f, 12.9f, 7.4f, 9.1f, 12f, 9.1f)
        curveTo(16.6f, 9.1f, 20.4f, 12.9f, 20.4f, 17.6f)
        moveTo(12f, 17.6f); lineTo(15.9f, 12.2f)
    }

    val ChevronRight: ImageVector = strokeIcon("ChevronRight", width = 2f) {
        moveTo(9.5f, 5f); lineTo(16.5f, 12f); lineTo(9.5f, 19f)
    }

    val ChevronDown: ImageVector = strokeIcon("ChevronDown", width = 2f) {
        moveTo(5f, 8.5f); lineTo(12f, 15.5f); lineTo(19f, 8.5f)
    }

    val Search: ImageVector = strokeIcon("Search", width = 2f) {
        circle(10.5f, 10.5f, 5.8f)
        moveTo(14.8f, 14.8f); lineTo(20f, 20f)
    }

    val Close: ImageVector = strokeIcon("Close", width = 2f) {
        moveTo(6.5f, 6.5f); lineTo(17.5f, 17.5f)
        moveTo(17.5f, 6.5f); lineTo(6.5f, 17.5f)
    }
}

// --- builders ---------------------------------------------------------------

private fun fillIcon(
    name: String,
    evenOdd: Boolean = false,
    block: PathBuilder.() -> Unit,
): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = SolidColor(Color.Black),
            pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
        ) { block() }
    }.build()

private fun strokeIcon(
    name: String,
    width: Float = 1.8f,
    block: PathBuilder.() -> Unit,
): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) { block() }
    }.build()

// --- path helpers -----------------------------------------------------------
// The two closed ones wind clockwise in the y-down viewport, so NonZero unions
// them and EvenOdd knocks them out of whatever they sit inside.

private fun PathBuilder.roundRect(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    radius: Float,
) {
    moveTo(left + radius, top)
    lineTo(right - radius, top)
    quadTo(right, top, right, top + radius)
    lineTo(right, bottom - radius)
    quadTo(right, bottom, right - radius, bottom)
    lineTo(left + radius, bottom)
    quadTo(left, bottom, left, bottom - radius)
    lineTo(left, top + radius)
    quadTo(left, top, left + radius, top)
    close()
}

private fun PathBuilder.circle(cx: Float, cy: Float, radius: Float) {
    val k = radius * 0.5523f
    moveTo(cx, cy - radius)
    curveTo(cx + k, cy - radius, cx + radius, cy - k, cx + radius, cy)
    curveTo(cx + radius, cy + k, cx + k, cy + radius, cx, cy + radius)
    curveTo(cx - k, cy + radius, cx - radius, cy + k, cx - radius, cy)
    curveTo(cx - radius, cy - k, cx - k, cy - radius, cx, cy - radius)
    close()
}

/**
 * Open circular arc to (x, y) — SVG's elliptical arc with both radii equal. [clockwise] is
 * the sweep as it reads on a y-down grid; [major] takes the long way round, the only way
 * one command reaches past 180°.
 */
private fun PathBuilder.arc(
    radius: Float,
    x: Float,
    y: Float,
    clockwise: Boolean,
    major: Boolean,
) {
    arcTo(radius, radius, 0f, major, clockwise, x, y)
}
