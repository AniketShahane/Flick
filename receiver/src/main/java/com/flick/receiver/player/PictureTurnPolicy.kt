package com.flick.receiver.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes

/**
 * Who actually turns the picture.
 *
 * Two mechanisms are left on this TV, and the difference between them is the
 * whole of this file.
 *
 * [Decoder] writes the turn into `MediaFormat.KEY_ROTATION`. The platform turns
 * that into a buffer transform on the codec's output surface, and the display
 * pipeline is expected to honour it while compositing. It costs nothing at all —
 * no extra pass, no extra buffer, no colour conversion — which is why it is the
 * only mechanism used when no turn is asserted. What it cannot do is guarantee
 * anything: a display pipeline that will not rotate a video layer drops the
 * transform silently, and media3 then reports a swapped `VideoSize` anyway,
 * because `MediaCodecVideoRenderer.onOutputFormatChanged` transposes width
 * against height from `Format.rotationDegrees` on the assumption the transform
 * was applied. The visible result is a picture whose BOX turns while the pixels
 * inside it do not. That is what the verified Google TV Streamer does — logged on
 * the device at 90, then 180, then 270, then back to 0, because the viewer kept
 * pressing a key that moved nothing on screen.
 *
 * [View] renders the film into a `TextureView` and puts the turn in that view's
 * own transform. It cannot fail the way the decoder's does, and the reason is
 * worth stating: nothing outside the app is asked to rotate anything, and no
 * separate compositor layer is involved. A `SurfaceView` is its own SurfaceFlinger
 * layer — which is precisely why a view transform cannot reach its contents, and
 * why it can never carry this — while a `TextureView` is drawn through the
 * ordinary hardware canvas and is transformable like any other view. What it
 * costs is the video layer itself: the frames become a texture composited into
 * the app's own window, so there is no layer left for tunneling, for an HDR
 * transfer, or for a TV that upscales its UI layer to present at panel
 * resolution. See [PictureColour] and [TurnNote], which are how that reaches the
 * viewer.
 *
 * Rotating the `SurfaceView` from Compose instead is not a cheaper version of
 * this; it is nothing at all. `Modifier.graphicsLayer` composites offscreen by
 * default, and a `SurfaceView` punches a hole through the view hierarchy and is
 * composited separately by SurfaceFlinger — so the offscreen buffer that gets
 * rotated never contains a single video pixel. It does not look slow or wrong. It
 * looks like nothing happened.
 *
 * ## The mechanism that used to sit between these two
 *
 * media3's video-effects graph is gone rather than kept as a third option. On the
 * verified hardware it consumed frames and presented none: the decoder ran at full
 * rate for the whole of a twelve-second deadline, no EGL error was raised
 * anywhere, and what the viewer got was a frozen picture over a healthy player.
 * That is a known failure class rather than this project's bad luck —
 * androidx/media#1139 (a `flush()` that hangs forever on a latch when
 * `ExternalTextureManager` skips `onFlushComplete` after dropping frames; fixed
 * May 2024) and androidx/media#1535 (filed after that fix: a `flush()` landing
 * between `releaseOutputBuffer` and `SurfaceTexture.onFrameAvailable` drops the
 * queued buffer silently and the callback never fires again; maintainer-confirmed,
 * closed by a stale bot with no fix named). Both present exactly as observed: a
 * permanent silent stall with nothing thrown.
 *
 * Two structural facts finish the case. `ExoPlayer.setVideoEffects` builds a graph
 * only if effects were set before the video renderer's FIRST enable —
 * `MediaCodecVideoRenderer.onEnabled` says so in as many words — so effects handed
 * to a renderer that had none are stored and silently never applied, which is why
 * engaging a turn used to cost a whole new player. And a graph is incompatible with
 * tunneling by definition: AOSP defines tunnel mode as compressed data reaching
 * the display "without being processed by app code or Android framework code",
 * which is precisely the `SurfaceTexture` access a graph exists to have.
 */
enum class TurnMechanism {
    /** `MediaFormat.KEY_ROTATION`. Free, and not always obeyed. */
    Decoder,

    /** The video surface's own transform. Always obeyed, and never free. */
    View,
}

/**
 * What the picture is made of, as far as a turn is concerned.
 *
 * Only three answers matter, because only three outcomes exist once the frames
 * leave the video layer for a texture in the app's window: nothing is lost, the
 * grade is lost, or there is nothing honest left to put on the panel.
 */
enum class PictureColour {
    /** A view transform costs this nothing. */
    Sdr,

    /**
     * HDR10 or HLG. The grade does not survive a turn, and this is a platform
     * fact rather than a property of this TV: Android's own media guidance states
     * that from API 33 a `TextureView` transcodes HDR to SDR, "resulting in
     * playback with possible loss of detail including clipped colors and video
     * banding", and recommends a `SurfaceView` for HDR playback wherever
     * possible. So there is no panel capability to probe and no EGL extension
     * that buys it back — the previous mechanism's question, which asked exactly
     * that, was retired with it.
     */
    Hdr,

    /**
     * Dolby Vision, which loses more than the grade. The RPU is dynamic metadata
     * the display applies to the video layer, so a DV film drawn through the view
     * hierarchy arrives as an uninterpreted base layer: washed out where that
     * layer is HDR10-compatible, and not the film's colours at all where it is
     * not. There is no honest picture to show, so it is never turned.
     */
    DolbyVision,
}

/**
 * The colour class of a decoded video track.
 *
 * Read from the MIME type first: a Dolby Vision track carries a perfectly
 * ordinary HDR10-compatible `ColorInfo` for its base layer, so the transfer
 * alone would call profile 8.1 plain HDR10 and turn a film that must not be.
 */
fun pictureColourOf(sampleMimeType: String?, colorTransfer: Int): PictureColour = when {
    sampleMimeType != null &&
        MimeTypes.VIDEO_DOLBY_VISION.equals(sampleMimeType, ignoreCase = true) ->
        PictureColour.DolbyVision
    colorTransfer == C.COLOR_TRANSFER_ST2084 || colorTransfer == C.COLOR_TRANSFER_HLG ->
        PictureColour.Hdr
    else -> PictureColour.Sdr
}

/**
 * What the viewer is owed about a turn that could not be given to them intact.
 *
 * Null is the ordinary case and means the picture is exactly what was asked for.
 *
 * Neither note covers what a turn costs an SDR film, because there is one thing
 * it costs every turned film and nothing can be done about it: media3's own
 * surface guidance gives a `SurfaceView` "full resolution of the display on
 * Android TV devices that upscale the UI layer", and a turned film is drawn in
 * that UI layer. So on such a TV a turned 4K picture may reach the panel
 * upscaled from whatever the UI layer runs at. It is not said out loud because
 * the alternative on offer is not turning the picture at all, and a viewer who
 * pressed the key has already answered that question.
 */
enum class TurnNote {
    /** Asked for, and this TV cannot do it without destroying the picture. */
    NotOnThisTv,

    /** Done, and the HDR grade was the price. */
    ShownInSdr,
}

/**
 * One resolved turn: the number the decoder is configured with, the number the
 * video surface's own transform applies, and what the viewer is owed about the
 * difference.
 *
 * Exactly one of the two numbers is ever non-zero. When [viewDegrees] carries the
 * turn, [decoderDegrees] is 0 rather than the container's own value — see
 * [pictureTurnFor].
 *
 * [viewDegrees] is CLOCKWISE, which is both `Format.rotationDegrees`' own sense
 * and `android.graphics.Matrix.postRotate`'s on a screen whose y axis points
 * down. That the two conventions agree is why no conversion lives here; the
 * effects graph this replaced took counterclockwise degrees and needed one.
 */
data class PictureTurn(
    /** Written into `Format.rotationDegrees`, and from there `KEY_ROTATION`. */
    val decoderDegrees: Int,
    /** The clockwise turn the video surface applies; 0 when it is not carrying one. */
    val viewDegrees: Int,
    val note: TurnNote?,
) {
    val mechanism: TurnMechanism
        get() = if (viewDegrees != 0) TurnMechanism.View else TurnMechanism.Decoder
}

/**
 * Decide who turns the picture, and what it costs.
 *
 * The gate is deliberately the TOTAL turn rather than Flick's own share of it,
 * and the two cases that separates are the point:
 *
 *  - **Nothing to do.** A film whose container declares no rotation and which
 *    nobody has turned; and — the case worth naming — a sideways film Auto has
 *    corrected, where the container's 90 and Flick's 270 cancel to 0. Both reach
 *    the decoder as rotation 0, which is not a transform at all, so no display
 *    pipeline can fail to honour it. Every ordinary cast is this case, including
 *    every 4K Dolby Vision one, and it stays on the `SurfaceView` it would have
 *    had if this feature did not exist.
 *
 *  - **Something to do.** The total is a quarter turn away from how the frames
 *    are coded, so a transform has to happen somewhere. On the verified hardware
 *    the decoder's transform is dropped, so [TurnMechanism.View] is the only
 *    mechanism that can produce a turned picture — and the decoder is then given
 *    0 rather than the total, because two mechanisms both applying the turn
 *    would land 180 out on any device where the decoder's one DOES work.
 *
 * Giving the decoder 0 has a second effect that is load-bearing rather than
 * incidental: media3 transposes the reported `VideoSize` from
 * `Format.rotationDegrees`, so a zero there keeps the reported size equal to the
 * coded size — which is the shape the frames actually arrive in, and therefore
 * the shape the surface transform has to be computed from. A decoder left
 * carrying the turn would have the whole chain describing a picture the panel is
 * not being sent.
 *
 * [turnUnavailable] is the film's own history: the view turn was engaged for it
 * once and the picture did not survive. It is never retried for that film.
 */
fun pictureTurnFor(
    containerDegrees: Int,
    extraDegrees: Int,
    colour: PictureColour,
    turnUnavailable: Boolean,
): PictureTurn {
    val effective = effectiveRotationDegrees(containerDegrees, extraDegrees)
    val quarter = quarterTurn(effective)
    // Off the quarter-turn grid is the container's own value, untouched: neither
    // KEY_ROTATION nor a quarter-turn matrix is the right home for it, and
    // inventing one would be a worse answer than the file's.
    if (quarter == null || quarter == 0) return PictureTurn(effective, viewDegrees = 0, note = null)
    if (colour == PictureColour.DolbyVision || turnUnavailable) {
        // The decoder keeps the turn: free, and on a TV whose display pipeline
        // does honour it, correct. On one that does not, the picture stays as
        // filed — which is what [TurnNote.NotOnThisTv] exists to say out loud.
        return PictureTurn(effective, viewDegrees = 0, note = TurnNote.NotOnThisTv)
    }
    // Unconditional, unlike the graph this replaced: that lost HDR only where the
    // panel's EGL could not present BT.2020, so it was worth asking. A
    // `TextureView` transcodes HDR to SDR from API 33 whatever the panel can do —
    // see [PictureColour.Hdr] — so there is nothing left to ask.
    val note = if (colour == PictureColour.Hdr) TurnNote.ShownInSdr else null
    return PictureTurn(decoderDegrees = 0, viewDegrees = quarter, note = note)
}
