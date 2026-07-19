package com.castspike.receiver.util

import android.os.Build
import android.view.Surface
import android.view.Window

/**
 * Best-effort refresh-rate matching to avoid 3:2 judder when the content frame
 * rate (e.g. 23.976 / 24 / 25 fps) does not divide the panel's default refresh
 * rate. Two complementary hints are applied once the frame rate is known:
 *
 *  1. [applyToWindow]: sets [android.view.WindowManager.LayoutParams.preferredRefreshRate],
 *     the app-level hint the platform uses to pick a matching display mode.
 *  2. [applyToSurface]: calls [Surface.setFrameRate] on API 30+ (the direct
 *     MATCH_CONTENT_FRAME_RATE mechanism). ExoPlayer also does this internally
 *     on the surface it owns; issuing it explicitly documents/forces the intent.
 *
 * Everything here is guarded and swallowed on failure — refresh-rate switching
 * is a nice-to-have, never a hard requirement.
 */
object RefreshRateHelper {

    fun applyToWindow(window: Window, frameRate: Float) {
        if (frameRate <= 0f) return
        runCatching {
            val params = window.attributes
            params.preferredRefreshRate = frameRate
            window.attributes = params
        }
    }

    fun applyToSurface(surface: Surface?, frameRate: Float) {
        if (surface == null || !surface.isValid || frameRate <= 0f) return
        runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    surface.setFrameRate(
                        frameRate,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                        Surface.CHANGE_FRAME_RATE_ALWAYS,
                    )
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    @Suppress("DEPRECATION")
                    surface.setFrameRate(
                        frameRate,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    )
            }
        }
    }
}
