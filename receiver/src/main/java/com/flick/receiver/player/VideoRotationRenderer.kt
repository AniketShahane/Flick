package com.flick.receiver.player

import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer

/**
 * The rotation the video decoder is being configured with, written on the main
 * thread and read on the playback thread.
 *
 * Volatile rather than synchronized because there is exactly one word of state
 * and the read happens inside codec configuration, on the thread that must not
 * be made to wait for a lock. Every write is followed by a `prepare()` on the
 * same thread, which posts to the playback thread and therefore carries the new
 * value across with it.
 */
class VideoRotationOverride {

    @Volatile
    private var extraDegrees: Int = 0

    /** True when this actually changed the value the decoder will be given. */
    fun setExtraDegrees(degrees: Int): Boolean {
        if (degrees == extraDegrees) return false
        extraDegrees = degrees
        return true
    }

    fun applyTo(containerRotationDegrees: Int): Int =
        effectiveRotationDegrees(containerRotationDegrees, extraDegrees)
}

/**
 * The video renderer, with one line changed: the rotation it hands the decoder.
 *
 * This is deliberately the SAME zero-cost path a correctly tagged file already
 * travels. `MediaCodecVideoRenderer.getMediaFormat` copies `rotationDegrees`
 * into `MediaFormat.KEY_ROTATION`, and from API 21 the decoder applies it while
 * rendering to the surface — media3 1.10.1's `onOutputFormatChanged` then swaps
 * width/height and inverts the sample aspect for a 90/270 turn, which is why
 * `VideoSize.unappliedRotationDegrees` is always 0 and why `PlayerView` needs no
 * help to letterbox the result. Rewriting the format the renderer is fed
 * therefore costs exactly what the file's own rotation costs: nothing. No
 * effects pipeline, no GL pass, no `TextureView` — the overlay, tunneling and
 * HDR paths are untouched.
 *
 * Media3 itself rewrites `formatHolder.format` in `BaseRenderer.readSource`, so
 * mutating the holder before delegating is the supported shape rather than a
 * trick; the `SampleQueue` behind it keeps its own reference and is not touched.
 *
 * Constructed by [FlickRenderersFactory], which then wraps it in
 * [AudioDelayVideoRenderer]. Being a SUBCLASS rather than a stock
 * `MediaCodecVideoRenderer` is load-bearing for one thing beyond rotation: it
 * takes media3's pre-warming path off the table permanently — see
 * `FlickRenderersFactory.createSecondaryRenderer`.
 */
internal class RotationCorrectingVideoRenderer(
    builder: MediaCodecVideoRenderer.Builder,
    private val rotation: VideoRotationOverride,
) : MediaCodecVideoRenderer(builder) {

    override fun onInputFormatChanged(formatHolder: FormatHolder): DecoderReuseEvaluation? {
        val format = formatHolder.format
        if (format != null) {
            val corrected = rotation.applyTo(format.rotationDegrees)
            if (corrected != format.rotationDegrees) {
                formatHolder.format = format.buildUpon().setRotationDegrees(corrected).build()
            }
        }
        return super.onInputFormatChanged(formatHolder)
    }
}
