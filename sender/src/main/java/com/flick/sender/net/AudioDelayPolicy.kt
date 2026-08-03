package com.flick.sender.net

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The A/V nudge, as arithmetic. The receiver holds these same three constants and REJECTS
 * an arriving `setAudioDelay` that is outside the range or off the step grid — it does not
 * clamp one into something the phone never asked for — so a value this widened is a frame
 * the TV drops on the floor. The two sides move together or not at all.
 *
 * The sign is VLC's and mpv's, because that is the convention the user has already met
 * somewhere else: a POSITIVE delay is audio heard LATER than the picture, a negative one
 * earlier, zero is in sync. Nothing in the app may print the offset without also stating
 * that direction in words — the sign alone reads either way round to most people.
 *
 * It lives here rather than inside [PlaybackSession] for the reason [SeekPolicy] does:
 * that class reads `SystemClock` and builds `org.json` frames, neither of which exists in
 * a plain JVM unit test.
 */
internal object AudioDelayPolicy {

    /** The far end of "earlier than the picture". */
    const val MIN_MS = -500

    /** The far end of "later than the picture". */
    const val MAX_MS = 500

    /** One press of a stepper, and the resolution the whole range is quantised to. */
    const val STEP_MS = 25

    /** Audio and picture together, and the value every new cast starts at. */
    const val IN_SYNC_MS = 0

    /**
     * The largest offset any single frame on the wire may MOVE the picture by.
     *
     * Holding a value costs the TV nothing in either direction — but changing one is paid
     * for once by the picture, in proportion to the size of the change: Media3 holds a
     * frame that lands more than 50 ms early, drops one more than 30 ms late, and past
     * 500 ms late abandons decoded buffers forward to the next keyframe, which is a
     * visible skip that takes a whole GOP to come back from. So the size of a step is not
     * the only thing that matters; the size of a JUMP is.
     *
     * Four steps is a handful of held or dropped frames — an eighth of a second of hitch
     * that corrects itself — and nowhere near the keyframe branch. It is a multiple of
     * [STEP_MS], so walking by it never leaves the grid.
     */
    const val MAX_JUMP_MS = 4 * STEP_MS

    /**
     * How many steps the range holds between its two ends, which is what an adjustable
     * control has to report so a screen reader lands on the same values a press does.
     */
    const val STEPS_BETWEEN_BOUNDS = (MAX_MS - MIN_MS) / STEP_MS - 1

    /**
     * The nearest legal value to [delayMs]: on the step grid and inside the bounds. Every
     * path into the session goes through this, which is what makes the phone incapable of
     * putting a frame on the wire that the receiver's validator would refuse.
     */
    fun clamp(delayMs: Int): Int {
        val bounded = delayMs.coerceIn(MIN_MS, MAX_MS)
        // Nearest, not truncated: a blade is dragged to arbitrary milliseconds and
        // truncation toward zero would make the same distance from in-sync land on a
        // different step on either side of it.
        return ((bounded.toFloat() / STEP_MS).roundToInt() * STEP_MS).coerceIn(MIN_MS, MAX_MS)
    }

    /** One step later than the picture, stopping at [MAX_MS]. */
    fun stepUp(delayMs: Int): Int = clamp(clamp(delayMs) + STEP_MS)

    /** One step earlier than the picture, stopping at [MIN_MS]. */
    fun stepDown(delayMs: Int): Int = clamp(clamp(delayMs) - STEP_MS)

    /**
     * The next value on the way from [current] to [target], never more than
     * [MAX_JUMP_MS] of picture away from where the TV already is.
     *
     * Everything that can ask for a large move in one go resolves through here: a tap on
     * the far end of the blade's track, a flick whose pointer samples are far apart, a
     * Reset from a bound, and a screen reader setting the value outright. A stepper press
     * and an ordinary drag are already inside the bound, so for them this returns the
     * target and no walk happens at all.
     */
    fun approach(current: Int, target: Int): Int {
        val from = clamp(current)
        val to = clamp(target)
        val delta = to - from
        if (abs(delta) <= MAX_JUMP_MS) return to
        return clamp(from + if (delta > 0) MAX_JUMP_MS else -MAX_JUMP_MS)
    }

    /** At a bound the stepper on that side is disabled rather than silently doing nothing. */
    fun canStepUp(delayMs: Int): Boolean = clamp(delayMs) < MAX_MS

    /** The mirror of [canStepUp]. */
    fun canStepDown(delayMs: Int): Boolean = clamp(delayMs) > MIN_MS

    /**
     * The signed figure the readout prints — `+150`, `−150`, `0`. U+2212 rather than a
     * hyphen, the same glyph `Format.remaining` sets its minus in, so the two line up
     * under the app's tabular figures. The unit and the direction are copy and live in
     * `strings.xml`; this is only the number.
     */
    fun signed(delayMs: Int): String = when (val value = clamp(delayMs)) {
        0 -> "0"
        in 1..MAX_MS -> "+$value"
        else -> "−${-value}"
    }
}
