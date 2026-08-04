package com.flick.sender.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.ui.graphics.toArgb
import com.flick.sender.ui.theme.OnSpark
import com.flick.sender.ui.theme.Spark

/**
 * The mat's own geometry: where the film's frame lands inside the square ground, in pixels.
 * Pure arithmetic, so the rule the ground depends on can be measured without a device.
 */
internal data class MatPlacement(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val boxPx: Int,
) {
    /** The share of the picture the ground keeps. What the platform's extractor scores. */
    val groundShare: Float
        get() = 1f - (width.toFloat() * height) / (boxPx.toFloat() * boxPx)
}

/**
 * Centre a still of [stillWidth] x [stillHeight] on a [boxPx] square, holding [matPx] of ground
 * clear on every side. The frame is scaled to fit and never up: a film smaller than the mat's
 * opening keeps its own pixels and simply sits in more ground.
 */
internal fun matPlacement(
    stillWidth: Int,
    stillHeight: Int,
    boxPx: Int = ARTWORK_BOX_PX,
    matPx: Int = ARTWORK_MAT_PX,
): MatPlacement {
    val opening = (boxPx - 2 * matPx).coerceAtLeast(1)
    val (width, height) = previewFrameSize(stillWidth, stillHeight, opening, opening)
    return MatPlacement((boxPx - width) / 2, (boxPx - height) / 2, width, height, boxPx)
}

/**
 * The still, laid unaltered on a square ground of Flick's amber.
 *
 * **This is the only way this app can put its own colour on the media controls.** From Android 13
 * a MediaStyle notification carrying a session is not drawn as a notification at all: SystemUI
 * lifts it into the media panel and paints that panel out of
 * `ColorScheme(WallpaperColors.fromBitmap(artwork), darkTheme = true, Style.CONTENT)` — the card's
 * gradient, the transport's tint, and even the app icon's tint. `Notification.setColor` is read
 * nowhere along that path. The releases before it differ only in which extractor they use:
 * SystemUI's `MediaNotificationProcessor` took the artwork's DOMINANT swatch. Every one of them
 * asks the picture, so the picture is what has to be amber.
 *
 * A mat is the shape that answers the extractor. It scores a colour as roughly
 * `70 * (share of the picture within ±15° of its hue)` plus a chroma term, so what wins is a large
 * flat area of one saturated colour — and a ground is exactly that, while a wash or a vignette laid
 * OVER the frame would spread into dozens of quantizer buckets and tint the film besides. Amber's
 * chroma alone cannot carry it (a saturated red scores far higher per pixel), which is why the
 * ground is sized by [ARTWORK_MAT_PX] to hold MORE than half the picture rather than a border's
 * worth of it.
 *
 * It costs the frame nothing. The still is drawn at its own aspect, unmodified, inside the square
 * rather than over it — and being square, it also stops the media controls' square art slot
 * cropping the sides off a letterboxed film the way an unmatted 16:9 picture invites.
 *
 * Null when the ground could not be composed at all — the caller then sends the bare still, which
 * is exactly what this app sent before the mat existed.
 */
internal fun mattedArtwork(still: Bitmap): Bitmap? {
    // A hardware-backed bitmap cannot be read by a software canvas at all, and the provider's
    // cached thumbnail is decoded by a path that is allowed to return one. A copy is the only
    // way to draw it, so the mat survives that case rather than being skipped for it.
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
    val placement = matPlacement(still.width, still.height)
    val mat = Bitmap.createBitmap(ARTWORK_BOX_PX, ARTWORK_BOX_PX, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(mat)
    canvas.drawColor(Spark.toArgb())
    val frame = Rect(
        placement.left,
        placement.top,
        placement.left + placement.width,
        placement.top + placement.height,
    )
    // A rule drawn OUTSIDE the frame, never over it: a bright film and a bright ground otherwise
    // bleed into one another, and the mat has to read as something the frame was placed on.
    canvas.drawRect(
        Rect(
            frame.left - ARTWORK_RULE_PX,
            frame.top - ARTWORK_RULE_PX,
            frame.right + ARTWORK_RULE_PX,
            frame.bottom + ARTWORK_RULE_PX,
        ),
        Paint().apply { color = OnSpark.toArgb() },
    )
    canvas.drawBitmap(still, null, frame, Paint(Paint.FILTER_BITMAP_FLAG))
    return mat
}

/**
 * The ground held clear on every side of the frame.
 *
 * Sized by the extractor rather than by taste. A colour scores about `70 x its share of the
 * picture` plus `(HCT chroma - 48) x 0.3`; this amber (#FFB61E) is chroma 57, worth 2.8, and a
 * fully saturated red is chroma 113, worth 19.6. So the ground starts 17 points behind and has
 * to make all of it back on area — about 24 points of share.
 *
 * At 56 px of a 448 px square the ground keeps 76% of the picture at 2.39:1, 68% at 16:9 and
 * 9:16, 58% at 4:3 and 44% of a square frame. Put as the question actually being asked — how
 * much of the still may be one saturated hue before the panel takes the film's colour instead
 * of this app's — that is a 16:9 frame however loud, four fifths of a 4:3 frame, and a third of
 * a square one.
 *
 * 40 px, which this was, is where that reasoning failed rather than a smaller cost for the same
 * result: it left 16:9 ahead by 0.08 of a point, well inside the error of the estimate above,
 * and 4:3 BEHIND against anything more than half filled with one hue — the exact outcome the mat
 * exists to prevent.
 *
 * A fully saturated 4:3 frame still wins, and no mat that leaves a picture changes that: drawing
 * level there needs 65 px, by which point the still is 318 px and the result reads as a picture
 * frame. What the extra 16 px buys is every frame a camera actually produces. A loud red scene
 * quantizes to something nearer chroma 88, worth 12.2, and 4:3 beats that outright; a flat
 * saturated slate is refused upstream by `VideoStills`, which will not choose a frame with no
 * spread in it.
 */
internal const val ARTWORK_MAT_PX = 56

/** See [ARTWORK_MAT_PX]: what is left for the still once the ground has its margin. */
internal const val ARTWORK_STILL_BOX_PX = ARTWORK_BOX_PX - 2 * ARTWORK_MAT_PX

private const val ARTWORK_RULE_PX = 2
