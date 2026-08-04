package com.flick.sender.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.util.Size as AndroidSize
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The one place a still is chosen for a local video — the tiles, the detail backdrop, the
 * cast poster and the media notification all end up here, so a film shows the same frame
 * on every surface that names it.
 *
 * The shape of the work is: take the cheapest picture available, JUDGE it, and spend
 * decodes only when it turns out to be a picture of nothing. [frameStats] and
 * [thumbnailCandidatesMs] are the judgement and the schedule; this file is the Android
 * half — the provider call, the retriever, and the memo that keeps a search from
 * happening twice for one file.
 */
internal object VideoStills {

    /**
     * Every retriever decode this file runs is serialized onto one thread, for
     * [MediaProbe.scrubDispatcher]'s reason — a plain `MediaMetadataRetriever` is not
     * thread-safe — and for one this app cares about more: a fling across the grid would
     * otherwise start a native decoder per visible tile on a phone whose HTTP server may
     * be streaming a multi-gigabyte 4K file over Wi-Fi at that exact moment, and nothing
     * about a thumbnail is worth a stall on the television.
     *
     * Its own thread rather than the scrub dispatcher so a drag on the remote and a scroll
     * in the library never queue behind one another.
     */
    private val stillDispatcher = Dispatchers.IO.limitedParallelism(1)

    /**
     * Where the search landed, per file.
     *
     * It memoizes a POSITION and never pixels: the picture belongs to Coil's memory cache
     * under a key that already carries the MediaStore revision, so nothing here can produce
     * a stale IMAGE. What it can produce is a stale CHOICE — the file is re-encoded, the
     * cache refetches under a fresh key, and the second that held a scene now holds black —
     * which is why a verdict recorded on a frame that PASSED is re-judged where it is used
     * and dropped the moment it stops holding up.
     *
     * The key is the bare URI: the service that decodes the notification's artwork knows
     * the file and nothing about the library row it came from, and both halves of the app
     * should reach the same frame.
     */
    private val memory = StillMemory()

    /**
     * A still for [uri] that is a picture of something, scaled to fit [width] x [height].
     *
     * MediaStore's own cached thumbnail is the fast path and stays one: it costs this
     * process no decode at all and it is already what the platform hands every gallery.
     * It is simply no longer trusted blind. Only a frame that judges as blank escalates to
     * the bounded search, and a file that has been searched once pays for a search again
     * only when the frame it settled on stops holding up.
     *
     * Null means every path failed — the caller's cue to fall back to whatever it drew
     * before this existed.
     */
    suspend fun still(
        context: Context,
        uri: Uri,
        durationMs: Long,
        width: Int,
        height: Int,
    ): Bitmap? {
        val key = uri.toString()
        val memo = memory.verdict(key)
        var provider: Bitmap? = null
        var providerUnread = false
        if (memo == null || memo is StillVerdict.ProviderThumbnail) {
            provider = providerThumbnail(context, uri, width, height)
            if (provider == null) {
                providerUnread = true
            } else {
                // Judged on every sighting rather than only the first: the provider
                // regenerates its cached thumbnail when the file underneath is rewritten,
                // and a marker taken on trust would keep handing back a tile of whatever
                // the new file opens on.
                val stats = judge(provider)
                // Unjudgeable is not blank: nothing is overruled unseen.
                if (stats == null || !stats.blank) {
                    memory.remember(key, StillVerdict.ProviderThumbnail)
                    return provider
                }
            }
        }
        // A provider that could not be READ has disproved nothing, so the search below runs
        // without the right to replace the marker: one transient failure would otherwise put
        // the file on the setDataSource path for the rest of the process.
        val record = !(memo is StillVerdict.ProviderThumbnail && providerUnread)
        val known = memo as? StillVerdict.Searched
        // A blank provider thumbnail is still a picture, so it is held as the last resort
        // for a file whose every decode fails — and released the moment one succeeds.
        val chosen = searched(context, uri, key, known, durationMs, width, height, record)
            ?: return provider
        provider?.recycle()
        return chosen
    }

    /**
     * MediaStore's cached thumbnail, which the provider generated once and hands to every
     * gallery on the device. There is no such call below API 29, so those releases reach
     * the search directly and everything below this line behaves exactly the same.
     */
    private suspend fun providerThumbnail(
        context: Context,
        uri: Uri,
        width: Int,
        height: Int,
    ): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return suspendCancellableCoroutine { continuation ->
            val cancellationSignal = CancellationSignal()
            continuation.invokeOnCancellation { cancellationSignal.cancel() }
            val bitmap = try {
                context.contentResolver.loadThumbnail(
                    uri,
                    AndroidSize(width, height),
                    cancellationSignal,
                )
            } catch (_: Exception) {
                null
            }
            if (continuation.isActive) continuation.resume(bitmap)
        }
    }

    /**
     * The escalation: open the file once and walk [thumbnailCandidatesMs], stopping at the
     * first frame that is not blank.
     *
     * The candidates are decoded at the size they will be DISPLAYED at rather than at some
     * smaller judging size, which is what holds the worst case to the schedule's four
     * decodes instead of four plus a fifth for the winner. Every rejected frame is recycled
     * the moment a better one arrives, so at most two live bitmaps exist at once.
     *
     * With [known] already decided this is one decode, plus — only where that verdict was
     * recorded on a frame that passed — one judgement to confirm the file has not changed
     * underneath it. That is the state every tile settles into after its first sighting.
     *
     * [record] is false for exactly one case: a file whose provider marker survived a
     * failed READ. A search may still answer the question there, but it may not overwrite a
     * marker nothing disproved.
     */
    private suspend fun searched(
        context: Context,
        uri: Uri,
        key: String,
        known: StillVerdict.Searched?,
        durationMs: Long,
        width: Int,
        height: Int,
        record: Boolean,
    ): Bitmap? = withContext(stillDispatcher) {
        val retriever = runCatching { MediaMetadataRetriever() }.getOrNull()
            ?: return@withContext null
        // Released on the spot when the container will not open: a retriever that threw
        // out of setDataSource still holds the native object, and a library scroll would
        // leak one per unreadable file.
        if (runCatching { retriever.setDataSource(context, uri) }.isFailure) {
            runCatching { retriever.release() }
            return@withContext null
        }
        try {
            // Re-read on arrival, as the HDR probe's memo is: the grid and the detail sheet
            // can ask for one file at the same moment, both miss the memo, and the second
            // has no reason to search a film the first just searched.
            val decided = known ?: memory.verdict(key) as? StillVerdict.Searched
            if (decided != null) {
                val decoded = MediaProbe.decodeStill(retriever, decided.positionMs, width, height)
                if (decoded != null) {
                    // A best-effort verdict is not re-judged: it recorded a blank frame
                    // deliberately, and a film that is dark throughout would otherwise
                    // re-run the whole search on every cache miss, forever.
                    val stats = if (decided.passed) judge(decoded) else null
                    if (!decided.stale(stats)) return@withContext decoded
                    decoded.recycle()
                }
                // A verdict whose frame no longer decodes, or no longer holds up, belongs to
                // a file that changed under it. Dropping it is what lets the schedule below
                // decide again, inside the session this has already paid for.
                memory.forget(key)
            }
            var best: Bitmap? = null
            var bestScore = -1
            var bestPositionMs = -1L
            var bestPassed = false
            for (positionMs in thumbnailCandidatesMs(sourceDurationMs(retriever, durationMs))) {
                currentCoroutineContext().ensureActive()
                val candidate = MediaProbe.decodeStill(retriever, positionMs, width, height) ?: continue
                val stats = judge(candidate)
                if (stats == null || !stats.blank) {
                    best?.recycle()
                    best = candidate
                    bestPositionMs = positionMs
                    bestPassed = true
                    break
                }
                if (stats.score > bestScore) {
                    best?.recycle()
                    best = candidate
                    bestScore = stats.score
                    bestPositionMs = positionMs
                } else {
                    candidate.recycle()
                }
            }
            if (best != null && record) {
                memory.remember(key, StillVerdict.Searched(bestPositionMs, bestPassed))
            }
            best
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * MediaStore withholds a duration for a file it never fully scanned, and the service
     * starts a cast from an intent that carries none at all. The container is already open
     * by the time this is asked, so the length comes from it rather than the search being
     * collapsed onto a single early frame.
     */
    private fun sourceDurationMs(retriever: MediaMetadataRetriever, durationMs: Long): Long {
        if (durationMs > 0L) return durationMs
        return runCatching {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
        }.getOrNull() ?: 0L
    }

    /** Null when the bitmap's pixels cannot be read at all — see [sampleLuma]. */
    private fun judge(bitmap: Bitmap): FrameStats? = sampleLuma(bitmap)?.let(::frameStats)

    /**
     * A coarse fixed grid of luma samples over the frame's INTERIOR — [sampleAxis] holds
     * the reason the edges are left out. Whole rows are read at a time because a per-sample
     * `getPixel` is one JNI hop each, and the grid is fixed rather than proportional so
     * judging a 960-wide backdrop costs exactly what judging a 160-wide preview does.
     *
     * Null rather than an empty array when the pixels are unreachable — a hardware-backed
     * bitmap throws on any pixel access — because "could not look" and "looked and found
     * nothing" have to reach opposite verdicts.
     */
    private fun sampleLuma(bitmap: Bitmap): IntArray? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0 || bitmap.config == Bitmap.Config.HARDWARE) return null
        val columns = sampleAxis(width, SampleColumns)
        val rows = sampleAxis(height, SampleRows)
        val row = IntArray(width)
        val samples = IntArray(SampleRows * SampleColumns)
        return runCatching {
            for (r in 0 until SampleRows) {
                bitmap.getPixels(row, 0, width, 0, rows[r], width, 1)
                for (c in 0 until SampleColumns) {
                    samples[r * SampleColumns + c] = lumaOf(row[columns[c]])
                }
            }
            samples
        }.getOrNull()
    }
}

/**
 * The picture the media notification and the platform media session draw, in both shapes
 * that surface needs: a `Bitmap` for the notification's large icon, and the same picture
 * compressed for the session's `MediaMetadata`, which is what the Android 13+ media
 * controls read on the shade and the lock screen.
 */
internal class CastArtwork(val bitmap: Bitmap, val data: ByteArray)

/**
 * Resolve the artwork for a cast of [uri]: the film's own frame at the film's own shape, trimmed
 * to the aspect bounds and scaled into the budget by [croppedArtwork]. Null whenever no frame
 * could be produced at all, and the caller then posts exactly the notification it always did.
 *
 * The still is asked for in a SQUARE box, which is what makes a still's long edge the bound
 * whichever way the film was shot — a still is scaled to fit inside it rather than to fill it,
 * and the landscape box this once asked for handed a 1080x1920 file 162x288, its short edge,
 * when portrait and sideways video is exactly what this app plays.
 *
 * There is no one size any more; the shape belongs to the film. What stays constant is the COST,
 * which is the number that was ever load-bearing: [artworkCrop] holds every shape to
 * [ARTWORK_BUDGET_PX] pixels because this picture is parceled to SystemUI twice over — once as
 * the notification's large icon and again, decoded from these bytes, into the platform session's
 * metadata — and a Binder transaction that overruns takes the notification with it. The JPEG is
 * tens of kilobytes for a photographic frame; it is the raw bitmap that budget is really about.
 */
internal suspend fun castArtwork(context: Context, uri: Uri): CastArtwork? = withContext(Dispatchers.IO) {
    // Zero duration: a start intent carries a URI, a name and a size, and the length is
    // read off the container only if the search actually needs it.
    val still = VideoStills.still(context, uri, 0L, ARTWORK_SOURCE_BOX_PX, ARTWORK_SOURCE_BOX_PX)
        ?: return@withContext null
    // The still is this function's own — `still` decodes a fresh bitmap per call and memoizes
    // only the position it chose — so once the artwork has been drawn out of it it is nothing's
    // picture but garbage.
    val bitmap = croppedArtwork(still)?.also { still.recycle() } ?: still
    val stream = ByteArrayOutputStream()
    val compressed = runCatching {
        bitmap.compress(Bitmap.CompressFormat.JPEG, ARTWORK_QUALITY, stream)
    }.getOrDefault(false)
    if (!compressed) {
        // Both halves or neither: nothing downstream ever received this one, so this function
        // is still its only owner.
        bitmap.recycle()
        return@withContext null
    }
    CastArtwork(bitmap, stream.toByteArray())
}

/**
 * The box the still is decoded into, before [artworkCrop] takes the picture out of it.
 *
 * Larger than any single edge the artwork is finally drawn at, because the crop only ever REMOVES
 * pixels: a scope frame gives up a quarter of its width to the wide bound and a phone clip a
 * quarter of its height to the tall one, and asking for the budget's own edge length would hand
 * those shapes back with less picture in them than they are allowed. A shape the crop has to trim
 * still arrives under the ceiling rather than exactly at it — the alternative is decoding a much
 * larger frame to make the trim free, and the ceiling is a limit, not a target.
 */
internal const val ARTWORK_SOURCE_BOX_PX = 640

private const val ARTWORK_QUALITY = 85
