package com.flick.sender.net

import com.flick.sender.model.ConnectionStatus

/**
 * When this phone keeps browsing for TVs. A browse left open for the life of the process
 * keeps the radio answering and sending mDNS through a whole screen-off film; nothing reads
 * the device list then but the paths below.
 *
 * Wanted while the app is in front of the viewer (the device list, the next dial); while
 * a pairing, dial, resume or reattach is in flight (each may pick a fresh address from the
 * list); while a block-wait window is armed (each of its ticks restarts the browse
 * anyway); and while a cast is live on a control connection that is not up, since the
 * recovery that follows may need to find the TV somewhere new. A healthy cast playing to
 * a backgrounded or screen-off phone, and an idle backgrounded phone, need none of it.
 */
internal object DiscoveryDemand {
    fun wanted(
        foreground: Boolean,
        attemptInFlight: Boolean,
        blockWaitArmed: Boolean,
        castLive: Boolean,
        connection: ConnectionStatus,
    ): Boolean =
        foreground || attemptInFlight || blockWaitArmed || (castLive && connection != ConnectionStatus.CONNECTED)
}

/**
 * Which held device records have not been seen again since discovery last stopped.
 *
 * Only a running browse (or a record subscription, which a stop drops too) reports a loss,
 * and a browse started later reports only what it finds then, never a loss for a name it
 * never found. So every record held across a stop is unconfirmed until a resolve lands for
 * it; once a new browse has had [RECONFIRM_GRACE_MS] to report, the caller puts every name
 * still unconfirmed through the ordinary loss path, which re-resolves before dropping.
 *
 * Until then such a record is a memory, not an advertisement: still dialable, but nothing
 * may call its address live on this network (see [LanProximity.sameSubnetClaim]).
 *
 * Callers serialize access (NsdDiscovery's lock).
 */
internal class HeldRecordConfirmation {
    private val unconfirmed = HashSet<String>()

    /** Discovery stopped while holding records for [held]. */
    fun onStopped(held: Collection<String>) {
        unconfirmed += held
    }

    /** A resolve for [name] landed: it is advertised now. */
    fun onResolved(name: String) {
        unconfirmed -= name
    }

    /** [name]'s record left the list. */
    fun onForgotten(name: String) {
        unconfirmed -= name
    }

    fun confirmed(name: String): Boolean = name !in unconfirmed

    val anyUnconfirmed: Boolean get() = unconfirmed.isNotEmpty()

    /** The names of [held] the loss path must now judge, once a new browse has had its grace. */
    fun dueForLoss(held: Collection<String>): List<String> = held.filter { it in unconfirmed }

    companion object {
        /**
         * From a restarted browse reporting started to judging what it has not found: twice
         * the loss path's own grace, which covers the first mDNS answers and their resolves
         * queued one at a time behind each other.
         */
        const val RECONFIRM_GRACE_MS = 5_000L
    }
}
