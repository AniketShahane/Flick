package com.flick.sender

import com.flick.sender.model.PlaybackPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The live cast as the phone's media notification sees it: everything the notification and
 * the platform media controls render, and nothing else. No `Uri`, no token, no URL, no
 * cast record — the notification is drawn on a lock screen.
 *
 * [positionMs] is the optimistic head, not the TV-confirmed position, for the same reason
 * the remote's scrub bar draws it: a run of ±10s taps moves the head before one seek
 * leaves, and a scrubber that only moved when the TV answered would read as taps thrown
 * away.
 */
internal data class CastTransportSnapshot(
    val castId: String,
    val title: String,
    /** The paired TV's name, or null while nothing has named it. */
    val deviceName: String?,
    val durationMs: Long,
    val positionMs: Long,
    /** The TV's buffered POSITION, as the `state` frame reports it — not a duration. */
    val bufferedMs: Long,
    val playing: Boolean,
    val phase: PlaybackPhase,
    /**
     * The head is the user's, mid-gesture (a tap run or a drag). It must not be
     * extrapolated forward: it is where they are pointing, not where the film is.
     */
    val headHeld: Boolean,
    /**
     * The control socket is up and this cast has reached Active — the only state in which
     * the TV has a player to command. Every transport verb is gated on it, so the
     * notification never offers a control that would silently do nothing.
     */
    val commandable: Boolean,
)

/**
 * The transport verbs, as the notification may spend them. Implemented by the coordinator
 * and therefore main-confined: `PlaybackSession`'s plain fields are safe only because
 * every caller is on the main thread, and a binder or notification thread reaching them
 * would be a data race. [CastRemotePlayer] satisfies that structurally — its application
 * looper IS the main looper and Media3 rejects a call from anywhere else.
 */
internal interface CastTransportCommands {
    fun togglePlaying()
    fun setPlaying(play: Boolean)
    fun skip(deltaMs: Long)
    fun seekTo(positionMs: Long)
    fun stop()
}

/**
 * Process-wide bridge between the cast coordinator (which owns the control socket and the
 * playback session) and [CastServerService] (which owns the notification), exactly as
 * [ServerStateHolder] bridges the served video and [SubtitleServingState] the subtitle.
 * Both live in the same process; neither needs Binder plumbing, and the service stays
 * free of any reference to `FlickApplication`.
 *
 * [state] is the single source of truth the notification and the player facade both read.
 * The coordinator republishes it **synchronously inside** every verb below, so a state
 * read immediately after a command already carries that command's optimistic result —
 * a `StateFlow` collector would only be resumed a dispatch later, which is exactly long
 * enough for the play/pause button to flicker back to what it was.
 */
internal object CastTransportState {

    private val _state = MutableStateFlow<CastTransportSnapshot?>(null)
    val state: StateFlow<CastTransportSnapshot?> = _state.asStateFlow()

    @Volatile
    private var commands: CastTransportCommands? = null

    fun attach(commands: CastTransportCommands) {
        this.commands = commands
    }

    /** Null while nothing is casting. */
    fun publish(snapshot: CastTransportSnapshot?) {
        _state.value = snapshot
    }

    fun togglePlaying() {
        commands?.togglePlaying()
    }

    fun setPlaying(play: Boolean) {
        commands?.setPlaying(play)
    }

    fun skip(deltaMs: Long) {
        commands?.skip(deltaMs)
    }

    fun seekTo(positionMs: Long) {
        commands?.seekTo(positionMs)
    }

    fun stop() {
        commands?.stop()
    }
}
