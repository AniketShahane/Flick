package com.flick.sender.net

/**
 * When a playing cast checks that the TV still hears this phone.
 *
 * The first check lands after the phone's driver has had time to set up a direct link
 * under the film's opening burst (measured starting 1–2 s in and failing about 5 s
 * later); the period keeps a link that fails later from outlasting the TV's buffer.
 * One ping per check, and none while one is unanswered, so it stays inside
 * [ControlLiveness.PINGS_PER_WINDOW] alongside a replacing cast's own check.
 */
internal object DirectLinkWatchPolicy {
    /** After the first frame. */
    const val FIRST_CHECK_MS = 8_000L

    /** Between the end of one check and the start of the next. */
    const val PERIOD_MS = 10_000L

    /** Longer than the receiver's 4 s main-thread adoption of a mid-film `loadMedia`. */
    const val UNANSWERED_PROOF_MS = 6_000L
}
