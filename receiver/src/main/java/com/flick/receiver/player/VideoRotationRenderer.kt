package com.flick.receiver.player

import android.content.Context
import android.os.Handler
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

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
 */
private class RotationCorrectingVideoRenderer(
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

/**
 * [DefaultRenderersFactory] with the video renderer replaced by the one above.
 *
 * The builder chain mirrors `DefaultRenderersFactory.buildVideoRenderers` in
 * media3 1.10.1 exactly, including its 50-frame drop-notify threshold. The
 * experimental flags it forwards from its own fields are omitted because this
 * module never sets them and their defaults are identical on both sides
 * (`parseAv1SampleDependencies` true, `lateThresholdToDropDecoderInputUs`
 * 15000 µs) — the builder's own defaults reproduce them.
 *
 * The extension-renderer branch of the original is dropped rather than
 * reproduced: the receiver pins `EXTENSION_RENDERER_MODE_OFF` because
 * hardware-only decode is the product thesis, so that branch is unreachable
 * here. `buildSecondaryVideoRenderer` is likewise left alone — the base
 * implementation gates on prewarming AND on an exact `MediaCodecVideoRenderer`
 * class match, so it can never build a second decoder for this subclass.
 */
class RotationCorrectingRenderersFactory(
    context: Context,
    private val rotation: VideoRotationOverride,
) : DefaultRenderersFactory(context) {

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        out.add(
            RotationCorrectingVideoRenderer(
                MediaCodecVideoRenderer.Builder(context)
                    .setCodecAdapterFactory(getCodecAdapterFactory())
                    .setMediaCodecSelector(mediaCodecSelector)
                    .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                    .setEnableDecoderFallback(enableDecoderFallback)
                    .setEventHandler(eventHandler)
                    .setEventListener(eventListener)
                    .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY),
                rotation,
            ),
        )
    }
}
