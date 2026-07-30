package com.flick.receiver.player

/**
 * Which decoders direct-play is willing to hand a video format to. An empty filtered
 * list intentionally makes Media3 report an unsupported video format rather than
 * using software: the whole thesis is that the TV decodes the original bytes in
 * hardware, and a silent software fallback for 4K would hide the one failure worth
 * seeing.
 *
 * `MediaCodecInfo.hardwareAccelerated` carries a usable answer on **every** supported
 * API level, not only on 29+. `android.media.MediaCodecInfo.isHardwareAccelerated()`
 * is the API 29 platform flag, and below that Media3 derives the same boolean from
 * the codec's namespace — it is the negative signal that is reliable, so it names the
 * software/reference decoders and treats every other vendor namespace as hardware.
 * [isSoftwareOnlyCodecName] mirrors that list; a positive allow-list of vendor
 * prefixes cannot be maintained against the Android TV market, and the one this file
 * used to carry (`c2.mtk.`/`omx.mtk.`) refused to play at all on the Amlogic,
 * Realtek, Broadcom, Qualcomm and Samsung silicon that most of the API 26-28
 * installed base runs.
 */
object HardwareDecoderPolicy {

    /**
     * The software and reference decoder namespaces, and the shape of a name that
     * belongs to no namespace at all.
     *
     * `arc.` is the exception that has to be checked first: App Runtime for Chrome
     * decoders are hardware-backed despite matching none of the vendor prefixes.
     * A name outside both `omx.` and `c2.` is treated as software because it is not a
     * platform decoder namespace — failing closed there preserves the intent of the
     * original allow-list without naming a single vendor.
     */
    fun isSoftwareOnlyCodecName(name: String): Boolean {
        val value = name.lowercase()
        if (value.startsWith("arc.")) return false
        return value.startsWith("omx.google.") ||
            value.startsWith("omx.ffmpeg.") ||
            (value.startsWith("omx.sec.") && value.contains(".sw.")) ||
            value == "omx.qcom.video.decoder.hevcswvdec" ||
            value.startsWith("c2.android.") ||
            value.startsWith("c2.google.") ||
            !(value.startsWith("omx.") || value.startsWith("c2."))
    }

    /**
     * [hardwareAccelerated] is Media3's own verdict and is preferred whenever it is
     * supplied, at any API level — it folds in the platform flag on 29+ and the
     * device-specific workarounds Media3 maintains below it. The name inversion is
     * only the fallback for a caller that has no `MediaCodecInfo` to ask.
     */
    fun isHardwareVideoCodec(name: String, hardwareAccelerated: Boolean?): Boolean {
        if (hardwareAccelerated != null) return hardwareAccelerated
        return !isSoftwareOnlyCodecName(name)
    }

    fun hasHardwareVideoCodec(
        codecNames: Iterable<String>,
        hardwareFlags: Iterable<Boolean?> = emptyList(),
    ): Boolean {
        val flags = hardwareFlags.iterator()
        return codecNames.any { isHardwareVideoCodec(it, if (flags.hasNext()) flags.next() else null) }
    }
}
