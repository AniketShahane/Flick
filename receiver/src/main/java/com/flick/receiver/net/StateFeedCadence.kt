package com.flick.receiver.net

import kotlin.math.abs

/**
 * Which ticks of the `state` feed actually go on the wire.
 *
 * Every frame costs the phone a parse, a state fan-out and a media-session update, and
 * with its screen off, where no Wi-Fi lock holds on current Android, a radio wake out of
 * power save. A feed that never changes pace therefore drains the phone for the whole
 * film, and worst of all while it sits paused or finished. The feed is sampled every
 * tick but emitted only while "hot" —
 * for [HOT_WINDOW_MS] after something the phone has to see promptly — and otherwise
 * once every [COLD_INTERVAL_MS].
 *
 * The cold interval is bounded from above by the phone, not by taste: it treats a
 * frame younger than two seconds as proof the line is alive, so a cold gap must stay
 * well inside that with room for a late tick and the network.
 *
 * The position check compares consecutive SAMPLES rather than the last emitted frame.
 * The sampled frame is refreshed on the main thread and only while the app is
 * STARTED, so its position can freeze while `playing` stays true (a main-thread stall,
 * a backgrounded app); measured against a frame up to a second old, that freeze reads
 * as a jump on every cold emission and keeps the feed hot forever. A seek moves the
 * position between two ticks, which is exactly what the sample-to-sample check sees.
 *
 * Writes nothing and knows nothing about the wire: [onTick] answers whether this tick
 * is emitted, and the caller emits exactly when it says so. One instance per control
 * connection, used from one coroutine.
 */
internal class StateFeedCadence {

    private class Emitted(
        val castId: String,
        val durationMs: Long,
        val playing: Boolean,
        val phase: String,
        val volume: Double,
        val atMs: Long,
    )

    private class Sample(val posMs: Long, val advancing: Boolean, val atMs: Long)

    private var lastEmitted: Emitted? = null
    private var lastSample: Sample? = null
    private var hotUntilMs = NEVER

    /**
     * Whether the tick at [nowMs] is emitted. The fields are the frame's own values
     * after its coercions; [lastCommandAtMs] is when the phone last sent a command
     * other than `ping` on this connection, or [NEVER].
     */
    fun onTick(
        nowMs: Long,
        castId: String,
        posMs: Long,
        durationMs: Long,
        playing: Boolean,
        phase: String,
        volume: Double,
        lastCommandAtMs: Long,
    ): Boolean {
        val emitted = lastEmitted
        val sample = lastSample
        val triggered = emitted == null ||
            emitted.castId != castId ||
            emitted.playing != playing ||
            emitted.phase != phase ||
            emitted.durationMs != durationMs ||
            emitted.volume != volume ||
            phase == PHASE_BUFFERING ||
            (sample != null && jumped(sample, posMs, nowMs))
        lastSample = Sample(posMs, advancing = playing && phase == PHASE_PLAYING, atMs = nowMs)

        if (triggered) hotUntilMs = maxOf(hotUntilMs, nowMs + HOT_WINDOW_MS)
        if (lastCommandAtMs != NEVER) hotUntilMs = maxOf(hotUntilMs, lastCommandAtMs + HOT_WINDOW_MS)

        val emit = triggered ||
            nowMs < hotUntilMs ||
            nowMs - emitted.atMs >= COLD_INTERVAL_MS
        if (emit) lastEmitted = Emitted(castId, durationMs, playing, phase, volume, nowMs)
        return emit
    }

    /** The cast stopped being speakable: the next speakable tick starts hot. */
    fun reset() {
        lastEmitted = null
        lastSample = null
        hotUntilMs = NEVER
    }

    private fun jumped(previous: Sample, posMs: Long, nowMs: Long): Boolean {
        val predictedMs = if (previous.advancing) previous.posMs + (nowMs - previous.atMs) else previous.posMs
        return abs(posMs - predictedMs) > JUMP_MS
    }

    companion object {
        const val NEVER = Long.MIN_VALUE

        /**
         * Whether an inbound frame of [type] heats the feed. `ping` does not: the phone
         * sends one every few seconds for its link check alone, and counting it would
         * keep the feed hot for a third of every film.
         */
        fun heats(type: String?): Boolean = type != null && type != PING

        /** How long the feed stays at the full tick rate after a trigger. */
        const val HOT_WINDOW_MS = 3_000L

        /** The longest a speakable cast goes without a frame, plus at most one tick. */
        const val COLD_INTERVAL_MS = 1_000L

        /** A position move this far from the prediction is a seek, not sampling jitter. */
        const val JUMP_MS = 1_000L

        private const val PING = "ping"
        private const val PHASE_PLAYING = "playing"
        private const val PHASE_BUFFERING = "buffering"
    }
}
