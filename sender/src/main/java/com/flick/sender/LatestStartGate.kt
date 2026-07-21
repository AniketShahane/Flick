package com.flick.sender

/** A cast identity paired with the service generation that owns its resources. */
internal data class CastGeneration(val castId: String, val value: Long)

/** Thread-safe logical generation for ACTION_START/ACTION_STOP ordering. */
internal class LatestStartGate {
    private var generation = 0L
    private var active: CastGeneration? = null

    @Synchronized
    fun begin(castId: String): CastGeneration {
        val next = CastGeneration(castId, ++generation)
        active = next
        return next
    }

    @Synchronized
    fun isLatest(session: CastGeneration): Boolean = active == session

    /**
     * Runs a short publication atomically with the latest-generation check. This
     * prevents a superseding ACTION_START from interleaving between the final check
     * and publishing RUNNING.
     */
    @Synchronized
    fun runIfLatest(session: CastGeneration, action: () -> Unit): Boolean {
        if (active != session) return false
        action()
        return true
    }

    /** Invalidates and returns the active generation only when [castId] still owns it. */
    @Synchronized
    fun stop(castId: String): CastGeneration? {
        val current = active ?: return null
        if (current.castId != castId) return null
        active = null
        ++generation
        return current
    }

    /** Invalidates [session] only if it is still current, for generation-scoped failure. */
    @Synchronized
    fun invalidateIfLatest(session: CastGeneration): Boolean {
        if (active != session) return false
        active = null
        ++generation
        return true
    }

    @Synchronized
    fun current(): CastGeneration? = active

    @Synchronized
    fun clear(): CastGeneration? {
        val current = active
        active = null
        ++generation
        return current
    }
}
