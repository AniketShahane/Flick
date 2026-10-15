package com.flick.sender.ui.components

import kotlin.math.min

/**
 * The synopsis panel on the detail page, as numbers: how far the finger has pulled it, where
 * a release settles it, and how its lines arrive. Everything is a fraction of the panel's
 * travel, or dp and dp/s, so the decision is the same on every density.
 * docs/flick-hyperspace-phone.md §1a is the same table in prose and has to move with it.
 */
internal object SynopsisPolicy {
    /** An opening drag released past this much of the way settles open. */
    const val OPEN_AT = 0.35f

    /** A closing drag (from open) released past this much of the way back settles closed. */
    const val CLOSE_AT = 0.65f

    /** A release at least this fast settles in its own direction, wherever it is. */
    const val FLING_DP_PER_S = 500f

    /** The page left uncovered beside the panel, so the poster it pushed aside stays in view. */
    const val PEEK_DP = 48f

    /** On a wide window the panel stops at a reading width rather than covering the page. */
    const val MAX_WIDTH_DP = 520f

    /** How far the page drifts right, and how deep it dims, with the panel fully in. */
    const val PAGE_DRIFT_DP = 44f
    const val SCRIM_ALPHA = 0.62f
    const val POSTER_RECEDE_SCALE = 0.94f

    /** Predictive back takes the panel at most this share of the way home before it commits. */
    const val BACK_TRAVEL = 0.3f

    /**
     * The panel's lines arrive one after another as it comes in: line [index] starts at
     * [STAGGER_START] + [STAGGER_STEP] × index of the travel and is whole [STAGGER_SPAN] later.
     * Driven by the travel itself rather than a clock of its own, so the stagger follows the
     * finger, the settle and predictive back alike, and costs no animation.
     */
    const val STAGGER_START = 0.25f
    const val STAGGER_STEP = 0.05f
    const val STAGGER_SPAN = 0.45f
    const val STAGGER_LAG_DP = 28f

    fun panelWidthDp(windowWidthDp: Float): Float =
        min(windowWidthDp - PEEK_DP, MAX_WIDTH_DP).coerceAtLeast(1f)

    /** The panel's travel for a finger [travelDp] from where a drag that began at [from] went down. */
    fun progress(from: Float, travelDp: Float, panelWidthDp: Float): Float =
        (from + travelDp / panelWidthDp).coerceIn(0f, 1f)

    /**
     * Where a release at [progress] with [velocityDpPerS] (positive is rightward, toward open)
     * settles. [opening] is the drag's direction of intent: a pull from the poster opens past
     * [OPEN_AT]; a push from the open panel closes past [CLOSE_AT], so a small wobble on an open
     * panel never shuts it.
     */
    fun settlesOpen(progress: Float, velocityDpPerS: Float, opening: Boolean): Boolean = when {
        velocityDpPerS >= FLING_DP_PER_S -> progress > 0f
        velocityDpPerS <= -FLING_DP_PER_S -> false
        opening -> progress >= OPEN_AT
        else -> progress >= CLOSE_AT
    }

    /** Line [index]'s arrival, 0 to 1, at panel travel [progress]. */
    fun lineIn(progress: Float, index: Int): Float =
        ((progress - STAGGER_START - STAGGER_STEP * index) / STAGGER_SPAN).coerceIn(0f, 1f)

    /** Where predictive back holds the panel for a back gesture [backProgress] of the way in. */
    fun backHold(backProgress: Float): Float = 1f - BACK_TRAVEL * backProgress.coerceIn(0f, 1f)
}
