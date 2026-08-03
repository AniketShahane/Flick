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
     * It is a frame-release bound, not an arbitrary one. A negative delay holds
     * frames back, and `VideoFrameReleaseControl` answers anything more than
     * 50 ms early with "try again later" — the renderer simply keeps the one
     * decoded output buffer it is holding and re-asks on the next render tick,
     * so the cost is decoder back-pressure absorbed by the load control, not a
     * dropped frame. A positive delay pulls frames forward, so the video
     * renderer needs samples half a second ahead of the audio clock; the buffer
     * budget is measured in tens of seconds, so half a second is inside it with
     * room to spare. Half a second is also far past any A/V error a remux
     * actually carries.
     *
     * What the bound costs is paid at a discontinuity — a change of shift, and
     * equally a seek at a settled one — never by uninterrupted playback; the
     * thresholds are in `AudioDelayVideoRenderer`. The small [STEP_MS] bounds
     * the first case to about a frame. Nothing bounds the second below the delay
     * itself, which is the other half of why this stops at half a second.
     */
    const val MIN_MS = -500
    const val MAX_MS = 500
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
