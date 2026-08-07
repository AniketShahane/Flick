package com.flick.receiver.player

/**
 * A turn the video surface is carrying, and the picture it has to carry it for.
 *
 * The picture's own shape is part of this rather than read off the view, because
 * a `TextureView` always draws its texture stretched across its whole bounds:
 * what is on screen is the coded frame squashed into the view rect, and undoing
 * that squash is half of what [surfaceTurnTransform] computes. The numbers are
 * the renderer's `VideoSize`, which under a view turn is the CODED size — the
 * decoder is configured at 0, so media3 transposes nothing.
 */
data class SurfaceTurn(
    /** Clockwise, in quarter turns. Zero is a film nobody has turned. */
    val degrees: Int,
    val pictureWidthPx: Int,
    val pictureHeightPx: Int,
    /** Sample aspect: it applies to the width, so 1440×1080 at 1.333 is 16:9. */
    val pixelWidthHeightRatio: Float,
    /**
     * Whether the picture is on the `TextureView` at all, which is a longer-lived
     * fact than any one turn and is why it cannot be read off [degrees].
     *
     * A film that has been turned once stays on that surface for the rest of its
     * cast, including all the way back at zero. Handing the picture back to a
     * `SurfaceView` mid-film is what this exists to stop: on the verified TV it
     * left the codec attached to a texture the new view does not own, behind a
     * fresh `PlayerView`'s shutter, and the picture never came back — not at zero,
     * not at Auto, and not at the next turn either.
     *
     * The cost is that a turned HDR film stays tone-mapped until the next cast,
     * which is a cost the viewer already accepted when they turned it. Every film
     * nobody turns is untouched: no `TextureView` is ever built for it, and it
     * keeps the video layer, tunneling and Dolby Vision.
     */
    val onTexture: Boolean,
) {
    val isTurned: Boolean get() = degrees != 0

    companion object {
        /** Every ordinary cast. Nothing is turned and no `TextureView` exists. */
        val NONE = SurfaceTurn(
            degrees = 0,
            pictureWidthPx = 0,
            pictureHeightPx = 0,
            pixelWidthHeightRatio = 1f,
            onTexture = false,
        )
    }
}

/**
 * A turn as `android.graphics.Matrix` takes it — rotate about the view's centre,
 * then scale about the same point.
 *
 * Kept as five numbers rather than a `Matrix` so the geometry can be exercised
 * without a device: `Matrix` is an android.graphics stub in a JVM test and would
 * silently return nothing.
 */
data class SurfaceTurnTransform(
    /** Clockwise on screen, which is `Matrix.postRotate`'s own sense. */
    val rotationDegrees: Float,
    val scaleX: Float,
    val scaleY: Float,
    val pivotX: Float,
    val pivotY: Float,
) {
    companion object {
        /** The texture drawn exactly as the view was laid out. */
        val IDENTITY = SurfaceTurnTransform(
            rotationDegrees = 0f,
            scaleX = 1f,
            scaleY = 1f,
            pivotX = 0f,
            pivotY = 0f,
        )
    }
}

/**
 * Where a turned picture lands inside the view that draws it.
 *
 * ## Why the view is full-bleed and the matrix does all the fitting
 *
 * media3's own long-deleted `applyTextureViewRotation` rotated about the view
 * centre and post-scaled the rotated rect back onto the view rect, which is only
 * correct if the view has ALREADY been sized to the turned aspect — so it was
 * paired with `PlayerView` swapping its content frame's aspect ratio. That pairing
 * is not available here, and not because of an API: `PlayerView`'s
 * `SubtitleView` is a child of the same `exo_content_frame`, so sizing that frame
 * to a portrait aspect would squeeze the captions into a portrait column too, and
 * the one thing a turn must never move is the subtitles.
 *
 * So the turned player is `RESIZE_MODE_FILL` instead — content frame, texture and
 * captions all full-bleed — and this function fits the picture. That is strictly
 * more general than media3's helper and reduces to it when the view already has
 * the turned aspect. It also removes the trap in the other approach: `PlayerView`
 * re-applies its own aspect ratio on every `onVideoSizeChanged`, and under FILL
 * that value is ignored rather than fought over.
 *
 * ## The arithmetic
 *
 * The texture is drawn stretched across the whole view, so the picture's width
 * spans [viewWidthPx] and its height spans [viewHeightPx] whatever their real
 * proportions. `postRotate` by a quarter turn then swaps which view edge each of
 * those spans lies along, and `postScale` sizes them: the picture's width has to
 * end up spanning the turned picture's height on screen, and vice versa. Solving
 * that for the largest centred rect of the turned aspect is the whole of it, and
 * it is exact — the result never distorts, because the two scale factors are
 * derived from the picture's own proportions rather than from the view's.
 *
 * Returns [SurfaceTurnTransform.IDENTITY] when the answer is not knowable yet — a
 * view that has not been laid out, or a picture whose size the renderer has not
 * published. Nothing is on screen in either case, and the layout that follows
 * asks again.
 */
fun surfaceTurnTransform(
    viewWidthPx: Int,
    viewHeightPx: Int,
    pictureWidthPx: Int,
    pictureHeightPx: Int,
    pixelWidthHeightRatio: Float,
    turnDegrees: Int,
): SurfaceTurnTransform {
    if (viewWidthPx <= 0 || viewHeightPx <= 0) return SurfaceTurnTransform.IDENTITY
    val quarter = quarterTurn(turnDegrees) ?: return SurfaceTurnTransform.IDENTITY
    // Zero is NOT identity here, and that is the whole reason this function is reached
    // with it. A film turned back to zero stays on the `TextureView` it was turned on —
    // see [SurfaceTurn.onTexture] — and that view is RESIZE_MODE_FILL, so the texture is
    // stretched across the whole player unless something fits it. The fall-through does
    // exactly that: at zero it is a fit with no rotation, and it reduces to identity on
    // its own whenever the picture and the view already share an aspect.
    val pictureAspect = displayAspectRatio(pictureWidthPx, pictureHeightPx, pixelWidthHeightRatio)
        ?: return SurfaceTurnTransform.IDENTITY
    val sideways = quarter == 90 || quarter == 270
    val turnedAspect = if (sideways) 1f / pictureAspect else pictureAspect
    val viewWidth = viewWidthPx.toFloat()
    val viewHeight = viewHeightPx.toFloat()
    // The largest rect of the turned aspect that fits, centred: bounded by the
    // view's height when the view is the wider shape of the two, by its width
    // otherwise.
    val boundedByHeight = viewWidth / viewHeight > turnedAspect
    val turnedWidth = if (boundedByHeight) turnedAspect * viewHeight else viewWidth
    val turnedHeight = if (boundedByHeight) viewHeight else viewWidth / turnedAspect
    return SurfaceTurnTransform(
        rotationDegrees = quarter.toFloat(),
        // After a quarter turn the picture's own width is the span lying along
        // the view's vertical edge, so it is the view's HEIGHT that the
        // horizontal scale has to be taken against.
        scaleX = turnedWidth / (if (sideways) viewHeight else viewWidth),
        scaleY = turnedHeight / (if (sideways) viewWidth else viewHeight),
        pivotX = viewWidth / 2f,
        pivotY = viewHeight / 2f,
    )
}

/**
 * The shape the picture is meant to be seen in — coded pixels with the sample
 * aspect applied, since that ratio applies to the width. Null when there is no
 * picture to describe.
 */
internal fun displayAspectRatio(
    widthPx: Int,
    heightPx: Int,
    pixelWidthHeightRatio: Float,
): Float? {
    if (widthPx <= 0 || heightPx <= 0) return null
    val ratio = if (pixelWidthHeightRatio > 0f) pixelWidthHeightRatio else 1f
    return (widthPx * ratio) / heightPx
}
