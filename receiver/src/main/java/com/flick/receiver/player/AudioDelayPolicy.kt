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
     * sample queue keeps that much resident behind it. A delay wider than the
     * buffer is a renderer that runs out of samples, which is the one failure
     * this app exists to prevent. The only question this bound settles is WHOSE
     * buffer it is written for.
     *
     * A single wire number has to fit the smallest device, and 2 s was the widest
     * that did. `bufferBudgetFor` holds, as forward buffer of 100 Mbps content,
     * 14,297 ms on a 512 MB heap, 7,301 ms at 256 MB, 2,738 ms at 96 MB and
     * 2,282 ms on the 32 MiB byte floor. The verified TV reports
     * `dalvik.vm.heapsize=512m` with `android:largeHeap="true"` and is not a
     * low-RAM device, so it has the 14,297 ms tier and 5 s fits it with room —
     * while 5 s of forward samples is 62.5 MB and does NOT fit the two smallest
     * tiers at all.
     *
     * One bound cannot be both the same on two screens and different on two TVs,
     * so it is two numbers: this range is the widest any device may be ASKED for,
     * and [maxDelayMsFor] is what the device that was actually granted the heap
     * can carry.
     *
     * What the bound costs is paid at a discontinuity — a change of shift, and
     * equally a seek at a settled one — never by uninterrupted playback; the
     * thresholds are in `AudioDelayVideoRenderer`. The phone walks a change so no
     * single frame moves the picture by more than its own jump bound. Nothing
     * bounds the second case below the delay itself: at the full 5 s a scrub or a
     * ±10 s tap costs up to five seconds of skipped or frozen picture before it is
     * right again, and that is the honest price of the wider range.
     */
    const val MIN_MS = -5_000
    const val MAX_MS = 5_000
    const val STEP_MS = 25

    /**
     * Whether a `setAudioDelay` frame's `delayMs` may be acted on at all.
     * Anything else is a malformed frame and is refused like an out-of-range
     * `setVolume` level, rather than being clamped into something the phone did
     * not ask for.
     *
     * Deliberately free of any device term, and [maxDelayMsFor] is deliberately
     * not consulted here: this is the WIRE rule, and the wire is a contract
     * between two screens. A frame the phone was entitled to send and this TV
     * refused takes the whole control socket down with it, and a socket that died
     * because of how much heap this particular TV happened to be granted would be
     * far worse than a delay quietly carried only as far as the buffer allows.
     */
    fun accepts(delayMs: Long): Boolean =
        delayMs >= MIN_MS && delayMs <= MAX_MS && delayMs % STEP_MS == 0L

    /**
     * The widest delay THIS device can actually carry, from the buffer it was
     * granted. Not bounded by [MAX_MS]: it answers what the hardware affords, and
     * intersecting that with what the wire allows is the caller's.
     *
     * The deciding figure is the FORWARD buffer at the planned peak —
     * [BufferBudget.plannedPeakFitMs] less the back buffer, which has already
     * spent its share of the same byte budget — because that is the pool a delay
     * eats into whichever way it points.
     *
     * The margin is [BufferBudget.bufferForPlaybackAfterRebufferMs] rather than
     * an arbitrary fraction, because it is the level the load control itself
     * demands before it will resume after a rebuffer. A delay allowed to leave
     * less than that behind would hold the player permanently below its own
     * resume threshold — the exact failure `PLAYBACK_THRESHOLD_DIVISOR` exists to
     * keep out of the tuning — so a delay may claim the forward buffer down to,
     * and never past, the level the player can still start again from.
     *
     * That leaves 9,532 ms on the verified TV, so its ±5,000 is carried untouched;
     * 4,868 ms on a 256 MB grant, 1,826 ms at 96 MB, 1,522 ms on the byte floor.
     */
    fun maxDelayMsFor(budget: BufferBudget): Int {
        val forwardMs = budget.plannedPeakFitMs - budget.backBufferMs
        return (forwardMs - budget.bufferForPlaybackAfterRebufferMs).coerceAtLeast(0)
    }

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
