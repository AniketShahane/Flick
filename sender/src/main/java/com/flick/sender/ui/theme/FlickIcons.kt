package com.flick.sender.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The Flick icon set — 24dp grid, 1.8px stroke, round caps/joins (design §1.5).
 * Built as [ImageVector]s so callers tint them via `Icon(tint = …)`. The brand
 * mark and the ±10s transport glyphs are Canvas composables in ui/components
 * (they carry text / streaks a vector path can't). back-10 / fwd-10 here are the
 * arc-only forms; the "10" is overlaid by the transport cluster.
 */
object FlickIcons {

    val Play: ImageVector = fillIcon("Play") {
        moveTo(8f, 5f); lineTo(19f, 12f); lineTo(8f, 19f); close()
    }

    val Pause: ImageVector = fillIcon("Pause") {
        moveTo(8.5f, 5f); lineTo(11f, 5f); lineTo(11f, 19f); lineTo(8.5f, 19f); close()
        moveTo(13f, 5f); lineTo(15.5f, 5f); lineTo(15.5f, 19f); lineTo(13f, 19f); close()
    }

    val Previous: ImageVector = strokeIcon("Previous") {
        moveTo(6f, 5.5f); lineTo(6f, 18.5f)
        moveTo(18.5f, 5.5f); lineTo(9.5f, 12f); lineTo(18.5f, 18.5f); close()
    }

    val Next: ImageVector = strokeIcon("Next") {
        moveTo(18f, 5.5f); lineTo(18f, 18.5f)
        moveTo(5.5f, 5.5f); lineTo(14.5f, 12f); lineTo(5.5f, 18.5f); close()
    }

    /** Circular back arrow (the "10" is drawn on top by the caller). */
    val Back10: ImageVector = strokeIcon("Back10") {
        moveTo(12f, 4.4f)
        curveTo(7.8f, 4.4f, 4.4f, 7.8f, 4.4f, 12f)
        moveTo(12.9f, 1.4f); lineTo(7.8f, 4.4f); lineTo(12.9f, 7.4f); close()
    }

    val Fwd10: ImageVector = strokeIcon("Fwd10") {
        moveTo(12f, 4.4f)
        curveTo(16.2f, 4.4f, 19.6f, 7.8f, 19.6f, 12f)
        moveTo(11.1f, 1.4f); lineTo(16.2f, 4.4f); lineTo(11.1f, 7.4f); close()
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

    val Qr: ImageVector = strokeIcon("Qr") {
        moveTo(4f, 4f); lineTo(10.5f, 4f); lineTo(10.5f, 10.5f); lineTo(4f, 10.5f); close()
        moveTo(13.5f, 4f); lineTo(20f, 4f); lineTo(20f, 10.5f); lineTo(13.5f, 10.5f); close()
        moveTo(4f, 13.5f); lineTo(10.5f, 13.5f); lineTo(10.5f, 20f); lineTo(4f, 20f); close()
        moveTo(13.5f, 13.5f); lineTo(16.5f, 13.5f); lineTo(16.5f, 16.5f); lineTo(13.5f, 16.5f); close()
        moveTo(17f, 17f); lineTo(20f, 17f); lineTo(20f, 20f); lineTo(17f, 20f); close()
    }

    val Private: ImageVector = strokeIcon("Private") {
        moveTo(5f, 10.5f); lineTo(19f, 10.5f); lineTo(19f, 19.5f); lineTo(5f, 19.5f); close()
        moveTo(8.5f, 10.5f); lineTo(8.5f, 8f)
        quadTo(8.5f, 4.5f, 12f, 4.5f); quadTo(15.5f, 4.5f, 15.5f, 8f); lineTo(15.5f, 10.5f)
    }

    val Settings: ImageVector = strokeIcon("Settings") {
        // Center hub.
        moveTo(15.2f, 12f)
        curveTo(15.2f, 13.77f, 13.77f, 15.2f, 12f, 15.2f)
        curveTo(10.23f, 15.2f, 8.8f, 13.77f, 8.8f, 12f)
        curveTo(8.8f, 10.23f, 10.23f, 8.8f, 12f, 8.8f)
        curveTo(13.77f, 8.8f, 15.2f, 10.23f, 15.2f, 12f)
        close()
        // Eight spokes.
        moveTo(12f, 3.5f); lineTo(12f, 6.1f)
        moveTo(12f, 17.9f); lineTo(12f, 20.5f)
        moveTo(3.5f, 12f); lineTo(6.1f, 12f)
        moveTo(17.9f, 12f); lineTo(20.5f, 12f)
        moveTo(6f, 6f); lineTo(7.8f, 7.8f)
        moveTo(16.2f, 16.2f); lineTo(18f, 18f)
        moveTo(18f, 6f); lineTo(16.2f, 7.8f)
        moveTo(7.8f, 16.2f); lineTo(6f, 18f)
    }

    val Metrics: ImageVector = strokeIcon("Metrics") {
        moveTo(4f, 17f); quadTo(4f, 8f, 12f, 8f); quadTo(20f, 8f, 20f, 17f)
        moveTo(12f, 17f); lineTo(16.2f, 12.4f)
    }

    val Check: ImageVector = strokeIcon("Check", width = 2f) {
        moveTo(4f, 12.5f); lineTo(9f, 17.5f); lineTo(20f, 6.5f)
    }

    val Back: ImageVector = strokeIcon("Back", width = 2f) {
        moveTo(14.5f, 5f); lineTo(7.5f, 12f); lineTo(14.5f, 19f)
    }
}

// --- builders ---------------------------------------------------------------

private fun fillIcon(name: String, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) { block() }
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
