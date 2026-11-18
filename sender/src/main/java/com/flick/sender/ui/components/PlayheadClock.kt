package com.flick.sender.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.flick.sender.model.PlaybackUiState
import com.flick.sender.net.StateFrameStamp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * A surface's playing head, run on between TV frames ([PlayheadExtrapolator]). Read
 * [positionMs] and [fraction] from a draw or layout phase, or a `derivedStateOf`, so a
 * frame or a [PlayheadTicker] tick redraws only what reads them.
 */
internal class Playhead(
    private val playback: State<PlaybackUiState>,
    private val frames: State<StateFrameStamp>,
    private val confirmed: Boolean,
) {
    private val tick = mutableLongStateOf(0L)
    private val extrapolator = PlayheadExtrapolator()

    fun positionMs(): Long = readAt(SystemClock.elapsedRealtime())

    fun fraction(): Float {
        val durationMs = playback.value.durationMs
        return if (durationMs > 0L) (positionMs().toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    }

    internal fun running(): Boolean {
        val state = playback.value
        return PlayheadExtrapolator.runs(state, frames.value, sourceOf(state))
    }

    internal fun durationMs(): Long = playback.value.durationMs

    internal fun frame(): StateFrameStamp = frames.value

    internal fun nextWakeMs(pixelMs: Long?, labels: Boolean): Long? {
        val nowMs = SystemClock.elapsedRealtime()
        readAt(nowMs)
        return extrapolator.nextWakeMs(nowMs, pixelMs, labels)
    }

    internal fun advance() {
        tick.longValue += 1
    }

    private fun readAt(nowMs: Long): Long {
        // Read so a tick redraws whatever reads the head.
        tick.longValue
        val state = playback.value
        val sourceMs = sourceOf(state)
        return extrapolator.read(
            sourceMs = sourceMs,
            nowMs = nowMs,
            runs = PlayheadExtrapolator.runs(state, frames.value, sourceMs),
            durationMs = state.durationMs,
            extrapolates = PlayheadExtrapolator.extrapolates(state),
        )
    }

    private fun sourceOf(state: PlaybackUiState): Long = if (confirmed) state.confirmedMs else state.targetMs
}

/**
 * [confirmed] runs the head on from the TV's confirmed position rather than the optimistic
 * one; the two are the same whenever it runs.
 *
 * Reads no state in the caller's scope: the redraw clock is [PlayheadTicker]'s, which the
 * caller composes beside it.
 */
@Composable
internal fun rememberPlayhead(
    playback: State<PlaybackUiState>,
    frames: StateFlow<StateFrameStamp>,
    confirmed: Boolean = false,
): Playhead {
    val frameState = frames.collectAsState()
    return remember(playback, frameState, confirmed) { Playhead(playback, frameState, confirmed) }
}

/**
 * The redraw clock for [head], in a restart scope of its own so that what it keys on —
 * whether the head runs, the film's length — recomposes nothing around it.
 *
 * It wakes only while the head runs: once a pixel on a film short enough to need it, and
 * at each flip of its position or remaining-time label when [labels] (a surface printing the time), whichever
 * is sooner, and not at all once the head has reached its lead cap. Every wake, including
 * the re-plan each new frame forces, waits on the frame clock, which Compose pauses while
 * the activity is stopped: nothing ticks with the screen off or the app in the background.
 * An infinite-animation frame, as the scrub bar's wave loop takes, so a test clock does
 * not wait on it.
 */
@Composable
internal fun PlayheadTicker(head: Playhead, labels: Boolean) {
    val widthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    val running by remember(head) { derivedStateOf { head.running() } }
    val durationMs by remember(head) { derivedStateOf { head.durationMs() } }
    LaunchedEffect(head, running, durationMs, widthPx, labels) {
        if (!running) return@LaunchedEffect
        val pixelMs = PlayheadExtrapolator.tickIntervalMs(durationMs, widthPx)
        if (pixelMs == null && !labels) return@LaunchedEffect
        // A frame moves the anchor, and with it when the next pixel or second falls.
        snapshotFlow { head.frame() }.collectLatest {
            var waitMs = withInfiniteAnimationFrameMillis { head.nextWakeMs(pixelMs, labels) }
            while (waitMs != null) {
                delay(waitMs)
                waitMs = withInfiniteAnimationFrameMillis {
                    head.advance()
                    head.nextWakeMs(pixelMs, labels)
                }
            }
        }
    }
}
