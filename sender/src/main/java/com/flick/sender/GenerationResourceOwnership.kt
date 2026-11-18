package com.flick.sender

/**
 * Records which generation created the process-wide socket and locks. Callers hold
 * their resource mutex while using it; releasing a stale generation can therefore
 * never close resources already claimed by its successor.
 */
internal class GenerationResourceOwnership {
    private var serverOwner: CastGeneration? = null
    private var locksOwner: CastGeneration? = null

    fun claimServer(session: CastGeneration) {
        serverOwner = session
    }

    fun claimLocks(session: CastGeneration) {
        locksOwner = session
    }

    fun ownsLocks(session: CastGeneration): Boolean = locksOwner == session

    /** The generation holding the locks now, if any. */
    fun locksHolder(): CastGeneration? = locksOwner

    fun release(session: CastGeneration): Release {
        val result = Release(
            locks = locksOwner == session,
            server = serverOwner == session,
        )
        if (result.locks) locksOwner = null
        if (result.server) serverOwner = null
        return result
    }

    fun releaseAll(): Release {
        val result = Release(locks = locksOwner != null, server = serverOwner != null)
        locksOwner = null
        serverOwner = null
        return result
    }

    data class Release(val locks: Boolean, val server: Boolean)
}

/**
 * How long the cast's CPU lock lives. Each acquire carries [TIMEOUT_MS] and is renewed every
 * [RENEW_MS], but only while a video byte has been served within [ACTIVITY_WINDOW_MS]: a cast
 * left paused or finished, which nothing on the phone ends, lets the lock lapse at most
 * [ACTIVITY_WINDOW_MS] + [TIMEOUT_MS] after its last served byte, while a film or a marathon
 * that keeps serving keeps it. Bytes served after a lapse re-acquire it. All stamps are on
 * the monotonic clock; the cast's start counts as activity.
 */
internal object WakeLockCeiling {
    const val TIMEOUT_MS = 2L * 60L * 60L * 1000L

    /** Half the timeout, so one missed or late renewal still leaves the lock held. */
    const val RENEW_MS = 60L * 60L * 1000L

    const val ACTIVITY_WINDOW_MS = 4L * 60L * 60L * 1000L

    /** Whether a renewal due at [nowMs] re-arms the lock; false lets it lapse on its timeout. */
    fun renews(lastActivityAtMs: Long, nowMs: Long): Boolean = nowMs - lastActivityAtMs < ACTIVITY_WINDOW_MS

    /**
     * Whether bytes served now must re-acquire the lock: a generation still owns the locks
     * ([owned]) and no renewal is running for it ([renewing]), so the lock has lapsed or
     * will on its timeout with nothing to re-arm it.
     */
    fun reacquires(owned: Boolean, renewing: Boolean): Boolean = owned && !renewing
}
