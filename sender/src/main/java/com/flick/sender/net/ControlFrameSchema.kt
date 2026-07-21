package com.flick.sender.net

/** Pure strict schema validator shared by the Ktor boundary and JVM tests. */
object ControlFrameSchema {
    private val failureCodes = setOf(
        "update_required", "control_unreachable", "source_unavailable", "no_compatible_lan", "media_bind_failed",
        "host_mismatch", "media_unreachable", "sender_not_serving", "http_rejected", "tv_backgrounded",
        "malformed_media", "unsupported_container", "unsupported_video_format", "unsupported_video_codec",
        "unsupported_hdr_profile", "decoder_init", "startup_timeout", "control_disconnected", "active_cast_busy",
        "protocol_error", "unknown",
    )

    fun preAuth(frame: Map<String, Any?>): Boolean = when (frame["t"]) {
        "negotiated" -> exact(frame, setOf("t", "v", "clientNonce", "serverNonce", "tvId", "cap")) &&
            version(frame) && id(frame["clientNonce"]) && id(frame["serverNonce"]) && id(frame["tvId"]) && caps(frame["cap"])
        "paired" -> endpoint(frame, setOf("t", "v", "key", "keyId", "tv", "tvId", "peerIp", "serverHost", "serverPort", "cap")) && key(frame["key"])
        "resumeChallenge" -> endpoint(frame, setOf("t", "v", "tv", "tvId", "keyId", "clientNonce", "serverNonce", "peerIp", "serverHost", "serverPort", "cap")) && id(frame["clientNonce"]) && id(frame["serverNonce"])
        "resumed" -> endpoint(frame, setOf("t", "v", "tv", "tvId", "keyId", "clientNonce", "serverNonce", "peerIp", "serverHost", "serverPort", "cap", "proof")) && id(frame["clientNonce"]) && id(frame["serverNonce"]) && key(frame["proof"])
        "denied" -> exact(frame, setOf("t", "v")) && version(frame)
        else -> false
    }

    fun event(frame: Map<String, Any?>): Boolean {
        if (!version(frame)) return false
        return when (frame["t"]) {
            "loadAccepted" -> exact(frame, setOf("t", "v", "castId")) && id(frame["castId"])
            "loadReady" -> exact(frame, setOf("t", "v", "castId", "probeLatencyMs", "startupMs")) && id(frame["castId"]) && millis(frame["probeLatencyMs"], 60_000) && millis(frame["startupMs"], 60_000)
            "loadFailed", "error" -> failure(frame)
            "state" -> exact(frame, setOf("t", "v", "castId", "posMs", "durationMs", "playing", "bufferedMs", "phase", "volume", "seq")) &&
                id(frame["castId"]) && millis(frame["posMs"], 604_800_000) && millis(frame["durationMs"], 604_800_000) && millis(frame["bufferedMs"], 604_800_000) &&
                frame["playing"] is Boolean && (frame["phase"] as? String) in setOf("buffering", "playing", "paused", "ended") && finiteIn(frame["volume"], 0.0, 1.0) && integer(frame["seq"], 0, Long.MAX_VALUE)
            "stopped" -> exact(frame, setOf("t", "v", "castId")) && id(frame["castId"])
            "commandRejected" -> exact(frame, setOf("t", "v", "castId", "command", "code")) && id(frame["castId"]) && ascii(frame["command"], 32) && ascii(frame["code"], 32)
            "pong" -> exact(frame, setOf("t", "v", "id")) && id(frame["id"])
            "busy" -> exact(frame, setOf("t", "v", "reason")) && frame["reason"] == "active_cast"
            else -> false
        }
    }

    private fun failure(frame: Map<String, Any?>): Boolean {
        val withHttp = setOf("t", "v", "castId", "code", "retryable", "httpStatus")
        val withoutHttp = withHttp - "httpStatus"
        return (exact(frame, withHttp) || exact(frame, withoutHttp)) && id(frame["castId"]) &&
            (frame["code"] as? String) in failureCodes && frame["retryable"] is Boolean && (!frame.containsKey("httpStatus") || integer(frame["httpStatus"], 100, 599))
    }

    private fun endpoint(frame: Map<String, Any?>, fields: Set<String>): Boolean = exact(frame, fields) && version(frame) &&
        id(frame["tvId"]) && id(frame["keyId"]) && label(frame["tv"], 80) && privateIp(frame["peerIp"]) && privateIp(frame["serverHost"]) && integer(frame["serverPort"], 1, 65535) && caps(frame["cap"])
    private fun exact(frame: Map<String, Any?>, fields: Set<String>) = frame.keys == fields
    private fun version(frame: Map<String, Any?>) = integer(frame["v"], 2, 2)
    private fun id(value: Any?) = value is String && ControlProtocolV2.id(value)
    private fun key(value: Any?) = value is String && ControlProtocolV2.key(value)
    private fun caps(value: Any?) = value is List<*> && value.all { it is String } && ControlProtocolV2.canonicalCaps(value.filterIsInstance<String>())
    private fun label(value: Any?, limit: Int) = value is String && ControlProtocolV2.normalizedLabel(value, limit) == value
    private fun ascii(value: Any?, limit: Int) = value is String && value.length in 1..limit && value.all { it.code in 0x20..0x7e }
    private fun privateIp(value: Any?) = value is String && PairLaunch.isCanonicalIpv4(value)
    private fun millis(value: Any?, maximum: Long) = integer(value, 0, maximum)
    /** JSONObject retains integral JSON tokens as Int/Long; reject 2.0 and 2e0 for integer fields. */
    private fun integer(value: Any?, minimum: Long, maximum: Long): Boolean =
        (value is Int || value is Long) && (value as Number).toLong() in minimum..maximum
    private fun finiteIn(value: Any?, minimum: Double, maximum: Double): Boolean = value is Number && value.toDouble().isFinite() && value.toDouble() in minimum..maximum
}
