package com.flick.sender

import com.flick.sender.model.PlaybackPhase

/**
 * Every decision the phone's media notification makes, as pure data: which transport verbs
 * are real right now, what stage the platform should be told playback is in, what the
 * second line says, and what has to change before the notification is worth re-posting.
 *
 * It lives here rather than inside [CastServerService] or [CastRemotePlayer] because
 * neither of those can be constructed in a plain JVM test — one is a `Service`, the other
 * needs a `Looper` and a Media3 `State` — so a decision left inside them could not be
 * tested at all. Nothing here knows about Media3 or Android: the two adapters map these
 * values onto `Player.STATE_*`, `Player.Commands` and string resources.
 */
internal object CastNotificationPolicy {

    /**
     * What one ±10s button is worth. It is deliberately the same ten seconds the remote's
     * transport sends and the TV's own D-pad seek uses: the notification is a second face
     * on one control, not a second control.
     */
    const val SKIP_INCREMENT_MS = 10_000L

    /** The platform's four playback stages, without Media3's constants. */
    enum class Stage { IDLE, BUFFERING, READY, ENDED }

    /**
     * Which transport verbs actually reach the TV. Anything false here is withheld from
     * both the notification and the session's available commands, because a media control
     * the user can press and that does nothing is worse than an absent one.
     */
    internal data class Controls(
        val playPause: Boolean,
        val skip: Boolean,
        val seek: Boolean,
        val stop: Boolean,
    ) {
        val anyTransport: Boolean get() = playPause || skip || seek
    }

    /** What the notification's second line is about. */
    enum class Line { CONNECTING, CASTING, FINISHED, SERVING }

    /**
     * Everything the posted notification renders. Re-posting is driven off this and not
     * off the position: a `state` frame arrives several times a second and re-posting on
     * each one would repaint the shade continuously for a scrubber the platform is
     * already advancing from the session on its own.
     */
    internal data class Shape(
        val title: String?,
        val deviceName: String?,
        val playing: Boolean,
        val line: Line,
        val controls: Controls,
    )

    fun stage(phase: PlaybackPhase): Stage = when (phase) {
        // ERROR joins IDLE deliberately: the coordinator turns a receiver error into a
        // terminal that tears this cast down, so the honest report for the frames before
        // that lands is "nothing is playing", not a player error the notification would
        // then have to explain.
        PlaybackPhase.IDLE, PlaybackPhase.ERROR -> Stage.IDLE
        PlaybackPhase.BUFFERING -> Stage.BUFFERING
        PlaybackPhase.PLAYING, PlaybackPhase.PAUSED -> Stage.READY
        PlaybackPhase.ENDED -> Stage.ENDED
    }

    /**
     * [commandable] is the whole gate: the control socket is up and the cast has reached
     * Active. Before that the TV has no player to command, and after a terminal there is
     * no cast left to command — Stop is the exception, because it is a local intent to the
     * source service and tears the cast down whether or not the TV is still listening.
     */
    fun controls(phase: PlaybackPhase, commandable: Boolean, durationMs: Long): Controls {
        val running = commandable && phase in RUNNING_PHASES
        return Controls(
            playPause = running,
            skip = running,
            // No duration means no scrubber: the Android media controls draw the seek bar
            // from the session's duration, and a bar over an unknown length is a fiction.
            seek = running && durationMs > 0L,
            stop = true,
        )
    }

    fun line(phase: PlaybackPhase, commandable: Boolean): Line = when {
        !commandable -> Line.CONNECTING
        phase == PlaybackPhase.ENDED -> Line.FINISHED
        else -> Line.CASTING
    }

    fun shape(snapshot: CastTransportSnapshot?): Shape = if (snapshot == null) {
        Shape(
            title = null,
            deviceName = null,
            playing = false,
            line = Line.SERVING,
            controls = Controls(playPause = false, skip = false, seek = false, stop = true),
        )
    } else {
        Shape(
            title = snapshot.title,
            deviceName = snapshot.deviceName,
            playing = snapshot.playing,
            line = line(snapshot.phase, snapshot.commandable),
            controls = controls(snapshot.phase, snapshot.commandable, snapshot.durationMs),
        )
    }

    /**
     * Whether the scrubber may run forward on its own between TV frames.
     *
     * Only a TV that says it is PLAYING is actually advancing: a BUFFERING frame carries
     * the position the player is filling toward, and a held head is where the user is
     * pointing. Extrapolating either would draw a film that is moving when it is not.
     */
    fun positionAdvances(phase: PlaybackPhase, playing: Boolean, headHeld: Boolean): Boolean =
        playing && !headHeld && phase == PlaybackPhase.PLAYING

    /**
     * The TV reports a buffered POSITION, and the head can legitimately lead it during a
     * seek the TV has not landed yet. The platform reads this as "buffered up to here", so
     * it may never sit behind the position it is qualifying.
     */
    fun bufferedPositionMs(positionMs: Long, bufferedMs: Long, durationMs: Long): Long {
        val floor = positionMs.coerceAtLeast(0L)
        val buffered = bufferedMs.coerceAtLeast(floor)
        return if (durationMs > 0L) buffered.coerceAtMost(durationMs) else buffered
    }

    private val RUNNING_PHASES = setOf(
        PlaybackPhase.BUFFERING,
        PlaybackPhase.PLAYING,
        PlaybackPhase.PAUSED,
    )
}
