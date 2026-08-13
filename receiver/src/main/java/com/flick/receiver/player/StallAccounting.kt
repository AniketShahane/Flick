package com.flick.receiver.player

/**
 * Which stretches of buffering count as a stall, and which are the viewer's own doing.
 *
 * This app's whole claim is that a direct-played file never stalls, so the stall counter
 * is the number that claim is judged on. It reaches the viewer as amber "N stalls" on the
 * metrics overlay, flips [DiagnosticsSnapshot.status] from PASS to WARN for the rest of
 * the cast, and is the reason `adb logcat -s FlickTV:W` is usable as a stall filter at
 * all. A number carrying that much weight must not count a button the viewer pressed.
 *
 * Three windows are therefore not stalls, and none of them is a judgement call:
 *  - before playback ever started, the initial fill IS the startup, measured separately;
 *  - a seek's refill, which the viewer asked for by moving the playhead;
 *  - an in-place reload's refill. Attaching or removing a sideloaded subtitle re-prepares
 *    the source (Media3 requires a new MediaItem for a sideloaded track), which buffers
 *    for about a second on a 4K file. Counting it reported a working subtitle feature as
 *    the one failure this app exists to prevent — measured on a real TV at three toggles,
 *    three "stalls", 1091/1257/1203 ms, against zero genuine network stalls in the
 *    same session.
 *
 * A window already open is not reopened: the count is of stall EPISODES, and buffering
 * that flutters mid-episode is one stall, not several.
 */
object StallAccounting {

    /**
     * True when a STATE_BUFFERING transition should open a new stall episode.
     *
     * [seekFillOpen] and [reloadFillOpen] are kept apart by their callers rather than
     * merged here, because closing them writes to different places — a seek's duration
     * is reported as seek responsiveness, and a reload's is reported nowhere.
     */
    fun opensStall(
        playbackStarted: Boolean,
        stallOpen: Boolean,
        seekFillOpen: Boolean,
        reloadFillOpen: Boolean,
    ): Boolean = playbackStarted && !stallOpen && !seekFillOpen && !reloadFillOpen
}
