package com.flick.receiver.player

/**
 * The longest a starting film waits at its first frame for the house lights to release
 * the picture. Past it the film plays regardless, so a picture-up that never comes —
 * a composition that is not drawing, a seam cancelled under it — cannot hang playback.
 * It sits a second past the stage's own cap (`STAGE_HOLD_CAP_MS`, 2.5 s from the seam),
 * which already bounds every wait before picture-up, so it only fires on a path where
 * picture-up was lost; firing early would start the film under the veil.
 */
internal const val PICTURE_HOLD_CAP_MS = 3_500L

/**
 * Whether a held film may start: at picture-up, or once [PICTURE_HOLD_CAP_MS] has run
 * since its first frame. A film with no first frame yet has nothing to release.
 */
internal fun playbackMayStart(pictureUp: Boolean, firstFrameAtMs: Long?, nowMs: Long): Boolean =
    pictureUp || (firstFrameAtMs != null && nowMs - firstFrameAtMs >= PICTURE_HOLD_CAP_MS)

/**
 * A starting film held at its decoded first frame until the picture is released.
 *
 * The card holds the screen for a minimum time after the first frame, and a film that
 * played through that would lose its opening seconds of sound and picture under the
 * veil. Held paused instead, the film's clock does not move until the picture is up, so
 * the viewer sees and hears it from the exact start position.
 *
 * While held, the viewer's play/pause is recorded rather than applied, so a pause pressed
 * during the card is honoured at release and a play is not applied early. Pure state:
 * [PlayerController] applies the answers to the player.
 */
internal class PictureHold {
    var holding: Boolean = false
        private set

    /** What the viewer wants once the picture is up. */
    var wanted: Boolean = true
        private set

    var firstFrameAtMs: Long? = null
        private set

    fun arm() {
        holding = true
        wanted = true
        firstFrameAtMs = null
    }

    fun onFirstFrame(nowMs: Long) {
        if (holding && firstFrameAtMs == null) firstFrameAtMs = nowMs
    }

    /**
     * The `playWhenReady` a request for [play] may put on the player now. While held the
     * request is remembered and the player stays paused.
     */
    fun request(play: Boolean): Boolean {
        if (!holding) return play
        wanted = play
        return false
    }

    /**
     * The `playWhenReady` a recovery or re-prepare that would otherwise resume [play]
     * may apply. Unlike [request] it is not the viewer's word, so it records nothing.
     */
    fun gate(play: Boolean): Boolean = play && !holding

    /** Ends the hold. Returns the `playWhenReady` to apply, or null when nothing was held. */
    fun release(): Boolean? {
        if (!holding) return null
        holding = false
        firstFrameAtMs = null
        return wanted
    }

    fun clear() {
        holding = false
        wanted = true
        firstFrameAtMs = null
    }
}
