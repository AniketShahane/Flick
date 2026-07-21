package com.flick.receiver.net

interface ControlCommands {
    /** Returns the retained outcome synchronously, before the server acknowledges a cast. */
    fun onLoadMedia(controlLeaseGeneration: Long, castId: String, url: String, title: String, durationMs: Long, startMs: Long): ControlCastResult
    fun replayResult(castId: String): ControlCastResult?
    /** Called only for the currently-owned control generation. */
    fun onControlLost(generation: Long)
    fun onPlay(castId: String)
    fun onPause(castId: String)
    fun onSeek(castId: String, posMs: Long)
    fun onSkip(castId: String, deltaMs: Long)
    fun onSetVolume(castId: String, level: Float)
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
