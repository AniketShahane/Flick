package com.flick.receiver.session

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.PlaybackException
import com.flick.receiver.net.CastFailureCode
import com.flick.receiver.net.ControlCastResult
import com.flick.receiver.net.ControlCommands
import com.flick.receiver.net.ExternalSubtitle
import com.flick.receiver.net.PreflightProbe
import com.flick.receiver.net.ProbeResult
import com.flick.receiver.player.PlaybackFailureClassifier
import com.flick.receiver.player.SessionPlayer
import com.flick.receiver.util.FlickLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

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
 * A seek the receiver has issued to the player and has not yet seen it confirm.
 *
 * [originMs] is the position the seek departed from, and it is what gives the
 * reconciliation a direction: ExoPlayer masks `currentPosition` with the requested
 * target the moment `seekTo` returns, so which side of the target a sample falls on
 * is all that separates a landing from a sample taken before the seek was issued.
 */
internal data class PendingSeek(
    val targetMs: Long,
    val originMs: Long,
    val issuedAtElapsedMs: Long,
)

/** Where an issued seek stands against the confirmed-position feed. */
internal enum class SeekPhase {
    /** Not reported yet: the target is the viewer's, not the clock's. */
    InFlight,

    /** Reported, but not yet long enough to have been read. */
    Settling,

    /** Reconciled, or given up on: the target follows the confirmed clock again. */
    Landed,
}

/**
 * When an issued seek has arrived. Pure, because the cases that matter — a sample
 * taken before the seek, an overshoot, a second seek issued over the first, a
 * target the player never reports — are exactly the ones a running TV cannot be
 * asked to reproduce.
 *
 * The confirmed-position feed cannot answer this by itself: it ticks every 100 ms
 * whether or not a seek is outstanding, so resolving on the next tick resolves on
 * an arbitrary boundary — usually one that arrives before the player has moved.
 */
internal object SeekReconciler {
    /**
     * How far past the target still counts as the target. The feed samples every
     * 100 ms and the film keeps running while the seek completes, so a landing is
     * always read slightly late; four samples covers that and stays far inside the
     * ±10 s the D-pad moves.
     */
    const val TOLERANCE_MS = 400L

    /**
     * The floor under the visible state. ExoPlayer masks its position with the
     * requested target as soon as the seek is issued, so a single press is
     * reconciled by the very next sample — and a claim that appears and clears
     * inside 100 ms is a flicker at three metres, not a state.
     */
    const val MIN_VISIBLE_MS = 300L

    /**
     * When to stop waiting. A target the player accepted is reported on the next
     * sample; only a request it clamped, or never received because no player was
     * attached, can miss — and the chrome may not go on claiming a sync that will
     * never land.
     */
    const val DEADLINE_MS = 1_500L

    fun phaseOf(pending: PendingSeek, positionMs: Long, nowElapsedMs: Long): SeekPhase {
        val elapsedMs = nowElapsedMs - pending.issuedAtElapsedMs
        return when {
            elapsedMs >= DEADLINE_MS -> SeekPhase.Landed
            !reported(pending, positionMs) -> SeekPhase.InFlight
            elapsedMs < MIN_VISIBLE_MS -> SeekPhase.Settling
            else -> SeekPhase.Landed
        }
    }

    private fun reported(pending: PendingSeek, positionMs: Long): Boolean = when {
        abs(positionMs - pending.targetMs) <= TOLERANCE_MS -> true
        pending.targetMs >= pending.originMs -> positionMs >= pending.targetMs
        else -> positionMs <= pending.targetMs
    }
}

/** PlayerController stops a seek this far short of the end; a target must agree. */
private const val SEEK_END_GUARD_MS = 1_000L

/**
 * Where the player will actually land, mirroring `PlayerController.seekTo`. The
 * reconciliation measures the reported position against this value, so a target
 * the player will never report — anything past the end guard — would otherwise
 * hold the chrome on "syncing" until the deadline expired.
 */
internal fun clampedSeekTarget(requestedMs: Long, durationMs: Long): Long {
    val floored = requestedMs.coerceAtLeast(0L)
    // PlaybackFrame reports 0 for a duration Media3 has not resolved yet, which is
    // the one case PlayerController leaves unclamped.
    if (durationMs <= 0L) return floored
    return floored.coerceAtMost((durationMs - SEEK_END_GUARD_MS).coerceAtLeast(0L))
}

/**
 * Main-thread owner of the receiver's cast transaction. Every asynchronous path
 * closes over a [CastGenerationGate] value so an A callback cannot affect B.
 */
class SessionController(
    private val controller: SessionPlayer,
    private val scope: CoroutineScope,
    private val lifecycleStarted: () -> Boolean,
    /** Injectable because it is the one step of a load that touches the LAN. */
    private val probe: suspend (String) -> ProbeResult = { url -> PreflightProbe.probe(url) },
) : ControlCommands {
    var stage by mutableStateOf<MediaStage>(MediaStage.None)
        private set
    var title by mutableStateOf<String?>(null)
        private set
    var seekTargetMs by mutableStateOf(0L)
        private set

    /**
     * The one seek the chrome is presenting. Compose state, because [seeking] is
     * derived from it and is read from composition — and derived rather than a
     * second flag so the two can never disagree.
     */
    private var pendingSeek by mutableStateOf<PendingSeek?>(null)

    /** True until the TV's own position feed confirms the seek that was issued. */
    val seeking: Boolean get() = pendingSeek != null

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
    /**
     * The external subtitle the live session is actually prepared with. A reload
     * frame is measured against this, so it has to be written by every path that
     * re-prepares the media — not only the startup transaction that sets it first.
     */
    private var preparedSubtitle: ExternalSubtitle? = null
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
    // Absolute, never expressed as a skip: a replay's destination is the start of the
    // film, while a skip's target is derived from wherever the player currently reports
    // itself — and at Ended that position has already been clamped to the duration.
    fun onSeek(posMs: Long) { castId?.let { onSeek(it, posMs) } }
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
        subtitle: ExternalSubtitle?,
    ): ControlCastResult {
        replayResult(castId)?.let { return it }
        return beginLoad(controlLeaseGeneration, castId, url, title, durationMs, startMs, subtitle)
    }

    /**
     * The phone re-issues `loadMedia` for the SAME castId when the user attaches or
     * removes an external subtitle mid-watch, because the served file, not the cast,
     * is what changed. Control ownership classifies that as a duplicate, so without
     * this the frame would be answered from the retained result and the selection
     * would never reach the player. Only a changed subtitle re-prepares: an ordinary
     * retransmit still replays and costs the user nothing.
     *
     * A cast that has already reached Active reloads in place — see [reloadInPlace].
     * The full load is reserved for a reload that arrives before there is anything
     * to reload, where the startup transaction is the correct one.
     */
    override fun onReloadMedia(
        controlLeaseGeneration: Long,
        castId: String,
        url: String,
        title: String,
        durationMs: Long,
        startMs: Long,
        subtitle: ExternalSubtitle?,
    ): ControlCastResult? {
        if (castId != this.castId || controlLeaseGeneration != this.controlLeaseGeneration) return null
        if (subtitle == preparedSubtitle) return null
        val inPlace = stage is MediaStage.Active
        FlickLog.i(
            "cast",
            "reload reason=subtitle castIdFp=${FlickLog.fp(castId)} extSub=${subtitle != null} inPlace=$inPlace",
        )
        if (inPlace && reloadInPlace(castId, url, startMs, subtitle)) return retainedResult
        return beginLoad(controlLeaseGeneration, castId, url, title, durationMs, startMs, subtitle)
    }

    /**
     * The reload a cast that is ALREADY PLAYING takes, and deliberately not a load
     * transaction. [beginLoad] drops the stage to Checking, which maps to a covered
     * surface mode and makes the UI rebuild the PlayerView's SurfaceView, while
     * [SessionPlayer.playStartup] releases the very player that is presenting.
     * The replacement then prepared against a surface that never presented, so no
     * first frame was ever signalled and the startup deadline — whose only disarm
     * path is that frame — tore down a healthy cast ~18 s after a subtitle was
     * attached or removed.
     *
     * Staying Active is therefore the fix, not an optimisation: the surface mode
     * never leaves VisiblePlayback, the player and its bindings survive, and no
     * deadline is armed. A reload that genuinely fails is a steady-state playback
     * error, reported through [onPlaybackError] — a cast that has already started
     * can never fail with a startup code.
     *
     * False means there was no live player after all, and the caller falls back to
     * the full load, which is exactly the right transaction when nothing is playing.
     */
    private fun reloadInPlace(
        castId: String,
        url: String,
        startMs: Long,
        subtitle: ExternalSubtitle?,
    ): Boolean {
        if (!controller.reloadInPlace(url, startMs, mediaIdFor(castId, generation), subtitle)) return false
        preparedSubtitle = subtitle
        return true
    }

    /**
     * The cold-start transaction: it arms the startup deadline and adopts a new
     * player. A cast arriving over a running film keeps the old player rendering
     * until [startPlayer] replaces it, so the TV never blanks any earlier than the
     * new prepare demands.
     */
    private fun beginLoad(
        controlLeaseGeneration: Long,
        castId: String,
        url: String,
        title: String,
        durationMs: Long,
        startMs: Long,
        subtitle: ExternalSubtitle?,
    ): ControlCastResult {
        invalidate(clearRetained = true)
        val accepted = ControlCastResult.Accepted(castId)
        retainedResult = accepted
        val generation = gate.adopt(castId, controlLeaseGeneration)
        this.controlLeaseGeneration = controlLeaseGeneration
        this.title = title
        seekTargetMs = startMs
        stage = MediaStage.Checking(castId, controlLeaseGeneration)
        startupUrl = url
        preparedSubtitle = subtitle
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
            when (val result = probe(url)) {
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
            subtitle = preparedSubtitle,
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
        if (!current(castId)) return
        // Sampled BEFORE the seek: the player masks its position with the target
        // as soon as it is issued, and a pending seek whose origin is its own
        // target has lost the direction the reconciliation needs.
        val before = controller.readPlaybackState()
        controller.seekTo(posMs)
        beginSeek(before.posMs, clampedSeekTarget(posMs, before.durationMs))
    }
    override fun onSkip(castId: String, deltaMs: Long) {
        if (!current(castId)) return
        val before = controller.readPlaybackState()
        controller.seekBy(deltaMs)
        beginSeek(before.posMs, clampedSeekTarget(before.posMs + deltaMs, before.durationMs))
    }
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
        pendingSeek = null
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
        preparedSubtitle = null
        startupPositionMs = 0L
    }

    private fun invalidateToNone(clearRetained: Boolean = true) {
        invalidate(clearRetained)
        controller.stop()
        title = null
        seekTargetMs = 0L
        stage = MediaStage.None
    }

    /**
     * The ~10 Hz confirmed-position feed, and the only thing that ends a seek.
     *
     * With nothing outstanding the target simply IS the confirmed clock, which is
     * what keeps the scrub bar's ghost and its connecting line off screen while
     * playback is healthy. While a seek is outstanding the target is the viewer's
     * and is held until [SeekReconciler] can see the TV report it — a tick is a
     * sample, not an acknowledgement.
     */
    fun syncTick(position: Long) {
        if (stage !is MediaStage.Active) {
            // The feed ticks across every stage. A seek left pending through a
            // teardown would still be claiming a sync over the next film.
            pendingSeek = null
            return
        }
        val pending = pendingSeek ?: run {
            seekTargetMs = position
            return
        }
        when (SeekReconciler.phaseOf(pending, position, SystemClock.elapsedRealtime())) {
            SeekPhase.InFlight -> Unit
            SeekPhase.Settling -> seekTargetMs = position
            SeekPhase.Landed -> {
                pendingSeek = null
                seekTargetMs = position
            }
        }
    }

    private fun beginSeek(originMs: Long, targetMs: Long) {
        seekTargetMs = targetMs
        pendingSeek = PendingSeek(
            targetMs = targetMs,
            originMs = originMs,
            issuedAtElapsedMs = SystemClock.elapsedRealtime(),
        )
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
