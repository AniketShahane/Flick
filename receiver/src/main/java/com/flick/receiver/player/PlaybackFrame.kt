package com.flick.receiver.player

/**
 * Coarse playback phase reported to the phone over the control channel
 * (control-channel.md §4 `state.phase`). Wire values are lowercase and stable.
 */
enum class PlaybackPhase(val wire: String) {
    Idle("idle"),
    Buffering("buffering"),
    Playing("playing"),
    Paused("paused"),
    Ended("ended"),
    Error("error"),
}

/**
 * The TV's authoritative playback position + phase, sampled on the main thread
 * from ExoPlayer. This is the "confirmed" feed the phone's ghost playhead trails
 * (design Part 4). `seq` is stamped by the control server as it emits, not here.
 */
data class PlaybackFrame(
    val posMs: Long,
    val durationMs: Long,
    val playing: Boolean,
    val bufferedMs: Long,
    val phase: PlaybackPhase,
    val volume: Float,
) {
    companion object {
        val IDLE = PlaybackFrame(
            posMs = 0L,
            durationMs = 0L,
            playing = false,
            bufferedMs = 0L,
            phase = PlaybackPhase.Idle,
            volume = 1f,
        )
    }
}
