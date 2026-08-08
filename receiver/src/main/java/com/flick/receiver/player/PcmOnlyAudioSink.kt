package com.flick.receiver.player

import androidx.media3.common.Format
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/**
 * A sink that refuses every compressed bitstream, so `MediaCodecAudioRenderer`
 * stops looking for a passthrough route and goes and finds a decoder.
 *
 * ## Why the refusal has to live here
 *
 * The obvious lever — handing `DefaultAudioSink.Builder` a PCM-only
 * [androidx.media3.exoplayer.audio.AudioCapabilities] — does not hold. In media3
 * 1.10.1 the builder forwards those capabilities to an
 * `AudioTrackAudioOutputProvider`, which registers an `AudioCapabilitiesReceiver`
 * and overwrites the field with the receiver's answer as soon as it starts:
 *
 * ```
 * audioCapabilitiesReceiver.register()  →  putfield audioCapabilities
 * ```
 *
 * The asserted value survives only until the provider looks at the platform, and
 * the platform's answer is the defect being worked around (see [AudioOutputPolicy]).
 * A sink built that way still selects AC-3 passthrough and still dies — observed on
 * the verified hardware as `Config(48000, 252, 5, 40000)`, encoding 5 being
 * `AudioFormat.ENCODING_AC3`, after the rebuild had supposedly disabled it.
 *
 * Wrapping the finished sink is not subject to that. `getFormatSupport` and
 * `supportsFormat` are the two questions `MediaCodecAudioRenderer` actually asks
 * before choosing passthrough — the first gates `getDecoderInfos`, the second gates
 * the `FORMAT_HANDLED` fast path in `supportsFormat` — and answering both from here
 * cannot be overwritten by anything downstream.
 *
 * ## What is and is not refused
 *
 * Only the *encoding* is refused, never the channel count. A decoded 5.1 track is
 * six channels of linear PCM and is forwarded untouched; AudioFlinger downmixes it
 * to whatever the route actually has. Refusing width here would throw away the
 * surround this exists to deliver.
 *
 * Linear PCM is detected exactly as `DefaultAudioSink` detects it, by
 * [Util.isEncodingLinearPcm] over `pcmEncoding`, so the two agree on where the
 * boundary is.
 *
 * A format with no decoder on the device — DTS on the verified TV — is unplayable
 * either way: refused here, and refused by the route that made this necessary. It
 * ends up with its audio track disabled and the picture still running, which is the
 * same outcome media3 reaches on its own.
 */
class PcmOnlyAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {

    override fun supportsFormat(format: Format): Boolean =
        if (isCompressed(format)) false else super.supportsFormat(format)

    override fun getFormatSupport(format: Format): Int =
        if (isCompressed(format)) AudioSink.SINK_FORMAT_UNSUPPORTED else super.getFormatSupport(format)

    private fun isCompressed(format: Format): Boolean = !Util.isEncodingLinearPcm(format.pcmEncoding)
}
