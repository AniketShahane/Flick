package com.flick.sender.ui.components

import kotlin.math.min
import kotlin.math.pow

/**
 * The synopsis intertitle on the detail page, as numbers: how far the finger has pulled it,
 * where a release settles it, and where every piece of it is at a given travel. Travel is 0
 * (put away) to 1 (open); lengths are dp and speeds dp/s, so the decision is the same on every
 * density. docs/flick-hyperspace-phone.md §1a is the same table in prose and has to move with it.
 */
internal object SynopsisPolicy {
    /** An opening drag released past this much of the way settles open. */
    const val OPEN_AT = 0.35f

    /** A closing drag (from open) released past this much of the way back settles closed. */
    const val CLOSE_AT = 0.65f

    /** A release at least this fast settles in its own direction, wherever it is. */
    const val FLING_DP_PER_S = 500f

    /** The finger's travel for the whole reveal, as a share of the window's width... */
    const val TRAVEL_SHARE = 0.72f

    /** ...but never longer than a thumb comfortably sweeps on a wide window. */
    const val MAX_TRAVEL_DP = 420f

    /** Predictive back takes the reveal at most this share of the way home before it commits. */
    const val BACK_TRAVEL = 0.3f

    // The page's chrome steps back as the story comes: gone by 1 / CHROME_FADE of the way.
    const val CHROME_FADE = 1.6f
    const val CHROME_DRIFT_DP = 30f

    const val GROUND_ALPHA = 0.96f

    /** The film strip down the leading edge, and the frames running in it. */
    const val STRIP_LEFT_DP = 12f
    const val STRIP_WIDTH_DP = 108f
    const val STRIP_BLEED_DP = 20f
    const val STRIP_FROM_DP = 130f
    const val FRAME_INSET_DP = 17f
    const val FRAME_WIDTH_DP = 74f
    const val FRAME_HEIGHT_DP = 111f
    const val FRAME_GAP_DP = 12f
    const val FRAME_PITCH_DP = FRAME_HEIGHT_DP + FRAME_GAP_DP

    /** Eight holes to a frame, so the holes and the frames repeat on the same pitch. */
    const val SPROCKETS_PER_FRAME = 8
    const val SPROCKET_WIDTH_DP = 7f
    const val SPROCKET_HEIGHT_DP = 10f
    const val SPROCKET_EDGE_DP = 4f
    const val SPROCKET_CORNER_DP = 1.5f

    /** The strip's frames run upward at this speed × travel. */
    const val REEL_DP_PER_S = 300f

    /** The strip's centre line, where the poster lands as its first frame. */
    const val STRIP_CENTER_DP = STRIP_LEFT_DP + STRIP_WIDTH_DP / 2f

    /** The card's column: from here to [CARD_END_DP] short of the trailing edge. */
    const val CARD_START_DP = STRIP_LEFT_DP + STRIP_WIDTH_DP + 14f
    const val CARD_END_DP = 12f
    const val CARD_MAX_WIDTH_DP = 480f

    /** Once the story is this far in, the card flickers like a projected title. */
    const val FLICKER_FROM = 0.6f
    const val FLICKER_MIN_BRIGHTNESS = 0.9f
    const val FLICKER_JITTER_DP = 0.4f

    /** The card's lines arrive one after another: line i from LINES_START + LINES_STEP × i. */
    const val LINES_START = 0.55f
    const val LINES_STEP = 0.06f
    const val LINES_SPAN = 0.3f
    const val LINES_RISE_DP = 6f

    fun travelDp(windowWidthDp: Float): Float =
        min(windowWidthDp * TRAVEL_SHARE, MAX_TRAVEL_DP).coerceAtLeast(1f)

    /** The reveal for a finger [travelDp] toward open (leftward) from where a drag at [from] went down. */
    fun progress(from: Float, travelDp: Float, fullTravelDp: Float): Float =
        (from + travelDp / fullTravelDp).coerceIn(0f, 1f)

    /**
     * Where a release at [progress] with [velocityDpPerS] (positive toward open, leftward)
     * settles. [opening] is the drag's direction of intent: a pull from the poster opens past
     * [OPEN_AT]; a push from the open story closes past [CLOSE_AT], so a small wobble on an
     * open story never shuts it.
     */
    fun settlesOpen(progress: Float, velocityDpPerS: Float, opening: Boolean): Boolean = when {
        velocityDpPerS >= FLING_DP_PER_S -> progress > 0f
        velocityDpPerS <= -FLING_DP_PER_S -> false
        opening -> progress >= OPEN_AT
        else -> progress >= CLOSE_AT
    }

    /**
     * Where predictive back holds a reveal that was at [from] when the gesture began, for a
     * back gesture [backProgress] of the way in: only ever further home.
     */
    fun backHold(from: Float, backProgress: Float): Float = from * (1f - BACK_TRAVEL * backProgress.coerceIn(0f, 1f))

    fun easeInOutCubic(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f
    }

    fun easeOutCubic(t: Float): Float {
        val x = 1f - t.coerceIn(0f, 1f)
        return 1f - x * x * x
    }

    private fun ramp(p: Float, from: Float, span: Float): Float = ((p - from) / span).coerceIn(0f, 1f)

    fun chromeAlpha(p: Float): Float = 1f - (p * CHROME_FADE).coerceIn(0f, 1f)

    fun groundAlpha(p: Float): Float = GROUND_ALPHA * easeInOutCubic(p)

    /** The poster shrinks onto the strip as its first frame: [endScale] there, 1 at rest. */
    fun posterScale(p: Float, endScale: Float): Float = 1f - (1f - endScale) * easeInOutCubic(p)

    fun posterAlpha(p: Float): Float = 1f - ramp(p, 0.55f, 0.3f)

    fun stripAlpha(p: Float): Float = ramp(p, 0.2f, 0.4f)

    /** The strip slides in from off the leading edge, dp, negative is left. */
    fun stripShiftDp(p: Float): Float = -STRIP_FROM_DP * (1f - easeInOutCubic(p))

    fun cardAlpha(p: Float): Float = ramp(p, 0.45f, 0.3f)

    fun closeAlpha(p: Float): Float = ramp(p, 0.6f, 0.4f)

    /**
     * Line [index]'s arrival, 0 to 1 and already eased, at travel [p]. A late line's span is
     * cut short so it is whole at rest too: at the full span the tagline would settle at 97%.
     */
    fun lineIn(p: Float, index: Int): Float {
        val start = LINES_START + LINES_STEP * index
        return easeOutCubic(ramp(p, start, min(LINES_SPAN, 1f - start)))
    }

    /** Title size, sp: a long title steps down so it keeps to two or three lines. */
    fun titleSp(title: String): Float = if (title.length > 14) 26f else 32f

    /** Overview size, sp: a long one steps down so more of it is in view before it scrolls. */
    fun overviewSp(overview: String): Float = if (overview.length > 240) 14.5f else 16f
}
