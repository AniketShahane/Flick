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
