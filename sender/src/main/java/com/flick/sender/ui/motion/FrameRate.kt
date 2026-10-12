package com.flick.sender.ui.motion

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.preferredFrameRate

/**
 * The display's peak refresh rate at its current resolution.
 *
 * An adaptive panel only leaves its normal rate for a layer that asks, and the platform's
 * own "high" category resolves below the peak on some panels (90 Hz on a 120 Hz panel), so
 * the vote names the rate itself. Modes at a different physical size are excluded: voting
 * for one of those would ask the display to switch resolution as well as rate.
 */
internal fun peakRefreshRate(display: Display): Float {
    val current = display.mode
    return display.supportedModes
        .filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        .maxOfOrNull { it.refreshRate }
        ?: current.refreshRate
}

@Composable
private fun rememberPeakRefreshRate(): Float {
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    return remember(view, configuration) {
        val display = view.display ?: view.context.defaultDisplay()
        display?.let(::peakRefreshRate) ?: 0f
    }
}

private fun Context.defaultDisplay(): Display? =
    (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)

/**
 * Votes the display's peak rate for this layer and its children while [active], and
 * withdraws the vote otherwise. The modifier stays in the chain either way, so toggling it
 * changes one value rather than adding and removing a layer.
 */
@Composable
fun Modifier.animatingFrameRate(active: Boolean): Modifier {
    val peak = rememberPeakRefreshRate()
    return preferredFrameRate(if (active) peak else 0f)
}

/**
 * One vote for a surface that is always moving but only sometimes moving fast: the peak while
 * [fast], the ambient rate otherwise. One modifier, so switching is a value, not a layer.
 */
@Composable
fun Modifier.loopFrameRate(fast: Boolean): Modifier {
    val peak = rememberPeakRefreshRate()
    return preferredFrameRate(if (fast) peak else AmbientFrameRateHz)
}

/**
 * A low vote for a loop that is always running but slow enough to read the same at a
 * quarter of the panel's peak. The platform picks the highest vote in the window, so this
 * only lowers the rate while nothing else on screen is moving.
 */
fun Modifier.ambientFrameRate(): Modifier = preferredFrameRate(AmbientFrameRateHz)

internal const val AmbientFrameRateHz = 30f
