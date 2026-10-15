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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.TransformOrigin
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

/** The film view's vertical offset in the window, as a fraction of its height. */
internal const val TUNNEL_WINDOW_LIFT = TUNNEL_VANISH_Y - 0.5f

/**
 * The tunnel is rasterised at this share of the screen's pixels and scaled up by its
 * view. A vector drawable animating on the RenderThread is redrawn into a bitmap and
 * uploaded every frame, and at full 1080p that is 8 MB a frame on the TV's GPU.
 */
private const val TUNNEL_RASTER_SCALE = 0.5f

/**
 * The bezel the film carries in the window. It hides the surface's square corners
 * outside the window's rounded ones, so its outer radius must stay at most
 * (2 + √2) times its width: [TUNNEL_RIM_RADIUS] ≤ 13.6 dp for a 4 dp rim.
 */
internal val TUNNEL_RIM_WIDTH = 4.dp
internal val TUNNEL_RIM_RADIUS = 12.dp

/**
 * The handshake copy's width under the window. Set by the headline it carries: at
 * 380 dp "<device> is flicking <film> (year)" wrapped and left the year alone on the
 * second line. 560 dp holds a film name and year of roughly thirty characters on one
 * line; longer ones wrap inside the name instead.
 */
internal val TUNNEL_CAPTION_WIDTH = 560.dp

/** The copy's gap under the window. */
private val TUNNEL_CAPTION_GAP = 28.dp

/** Test tag on the tunnel's overlay: the window cover, the bezel, the copy and the flash. */
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
 * Where the film's view sits while the house lights stage it: a view transform, never
 * a resize. Scaling a view leaves its surface's buffer alone, so the decoder is never
 * reconfigured and no SurfaceSyncGroup holds the window; the SurfaceView follows its
 * ancestors' transform and punches its hole at the transformed rect, so what is drawn
 * before it shows around the window and what is drawn after it covers the film.
 *
 * Hidden is alpha 0, never INVISIBLE or GONE, which would destroy the surface. A view
 * attached mid-pose takes the pose before its first draw.
 */
class FilmPose {
    private var view: View? = null
    private val relayout = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> apply(v) }

    var scale: Float = 1f
        private set
    var lift: Float = 0f
        private set
    var shown: Boolean = true
        private set

    fun attach(v: View) {
        view?.removeOnLayoutChangeListener(relayout)
        view = v
        v.addOnLayoutChangeListener(relayout)
        apply(v)
    }

    fun detach(v: View) {
        if (view !== v) return
        v.removeOnLayoutChangeListener(relayout)
        view = null
    }

    fun set(scale: Float = this.scale, lift: Float = this.lift, shown: Boolean = this.shown) {
        this.scale = scale
        this.lift = lift
        this.shown = shown
        view?.let(::apply)
    }

    /** In the tunnel's window at expansion progress [e]: 0 is the window, 1 the whole screen. */
    fun at(e: Float, shown: Boolean = this.shown) =
        set(lerp(TUNNEL_WINDOW_FRACTION, 1f, e), lerp(TUNNEL_WINDOW_LIFT, 0f, e), shown)

    fun full() = set(1f, 0f, true)

    private fun apply(v: View) {
        v.pivotX = v.width / 2f
        v.pivotY = v.height / 2f
        v.scaleX = scale
        v.scaleY = scale
        v.translationY = lift * v.height
        v.alpha = if (shown) 1f else 0f
    }
}

/**
 * The tunnel's loop, half resolution and scaled up (see [TUNNEL_RASTER_SCALE]). The
 * scale must be the child view's own property: a canvas scale would be recorded into
 * the display list, and the vector drawable sizes its bitmap from that.
 */
private class TunnelBackdropView(context: Context) : ViewGroup(context) {
    private val loop = context.getDrawable(R.drawable.tunnel_loop) as AnimatedVectorDrawable
    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        setImageDrawable(loop)
        pivotX = 0f
        pivotY = 0f
    }

    init {
        addView(image)
        visibility = INVISIBLE
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        image.measure(
            MeasureSpec.makeMeasureSpec(ceil(w * TUNNEL_RASTER_SCALE).toInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(ceil(h * TUNNEL_RASTER_SCALE).toInt(), MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        image.layout(0, 0, image.measuredWidth, image.measuredHeight)
        if (image.measuredWidth > 0 && image.measuredHeight > 0) {
            image.scaleX = (r - l).toFloat() / image.measuredWidth
            image.scaleY = (b - t).toFloat() / image.measuredHeight
        }
    }

    override fun hasOverlappingRendering(): Boolean = false

    fun start() {
        visibility = VISIBLE
        loop.start()
    }

    fun stop() {
        loop.reset()
        visibility = INVISIBLE
    }
}

/**
 * The tunnel's state. Outlives every curtain, so a show interrupted by a seam carries on
 * from where it is. [expand] is the window's progress to the whole screen; [cover] the
 * glowing window over the film, [rim] the film's bezel, [caption] the copy, [flash] the
 * white as the window takes the screen, and [presence] the whole show, for a seam that
 * dismisses it with nothing covering it.
 */
private class Tunnel {
    private var host: TunnelBackdropView? = null
    var running = false
        private set

    /** Starting the loop and stamping its first drawn frame. */
    var entrance: Job? = null
    var captionIn: Job? = null

    /** The loop's first drawn frame; null while it is not running. */
    var startedAtMs: Long? = null

    /** Composes the overlay; true from the cue until the show is retired. */
    var up by mutableStateOf(false)

    val expand = Animatable(0f)
    val cover = Animatable(0f)
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
        cover.snapTo(0f)
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
 * [film] is the stage's own content. It is composed between the tunnel and its overlay,
 * because the film's surface must punch through the tunnel at the window while the
 * window's cover, the bezel, the copy and the flash draw over the film. [filmPose] is the
 * film view's transform, shared with whoever creates that view.
 *
 * [onLightsDown] runs once the retained standby or error face is covered and may leave
 * composition. [onPictureUp] runs when the film appears in the tunnel's window: the
 * resync wait has opened and the tunnel has had its least time on screen. The film is
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
    filmPose: FilmPose,
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
    film: @Composable BoxScope.() -> Unit = {},
) {
    val reduced = LocalReducedMotion.current
    val memory = remember { HouseMemory() }
    val tunnel = remember { Tunnel() }
    val tunnelScope = rememberCoroutineScope()

    val curtain = remember(stage) {
        val prev = memory.lastCurtain
        val pp = prev?.let { Snapshot.withoutReadObservation { it.aperture.value } } ?: 1f
        val pd = prev?.let { Snapshot.withoutReadObservation { it.density.value } } ?: 1f
        val fd = Snapshot.withoutReadObservation { filmDimNow() }
        val move = houseMove(memory.lastStage, stage, pp, pd, fd, reduced, offstage)
        // Here rather than in an effect: a film view this same composition creates is
        // drawn before any effect runs, and it must be in the window, hidden, from its
        // first frame.
        when (move.kind) {
            HouseMoveKind.LightsDown -> filmPose.at(0f, shown = false)
            HouseMoveKind.PictureUp, HouseMoveKind.VeilIn -> Unit
            else -> filmPose.full()
        }
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

    DisposableEffect(Unit) {
        onDispose { filmPose.full() }
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
            filmPose.full()
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
                tunnel.expand.animateTo(1f, FlickMotion.tunnelExpand()) { filmPose.at(value) }
            }
            filmPose.full()
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
            filmPose.full()
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
                // A re-cast whose first frame landed while the old film was still being
                // drawn back into the window finishes that leg first, and covers it: the
                // new film's held first frame is not shown before its time.
                val windowed = launch {
                    if (tunnel.expand.value > 0f) {
                        tunnel.expand.animateTo(0f, tween(FlickMotion.TUNNEL_FILM_IN_MS, easing = FlickMotion.CrossDissolve)) {
                            filmPose.at(value)
                        }
                    }
                    if (filmPose.shown) {
                        tunnel.cover.animateTo(1f, tween(FlickMotion.TUNNEL_FILM_IN_MS, easing = FlickMotion.CrossDissolve))
                        filmPose.at(0f, shown = false)
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

                // The glowing window is drawn over the film first, so the film's view can
                // be shown under it in the same frame and the glow then gives way.
                tunnel.cover.snapTo(1f)
                filmPose.at(0f, shown = true)
                tunnel.captionIn?.cancel()
                FlickLog.i("stage", "tunnel film shown sinceSeamMs=${now() - edge}")
                pictureUpNow()
                launch { tunnel.cover.animateTo(0f, tween(FlickMotion.TUNNEL_FILM_IN_MS, easing = FlickMotion.CrossDissolve)) }
                launch { tunnel.rim.animateTo(1f, tween(FlickMotion.TUNNEL_FILM_IN_MS, easing = FlickMotion.CrossDissolve)) }
                launch { tunnel.caption.animateTo(0f, tween(FlickMotion.TUNNEL_CAPTION_OUT_MS, easing = FlickMotion.ChromeFade)) }
                val keyed = withTimeoutOrNull(FlickMotion.TUNNEL_DWELL_MS) { keyPresses.first() } != null
                FlickLog.i("stage", "tunnel dwell end reason=${if (keyed) "key" else "time"} sinceSeamMs=${now() - edge}")
                expandTunnel()
                pictureSettledNow()
            }

            HouseMoveKind.VeilIn -> {
                // The paused film still fills the screen: it is drawn back down into the
                // window while the tunnel rises behind it, and the window then glows over it.
                tunnel.expand.snapTo(1f)
                startTunnel()
                tunnel.entrance?.join()
                coroutineScope {
                    launch { density.animateTo(0f, tween(FlickMotion.TUNNEL_SHRINK_MS, easing = FlickMotion.CrossDissolve)) }
                    launch {
                        delay((FlickMotion.TUNNEL_SHRINK_MS - FlickMotion.TUNNEL_FILM_IN_MS).toLong())
                        tunnel.rim.animateTo(1f, tween(FlickMotion.TUNNEL_FILM_IN_MS, easing = FlickMotion.CrossDissolve))
                    }
                    tunnel.expand.animateTo(0f, FlickMotion.tunnelShrink()) { filmPose.at(value) }
                }
                tunnel.cover.animateTo(1f, tween(FlickMotion.TUNNEL_FILM_IN_MS, easing = FlickMotion.CrossDissolve))
                filmPose.at(0f, shown = false)
                tunnel.cover.snapTo(0f)
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
        // Below the film, whose surface punches through it at the window.
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .clearAndSetSemantics { }
                .graphicsLayer {
                    val e = tunnel.expand.value
                    val rush = lerp(1f, FlickMotion.TUNNEL_RUSH_SCALE, e)
                    scaleX = rush
                    scaleY = rush
                    transformOrigin = TransformOrigin(0.5f, TUNNEL_VANISH_Y)
                    alpha = tunnel.presence.value * tunnelFade(e)
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
            factory = { context -> TunnelBackdropView(context).also(tunnel::attach) },
            onRelease = { tunnel.detach(it) },
        )

        Box(Modifier.fillMaxSize(), content = film)

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
 * Everything of the tunnel that draws over the film: the window's cover and the film's
 * bezel, posed exactly as the film's view is, the copy under the window, and the flash.
 * Each moves on its layer's properties, so nothing here recomposes while it animates.
 */
@Composable
private fun TunnelOverlay(tunnel: Tunnel, headline: String, live: Boolean) {
    Box(
        Modifier
            .fillMaxSize()
            .testTag(TUNNEL_TAG)
            .graphicsLayer { alpha = tunnel.presence.value },
    ) {
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val e = tunnel.expand.value
                    val s = lerp(TUNNEL_WINDOW_FRACTION, 1f, e)
                    scaleX = s
                    scaleY = s
                    translationY = lerp(TUNNEL_WINDOW_LIFT, 0f, e) * size.height
                }
                .drawWithCache {
                    // Drawn at the screen's size and scaled down with the film, so the
                    // window's own corner and bezel are divided by the window's scale.
                    val w = size.width
                    val h = size.height
                    val corner = CornerRadius(TUNNEL_WINDOW_CORNER * w)
                    val rimW = TUNNEL_RIM_WIDTH.toPx() / TUNNEL_WINDOW_FRACTION
                    val rimR = CornerRadius(TUNNEL_RIM_RADIUS.toPx() / TUNNEL_WINDOW_FRACTION)
                    val window = Path().apply { addRoundRect(RoundRect(0f, 0f, w, h, corner)) }
                    val bezel = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRoundRect(RoundRect(-rimW, -rimW, w + rimW, h + rimW, rimR))
                        addRoundRect(RoundRect(0f, 0f, w, h, corner))
                    }
                    onDrawBehind {
                        val c = tunnel.cover.value
                        if (c > 0f) drawPath(window, FlickColor.TunnelWindow, alpha = c)
                        val r = tunnel.rim.value
                        if (r > 0f) drawPath(bezel, FlickColor.TunnelWindow, alpha = r)
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

        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = tunnel.flash.value }
                .background(FlickColor.TunnelFlash),
        )
    }
}
