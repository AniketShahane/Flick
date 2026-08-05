package com.flick.sender.net

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The A/V nudge, as arithmetic. The receiver holds these same three constants and REJECTS
 * an arriving `setAudioDelay` that is outside the range or off the step grid — it does not
 * clamp one into something the phone never asked for — so a value this widened is a frame
 * the TV drops on the floor, and a refused frame costs the whole control socket. The two
 * sides move together or not at all.
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

    /**
     * The far end of "earlier than the picture", and [MAX_MS] its mirror. Five seconds is
     * not a round number picked for looking generous: it is a bound the verified TV's
     * forward buffer clears with 9.3 s to spare, and it is deliberately wider than the
     * smallest receiver can render.
     *
     * A delay claims BUFFER, whichever way it points. Positive pulls the picture forward,
     * so the video renderer reads samples |delay| ahead of the audio clock and the load
     * control has to hold that much past the position it measures its own floor against.
     * Negative holds the picture back, so the video queue trails the audio's by |delay|
     * and those samples stay resident rather than being discarded behind the read head.
     * Either way the claim is |delay| of media out of the forward budget
     * `BufferBudgetPolicy` sizes, which holds 14,297 ms of 100 Mbps content on a 512 MB
     * heap, 7,301 ms at 256 MB, 2,738 ms at 96 MB, and 2,282 ms on the smallest tier that
     * policy will build (its 32 MiB byte floor). The verified TV — a Google TV Streamer —
     * reports a 512 MB grant with `largeHeap` and is not a low-RAM device, so it has the
     * first of those. What a receiver will actually render is narrower than its forward
     * buffer, because its cap reserves the load control's own resume threshold on top:
     * 9,532 ms, 4,868 ms, 1,826 ms and 1,522 ms across those four tiers. So only the
     * 512 MB tier carries the full five seconds — a 256 MB TV clamps a full-range offset
     * by 132 ms, and the two smallest clamp hard.
     *
     * That last part is deliberate, and it is the one thing here that is not a fit test.
     * This pair is the WIRE range — the widest value any receiver will accept — not the
     * widest every receiver can render. A TV too small for it caps the offset against its
     * own heap before applying it; what these bounds have to guarantee is only that the
     * frame is LEGAL, because an out-of-range frame is refused and a refused frame costs
     * the socket. A delay wider than the buffer receiving it is a video renderer that runs
     * out of samples, and that is a stall — the one thing this app exists not to do — so
     * the cap belongs where the heap figure is known, which is not here.
     *
     * What the range costs on every TV is paid at a SEEK. The receiver shifts the video
     * clock and deliberately does NOT shift what a seek lands on, so after a scrub or a
     * ±10 s tap the decoder can supply frames only from P while the shifted clock already
     * reads P ± |delay|: the picture skips forward, or freezes, for up to the whole delay
     * before it is right again. At ±500 ms that was half a second. At ±5000 a viewer who
     * has dialled in the full five seconds pays five seconds of it on every seek they make
     * — half the distance a ±10 s tap moved, spent arriving there — and the same again,
     * under [MAX_JUMP_MS]'s rule rather than this one, on the Reset that puts the nudge
     * back. It is inherent (a renderer cannot show frames it does not have), it
     * self-corrects, and it is the price of the range rather than a defect in it.
     */
    const val MIN_MS = -5_000

    /** The far end of "later than the picture". See [MIN_MS] for what the bound costs. */
    const val MAX_MS = 5_000

    /**
     * One press of a stepper, and the resolution the whole range is quantised to.
     *
     * It stays 25 ms at every offset rather than coarsening with distance from zero. The
     * error a badly muxed file carries can sit anywhere in this range, and lip-sync work
     * happens around THAT value, not around in-sync: a step that grew with the offset
     * would leave a film 3.5 s out of sync with no way to trim its last 25 ms, which is
     * the whole job. That the range is 400 steps end to end is not a problem to solve,
     * because nothing travels it by pressing: the blade crosses the full span in one drag
     * at about 45 ms of offset per dp of track, so a finger places any value in the range
     * within a step or two, and the presses are what close that gap.
     */
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
     * Ten steps: 250 ms, half that branch, and a multiple of [STEP_MS] so walking by it
     * never leaves the grid. The bound used to be argued from both ends — under the 500 ms
     * ceiling, over what a finger covers between two pointer samples — and across this
     * range only the ceiling survives. The blade is about 45 ms of offset per dp, so the
     * 5.5 dp a quick drag crosses between two samples is worth 250 ms of value where it
     * was worth 100, and the doubling that gave the old bound its headroom over the finger
     * would land exactly on the branch. So this is set from the ceiling alone, at the
     * largest hop that keeps half of it clear, and what the finger outruns is absorbed by
     * the walk instead — which is the mechanism that exists for exactly that.
     *
     * It also divides the span exactly, which is what makes a walk land ON the target
     * rather than a hop short of it. Bound to bound is 40 hops — see [WALK_INTERVAL_MS]
     * for what they cost in time and in frames. Keeping 200 would have been 50 hops at the
     * same peak rate, the rate being the interval's and not the hop's: 400 ms more crawl
     * under the readout, for clearance below a branch both are already clear of.
     *
     * Bounding the hop bounds what one frame asks for; it cannot bound what the move
     * costs. A move of D toward "audio earlier" holds the picture for D of real time
     * however it is walked there, because the clock has to advance that far before the
     * frame already on screen comes due again — the walk buys the absence of the keyframe
     * branch, not the absence of the wait.
     */
    const val MAX_JUMP_MS = 10 * STEP_MS

    /**
     * How long one hop of a walked move waits behind the last.
     *
     * It sits beside [MAX_JUMP_MS] rather than in [PlaybackSession] because hop size and
     * hop cadence are one behaviour: neither of the two figures that matter — how long a
     * move takes, and how many frames it puts on the wire — can be read off either alone.
     * The widest move there is, bound to bound, is `(MAX_MS - MIN_MS) / MAX_JUMP_MS` = 40
     * hops; the first goes out inline, so the value lands 1,560 ms after the finger and
     * the burst peaks at 25 frames a second. The rate is this interval and nothing else —
     * widening the range lengthened the burst and left its rate alone — and it stays the
     * same order as the ≤20 a second [PlaybackSession] throttles a scrub to, which is what
     * this channel is already sized for under a drag.
     */
    const val WALK_INTERVAL_MS = 40L

    /**
     * The grid a drag is FELT against, which is deliberately not the grid it moves on.
     *
     * The volume blade already separates the two — it ticks against buckets of the track
     * rather than of the value — for the reason this one now has to. The blade is about
     * 220 dp of track on an ordinary phone: at ±500 ms a tick per 25 ms step was a detent
     * every 5.5 dp, and across this range the same rule would put one every 0.55 dp, which
     * is not a texture but a buzz. Ten steps — 250 ms — restores exactly the old spacing,
     * and being a multiple of [STEP_MS] counted from zero it lands a detent on every round
     * figure and one exactly at in-sync.
     */
    const val HAPTIC_TICK_MS = 10 * STEP_MS

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
     * is always inside the bound, and so is any drag short of a flick, so for them this
     * returns the target and no walk happens at all.
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
     * Which [HAPTIC_TICK_MS] bucket [delayMs] falls in; a drag ticks when this changes and
     * is silent between. Floored rather than truncated, so the bucket containing in-sync
     * is the same width as every other one and crossing zero is felt rather than sitting
     * in the middle of a bucket twice as wide as its neighbours.
     */
    fun tickIndex(delayMs: Int): Int = clamp(delayMs).floorDiv(HAPTIC_TICK_MS)

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
