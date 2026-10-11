package com.flick.receiver.net

/** What one sample of this TV's LAN address means while it is bound to [LanBlipPolicy]'s host. */
internal enum class LanBlipVerdict {
    /** No address yet, and the blip window is still open: sample again. */
    WAIT,

    /** The bound address is back: the binding, and any cast on it, stay. */
    KEEP,

    /** A different address: a real move, released and rebound exactly as before. */
    CHANGED,

    /** Still nothing at the end of the window: released exactly as before. */
    RELEASE,
}

/**
 * Whether a missing address is a blip or a departure.
 *
 * A station that briefly loses its association — band steering, a mesh hand-off, an
 * access point's inactivity kick, a re-key — has its address removed for a second or
 * two and then gets the same one back. Releasing on the first empty sample tore down the
 * cast, the control server and the advertisement for that.
 */
internal object LanBlipPolicy {
    /**
     * How long an address may be missing before it is treated as gone. Well inside the
     * 45 s a playing cast is held for its phone, so a TV that does come back still has
     * time to be found again.
     */
    const val LAN_BLIP_GRACE_MS = 20_000L

    const val LAN_BLIP_RESAMPLE_MS = 1_000L

    fun decide(boundHost: String, sample: String?, waitedMs: Long): LanBlipVerdict = when {
        sample == boundHost -> LanBlipVerdict.KEEP
        sample != null -> LanBlipVerdict.CHANGED
        waitedMs < LAN_BLIP_GRACE_MS -> LanBlipVerdict.WAIT
        else -> LanBlipVerdict.RELEASE
    }
}
