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
     * Reads the video track's MIME / color-transfer to classify HDR. Dolby Vision
     * carries its own MIME; HDR10/HLG show up as an ST2084 / HLG transfer.
     */
    suspend fun detectHdr(context: Context, uri: Uri): HdrType = withContext(probeDispatcher) {
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
                    160,
                    90,
                )
            } else {
                retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } catch (_: Throwable) {
            null
        }
    }
}

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
