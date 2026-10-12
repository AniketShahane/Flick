package com.flick.receiver.ui.components

import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
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
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

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
 * The handshake card enters once the aperture has closed this far. The last light
 * pool holds the lit mark and the clock; any earlier and the card and its copy land
 * on them while they are still legible.
 */
internal const val CARD_ENTER_APERTURE = 0.12f

/** The handshake veil. The state-overlay plate's contrast figures in Color.kt assume this same density, so it stays tied to [FlickColor.ScrimVeil]. */
internal val VEIL_DENSITY: Float = FlickColor.ScrimVeil.alpha

internal fun apertureScale(p: Float): Float = p.coerceIn(0f, 1f) * APERTURE_S_OPEN

/**
 * How much longer a card that began its flight at [enteredAtMs] must stay before it may
 * take the screen. A card seated without a flight (null) owes nothing.
 */
internal fun cardHoldLeftMs(enteredAtMs: Long?, nowMs: Long): Long =
    if (enteredAtMs == null) 0L else (enteredAtMs + FlickMotion.FLICK_CARD_MIN_SHOWN_MS - nowMs).coerceAtLeast(0L)

/**
 * When a card that began its flight at [enteredAtMs] was first really on screen: a display
 * mode change at [lastChangeAtMs] blanks the panel until its resync grace has run, so a
 * change landing after the card entered, or just before, restarts the card's time from
 * the settle. Null for a card seated without a flight, which owes nothing.
 */
internal fun cardShownSinceMs(enteredAtMs: Long?, lastChangeAtMs: Long?): Long? {
    if (enteredAtMs == null) return null
    val settledAt = lastChangeAtMs?.plus(RESYNC_GRACE_MS) ?: return enteredAtMs
    return max(enteredAtMs, settledAt)
}

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

/** The longest a retained face may outlive its stage if the curtain never reports it dark. */
internal const val RETAINED_SHELL_LIMIT_MS = 1_500L

/** The longest a film waits for the curtain to report the picture fully up. */
internal const val REVEAL_SETTLE_FALLBACK_MS = 4_000L

/**
 * The handshake column's width (receiver-expressive-spec.md §5.2). Set by the headline
 * it carries: at 380 dp "<device> is flicking <film> (year)" wrapped and left the year
 * alone on the second line. 560 dp holds a film name and year of roughly thirty
 * characters on one line; longer ones wrap inside the name instead.
 */
internal val HANDSHAKE_CARD_WIDTH = 560.dp

/** The Flick card at rest (receiver-expressive-spec.md §5.2). */
internal val FLICK_CARD_WIDTH = 160.dp
internal val FLICK_CARD_HEIGHT = 106.dp
private val FLICK_CARD_RADIUS = 16.dp

/** The corner the card keeps once it has taken the screen. */
private val FLICK_CARD_EXPANDED_RADIUS = 3.dp

/** The glow's vertical reach from the card's centre; it is [FLICK_CARD_GLOW_ASPECT] times wider. */
private val FLICK_CARD_GLOW_RADIUS = 76.dp
private const val FLICK_CARD_GLOW_ASPECT = 1.5f
private const val FLICK_CARD_GLOW_ALPHA = 0.55f

/** One unit of the brand mark's 64-unit grid, which puts a ~47 dp play mark on the card. */
private val FLICK_MARK_UNIT = 1.1.dp

/** The mark's triangle is centred on its centroid, not its bounds, so it sits optically centred. */
private const val MARK_CENTROID_X = 112f / 3f
private const val MARK_CENTROID_Y = 32f

/** The mark's streak weights, 0.45 / 0.75 / 0.45, stood upright under the card. */
private val SPEED_BAR_WIDTHS = listOf(6.dp, 7.dp, 6.dp)
private val SPEED_BAR_HEIGHTS = listOf(64.dp, 88.dp, 64.dp)
private val SPEED_BAR_PITCH = 15.dp
private val SPEED_BAR_GAP = 10.dp
private val SPEED_BAR_DRIFT = 24.dp

/** The share of the bars' run spent flashing in; the rest dissolves them downward. */
private const val SPEED_BAR_FLASH = 0.15f

/** [HouseCard.arrive] at the overshoot pose and seated. */
private const val ARRIVE_OVERSHOOT = 1f
private const val ARRIVE_SEATED = 2f

private val TWO_PI = (2.0 * PI).toFloat()

/** The speed bars' alpha across their run [b], 0 → 1. */
internal fun speedBarAlpha(b: Float): Float = when {
    b <= 0f || b >= 1f -> 0f
    b < SPEED_BAR_FLASH -> b / SPEED_BAR_FLASH
    else -> (1f - b) / (1f - SPEED_BAR_FLASH)
}

/**
 * The degrade switch for a turned film, which is composited into the app's window
 * rather than presented on a layer of its own. Flip to true only if the turned-film
 * framestats check fails.
 */
private const val SNAP_REVEAL_ON_TURNED_FILM = false

/** A curtain with this little density, or this much aperture, draws nothing. */
private const val EMPTY_DENSITY = 0.004f
private const val OPEN_APERTURE = 0.999f
private const val CLOSED_APERTURE = 0.001f

/** Where each stage's curtain rests: (aperture, density). */
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
    // Closed and fully dense is opaque Canvas, the same black as the covered surface.
    if (offstage) {
        return if (to == HouseStage.Handshake) HouseMove(to, HouseMoveKind.LightsDown, 0f, 1f, HouseGate.None) else snap()
    }
    if (from == null) {
        return if (to == HouseStage.Room) HouseMove(to, HouseMoveKind.Launch, 0f, 1f, HouseGate.None) else snap()
    }
    val lit = from == HouseStage.Room || from == HouseStage.Fault
    // Act I still closing (or not yet started): the lit pool is on screen, so a move
    // off the handshake carries it on from where it is. Raw values, not pN, because
    // pN = 1 also stands for an empty curtain, over which the film's cut is accepted.
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
 * False while a shell face is retained under the curtain or on its way out. Each
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

/**
 * Outlives every curtain, so an interrupted entrance or exit continues from where it is.
 * [alpha] is the whole card's; [copy], [rise] and [sink] move only the text, which
 * leaves at the first frame while the Flick card stays to take the screen.
 */
private class HouseCard {
    val alpha = Animatable(0f)
    val rise = Animatable(1f)
    val sink = Animatable(0f)
    val copy = Animatable(1f)

    /** 0 below the screen, [ARRIVE_OVERSHOOT] at the overshoot pose, [ARRIVE_SEATED] seated. */
    val arrive = Animatable(0f)

    /** The speed bars' run; 1 is spent. */
    val bars = Animatable(1f)

    /**
     * The arrival and its speed bars. Run in the composition's scope rather than a
     * seam's, so the first frame landing mid-flight does not cut it short.
     */
    var flight: Job? = null

    /** When the current flight began; null for a card seated without one, or gone. */
    var enteredAtMs: Long? = null

    val flying: Boolean get() = flight?.isActive == true

    /** The bob's phase in cycles. Only its fraction is drawn, so a resumed bob carries on from it. */
    val bob = Animatable(0f)
    val expand = Animatable(0f)
    val mark = Animatable(1f)

    /** The expanded card's fade into the film. */
    val cover = Animatable(1f)
    var held by mutableStateOf(false)
    var settledIn by mutableStateOf(false)

    /** Written in layout and read only in draw, so a resize repositions the card without recomposing. */
    var columnHeight by mutableIntStateOf(0)

    suspend fun seat() {
        land()
        alpha.snapTo(1f)
        rise.snapTo(0f)
        sink.snapTo(0f)
        copy.snapTo(1f)
        arrive.snapTo(ARRIVE_SEATED)
        bars.snapTo(1f)
        bob.snapTo(0f)
        expand.snapTo(0f)
        mark.snapTo(1f)
        cover.snapTo(1f)
        held = true
        settledIn = true
    }

    fun land() {
        flight?.cancel()
        flight = null
        enteredAtMs = null
    }

    suspend fun retire() {
        land()
        held = false
        settledIn = false
        alpha.snapTo(0f)
        rise.snapTo(1f)
        sink.snapTo(0f)
        copy.snapTo(1f)
        arrive.snapTo(0f)
        bars.snapTo(1f)
        expand.snapTo(0f)
        mark.snapTo(1f)
        cover.snapTo(1f)
    }
}

/** The headline an exiting card keeps showing once the session has moved on. */
private class HeadlineLatch {
    var last: String? = null
}

/**
 * The room's light: a draw-only curtain over every stage seam, and the handshake card.
 *
 * [onLightsDown] runs once the retained standby or error face is under full dark and
 * may leave composition. [onPictureUp] runs when the resync wait opens and the Flick
 * card has had its least time on screen, and the veil is about to lift; the film is held at
 * its first frame until then, so every path into Film must call it.
 * [onPictureSettled] when the lift has finished.
 *
 * [freshRate] is read only inside the reveal's hold, so a caller can hand over a
 * reader of state that changes while the film plays without recomposing this.
 * [pinnedRate] is the rate the window is being asked for, read once at the seam.
 * [filmDimNow] is read once, when a seam is planned, for the same reason.
 *
 * [offstage] is true for compositions made while the app was stopped: a seam planned
 * then starts from nothing on screen.
 *
 * [expandAtPictureUp] false lets the Flick card leave with its copy at the first frame
 * instead of taking the screen once the resync wait ends. The stage tests use it to read
 * the veil's lift, which the expanded card covers.
 */
@Composable
internal fun HouseLights(
    stage: HouseStage,
    turnedFilm: Boolean,
    deviceLabel: String?,
    title: String?,
    cadence: DisplayCadenceSource,
    keyPresses: Flow<Unit>,
    onLightsDown: () -> Unit,
    onPictureUp: () -> Unit,
    onPictureSettled: () -> Unit,
    modifier: Modifier = Modifier,
    filmDim: Float = 0f,
    filmDimNow: () -> Float = { filmDim },
    offstage: Boolean = false,
    expandAtPictureUp: Boolean = true,
    requestedRefreshRate: Float = 0f,
    freshRate: () -> Float = { requestedRefreshRate },
    pinnedRate: () -> Float = { requestedRefreshRate },
    now: () -> Long = SystemClock::uptimeMillis,
) {
    val reduced = LocalReducedMotion.current
    val memory = remember { HouseMemory() }
    val card = remember { HouseCard() }
    val cardScope = rememberCoroutineScope()

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
    val latestTurnedFilm by rememberUpdatedState(turnedFilm)
    val expandNow by rememberUpdatedState(expandAtPictureUp)
    val lightsDownNow by rememberUpdatedState(onLightsDown)
    val pictureUpNow by rememberUpdatedState(onPictureUp)
    val pictureSettledNow by rememberUpdatedState(onPictureSettled)
    val stateSpec by rememberUpdatedState(FlickMotion.stateEffects<Float>())
    val fastSpec by rememberUpdatedState(FlickMotion.fastStateEffects<Float>())
    val panelSpec by rememberUpdatedState(FlickMotion.panelSpatial<Float>())
    val focusSpec by rememberUpdatedState(FlickMotion.focusSpatial<Float>())

    LaunchedEffect(curtain, reduced) {
        val move = curtain.move
        val aperture = curtain.aperture
        val density = curtain.density
        val edge = now()
        // At the edge, before Act I closes, so a tick landing during the close cannot rewrite it.
        val releasedAtSeam = pinnedRateNow() <= 0f
        fun <T> spec(s: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> = FlickMotion.orSnap(reduced, s)

        fun startFlight() {
            card.flight?.cancel()
            card.enteredAtMs = null
            card.flight = cardScope.launch {
                // The keyframes pin their start, so a flight only ever runs from 0.
                card.arrive.snapTo(0f)
                // The card's time starts on the flight's first drawn frame: the main thread
                // can be held between the cue and that frame (the previous player's release
                // runs there), and time spent before it was never on screen.
                launch {
                    withFrameMillis { }
                    card.enteredAtMs = now()
                }
                launch { card.arrive.animateTo(ARRIVE_SEATED, spec(FlickMotion.flickCardArrive())) }
                launch {
                    delay(FlickMotion.SPEED_BARS_LAG_MS)
                    card.bars.snapTo(0f)
                    card.bars.animateTo(1f, spec(FlickMotion.speedBars()))
                }
            }
        }

        // Rising, the Flick card flies up from below the screen into the last light,
        // or over a film into the veil; otherwise it fades in already seated.
        suspend fun enterCard(alphaSpec: FiniteAnimationSpec<Float>, rising: Boolean) {
            card.held = true
            card.settledIn = false
            val flies = rising && card.arrive.value <= 0f
            FlickLog.i("stage", "card enter rising=$rising flies=$flies sinceSeamMs=${now() - edge}")
            if (flies) startFlight()
            coroutineScope {
                launch { card.alpha.animateTo(1f, spec(alphaSpec)) }
                launch { card.copy.animateTo(1f, spec(alphaSpec)) }
                launch { card.rise.animateTo(0f, spec(panelSpec)) }
                launch { card.sink.animateTo(0f, spec(panelSpec)) }
                when {
                    flies -> Unit
                    !rising -> card.arrive.snapTo(ARRIVE_SEATED)
                    // Caught mid-exit: the keyframes pin their start, so settle from here.
                    else -> launch { card.arrive.animateTo(ARRIVE_SEATED, spec(panelSpec)) }
                }
            }
            card.flight?.join()
            card.settledIn = true
            FlickLog.i("stage", "card seated sinceSeamMs=${now() - edge}")
        }

        suspend fun exitCard(alphaSpec: FiniteAnimationSpec<Float>) {
            if (!card.held) return
            FlickLog.i("stage", "card exit flying=${card.flying} sinceSeamMs=${now() - edge}")
            card.settledIn = false
            card.land()
            coroutineScope {
                launch { card.alpha.animateTo(0f, spec(alphaSpec)) }
                launch { card.sink.animateTo(1f, spec(focusSpec)) }
            }
            card.retire()
        }

        // An expansion cut off by the next seam would otherwise hold the screen until
        // its card re-entered.
        suspend fun clearExpanded() {
            if (card.held && card.expand.value > 0f) exitCard(fastSpec)
        }

        suspend fun expandCard() {
            val start = now()
            FlickLog.i("stage", "card expand start flying=${card.flying} sinceSeamMs=${start - edge}")
            coroutineScope {
                launch { card.mark.animateTo(0f, spec(FlickMotion.flickCardMarkFade())) }
                launch { card.expand.animateTo(1f, spec(FlickMotion.flickCardExpand())) }
                launch {
                    delay(FlickMotion.FLICK_CARD_FADE_DELAY_MS)
                    card.cover.animateTo(0f, spec(FlickMotion.flickCardFade()))
                }
            }
            card.retire()
            FlickLog.i("stage", "card expand done tookMs=${now() - start}")
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

        // Ends at the flight's end plus the card's least time on screen, a key, or the cap.
        // The least time counts only while the panel shows: a mode change restarts it
        // from that change's settle.
        suspend fun awaitCardShown(): String {
            val start = now()
            val ended = withTimeoutOrNull(capLeft()) {
                merge(
                    flow {
                        card.flight?.join()
                        while (true) {
                            val left = cardHoldLeftMs(cardShownSinceMs(card.enteredAtMs, cadence.lastChangeAtMs), now())
                            if (left <= 0L) break
                            withTimeoutOrNull(left) { cadence.changes.first() }
                        }
                        emit("shown")
                    },
                    keyPresses.map { "key" },
                ).first()
            } ?: "cap"
            FlickLog.i("stage", "card hold reason=$ended waitedMs=${now() - start}")
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
            if (move.to == HouseStage.Handshake) card.seat() else card.retire()
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
                // Seeded closed at full density: nothing lit is left to close over.
                val alreadyDark = aperture.value <= CLOSED_APERTURE && density.value >= 1f - EMPTY_DENSITY
                if (alreadyDark) lightsDownNow()
                val clearing = launch { clearExpanded() }
                launch {
                    if (aperture.value > CLOSED_APERTURE) {
                        snapshotFlow { aperture.value <= CARD_ENTER_APERTURE }.first { it }
                    } else {
                        // A fault still dissolving: the retained face must be mostly dark
                        // before the card lands on it.
                        snapshotFlow { density.value >= 1f - CARD_ENTER_APERTURE }.first { it }
                    }
                    clearing.join()
                    enterCard(stateSpec, rising = true)
                }
                if (alreadyDark) {
                    density.animateTo(VEIL_DENSITY, spec(FlickMotion.crossDissolve()))
                    return@LaunchedEffect
                }
                if (aperture.value > CLOSED_APERTURE) {
                    // Same curve for both, so the face outside the pool is as dark as
                    // the pool is closed when the card enters.
                    coroutineScope {
                        if (density.value < 1f) launch { density.animateTo(1f, spec(FlickMotion.lightsDown())) }
                        aperture.animateTo(0f, spec(FlickMotion.lightsDown()))
                    }
                } else {
                    density.animateTo(1f, spec(FlickMotion.lightsDown()))
                }
                lightsDownNow()
                density.snapTo(VEIL_DENSITY)
            }

            HouseMoveKind.PictureUp -> {
                val expanding = expandNow
                FlickLog.i(
                    "stage",
                    "card pictureUp held=${card.held} flying=${card.flying} " +
                        "shownMs=${card.enteredAtMs?.let { now() - it }}",
                )
                if (expanding) {
                    // The copy leaves at once; the card holds its seat through the resync
                    // wait, so its expansion is never thrown into a mode switch's blank.
                    card.settledIn = false
                    if (card.held) {
                        launch { card.copy.animateTo(0f, spec(FlickMotion.crossDissolve())) }
                    } else {
                        // The first frame beat the card's cue: it flies in without its copy.
                        card.held = true
                        card.copy.snapTo(0f)
                        FlickLog.i("stage", "card enter rising=true flies=true sinceSeamMs=${now() - edge}")
                        startFlight()
                    }
                    launch { card.sink.animateTo(1f, spec(focusSpec)) }
                    launch { card.alpha.animateTo(1f, spec(stateSpec)) }
                    // A flight in progress keeps its own arrival and bars.
                    if (!card.flying) {
                        launch { card.bars.animateTo(1f, spec(FlickMotion.speedBars())) }
                        if (card.arrive.value < ARRIVE_SEATED) {
                            launch { card.arrive.animateTo(ARRIVE_SEATED, spec(panelSpec)) }
                        }
                    }
                } else {
                    FlickLog.i("stage", "card skip reason=expandOff held=${card.held}")
                    launch { exitCard(FlickMotion.crossDissolve()) }
                }
                var cardHold: String? = null
                val cardShown = if (expanding) launch { cardHold = awaitCardShown() } else null
                if (aperture.value > CLOSED_APERTURE) aperture.animateTo(0f, spec(fastSpec))
                // Also when Act I had already closed: releasing a face that is already
                // gone is a no-op, and a face still retained would cover the film.
                lightsDownNow()
                val revealHold = holdForReveal()
                cardShown?.join()
                // A switch reported after the card's time ran out, while the reveal still
                // waited, blanked part of that time.
                if (cardHold == "shown" && revealHold != HoldReason.KeyPressed) awaitCardShown()
                pictureUpNow()
                if (expanding) launch { expandCard() }
                delay(FlickMotion.REVEAL_VEIL_LAG_MS)
                val lift: FiniteAnimationSpec<Float> = if (latestTurnedFilm && SNAP_REVEAL_ON_TURNED_FILM) {
                    FlickMotion.orSnap(true, FlickMotion.filmReveal())
                } else {
                    spec(FlickMotion.filmReveal())
                }
                density.animateTo(0f, lift)
                pictureSettledNow()
            }

            HouseMoveKind.VeilIn -> {
                val clearing = launch { clearExpanded() }
                launch {
                    val seed = density.value
                    val threshold = seed + (VEIL_DENSITY - seed) * FlickMotion.CARD_AFTER_DIM_FRACTION
                    snapshotFlow { density.value >= threshold }.first { it }
                    clearing.join()
                    enterCard(FlickMotion.crossDissolve(), rising = true)
                }
                density.animateTo(VEIL_DENSITY, spec(FlickMotion.filmReveal()))
            }

            HouseMoveKind.LightsUpRoom -> {
                launch { exitCard(fastSpec) }
                hold(restPending())
                aperture.animateTo(1f, spec(FlickMotion.pictureUp()))
            }

            HouseMoveKind.LightsUpFault -> {
                launch { exitCard(fastSpec) }
                hold(restPending())
                density.animateTo(0f, spec(FlickMotion.crossDissolve()))
            }

            HouseMoveKind.Rest -> {
                // A card interrupted mid-exit on the way here would otherwise stay up.
                launch { exitCard(fastSpec) }
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

    // Liveness, not progress: the handshake sits in one stage for as long as the TV
    // takes to answer, so a card holding still would read as a hang. It stops at the
    // first frame, so no loop runs while the film decodes.
    val bobbing = !reduced && stage == HouseStage.Handshake && card.settledIn
    LaunchedEffect(bobbing) {
        if (bobbing) card.bob.animateTo(card.bob.value + 1f, FlickMotion.flickCardBob())
    }

    Box(modifier.fillMaxSize()) {
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

        if (stage == HouseStage.Handshake || card.held) {
            val live = stage == HouseStage.Handshake
            Box(
                Modifier.fillMaxSize().testTag("house-card"),
                contentAlignment = Alignment.Center,
            ) {
                // No panel behind the copy: the 82 % veil alone holds OnSurface at about
                // 13:1 and OnSurfaceDim at about 6:1 even over a white frame.
                Column(
                    modifier = Modifier
                        .width(HANDSHAKE_CARD_WIDTH)
                        .onSizeChanged { card.columnHeight = it.height }
                        .then(if (live) Modifier else Modifier.clearAndSetSemantics { }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // The Flick card's seat. The card is drawn by the layer below, because
                    // it travels from off screen and ends covering all of it.
                    Spacer(Modifier.size(FLICK_CARD_WIDTH, FLICK_CARD_HEIGHT))
                    Spacer(Modifier.height(FlickSpace.Lg))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (card.settledIn && live) {
                                    Modifier
                                } else {
                                    Modifier.graphicsLayer {
                                        alpha = card.alpha.value * card.copy.value
                                        translationY = (card.rise.value + 0.5f * card.sink.value) *
                                            FlickMotion.TvRiseCard.toPx()
                                        val sc = lerp(1f, 0.97f, card.rise.value)
                                        scaleX = sc
                                        scaleY = sc
                                        compositingStrategy = CompositingStrategy.ModulateAlpha
                                    }
                                },
                            ),
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
                }
                FlickCardLayer(card)
            }
        }
    }
}

/**
 * The Flick card, its glow, its play mark and the speed bars, drawn from [card]'s
 * animatables in the draw phase on a layer of their own, so the bob repaints this
 * layer alone and recomposes nothing. Every alpha is per draw op: no offscreen layer,
 * even when the card covers the screen.
 */
@Composable
private fun FlickCardLayer(card: HouseCard) {
    Spacer(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .drawWithCache {
                val cardW = FLICK_CARD_WIDTH.toPx()
                val cardH = FLICK_CARD_HEIGHT.toPx()
                val radius = FLICK_CARD_RADIUS.toPx()
                val expandedRadius = FLICK_CARD_EXPANDED_RADIUS.toPx()
                val riseFrom = FlickMotion.FlickCardRiseFrom.toPx()
                val overshoot = -FlickMotion.FlickCardOvershoot.toPx()
                val bobReach = FlickMotion.FlickCardBob.toPx()
                val glowRadius = FLICK_CARD_GLOW_RADIUS.toPx()
                val glow = Brush.radialGradient(
                    colors = listOf(
                        FlickColor.Primary.copy(alpha = FLICK_CARD_GLOW_ALPHA),
                        FlickColor.Primary.copy(alpha = 0f),
                    ),
                    center = Offset.Zero,
                    radius = glowRadius,
                )
                val unit = FLICK_MARK_UNIT.toPx()
                val mark = android.graphics.Path().apply {
                    moveTo((28f - MARK_CENTROID_X) * unit, (15f - MARK_CENTROID_Y) * unit)
                    lineTo((56f - MARK_CENTROID_X) * unit, (32f - MARK_CENTROID_Y) * unit)
                    lineTo((28f - MARK_CENTROID_X) * unit, (49f - MARK_CENTROID_Y) * unit)
                    close()
                }
                // Fill and round-joined stroke as one geometry, as the brand mark draws
                // them: two separate ops would double-blend along the edge while it fades.
                val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL_AND_STROKE
                    strokeJoin = Paint.Join.ROUND
                    strokeWidth = 9f * unit
                    color = android.graphics.Color.WHITE
                }
                val barW = FloatArray(SPEED_BAR_WIDTHS.size) { SPEED_BAR_WIDTHS[it].toPx() }
                val barH = FloatArray(SPEED_BAR_HEIGHTS.size) { SPEED_BAR_HEIGHTS[it].toPx() }
                val barPitch = SPEED_BAR_PITCH.toPx()
                val barGap = SPEED_BAR_GAP.toPx()
                val barDrift = SPEED_BAR_DRIFT.toPx()
                onDrawBehind {
                    val a = card.alpha.value * card.cover.value
                    if (a <= 0f) return@onDrawBehind
                    val s = card.arrive.value
                    val firstLeg = s <= ARRIVE_OVERSHOOT
                    val t = if (firstLeg) s / ARRIVE_OVERSHOOT else (s - ARRIVE_OVERSHOOT) / (ARRIVE_SEATED - ARRIVE_OVERSHOOT)
                    val dy = if (firstLeg) lerp(riseFrom, overshoot, t) else lerp(overshoot, 0f, t)
                    val tilt = if (firstLeg) {
                        lerp(FlickMotion.FLICK_CARD_TILT_FROM, FlickMotion.FLICK_CARD_TILT_OVERSHOOT, t)
                    } else {
                        lerp(FlickMotion.FLICK_CARD_TILT_OVERSHOOT, FlickMotion.FLICK_CARD_TILT_SEAT, t)
                    }
                    val sc = if (firstLeg) {
                        lerp(FlickMotion.FLICK_CARD_SCALE_FROM, FlickMotion.FLICK_CARD_SCALE_OVERSHOOT, t)
                    } else {
                        lerp(FlickMotion.FLICK_CARD_SCALE_OVERSHOOT, 1f, t)
                    }
                    val wave = sin(card.bob.value * TWO_PI)
                    val e = card.expand.value
                    val still = 1f - e
                    // The seat is the top of the centred column, whose height the copy sets.
                    val seatY = (size.height - card.columnHeight) / 2f + cardH / 2f
                    val cx = size.width / 2f
                    val cy = lerp(seatY + dy + wave * bobReach, size.height / 2f, e)
                    val w = lerp(cardW * sc, size.width, e)
                    val h = lerp(cardH * sc, size.height, e)
                    val r = lerp(radius * sc, expandedRadius, e)
                    val rot = lerp(tilt + wave * FlickMotion.FLICK_CARD_BOB_TILT, 0f, e)
                    withTransform({
                        translate(cx, cy)
                        rotate(rot, Offset.Zero)
                    }) {
                        if (still > 0f) {
                            withTransform({ scale(FLICK_CARD_GLOW_ASPECT * sc, sc, Offset.Zero) }) {
                                drawCircle(glow, radius = glowRadius, center = Offset.Zero, alpha = a * still)
                            }
                        }
                        drawRoundRect(
                            color = FlickColor.Primary,
                            topLeft = Offset(-w / 2f, -h / 2f),
                            size = Size(w, h),
                            cornerRadius = CornerRadius(r),
                            alpha = a,
                        )
                        val m = a * card.mark.value
                        if (m > 0f) {
                            withTransform({ scale(sc, sc, Offset.Zero) }) {
                                drawIntoCanvas { c ->
                                    markPaint.alpha = (m * 255f).roundToInt()
                                    c.nativeCanvas.drawPath(mark, markPaint)
                                }
                            }
                        }
                    }
                    val barsAlpha = a * still * speedBarAlpha(card.bars.value)
                    if (barsAlpha > 0f) {
                        val b = card.bars.value
                        val top = cy + h / 2f + barGap
                        for (i in barW.indices) {
                            // The top retracts and the foot drifts: the trail dissolves downward.
                            val y0 = top + b * barH[i]
                            val y1 = top + barH[i] + b * barDrift
                            drawRoundRect(
                                color = FlickColor.Spark,
                                topLeft = Offset(cx + (i - 1) * barPitch - barW[i] / 2f, y0),
                                size = Size(barW[i], y1 - y0),
                                cornerRadius = CornerRadius(barW[i] / 2f),
                                alpha = barsAlpha,
                            )
                        }
                    }
                }
            },
    )
}
