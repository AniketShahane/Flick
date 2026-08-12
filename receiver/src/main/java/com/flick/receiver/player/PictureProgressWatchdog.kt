package com.flick.receiver.player

/** What a stalled rendered-frame counter has earned. */
enum class PictureVerdict { HEALTHY, REPREPARE, ANNOUNCE }

/**
 * A frozen counter across several samples while the player says READY and playing is
 * proof the renderer stopped releasing buffers. It is NOT proof of the cause — a wedged
 * codec and a lost surface look identical from here, as [TurnWatchdog]'s doc concedes —
 * so the copy this drives claims only what stopped.
 *
 * It exists because the per-frame heartbeat is installed only while a turn is in force,
 * so the app's single most-hidden failure was watched only on its rarest path. Media3
 * cannot cover it either: `stuckBufferingDetectionTimeoutMs` defaults to 600 000 ms and
 * stuck-playing detection is off unless a static experimental flag is set, which this app
 * never sets. The evidence has been sampled twice a second all along —
 * [DiagnosticsSnapshot.renderedFrames] — and drawn only in the opt-in dev overlay.
 *
 * One silent re-prepare comes first, mirroring the turn fallback: only announce after a
 * re-prepare has failed to restore the counter, so a blip never puts a terminal screen
 * over a film that recovers.
 *
 * [hasRenderedAFrame] and [framesExpected] are mandatory guards, not refinements. Without
 * the first, a film with no video track at all fires a terminal screen: [videoTrackShortfall]
 * returns null when a container declares no video group, so an audio-only file plays with a
 * counter frozen at zero because there was never a picture to stop. It must be LATCHED for
 * the film rather than read off the live counter — a re-prepare reallocates the decoder's
 * counters, so a counter read after one says "never rendered" about a film that has.
 *
 * [positionAdvancing] is no longer a guard but a grade. A pipeline where the clock has
 * frozen too — an audio track whose playback head stalls without raising a write error —
 * stays READY and playing forever with nothing to watch it, which was the last remaining
 * frozen-picture mode nothing covered. It is judged on [wedgedThreshold], double the
 * ordinary one, so ordinary sampling jitter around a seek can never reach it; what
 * `positionAdvancing` was protecting — a paused player, an ended stream, a film with no
 * picture — is what [ready], [playing] and [hasRenderedAFrame] already exclude.
 */
fun pictureVerdict(
    frozenSamples: Int,
    ready: Boolean,
    playing: Boolean,
    positionAdvancing: Boolean,
    hasRenderedAFrame: Boolean,
    framesExpected: Boolean,
    rePrepared: Boolean,
    threshold: Int = PICTURE_FROZEN_SAMPLES,
    wedgedThreshold: Int = PICTURE_WEDGED_SAMPLES,
): PictureVerdict = when {
    !ready || !playing || !hasRenderedAFrame || !framesExpected -> PictureVerdict.HEALTHY
    frozenSamples < if (positionAdvancing) threshold else wedgedThreshold -> PictureVerdict.HEALTHY
    !rePrepared -> PictureVerdict.REPREPARE
    else -> PictureVerdict.ANNOUNCE
}

/**
 * Whether a stopped counter can be read as a fault at all.
 *
 * A frozen counter is evidence only where more frames were due. A film whose video track
 * is a single still frame under a full-length audio track is a perfectly legal file — a
 * music rip is the common shape — and it has exactly the signature of a wedged decoder:
 * one buffer released, then nothing, while the clock runs on the audio track. The only
 * evidence of expectation available this side of the decoder is the pace the container
 * declares, and an extractor derives that from sample count over duration, so a track
 * with one sample carries no rate at all.
 *
 * The cost is a blind spot, never a false accusation: a film whose container declares no
 * usable frame rate is not judged. That is the right way round for the one watchdog that
 * can end a cast.
 */
fun framesAreExpected(declaredFrameRate: Float): Boolean =
    declaredFrameRate.isFinite() && declaredFrameRate >= MIN_JUDGED_FRAME_RATE

/**
 * Six seconds at the existing 500 ms snapshot cadence.
 *
 * Sized against the same asymmetry [PlayerController.TURN_DEADLINE_MS] is: firing early
 * costs a viewer a re-prepare of a film that was fine, and a decoder that has released no
 * buffer for six seconds while the clock keeps running is not a film that is fine.
 */
const val PICTURE_FROZEN_SAMPLES: Int = 12

/**
 * Twelve seconds, for the case where the clock is frozen with the counter.
 *
 * Double, because the evidence is weaker: a position that does not move between two
 * samples is also what a seek, a masked clock and a rounding of the audio timestamp look
 * like, and none of those survives twelve seconds of a film that says it is playing.
 */
const val PICTURE_WEDGED_SAMPLES: Int = PICTURE_FROZEN_SAMPLES * 2

/**
 * The slowest declared pace a frozen counter may be judged against. Anything below one
 * frame a second is a slideshow or a still, where six seconds of no new frame is the
 * file rather than the fault.
 */
const val MIN_JUDGED_FRAME_RATE: Float = 1f
