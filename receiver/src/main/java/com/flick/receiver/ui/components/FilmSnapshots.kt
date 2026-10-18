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
     * Starts copying the film. True once a usable first copy has landed; false when the film
     * cannot be copied, and the window falls back to a porthole onto the live film. The
     * caller stops it on every path out either way.
     */
    suspend fun start(): Boolean

    /** Stops copying; the last copy stays drawable until [clear]. */
    fun stop()

    /** Drops the last copy. */
    fun clear()

    /** Draws the film's whole frame into [l], [t], [r], [b]; false when there is nothing to draw. */
    fun draw(canvas: Canvas, l: Float, t: Float, r: Float, b: Float): Boolean
}

/** Whether an HDR10 or HLG film is copied at all. A readback may tone-map it; flip after a look on the TV. */
internal const val SNAPSHOT_HDR = true

/** A copy every this many ms: about 12 a second, which the window's size makes read as motion. */
internal const val SNAPSHOT_INTERVAL_MS = 83L

/** The longest the first copy may take before the window falls back to the porthole. */
internal const val SNAPSHOT_FIRST_WAIT_MS = 250L

/** The copies' width; the film's whole frame is 16:9, so this is about 960 × 540 at most. */
internal const val SNAPSHOT_WIDTH = 960

/** A first copy darker than this on average reads the surface as empty, not as a dark scene. */
private const val SNAPSHOT_BLACK_LUMA = 0.01f

/** The copies are triple-buffered: one shown, one perhaps still being drawn, one being written. */
private const val SNAPSHOT_BUFFERS = 3

/**
 * [FilmFrames] by PixelCopy of the film's [SurfaceView] on a thread of its own, into reused
 * bitmaps. [frame] is the player's whole view and [video] its video surface, which the view
 * may letterbox: each copy is placed at the surface's rect within the frame and the rest of
 * the frame is the player's own black. [refusal] names why this film may not be copied, or
 * is null.
 */
class FilmSnapshots(
    private val frame: () -> View?,
    private val video: () -> View?,
    private val refusal: () -> String? = { null },
) : FilmFrames {
    private var thread: HandlerThread? = null
    private var buffers: Array<Bitmap>? = null

    /** The buffer last copied into, or -1; read by the draw on the main thread. */
    @Volatile private var latest = -1

    /** Bumped on every start and stop, so a copy that lands late is dropped. */
    @Volatile private var session = 0
    private var first: CompletableDeferred<Boolean>? = null

    // Where the surface sits in the frame, as fractions of the frame.
    private var placeL = 0f
    private var placeT = 0f
    private var placeR = 1f
    private var placeB = 1f
    private val place = RectF()
    private val filtered = Paint(Paint.FILTER_BITMAP_FLAG)
    private val letterbox = Paint().apply { color = FlickColor.CanvasPlayback.toArgb() }
    private val location = IntArray(2)

    // Measured on the copying thread, read when it stops.
    private var startedAtMs = 0L
    private var copies = 0
    private var copyTotalMs = 0L
    private var copyMaxMs = 0L
    private var failures = 0

    /** Whether the copying thread is up. */
    val running: Boolean get() = thread != null

    override suspend fun start(): Boolean {
        stop()
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
        frameView.getLocationInWindow(location)
        val fx = location[0]
        val fy = location[1]
        videoView.getLocationInWindow(location)
        placeL = (location[0] - fx).toFloat() / frameView.width
        placeT = (location[1] - fy).toFloat() / frameView.height
        placeR = placeL + videoView.width.toFloat() / frameView.width
        placeB = placeT + videoView.height.toFloat() / frameView.height
        val scale = SNAPSHOT_WIDTH.toFloat() / frameView.width
        val w = max(1, (videoView.width * scale).roundToInt())
        val h = max(1, (videoView.height * scale).roundToInt())
        val reuse = buffers?.takeIf { it[0].width == w && it[0].height == h && !it[0].isRecycled }
        buffers = reuse ?: Array(SNAPSHOT_BUFFERS) { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
        latest = -1
        copies = 0
        copyTotalMs = 0L
        copyMaxMs = 0L
        failures = 0
        val gen = ++session
        val landed = CompletableDeferred<Boolean>()
        first = landed
        val worker = HandlerThread("flick-snapshot").apply { start() }
        thread = worker
        val handler = Handler(worker.looper)
        startedAtMs = SystemClock.uptimeMillis()
        handler.post { copy(gen, videoView, handler) }
        val ok = try {
            withTimeoutOrNull(SNAPSHOT_FIRST_WAIT_MS) { landed.await() } ?: run {
                FlickLog.i("snapshot", "fallback reason=firstCopyTimeout waitedMs=$SNAPSHOT_FIRST_WAIT_MS")
                false
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            stop()
            throw e
        }
        if (!ok) stop()
        return ok
    }

    private fun copy(gen: Int, source: SurfaceView, handler: Handler) {
        if (gen != session) return
        val target = buffers ?: return
        // Never the copy on screen, nor the one before it, which a frame may still be drawing.
        val into = (latest + 1).mod(SNAPSHOT_BUFFERS)
        val began = SystemClock.uptimeMillis()
        try {
            PixelCopy.request(source, target[into], { result -> copied(gen, into, result, began, source, handler) }, handler)
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
            copyTotalMs += took
            copyMaxMs = max(copyMaxMs, took)
            if (firstCopy != null && !firstCopy.isCompleted) {
                val luma = meanLuma(buffers!![into])
                val usable = luma > SNAPSHOT_BLACK_LUMA
                FlickLog.i(
                    "snapshot",
                    "first copy latencyMs=${now - startedAtMs} tookMs=$took luma=${"%.3f".format(luma)} " +
                        "size=${buffers!![into].width}x${buffers!![into].height} usable=$usable",
                )
                if (usable) latest = into else FlickLog.i("snapshot", "fallback reason=blackFirstCopy")
                firstCopy.complete(usable)
                if (!usable) return
            } else {
                latest = into
            }
        } else {
            failures++
            FlickLog.i("snapshot", "copy failed result=$result")
            if (firstCopy != null && !firstCopy.isCompleted) {
                FlickLog.i("snapshot", "fallback reason=copyFailed result=$result")
                firstCopy.complete(false)
                return
            }
        }
        handler.postDelayed({ copy(gen, source, handler) }, (SNAPSHOT_INTERVAL_MS - took).coerceAtLeast(0L))
    }

    override fun stop() {
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
    }

    override fun draw(canvas: Canvas, l: Float, t: Float, r: Float, b: Float): Boolean {
        val index = latest
        val copy = buffers?.getOrNull(index) ?: return false
        canvas.drawRect(l, t, r, b, letterbox)
        val w = r - l
        val h = b - t
        place.set(l + placeL * w, t + placeT * h, l + placeR * w, t + placeB * h)
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
