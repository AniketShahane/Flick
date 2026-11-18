package com.flick.sender.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.flick.sender.model.PlaybackUiState
import kotlinx.coroutines.delay

/**
 * A surface's playing head, run on between TV frames ([PlayheadExtrapolator]). Read
 * [positionMs] and [fraction] only from a draw or layout phase, as the raw clock is.
 */
internal class Playhead(
    private val playback: State<PlaybackUiState>,
    private val tick: State<Long>,
    private val confirmed: Boolean,
) {
    private val extrapolator = PlayheadExtrapolator()

    fun positionMs(): Long {
        // Read so a tick redraws whatever reads the head.
        tick.value
        val state = playback.value
        return extrapolator.read(
            sourceMs = if (confirmed) state.confirmedMs else state.targetMs,
            nowMs = SystemClock.elapsedRealtime(),
            extrapolating = PlayheadExtrapolator.extrapolates(state),
            durationMs = state.durationMs,
        )
    }

    fun fraction(): Float {
        val durationMs = playback.value.durationMs
        return if (durationMs > 0L) (positionMs().toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    }
}

/**
 * [confirmed] runs the head on from the TV's confirmed position rather than the optimistic
 * one; the two are the same whenever it runs.
 *
 * The redraw tick runs only while the head is running on a film short enough to need it,
 * and waits on the frame clock, which Compose pauses while the activity is stopped: no
 * tick runs with the screen off or the app in the background. An infinite-animation frame,
 * as the scrub bar's wave loop takes, so a test clock does not wait on it.
 */
@Composable
internal fun rememberPlayhead(playback: State<PlaybackUiState>, confirmed: Boolean = false): Playhead {
    val tick = remember { mutableLongStateOf(0L) }
    val widthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    val extrapolating by remember(playback) { derivedStateOf { PlayheadExtrapolator.extrapolates(playback.value) } }
    val durationMs by remember(playback) { derivedStateOf { playback.value.durationMs } }
    LaunchedEffect(extrapolating, durationMs, widthPx) {
        if (!extrapolating) return@LaunchedEffect
        val intervalMs = PlayheadExtrapolator.tickIntervalMs(durationMs, widthPx) ?: return@LaunchedEffect
        while (true) {
            delay(intervalMs)
            withInfiniteAnimationFrameMillis { tick.longValue += 1 }
        }
    }
    return remember(playback, confirmed) { Playhead(playback, tick, confirmed) }
}
