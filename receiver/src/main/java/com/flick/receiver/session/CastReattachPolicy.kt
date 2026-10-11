package com.flick.receiver.session

import com.flick.receiver.net.ControlLossReason

/**
 * A playing cast whose control socket died, held for the phone that owned it.
 *
 * [keyId] is null only when the loss could not name that phone, and then nothing can
 * re-adopt it: the hold just runs out into today's face.
 */
internal data class OrphanedCast(
    val keyId: String?,
    val castId: String,
    val url: String,
    val deadlineElapsedMs: Long,
)

/**
 * When a cast outlives the control socket it was started on, and who may pick it up.
 *
 * Pure, because the cases that decide it — the other phone, the same phone with a
 * different film, a frame one millisecond late — are the ones a TV on a real network
 * cannot be asked to reproduce.
 */
internal object CastReattachPolicy {
    /**
     * How long a playing cast waits for its phone. Above the phone's own 40 s reattach
     * window, so its last attempt still lands inside this one.
     */
    const val ORPHAN_GRACE_MS = 45_000L

    /**
     * Only a socket that simply stopped answering, under a film that had started, on a
     * TV still in front. Everything this TV does on purpose — a stop, a Forget, its own
     * server going down, another cast — keeps the teardown it always had, and so does a
     * loss before the first frame: there is nothing playing to keep.
     */
    fun entersGrace(reason: ControlLossReason, active: Boolean, foreground: Boolean): Boolean =
        reason == ControlLossReason.DROPPED && active && foreground

    /**
     * The same phone, the same cast and the same bytes, inside the window. The URL is
     * part of it because a phone whose own address moved is serving from somewhere
     * else, and the running player could never reach it again.
     */
    fun readopts(orphan: OrphanedCast?, keyId: String, castId: String, url: String, nowElapsedMs: Long): Boolean =
        orphan != null &&
            orphan.keyId != null &&
            orphan.keyId == keyId &&
            orphan.castId == castId &&
            orphan.url == url &&
            nowElapsedMs < orphan.deadlineElapsedMs

    /**
     * The other route back: the phone's new socket took the lease over before the old
     * one was ever seen to die, so nothing was orphaned and the lease has already moved.
     * Only a film that is actually playing is answered as kept; one still starting gets
     * its `loadReady` from its own first frame.
     */
    fun continues(liveCastId: String?, liveUrl: String?, active: Boolean, castId: String, url: String): Boolean =
        active && liveCastId == castId && liveUrl == url
}
