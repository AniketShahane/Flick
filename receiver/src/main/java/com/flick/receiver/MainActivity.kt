package com.flick.receiver

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.flick.receiver.net.NsdAdvertiser
import com.flick.receiver.ui.theme.warmBundledTypefaces
import com.flick.receiver.util.FlickLog

/**
 * Single leanback Activity for the Phase 0 receiver spike. Hosts the entire
 * Compose-for-TV UI (URL entry, Play/Stop, ExoPlayer surface, live debug
 * overlay). Player lifecycle is driven from Compose via [ReceiverApp] using the
 * Activity's Lifecycle, so there is nothing player-related to manage here beyond
 * keeping the screen awake during playback.
 */
class MainActivity : ComponentActivity() {
    private val remoteKeys = TvRemoteKeyDispatcher()

    // `RestrictedApi` is a false positive on this override, and unavoidable for any
    // app: `dispatchKeyEvent` is `android.app.Activity`'s own public method, but the
    // nearest declaration lint resolves is on `androidx.core.app.ComponentActivity`,
    // whose CLASS — not this member — carries @RestrictTo(LIBRARY_GROUP_PREFIX). We
    // subclass the public `androidx.activity.ComponentActivity`, never that one, and
    // overriding a framework callback and chaining to `super` is the only way to
    // reach the remote before Compose focus dispatch.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // TV remote input arrives here before Compose focus dispatch. Custom
        // hidden-chrome D-pad commands stop here; unhandled focus navigation and
        // every dedicated media key continue exactly once to Compose/the system
        // MediaSession route.
        return remoteKeys.dispatch(event) || super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FlickLog.i(
            "bind",
            "app start version=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE} " +
                "controlV=${NsdAdvertiser.PROTOCOL_VERSION} wireCaps=array",
        )
        // Started before setContent and off the main thread so it races composition
        // rather than joining it: the bundled faces are Blocking by design, and this
        // is the only way their parse cost leaves the first measure. See
        // [warmBundledTypefaces] — a daemon thread because losing the race is the
        // ordinary outcome on a cold start and costs nothing.
        Thread({ warmBundledTypefaces(applicationContext) }, "flick-font-warm")
            .apply { isDaemon = true }
            .start()

        // Unconditional for the Activity's whole life, which is WRONG for the resting
        // surfaces: idle, pairing and settings are where the TV sits for hours between
        // casts, and the flag denies an OLED panel its dimming and every screensaver
        // there too. [keepScreenOnWhilePresenting] is the scoped answer, but applying
        // it needs the cast stage, which lives in ReceiverApp — until it is wired
        // there this stays, because a panel that never sleeps is a lesser fault than
        // one that sleeps over a film.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            ReceiverApp(window = window, remoteKeys = remoteKeys)
        }
    }
}
