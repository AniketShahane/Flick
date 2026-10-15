package com.flick.sender.ui.components

import kotlin.math.abs

/**
 * The poster-flick on the detail sheet, as numbers. Everything is in dp and dp/s so the
 * decision is the same on every density; docs/flick-hyperspace-phone.md is the same table
 * in prose and has to move with it.
 */
internal object FlickPadPolicy {
    /** Finger travel upward that arms a send on its own. */
    const val ARM_DISTANCE_DP = 80f

    /** Upward speed at release that sends a shorter flick. */
    const val FLING_VELOCITY_DP_PER_S = 1000f

    /** A fling still has to have moved the poster, or a tap with a twitch would send. */
    const val FLING_MIN_DISTANCE_DP = 20f

    /** Downward travel is followed at this fraction: the poster only goes one way. */
    const val DOWN_RESISTANCE = 0.2f

    /** Sideways travel is followed at this fraction, enough to feel held. */
    const val SIDE_FOLLOW = 0.25f
    const val TILT_DEG_PER_DP = 0.12f
    const val MAX_TILT_DEG = 8f

    /**
     * Whether the poster takes a drag that has just crossed touch slop, [travelYDp] from where
     * the finger went down (negative is up). The sheet around it scrolls, and a swipe it can
     * still use is a scroll: only once it has nothing further below does an upward swipe become
     * a throw, and a downward one that could scroll it back stays the sheet's too.
     */
    fun claims(sheetCanScrollForward: Boolean, sheetCanScrollBackward: Boolean, travelYDp: Float): Boolean =
        !sheetCanScrollForward && !(travelYDp > 0f && sheetCanScrollBackward)

    /**
     * A rightward drag at least this many times more sideways than vertical opens the
     * synopsis instead of being thrown: within about 34° of horizontal.
     */
    const val REVEAL_DOMINANCE = 1.5f

    /**
     * Whether a drag that has just crossed touch slop, [travelXDp] and [travelYDp] from where
     * the finger went down, is the synopsis being pulled in from the left. Decided once, at
     * slop, and never revisited: a drag that starts as a reveal stays one, and anything else —
     * up, down, left, or a steep diagonal — is the throw's, exactly as before.
     */
    fun reveals(travelXDp: Float, travelYDp: Float): Boolean =
        travelXDp > 0f && travelXDp >= abs(travelYDp) * REVEAL_DOMINANCE

    /** While the finger is down only distance can be judged; velocity exists at release. */
    fun armed(upDp: Float): Boolean = upDp >= ARM_DISTANCE_DP

    /** Whether a release sends the film. [upDp] and [upVelocityDpPerS] are positive upward. */
    fun sends(upDp: Float, upVelocityDpPerS: Float): Boolean =
        armed(upDp) ||
            (upDp >= FLING_MIN_DISTANCE_DP && upVelocityDpPerS >= FLING_VELOCITY_DP_PER_S)

    /** Where the poster sits for a finger [rawDyDp] from where it went down (negative is up). */
    fun followY(rawDyDp: Float): Float = if (rawDyDp < 0f) rawDyDp else rawDyDp * DOWN_RESISTANCE

    fun followX(rawDxDp: Float): Float = rawDxDp * SIDE_FOLLOW

    fun tiltDegrees(followXDp: Float): Float =
        (followXDp * TILT_DEG_PER_DP).coerceIn(-MAX_TILT_DEG, MAX_TILT_DEG)
}
