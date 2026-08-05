package com.flick.sender.net

import android.os.SystemClock
import com.flick.sender.model.PlaybackPhase
import com.flick.sender.model.PlaybackUiState
import com.flick.sender.model.VideoRotation
import com.flick.sender.util.FlickLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The hero's brain (design Part 4 + control-channel.md §4): one session clock held
 * as two numbers. [PlaybackUiState.targetMs] is the optimistic head the thumb
 * drives; [PlaybackUiState.confirmedMs] is the last TV-reported position that
 * trails. While a seek is in flight the head **leads** and the ghost **chases**;
 * when they collapse the session reconciles and fires a shared Spark pulse. When
 * idle, the head follows the TV so a cross-surface pause/seek mirrors on the phone.
 *
 * Commands go out through [ControlClient]; absolute-valued verbs (`seek posMs`,
 * `setVolume level`, `setAudioDelay delayMs`) are idempotent so reordering can't corrupt
 * the position, the level or the nudge.
 *
 * The scope carries the one thing here that waits: the quiet period a run of ±10s taps
 * commits on. It is the caller's application scope — main-confined, which is what makes
 * every plain field below safe to touch from both a tap and a frame, and outliving the
 * remote screen, which is what lets a run land after the user has left it.
 */
class PlaybackSession(
    private val control: ControlClient,
    private val scope: CoroutineScope,
    private val fallbackTitle: String = GENERIC_TITLE,
) {
    private var castId: String? = null

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    /**
     * The A/V nudge in force for the cast being driven, in the sign [AudioDelayPolicy]
     * fixes. It is deliberately NOT part of [PlaybackUiState]: no `state` frame carries
     * it and the TV never reports it back, so this phone is the source of truth for what
     * is displayed — and putting it in the clock's own value would republish it at 10 Hz
     * to say nothing new.
     */
    private val _audioDelayMs = MutableStateFlow(AudioDelayPolicy.IN_SYNC_MS)
    val audioDelayMs: StateFlow<Int> = _audioDelayMs.asStateFlow()

    /** The run of absolute values a move too large for one frame is being walked as. */
    private var audioDelayWalk: Job? = null

    /** Haptic cues for the hand (grip/detent/snap/confirm). */
    val haptics = MutableSharedFlow<HapticCue>(extraBufferCapacity = 16)

    /** Emitted the instant ghost & target reconcile — drives the Spark pulse ring. */
    val pulses = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    private var lastSeq = -1L
    private var lastSeekAtMs = 0L
    private var lastConfirmedAtMs = 0L
    private var lastDetentBucket = -1L

    private var seekPending = false
    private var seekPendingSinceMs = 0L

    // A locally-commanded play/pause is optimistic; hold that value against stale
    // in-flight `state` frames (sampled up to ~100ms before the TV applied the verb)
    // until the TV echoes the commanded value or the grace window lapses — otherwise
    // the morphing button double-flickers and fires 2–3 spurious CONFIRM haptics.
    private var playPending = false
    private var playPendingSinceMs = 0L
    private var playCommanded = false

    // A run of ±10s taps is one intent, so the head accumulates locally and exactly one
    // absolute seek leaves when the tapping stops. One seek per tap made the TV flush its
    // decoder and refill from a new byte offset for every tap — three fills to move thirty
    // seconds, each of them the buffering face over the transport the user was reaching for.
    private val skipBurst = SkipBurstTimer(scope) { commitSkipBurst() }

    // --- commands ----------------------------------------------------------

    /**
     * A fresh control session opened (connect/resume): drop the stale `seq` watermark
     * so `state` frames from a restarted TV (whose seq counter restarts near 0) aren't
     * discarded as stale until the next loadMedia. Idempotent; safe on every connect.
     */
    fun onConnected() {
        lastSeq = -1L
    }

    fun loadMedia(castId: String, url: String, title: String, durationMs: Long, startMs: Long) {
        val safeTitle = ControlProtocolV2.normalizedLabel(title, 200) ?: fallbackTitle
        // Per cast, and only per cast. A subtitle swap re-loads the SAME castId, and the
        // nudge the user dialled in belongs to the film they are still watching — so a
        // walk still in flight across that swap is left to land. Across a cast boundary
        // it is cancelled instead: every frame it has left carries whatever castId is
        // current, and a previous film's target is not a thing to aim this one at. The
        // receiver drops its own copy on the same boundary, which is what keeps the two
        // in step with no echo on the wire.
        if (this.castId != castId) {
            audioDelayWalk?.cancel()
            audioDelayWalk = null
            _audioDelayMs.value = AudioDelayPolicy.IN_SYNC_MS
        }
        this.castId = castId
        lastSeq = -1L
        seekPending = false
        playPending = false
        // A queued seek belongs to the cast it was tapped on and must never land against
        // this one; the fresh state below drops the run's head with it.
        skipBurst.cancel()
        _state.value = PlaybackUiState(
            title = safeTitle,
            durationMs = durationMs,
            targetMs = startMs,
            confirmedMs = startMs,
            playing = true,
            phase = PlaybackPhase.BUFFERING,
            // Stated rather than defaulted: this is the seed that makes the phone's
            // selection agree with the receiver, which resets to Auto per cast.
            rotation = VideoRotation.Auto,
        )
        control.send(
            cmd("loadMedia", castId)
                .put("url", url)
                .put("title", safeTitle)
                .put("durationMs", durationMs)
                .put("startMs", startMs),
        )
    }

    fun togglePlayPause() {
        val next = !_state.value.playing
        playCommanded = next
        playPending = true
        playPendingSinceMs = SystemClock.elapsedRealtime()
        _state.update { it.copy(playing = next) }
        control.send(cmd(if (next) "play" else "pause"))
        haptics.tryEmit(HapticCue.CONFIRM)
    }

    /**
     * One ±10s tap. It moves the head and the timecode and nothing else — the wire waits
     * for the tapping to stop ([SeekPolicy.QUIET_WINDOW_MS]), so the user can keep tapping
     * to wherever they meant and pay for one refill instead of one per tap.
     */
    fun skip(deltaMs: Long) {
        val s = _state.value
        val next = SeekPolicy.skipTarget(s.targetMs, deltaMs, s.durationMs)
        _state.update { it.copy(targetMs = next, skipping = true) }
        skipBurst.arm()
        haptics.tryEmit(HapticCue.CONFIRM)
    }

    /**
     * The end of a tap run: one absolute seek (idempotent, so it survives reordering) for
     * wherever the head ended up, issued through the same [beginSeek] every other seek
     * takes so no part of reconcile is special-cased for this path.
     *
     * The state is the authority on whether a seek is still owed: a window that elapses
     * after a scrub, a new cast or a stop has nothing left to commit.
     */
    private fun commitSkipBurst() {
        if (!_state.value.skipping) return
        _state.update { it.copy(skipping = false) }
        beginSeek(_state.value.targetMs)
    }

    /**
     * Land a run that is still accumulating, now. The remote spends this when it leaves the
     * screen: those taps were already paid out on a head the user watched move, so the seek
     * is owed either way — this only keeps it from arriving after the screen it was made on.
     */
    fun commitPendingSkip() = skipBurst.commitNow()

    fun scrubStart() {
        // A drag supersedes a tap run: without cancelling here, both mechanisms would send
        // a seek for what the user experienced as one gesture.
        skipBurst.cancel()
        _state.update { it.copy(scrubbing = true, skipping = false) }
        lastDetentBucket = _state.value.targetMs / DETENT_MS
        haptics.tryEmit(HapticCue.GRIP)
    }

    fun scrubTo(fraction: Float) {
        val s = _state.value
        if (s.durationMs <= 0L) return
        val next = (fraction.coerceIn(0f, 1f) * s.durationMs).toLong().coerceIn(0L, s.durationMs)
        val bucket = next / DETENT_MS
        if (bucket != lastDetentBucket) {
            lastDetentBucket = bucket
            haptics.tryEmit(HapticCue.DETENT)
        }
        val now = SystemClock.elapsedRealtime()
        val syncing = (now - lastConfirmedAtMs) > SYNC_GRACE_MS
        _state.update { it.copy(targetMs = next, syncing = syncing) }
        if (now - lastSeekAtMs >= SEEK_THROTTLE_MS) {
            lastSeekAtMs = now
            control.send(cmd("seek").put("posMs", next))
        }
    }

    fun scrubEnd() {
        val target = _state.value.targetMs
        control.send(cmd("seek").put("posMs", target))
        seekPending = true
        seekPendingSinceMs = SystemClock.elapsedRealtime()
        _state.update { it.copy(scrubbing = false) }
        haptics.tryEmit(HapticCue.SNAP)
    }

    /**
     * One absolute seek from a surface that has no gesture to track — the media
     * notification's scrubber, which reports where the thumb was let go and nothing
     * before it. [scrubStart]/[scrubTo]/[scrubEnd] are a drag's three halves and would
     * send that one landing twice; this is the [beginSeek] all of them end on, taken once.
     *
     * A drag or a tap run already in flight is superseded exactly as [scrubStart]
     * supersedes a run: the user pointed somewhere newer, and both mechanisms sending a
     * seek for what was one intent is the thing the burst timer exists to prevent.
     */
    fun seekTo(positionMs: Long) {
        skipBurst.cancel()
        val target = SeekPolicy.seekTarget(positionMs, _state.value.durationMs)
        _state.update { it.copy(scrubbing = false, skipping = false) }
        beginSeek(target)
        haptics.tryEmit(HapticCue.SNAP)
    }

    fun setVolume(level: Float) {
        val v = level.coerceIn(0f, 1f)
        _state.update { it.copy(volume = v) }
        control.send(cmd("setVolume").put("level", v.toDouble()))
    }

    /**
     * Turn the picture on the TV. Idempotent like every other absolute-valued verb,
     * so reordering cannot leave the film on its side.
     *
     * The selection is held optimistically — the same way [togglePlayPause] holds a
     * commanded play state — because no TV frame reports it back: [PlaybackUiState.rotation]
     * says why the `state` frame must not grow a field for it. [loadMedia] reseeds it
     * to Auto, which is the reset the receiver performs for every new cast.
     *
     * A frame with no `castId` is malformed, and a malformed frame costs the whole
     * control socket, so a verb with no cast to name is not sent at all.
     */
    fun setRotation(choice: VideoRotation) {
        val cast = castId ?: run {
            FlickLog.w("cast", "setRotation drop reason=no_cast choice=$choice")
            return
        }
        // Deliberately NOT deduped against the held selection. That selection is
        // optimistic — the TV's own panel can have moved the picture since — so it is
        // not evidence about the film and must not be allowed to decide what goes out.
        // Re-asserting costs nothing anyway: the receiver no-ops an unchanged choice,
        // and again an unchanged effective rotation, so nothing re-prepares unless the
        // picture really has to turn.
        val (field, value) = ControlProtocolV2.rotationField(choice)
        // The send is what decides whether the turn happened, so it runs BEFORE the
        // selection is recorded and before anything is logged as asked for. A frame that
        // never reached a socket would otherwise leave the phone holding a rotation the
        // TV was never told about, under a log line saying it had been — and these lines
        // are read to decide whether a turn that did not happen was lost on the phone or
        // ignored by the TV, which is the one question a false "sent" answers backwards.
        if (!control.send(cmd("setRotation", cast).put(field, value))) {
            // `send` has already named the transport reason; this names the turn.
            FlickLog.w("cast", "setRotation drop castIdFp=${FlickLog.fp(cast)} choice=$choice $field=$value")
            return
        }
        _state.update { it.copy(rotation = choice) }
        // Logged per field rather than by dumping the frame, which is the redaction
        // contract, and logged HERE rather than at the button because nothing on this
        // verb comes back: no ack, no state field, and a receiver that refuses it drops
        // the socket instead of answering. The chosen shape and the value that carries
        // it are the only evidence this phone can offer that the turn was ever asked for.
        FlickLog.i("cast", "setRotation castIdFp=${FlickLog.fp(cast)} choice=$choice $field=$value")
    }

    /**
     * Nudge the audio against the picture. Absolute-valued like every other verb here, so
     * a run of nudges is a stream of complete values: nothing accumulates on the wire, and
     * whichever one lands last is the one in force however they were reordered.
     *
     * A large move is WALKED rather than sent whole. Steady state costs the TV nothing in
     * either direction, but a change is paid for once by the picture in proportion to its
     * size, and past half a second the receiver's player abandons decoded buffers forward
     * to the next keyframe — a visible skip. So no single frame moves the picture by more
     * than [AudioDelayPolicy.MAX_JUMP_MS], and anything further arrives as a short run of
     * absolute values at [AudioDelayPolicy.WALK_INTERVAL_MS] a hop instead: bound to bound
     * across the whole ten-second range that is 40 frames landing inside 1.6 s, at the
     * same 25 a second the narrower range burst at. A stepper press and any drag short of
     * a flick are already inside that bound and go out in one frame, unwalked.
     *
     * Latest-wins, like the seek throttle: a new target cancels the walk in flight and is
     * approached from wherever that walk had reached, never from where it was aimed.
     */
    fun setAudioDelay(delayMs: Int) {
        val target = AudioDelayPolicy.clamp(delayMs)
        audioDelayWalk?.cancel()
        // No cast, no picture to protect and no frame to send — the walk exists only for
        // the TV's sake, so with no TV in it the value simply arrives.
        if (castId == null) {
            _audioDelayMs.value = target
            return
        }
        // The first hop is inline rather than launched: the scope is main-confined but a
        // launch is still dispatched, and a control under the finger must not wait a
        // frame to answer.
        val first = AudioDelayPolicy.approach(_audioDelayMs.value, target)
        emitAudioDelay(first)
        if (first == target) return
        audioDelayWalk = scope.launch {
            var value = first
            while (value != target) {
                delay(AudioDelayPolicy.WALK_INTERVAL_MS)
                value = AudioDelayPolicy.approach(value, target)
                emitAudioDelay(value)
            }
        }
    }

    private fun emitAudioDelay(value: Int) {
        _audioDelayMs.value = value
        // The frame has no castId-less form and there is nothing for a TV to nudge when
        // no cast owns it. The value is still held, because the phone is what displays it.
        val id = castId ?: return
        control.send(cmd("setAudioDelay", id).put("delayMs", value))
    }

    /** One stepper press: [later] moves the audio behind the picture, otherwise ahead of it. */
    fun nudgeAudioDelay(later: Boolean) {
        val current = _audioDelayMs.value
        setAudioDelay(
            if (later) AudioDelayPolicy.stepUp(current) else AudioDelayPolicy.stepDown(current),
        )
    }

    fun resetAudioDelay() = setAudioDelay(AudioDelayPolicy.IN_SYNC_MS)

    fun stop() {
        control.send(cmd("stop"))
        clear()
    }

    fun clear() {
        castId = null
        seekPending = false
        playPending = false
        skipBurst.cancel()
        audioDelayWalk?.cancel()
        audioDelayWalk = null
        lastSeq = -1L
        _state.value = PlaybackUiState()
        _audioDelayMs.value = AudioDelayPolicy.IN_SYNC_MS
    }

    // --- TV → phone --------------------------------------------------------

    /** Feed one decoded TV frame. Drops stale `state` by [seq]. */
    fun onFrame(obj: JSONObject) {
        val frameCastId = obj.optString("castId", "")
        if (frameCastId.isNotEmpty() && frameCastId != castId) return
        when (obj.optString("t")) {
            "state" -> onStateFrame(obj)
            "error" -> _state.update { it.copy(phase = PlaybackPhase.ERROR) }
            // "pong" is liveness only; ignored here.
        }
    }

    private fun onStateFrame(obj: JSONObject) {
        val seq = obj.optLong("seq", lastSeq + 1)
        if (seq < lastSeq) return
        lastSeq = seq
        lastConfirmedAtMs = SystemClock.elapsedRealtime()

        val pos = obj.optLong("posMs", _state.value.confirmedMs)
        val dur = obj.optLong("durationMs", _state.value.durationMs)
        val framePlaying = obj.optBoolean("playing", _state.value.playing)
        val buffered = obj.optLong("bufferedMs", 0L)
        val phase = phaseOf(obj.optString("phase"))
        val volume = if (obj.has("volume")) obj.optDouble("volume", _state.value.volume.toDouble()).toFloat()
        else _state.value.volume

        val prev = _state.value
        val reconcileNow: Boolean
        val newTarget: Long
        val newPlaying: Boolean
        val newSyncing: Boolean

        when {
            prev.scrubbing -> {
                // Head leads; ghost chases. Keep the user's target. SYNCING reflects
                // staleness (a Wi-Fi hiccup: no fresh state within the grace window),
                // NOT how far the confirmed position trails the thumb — while dragging a
                // long film the ghost legitimately lags by seconds, and driving the
                // shimmer off that divergence would keep it lit for the whole gesture
                // (control-channel.md §4: healthy sync is invisible). We just stamped
                // lastConfirmedAtMs above, so a fresh frame reads as not-stale (false);
                // scrubTo raises it when frames actually dry up.
                newTarget = prev.targetMs
                newPlaying = prev.playing
                newSyncing = (SystemClock.elapsedRealtime() - lastConfirmedAtMs) > SYNC_GRACE_MS
                reconcileNow = false
            }
            prev.skipping -> {
                // A tap run the user may still be adding to: the head is theirs, exactly as
                // under a drag. Ahead of `seekPending` deliberately — a second run can start
                // while an earlier seek is still outstanding, and that branch would adopt
                // `pos` and drag the bar off the position the thumb is building. SYNCING
                // stays down because nothing is in flight yet to be out of sync with; it
                // reads staleness only (control-channel.md §4: healthy sync is invisible),
                // and lastConfirmedAtMs was just stamped, so a fresh frame reads not-stale.
                newTarget = prev.targetMs
                newPlaying = prev.playing
                newSyncing = (SystemClock.elapsedRealtime() - lastConfirmedAtMs) > SYNC_GRACE_MS
                reconcileNow = false
            }
            seekPending -> {
                val outstandingMs = SystemClock.elapsedRealtime() - seekPendingSinceMs
                val outcome = SeekPolicy.pending(prev.targetMs, pos, phase, outstandingMs)
                if (outcome == SeekPolicy.Pending.WAITING) {
                    newTarget = prev.targetMs
                    newPlaying = prev.playing
                    newSyncing = true
                    reconcileNow = false
                } else {
                    // Arrived, or given up on: either way the TV's position is now the
                    // honest one. Only an arrival is a reconcile, so only it pulses.
                    seekPending = false
                    newTarget = pos
                    newPlaying = framePlaying
                    newSyncing = false
                    reconcileNow = outcome == SeekPolicy.Pending.ARRIVED
                }
            }
            else -> {
                // Idle: the head follows the TV so cross-surface commands mirror.
                newTarget = pos
                newSyncing = false
                reconcileNow = false
                if (playPending) {
                    val expired = SystemClock.elapsedRealtime() - playPendingSinceMs > PLAY_PENDING_MS
                    if (framePlaying == playCommanded || expired) {
                        // Our command took effect (or the window lapsed): adopt the
                        // authoritative value and stop suppressing. No haptic — the
                        // command already fired one at the tap.
                        playPending = false
                        newPlaying = framePlaying
                    } else {
                        // A stale in-flight frame still carries the pre-command value;
                        // hold the optimistic state and swallow its bogus transition.
                        newPlaying = prev.playing
                    }
                } else {
                    newPlaying = framePlaying
                    if (framePlaying != prev.playing) haptics.tryEmit(HapticCue.CONFIRM)
                }
            }
        }

        _state.value = prev.copy(
            confirmedMs = pos,
            durationMs = if (dur > 0L) dur else prev.durationMs,
            targetMs = newTarget,
            playing = newPlaying,
            bufferedMs = buffered,
            phase = phase,
            volume = volume,
            syncing = newSyncing,
        )
        if (reconcileNow) pulses.tryEmit(Unit)
    }

    private fun beginSeek(target: Long) {
        seekPending = true
        seekPendingSinceMs = SystemClock.elapsedRealtime()
        _state.update { it.copy(targetMs = target) }
        control.send(cmd("seek").put("posMs", target))
    }

    private fun cmd(t: String, overrideCastId: String? = castId): JSONObject =
        ControlProtocolV2.command(t, overrideCastId)

    private fun phaseOf(s: String?): PlaybackPhase = when (s) {
        "buffering" -> PlaybackPhase.BUFFERING
        "playing" -> PlaybackPhase.PLAYING
        "paused" -> PlaybackPhase.PAUSED
        "ended" -> PlaybackPhase.ENDED
        "error" -> PlaybackPhase.ERROR
        else -> PlaybackPhase.IDLE
    }

    private companion object {
        const val DETENT_MS = 10_000L        // haptic tick every 10s of film
        const val SEEK_THROTTLE_MS = 50L      // ≤ ~20 seeks/s (control-channel §4)
        const val SYNC_GRACE_MS = 250L        // hiccup threshold
        const val PLAY_PENDING_MS = 600L      // hold optimistic play/pause past stale frames
        const val GENERIC_TITLE = "Video"
    }
}
