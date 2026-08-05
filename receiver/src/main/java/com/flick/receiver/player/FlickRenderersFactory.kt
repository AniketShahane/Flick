package com.flick.receiver.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * The receiver's one renderers factory.
 *
 * ExoPlayer accepts exactly one, and this build customizes the video renderer in
 * two unrelated ways — the picture-orientation correction and the A/V nudge — so
 * they have to compose here rather than compete for the slot. They compose
 * cleanly because they hook different levels of the same factory:
 * [buildVideoRenderers] CONSTRUCTS the video renderer with the corrected
 * rotation, and [createRenderers] — which is what calls it, so `super` runs the
 * override below — then WRAPS what came back in the audio-delay shift. Rotation
 * lands in the format the decoder is configured from; the shift lands in the
 * frame-release decision; neither can see the other.
 *
 * The OTHER turning mechanism — the video surface's own transform, in force only
 * while a film is genuinely turned — needs nothing from this factory. It changes
 * which view the frames are presented in and what that view's matrix is, and the
 * renderer built here is told only through the ordinary output-surface and
 * frame-metadata messages, forwarded by `ForwardingRenderer.handleMessage`
 * through the audio-delay wrapper.
 *
 * With no rotation asserted and a zero shift this builds and behaves as
 * `DefaultRenderersFactory` does, minus the extension-renderer branch noted
 * below.
 *
 * The text renderer is deliberately left alone by the shift, so subtitles stay
 * on the audio's unshifted clock and drift against the picture by the delay:
 * they transcribe dialogue, so following the voice is what keeps them right.
 */
class FlickRenderersFactory(
    context: Context,
    private val rotation: VideoRotationOverride,
    private val shift: AudioDelayShift,
) : DefaultRenderersFactory(context) {

    /**
     * The builder chain mirrors `DefaultRenderersFactory.buildVideoRenderers` in
     * media3 1.10.1, including its 50-frame drop-notify threshold, with the one
     * renderer class swapped.
     *
     * Overriding at this level costs less than it looks like it should. The
     * hardware-only `MediaCodecSelector` and the decoder-fallback flag are
     * PARAMETERS of this method — media3 reads them off its own fields in
     * `createRenderers` and hands them down — so forwarding them here loses
     * nothing the caller configured. What is genuinely given up is the four
     * settings media3 forwards from factory fields the parameter list does not
     * carry: `parseAv1SampleDependencies`, `lateThresholdToDropDecoderInputUs`,
     * `setEnableDurationToProgressUs`, and on API 34+ the buffer-decode-only
     * flag. This module sets none of them, and each factory default is the value
     * `MediaCodecVideoRenderer.Builder` already defaults to on its own (true,
     * 15000 µs, false, false), so reproducing them would change nothing today.
     * The real forfeit is the future: construction logic a later media3 adds
     * here reaches this renderer only when someone updates this chain.
     *
     * The extension-renderer branch of the original is dropped rather than
     * reproduced. The receiver pins `EXTENSION_RENDERER_MODE_OFF` because
     * hardware-only decode is the product thesis, so that branch is unreachable.
     */
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

    /**
     * Wraps every video renderer `super` produced — which is the rotation-aware
     * one built above — so the audio-delay shift reaches the frame-release
     * decision. Mapping over the finished array rather than adding the wrapper
     * inside [buildVideoRenderers] keeps the two customizations at the levels
     * they belong to, and leaves the audio, text and metadata renderers exactly
     * as media3 built them.
     */
    override fun createRenderers(
        eventHandler: Handler,
        videoRendererEventListener: VideoRendererEventListener,
        audioRendererEventListener: AudioRendererEventListener,
        textRendererOutput: TextOutput,
        metadataRendererOutput: MetadataOutput,
    ): Array<Renderer> = super.createRenderers(
        eventHandler,
        videoRendererEventListener,
        audioRendererEventListener,
        textRendererOutput,
        metadataRendererOutput,
    ).map { renderer ->
        if (renderer.trackType == C.TRACK_TYPE_VIDEO) {
            AudioDelayVideoRenderer(renderer, shift)
        } else {
            renderer
        }
    }.toTypedArray()

    /**
     * Unreachable in this build, for two independent reasons, and kept for shape
     * rather than for effect.
     *
     * Read off the media3 1.10.1 bytecode: `buildSecondaryVideoRenderer` returns
     * null unless `enableMediaCodecVideoRendererPrewarming` is set — it is opt-in
     * and `experimentalSetEnableMediaCodecVideoRendererPrewarming` is never
     * called anywhere in this project — AND unless the primary's class is
     * EXACTLY `MediaCodecVideoRenderer`, compared by class identity, not
     * `instanceof`. The primary here is [RotationCorrectingVideoRenderer], a
     * subclass, so that second gate fails even if pre-warming is switched on
     * later.
     *
     * That is a correction to what this method used to be for. Before rotation
     * existed, unwrapping the primary genuinely preserved pre-warming; now
     * nothing can, and no unwrapping restores it. The practical behaviour is
     * unchanged, because pre-warming was already off.
     *
     * What is still worth keeping is the shape. `super` is handed the renderer
     * instead of the wrapper, and anything it did return gets the same shift,
     * because `RendererHolder` drives primary and secondary from one
     * `rendererPositionUs` and a pre-warm swap into an unshifted secondary would
     * step the picture back by the whole delay.
     */
    override fun createSecondaryRenderer(
        renderer: Renderer,
        eventHandler: Handler,
        videoRendererEventListener: VideoRendererEventListener,
        audioRendererEventListener: AudioRendererEventListener,
        textRendererOutput: TextOutput,
        metadataRendererOutput: MetadataOutput,
    ): Renderer? {
        val primary = (renderer as? AudioDelayVideoRenderer)?.delegate ?: renderer
        val secondary = super.createSecondaryRenderer(
            primary,
            eventHandler,
            videoRendererEventListener,
            audioRendererEventListener,
            textRendererOutput,
            metadataRendererOutput,
        ) ?: return null
        return if (secondary.trackType == C.TRACK_TYPE_VIDEO) {
            AudioDelayVideoRenderer(secondary, shift)
        } else {
            secondary
        }
    }
}
