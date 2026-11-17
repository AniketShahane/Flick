package com.flick.receiver.session

/**
 * The seam through which a cast repairs a one-sided Wi-Fi direct link to its phone:
 * the phone's frames reach this TV, this TV's frames to the phone are lost. A seam
 * because the repair is a radio call and a probe, and the cast transaction's rules
 * for when to ask for it must be testable without either.
 */
interface DirectLinkGuard {
    /** Main thread. The cast's film is playing; its phone is [mediaUrl]'s host. Repeats for one cast are ignored. */
    fun watchCast(castId: String, mediaUrl: String)

    /** Main thread. No cast is playing any more; nothing may be probed or torn down for it. */
    fun releaseCast()

    /**
     * The pre-flight probe could not reach the phone that has just sent this TV a
     * cast. True when the phone did not answer a ping and a teardown was requested,
     * after which one more pre-flight is worth running; false leaves the failure as it was.
     */
    suspend fun repairUnreachablePeer(mediaUrl: String): Boolean

    /** No repair: every cast behaves exactly as it did before this seam existed. */
    object None : DirectLinkGuard {
        override fun watchCast(castId: String, mediaUrl: String) = Unit
        override fun releaseCast() = Unit
        override suspend fun repairUnreachablePeer(mediaUrl: String): Boolean = false
    }
}
