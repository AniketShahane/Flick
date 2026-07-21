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
    fun invalidate(): Long { castId = null; controlLeaseGeneration = null; generation = ++next; return generation }
    /** Lifecycle/LAN authority is not a socket callback and always wins. */
    fun forceInvalidate(): Long = invalidate()
    fun isCurrent(id: String, value: Long) = castId == id && generation == value
    fun isOwnedByControlLease(value: Long) = controlLeaseGeneration == value
    fun shouldInvalidateForControlLoss(value: Long) = isOwnedByControlLease(value)
}
