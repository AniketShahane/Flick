package com.flick.sender

/**
 * The default network's transport as the incident journal names it. A VPN is named over
 * the transport under it: it is what decides whether the control connection, which is
 * deliberately not pinned to Wi-Fi, still reaches the TV.
 */
internal fun defaultNetworkTransport(wifi: Boolean, vpn: Boolean, cellular: Boolean): String = when {
    vpn -> "vpn"
    wifi -> "wifi"
    cellular -> "cellular"
    else -> "other"
}

/**
 * Whether a reading differs from the last one, so a callback that fires on every signal
 * step writes a journal line only when the value it watches moves. Null readings are
 * skipped: an absent link is the association edges' to record.
 */
internal class ChangeLatch<T : Any> {
    private var last: T? = null

    /** True when [value] differs from the previous non-null reading, which must exist. */
    @Synchronized
    fun moved(value: T?): Boolean {
        if (value == null) return false
        val previous = last
        last = value
        return previous != null && previous != value
    }

    /** True when [value] differs from the previous reading, the first included. */
    @Synchronized
    fun changed(value: T): Boolean {
        val previous = last
        last = value
        return previous != value
    }

    /** Record [value] as the baseline without reporting a move. */
    @Synchronized
    fun reset(value: T?) {
        last = value
    }
}
