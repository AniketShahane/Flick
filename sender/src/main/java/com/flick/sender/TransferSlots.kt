package com.flick.sender

/**
 * The video body transfers [MediaHttpServer] lets run at once, and which one gives way.
 *
 * A transfer whose TV end vanished in a Wi-Fi blip does not fail: its write blocks in a
 * full socket buffer for many minutes. Four blips used to hold every slot and answer the
 * TV's next request `503`, which ends the film. A newcomer that finds every slot taken
 * now evicts the transfer with the oldest progress, if that one has moved nothing for
 * [EVICT_IDLE_MS]. A live stream can sit that idle too — a full buffer leaves its
 * connection unread for seconds, a pause for minutes — but with one TV every stranded
 * transfer stopped before the live one did, so the oldest is never the live one; and were
 * it ever evicted, the TV's read fails and its player reconnects within its retry delay.
 *
 * The newcomer takes the slot at once. The evicted transfer's [Lease.cancel] is run, but
 * how long its thread takes to unwind is not this ledger's concern: its own [release]
 * finds the lease gone and does nothing.
 */
internal class TransferSlots(
    private val capacity: Int,
    private val evictIdleMs: Long = EVICT_IDLE_MS,
) {
    class Lease internal constructor(startedAtMs: Long, internal val cancel: () -> Unit) {
        @Volatile internal var lastProgressMs: Long = startedAtMs
            private set

        @Volatile var evicted: Boolean = false
            internal set

        fun progress(nowMs: Long) {
            lastProgressMs = nowMs
        }
    }

    sealed interface Admission {
        /** [evictedIdleMs] is how long the transfer that gave way had been still, or null. */
        class Admitted(val lease: Lease, val evictedIdleMs: Long?, val active: Int) : Admission

        class Refused(val active: Int) : Admission
    }

    private val leases = ArrayList<Lease>(capacity)

    fun acquire(nowMs: Long, cancel: () -> Unit): Admission {
        var victim: Lease? = null
        val admission = synchronized(leases) {
            var idleMs: Long? = null
            if (leases.size >= capacity) {
                val oldest = leases.minByOrNull { it.lastProgressMs }
                val oldestIdleMs = oldest?.let { nowMs - it.lastProgressMs }
                if (oldest == null || oldestIdleMs == null || oldestIdleMs < evictIdleMs) {
                    return Admission.Refused(leases.size)
                }
                leases.remove(oldest)
                oldest.evicted = true
                victim = oldest
                idleMs = oldestIdleMs
            }
            val lease = Lease(nowMs, cancel)
            leases.add(lease)
            Admission.Admitted(lease, idleMs, leases.size)
        }
        victim?.let { runCatching { it.cancel() } }
        return admission
    }

    fun release(lease: Lease) {
        synchronized(leases) { leases.remove(lease) }
    }

    val active: Int get() = synchronized(leases) { leases.size }

    companion object {
        const val EVICT_IDLE_MS = 5_000L
    }
}
