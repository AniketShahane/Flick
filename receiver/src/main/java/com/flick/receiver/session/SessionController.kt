package com.flick.receiver.session

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.PlaybackException
import com.flick.receiver.net.CastFailureCode
import com.flick.receiver.net.ControlCastResult
import com.flick.receiver.net.ControlCommands
import com.flick.receiver.net.PreflightProbe
import com.flick.receiver.net.ProbeResult
import com.flick.receiver.player.PlaybackFailureClassifier
import com.flick.receiver.player.PlayerController
import com.flick.receiver.util.FlickLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface MediaStage {
    data object None : MediaStage
    data class Checking(val castId: String, val controlLeaseGeneration: Long) : MediaStage
    data class Preparing(val castId: String, val controlLeaseGeneration: Long) : MediaStage
    data class Active(val castId: String, val controlLeaseGeneration: Long) : MediaStage
    data class Error(val castId: String?, val code: CastFailureCode, val controlLeaseGeneration: Long?) : MediaStage {
        val kind get() = if (code == CastFailureCode.MEDIA_UNREACHABLE) ErrorKind.Unreachable else ErrorKind.NotServing
    }
}

enum class ErrorKind { NotServing, Unreachable }

/**
 * Main-thread owner of the receiver's cast transaction. Every asynchronous path
 * closes over a [CastGenerationGate] value so an A callback cannot affect B.
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
    var seekTargetMs by mutableStateOf(0L)
        private set
    var seeking by mutableStateOf(false)
        private set
    var chromePoke by mutableStateOf(0)
        private set

    private val gate = CastGenerationGate()
    private val castId get() = gate.castId
    private val generation get() = gate.generation
    /** The control connection that synchronously adopted [castId]. */
    private var controlLeaseGeneration: Long? = null
    private var probeJob: Job? = null
    private var startupDeadlineJob: Job? = null
    private var startupRetries = 0
    private var startupDeadlineElapsedMs = 0L
    private var startupUrl: String? = null
    private var startupPositionMs = 0L
    private var retainedResult: ControlCastResult? = null
    private var terminal: ((String, CastFailureCode, Boolean, Int?, Boolean) -> Unit)? = null
    private var ready: ((String, Long, Long) -> Unit)? = null

    init {
        controller.setPlaybackFailureListener(::onPlaybackError)
    }

    fun attachTerminal(emit: (String, CastFailureCode, Boolean, Int?, Boolean) -> Unit) { terminal = emit }
    fun attachReady(emit: (String, Long, Long) -> Unit) { ready = emit }
    fun pokeChrome() { chromePoke++ }
    fun onPlay() { castId?.let(::onPlay) }
    fun onPause() { castId?.let(::onPause) }
    fun onSkip(deltaMs: Long) { castId?.let { onSkip(it, deltaMs) } }
    fun onSetVolume(level: Float) { castId?.let { onSetVolume(it, level) } }

    /** Synchronous adoption is the commit boundary for ControlServer.loadAccepted. */
    override fun onLoadMedia(
        controlLeaseGeneration: Long,
        castId: String,
        url: String,
        title: String,
        durationMs: Long,
        startMs: Long,
    ): ControlCastResult {
        replayResult(castId)?.let { return it }
        invalidate(clearRetained = true)
        val accepted = ControlCastResult.Accepted(castId)
        retainedResult = accepted
        val generation = gate.adopt(castId, controlLeaseGeneration)
        this.controlLeaseGeneration = controlLeaseGeneration
        this.title = title
        seekTargetMs = startMs
        stage = MediaStage.Checking(castId, controlLeaseGeneration)
        startupUrl = url
        startupPositionMs = startMs
        startupRetries = 0
        startupDeadlineElapsedMs = SystemClock.elapsedRealtime() + STARTUP_DEADLINE_MS
        startupDeadlineJob = scope.launch {
            delay(STARTUP_DEADLINE_MS)
            if (gate.isCurrent(castId, generation) && stage !is MediaStage.Active) {
                fail(castId, generation, CastFailureCode.STARTUP_TIMEOUT, retryable = true, beforeReady = true)
            }
        }
        FlickLog.i("cast", "stage=checking castIdFp=${FlickLog.fp(castId)} src=${FlickLog.endpoint(url)} startMs=$startMs durationMs=$durationMs")
        val started = SystemClock.elapsedRealtime()
        probeJob = scope.launch {
            val probeStarted = SystemClock.elapsedRealtime()
            when (val result = PreflightProbe.probe(url)) {
                is ProbeResult.Ok -> {
                    FlickLog.i("probe", "result=Ok latencyMs=${result.latencyMs}")
                    if (!gate.isCurrent(castId, generation)) return@launch
                    if (!lifecycleStarted()) {
                        fail(castId, generation, CastFailureCode.TV_BACKGROUNDED, retryable = false, beforeReady = true)
                    } else {
                        controller.recordProbeLatency(result.latencyMs)
                        startPlayer(castId, generation, result.latencyMs, started)
                    }
                }
                ProbeResult.Unreachable -> {
                    FlickLog.w("probe", "result=Unreachable latencyMs=${SystemClock.elapsedRealtime() - probeStarted}")
                    fail(castId, generation, CastFailureCode.MEDIA_UNREACHABLE, true, beforeReady = true)
                }
                ProbeResult.ConnectionRefused -> {
                    FlickLog.w("probe", "result=ConnectionRefused latencyMs=${SystemClock.elapsedRealtime() - probeStarted}")
                    fail(castId, generation, CastFailureCode.SENDER_NOT_SERVING, true, beforeReady = true)
                }
                is ProbeResult.HttpError -> {
                    FlickLog.w("probe", "result=HttpError status=${result.status ?: -1} latencyMs=${SystemClock.elapsedRealtime() - probeStarted}")
                    fail(castId, generation, CastFailureCode.HTTP_REJECTED, true, result.status, true)
                }
                ProbeResult.BadResponse -> {
                    FlickLog.w("probe", "result=BadResponse latencyMs=${SystemClock.elapsedRealtime() - probeStarted}")
                    fail(castId, generation, CastFailureCode.HTTP_REJECTED, true, beforeReady = true)
                }
            }
        }
        return accepted
    }

    override fun replayResult(castId: String): ControlCastResult? = retainedResult?.takeIf { resultCastId(it) == castId }

    private fun startPlayer(castId: String, generation: Long, probeLatencyMs: Long, startedElapsedMs: Long) {
        if (!gate.isCurrent(castId, generation) || SystemClock.elapsedRealtime() >= startupDeadlineElapsedMs) {
            fail(castId, generation, CastFailureCode.STARTUP_TIMEOUT, true, beforeReady = true)
            return
        }
        val lease = controlLeaseGeneration ?: return
        stage = MediaStage.Preparing(castId, lease)
        val url = startupUrl ?: run {
            fail(castId, generation, CastFailureCode.UNKNOWN, false, beforeReady = true)
            return
        }
        controller.playStartup(
            url = url,
            startMs = startupPositionMs,
            mediaId = mediaIdFor(castId, generation),
            onFirstFrame = firstFrame@{
                if (!gate.isCurrent(castId, generation)) return@firstFrame
                startupDeadlineJob?.cancel()
                startupDeadlineJob = null
                stage = MediaStage.Active(castId, lease)
                FlickLog.i("cast", "stage=active castIdFp=${FlickLog.fp(castId)} startupMs=${SystemClock.elapsedRealtime() - startedElapsedMs}")
                val outcome = ControlCastResult.Ready(
                    castId = castId,
                    probeLatencyMs = probeLatencyMs,
                    startupMs = SystemClock.elapsedRealtime() - startedElapsedMs,
                )
                retainedResult = outcome
                ready?.invoke(castId, outcome.probeLatencyMs, outcome.startupMs)
            },
            onError = { error -> onStartupError(castId, generation, probeLatencyMs, startedElapsedMs, error) },
        )
    }

    private fun onStartupError(
        castId: String,
        generation: Long,
        probeLatencyMs: Long,
        startedElapsedMs: Long,
        error: PlaybackException,
    ) {
        if (!gate.isCurrent(castId, generation)) return
        val retryDelay = StartupRetryPolicy.delayForRetry(
            completedRetries = startupRetries,
            isTransientIo = PlaybackFailureClassifier.isStartupRetryable(error),
            nowMs = SystemClock.elapsedRealtime(),
            deadlineMs = startupDeadlineElapsedMs,
        )
        if (retryDelay != null) {
            startupRetries++
            scope.launch {
                delay(retryDelay)
                if (gate.isCurrent(castId, generation) && stage !is MediaStage.Active) {
                    startPlayer(castId, generation, probeLatencyMs, startedElapsedMs)
                }
            }
            return
        }
        fail(
            castId,
            generation,
            if (PlaybackFailureClassifier.isStartupRetryable(error)) CastFailureCode.STARTUP_TIMEOUT else PlaybackFailureClassifier.classify(error),
            retryable = PlaybackFailureClassifier.isStartupRetryable(error),
            beforeReady = true,
        )
    }

    /** The exact exception comes from PlayerController, rather than a polling phase. */
    private fun onPlaybackError(error: PlaybackException) {
        val id = castId ?: return
        if (stage !is MediaStage.Active) return
        fail(id, generation, PlaybackFailureClassifier.classify(error), retryable = true, beforeReady = false)
    }

    private fun fail(
        id: String,
        generation: Long,
        code: CastFailureCode,
        retryable: Boolean,
        status: Int? = null,
        beforeReady: Boolean,
    ) {
        if (!gate.isCurrent(id, generation)) return
        FlickLog.w("cast", "fail code=${code.wire} retryable=$retryable beforeReady=$beforeReady status=${status ?: -1} castIdFp=${FlickLog.fp(id)}")
        controller.stop()
        startupDeadlineJob?.cancel()
        startupDeadlineJob = null
        val outcome = ControlCastResult.Failed(id, code, retryable, status, beforeReady)
        retainedResult = outcome
        stage = MediaStage.Error(id, code, controlLeaseGeneration)
        terminal?.invoke(id, code, retryable, status, beforeReady)
        // Keep only the immutable result for a duplicate replay; no player or
        // active ownership survives a terminal failure.
        gate.invalidate()
        clearStartupState()
    }

    override fun onPlay(castId: String) { if (current(castId)) controller.resume() }
    override fun onPause(castId: String) { if (current(castId)) controller.pause() }
    override fun onSeek(castId: String, posMs: Long) {
        if (current(castId)) { seekTargetMs = posMs; seeking = true; controller.seekTo(posMs) }
    }
    override fun onSkip(castId: String, deltaMs: Long) { if (current(castId)) controller.seekBy(deltaMs) }
    override fun onSetVolume(castId: String, level: Float) { if (current(castId)) controller.setVolume(level) }
    override fun onCancelLoad(castId: String): Boolean {
        if (!current(castId)) return false
        invalidateToNone()
        return true
    }
    override fun onStop(castId: String): Boolean {
        if (!current(castId)) return false
        retainedResult = ControlCastResult.Stopped(castId)
        invalidateToNone(clearRetained = false)
        return true
    }

    /** Called by control ownership before it can allow queued work to survive a close. */
    override fun onControlLost(generation: Long) {
        if (!gate.shouldInvalidateForControlLoss(generation) && stageLeaseGeneration() != generation) return
        invalidateToNone(clearRetained = true)
    }

    /** Lifecycle/LAN teardown is terminal: never revive this URL on ON_START. */
    fun onBackground() {
        val teardown = forceLocalTeardown()
        teardown.castId?.let { terminal?.invoke(it, CastFailureCode.TV_BACKGROUNDED, false, null, teardown.beforeReady) }
    }

    /** Unconditional local authority for lifecycle, LAN loss and endpoint rebind. */
    fun forceLocalTeardown(): LocalTeardown {
        val result = LocalTeardown(castId, TerminalPhase.beforeReady(stage))
        invalidateToNone(clearRetained = true)
        return result
    }

    private fun current(id: String) = id == castId

    private fun invalidate(clearRetained: Boolean) {
        gate.forceInvalidate()
        probeJob?.cancel(); probeJob = null
        startupDeadlineJob?.cancel(); startupDeadlineJob = null
        controller.clearStartupListener()
        clearStartupState()
        controlLeaseGeneration = null
        if (clearRetained) retainedResult = null
    }

    private fun clearStartupState() {
        startupRetries = 0
        startupDeadlineElapsedMs = 0L
        startupUrl = null
        startupPositionMs = 0L
    }

    private fun invalidateToNone(clearRetained: Boolean = true) {
        invalidate(clearRetained)
        controller.stop()
        title = null
        seekTargetMs = 0L
        seeking = false
        stage = MediaStage.None
    }

    fun syncTick(position: Long) {
        if (stage is MediaStage.Active) { seekTargetMs = position; seeking = false }
    }

    /** Kept only for compatibility with the polling surface; errors arrive immediately above. */
    fun onFatalPlaybackError() = Unit
    fun retry() = Unit
    /** Local Back has the same terminal outcome as a matching control stop. */
    fun backToStandby(): ControlCastResult.Stopped? {
        val id = castId ?: run { invalidateToNone(); return null }
        return if (onStop(id)) retainedResult as? ControlCastResult.Stopped else null
    }

    private fun resultCastId(result: ControlCastResult) = when (result) {
        is ControlCastResult.Accepted -> result.castId
        is ControlCastResult.Ready -> result.castId
        is ControlCastResult.Failed -> result.castId
        is ControlCastResult.Stopped -> result.castId
    }

    private fun mediaIdFor(castId: String, generation: Long) = "flick:$castId:$generation"

    private fun stageLeaseGeneration(): Long? = when (val value = stage) {
        is MediaStage.Checking -> value.controlLeaseGeneration
        is MediaStage.Preparing -> value.controlLeaseGeneration
        is MediaStage.Active -> value.controlLeaseGeneration
        is MediaStage.Error -> value.controlLeaseGeneration
        MediaStage.None -> null
    }

    private companion object {
        const val STARTUP_DEADLINE_MS = 18_000L
    }
}

data class LocalTeardown(val castId: String?, val beforeReady: Boolean)
