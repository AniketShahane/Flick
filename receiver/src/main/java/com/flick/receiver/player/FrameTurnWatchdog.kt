package com.flick.receiver.player

import androidx.media3.common.Player

/**
 * Whether an engagement of media3's effects graph has proved it can still put a
 * frame on the panel.
 *
 * `recoverFromFrameTurnFailure` only ever hears about a graph that raised a
 * [androidx.media3.common.PlaybackException]. A graph that simply stops producing
 * frames raises nothing at all, and the viewer is then left looking at a dead
 * picture with a healthy player behind it — audio running, position advancing,
 * no error to classify and nothing to recover from. This is the other half of
 * that net: an engagement that cannot show a frame within its deadline is treated
 * as exactly the same event as one that threw.
 *
 * The Android timer, the frame callback and the fallback all live in
 * [PlayerController]. What is here is the generation bookkeeping and the two
 * judgements a deadline needs — what a rendered frame is worth, and what a
 * deadline that has come due is worth — because both decide the viewer's turn for
 * the rest of a film and must be cheap to exercise without a decoder, a surface
 * or a GL context.
 *
 * ## Threading
 *
 * [armedGeneration] is the one field the playback thread touches, through
 * [isArmed] and [evidenceGenerationFor], so that the overwhelmingly common case —
 * a graph that is working, on a film with no engagement outstanding — costs one
 * volatile read per frame and no main-thread post. Everything else is main-thread
 * only.
 */
internal class FrameTurnWatchdog {

    /**
     * How the graph came to be carrying the turn under test, which is what
     * decides whether a rendered frame says anything about it.
     */
    enum class Engagement {
        /**
         * A new ExoPlayer instance, built with the graph before its first
         * `prepare`. Nothing can reach the panel except through that graph, so
         * the instance's first frame is proof outright.
         */
        NewGraph,

        /**
         * A LIVE graph handed a different effect list.
         *
         * `ExoPlayerImpl.setVideoEffects` does its lib-effect reflection check
         * and then `sendRendererMessage`, so it returns while the swap is still a
         * queued renderer message; `MediaCodecVideoRenderer.handleMessage` hands
         * it to `InputVideoSink.setVideoEffects`, which re-registers the input
         * stream on the playback thread. Frames already inside the GL pipeline at
         * that moment keep draining under the PREVIOUS effect list, and
         * `DefaultVideoSink$FrameRendererImpl.renderFrame` calls the metadata
         * listener for every one of them — so the very next frame, at most a
         * frame interval away on a playing film, would otherwise prove a
         * re-registration that never took. Only a frame from after that drain
         * counts; see [arm].
         */
        LiveSwap,
    }

    /** What a deadline that has come due is worth acting on. */
    enum class Verdict {
        /** A later engagement replaced this one, or the graph already proved itself. */
        Stale,

        /**
         * A picture nobody asked to move is no evidence about the graph. The
         * engagement stays armed and the caller re-posts the deadline.
         */
        NotYet,

        /** The graph was engaged, frames were asked for, and none arrived. */
        NoFrames,
    }

    /**
     * The engagement a frame would be evidence about, or [NOT_ARMED]. Read by the
     * playback thread; written only here, on the main thread.
     */
    @Volatile
    private var armedGeneration: Long = NOT_ARMED

    /**
     * The earliest a rendered frame can be evidence about the armed engagement.
     * Written BEFORE [armedGeneration], so a playback thread that sees the arm at
     * all sees the threshold that goes with it.
     */
    @Volatile
    private var proofNotBeforeMs: Long = 0L

    private var generation: Long = NOT_ARMED

    /** True while a deadline is outstanding. */
    val isArmed: Boolean get() = armedGeneration != NOT_ARMED

    /**
     * Start watching a new engagement, replacing any outstanding one. Returns the
     * generation its deadline must carry, so a deadline posted for a graph that
     * has since been rebuilt cannot consume the new one's.
     *
     * [swapSettleMs] is how long an [Engagement.LiveSwap] waits before a frame
     * means anything, and it is a bound on the pre-swap drain rather than a guess:
     * media3 lets at most `Util.getMaxPendingFramesCountForMediaCodecDecoders`
     * frames — 5, or 1 where surface-input frame drop is allowed — sit inside the
     * graph, and `FinalShaderProgramWrapper.SURFACE_INPUT_CAPACITY` is 1, so the
     * old effect list can have about half a dozen frames still to render. Every
     * one of them is released at its own presentation time, which on a playing
     * film is real time, so they are gone within a handful of frame intervals.
     * A film that is NOT playing drains none of them, and one that renders after
     * the wait would then be counted wrongly — but that direction only misses a
     * failure, and this deadline is sized so that missing one is the cheap error
     * and calling a working graph dead is not.
     */
    fun arm(engagement: Engagement, nowMs: Long, swapSettleMs: Long): Long {
        generation++
        proofNotBeforeMs = if (engagement == Engagement.LiveSwap) nowMs + swapSettleMs else nowMs
        armedGeneration = generation
        return generation
    }

    /**
     * The engagement a frame rendered at [atMs] is evidence about, or [NOT_ARMED]
     * when it is evidence about nothing.
     *
     * Playback thread. The generation travels with the caller's post so a frame
     * from an engagement that has since been replaced cannot clear the new one,
     * which has had no time at all.
     */
    fun evidenceGenerationFor(atMs: Long): Long {
        val armed = armedGeneration
        if (armed == NOT_ARMED) return NOT_ARMED
        return if (atMs >= proofNotBeforeMs) armed else NOT_ARMED
    }

    /**
     * A qualifying frame reached the panel — see [evidenceGenerationFor], which is
     * the only honest source of [generation]. True exactly once per engagement.
     */
    fun onFrameRendered(generation: Long): Boolean {
        if (generation == NOT_ARMED || armedGeneration != generation) return false
        armedGeneration = NOT_ARMED
        return true
    }

    fun cancel() {
        armedGeneration = NOT_ARMED
    }

    /**
     * [renderingExpected] is [framesExpectedFrom]'s answer: a film the viewer
     * paused, or one that has run out, is not evidence about the graph, and
     * spending the deadline on it would cost them the turn for the rest of the
     * film.
     */
    fun consumeDeadline(generation: Long, renderingExpected: Boolean): Verdict {
        if (generation == NOT_ARMED || armedGeneration != generation) return Verdict.Stale
        if (!renderingExpected) return Verdict.NotYet
        armedGeneration = NOT_ARMED
        return Verdict.NoFrames
    }

    companion object {
        /** No engagement is being watched. Never a real generation. */
        const val NOT_ARMED = 0L
    }
}

/**
 * Whether a working graph would be putting frames on the panel right now.
 *
 * `playWhenReady` alone is not the question. A film that has run to the end keeps
 * it set and renders nothing ever again, so a deadline landing there would
 * condemn a graph that spent the whole film working and hand the viewer a rebuild
 * of a film they have finished. BUFFERING is deliberately NOT excused: a graph
 * that has stopped consuming frames leaves the video renderer un-ready, which is
 * the same state a genuine rebuffer produces, so excusing it would excuse the
 * failure this deadline exists to catch.
 */
internal fun framesExpectedFrom(playWhenReady: Boolean, playbackState: Int): Boolean =
    playWhenReady && playbackState != Player.STATE_ENDED
