package com.flick.receiver.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * The live video-clock shift, written on the main thread by the control command
 * and read on the playback thread every render tick.
 *
 * One field with no companion invariant to hold, so `@Volatile` is the entire
 * synchronization: the worst a late read can cost is one tick rendered at the
 * previous offset, which is shorter than a frame. It is owned by
 * [PlayerController] rather than by the factory so a commanded delay survives an
 * ExoPlayer rebuild — the replacement renderers read this same instance, so
 * unlike volume there is nothing to re-apply after a background/foreground cycle.
 */
class AudioDelayShift {
    @Volatile var videoShiftUs: Long = 0L
}

/**
 * A video renderer that sees the playback clock shifted by [shift], and nothing
 * else changed.
 *
 * `render` is where a decoded frame's release is decided, from
 * `earlyUs = presentationTimeUs - positionUs`, so a position handed forward
 * releases frames sooner and one handed back holds them. `getDurationToProgressUs`
 * takes the identical shift because ExoPlayer's dynamic scheduling uses it to
 * choose the next wake-up; computed against an unshifted clock it would wake for
 * the wrong instant and the two would disagree about when the next frame is due.
 *
 * Every other method keeps the true renderer timestamp. `enable`, `resetPosition`
 * and `getReadingPositionUs` in particular: a seek must land exactly where it was
 * asked to, and the media clock — which is the audio renderer's, not this one's —
 * stays the single source of the reported position, so the scrub bar, the resume
 * checkpoints and the `state` frames are untouched by any delay.
 *
 * The picture pays for any DISCONTINUITY between the shifted clock and what the
 * decoder can supply, and there are exactly two. Media3 1.10.1's thresholds set
 * the price: a frame more than 50 ms early is answered "try again later" and
 * held, one more than 30 ms late is dropped, one more than 500 ms late drops
 * decoded buffers to the next keyframe.
 *
 * A CHANGE of shift costs the size of the change — toward "audio earlier" the
 * picture holds for it, the other way it drops that much. The phone steps in
 * 25 ms, so a press costs at most a frame; only slamming end to end in a single
 * frame reaches the drop-to-keyframe branch.
 *
 * A SEEK at a non-zero shift costs the whole delay, and re-pays it every time.
 * `resetPosition` deliberately gets the true position, so a seek to P leaves the
 * decoder able to supply frames from P while the clock already reads P+delay:
 * ahead of the audio the first frame force-renders and the next |delay| worth
 * arrive late and are dropped, behind it the target frame paints and then holds.
 * That is inherent to shifting a clock — a renderer cannot show frames it does
 * not have — so a scrub or a ±10 s tap at a settled delay skips or freezes for up
 * to half a second before it is right again.
 *
 * Both self-correct, and uninterrupted playback at a settled shift costs nothing
 * beyond one held output buffer, which the load control's headroom absorbs.
 */
private class AudioDelayVideoRenderer(
    val delegate: Renderer,
    private val shift: AudioDelayShift,
) : ForwardingRenderer(delegate) {

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        super.render(positionUs + shift.videoShiftUs, elapsedRealtimeUs)
    }

    override fun getDurationToProgressUs(positionUs: Long, elapsedRealtimeUs: Long): Long =
        super.getDurationToProgressUs(positionUs + shift.videoShiftUs, elapsedRealtimeUs)
}

/**
 * [DefaultRenderersFactory] with every video renderer wrapped so the audio-delay
 * shift reaches the frame-release decision.
 *
 * The text renderer is deliberately left alone, so subtitles stay on the audio's
 * unshifted clock and drift against the picture by the delay: they transcribe
 * dialogue, so following the voice is what keeps them right.
 *
 * Subclassed and mapped over `super`'s output rather than overriding
 * `buildVideoRenderers` and constructing a `MediaCodecVideoRenderer` by hand: the
 * hardware-only `MediaCodecSelector`, the decoder-fallback flag and whatever
 * construction logic a future Media3 adds are all things the caller gets for free
 * today and would silently lose.
 */
class AudioDelayRenderersFactory(
    context: Context,
    private val shift: AudioDelayShift,
) : DefaultRenderersFactory(context) {

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
     * Media3 builds a pre-warming secondary only when the primary's class is
     * *exactly* `MediaCodecVideoRenderer`, so `super` has to be handed the wrapped
     * renderer rather than the wrapper or wrapping would silently switch
     * pre-warming off. `RendererHolder` then drives both renderers from the same
     * `rendererPositionUs`, so the secondary needs the identical shift — without
     * it a pre-warm swap would step the picture back by the whole delay.
     *
     * In this build `super` returns null regardless: pre-warming is opt-in and
     * `experimentalSetEnableMediaCodecVideoRendererPrewarming` is never called.
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
