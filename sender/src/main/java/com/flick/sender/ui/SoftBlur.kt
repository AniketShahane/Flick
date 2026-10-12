package com.flick.sender.ui

import android.graphics.Bitmap
import coil.size.Dimension
import coil.size.Size
import coil.transform.Transformation
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.ensureActive

/**
 * A blur computed once, on Coil's decode thread, into a bitmap a few hundred pixels wide.
 *
 * The alternative is a live `Modifier.blur`, which re-blurs every frame the image is drawn —
 * a full-screen render effect for every frame of a page's entrance. A blurred image has no
 * detail left to lose, so the small bitmap scaled up on draw is indistinguishable from it.
 */
internal object SoftBlur {

    /**
     * [passes] box blurs of [radius] across, then down, over packed ARGB [pixels]. Three
     * passes are within a few percent of a gaussian with sigma `sqrt(radius * (radius + 1))`.
     * Edges clamp, so a border never darkens toward transparent black.
     */
    fun blur(pixels: IntArray, width: Int, height: Int, radius: Int, passes: Int = 3): IntArray {
        require(width >= 0 && height >= 0 && pixels.size >= width * height)
        val count = width * height
        val out = pixels.copyOf(count)
        if (radius <= 0 || count == 0) return out
        val scratch = IntArray(count)
        repeat(passes) {
            boxPass(out, scratch, lines = height, length = width, lineStride = width, step = 1, radius = radius)
            boxPass(scratch, out, lines = width, length = height, lineStride = 1, step = width, radius = radius)
        }
        return out
    }

    private fun boxPass(
        src: IntArray,
        dst: IntArray,
        lines: Int,
        length: Int,
        lineStride: Int,
        step: Int,
        radius: Int,
    ) {
        val window = 2 * radius + 1
        val half = window / 2
        val last = length - 1
        for (line in 0 until lines) {
            val base = line * lineStride
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (k in -radius..radius) {
                val p = src[base + k.coerceIn(0, last) * step]
                a += p ushr 24
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
            }
            for (i in 0..last) {
                dst[base + i * step] = ((a + half) / window shl 24) or
                    ((r + half) / window shl 16) or
                    ((g + half) / window shl 8) or
                    ((b + half) / window)
                val leaving = src[base + max(i - radius, 0) * step]
                val entering = src[base + min(i + radius + 1, last) * step]
                a += (entering ushr 24) - (leaving ushr 24)
                r += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
                g += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
                b += (entering and 0xFF) - (leaving and 0xFF)
            }
        }
    }

    /**
     * Where a [boxWidth] × [boxHeight] window sits on a [width] × [height] image scaled to
     * cover it: the scaled image's size, and the window's offset into it. Horizontally
     * centred; vertically at [biasY] (0 top, 1 bottom), the alignment the image is drawn
     * with, so the crop keeps the part the page would have shown anyway.
     */
    fun coverWindow(width: Int, height: Int, boxWidth: Int, boxHeight: Int, biasY: Float): IntArray {
        val scale = max(boxWidth.toFloat() / width, boxHeight.toFloat() / height)
        val scaledWidth = max(boxWidth, (width * scale).roundToInt())
        val scaledHeight = max(boxHeight, (height * scale).roundToInt())
        val left = (scaledWidth - boxWidth) / 2
        val top = ((scaledHeight - boxHeight) * biasY.coerceIn(0f, 1f)).roundToInt()
        return intArrayOf(scaledWidth, scaledHeight, left, top)
    }
}

/**
 * Scales the decoded image down to cover the request's size, crops it there (centred across,
 * at [biasY] down), and blurs it by [radiusPx] at that small size. The request's size is
 * therefore the blurred bitmap's size: keep it small, and draw the result with
 * `ContentScale.Crop` at the same alignment.
 */
internal class SoftBlurTransformation(
    private val radiusPx: Int,
    private val biasY: Float = 0.5f,
) : Transformation {

    override val cacheKey: String = "flick.softBlur(radius=$radiusPx,biasY=$biasY)"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val boxWidth = (size.width as? Dimension.Pixels)?.px ?: input.width
        val boxHeight = (size.height as? Dimension.Pixels)?.px ?: input.height
        if (boxWidth <= 0 || boxHeight <= 0 || input.width <= 0 || input.height <= 0) return input
        val (scaledWidth, scaledHeight, left, top) =
            SoftBlur.coverWindow(input.width, input.height, boxWidth, boxHeight, biasY)
        val scaled = if (scaledWidth == input.width && scaledHeight == input.height) {
            input
        } else {
            Bitmap.createScaledBitmap(input, scaledWidth, scaledHeight, true)
        }
        val pixels = IntArray(boxWidth * boxHeight)
        scaled.getPixels(pixels, 0, boxWidth, left, top, boxWidth, boxHeight)
        if (scaled !== input) scaled.recycle()
        coroutineContext.ensureActive()
        val blurred = SoftBlur.blur(pixels, boxWidth, boxHeight, radiusPx)
        coroutineContext.ensureActive()
        return Bitmap.createBitmap(blurred, boxWidth, boxHeight, Bitmap.Config.ARGB_8888)
    }

    override fun equals(other: Any?): Boolean =
        other is SoftBlurTransformation && other.radiusPx == radiusPx && other.biasY == biasY

    override fun hashCode(): Int = 31 * radiusPx + biasY.hashCode()
}
