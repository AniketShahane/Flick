package com.flick.receiver.session

/** Narrow, deterministic A/B ownership gate for delayed probe/player callbacks. */
class CastGenerationGate {
    private var next = 0L
    var castId: String? = null; private set
    var generation: Long = 0L; private set
    var controlLeaseGeneration: Long? = null; private set
    fun adopt(id: String, controlLeaseGeneration: Long? = null): Long {
        castId = id
        this.controlLeaseGeneration = controlLeaseGeneration
        generation = ++next
        return generation
    }
    /**
     * Moves the running cast to another control lease WITHOUT a new generation: the
     * player's media id is derived from the generation, and a reattach must not make
     * the film it kept look like a stranger's to its own callbacks.
     */
    fun rebindLease(controlLeaseGeneration: Long) {
        if (castId != null) this.controlLeaseGeneration = controlLeaseGeneration
    }
    fun invalidate(): Long { castId = null; controlLeaseGeneration = null; generation = ++next; return generation }
    /** Lifecycle/LAN authority is not a socket callback and always wins. */
    fun forceInvalidate(): Long = invalidate()
    fun isCurrent(id: String, value: Long) = castId == id && generation == value
    fun isOwnedByControlLease(value: Long) = controlLeaseGeneration == value
    fun shouldInvalidateForControlLoss(value: Long) = isOwnedByControlLease(value)
}
