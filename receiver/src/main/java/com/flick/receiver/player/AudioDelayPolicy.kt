package com.flick.receiver.player

/**
 * The audio-delay range the wire allows, and its translation into the shift the
 * video renderers apply.
 *
 * The wire value is stated the way the viewer means it: a POSITIVE `delayMs` is
 * audio heard LATER than the picture, a negative one is audio heard earlier, and
 * zero is in sync. What the receiver actually moves is the picture — Media3
 * 1.10.1 has no audio-offset API, and the only one it could grow (an
 * `AudioProcessor` padding or dropping PCM) is bypassed entirely when encoded
 * audio is passed through to the HDMI sink, which is exactly the E-AC3 / DTS /
 * TrueHD content most likely to be out of sync. Advancing the picture by the
 * same amount is indistinguishable to a viewer and costs nothing on the audio
 * path.
 *
 * [STEP_MS] is a wire rule rather than a rendering one: any value inside the
 * range renders, but a remote that can only produce multiples of 25 ms keeps the
 * phone's displayed value and the TV's applied value the same number.
 */
object AudioDelayPolicy {

    /**
     * The bound in both directions.
     *
     * It is a BUFFER bound, not a taste one, and it is the same number on the
     * phone — the two must agree exactly, because [accepts] refuses anything
     * outside this range and a refused frame costs the whole control socket.
     *
     * A delay claims `|delay|` of forward buffer whichever way it points. A
     * positive one pulls frames forward, so the video renderer reads that far
     * ahead of the audio clock; a negative one holds them back, so the video
     * sample queue keeps that much resident behind it. `BufferBudgetPolicy`'s
     * smallest tier holds 2,282 ms of 100 Mbps content, so 2 s fits every device
     * this runs on and 2.5 s would not fit that tier. A delay wider than the
     * buffer is a renderer that runs out of samples, which is the one failure
     * this app exists to prevent.
     *
     * What the bound costs is paid at a discontinuity — a change of shift, and
     * equally a seek at a settled one — never by uninterrupted playback; the
     * thresholds are in `AudioDelayVideoRenderer`. The phone walks a change so no
     * single frame moves the picture by more than its own jump bound. Nothing
     * bounds the second case below the delay itself: at the full 2 s a scrub or a
     * ±10 s tap costs up to two seconds of skipped or frozen picture before it is
     * right again.
     */
    const val MIN_MS = -2_000
    const val MAX_MS = 2_000
    const val STEP_MS = 25

    /**
     * Whether a `setAudioDelay` frame's `delayMs` may be acted on at all.
     * Anything else is a malformed frame and is refused like an out-of-range
     * `setVolume` level, rather than being clamped into something the phone did
     * not ask for.
     */
    fun accepts(delayMs: Long): Boolean =
        delayMs >= MIN_MS && delayMs <= MAX_MS && delayMs % STEP_MS == 0L

    /**
     * The last guard before the renderer, for a caller that is not the wire.
     * Range only: the step is what keeps two screens agreeing on a number, and
     * silently rounding a value the wire already accepted would be the receiver
     * inventing a delay of its own.
     */
    fun clamp(delayMs: Int): Int = delayMs.coerceIn(MIN_MS, MAX_MS)

    /**
     * The shift added to the position the video renderers are rendered against.
     *
     * A renderer releases a decoded frame from `earlyUs = presentationTimeUs -
     * positionUs`, so handing it a position that is [delayMs] further along
     * releases every frame that much sooner — the picture moves earlier, which
     * is the same event as the audio moving later. The sign therefore passes
     * straight through.
     */
    fun videoShiftUs(delayMs: Int): Long = delayMs.toLong() * 1_000L
}
