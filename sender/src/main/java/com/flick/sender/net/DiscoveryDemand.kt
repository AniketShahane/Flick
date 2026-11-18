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
