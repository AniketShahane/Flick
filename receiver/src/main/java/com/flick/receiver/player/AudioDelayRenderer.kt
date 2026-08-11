package com.flick.receiver.player

import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import com.flick.receiver.util.FlickLog

/**
 * The live video-clock shift, written on the main thread by the control command
 * and read on the playback thread every render tick.
 *
 * One field with no companion invariant to hold, so `@Volatile` is the entire
 * synchronization: the worst a late read can cost is one tick rendered at the
 * previous offset, which is shorter than a frame. It is owned by
 * [PlayerController] rather than by the factory so a commanded delay survives an
 * ExoPlayer rebuild — the replacement renderers read this same instance, so
 * unlike volume there is nothing to re-apply after a background/foreground cycle.
 */
class AudioDelayShift {
    @Volatile var videoShiftUs: Long = 0L
}

/**
 * Whether a shift that has held its value since [heldSinceUs] has stopped moving, judged
 * against the clock [nowUs] the renderer was handed for this tick.
 *
 * Pure, and taking the clock rather than reading one, because the playback thread already
 * has the only clock that matters here and a settle decision that samples its own is
 * untestable without a decoder.
 */
internal fun audioShiftSettled(nowUs: Long, heldSinceUs: Long): Boolean =
    nowUs - heldSinceUs >= AUDIO_SHIFT_SETTLE_US

/**
 * How long a shift must hold before it is reported.
 *
 * The phone walks a drag one hop every 40 ms, so this has to outlast a hop by enough that
 * ordinary scheduling jitter between two renderer ticks cannot look like the end of a
 * walk. It also has to stay short enough that the line lands while the viewer still has
 * the nudge in mind — a quarter second is both.
 */
internal const val AUDIO_SHIFT_SETTLE_US = 250_000L

/**
 * A video renderer that sees the playback clock shifted by [shift], and nothing
 * else changed.
 *
 * `render` is where a decoded frame's release is decided, from
 * `earlyUs = presentationTimeUs - positionUs`, so a position handed forward
 * releases frames sooner and one handed back holds them. `getDurationToProgressUs`
 * takes the identical shift because ExoPlayer's dynamic scheduling uses it to
 * choose the next wake-up; computed against an unshifted clock it would wake for
 * the wrong instant and the two would disagree about when the next frame is due.
 *
 * Every other method keeps the true renderer timestamp. `enable`, `resetPosition`
 * and `getReadingPositionUs` in particular: a seek must land exactly where it was
 * asked to, and the media clock — which is the audio renderer's, not this one's —
 * stays the single source of the reported position, so the scrub bar, the resume
 * checkpoints and the `state` frames are untouched by any delay.
 *
 * The picture pays for any DISCONTINUITY between the shifted clock and what the
 * decoder can supply, and there are exactly two. Media3 1.10.1's thresholds set
 * the price: a frame more than 50 ms early is answered "try again later" and
 * held, one more than 30 ms late is dropped, one more than 500 ms late drops
 * decoded buffers to the next keyframe.
 *
 * A CHANGE of shift costs the size of the change — toward "audio earlier" the
 * picture holds for it, the other way it drops that much. The phone caps any
 * single frame's move at 250 ms and walks anything larger as a run of absolute
 * values, so no frame this renderer ever sees reaches the 500 ms
 * drop-to-keyframe branch.
 *
 * A SEEK at a non-zero shift costs the whole delay, and re-pays it every time.
 * `resetPosition` deliberately gets the true position, so a seek to P leaves the
 * decoder able to supply frames from P while the clock already reads P+delay:
 * ahead of the audio the first frame force-renders and the next |delay| worth
 * arrive late and are dropped, behind it the target frame paints and then holds.
 * That is inherent to shifting a clock — a renderer cannot show frames it does
 * not have — so a scrub or a ±10 s tap at a settled delay skips or freezes for up
 * to the delay itself before it is right again: five seconds at the bound, and
 * less on a TV whose buffer forced the delay down — see
 * [AudioDelayPolicy.maxDelayMsFor]. That is the honest cost of the range, and it
 * is paid only by a viewer who dialled the shift out that far.
 *
 * Both self-correct, and uninterrupted playback at a settled shift costs nothing
 * beyond one held output buffer, which the load control's headroom absorbs.
 *
 * What this wraps is a [RotationCorrectingVideoRenderer], not a stock one:
 * [FlickRenderersFactory] stacks the two video customizations. They cannot
 * interact — rotation is settled where the picture is turned, and the shift only
 * moves the instant a decoded frame is released. That holds under both turning
 * mechanisms: the view turn changes which surface the delegate releases into and
 * what that view's matrix is, neither of which the release decision can see.
 */
internal class AudioDelayVideoRenderer(
    val delegate: Renderer,
    private val shift: AudioDelayShift,
) : ForwardingRenderer(delegate) {

    /**
     * The last shift this renderer reported, and the value it is currently watching
     * settle. Playback-thread only — [render] is the only reader and the only
     * writer — so plain fields, not volatiles.
     */
    private var loggedShiftUs: Long = 0L
    private var heldShiftUs: Long = 0L
    private var heldSinceUs: Long = 0L

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        val shiftUs = shift.videoShiftUs
        // The command's log line is written before the player is touched at all,
        // so it says a frame arrived and nothing more. This one is the other half:
        // it is written from the thread that releases frames, by the renderer that
        // is actually in the array, so the two together separate "the TV accepted
        // the nudge" from "the nudge reached the picture".
        //
        // Reported once the value has STOPPED moving, not once it differs. The
        // original wording of this comment assumed a user step changes the shift a
        // few times a second, and a stepper press does — but the phone walks a drag
        // to the target 25 times a second for as long as 1.6 s precisely so the
        // picture is not skipped, and one line per hop turns one gesture into forty
        // and evicts the cast from a 200-entry ring. Where the nudge ENDED is the
        // answer a reader wants; every tick before that compares and returns.
        if (shiftUs != heldShiftUs) {
            heldShiftUs = shiftUs
            heldSinceUs = elapsedRealtimeUs
        } else if (shiftUs != loggedShiftUs &&
            audioShiftSettled(nowUs = elapsedRealtimeUs, heldSinceUs = heldSinceUs)
        ) {
            loggedShiftUs = shiftUs
            FlickLog.i("player", "audioDelayShift us=$shiftUs")
        }
        super.render(positionUs + shiftUs, elapsedRealtimeUs)
    }

    override fun getDurationToProgressUs(positionUs: Long, elapsedRealtimeUs: Long): Long =
        super.getDurationToProgressUs(positionUs + shift.videoShiftUs, elapsedRealtimeUs)
}
