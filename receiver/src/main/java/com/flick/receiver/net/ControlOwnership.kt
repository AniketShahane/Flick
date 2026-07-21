package com.flick.receiver.net

/**
 * Session-level ownership policy. A lease is compared on every mutation so a
 * closing superseded socket cannot clear a newer controller or cast.
 */
class ControlOwnership {
    data class Adoption(val displaced: Any?)
    enum class CastAdoption { NEW, DUPLICATE, STALE_LEASE }

    private var controller: Any? = null
    private var controllerGeneration = 0L
    private var activeCastId: String? = null

    @Synchronized fun adoptIdleConnection(token: Any, generation: Long): Adoption? {
        if (activeCastId != null) return null
        val displaced = controller
        controller = token
        controllerGeneration = generation
        return Adoption(displaced)
    }

    @Synchronized fun adoptCast(token: Any, generation: Long, castId: String): CastAdoption {
        if (!owns(token, generation)) return CastAdoption.STALE_LEASE
        return if (activeCastId == castId) CastAdoption.DUPLICATE else {
            activeCastId = castId
            CastAdoption.NEW
        }
    }

    @Synchronized fun clearCast(token: Any, generation: Long, castId: String): Boolean {
        if (!owns(token, generation) || activeCastId != castId) return false
        activeCastId = null
        return true
    }

    @Synchronized fun release(token: Any, generation: Long): Boolean {
        if (!owns(token, generation)) return false
        controller = null
        controllerGeneration = 0L
        activeCastId = null
        return true
    }

    @Synchronized fun invalidate(): Long? {
        val result = controllerGeneration.takeIf { controller != null }
        controller = null
        controllerGeneration = 0L
        activeCastId = null
        return result
    }

    @Synchronized fun isCurrent(token: Any, generation: Long, castId: String) =
        owns(token, generation) && activeCastId == castId

    @Synchronized fun currentCast(token: Any, generation: Long) =
        if (owns(token, generation)) activeCastId else null

    @Synchronized fun isBusy() = activeCastId != null

    private fun owns(token: Any, generation: Long) = controller === token && controllerGeneration == generation
}
