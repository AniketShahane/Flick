package com.flick.receiver.net

/**
 * The inbound control verbs (control-channel.md §4, phone → TV), implemented by
 * the app and invoked by [ControlServer] AFTER it has marshalled the call onto
 * the main thread. Implementations touch ExoPlayer directly, so they must never
 * be called off-main.
 *
 * `loadMedia` host-validation is enforced by the server BEFORE this is called;
 * implementations still run the existing pre-flight probe before playing.
 */
interface ControlCommands {
    fun onLoadMedia(url: String, title: String?, durationMs: Long, startMs: Long)
    fun onPlay()
    fun onPause()
    /** Absolute optimistic-target seek. */
    fun onSeek(posMs: Long)
    /** Relative skip (±10000 ms per contract). */
    fun onSkip(deltaMs: Long)
    fun onSetVolume(level: Float)
    fun onStop()
}

/** `error` frame codes (control-channel.md §4, TV → phone). */
object ControlErrors {
    const val HOST_MISMATCH = "host_mismatch"
    const val LOAD_REJECTED = "load_rejected"

    // `message` reasons that accompany a LOAD_REJECTED error frame, so the phone
    // can route S12 (design) with a specific cause rather than a frozen NowPlaying.
    const val REASON_UNREACHABLE = "unreachable"
    const val REASON_NOT_SERVING = "not_serving"
    const val REASON_TV_BACKGROUNDED = "tv_backgrounded"
}
