package com.flick.receiver.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Corner tokens (receiver-expressive-spec.md §5.3), retuned to the Expressive
 * radii. The design's 1920-px radii are halved to dp; the names are unchanged so
 * the whole tree keeps compiling.
 *
 * | Token  | Radius | Where |
 * |--------|--------|-------|
 * | [Sm]   |  8 dp  | spec chips, close buttons, small square affordances |
 * | [Md]   | 13 dp  | the subtitles / stream-metrics side cards |
 * | [Lg]   | 17 dp  | the square back-10 / forward-10 transport buttons |
 * | [Xl]   | 20 dp  | the subtitles & metrics panels, the manual-entry card |
 * | [Play] | 22 dp  | the play button only |
 * | [Hero] | 26 dp  | the bottom transport panel, handshake card, QR card |
 * | [Pill] |  50 %  | live status pills, the scrub track, outlined pills |
 */
object FlickShape {
    val Sm = RoundedCornerShape(8.dp)
    val Md = RoundedCornerShape(13.dp)
    val Lg = RoundedCornerShape(17.dp)
    val Xl = RoundedCornerShape(20.dp)

    /** The play button's own radius — between [Lg] and [Hero]. */
    val Play = RoundedCornerShape(22.dp)

    val Hero = RoundedCornerShape(26.dp)
    val Pill = RoundedCornerShape(percent = 50)
}
