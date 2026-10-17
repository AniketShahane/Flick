package com.flick.sender.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.drawable.CrossfadeDrawable
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import com.flick.sender.media.FilmPosterPolicy
import com.flick.sender.ui.components.IntertitleColors
import com.flick.sender.ui.components.PosterPalette
import com.flick.sender.ui.components.SynopsisPolicy
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the synopsis intertitle is made of, read once from the poster the page already shows:
 * its [colors], the [poster] itself (the page's own decode) with the 2:3 window the page crops
 * it to, the strip's [frame] (that crop at frame size, saturated ×1.2) and the card's [glass]
 * (that crop small, saturated ×1.5 and blurred, drawn scaled up — never blurred per frame).
 */
internal class IntertitleArt(
    val colors: IntertitleColors,
    val poster: ImageBitmap,
    val posterSrcOffset: IntOffset,
    val posterSrcSize: IntSize,
    val frame: ImageBitmap,
    val glass: ImageBitmap,
) {
    companion object {
        private const val GLASS_WIDTH = 96
        private const val GLASS_HEIGHT = 144

        /**
         * A 3-pass box of this radius spreads about 6.7% of the bitmap's width, which is
         * CSS `blur(18px)` over the ~270 dp box the card's glass covers.
         */
        private const val GLASS_RADIUS = 6
        private const val GLASS_SATURATION = 1.5f
        private const val FRAME_SATURATION = 1.2f

        /** Off the main thread: reads, scales and blurs [source] into everything above. */
        fun from(source: Bitmap, framePx: IntSize): IntertitleArt {
            val soft = if (source.config == Bitmap.Config.HARDWARE) {
                source.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                source
            }
            try {
                val crop = posterCrop(soft.width, soft.height)
                val palette = PosterPalette.of(sample(soft, crop, PosterPalette.SAMPLE_WIDTH, PosterPalette.SAMPLE_HEIGHT))
                val glassPixels = sample(soft, crop, GLASS_WIDTH, GLASS_HEIGHT)
                PosterPalette.saturate(glassPixels, GLASS_SATURATION)
                val blurred = SoftBlur.blur(glassPixels, GLASS_WIDTH, GLASS_HEIGHT, GLASS_RADIUS)
                val frameWidth = framePx.width.coerceAtLeast(1)
                val frameHeight = framePx.height.coerceAtLeast(1)
                val framePixels = sample(soft, crop, frameWidth, frameHeight)
                PosterPalette.saturate(framePixels, FRAME_SATURATION)
                return IntertitleArt(
                    colors = IntertitleColors.stainedGlass(palette),
                    poster = source.asImageBitmap(),
                    posterSrcOffset = IntOffset(crop.left, crop.top),
                    posterSrcSize = IntSize(crop.width(), crop.height()),
                    frame = Bitmap.createBitmap(framePixels, frameWidth, frameHeight, Bitmap.Config.ARGB_8888).asImageBitmap(),
                    glass = Bitmap.createBitmap(blurred, GLASS_WIDTH, GLASS_HEIGHT, Bitmap.Config.ARGB_8888).asImageBitmap(),
                )
            } finally {
                if (soft !== source) soft.recycle()
            }
        }

        /** The centred 2:3 window the page's poster seat crops [width] × [height] to. */
        private fun posterCrop(width: Int, height: Int): Rect {
            val aspect = 2f / 3f
            return if (width.toFloat() / height > aspect) {
                val w = (height * aspect).roundToInt().coerceIn(1, width)
                val left = (width - w) / 2
                Rect(left, 0, left + w, height)
            } else {
                val h = (width / aspect).roundToInt().coerceIn(1, height)
                val top = (height - h) / 2
                Rect(0, top, width, top + h)
            }
        }

        /**
         * [crop] of [src] at [width] × [height] as packed ARGB. Halved step by step first: one
         * bilinear pass from hundreds of pixels to forty reads four of them per output and
         * skips the rest, and a poster's palette is the rest.
         */
        private fun sample(src: Bitmap, crop: Rect, width: Int, height: Int): IntArray {
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            var current = src
            var area = Rect(crop)
            while (area.width() / 2 >= width && area.height() / 2 >= height) {
                val half = Bitmap.createBitmap(max(1, area.width() / 2), max(1, area.height() / 2), Bitmap.Config.ARGB_8888)
                Canvas(half).drawBitmap(current, area, Rect(0, 0, half.width, half.height), paint)
                if (current !== src) current.recycle()
                current = half
                area = Rect(0, 0, half.width, half.height)
            }
            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(current, area, Rect(0, 0, width, height), paint)
            if (current !== src) current.recycle()
            val pixels = IntArray(width * height)
            out.getPixels(pixels, 0, width, 0, 0, width, height)
            out.recycle()
            return pixels
        }
    }
}

/** Where a film's intertitle art comes from: [request] decodes the poster the page shows. */
internal class IntertitleSource(val key: String, val request: ImageRequest)

/**
 * The poster the detail page's seat draws, as a request that is the same decode: TMDB's poster
 * by its url (a request with no transformations is cached under the url alone), else the
 * page's own frame [frameRequest], which carries its memory key.
 */
@Composable
internal fun rememberIntertitleSource(posterPath: String?, frameRequest: ImageRequest, seatPx: IntSize): IntertitleSource? {
    val context = LocalContext.current
    return remember(posterPath, frameRequest, seatPx) {
        if (posterPath != null) {
            val url = FilmPosterPolicy.imageUrl(posterPath, FilmPosterPolicy.HERO_WIDTH) ?: return@remember null
            val request = ImageRequest.Builder(context)
                .data(url)
                .apply {
                    if (seatPx.width > 0 && seatPx.height > 0) size(seatPx.width, seatPx.height)
                }
                .precision(Precision.INEXACT)
                .build()
            IntertitleSource(url, request)
        } else {
            val key = frameRequest.memoryCacheKey?.key ?: frameRequest.data.toString()
            IntertitleSource(key, frameRequest)
        }
    }
}

/**
 * The intertitle's inks as they stand: [IntertitleColors.Neutral] until the poster has been
 * read, then that poster's, crossfaded over [ArrivalMs] if the story is on screen as they land.
 * Every getter is meant for a draw, a layer or a [androidx.compose.ui.graphics.ColorProducer]:
 * the crossfade redraws, it never recomposes.
 */
@Stable
internal class IntertitleInks(initial: IntertitleArt? = null) {
    var art by mutableStateOf(initial)
        private set
    private val arrival = Animatable(if (initial != null) 1f else 0f)

    private fun mix(neutral: Int, read: (IntertitleColors) -> Int): Color {
        val target = art?.colors ?: return Color(neutral)
        return lerp(Color(neutral), Color(read(target)), arrival.value)
    }

    /** How far the poster's own art (frames, glass) has faded in. */
    fun artAlpha(): Float = if (art == null) 0f else arrival.value

    fun ground(): Color = mix(IntertitleColors.Neutral.ground) { it.ground }
    fun film(): Color = mix(IntertitleColors.Neutral.film) { it.film }
    fun rule(): Color = mix(IntertitleColors.Neutral.rule) { it.rule }
    fun ruleSoft(): Color = mix(IntertitleColors.Neutral.ruleSoft) { it.ruleSoft }
    fun glow(): Color = mix(IntertitleColors.Neutral.glow) { it.glow }
    fun accent(): Color = mix(IntertitleColors.Neutral.accent) { it.accent }

    /** Settles [art] in, crossfading only when someone can see it arrive. */
    internal suspend fun land(loaded: IntertitleArt, visible: Boolean) {
        art = loaded
        if (visible) arrival.animateTo(1f, tween(ArrivalMs)) else arrival.snapTo(1f)
    }

    companion object {
        const val ArrivalMs = 400
    }
}

/**
 * Reads [source] into the intertitle's art once [ready] (the page has landed and has a
 * synopsis to show), off the main thread, from a small per-process cache when this poster
 * was read before. [visible] is whether the story is on screen right now.
 */
@Composable
internal fun rememberIntertitleInks(
    source: IntertitleSource?,
    imageLoader: ImageLoader,
    ready: Boolean,
    visible: () -> Boolean,
): IntertitleInks {
    val density = LocalDensity.current
    val framePx = with(density) {
        IntSize(SynopsisPolicy.FRAME_WIDTH_DP.dp.roundToPx(), SynopsisPolicy.FRAME_HEIGHT_DP.dp.roundToPx())
    }
    val key = source?.key
    // Already read this session: drawn in its own colours from the first frame, no crossfade.
    val inks = remember(key) { IntertitleInks(key?.let { IntertitleArtCache.get(it) }) }
    val currentVisible by rememberUpdatedState(visible)
    LaunchedEffect(inks, ready) {
        if (!ready || source == null || inks.art != null) return@LaunchedEffect
        val result = imageLoader.execute(source.request)
        val bitmap = ((result as? SuccessResult)?.drawable?.let { drawable ->
            when (drawable) {
                is BitmapDrawable -> drawable.bitmap
                is CrossfadeDrawable -> (drawable.end as? BitmapDrawable)?.bitmap
                else -> null
            }
        }) ?: return@LaunchedEffect
        val art = withContext(Dispatchers.Default) { IntertitleArt.from(bitmap, framePx) }
        IntertitleArtCache.put(source.key, art)
        inks.land(art, currentVisible())
    }
    return inks
}

/** A few posters' worth: a film revisited in the same session does not read its poster again. */
private object IntertitleArtCache {
    private val cache = LruCache<String, IntertitleArt>(4)
    fun get(key: String): IntertitleArt? = cache.get(key)
    fun put(key: String, art: IntertitleArt) {
        cache.put(key, art)
    }
}
