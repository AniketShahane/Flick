package com.flick.sender.net

/**
 * Whether a resume the person is waiting on gets a second sweep before the face.
 *
 * Its faults are often a second or two long: a VPN switching on or off destroys this
 * app's sockets while routing settles, the TV's control server may be re-binding, and
 * a Wi-Fi direct link one side has just torn down blacks the pair out until the other
 * side follows. One more sweep a second later rides most of those out.
 *
 * Constraints: only one, because nothing is on screen while it runs and every sweep
 * can cost a full connect timeout per candidate; only for a dial the person started,
 * because the recovery paths already have their own windows (reattach, re-cast, block
 * wait) and a longer tick there would outlast its own foreground check; and only on
 * Wi-Fi, because every fault it is for happens there.
 */
internal object ResumeRetryPolicy {

    const val RETRY_DELAY_MS = 1_000L

    fun sweepsAgain(sweep: Int, userDial: Boolean, onWifi: Boolean): Boolean =
        sweep == 1 && userDial && onWifi
}
