package com.flick.receiver.ui.components

import android.content.Context
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.AnimatedVectorDrawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.session.MediaStage
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickMotion
import com.flick.receiver.ui.theme.FlickSpace
import com.flick.receiver.ui.theme.FlickType
import com.flick.receiver.ui.theme.LocalReducedMotion
import com.flick.receiver.util.FlickLog
import com.flick.receiver.util.preferredWindowRefreshRate
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/** The room the TV is in, as the house lights see it. Checking and Preparing are one stage. */
internal enum class HouseStage { Room, Fault, Handshake, Film }

internal fun houseStageFor(stage: MediaStage): HouseStage = when (stage) {
    MediaStage.None -> HouseStage.Room
    is MediaStage.Error -> HouseStage.Fault
    is MediaStage.Checking, is MediaStage.Preparing -> HouseStage.Handshake
    is MediaStage.Active -> HouseStage.Film
}

internal enum class HouseMoveKind { Rest, Launch, LightsDown, PictureUp, VeilIn, LightsUpRoom, LightsUpFault, Snap }

/** Which resync wait a move holds its pixels for, if any. */
internal enum class HouseGate { None, Reveal, Rest }

internal data class HouseMove(
    val to: HouseStage,
    val kind: HouseMoveKind,
    val seedAperture: Float,
    val seedDensity: Float,
    val gate: HouseGate,
)

/**
 * The aperture's growth at full open. Its clear core, [APERTURE_CORE] of it, must
 * still reach the screen's corners, which sit at √2 in the gradient's unit space.
 */
internal const val APERTURE_S_OPEN = 4.2f

/** The fraction of the gradient's radius that is fully clear. */
internal const val APERTURE_CORE = 0.35f

/**
 * The handshake's cover where no tunnel runs (reduced motion). The state-overlay plate's
 * contrast figures in Color.kt assume this same density, so it stays tied to
 * [FlickColor.ScrimVeil].
 */
internal val VEIL_DENSITY: Float = FlickColor.ScrimVeil.alpha

internal fun apertureScale(p: Float): Float = p.coerceIn(0f, 1f) * APERTURE_S_OPEN

/**
 * The tunnel's window, as a fraction of the stage on each axis. The film's view is
 * scaled by exactly this, so the window and the film inside it are one rectangle.
 * `drawable/tunnel_loop.xml` is generated from the same three numbers.
 */
internal const val TUNNEL_WINDOW_FRACTION = 0.3f

/** The vanishing point, which the window is centred on, as a fraction of the stage's height. */
internal const val TUNNEL_VANISH_Y = 0.42f

/** The window's corner radius as a fraction of its width. */
internal const val TUNNEL_WINDOW_CORNER = 0.04f

/**
 * The tunnel is rasterised at this share of the screen's pixels and scaled up by its
 * view. A vector drawable animating on the RenderThread is redrawn into a bitmap and
 * uploaded every frame, and at full 1080p that is 8 MB a frame on the TV's GPU.
 */
private const val TUNNEL_RASTER_SCALE = 0.5f

/**
 * The wash and glow under the loop (`tunnel_wash.xml`) are smooth gradients that never
 * change after their fade, which is the drawable's root alpha and so leaves its bitmap
 * alone: they are rasterised once, at a quarter of the screen's pixels.
 */
private const val TUNNEL_WASH_RASTER_SCALE = 0.25f

/**
 * The porthole's pane (`tunnel_window.xml`) overlaps the cutout by this share of the stage's
 * width all round (one unit of its 960-unit viewport), so the two antialiased edges leave no
 * seam. It is laid out at the window's rect and grown by its own view scale, never by the
 * vector, so it too is rasterised once, at the window's size.
 */
private const val TUNNEL_PANE_BLEED = 1f / 960f

/** The pale bezel round the porthole while the film plays in it, concentric with its corners. */
internal val TUNNEL_RIM_WIDTH = 4.dp

/**
 * The handshake copy's width under the window. Set by the headline it carries: at
 * 380 dp "<device> is flicking <film> (year)" wrapped and left the year alone on the
 * second line. 560 dp holds a film name and year of roughly thirty characters on one
 * line; longer ones wrap inside the name instead.
 */
internal val TUNNEL_CAPTION_WIDTH = 560.dp

/** The copy's gap under the window. */
private val TUNNEL_CAPTION_GAP = 28.dp

/** Test tag on the tunnel's overlay: the bezel, the copy and the flash. */
internal const val TUNNEL_TAG = "house-tunnel"

/**
 * How much longer a tunnel first drawn at [startedAtMs] must stay before the film may
 * appear in its window. A tunnel not yet drawn (null) owes nothing.
 */
internal fun tunnelHoldLeftMs(
    startedAtMs: Long?,
    nowMs: Long,
    leastShownMs: Long = FlickMotion.TUNNEL_MIN_SHOWN_MS,
): Long = if (startedAtMs == null) 0L else (startedAtMs + leastShownMs - nowMs).coerceAtLeast(0L)

/**
 * When a tunnel first drawn at [startedAtMs] was really on screen: a display mode
 * change at [lastChangeAtMs] blanks the panel until its resync grace has run, so a
 * change landing after the tunnel started, or just before, restarts its time from the
 * settle. Null for a tunnel not yet drawn, which owes nothing.
 */
internal fun tunnelShownSinceMs(startedAtMs: Long?, lastChangeAtMs: Long?): Long? {
    if (startedAtMs == null) return null
    val settledAt = lastChangeAtMs?.plus(RESYNC_GRACE_MS) ?: return startedAtMs
    return max(startedAtMs, settledAt)
}

/** The tunnel's opacity at expansion progress [e]: whole until [FlickMotion.TUNNEL_FADE_FROM], then gone by the end. */
internal fun tunnelFade(e: Float): Float =
    1f - ((e - FlickMotion.TUNNEL_FADE_FROM) / (1f - FlickMotion.TUNNEL_FADE_FROM)).coerceIn(0f, 1f)

/** Stops across the aperture's ramp: enough that the piecewise-linear curve shows no knees of its own. */
private const val APERTURE_RAMP_STEPS = 8

/**
 * The curtain's alpha across its ramp, [t] from the clear core (0) to the rim (1).
 * Smoothstep, so the slope is zero at both ends: a slope break reads as a bright
 * ridge (a Mach band) that dithering cannot remove.
 */
internal fun apertureRampAlpha(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

/** Gradient stop positions: the centre, then [APERTURE_RAMP_STEPS] even steps from [APERTURE_CORE] to 1. */
internal val APERTURE_POSITIONS: FloatArray = FloatArray(APERTURE_RAMP_STEPS + 2) { i ->
    if (i == 0) 0f else APERTURE_CORE + (1f - APERTURE_CORE) * (i - 1) / APERTURE_RAMP_STEPS
}

/** The ramp alpha at each of [APERTURE_POSITIONS]. */
internal val APERTURE_ALPHAS: FloatArray = FloatArray(APERTURE_POSITIONS.size) { i ->
    if (i == 0) 0f else apertureRampAlpha((i - 1f) / APERTURE_RAMP_STEPS)
}

/** Frames the released film must put on the screen before the porthole opens onto it. */
internal const val FILM_FLOWING_FRAMES = 2

/** The longest the porthole waits for them: a film that stalls at once is shown anyway. */
internal const val FILM_FLOWING_WAIT_MS = 500L

/**
 * Returns once [rendered], the film's count of frames released to the screen, has risen by
 * [FILM_FLOWING_FRAMES], or after [FILM_FLOWING_WAIT_MS]. Polled once a frame, so the player
 * keeps no per-frame callback. True when the frames came.
 */
internal suspend fun awaitFramesFlowing(rendered: () -> Int, now: () -> Long = SystemClock::uptimeMillis): Boolean {
    val start = now()
    val from = rendered()
    val flowed = withTimeoutOrNull(FILM_FLOWING_WAIT_MS) {
        while (rendered() - from < FILM_FLOWING_FRAMES) withFrameMillis { }
    } != null
    FlickLog.i("stage", "film flowing frames=${rendered() - from} waitedMs=${now() - start} timedOut=${!flowed}")
    return flowed
}

/** The longest a retained face may outlive its stage if the house lights never report it dark. */
internal const val RETAINED_SHELL_LIMIT_MS = 1_500L

/**
 * The longest a film waits for the house lights to report the picture fully up: the
 * stage hold's cap, the film's time in the window and the expansion, with room to spare.
 */
internal const val REVEAL_SETTLE_FALLBACK_MS = 6_000L

/** A curtain with this little density, or this much aperture, draws nothing. */
private const val EMPTY_DENSITY = 0.004f
private const val OPEN_APERTURE = 0.999f
private const val CLOSED_APERTURE = 0.001f

/** Where each stage's curtain rests: (aperture, density). The handshake's is its cover without a tunnel. */
internal fun houseRest(stage: HouseStage): Pair<Float, Float> = when (stage) {
    HouseStage.Room -> 1f to 1f
    HouseStage.Fault -> 0f to 0f
    HouseStage.Handshake -> 0f to VEIL_DENSITY
    HouseStage.Film -> 0f to 0f
}

/**
 * The move from [from] to [to], seeded from where the outgoing curtain actually is,
 * so an interrupted move continues rather than pops.
 */
internal fun houseMove(
    from: HouseStage?,
    to: HouseStage,
    prevAperture: Float,
    prevDensity: Float,
    filmDim: Float,
    reducedMotion: Boolean,
    offstage: Boolean = false,
): HouseMove {
    val empty = prevDensity <= EMPTY_DENSITY || prevAperture >= OPEN_APERTURE
    val pN = if (empty) 1f else prevAperture
    val dN = if (empty) 1f else prevDensity
    fun snap(): HouseMove {
        val (p, d) = houseRest(to)
        return HouseMove(to, HouseMoveKind.Snap, p, d, HouseGate.None)
    }
    if (reducedMotion) return snap()
    // Nothing composed while the viewer was elsewhere is on screen to carry on from.
    // Closed and fully dense is opaque Canvas, the same dark the tunnel rises out of.
    // A first composition into a handshake is the same case: an Activity created for
    // a cast (one the system destroyed in the background, then summoned) has nothing
    // on screen yet either, and its tunnel must still run.
    if (offstage || (from == null && to == HouseStage.Handshake)) {
        return if (to == HouseStage.Handshake) HouseMove(to, HouseMoveKind.LightsDown, 0f, 1f, HouseGate.None) else snap()
    }
    if (from == null) {
        return if (to == HouseStage.Room) HouseMove(to, HouseMoveKind.Launch, 0f, 1f, HouseGate.None) else snap()
    }
    val lit = from == HouseStage.Room || from == HouseStage.Fault
    // The lit pool is on screen, so a move off the handshake carries it on from where
    // it is. Raw values, not pN, because pN = 1 also stands for an empty curtain, over
    // which the film's cut is accepted.
    val poolLit = prevAperture > CLOSED_APERTURE && prevDensity > EMPTY_DENSITY
    val poolSeed = if (poolLit) prevAperture.coerceAtMost(1f) else 0f
    return when {
        from == to -> snap()
        lit && to == HouseStage.Handshake -> HouseMove(to, HouseMoveKind.LightsDown, pN, dN, HouseGate.None)
        from == HouseStage.Film && to == HouseStage.Handshake -> HouseMove(
            to,
            HouseMoveKind.VeilIn,
            0f,
            max(filmDim, if (pN <= CLOSED_APERTURE) dN else 0f),
            HouseGate.None,
        )
        from == HouseStage.Handshake && to == HouseStage.Film ->
            HouseMove(to, HouseMoveKind.PictureUp, pN, dN, HouseGate.Reveal)
        lit && to == HouseStage.Film -> snap()
        from == HouseStage.Handshake && to == HouseStage.Room ->
            HouseMove(to, HouseMoveKind.LightsUpRoom, poolSeed, 1f, HouseGate.Rest)
        from == HouseStage.Film && to == HouseStage.Room ->
            HouseMove(to, HouseMoveKind.LightsUpRoom, 0f, 1f, HouseGate.Rest)
        // A fault never moves the aperture: a pool still open holds where it is and
        // dissolves with the rest of the curtain.
        from == HouseStage.Handshake && to == HouseStage.Fault ->
            HouseMove(to, HouseMoveKind.LightsUpFault, poolSeed, 1f, HouseGate.Rest)
        from == HouseStage.Film && to == HouseStage.Fault ->
            HouseMove(to, HouseMoveKind.LightsUpFault, 0f, 1f, HouseGate.Rest)
        // Room ↔ Fault: the shell crossfades on its own.
        else -> HouseMove(to, HouseMoveKind.Rest, pN, dN, HouseGate.None)
    }
}

/**
 * False while a shell face is retained under the house lights or on its way out. Each
 * control reads it where it takes focus: a scroll container is a focus boundary, so
 * a `canFocus = false` set on the face's root never reaches the rows inside one.
 */
internal val LocalShellInteractive = compositionLocalOf { true }

/** One diagnostics-arm reading of the film's frame rate, tagged with the cast it was read under. */
internal data class RateSample(val castId: String?, val frameRate: Float)

/**
 * The rate a reveal may decide its hold on: only a reading taken under the Active
 * cast. An older one can carry the previous film's cadence, or the 0 the player
 * resets to before this film's format is known.
 */
internal fun freshRevealRate(activeCastId: String?, sample: RateSample?): Float =
    if (activeCastId != null && sample?.castId == activeCastId) {
        preferredWindowRefreshRate(presentingVideo = true, contentFrameRate = sample.frameRate)
    } else {
        0f
    }

/** The dim last drawn over the film. Plain rather than snapshot state, so a write invalidates nothing. */
class FilmDimReading {
    var drawn: Float = 0f
}

/**
 * Whether the house lights are staging the film: true from the tunnel's cue until the
 * porthole has opened onto the whole screen and the show is retired. The film's own
 * screen draws the film alone while it is, because the tunnel is drawn over it.
 */
class FilmStaging {
    var staged by mutableStateOf(false)
        internal set
}

/** The porthole's rounded rect, in pixels of a [w] × [h] stage. */
internal data class TunnelCutout(val left: Float, val top: Float, val right: Float, val bottom: Float, val radius: Float)

/** The window's size, as a share of its full size, [elapsedMs] after the tunnel's first frame. */
internal fun tunnelApproach(elapsedMs: Long): Float {
    val t = elapsedMs.coerceAtLeast(0L).toFloat()
    return lerp(FlickMotion.TUNNEL_APPROACH_FROM, 1f, 1f - exp(-t / FlickMotion.TUNNEL_APPROACH_TAU_MS))
}

/**
 * The porthole at expansion progress [e] with the window at [approach] of its size:
 * 0 is the window at the far end, 1 the whole screen, square. The expansion is laid over
 * the approach rather than replacing it, so the window keeps its own speed into it.
 */
internal fun tunnelCutout(e: Float, w: Float, h: Float, approach: Float = 1f): TunnelCutout {
    val p = e.coerceIn(0f, 1f)
    val ww = TUNNEL_WINDOW_FRACTION * w * approach
    val wh = TUNNEL_WINDOW_FRACTION * h * approach
    val cx = w / 2f
    val cy = TUNNEL_VANISH_Y * h
    return TunnelCutout(
        left = lerp(cx - ww / 2f, 0f, p),
        top = lerp(cy - wh / 2f, 0f, p),
        right = lerp(cx + ww / 2f, w, p),
        bottom = lerp(cy + wh / 2f, h, p),
        radius = lerp(TUNNEL_WINDOW_CORNER * ww, 0f, p),
    )
}

/**
 * The tunnel, drawn over the film with a porthole cut out of it. Three vector drawables,
 * each on the RenderThread and each in an image view scaled up from a fraction of the
 * screen's pixels (see [TUNNEL_RASTER_SCALE]): the scale must be the child's own view
 * property, because a canvas scale is recorded into the display list and a vector drawable
 * sizes its bitmap from that.
 *
 * The porthole is a clip, not a punched hole: the wash and the loop are drawn under
 * `clipOutPath` of one rounded rect, which the GPU applies analytically, so there is no
 * offscreen layer and the drawables' bitmaps are untouched by it. The pane that fills the
 * porthole before the film shows is drawn outside the clip. Only the expansion changes
 * the clip, and then only this view's few recorded ops are redrawn.
 */
private class TunnelBackdropView(context: Context) : ViewGroup(context) {
    private val drawables = listOf(R.drawable.tunnel_wash, R.drawable.tunnel_loop, R.drawable.tunnel_window)
        .map { context.getDrawable(it) as AnimatedVectorDrawable }
    private val images = drawables.map { drawable ->
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageDrawable(drawable)
        }
    }
    private val wash get() = images[0]
    private val loop get() = images[1]
    private val pane get() = images[2]

    /** The full-screen layers and the share of the screen's pixels each is rasterised at. */
    private val screenLayers get() = listOf(wash to TUNNEL_WASH_RASTER_SCALE, loop to TUNNEL_RASTER_SCALE)
    private val cutoutPath = android.graphics.Path()
    private var cutoutAt = 0f
    private var approachAt = 1f
    private var rush = 1f

    init {
        images.forEach(::addView)
        screenLayers.forEach { (image, _) ->
            image.pivotX = 0f
            image.pivotY = 0f
        }
        visibility = INVISIBLE
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private fun paneWidth(w: Int) = ceil(w * (TUNNEL_WINDOW_FRACTION + 2f * TUNNEL_PANE_BLEED)).toInt()
    private fun paneHeight(w: Int, h: Int) = ceil(h * TUNNEL_WINDOW_FRACTION + 2f * w * TUNNEL_PANE_BLEED).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        for ((image, scale) in screenLayers) {
            image.measure(
                MeasureSpec.makeMeasureSpec(ceil(w * scale).toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(ceil(h * scale).toInt(), MeasureSpec.EXACTLY),
            )
        }
        pane.measure(
            MeasureSpec.makeMeasureSpec(paneWidth(w), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(paneHeight(w, h), MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for ((image, _) in screenLayers) image.layout(0, 0, image.measuredWidth, image.measuredHeight)
        val vx = (r - l) / 2f
        val vy = TUNNEL_VANISH_Y * (b - t)
        val left = (vx - pane.measuredWidth / 2f).roundToInt()
        val top = (vy - pane.measuredHeight / 2f).roundToInt()
        pane.layout(left, top, left + pane.measuredWidth, top + pane.measuredHeight)
        pane.pivotX = vx - left
        pane.pivotY = vy - top
        placeImages()
        shapeCutout()
    }

    /**
     * Expansion progress (the porthole's), time through the expansion (the tunnel's rush and
     * fade), the pane's share of the porthole, the whole show's presence, and the window's
     * approach.
     */
    fun pose(expand: Float, leave: Float, glow: Float, presence: Float, approach: Float) {
        val e = expand.coerceIn(0f, 1f)
        val going = maxOf(e, leave.coerceIn(0f, 1f))
        rush = lerp(1f, FlickMotion.TUNNEL_RUSH_SCALE, going)
        // Each child takes its own alpha: an image view with no background draws it per
        // op, where an alpha on this group would render the whole screen offscreen.
        val fade = presence * tunnelFade(going)
        wash.alpha = fade
        loop.alpha = fade
        pane.alpha = presence * glow
        val paneScale = approach * rush
        pane.scaleX = paneScale
        pane.scaleY = paneScale
        placeImages()
        if (e != cutoutAt || approach != approachAt) {
            cutoutAt = e
            approachAt = approach
            shapeCutout()
            invalidate()
        }
    }

    // Scaled up from its raster, then rushed out about the vanishing point.
    private fun placeImages() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val vx = w / 2f
        val vy = TUNNEL_VANISH_Y * h
        for ((image, _) in screenLayers) {
            if (image.measuredWidth <= 0 || image.measuredHeight <= 0) continue
            image.scaleX = w / image.measuredWidth * rush
            image.scaleY = h / image.measuredHeight * rush
            image.translationX = vx * (1f - rush)
            image.translationY = vy * (1f - rush)
        }
    }

    private fun shapeCutout() {
        val c = tunnelCutout(cutoutAt, width.toFloat(), height.toFloat(), approachAt)
        cutoutPath.rewind()
        cutoutPath.addRoundRect(c.left, c.top, c.right, c.bottom, c.radius, c.radius, android.graphics.Path.Direction.CW)
    }

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        val time = drawingTime
        if (cutoutAt < 1f) {
            val saved = canvas.save()
            canvas.clipOutPath(cutoutPath)
            drawChild(canvas, wash, time)
            drawChild(canvas, loop, time)
            canvas.restoreToCount(saved)
        }
        drawChild(canvas, pane, time)
    }

    fun start() {
        visibility = VISIBLE
        drawables.forEach { it.start() }
    }

    fun stop() {
        drawables.forEach { it.reset() }
        visibility = INVISIBLE
    }
}

/**
 * The tunnel's state. Outlives every curtain, so a show interrupted by a seam carries on
 * from where it is. [expand] is the porthole's progress to the whole screen; [glow] the
 * lit pane that fills the porthole until the film shows through it, [rim] the porthole's bezel, [caption] the copy, [flash] the
 * white as the window takes the screen, and [presence] the whole show, for a seam that
 * dismisses it with nothing covering it.
 */
private class Tunnel(private val staging: FilmStaging) {
    private var host: TunnelBackdropView? = null
    var running = false
        private set

    /** Starting the loop and stamping its first drawn frame. */
    var entrance: Job? = null
    var captionIn: Job? = null

    /** The loop's first drawn frame; null while it is not running. */
    var startedAtMs: Long? = null

    /** The frame clock while the show is up, which the window's approach is read against. */
    var clockMs by mutableLongStateOf(0L)

    /** The window's size now; it waits at its start until the loop's first frame. */
    val approach: Float
        get() = startedAtMs?.let { tunnelApproach(clockMs - it) } ?: FlickMotion.TUNNEL_APPROACH_FROM

    private var shown by mutableStateOf(false)

    /** Composes the overlay and stages the film; true from the cue until the show is retired. */
    var up: Boolean
        get() = shown
        set(value) {
            shown = value
            staging.staged = value
        }

    val expand = Animatable(0f)
    val glow = Animatable(1f)

    /** Time through the expansion, 0 → 1 linear: the tunnel's rush and fade run on it. */
    val leave = Animatable(0f)
    val rim = Animatable(0f)
    val caption = Animatable(0f)
    val flash = Animatable(0f)
    val presence = Animatable(1f)

    fun attach(view: TunnelBackdropView) {
        host = view
        if (running) view.start()
    }

    fun detach(view: TunnelBackdropView) {
        if (host !== view) return
        view.stop()
        host = null
    }

    fun start() {
        running = true
        up = true
        host?.start()
    }

    /** Called from the draw phase, so the pose lands in the frame its values changed in. */
    fun applyPose() {
        host?.pose(expand.value, leave.value, glow.value, presence.value, approach)
    }

    suspend fun retire() {
        entrance?.cancel()
        entrance = null
        captionIn?.cancel()
        captionIn = null
        host?.stop()
        running = false
        startedAtMs = null
        up = false
        expand.snapTo(0f)
        glow.snapTo(1f)
        leave.snapTo(0f)
        rim.snapTo(0f)
        caption.snapTo(0f)
        flash.snapTo(0f)
        presence.snapTo(1f)
    }
}

private class HouseCurtain(
    val from: HouseStage?,
    val move: HouseMove,
    val aperture: Animatable<Float, AnimationVector1D>,
    val density: Animatable<Float, AnimationVector1D>,
)

private class HouseMemory {
    var lastStage: HouseStage? = null
    var lastCurtain: HouseCurtain? = null
}

/** The headline an exiting tunnel keeps showing once the session has moved on. */
private class HeadlineLatch {
    var last: String? = null
}

/**
 * The room's light: a draw-only curtain over every stage seam, and the handshake's tunnel.
 *
 * Everything here draws over the stage's own content, the film included: the tunnel is
 * opaque but for its porthole, through which the full-size, untransformed film shows.
 * The video's view is never scaled, moved or faded, because on the TV a transformed
 * video layer leaves the hardware composer for a slow blitter. [filmStaging] tells the
 * film's own screen that the show is on.
 *
 * [onLightsDown] runs once the retained standby or error face is covered and may leave
 * composition. [onPictureUp] runs once the resync wait has opened and the tunnel has had
 * its least time on screen; the porthole opens onto the film once [awaitFilmFlowing]
 * says its frames are reaching the screen. The film is
 * held at its first frame until then, so every path into Film must call it.
 * [onPictureSettled] runs once the window has taken the screen.
 *
 * [freshRate] is read only inside the reveal's hold, so a caller can hand over a
 * reader of state that changes while the film plays without recomposing this.
 * [pinnedRate] is the rate the window is being asked for, read once at the seam.
 * [filmDimNow] is read once, when a seam is planned, for the same reason.
 *
 * [offstage] is true for compositions made while the app was stopped: a seam planned
 * then starts from nothing on screen.
 *
 * [awaitOnScreen] returns once the window is really on screen; the tunnel waits for it,
 * so a cast that summons the app from the background runs after the system's open
 * animation rather than under it.
 *
 * [leastShownMs] is the tunnel's least time on screen before the film may appear in it;
 * the stage tests lower it to judge the resync hold on its own.
 */
@Composable
internal fun HouseLights(
    stage: HouseStage,
    deviceLabel: String?,
    title: String?,
    cadence: DisplayCadenceSource,
    keyPresses: Flow<Unit>,
    filmStaging: FilmStaging,
    onLightsDown: () -> Unit,
    onPictureUp: () -> Unit,
    onPictureSettled: () -> Unit,
    modifier: Modifier = Modifier,
    filmDim: Float = 0f,
    filmDimNow: () -> Float = { filmDim },
    offstage: Boolean = false,
    requestedRefreshRate: Float = 0f,
    freshRate: () -> Float = { requestedRefreshRate },
    pinnedRate: () -> Float = { requestedRefreshRate },
    now: () -> Long = SystemClock::uptimeMillis,
    awaitOnScreen: suspend () -> Unit = {},
    leastShownMs: Long = FlickMotion.TUNNEL_MIN_SHOWN_MS,
    awaitFilmFlowing: suspend () -> Unit = {},
) {
    val reduced = LocalReducedMotion.current
    val memory = remember { HouseMemory() }
    val tunnel = remember { Tunnel(filmStaging) }
    val tunnelScope = rememberCoroutineScope()

    val curtain = remember(stage) {
        val prev = memory.lastCurtain
        val pp = prev?.let { Snapshot.withoutReadObservation { it.aperture.value } } ?: 1f
        val pd = prev?.let { Snapshot.withoutReadObservation { it.density.value } } ?: 1f
        val fd = Snapshot.withoutReadObservation { filmDimNow() }
        val move = houseMove(memory.lastStage, stage, pp, pd, fd, reduced, offstage)
        HouseCurtain(memory.lastStage, move, Animatable(move.seedAperture), Animatable(move.seedDensity))
    }
    SideEffect {
        memory.lastStage = stage
        memory.lastCurtain = curtain
    }

    val freshRateNow by rememberUpdatedState(freshRate)
    val pinnedRateNow by rememberUpdatedState(pinnedRate)
    val lightsDownNow by rememberUpdatedState(onLightsDown)
    val pictureUpNow by rememberUpdatedState(onPictureUp)
    val pictureSettledNow by rememberUpdatedState(onPictureSettled)
    val awaitOnScreenNow by rememberUpdatedState(awaitOnScreen)
    val awaitFilmFlowingNow by rememberUpdatedState(awaitFilmFlowing)

    DisposableEffect(Unit) {
        onDispose { filmStaging.staged = false }
    }

    // The approach is read off this clock, so only the porthole's clip and the bezel follow
    // it: nothing recomposes, and the drawables carry their own copy on the RenderThread.
    LaunchedEffect(tunnel.up) {
        while (tunnel.up) withFrameMillis { tunnel.clockMs = now() }
    }

    LaunchedEffect(curtain, reduced) {
        val move = curtain.move
        val aperture = curtain.aperture
        val density = curtain.density
        val edge = now()
        // At the edge, before anything else runs, so a tick landing later cannot rewrite it.
        val releasedAtSeam = pinnedRateNow() <= 0f
        fun <T> spec(s: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> = FlickMotion.orSnap(reduced, s)
        fun covered(): Boolean = aperture.value <= CLOSED_APERTURE && density.value >= 1f - EMPTY_DENSITY

        // Joined, not restarted, by a seam that lands while it is still waiting for the window.
        fun startTunnel() {
            if (tunnel.running || tunnel.entrance?.isActive == true) return
            tunnel.up = true
            tunnel.entrance = tunnelScope.launch {
                val waitStart = now()
                awaitOnScreenNow()
                val waitedMs = now() - waitStart
                if (waitedMs > 0L) FlickLog.i("stage", "tunnel waited for window waitedMs=$waitedMs")
                tunnel.start()
                FlickLog.i("stage", "tunnel start sinceSeamMs=${now() - edge}")
                // The tunnel's time starts on its first drawn frame: the main thread can
                // be held between the cue and that frame, and time spent before it was
                // never on screen.
                withFrameMillis { }
                tunnel.startedAtMs = now()
                tunnel.captionIn = tunnelScope.launch {
                    delay(FlickMotion.TUNNEL_CAPTION_DELAY_MS)
                    tunnel.caption.animateTo(1f, tween(FlickMotion.TUNNEL_CAPTION_IN_MS, easing = FlickMotion.ChromeFade))
                }
            }
        }

        // A seam that cut the last show short (in its hold, dwell, expansion or exit) leaves the
        // loop running; a new handshake takes it on whole, with its copy back.
        suspend fun resumeTunnel() {
            FlickLog.i("stage", "tunnel resume expand=${tunnel.expand.value} presence=${tunnel.presence.value}")
            tunnel.presence.snapTo(1f)
            tunnel.flash.snapTo(0f)
            tunnel.leave.snapTo(0f)
            if (tunnel.running) {
                tunnel.captionIn?.cancel()
                tunnel.captionIn = tunnelScope.launch {
                    tunnel.caption.animateTo(1f, tween(FlickMotion.TUNNEL_CAPTION_IN_MS, easing = FlickMotion.ChromeFade))
                }
            }
        }

        // Returns once the tunnel is opaque over whatever it rose over. The curtain, if
        // it was drawing anything, gives way to it over the same span.
        suspend fun awaitTunnelIn() {
            tunnel.entrance?.join()
            val started = tunnel.startedAtMs ?: now()
            val left = (started + FlickMotion.TUNNEL_FADE_IN_MS - now()).coerceAtLeast(0L)
            if (density.value > EMPTY_DENSITY && aperture.value < OPEN_APERTURE && left > 0L) {
                density.animateTo(0f, tween(left.toInt(), easing = FlickMotion.CrossDissolve))
            } else if (left > 0L) {
                delay(left)
            }
            // Empty, and not a lit pool: an exit from here closes from full dark.
            aperture.snapTo(0f)
            density.snapTo(0f)
        }

        // The overlay draws over the curtain, so even a covered tunnel leaves on a fade.
        suspend fun dismissTunnel() {
            if (!tunnel.up) return
            FlickLog.i("stage", "tunnel dismiss covered=${covered()} sinceSeamMs=${now() - edge}")
            tunnel.presence.animateTo(0f, spec(FlickMotion.filmExit()))
            tunnel.retire()
        }

        suspend fun expandTunnel() {
            val start = now()
            FlickLog.i("stage", "tunnel expand start sinceSeamMs=${start - edge}")
            coroutineScope {
                launch {
                    tunnel.flash.snapTo(FlickMotion.TUNNEL_FLASH_PEAK)
                    tunnel.flash.animateTo(0f, FlickMotion.tunnelFlash())
                }
                launch { tunnel.rim.animateTo(0f, tween(FlickMotion.TUNNEL_RIM_OUT_MS, easing = LinearEasing)) }
                launch { tunnel.leave.animateTo(1f, tween(FlickMotion.TUNNEL_EXPAND_MS, easing = LinearEasing)) }
                tunnel.expand.animateTo(1f, FlickMotion.tunnelExpand())
            }
            delay(FlickMotion.TUNNEL_SETTLE_MS)
            tunnel.retire()
            FlickLog.i("stage", "tunnel expand done tookMs=${now() - start}")
        }

        fun recentChange(): Boolean {
            val last = cadence.lastChangeAtMs ?: return false
            return edge - last < RESYNC_GRACE_MS
        }

        // With no rate yet the pin already given up at the seam is still a switch
        // to wait out; only the one to the film's own cadence is unknown.
        fun revealPending(rate: Float): Boolean {
            if (rate <= 0f) return recentChange()
            return recentChange() ||
                (!cadenceCompatible(cadence.physicalHz, rate) && cadence.switchWouldBlank(rate))
        }

        fun restPending(): Boolean = recentChange() || !sameCadence(cadence.physicalHz, cadence.restHz)

        fun logHold(held: StageHold, rateWaitMs: Long, rate: Float) {
            FlickLog.i(
                "stage",
                "hold kind=${move.gate} rateWaitMs=$rateWaitMs waitedMs=${held.waitMs} reason=${held.reason} " +
                    "requestedHz=$rate physicalHz=${cadence.physicalHz} restHz=${cadence.restHz}",
            )
        }

        suspend fun hold(pending: Boolean) {
            logHold(awaitStageWindow(cadence, now, keyPresses, pending), 0L, freshRateNow())
        }

        fun capLeft(): Long = (STAGE_HOLD_CAP_MS - (now() - edge)).coerceAtLeast(0L)

        // Ends at the tunnel's least time on screen, a key, or the cap. The least time
        // counts only while the panel shows: a mode change restarts it from that
        // change's settle.
        suspend fun awaitTunnelShown(): String {
            val start = now()
            val ended = withTimeoutOrNull(capLeft()) {
                merge(
                    flow {
                        tunnel.entrance?.join()
                        while (true) {
                            val since = tunnelShownSinceMs(tunnel.startedAtMs, cadence.lastChangeAtMs)
                            val left = tunnelHoldLeftMs(since, now(), leastShownMs)
                            if (left <= 0L) break
                            withTimeoutOrNull(left) { cadence.changes.first() }
                        }
                        emit("shown")
                    },
                    keyPresses.map { "key" },
                ).first()
            } ?: "cap"
            FlickLog.i("stage", "tunnel hold reason=$ended waitedMs=${now() - start}")
            return ended
        }

        // The rate comes from the 2 Hz diagnostics arm, so at the first frame it can
        // be unknown or still the previous film's; the pin, and any blank, follow the
        // fresh one. A re-cast can also release the old pin at the seam and re-pin
        // once the rate lands, so a hold that settled on the first switch waits for
        // the second while the panel still cannot show the film's cadence.
        suspend fun holdForReveal(): HoldReason {
            val rateWaitStart = now()
            val physicalAtSeam = cadence.physicalHz
            val changeAtSeam = cadence.lastChangeAtMs
            val unknownAtSeam = freshRateNow() <= 0f
            val keyed = unknownAtSeam && withTimeoutOrNull(RATE_KNOWN_WAIT_MS) {
                merge(
                    snapshotFlow { freshRateNow() }.filter { it > 0f }.map { false },
                    keyPresses.map { true },
                ).first()
            } == true
            val rateWaitMs = now() - rateWaitStart
            if (keyed) {
                logHold(StageHold(0L, HoldReason.KeyPressed), rateWaitMs, freshRateNow())
                return HoldReason.KeyPressed
            }
            val rate = freshRateNow()
            // A pin given up at the seam is a switch that may not be reported yet when
            // the rate lands; the fresh rate alone cannot say so, since the pin reads
            // an untagged snapshot. That snapshot can also hold the old film's pin
            // through the seam and give it up on the first tick after it, when the new
            // film reports no rate.
            val released = releasedAtSeam || pinnedRateNow() <= 0f
            val releaseUnreported = released && cadence.lastChangeAtMs == changeAtSeam &&
                !sameCadence(physicalAtSeam, cadence.restHz)
            val held = awaitStageWindow(cadence, now, keyPresses, releaseUnreported || revealPending(rate), capLeft())
            val settledRate = freshRateNow()
            val owed = held.reason == HoldReason.Settled && capLeft() > 0L &&
                switchStillOwed(cadence.physicalHz, settledRate) { cadence.switchWouldBlank(settledRate) }
            return if (owed) {
                val more = awaitOwedSwitch(cadence, now, keyPresses, capLeft())
                logHold(StageHold(held.waitMs + more.waitMs, more.reason), rateWaitMs, settledRate)
                more.reason
            } else {
                logHold(held, rateWaitMs, rate)
                held.reason
            }
        }

        FlickLog.i("stage", "move from=${curtain.from} to=${move.to} kind=${move.kind}")
        if (reduced || move.kind == HouseMoveKind.Snap) {
            val (p, d) = houseRest(move.to)
            aperture.snapTo(p)
            density.snapTo(d)
            tunnel.retire()
            if (move.to == HouseStage.Handshake) tunnel.caption.snapTo(1f)
            if (move.to != HouseStage.Room && move.to != HouseStage.Fault) lightsDownNow()
            if (move.to == HouseStage.Film) {
                pictureUpNow()
                pictureSettledNow()
            }
            return@LaunchedEffect
        }

        when (move.kind) {
            HouseMoveKind.Launch -> aperture.animateTo(1f, spec(FlickMotion.pictureUp()))

            HouseMoveKind.LightsDown -> {
                // Seeded closed at full density: nothing lit is left to cover.
                val dark = covered()
                if (dark) lightsDownNow()
                if (tunnel.up) {
                    resumeTunnel()
                    tunnel.expand.snapTo(0f)
                    tunnel.rim.snapTo(0f)
                    tunnel.glow.snapTo(1f)
                }
                startTunnel()
                awaitTunnelIn()
                if (!dark) lightsDownNow()
            }

            HouseMoveKind.PictureUp -> {
                FlickLog.i(
                    "stage",
                    "tunnel pictureUp up=${tunnel.up} running=${tunnel.running} " +
                        "shownMs=${tunnel.startedAtMs?.let { now() - it }}",
                )
                // The first frame beat the tunnel's cue: it still runs, and still owes its time.
                startTunnel()
                // This seam's composition (the film's whole playback screen) lands in its
                // first frames; the window opens on a main thread that has finished it.
                val composed = launch {
                    withFrameMillis { }
                    withFrameMillis { }
                }
                launch {
                    awaitTunnelIn()
                    lightsDownNow()
                }
                // A re-cast whose first frame landed while the porthole was still closing
                // on the old film finishes that leg first, and lights the pane over it: the
                // new film's held first frame is not shown before its time.
                val windowed = launch {
                    if (tunnel.expand.value > 0f) {
                        tunnel.expand.animateTo(0f, tween(FlickMotion.TUNNEL_FILM_IN_MS, easing = FlickMotion.CrossDissolve))
                    }
                    if (tunnel.glow.value < 1f) {
                        tunnel.glow.animateTo(1f, tween(FlickMotion.TUNNEL_FILM_IN_MS, easing = FlickMotion.CrossDissolve))
                    }
                }
                var tunnelHold: String? = null
                val shown = launch { tunnelHold = awaitTunnelShown() }
                val revealHold = holdForReveal()
                shown.join()
                // A switch reported after the tunnel's time ran out, while the reveal still
                // waited, blanked part of that time.
                if (tunnelHold == "shown" && revealHold != HoldReason.KeyPressed) awaitTunnelShown()
                composed.join()
                windowed.join()

                pictureUpNow()
                FlickLog.i("stage", "tunnel film released sinceSeamMs=${now() - edge}")
                // The pane gives way only once the released film's frames are reaching the
                // screen, so the porthole never opens onto a frame still held or a surface
                // still settling.
                awaitFilmFlowingNow()
                tunnel.captionIn?.cancel()
                FlickLog.i("stage", "tunnel film shown sinceSeamMs=${now() - edge}")
                launch { tunnel.glow.animateTo(0f, tween(FlickMotion.TUNNEL_FILM_IN_MS, easing = FlickMotion.CrossDissolve)) }
                launch { tunnel.rim.animateTo(1f, tween(FlickMotion.TUNNEL_FILM_IN_MS, easing = FlickMotion.CrossDissolve)) }
                launch { tunnel.caption.animateTo(0f, tween(FlickMotion.TUNNEL_CAPTION_OUT_MS, easing = FlickMotion.ChromeFade)) }
                val keyed = withTimeoutOrNull(FlickMotion.TUNNEL_DWELL_MS) { keyPresses.first() } != null
                FlickLog.i("stage", "tunnel dwell end reason=${if (keyed) "key" else "time"} sinceSeamMs=${now() - edge}")
                expandTunnel()
                pictureSettledNow()
            }

            HouseMoveKind.VeilIn -> {
                // The paused film still fills the screen: the tunnel closes in on it until it
                // shows only through the porthole, and the pane then lights over it. From
                // wherever the porthole is: a re-cast during the last show's dwell or
                // expansion finds it part way open, and it must never widen on the way back.
                val resumed = tunnel.up
                if (resumed) resumeTunnel() else tunnel.glow.snapTo(0f)
                tunnel.expand.snapTo(if (resumed) tunnel.expand.value else 1f)
                startTunnel()
                tunnel.entrance?.join()
                coroutineScope {
                    launch { density.animateTo(0f, tween(FlickMotion.TUNNEL_SHRINK_MS, easing = FlickMotion.CrossDissolve)) }
                    launch {
                        delay((FlickMotion.TUNNEL_SHRINK_MS - FlickMotion.TUNNEL_FILM_IN_MS).toLong())
                        tunnel.rim.animateTo(1f, tween(FlickMotion.TUNNEL_FILM_IN_MS, easing = FlickMotion.CrossDissolve))
                    }
                    tunnel.expand.animateTo(0f, FlickMotion.tunnelShrink())
                }
                tunnel.glow.animateTo(1f, tween(FlickMotion.TUNNEL_FILM_IN_MS, easing = FlickMotion.CrossDissolve))
                FlickLog.i("stage", "tunnel film withdrawn sinceSeamMs=${now() - edge}")
                aperture.snapTo(0f)
                tunnel.rim.animateTo(0f, tween(FlickMotion.TUNNEL_FILM_IN_MS, easing = FlickMotion.CrossDissolve))
            }

            HouseMoveKind.LightsUpRoom -> {
                launch { dismissTunnel() }
                hold(restPending())
                aperture.animateTo(1f, spec(FlickMotion.pictureUp()))
            }

            HouseMoveKind.LightsUpFault -> {
                launch { dismissTunnel() }
                hold(restPending())
                density.animateTo(0f, spec(FlickMotion.crossDissolve()))
            }

            HouseMoveKind.Rest -> {
                launch { dismissTunnel() }
                val p = aperture.value
                val d = density.value
                if (p in CLOSED_APERTURE..OPEN_APERTURE && d > EMPTY_DENSITY) {
                    aperture.animateTo(1f, spec(FlickMotion.pictureUp()))
                } else if (p <= CLOSED_APERTURE && d > EMPTY_DENSITY) {
                    density.animateTo(0f, spec(FlickMotion.crossDissolve()))
                }
            }

            HouseMoveKind.Snap -> Unit
        }
    }

    Box(modifier.fillMaxSize()) {
        // The tunnel's pose is applied in the draw phase, before the tunnel's own view is
        // drawn, so it lands in the same frame as the bezel, flash and curtain read the same
        // values. Both sit on layers of their own: a per-frame redraw of either then stops
        // at its layer instead of re-running every draw lambda in the tree.
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
                .drawBehind { tunnel.applyPose() },
        )
        // Over the film, which shows through its porthole.
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer()
                .clearAndSetSemantics { },
            factory = { context -> TunnelBackdropView(context).also(tunnel::attach) },
            onRelease = { tunnel.detach(it) },
        )

        // Under the overlay: a tunnel leaving over a closed curtain takes its copy with it.
        Spacer(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val opaque = FlickColor.Canvas.toArgb()
                    val radius = size.height / 2f
                    if (radius <= 0f || size.width <= 0f) return@drawWithCache onDrawBehind { }
                    // A framework shader because Compose's radial brush cannot be scaled
                    // anisotropically, and dithered because a dark gradient this wide bands.
                    val colors = IntArray(APERTURE_ALPHAS.size) { i ->
                        FlickColor.Canvas.copy(alpha = APERTURE_ALPHAS[i]).toArgb()
                    }
                    val shader = RadialGradient(
                        0f,
                        0f,
                        radius,
                        colors,
                        APERTURE_POSITIONS,
                        Shader.TileMode.CLAMP,
                    )
                    val holePaint = Paint().apply {
                        isDither = true
                        this.shader = shader
                    }
                    val flatPaint = Paint().apply { color = opaque }
                    onDrawBehind {
                        val p = curtain.aperture.value
                        val d = curtain.density.value
                        if (d <= EMPTY_DENSITY || p >= OPEN_APERTURE) return@onDrawBehind
                        val w = size.width
                        val h = size.height
                        drawIntoCanvas { c ->
                            val nc = c.nativeCanvas
                            val a = (d * 255f).roundToInt()
                            if (p <= CLOSED_APERTURE) {
                                flatPaint.alpha = a
                                nc.drawRect(0f, 0f, w, h, flatPaint)
                            } else {
                                val s = apertureScale(p)
                                val k = w / h
                                holePaint.alpha = a
                                nc.save()
                                nc.translate(w / 2f, h / 2f)
                                nc.scale(s * k, s)
                                nc.drawRect(
                                    -(w / 2f) / (s * k),
                                    -(h / 2f) / s,
                                    (w / 2f) / (s * k),
                                    (h / 2f) / s,
                                    holePaint,
                                )
                                nc.restore()
                            }
                        }
                    }
                },
        )

        val liveHeadline = if (deviceLabel != null && title != null) {
            stringResource(R.string.connecting_device_title, deviceLabel, title)
        } else {
            stringResource(R.string.connecting_title)
        }
        val latch = remember { HeadlineLatch() }
        val headline = if (stage == HouseStage.Handshake) liveHeadline else latch.last ?: liveHeadline
        SideEffect { if (stage == HouseStage.Handshake) latch.last = liveHeadline }

        if (stage == HouseStage.Handshake || tunnel.up) {
            TunnelOverlay(tunnel, headline, live = stage == HouseStage.Handshake)
        }
    }
}

/**
 * Everything of the tunnel that draws over the porthole's edge: the bezel around it, the
 * copy under it, and the flash. Each moves on draw-phase or layer reads, so nothing here
 * recomposes while it animates.
 */
@Composable
private fun TunnelOverlay(tunnel: Tunnel, headline: String, live: Boolean) {
    Box(
        Modifier
            .fillMaxSize()
            .testTag(TUNNEL_TAG)
            .graphicsLayer {
                alpha = tunnel.presence.value
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
    ) {
        Spacer(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val rimW = TUNNEL_RIM_WIDTH.toPx()
                    val bezel = Path().apply { fillType = PathFillType.EvenOdd }
                    onDrawBehind {
                        val r = tunnel.rim.value
                        if (r <= 0f) return@onDrawBehind
                        val c = tunnelCutout(tunnel.expand.value, size.width, size.height, tunnel.approach)
                        bezel.reset()
                        bezel.fillType = PathFillType.EvenOdd
                        bezel.addRoundRect(
                            RoundRect(c.left - rimW, c.top - rimW, c.right + rimW, c.bottom + rimW, CornerRadius(c.radius + rimW)),
                        )
                        bezel.addRoundRect(RoundRect(c.left, c.top, c.right, c.bottom, CornerRadius(c.radius)))
                        drawPath(bezel, FlickColor.TunnelWindow, alpha = r)
                    }
                },
        )

        Column(
            modifier = Modifier
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        val top = (TUNNEL_VANISH_Y + TUNNEL_WINDOW_FRACTION / 2f) * constraints.maxHeight
                        placeable.place(
                            (constraints.maxWidth - placeable.width) / 2,
                            (top + TUNNEL_CAPTION_GAP.toPx()).roundToInt(),
                        )
                    }
                }
                .width(TUNNEL_CAPTION_WIDTH)
                .graphicsLayer {
                    alpha = tunnel.caption.value
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                .then(if (live) Modifier else Modifier.clearAndSetSemantics { }),
            verticalArrangement = Arrangement.spacedBy(FlickSpace.Md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FlickSwap(
                target = headline,
                resize = true,
                contentAlignment = Alignment.Center,
                label = "handshakeHeadline",
            ) {
                Text(
                    text = it,
                    style = FlickType.display(sizeSp = 22),
                    color = FlickColor.OnSurface,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                text = stringResource(R.string.connecting_detail),
                style = FlickType.body(sizeSp = 16),
                color = FlickColor.OnSurfaceDim,
                textAlign = TextAlign.Center,
            )
        }

        // Alpha on the draw, not the layer: a full-screen layer alpha would render offscreen.
        Spacer(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val f = tunnel.flash.value
                    if (f > 0f) drawRect(FlickColor.TunnelFlash, alpha = f)
                },
        )
    }
}
