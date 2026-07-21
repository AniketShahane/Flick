package com.flick.receiver.player

/**
 * API 29 exposes a codec's hardware flag. API 26-28 do not, so direct-play
 * fails closed unless the decoder has a verified MediaTek hardware namespace
 * used by the supported Google TV Streamer. An empty filtered list intentionally
 * makes Media3 report an unsupported video format rather than using software.
 */
object HardwareDecoderPolicy {
    fun isHardwareVideoCodec(name: String, apiLevel: Int, hardwareAccelerated: Boolean?): Boolean {
        if (apiLevel >= 29 && hardwareAccelerated != null) return hardwareAccelerated
        val value = name.lowercase()
        return value.startsWith("c2.mtk.") || value.startsWith("omx.mtk.")
    }

    fun hasHardwareVideoCodec(
        codecNames: Iterable<String>,
        apiLevel: Int,
        hardwareFlags: Iterable<Boolean?> = emptyList(),
    ): Boolean {
        val flags = hardwareFlags.iterator()
        return codecNames.any { isHardwareVideoCodec(it, apiLevel, if (flags.hasNext()) flags.next() else null) }
    }
}
