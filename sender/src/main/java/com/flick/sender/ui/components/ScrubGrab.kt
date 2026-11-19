package com.flick.sender.ui.components

import kotlin.math.abs
import kotlin.math.sign

/**
 * How a finger that took hold of the thumb maps onto the bar.
 *
 * Absolute mapping (x / width) made the film jump by however far off-centre the thumb was
 * grabbed. Mapping relative to the grab instead fixes that but strands the ends: a finger
 * that took a thumb near the end from its right side runs out of screen before the bar
 * does. So the bar is split at the anchor and each side is stretched to its own edge —
 * continuous at the anchor, exactly 0 at x = 0 and exactly 1 at x = width.
 */
internal object ScrubGrab {

    fun isThumbGrab(downX: Float, thumbX: Float, grabRadiusPx: Float): Boolean =
        abs(downX - thumbX) <= grabRadiusPx

    /**
     * Where the finger crossed the touch slop on its way from [downX] to [currentX]: the
     * slop is spent holding still, but whatever the same event travelled past it is real
     * movement and is kept.
     */
    fun anchorX(downX: Float, currentX: Float, slopPx: Float, widthPx: Float): Float =
        (downX + sign(currentX - downX) * slopPx).coerceIn(0f, widthPx.coerceAtLeast(0f))

    /**
     * An anchor within a pixel of an edge leaves that side no span to stretch over; its
     * denominator is floored at a pixel rather than dividing by ~0, and the exact edges
     * are answered before either side is consulted.
     */
    fun fraction(x: Float, anchorX: Float, anchorFraction: Float, widthPx: Float): Float {
        val w = widthPx.coerceAtLeast(1f)
        val ax = anchorX.coerceIn(0f, w)
        val af = anchorFraction.coerceIn(0f, 1f)
        val cx = x.coerceIn(0f, w)
        if (cx <= 0f) return 0f
        if (cx >= w) return 1f
        val f = if (cx >= ax) {
            af + (cx - ax) / (w - ax).coerceAtLeast(1f) * (1f - af)
        } else {
            af * cx / ax.coerceAtLeast(1f)
        }
        return f.coerceIn(0f, 1f)
    }
}
