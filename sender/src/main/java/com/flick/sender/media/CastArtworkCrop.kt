package com.flick.sender.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Which rectangle of the still becomes the artwork, and how large that rectangle is drawn.
 * Pure arithmetic, so the rule every cast's album art depends on can be measured without a device.
 */
internal data class ArtworkCrop(
    val left: Int,
    val top: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val width: Int,
    val height: Int,
) {
    /**
     * What the drawn picture costs in a Binder transaction: ARGB_8888 is four bytes a pixel and
     * the notification parcels the bitmap itself. This is the number [ARTWORK_BUDGET_PX] is
     * about — not the JPEG the session metadata carries, which is tens of kilobytes for a
     * photographic frame.
     */
    val bytes: Int
        get() = width * height * ARTWORK_BYTES_PER_PIXEL
}

/**
 * The artwork's geometry for a still of [stillWidth] x [stillHeight]: the film's own shape, held
 * between two aspect bounds and inside one pixel budget.
 *
 * **The shape is the film's.** No ground, no bars, nothing of this app's in the picture. The
 * consequence is that the media card takes its colour from the footage, because SystemUI paints
 * that surface out of a scheme extracted from this bitmap and consults the notification's own
 * `setColor` nowhere along the way. That trade is deliberate: the picture is the film's, so the
 * colour is too.
 *
 * **Both bounds exist because every slot this reaches CROPS.** The surfaces fill their slot with
 * the artwork and discard the overflow, and those slots run from square — a notification's
 * right-hand icon, a car head unit, a watch — to roughly 2:1, the media card in the shade. So a
 * frame wider than the widest slot spends its width on columns nothing ever displays, and a frame
 * taller than square spends its height the same way. Both spend it out of a FIXED budget, which
 * means the waste is subtracted from the resolution of the part that IS seen. Bounding the aspect
 * is how the budget ends up spent on visible pixels.
 *
 * 16:9 is the wide bound because it is already the shape of nearly everything this app plays:
 * it passes through untouched, and the only films it trims are the ones no slot could show whole
 * anyway — a 2.39:1 scope frame loses a quarter of its width, from both sides equally.
 *
 * 3:4 is the tall bound, so portrait is deliberately not left native. A 9:16 clip is the worst
 * case a landscape card has: it shows the middle band and nothing else, so three quarters of the
 * pixels would be rows cropped before anyone saw them. Trimmed to 3:4 the same clip still reads
 * unmistakably as portrait — it is a shape cameras genuinely frame for — while every pixel left
 * in it is one a square slot can show.
 *
 * **The COST is the constant that the SIZE used to be.** A still is scaled to fit [budgetPx]
 * pixels and never scaled up, so a small frame keeps its own pixels and a large one arrives at
 * whatever edge lengths its own shape spends the budget on.
 */
internal fun artworkCrop(
    stillWidth: Int,
    stillHeight: Int,
    budgetPx: Int = ARTWORK_BUDGET_PX,
): ArtworkCrop {
    val width = stillWidth.coerceAtLeast(1)
    val height = stillHeight.coerceAtLeast(1)
    var sourceWidth = width
    var sourceHeight = height
    // Integer ratios rather than a float aspect, so a frame shot exactly at a bound is never
    // trimmed by a rounding error in the comparison itself.
    if (width.toLong() * ARTWORK_WIDE_BOUND_H > height.toLong() * ARTWORK_WIDE_BOUND_W) {
        sourceWidth = (height.toLong() * ARTWORK_WIDE_BOUND_W / ARTWORK_WIDE_BOUND_H)
            .toInt()
            .coerceIn(1, width)
    } else if (width.toLong() * ARTWORK_TALL_BOUND_H < height.toLong() * ARTWORK_TALL_BOUND_W) {
        sourceHeight = (width.toLong() * ARTWORK_TALL_BOUND_H / ARTWORK_TALL_BOUND_W)
            .toInt()
            .coerceIn(1, height)
    }
    val scale = sqrt(budgetPx.toDouble() / (sourceWidth.toDouble() * sourceHeight))
        .coerceAtMost(1.0)
    var drawnWidth = (sourceWidth * scale).roundToInt().coerceIn(1, sourceWidth)
    var drawnHeight = (sourceHeight * scale).roundToInt().coerceIn(1, sourceHeight)
    // Rounding to the nearer pixel keeps the film's aspect, but it is allowed to carry the pair a
    // few hundred pixels past the budget, so the long edge gives one back until it fits. The
    // ceiling has to be something this function PROVES rather than something it approximates: a
    // notification whose Binder transaction overruns does not arrive at all.
    while (drawnWidth.toLong() * drawnHeight > budgetPx && (drawnWidth > 1 || drawnHeight > 1)) {
        if (drawnWidth >= drawnHeight) drawnWidth-- else drawnHeight--
    }
    return ArtworkCrop(
        left = (width - sourceWidth) / 2,
        top = (height - sourceHeight) / 2,
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        width = drawnWidth,
        height = drawnHeight,
    )
}

/**
 * The still, cropped and scaled by [artworkCrop] — the picture the media notification's large
 * icon and the platform session's metadata both carry.
 *
 * Null when it could not be composed at all; the caller then sends the bare still, which is
 * exactly what this app sent before any of this existed.
 */
internal fun croppedArtwork(still: Bitmap): Bitmap? {
    // A hardware-backed bitmap cannot be read by a software canvas at all, and the provider's
    // cached thumbnail is decoded by a path that is allowed to return one. A copy is the only
    // way to draw it, so the artwork survives that case rather than being skipped for it.
    val readable = runCatching {
        if (still.config == Bitmap.Config.HARDWARE) {
            still.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            still
        }
    }.getOrNull() ?: return null
    return try {
        runCatching { compose(readable) }.getOrNull()
    } finally {
        if (readable !== still) readable.recycle()
    }
}

private fun compose(still: Bitmap): Bitmap {
    val crop = artworkCrop(still.width, still.height)
    // Drawn even when the geometry asks for nothing, so the caller owns exactly one rule: a
    // non-null result is a NEW bitmap and the still it was taken from is free.
    val artwork = Bitmap.createBitmap(crop.width, crop.height, Bitmap.Config.ARGB_8888)
    Canvas(artwork).drawBitmap(
        still,
        Rect(
            crop.left,
            crop.top,
            crop.left + crop.sourceWidth,
            crop.top + crop.sourceHeight,
        ),
        Rect(0, 0, crop.width, crop.height),
        Paint(Paint.FILTER_BITMAP_FLAG),
    )
    return artwork
}

/**
 * The pixel budget every cast's artwork is held to, whatever shape it is: 448 x 448 worth, which
 * is 802,816 bytes at four bytes a pixel.
 *
 * The picture is parceled to SystemUI twice over — once as the notification's large icon, and
 * again as the bytes the session's metadata carries — against a Binder transaction ceiling of
 * about a megabyte that takes the notification with it when it overruns. This leaves 240 KiB of
 * that ceiling for the rest of the notification, which is the margin the artwork has always run
 * with: the number is unchanged from the square this app used to send.
 *
 * Holding the AREA rather than an edge is what lets the shape belong to the film. A 16:9 frame
 * spends the budget as 597 x 336, a 4:3 frame as 517 x 388, a square one as 448 x 448.
 */
internal const val ARTWORK_BUDGET_PX = 448 * 448

/** Wider than 16:9 is centre-cropped to it — [artworkCrop] holds why the bound is there. */
internal const val ARTWORK_WIDE_BOUND_W = 16
internal const val ARTWORK_WIDE_BOUND_H = 9

/** Taller than 3:4 is centre-cropped to it — [artworkCrop] holds why portrait is not left native. */
internal const val ARTWORK_TALL_BOUND_W = 3
internal const val ARTWORK_TALL_BOUND_H = 4

/** ARGB_8888: the config every bitmap this file composes is drawn in. */
internal const val ARTWORK_BYTES_PER_PIXEL = 4
