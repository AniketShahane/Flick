package com.flick.receiver.util

import android.view.Window
import android.view.WindowManager

/**
 * Whether the window must be holding `FLAG_KEEP_SCREEN_ON` right now.
 *
 * The flag defeats the panel's dimming and every screensaver, so it belongs to the
 * film and not to the process. Held for the whole of a leanback Activity's life it
 * also covers the idle and pairing surfaces — which is where a TV sits for hours
 * between casts, showing a mostly-static blue wash to an OLED panel that is being
 * denied the one mechanism it has to protect itself.
 *
 * Idle may rest only when a cast can wake the TV ([idleMayRest]): otherwise a TV
 * that dimmed into its screensaver would refuse the next cast, so an idle Flick
 * keeps the panel on exactly as it always has. A film holds the panel whether it is
 * playing, paused or ended: a screensaver over a live cast stops the Activity, and
 * that ends the cast as `tv_backgrounded`.
 *
 * The handshake is inside the flag, not outside it: [castHandshakeInFlight] is bounded
 * by the 18 s startup deadline, the viewer is waiting on it, and a screensaver landing
 * between "connecting" and the first frame would read as the cast having failed.
 * [pairingRendered] is held for the same reason: the viewer is reading it while
 * they act on the phone.
 *
 * Derived from current state and never latched, for the reason
 * [preferredWindowRefreshRate] is: a one-way flag outlives the film that justified it,
 * and the platform gives no way to notice that it should have been withdrawn.
 */
fun keepScreenOnWhilePresenting(
    presentingVideo: Boolean,
    castHandshakeInFlight: Boolean,
    pairingRendered: Boolean,
    idleMayRest: Boolean,
): Boolean = presentingVideo || castHandshakeInFlight || pairingRendered || !idleMayRest

/**
 * Applies [keepScreenOnWhilePresenting]'s answer to the window.
 *
 * Guarded and swallowed like [RefreshRateHelper]: letting the panel sleep is a
 * nuisance, and throwing out of a window update during playback is not.
 */
object ScreenWakeHelper {
    fun applyToWindow(window: Window, keepAwake: Boolean) {
        runCatching {
            if (keepAwake) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    /** Hands the panel back its own dimming and screensaver timers. */
    fun release(window: Window) {
        applyToWindow(window, keepAwake = false)
    }
}
