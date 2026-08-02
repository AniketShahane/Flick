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
