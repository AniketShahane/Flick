package com.flick.receiver.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import androidx.compose.ui.graphics.toArgb
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.util.FlickLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What the tunnel's window shows of the film while it plays there: the whole frame, as the
 * full-screen view shows it, shrunk. The film's own view is never transformed — on the TV a
 * scaled video layer falls back to a slow blitter — so the window shows copies of it instead.
 */
interface FilmFrames {
    /**
     * Starts copying the film. True once a usable first copy has landed, within [budgetMs] at
     * most; false when the film cannot be copied, and the window falls back to a porthole onto
     * the live film. The caller stops it on every path out either way.
     */
    suspend fun start(budgetMs: Long): Boolean

    /** Stops copying; the last copy stays drawable until [clear]. */
    fun stop()

    /** Drops the copies and the memory they hold. */
    fun clear()

    /** True once copying has given up on a film it had been copying: its surface went away. */
    val lost: Boolean

    /** Draws the film's whole frame into [l], [t], [r], [b] at [alpha]; false when there is nothing to draw. */
    fun draw(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, alpha: Float = 1f): Boolean
}

/** Whether an HDR10 or HLG film is copied at all. A readback may tone-map it; flip after a look on the TV. */
internal const val SNAPSHOT_HDR = true

/** A copy every this many ms: about 12 a second, which the window's size makes read as motion. */
internal const val SNAPSHOT_INTERVAL_MS = 83L

/** The longest an SDR film's first copy may take before the window falls back to the porthole. */
internal const val SNAPSHOT_FIRST_WAIT_MS = 250L

/**
 * The copies' width: 640 × 360 for a 16:9 frame. Each one is read back on the RenderThread and
 * uploaded again when drawn, so this is the knob for the TV's per-copy cost.
 */
internal const val SNAPSHOT_WIDTH = 640

/** An HDR copy at least this bright on average is a picture; darker, the readback is not yet one. */
internal const val SNAPSHOT_HDR_LUMA = 0.01f

/** Consecutive failed copies after which a film's surface is taken as gone. */
internal const val SNAPSHOT_LOST_AFTER = 3

/** The copies are triple-buffered: one shown, one perhaps still being drawn, one being written. */
private const val SNAPSHOT_BUFFERS = 3

/** Copies [source] into [into], reporting a [PixelCopy] result on [handler]. Tests replace it. */
internal typealias SnapshotCopier = (source: SurfaceView, into: Bitmap, done: (Int) -> Unit, handler: Handler) -> Unit

private val pixelCopy: SnapshotCopier = { source, into, done, handler ->
    PixelCopy.request(source, into, { result -> done(result) }, handler)
}

/**
 * [FilmFrames] by PixelCopy of the film's [SurfaceView] on a thread of its own, into reused
 * bitmaps. [frame] is the player's whole view and [video] its video surface, which the view
 * may letterbox: each copy is placed at the surface's rect within the frame, kept in step
 * with its layout, and the rest of the frame is the player's own black. [refusal] names why
 * this film may not be copied, or is null; [hdr] says its copies must show a picture before
 * the window takes them, since an HDR readback can come back dark while it is tone-mapped.
 */
class FilmSnapshots internal constructor(
    private val frame: () -> View?,
    private val video: () -> View?,
    private val refusal: () -> String?,
    private val hdr: () -> Boolean,
    private val copier: SnapshotCopier,
) : FilmFrames {
    constructor(
        frame: () -> View?,
        video: () -> View?,
        refusal: () -> String? = { null },
        hdr: () -> Boolean = { false },
    ) : this(frame, video, refusal, hdr, pixelCopy)

    private var thread: HandlerThread? = null
    private var buffers: Array<Bitmap>? = null

    /** The buffer last accepted, or -1; read by the draw on the main thread. */
    @Volatile private var latest = -1

    /** Bumped on every start and stop, so a copy that lands late is dropped. */
    @Volatile private var session = 0
    private var first: CompletableDeferred<Boolean>? = null

    @Volatile override var lost = false
        private set

    // Where the surface sits in the frame, as fractions of the frame; main thread only.
    private var placeL = 0f
    private var placeT = 0f
    private var placeR = 1f
    private var placeB = 1f
    private val place = RectF()
    private val filtered = Paint(Paint.FILTER_BITMAP_FLAG)
    private val letterbox = Paint().apply { color = FlickColor.CanvasPlayback.toArgb() }
    private val location = IntArray(2)
    private var placedFrame: View? = null
    private var placedVideo: View? = null
    private val replace = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }

    // Measured on the copying thread, read when it stops.
    private var copyHdr = false
    private var accepted = false
    private var startedAtMs = 0L
    private var copies = 0
    private var copyTotalMs = 0L
    private var copyMaxMs = 0L
    private var failures = 0
    private var failedInARow = 0

    /** Whether the copying thread is up. */
    val running: Boolean get() = thread != null

    override suspend fun start(budgetMs: Long): Boolean {
        stop()
        lost = false
        refusal()?.let {
            FlickLog.i("snapshot", "fallback reason=$it")
            return false
        }
        val frameView = frame()
        val videoView = video()
        if (frameView == null || frameView.width <= 0 || frameView.height <= 0 || videoView == null) {
            FlickLog.i("snapshot", "fallback reason=noView")
            return false
        }
        if (videoView !is SurfaceView) {
            // A turned film plays on a texture inside the window. Reading one back is a
            // synchronous GPU copy on the main thread, which the tunnel animates on.
            FlickLog.i("snapshot", "fallback reason=texture")
            return false
        }
        if (!videoView.holder.surface.isValid || videoView.width <= 0 || videoView.height <= 0) {
            FlickLog.i("snapshot", "fallback reason=noSurface")
            return false
        }
        placedFrame = frameView
        placedVideo = videoView
        place()
        frameView.addOnLayoutChangeListener(replace)
        videoView.addOnLayoutChangeListener(replace)
        val scale = SNAPSHOT_WIDTH.toFloat() / frameView.width
        val w = max(1, (videoView.width * scale).roundToInt())
        val h = max(1, (videoView.height * scale).roundToInt())
        val reuse = buffers?.takeIf { it[0].width == w && it[0].height == h }
        buffers = reuse ?: Array(SNAPSHOT_BUFFERS) { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
        latest = -1
        copyHdr = hdr()
        accepted = false
        copies = 0
        copyTotalMs = 0L
        copyMaxMs = 0L
        failures = 0
        failedInARow = 0
        val gen = ++session
        val landed = CompletableDeferred<Boolean>()
        first = landed
        val worker = HandlerThread("flick-snapshot").apply { start() }
        thread = worker
        val handler = Handler(worker.looper)
        startedAtMs = SystemClock.uptimeMillis()
        handler.post { copy(gen, videoView, handler) }
        // An SDR film's first copy is the picture whatever it shows; an HDR one may wait for a
        // copy that shows something, within the caller's budget.
        val wait = if (copyHdr) budgetMs else minOf(SNAPSHOT_FIRST_WAIT_MS, budgetMs)
        val ok = try {
            withTimeoutOrNull(wait) { landed.await() } ?: run {
                FlickLog.i("snapshot", "fallback reason=${if (copyHdr) "hdrNoPicture" else "firstCopyTimeout"} waitedMs=$wait")
                false
            }
        } catch (e: CancellationException) {
            stop()
            throw e
        }
        if (!ok) stop()
        return ok
    }

    /** The surface's rect within the frame, as the frame lays it out now. */
    private fun place() {
        val frameView = placedFrame ?: return
        val videoView = placedVideo ?: return
        if (frameView.width <= 0 || frameView.height <= 0) return
        frameView.getLocationInWindow(location)
        val fx = location[0]
        val fy = location[1]
        videoView.getLocationInWindow(location)
        placeL = (location[0] - fx).toFloat() / frameView.width
        placeT = (location[1] - fy).toFloat() / frameView.height
        placeR = placeL + videoView.width.toFloat() / frameView.width
        placeB = placeT + videoView.height.toFloat() / frameView.height
    }

    private fun copy(gen: Int, source: SurfaceView, handler: Handler) {
        if (gen != session) return
        val target = buffers ?: return
        // Never the copy on screen, nor the one before it, which a frame may still be drawing.
        val into = (latest + 1).mod(SNAPSHOT_BUFFERS)
        val began = SystemClock.uptimeMillis()
        try {
            copier(source, target[into], { result -> copied(gen, into, result, began, source, handler) }, handler)
        } catch (e: IllegalArgumentException) {
            // The surface went away between copies.
            copied(gen, into, PixelCopy.ERROR_SOURCE_INVALID, began, source, handler)
        }
    }

    private fun copied(gen: Int, into: Int, result: Int, began: Long, source: SurfaceView, handler: Handler) {
        if (gen != session) return
        val now = SystemClock.uptimeMillis()
        val took = now - began
        val firstCopy = first
        if (result == PixelCopy.SUCCESS) {
            copies++
            failedInARow = 0
            copyTotalMs += took
            copyMaxMs = max(copyMaxMs, took)
            if (!accepted) {
                val copy = buffers?.get(into) ?: return
                val luma = meanLuma(copy)
                accepted = !copyHdr || luma > SNAPSHOT_HDR_LUMA
                FlickLog.i(
                    "snapshot",
                    "copy latencyMs=${now - startedAtMs} tookMs=$took luma=${"%.3f".format(luma)} " +
                        "size=${copy.width}x${copy.height} hdr=$copyHdr accepted=$accepted",
                )
                if (accepted) {
                    latest = into
                    firstCopy?.complete(true)
                }
            } else {
                latest = into
            }
        } else {
            failures++
            failedInARow++
            FlickLog.i("snapshot", "copy failed result=$result inARow=$failedInARow")
            if (!accepted) {
                FlickLog.i("snapshot", "fallback reason=copyFailed result=$result")
                firstCopy?.complete(false)
                return
            }
            if (failedInARow >= SNAPSHOT_LOST_AFTER) {
                FlickLog.i("snapshot", "lost after $failedInARow failed copies")
                lost = true
                return
            }
        }
        handler.postDelayed({ copy(gen, source, handler) }, (SNAPSHOT_INTERVAL_MS - took).coerceAtLeast(0L))
    }

    override fun stop() {
        placedFrame?.removeOnLayoutChangeListener(replace)
        placedVideo?.removeOnLayoutChangeListener(replace)
        placedFrame = null
        placedVideo = null
        val worker = thread ?: return
        session++
        thread = null
        first?.takeIf { !it.isCompleted }?.complete(false)
        first = null
        worker.quitSafely()
        if (copies > 0 || failures > 0) {
            FlickLog.i(
                "snapshot",
                "stop copies=$copies avgMs=${if (copies > 0) copyTotalMs / copies else 0} maxMs=$copyMaxMs failures=$failures",
            )
        }
    }

    override fun clear() {
        latest = -1
        // Dropped, not recycled: a frame in flight may still be drawing one.
        buffers = null
    }

    override fun draw(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, alpha: Float): Boolean {
        val index = latest
        val copy = buffers?.getOrNull(index) ?: return false
        val a = (alpha * 255f).roundToInt().coerceIn(0, 255)
        letterbox.alpha = a
        filtered.alpha = a
        val w = r - l
        val h = b - t
        place.set(l + placeL * w, t + placeT * h, l + placeR * w, t + placeB * h)
        // The letterbox round the picture, never under it: fading over the live film, two
        // layers at the same alpha would darken it.
        if (place.top > t) canvas.drawRect(l, t, r, place.top, letterbox)
        if (place.bottom < b) canvas.drawRect(l, place.bottom, r, b, letterbox)
        if (place.left > l) canvas.drawRect(l, place.top, place.left, place.bottom, letterbox)
        if (place.right < r) canvas.drawRect(place.right, place.top, r, place.bottom, letterbox)
        canvas.drawBitmap(copy, null, place, filtered)
        return true
    }

    private fun meanLuma(bitmap: Bitmap): Float {
        var sum = 0f
        var n = 0
        val stepX = max(1, bitmap.width / 32)
        val stepY = max(1, bitmap.height / 18)
        var y = stepY / 2
        while (y < bitmap.height) {
            var x = stepX / 2
            while (x < bitmap.width) {
                val c = bitmap.getPixel(x, y)
                sum += (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f
                n++
                x += stepX
            }
            y += stepY
        }
        return if (n == 0) 0f else sum / n
    }
}
