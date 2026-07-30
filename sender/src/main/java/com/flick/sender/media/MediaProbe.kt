package com.flick.sender.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.flick.sender.model.HdrType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Cheap, best-effort probes of a local video: HDR classification (for the DV/HDR
 * badges) and on-device still decoding (the frame preview that rides the thumb —
 * possible *because the file is local*, design Part 4).
 */
object MediaProbe {

    private val hdrCache = ConcurrentHashMap<String, HdrType>()

    // A cold first scroll over a large library launches one detectHdr per tile; cap the
    // burst so those MediaExtractor container parses don't saturate the IO pool and
    // starve Coil's thumbnail decoders (they share Dispatchers.IO) on the same files.
    private val probeDispatcher = Dispatchers.IO.limitedParallelism(2)

    /**
     * The verdict already reached for [uri], or null if nobody has looked yet.
     *
     * Exposed so a caller can read the memo WITHOUT suspending. The hit test used to live
     * inside [detectHdr]'s `withContext`, which meant a tile whose answer was already a
     * value in this map still had to launch a coroutine and queue on a two-wide dispatcher
     * behind whatever cold tiles were mid-container-parse of a multi-gigabyte file — then
     * dispatch back to Main and write state, recomposing the tile. Over a fling across an
     * already-visited part of the grid that is forty coroutines and forty recompositions to
     * re-answer forty questions that were answered the first time.
     */
    fun cachedHdr(uri: Uri): HdrType? = hdrCache[uri.toString()]

    /**
     * Reads the video track's MIME / color-transfer to classify HDR. Dolby Vision
     * carries its own MIME; HDR10/HLG show up as an ST2084 / HLG transfer.
     *
     * The memo is checked before the dispatch, not inside it — see [cachedHdr].
     */
    suspend fun detectHdr(context: Context, uri: Uri): HdrType {
        cachedHdr(uri)?.let { return it }
        return withContext(probeDispatcher) {
            // Re-checked on arrival: two tiles for the same file can both miss above and
            // both queue, and the second no longer has a reason to parse the container.
            hdrCache[uri.toString()]?.let { return@withContext it }
            val result = runCatching {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(context, uri, null)
                    var found = HdrType.NONE
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                        if (!mime.startsWith("video/")) continue
                        if (mime.equals("video/dolby-vision", ignoreCase = true)) {
                            found = HdrType.DOLBY_VISION
                            break
                        }
                        if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                            val transfer = format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
                            if (transfer == MediaFormat.COLOR_TRANSFER_ST2084 ||
                                transfer == MediaFormat.COLOR_TRANSFER_HLG
                            ) {
                                found = HdrType.HDR10
                            }
                        }
                    }
                    found
                } finally {
                    extractor.release()
                }
            }.getOrDefault(HdrType.NONE)
            hdrCache[uri.toString()] = result
            result
        }
    }

    /**
     * A single scrub session serializes every preview decode onto one thread so a fast
     * drag can never stack concurrent native decoders — a plain MediaMetadataRetriever
     * is not thread-safe and one open retriever is reused across positions.
     */
    internal val scrubDispatcher = Dispatchers.IO.limitedParallelism(1)

    /**
     * Decode a scaled still at [positionMs] from an already-open [retriever] (no
     * setDataSource — the expensive container parse happens once per drag session).
     * Blocking and non-cancellable; the caller confines it to [scrubDispatcher].
     */
    internal fun decodeStill(retriever: MediaMetadataRetriever, positionMs: Long): Bitmap? {
        return try {
            val us = positionMs.coerceAtLeast(0L) * 1000L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    us,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    PREVIEW_WIDTH_PX,
                    PREVIEW_HEIGHT_PX,
                )
            } else {
                // No scaling call before 27, and getFrameAtTime hands back the frame at
                // its full decoded size — 33 MB for one 4K frame. The decode itself is the
                // platform's, so that transient is unavoidable; what must not survive it
                // is a 33 MB bitmap held live by the preview while the next bucket
                // allocates another. Scaled into the same box the newer call takes, and the
                // full-size frame released here rather than left to the collector.
                retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?.let(::scaleToPreview)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun scaleToPreview(full: Bitmap): Bitmap {
        val (width, height) = previewFrameSize(full.width, full.height)
        if (width == full.width && height == full.height) return full
        val scaled = Bitmap.createScaledBitmap(full, width, height, true)
        // createScaledBitmap is allowed to hand back its source; recycling then would
        // recycle the bitmap being returned.
        if (scaled !== full) full.recycle()
        return scaled
    }
}

/**
 * The box a scrub still is decoded into. It matches `getScaledFrameAtTime`'s contract —
 * fit inside the box, never upscale, preserve the source's own aspect ratio — so both
 * branches of [MediaProbe.decodeStill] produce the same picture on either side of API 27.
 */
internal fun previewFrameSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
    if (sourceWidth <= 0 || sourceHeight <= 0) return PREVIEW_WIDTH_PX to PREVIEW_HEIGHT_PX
    val scale = minOf(
        PREVIEW_WIDTH_PX.toFloat() / sourceWidth,
        PREVIEW_HEIGHT_PX.toFloat() / sourceHeight,
        1f,
    )
    return (sourceWidth * scale).roundToInt().coerceAtLeast(1) to
        (sourceHeight * scale).roundToInt().coerceAtLeast(1)
}

// The preview card is 116x65dp; anything larger is decoded and thrown away.
internal const val PREVIEW_WIDTH_PX = 160
internal const val PREVIEW_HEIGHT_PX = 90

private const val SCRUB_BUCKET_MS = 500L    // decode at most one still per 500ms of media
private const val SCRUB_DEBOUNCE_MS = 90L   // wall-clock settle before decoding a bucket

/**
 * Produces the scrub frame preview. [positionMs] is read as a lambda so pointer-rate
 * scrub updates don't recompose the caller — the position is observed inside a
 * [snapshotFlow], bucketed to 500ms of media time, wall-clock debounced, and decoded
 * **latest-wins** on a single thread from ONE retriever opened for the drag session
 * (released when scrubbing ends or the composable leaves). This bounds concurrency to
 * one decode and eliminates the repeated setDataSource that a naive per-bucket decode
 * incurred on a multi-GB 4K/DV file the media server is streaming concurrently.
 */
@Composable
fun rememberScrubFrame(uri: Uri?, positionMs: () -> Long, enabled: Boolean): ImageBitmap? {
    val context = LocalContext.current
    val currentPosition = rememberUpdatedState(positionMs)
    return produceState<ImageBitmap?>(initialValue = null, uri, enabled) {
        if (!enabled || uri == null) {
            value = null
            return@produceState
        }
        val retriever = withContext(MediaProbe.scrubDispatcher) {
            runCatching {
                MediaMetadataRetriever().apply { setDataSource(context, uri) }
            }.getOrNull()
        }
        if (retriever == null) {
            value = null
            return@produceState
        }
        try {
            snapshotFlow { currentPosition.value() / SCRUB_BUCKET_MS }
                .distinctUntilChanged()
                .collectLatest { bucket ->
                    // A newer bucket cancels this before the decode runs (true debounce).
                    delay(SCRUB_DEBOUNCE_MS)
                    val bmp = withContext(MediaProbe.scrubDispatcher) {
                        MediaProbe.decodeStill(retriever, bucket * SCRUB_BUCKET_MS)
                    }
                    if (bmp != null) value = bmp.asImageBitmap()
                }
        } finally {
            withContext(NonCancellable + MediaProbe.scrubDispatcher) {
                runCatching { retriever.release() }
            }
        }
    }.value
}
