package com.flick.receiver

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * Single leanback Activity for the Phase 0 receiver spike. Hosts the entire
 * Compose-for-TV UI (URL entry, Play/Stop, ExoPlayer surface, live debug
 * overlay). Player lifecycle is driven from Compose via [ReceiverApp] using the
 * Activity's Lifecycle, so there is nothing player-related to manage here beyond
 * keeping the screen awake during playback.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No remote/user interaction during long playback — don't let the TV
        // dim or sleep while a video is on screen.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            ReceiverApp(window = window)
        }
    }
}
