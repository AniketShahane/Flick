package com.flick.receiver.player

/** Private MediaItem tag; never changes the cast mediaId or wire contract. */
internal data class SubtitleReloadAttemptTag(val token: Long)

/**
 * Generation gate for the short interval in which a live player is re-prepared
 * with a sideloaded subtitle. The Android timer stays in [PlayerController];
 * keeping the state transition pure makes stale callbacks and deadlines cheap to
 * exercise without a decoder.
 */
internal class SubtitleReloadWatchdog {
    private data class Attempt(
        val token: Long,
        val mediaId: String,
        var observedReloading: Boolean,
        var readyAfterReload: Boolean = false,
        var presentedAfterReload: Boolean = false,
        var subtitleLoaded: Boolean = false,
    )

    private var attempt: Attempt? = null

    fun arm(
        token: Long,
        mediaId: String,
        alreadyReloading: Boolean,
    ) {
        attempt = Attempt(token, mediaId, alreadyReloading)
    }

    /** Returns true exactly once when the current attempt becomes healthy. */
    fun onPlaybackState(token: Long?, mediaId: String?, ready: Boolean): Boolean {
        val current = attempt?.takeIf { it.token == token && it.mediaId == mediaId }
            ?: return false
        if (!ready) {
            current.observedReloading = true
            current.readyAfterReload = false
            return false
        }
        if (!current.observedReloading) return false
        current.readyAfterReload = true
        return completeIfHealthy(current)
    }

    /** Returns true exactly once when the current attempt becomes healthy. */
    fun onPresented(token: Long?, mediaId: String?): Boolean {
        val current = attempt?.takeIf { it.token == token && it.mediaId == mediaId }
            ?: return false
        if (!current.observedReloading) return false
        current.presentedAfterReload = true
        return completeIfHealthy(current)
    }

    /** A merged source is healthy only after its optional text source completed. */
    fun onSubtitleLoaded(token: Long?, mediaId: String?): Boolean {
        val current = attempt?.takeIf { it.token == token && it.mediaId == mediaId }
            ?: return false
        current.subtitleLoaded = true
        return completeIfHealthy(current)
    }

    /** True only for the still-current attempt; consuming it makes rollback one-shot. */
    fun consumeDeadline(token: Long, mediaId: String?): Boolean {
        val current = attempt?.takeIf { it.token == token && it.mediaId == mediaId }
            ?: return false
        attempt = null
        return true
    }

    fun cancel() {
        attempt = null
    }

    private fun completeIfHealthy(current: Attempt): Boolean {
        val healthy = current.readyAfterReload &&
            current.presentedAfterReload &&
            current.subtitleLoaded
        if (healthy) attempt = null
        return healthy
    }
}

/** One-shot attribution state shared by explicit-error and watchdog rollback. */
/**
 * Whether a load event is the external subtitle currently attached — the question that
 * decides whether a failure is the SUBTITLE's or the FILM's.
 *
 * The URL and nothing else. It carried a second clause once: the load also had to belong to
 * the reload attempt still pending. That clause was true for the whole of a cast that began
 * with its subtitle already attached — no attempt, nothing pending, `null` matching `null` —
 * and it went false forever the moment a subtitle attached MID-CAST finished loading, because
 * settling a reload clears the pending token while the media item keeps the tag naming it.
 * From then on every later failure of that subtitle was read as a failure of the film.
 *
 * What that cost is the only thing standing between a bad subtitle and a dead cast: the
 * receiver survives one by ATTRIBUTING it and dropping the text track, and an unattributed
 * subtitle error is just a playback error, which on a live cast is terminal by design. So the
 * net was disarmed on exactly the path where a subtitle is likeliest to fail late — a
 * downloaded one, whose file can be evicted from the cache underneath a cast still reading it.
 *
 * Deciding it here, on values, rather than inside the controller: the drift above happened in
 * a method no test could reach, and every token this once compared is still checked by
 * [SubtitleReloadWatchdog] itself, which is the place that genuinely needs to know which
 * attempt it is looking at.
 */
internal fun isExternalSubtitleLoad(
    currentSubtitleUrl: String?,
    dataSpecUri: String,
    eventUri: String,
): Boolean {
    val attached = currentSubtitleUrl ?: return false
    return dataSpecUri == attached || eventUri == attached
}

internal class ExternalSubtitleFailureState {
    private var loadFailed = false
    private var dropped = false

    fun reset() {
        loadFailed = false
        dropped = false
    }

    fun recordLoadFailure() {
        loadFailed = true
    }

    fun recordLoadSuccess() {
        loadFailed = false
    }

    fun canRollback(hasSubtitle: Boolean): Boolean = hasSubtitle && !dropped

    fun shouldRollbackAfterPlayerError(hasSubtitle: Boolean): Boolean =
        canRollback(hasSubtitle) && loadFailed

    fun recordRollback() {
        dropped = true
        loadFailed = false
    }
}
