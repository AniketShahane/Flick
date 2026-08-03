package com.flick.receiver.net

/**
 * A sideloaded subtitle the sender is serving alongside the media, already
 * validated against the same pinning rules as the media URL. Absent means the
 * cast carries no external subtitle at all — the container's own text tracks
 * are the only ones offered.
 */
data class ExternalSubtitle(
    val url: String,
    val label: String,
    /** BCP-47; null when the sender could not determine one. */
    val language: String?,
)

interface ControlCommands {
    /** Returns the retained outcome synchronously, before the server acknowledges a cast. */
    fun onLoadMedia(
        controlLeaseGeneration: Long,
        castId: String,
        url: String,
        title: String,
        durationMs: Long,
        startMs: Long,
        subtitle: ExternalSubtitle?,
    ): ControlCastResult
    /**
     * A repeated `loadMedia` for the cast that is already running. Returns a new
     * outcome only when the frame asks for something the running session cannot
     * express without re-preparing — today, a different external subtitle.
     * Returning null means nothing actionable changed and the retained result is
     * still the honest answer, so an ordinary retransmit never costs a re-buffer.
     */
    fun onReloadMedia(
        controlLeaseGeneration: Long,
        castId: String,
        url: String,
        title: String,
        durationMs: Long,
        startMs: Long,
        subtitle: ExternalSubtitle?,
    ): ControlCastResult?
    fun replayResult(castId: String): ControlCastResult?
    /** Called only for the currently-owned control generation. */
    fun onControlLost(generation: Long)
    fun onPlay(castId: String)
    fun onPause(castId: String)
    fun onSeek(castId: String, posMs: Long)
    fun onSkip(castId: String, deltaMs: Long)
    fun onSetVolume(castId: String, level: Float)

    /**
     * An explicit picture rotation, in quarter turns applied ON TOP of whatever
     * the container declares. The phone is asserting an orientation here; it
     * gives up the receiver's own reading of the file by doing so.
     */
    fun onSetRotation(castId: String, degrees: Int)

    /**
     * The other half of the same verb: give the reading back. Separate from
     * [onSetRotation] because Auto is not a turn — it is the verdict the
     * receiver recomputes for whatever is playing, and no degree stands for it.
     */
    fun onSetAutoRotation(castId: String)

    /**
     * A/V sync nudge for the running cast. Positive means audio heard LATER than
     * the picture; the range and step are `AudioDelayPolicy`'s, and the server has
     * already refused anything outside them. Never reported back — the phone is
     * the display source of truth.
     */
    fun onSetAudioDelay(castId: String, delayMs: Int)
    /** True only when this cancelled the current pre-ready cast. */
    fun onCancelLoad(castId: String): Boolean
    /** True only when this stopped the current preparing or active cast. */
    fun onStop(castId: String): Boolean
}

sealed interface ControlCastResult {
    data class Accepted(val castId: String) : ControlCastResult
    data class Ready(val castId: String, val probeLatencyMs: Long, val startupMs: Long) : ControlCastResult
    data class Failed(
        val castId: String,
        val code: CastFailureCode,
        val retryable: Boolean,
        val httpStatus: Int? = null,
        val beforeReady: Boolean = true,
    ) : ControlCastResult
    data class Stopped(val castId: String) : ControlCastResult
}

enum class CastFailureCode(val wire: String) {
    NO_COMPATIBLE_LAN("no_compatible_lan"), MEDIA_BIND_FAILED("media_bind_failed"), HOST_MISMATCH("host_mismatch"),
    MEDIA_UNREACHABLE("media_unreachable"), SENDER_NOT_SERVING("sender_not_serving"), HTTP_REJECTED("http_rejected"),
    TV_BACKGROUNDED("tv_backgrounded"), MALFORMED_MEDIA("malformed_media"), UNSUPPORTED_CONTAINER("unsupported_container"),
    UNSUPPORTED_VIDEO_FORMAT("unsupported_video_format"), UNSUPPORTED_VIDEO_CODEC("unsupported_video_codec"),
    UNSUPPORTED_HDR_PROFILE("unsupported_hdr_profile"), DECODER_INIT("decoder_init"), STARTUP_TIMEOUT("startup_timeout"),
    CONTROL_DISCONNECTED("control_disconnected"), ACTIVE_CAST_BUSY("active_cast_busy"), PROTOCOL_ERROR("protocol_error"), UNKNOWN("unknown")
}
