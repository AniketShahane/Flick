package com.flick.receiver.util

import android.os.SystemClock
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Whether the Activity's window is really on screen. A window brought forward from the
 * background (a summoned cast) is under the system's open animation from ON_START until
 * `Activity.onEnterAnimationComplete`, and motion run in that span is never seen: on the
 * TV the Flick card's whole rise used to play behind the launcher.
 */
class WindowEntrance(private val now: () -> Long = SystemClock::uptimeMillis) {
    private sealed interface State
    private object Hidden : State
    private class Entering(val sinceMs: Long) : State
    private object Shown : State

    private val state = MutableStateFlow<State>(Hidden)

    fun onStart() {
        if (state.value === Hidden) state.value = Entering(now())
    }

    fun onEnterAnimationComplete() {
        state.value = Shown
    }

    fun onStop() {
        state.value = Hidden
    }

    /**
     * Returns once the window is on screen. The completion callback is not promised for
     * every way a window can appear, so an entrance that never reports it is taken as
     * done [fallbackMs] after ON_START. A window still hidden waits for its ON_START: a
     * summon that never brings it forward fails the cast, which cancels the waiter.
     */
    suspend fun awaitShown(fallbackMs: Long = ENTER_FALLBACK_MS) {
        while (true) {
            when (val s = state.value) {
                Shown -> return
                Hidden -> state.first { it !== Hidden }
                is Entering -> {
                    val left = s.sinceMs + fallbackMs - now()
                    if (left <= 0L) return
                    withTimeoutOrNull(left) { state.first { it !== s } }
                }
            }
        }
    }

    companion object {
        /** The TV's open animation ends about 550 ms after ON_START (Google TV Streamer, measured). */
        const val ENTER_FALLBACK_MS = 1_000L
    }
}

/** Null where no Activity reports its window, as in the stage tests: always on screen. */
val LocalWindowEntrance = staticCompositionLocalOf<WindowEntrance?> { null }
