package com.flick.receiver.player

import android.opengl.EGL14
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.MimeTypes

/**
 * Who actually turns the picture.
 *
 * There are only two mechanisms on Android, and the difference between them is
 * the whole of this file.
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
 * inside it do not. That is what the verified Google TV Streamer does.
 *
 * [Frames] routes the decoded frames through media3's video-effects graph — a
 * real GL pass, with the turn in the vertex shader — and renders the result to
 * the same `SurfaceView`. It always works, because nothing outside the app is
 * asked to rotate anything. It costs a texture pass per frame, and on a panel
 * whose EGL cannot present BT.2020 it costs the HDR as well: media3 falls back
 * to tone mapping when the output colour transfer has no EGL extension.
 */
enum class TurnMechanism {
    /** `MediaFormat.KEY_ROTATION`. Free, and not always obeyed. */
    Decoder,

    /** media3's effects graph. Always obeyed, and never free. */
    Frames,
}

/**
 * What the picture is made of, as far as a turn is concerned.
 *
 * Only three answers matter, because only three outcomes exist when frames are
 * pushed through a GL pass: nothing is lost, the HDR grade is lost, or the
 * format cannot enter the pipeline at all.
 */
enum class PictureColour {
    /** A GL pass costs this nothing. */
    Sdr,

    /** HDR10 or HLG. Survives a GL pass only where the panel's EGL can present it. */
    Hdr,

    /**
     * Dolby Vision. `GlUtil.createEglSurface` accepts exactly three output
     * transfers — SDR, BT.2020 PQ and BT.2020 HLG — so there is no surface a DV
     * RPU can be presented through, and the dynamic metadata has nowhere to go.
     */
    DolbyVision,
}

/**
 * The colour class of a decoded video track.
 *
 * Read from the MIME type first: a Dolby Vision track carries a perfectly
 * ordinary HDR10-compatible `ColorInfo` for its base layer, so the transfer
 * alone would call profile 8.1 plain HDR10 and send it into a pipeline that
 * cannot carry its RPU.
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
 */
enum class TurnNote {
    /** Asked for, and this TV cannot do it without destroying the picture. */
    NotOnThisTv,

    /** Done, and the HDR grade was the price. */
    ShownInSdr,
}

/**
 * One resolved turn: the number the decoder is configured with, the number the
 * frame pipeline applies, and what the viewer is owed about the difference.
 *
 * Exactly one of the two numbers is ever non-zero. When [frameDegrees] carries
 * the turn, [decoderDegrees] is 0 rather than the container's own value — see
 * [pictureTurnFor].
 */
data class PictureTurn(
    /** Written into `Format.rotationDegrees`, and from there `KEY_ROTATION`. */
    val decoderDegrees: Int,
    /** The clockwise turn the effects graph applies; 0 when it is not engaged. */
    val frameDegrees: Int,
    val note: TurnNote?,
) {
    val mechanism: TurnMechanism
        get() = if (frameDegrees != 0) TurnMechanism.Frames else TurnMechanism.Decoder

    /**
     * The same turn in the units `ScaleAndRotateTransformation` takes.
     *
     * `Format.rotationDegrees` is documented as "the clockwise rotation that
     * should be applied to the video for it to be rendered in the correct
     * orientation"; `ScaleAndRotateTransformation.setRotationDegrees` is
     * documented as counterclockwise. The two conventions are opposite, and this
     * is the only place that knows it.
     */
    val frameDegreesCounterClockwise: Int get() = (360 - frameDegrees) % 360
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
 *    every 4K Dolby Vision one, and it is byte-identical to having no rotation
 *    feature at all.
 *
 *  - **Something to do.** The total is a quarter turn away from how the frames
 *    are coded, so a transform has to happen somewhere. On the verified hardware
 *    the decoder's transform is dropped, so [TurnMechanism.Frames] is the only
 *    mechanism that can produce a turned picture — and the decoder is then given
 *    0 rather than the total, because two mechanisms both applying the turn
 *    would land 180 out on any device where the decoder's one DOES work.
 *
 * Giving the decoder 0 has a second effect that is load-bearing rather than
 * incidental: media3 transposes the reported `VideoSize` from
 * `Format.rotationDegrees`, so a zero there keeps the reported size equal to the
 * coded size — which is also the size of the texture the graph is registered
 * with, and the size of the frames that actually arrive. The whole chain then
 * agrees, and `FinalShaderProgramWrapper` letterboxes the turned frame into the
 * surface on its own.
 *
 * [framesUnavailable] is the film's own history: the effects graph was engaged
 * for it once and the player failed. It is never retried for that film.
 */
fun pictureTurnFor(
    containerDegrees: Int,
    extraDegrees: Int,
    colour: PictureColour,
    hdrSurvivesFrames: Boolean,
    framesUnavailable: Boolean,
): PictureTurn {
    val effective = effectiveRotationDegrees(containerDegrees, extraDegrees)
    val quarter = quarterTurn(effective)
    // Off the quarter-turn grid is the container's own value, untouched: neither
    // KEY_ROTATION nor SurfaceInfo accepts anything else, and inventing one would
    // be a worse answer than the file's.
    if (quarter == null || quarter == 0) return PictureTurn(effective, frameDegrees = 0, note = null)
    if (colour == PictureColour.DolbyVision || framesUnavailable) {
        // The decoder keeps the turn: free, and on a TV whose display pipeline
        // does honour it, correct. On one that does not, the picture stays as
        // filed — which is what [TurnNote.NotOnThisTv] exists to say out loud.
        return PictureTurn(effective, frameDegrees = 0, note = TurnNote.NotOnThisTv)
    }
    val note = if (colour == PictureColour.Hdr && !hdrSurvivesFrames) TurnNote.ShownInSdr else null
    return PictureTurn(decoderDegrees = 0, frameDegrees = quarter, note = note)
}

/**
 * Whether an HDR transfer reaches the panel intact through media3's effects
 * graph, given which EGL colour-space extensions the device has.
 *
 * This reproduces `PlaybackVideoGraphWrapper.registerInput` and
 * `GlUtil.isColorTransferSupported` from media3 1.10.1 rather than guessing at
 * them, because the answer decides whether a turn silently costs the viewer the
 * grade. Media3's own fallback for an unpresentable transfer is OpenGL tone
 * mapping to SDR — it does not fail, it just quietly stops being HDR.
 *
 * The one non-obvious branch is HLG below API 34: media3 converts it to PQ
 * there, because PQ output landed a release before HLG output did.
 */
fun hdrSurvivesFrameProcessing(
    colorTransfer: Int,
    bt2020PqSupported: Boolean,
    bt2020HlgSupported: Boolean,
    sdkInt: Int,
): Boolean = when (colorTransfer) {
    C.COLOR_TRANSFER_ST2084 -> bt2020PqSupported
    C.COLOR_TRANSFER_HLG -> bt2020HlgSupported || (sdkInt < 34 && bt2020PqSupported)
    else -> true
}

/**
 * What this device's EGL can present, asked once.
 *
 * Queried directly rather than assumed, because it is the fact that decides
 * whether turning an HDR film costs its grade, and it is a property of the
 * silicon rather than of Android: the verified Google TV Streamer advertises
 * `GL_EXT_YUV_target` and OpenGL ES 3.2 — everything media3 needs to READ HDR —
 * and neither BT.2020 colour-space extension, which is what it would need to
 * PRESENT it. A TV that has them keeps its HDR through a turn, and this is how
 * that TV is told from this one.
 *
 * The default display is initialized rather than assumed live: `eglInitialize`
 * is reference-counted and idempotent, and this process has a display already,
 * so this adds a query and nothing else. Anything thrown means the question
 * cannot be answered, and an unanswerable question about HDR is treated as a no.
 */
object GlColourOutput {

    private const val EXTENSION_BT2020_PQ = "EGL_EXT_gl_colorspace_bt2020_pq"
    private const val EXTENSION_BT2020_HLG = "EGL_EXT_gl_colorspace_bt2020_hlg"

    private val extensions: String by lazy { readEglExtensions() }

    /** Media3 refuses PQ output below API 33 whatever the extension says. */
    private val bt2020PqSupported: Boolean by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            extensions.contains(EXTENSION_BT2020_PQ)
    }

    private val bt2020HlgSupported: Boolean by lazy { extensions.contains(EXTENSION_BT2020_HLG) }

    fun hdrSurvivesFrames(colorTransfer: Int): Boolean = hdrSurvivesFrameProcessing(
        colorTransfer = colorTransfer,
        bt2020PqSupported = bt2020PqSupported,
        bt2020HlgSupported = bt2020HlgSupported,
        sdkInt = Build.VERSION.SDK_INT,
    )

    /** For the one log line that says what this panel can and cannot present. */
    fun describe(): String = "pq=$bt2020PqSupported hlg=$bt2020HlgSupported"

    private fun readEglExtensions(): String = runCatching {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return@runCatching ""
        EGL14.eglInitialize(display, IntArray(1), 0, IntArray(1), 0)
        EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS).orEmpty()
    }.getOrDefault("")
}
