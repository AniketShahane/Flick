package com.flick.receiver.player

import androidx.media3.common.Player

/**
 * Whether a turned picture is still being fed frames.
 *
 * `recoverFromTurnFailure` only ever hears about a turn that raised a
 * [androidx.media3.common.PlaybackException]. A picture that simply stops moving
 * raises nothing at all, and the viewer is then left looking at a dead frame with
 * a healthy player behind it — audio running, position advancing, no error to
 * classify and nothing to recover from. This is the other half of that net: a turn
 * whose picture goes a whole deadline without a frame is treated as exactly the
 * same event as one that threw.
 *
 * ## Why this watches for the whole film and not just the engagement
 *
 * The version of this that watched only the engagement took the first rendered
 * frame as proof and cancelled itself forever. On the verified hardware that is
 * precisely how the worst failure hid: one frame reached the panel, the deadline
 * was cancelled on the strength of it, and the picture then froze for the rest of
 * the film with nothing left watching. So there is no "proved" state here. The
 * deadline re-arms after every verdict, and each one asks the same question about
 * the window that has just passed.
 *
 * ## What the frame signal can and cannot see
 *
 * [onFrameRendered] is driven by `setVideoFrameMetadataListener`, which
 * `MediaCodecVideoRenderer` calls immediately before it releases a buffer to the
 * output surface. That proves the decoder is producing and releasing frames; it
 * does not prove the compositor drew them. A surface that accepts frames and puts
 * none of them on screen is therefore outside what this can catch, and is left to
 * the errors media3 does raise. What it does catch is the failure that has
 * actually happened here twice: frames stopping.
 *
 * ## Threading
 *
 * [lastFrameAtMs] is one volatile write per frame from the playback thread and
 * nothing else — no comparison, no branch, no main-thread post — and the listener
 * is installed only while a turn is in force, so an untouched cast never reaches
 * this class at all. Everything else is main-thread only.
 *
 * The generation is what stops a deadline posted for one engagement from judging
 * a later one. A frame does NOT carry a generation: a late frame from a surface
 * that has just been replaced can only postpone a verdict by one window, and the
 * window after it asks again — which is the whole benefit of a watchdog that
 * never stops watching.
 */
internal class TurnWatchdog {

    /** What a deadline that has come due is worth acting on. */
    enum class Verdict {
        /** A later engagement replaced this one, or nothing is engaged at all. */
        Stale,

        /**
         * A picture nobody asked to move is no evidence about the turn. The
         * window restarts and the caller re-posts the deadline.
         */
        NotYet,

        /** Frames arrived during the window. Re-posted, and asked again later. */
        Alive,

        /** The turn was in force, frames were owed, and none arrived. */
        NoFrames,
    }

    /** The engagement a deadline can be about, or [NOT_ENGAGED]. Main thread writes. */
    @Volatile
    private var engagedGeneration: Long = NOT_ENGAGED

    /** Playback thread writes, main thread reads. [NO_FRAME] until the first one. */
    @Volatile
    private var lastFrameAtMs: Long = NO_FRAME

    private var generation: Long = NOT_ENGAGED

    /** The start of the stretch the next verdict is about. Main-thread only. */
    private var windowOpenedAtMs: Long = 0L

    val isEngaged: Boolean get() = engagedGeneration != NOT_ENGAGED

    /**
     * Whether this engagement has ever put a frame out. It is what separates the
     * two questions a deadline can be asking — see [framesExpectedFrom].
     */
    val hasRenderedAFrame: Boolean get() = lastFrameAtMs != NO_FRAME

    /**
     * Start watching a turn, replacing any engagement already being watched.
     * Returns the generation its deadline must carry, so a deadline posted for a
     * surface that has since been swapped cannot consume the new one's.
     */
    fun engage(nowMs: Long): Long {
        generation++
        lastFrameAtMs = NO_FRAME
        windowOpenedAtMs = nowMs
        engagedGeneration = generation
        return generation
    }

    /** Playback thread. One volatile write, and deliberately nothing else. */
    fun onFrameRendered(atMs: Long) {
        lastFrameAtMs = atMs
    }

    fun disengage() {
        engagedGeneration = NOT_ENGAGED
    }

    /**
     * [renderingExpected] is [framesExpectedFrom]'s answer: a film the viewer
     * paused, one that has run out, and — once the turn has shown it can render at
     * all — one that is refilling its buffer are none of them evidence, and
     * spending the verdict on any of them would cost the viewer the turn for the
     * rest of the film.
     */
    fun consumeDeadline(generation: Long, nowMs: Long, renderingExpected: Boolean): Verdict {
        if (generation == NOT_ENGAGED || engagedGeneration != generation) return Verdict.Stale
        if (!renderingExpected) {
            // The window restarts rather than carrying the quiet stretch forward:
            // a film resumed after an hour paused owes its first frame from now.
            windowOpenedAtMs = nowMs
            return Verdict.NotYet
        }
        val lastFrame = lastFrameAtMs
        if (lastFrame != NO_FRAME && lastFrame >= windowOpenedAtMs) {
            windowOpenedAtMs = nowMs
            return Verdict.Alive
        }
        engagedGeneration = NOT_ENGAGED
        return Verdict.NoFrames
    }

    companion object {
        /** No turn is being watched. Never a real generation. */
        const val NOT_ENGAGED = 0L

        /** Outside every elapsed-realtime reading, so a real frame always wins. */
        private const val NO_FRAME = Long.MIN_VALUE
    }
}

/**
 * Whether a working turn would be putting frames on the panel right now.
 *
 * `playWhenReady` alone is not the question. A film that has run to the end keeps
 * it set and renders nothing ever again, so a deadline landing there would condemn
 * a turn that spent the whole film working and hand the viewer a re-prepare of a
 * film they have finished.
 *
 * [provenOnce] splits the two genuinely different questions a deadline asks, and
 * the split is BUFFERING:
 *
 *  - **Before any frame.** A turn that has never rendered may have wedged the
 *    pipeline, and a wedged pipeline leaves the video renderer un-ready — the same
 *    state a genuine rebuffer produces. Excusing it here would excuse the failure
 *    this exists to catch, so it is not excused.
 *  - **After a frame.** The turn has demonstrably reached the panel, so an empty
 *    window means either the picture died or the LAN did. The receiver's own
 *    retry policy rides out roughly 100 s of network trouble, which is many times
 *    this deadline, and condemning a turn because the Wi-Fi stalled would take the
 *    picture off a viewer who is already watching a spinner. So BUFFERING is
 *    excused, and what is still caught — the failure actually reported here — is a
 *    frozen picture over a player that says it is READY and playing.
 */
internal fun framesExpectedFrom(
    playWhenReady: Boolean,
    playbackState: Int,
    provenOnce: Boolean,
): Boolean {
    if (!playWhenReady) return false
    return when (playbackState) {
        Player.STATE_READY -> true
        Player.STATE_BUFFERING -> !provenOnce
        // IDLE has no pipeline at all and ENDED will never render again.
        else -> false
    }
}
