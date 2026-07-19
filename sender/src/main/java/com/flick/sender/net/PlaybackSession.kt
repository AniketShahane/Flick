package com.flick.sender.net

import android.os.SystemClock
import com.flick.sender.model.PlaybackPhase
import com.flick.sender.model.PlaybackUiState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import kotlin.math.abs

/**
 * The hero's brain (design Part 4 + control-channel.md §4): one session clock held
 * as two numbers. [PlaybackUiState.targetMs] is the optimistic head the thumb
 * drives; [PlaybackUiState.confirmedMs] is the last TV-reported position that
 * trails. While a seek is in flight the head **leads** and the ghost **chases**;
 * when they collapse the session reconciles and fires a shared Spark pulse. When
 * idle, the head follows the TV so a cross-surface pause/seek mirrors on the phone.
 *
 * Commands go out through [ControlClient]; absolute-valued verbs (`seek posMs`,
 * `setVolume level`) are idempotent so reordering can't corrupt the position.
 */
class PlaybackSession(private val control: ControlClient) {

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

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

    // --- commands ----------------------------------------------------------

    /**
     * A fresh control session opened (connect/resume): drop the stale `seq` watermark
     * so `state` frames from a restarted TV (whose seq counter restarts near 0) aren't
     * discarded as stale until the next loadMedia. Idempotent; safe on every connect.
     */
    fun onConnected() {
        lastSeq = -1L
    }

    fun loadMedia(url: String, title: String, durationMs: Long, startMs: Long) {
        lastSeq = -1L
        seekPending = false
        playPending = false
        _state.value = PlaybackUiState(
            title = title,
            durationMs = durationMs,
            targetMs = startMs,
            confirmedMs = startMs,
            playing = true,
            phase = PlaybackPhase.BUFFERING,
        )
        control.send(
            cmd("loadMedia")
                .put("url", url)
                .put("title", title)
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

    fun skip(deltaMs: Long) {
        val s = _state.value
        // With an unknown duration (durationMs == 0 — MediaStore had none and no
        // state frame with dur>0 has arrived yet), clamp only at the low end; the
        // receiver clamps the high end to the real duration. Without this, +10s
        // would coerceIn(0,0) → seek 0 and restart playback from the start.
        val next = if (s.durationMs > 0L) {
            (s.targetMs + deltaMs).coerceIn(0L, s.durationMs)
        } else {
            (s.targetMs + deltaMs).coerceAtLeast(0L)
        }
        // Absolute seek (idempotent) carries the ±10s intent; the TV also accepts
        // the relative `skip` verb, but the absolute form survives reordering.
        beginSeek(next)
        haptics.tryEmit(HapticCue.CONFIRM)
    }

    fun scrubStart() {
        _state.update { it.copy(scrubbing = true) }
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

    fun setVolume(level: Float) {
        val v = level.coerceIn(0f, 1f)
        _state.update { it.copy(volume = v) }
        control.send(cmd("setVolume").put("level", v.toDouble()))
    }

    fun stop() {
        control.send(cmd("stop"))
        clear()
    }

    fun clear() {
        seekPending = false
        playPending = false
        lastSeq = -1L
        _state.value = PlaybackUiState()
    }

    // --- TV → phone --------------------------------------------------------

    /** Feed one decoded TV frame. Drops stale `state` by [seq]. */
    fun onFrame(obj: JSONObject) {
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
            seekPending -> {
                val collapsed = abs(prev.targetMs - pos) <= RECONCILE_MS
                val timedOut = SystemClock.elapsedRealtime() - seekPendingSinceMs > SEEK_TIMEOUT_MS
                if (collapsed || timedOut) {
                    seekPending = false
                    newTarget = pos
                    newPlaying = framePlaying
                    newSyncing = false
                    reconcileNow = collapsed
                } else {
                    newTarget = prev.targetMs
                    newPlaying = prev.playing
                    newSyncing = true
                    reconcileNow = false
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

    private fun cmd(t: String) = JSONObject().put("t", t).put("v", 1)

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
        const val RECONCILE_MS = 400L         // "collapsed" window
        const val SEEK_TIMEOUT_MS = 3_000L    // don't get stuck if TV never catches up
        const val PLAY_PENDING_MS = 600L      // hold optimistic play/pause past stale frames
    }
}
