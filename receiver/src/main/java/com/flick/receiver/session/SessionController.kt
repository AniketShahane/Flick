package com.flick.receiver.session

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.flick.receiver.net.ControlCommands
import com.flick.receiver.net.ControlErrors
import com.flick.receiver.net.PreflightProbe
import com.flick.receiver.net.ProbeResult
import com.flick.receiver.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Which playback stage the TV is in (drives which screen renders). */
sealed interface MediaStage {
    data object None : MediaStage
    data object Checking : MediaStage
    data object Active : MediaStage
    data class Error(val kind: ErrorKind) : MediaStage
}

/** T9 variants: reachable-but-not-serving (amber) vs unreachable (crimson). */
enum class ErrorKind { NotServing, Unreachable }

/**
 * Bridges the control channel onto [PlayerController] and owns the TV-side UI
 * state machine (design T2–T9 + the seeking/ghost heuristic for T6). It is a
 * plain (non-Composable) holder so the [com.flick.receiver.net.ControlServer] can
 * keep a stable reference while Compose observes its state fields.
 *
 * Every [ControlCommands] method is invoked on the main thread (the server
 * marshals to it), matching [PlayerController]'s main-thread contract. The
 * existing pre-flight probe still gates every load, so a doomed cast never starts
 * silently — it lands on the specific T9 diagnosis instead.
 */
class SessionController(
    private val controller: PlayerController,
    private val scope: CoroutineScope,
    private val lifecycleStarted: () -> Boolean,
) : ControlCommands {

    var stage by mutableStateOf<MediaStage>(MediaStage.None)
        private set
    var title by mutableStateOf<String?>(null)
        private set

    /** Optimistic (target) playhead the phone is driving; solid ● on the TV bar. */
    var seekTargetMs by mutableStateOf(0L)
        private set

    /** True while the phone is actively scrubbing — shows the T6 ghost + SYNCING. */
    var seeking by mutableStateOf(false)
        private set

    /** Bumped on any command / key so the chrome reveals (design chromeFade in). */
    var chromePoke by mutableStateOf(0)
        private set

    private var lastUrl: String? = null
    private var heldPositionMs: Long = 0L
    private var lastSeekAtMs: Long = 0L
    private var probeJob: Job? = null

    /**
     * Pushes a TV→phone `error` frame (control-channel.md §4) so a preflight /
     * backgrounded / fatal failure reaches the phone (design S12) instead of
     * leaving it frozen on NowPlaying. Wired by the control server once it exists.
     */
    private var sendError: ((code: String, message: String) -> Unit)? = null

    fun attachErrorChannel(emit: (code: String, message: String) -> Unit) {
        sendError = emit
    }

    fun pokeChrome() {
        chromePoke++
    }

    // --- ControlCommands (main thread) --------------------------------------

    override fun onLoadMedia(url: String, title: String?, durationMs: Long, startMs: Long) {
        lastUrl = url
        this.title = title
        loadInternal(url, startMs)
    }

    override fun onPlay() {
        if (rejectedWhileBackgrounded()) return
        controller.resume()
        pokeChrome()
    }

    override fun onPause() {
        if (rejectedWhileBackgrounded()) return
        controller.pause()
        pokeChrome()
    }

    override fun onSeek(posMs: Long) {
        if (rejectedWhileBackgrounded()) return
        seekTargetMs = posMs
        lastSeekAtMs = SystemClock.elapsedRealtime()
        seeking = true
        controller.seekTo(posMs)
        pokeChrome()
    }

    override fun onSkip(deltaMs: Long) {
        if (rejectedWhileBackgrounded()) return
        controller.seekBy(deltaMs)
        lastSeekAtMs = SystemClock.elapsedRealtime()
        pokeChrome()
    }

    override fun onSetVolume(level: Float) {
        if (rejectedWhileBackgrounded()) return
        controller.setVolume(level)
        pokeChrome()
    }

    /**
     * While the TV app is backgrounded the player + decoder are released (terminal
     * ON_STOP), so a transport verb can't take effect. Tell the phone with an
     * `error` frame instead of silently swallowing it, so it doesn't keep driving a
     * dead remote. `onStop` is exempt — teardown must still apply while asleep.
     */
    private fun rejectedWhileBackgrounded(): Boolean {
        if (lifecycleStarted()) return false
        sendError?.invoke(ControlErrors.LOAD_REJECTED, ControlErrors.REASON_TV_BACKGROUNDED)
        return true
    }

    override fun onStop() {
        probeJob?.cancel()
        controller.stop()
        stage = MediaStage.None
        title = null
        seeking = false
    }

    // --- Retry (T9 recovery actions) ----------------------------------------

    fun retry() {
        val url = lastUrl ?: return
        loadInternal(url, heldPositionMs)
    }

    fun backToStandby() = onStop()

    private fun loadInternal(url: String, startMs: Long) {
        probeJob?.cancel()
        stage = MediaStage.Checking
        probeJob = scope.launch {
            when (val result = PreflightProbe.probe(url)) {
                is ProbeResult.Ok -> {
                    // Never start a session into a stopped activity (preserves the
                    // player's decoder/network contract while backgrounded).
                    if (lifecycleStarted()) {
                        controller.play(url)
                        if (startMs > 0L) controller.seekTo(startMs)
                        controller.recordProbeLatency(result.latencyMs)
                        seekTargetMs = startMs
                        stage = MediaStage.Active
                    } else {
                        // The load is refused into a stopped activity; tell the
                        // phone so it doesn't sit forever on an optimistic
                        // NowPlaying with a dead remote.
                        stage = MediaStage.None
                        sendError?.invoke(ControlErrors.LOAD_REJECTED, ControlErrors.REASON_TV_BACKGROUNDED)
                    }
                }
                ProbeResult.Unreachable -> {
                    stage = MediaStage.Error(ErrorKind.Unreachable)
                    sendError?.invoke(ControlErrors.LOAD_REJECTED, ControlErrors.REASON_UNREACHABLE)
                }
                else -> {
                    stage = MediaStage.Error(ErrorKind.NotServing)
                    sendError?.invoke(ControlErrors.LOAD_REJECTED, ControlErrors.REASON_NOT_SERVING)
                }
            }
        }
    }

    /**
     * Called from the ~10 Hz main-thread loop with the confirmed TV position.
     * Reconciles the seeking flag (T6 ↔ T3) and captures the held position for
     * T9 recovery; when not seeking, the solid ● snaps to the confirmed ghost so
     * the bar reads a single playhead (healthy = invisible sync).
     */
    fun syncTick(confirmedMs: Long) {
        heldPositionMs = confirmedMs
        if (stage != MediaStage.Active) {
            seeking = false
            return
        }
        val sinceSeek = SystemClock.elapsedRealtime() - lastSeekAtMs
        val diverged = kotlin.math.abs(seekTargetMs - confirmedMs) > SEEK_EPS_MS
        seeking = sinceSeek < SEEK_ACTIVE_MS || (diverged && sinceSeek < SEEK_SETTLE_MS)
        if (!seeking) seekTargetMs = confirmedMs
    }

    /** Surface a mid-stream fatal player error as the reachable-not-serving state. */
    fun onFatalPlaybackError() {
        if (stage == MediaStage.Active) {
            stage = MediaStage.Error(ErrorKind.NotServing)
            sendError?.invoke(ControlErrors.LOAD_REJECTED, ControlErrors.REASON_NOT_SERVING)
        }
    }

    companion object {
        private const val SEEK_ACTIVE_MS = 400L
        private const val SEEK_SETTLE_MS = 1_500L
        private const val SEEK_EPS_MS = 750L
    }
}
