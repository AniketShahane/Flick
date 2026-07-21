package com.flick.receiver

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.flick.receiver.net.NsdAdvertiser
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
        // No remote/user interaction during long playback — don't let the TV
        // dim or sleep while a video is on screen.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            ReceiverApp(window = window, remoteKeys = remoteKeys)
        }
    }
}
