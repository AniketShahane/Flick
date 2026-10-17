package com.flick.sender.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Where the synopsis intertitle is: [progress] 0 is put away, 1 is fully in. The poster's drag,
 * the open story's own drag and predictive back all write it; everything that moves with it
 * reads it in a layer or a draw, so a frame of travel recomposes nothing.
 *
 * [open] is where it was last sent, not where it is: it changes once per decision, which is
 * what back handling, semantics and the idle nudge may recompose on.
 */
@Stable
internal class SynopsisReveal(
    private val scope: CoroutineScope,
    private val settleSpec: () -> FiniteAnimationSpec<Float>,
) {
    val progress = Animatable(0f)

    var open by mutableStateOf(false)
        private set

    /** The finger's travel for the whole reveal in px, set by the page from its window. */
    var widthPx by mutableFloatStateOf(1f)

    /**
     * The poster's seat and the page's own origin, in root coordinates, so the poster can be
     * flown onto the strip from where it rests. Written on layout, read in layers and draws.
     */
    var posterSeat by mutableStateOf(Rect.Zero)
    var pageOrigin by mutableStateOf(Offset.Zero)

    /**
     * Set by the intertitle while it draws the poster's flight itself, over the strip and the
     * card; the page's poster then hides its face rather than fly under them.
     */
    var posterCopyLive by mutableStateOf(false)

    private var dragFrom = 0f
    private var dragging by mutableStateOf(false)
    private var settle: Job? = null

    /** Anything on screen is in motion, or the story is anywhere but away. */
    val engaged by derivedStateOf { open || dragging || progress.value > 0f }

    /**
     * Whether the story takes touches: open, or under a finger. Not merely [engaged] — a story
     * springing home must let a touch through to the poster at once, or a throw made right
     * after closing is swallowed for the length of the spring.
     */
    val catching by derivedStateOf { open || dragging }

    /** Set once the first reveal starts; the story is not composed before anyone asks for it. */
    var everShown by mutableStateOf(false)
        private set

    val moving: Boolean get() = dragging || progress.isRunning

    /** A finger takes the story wherever it is, mid-settle included. */
    fun beginDrag() {
        settle?.cancel()
        dragging = true
        everShown = true
        dragFrom = progress.value
    }

    /** [travelPx] from where the finger went down, positive toward open (leftward). */
    fun dragTo(travelPx: Float) {
        val next = SynopsisPolicy.progress(dragFrom, travelPx, widthPx)
        scope.launch { progress.snapTo(next) }
    }

    fun dragBy(deltaPx: Float) {
        dragFrom = SynopsisPolicy.progress(dragFrom, deltaPx, widthPx)
        val next = dragFrom
        scope.launch { progress.snapTo(next) }
    }

    /** Settles where the release says; [velocityPxPerS] positive is toward open (leftward). */
    fun release(velocityPxPerS: Float, density: Float, opening: Boolean) {
        dragging = false
        val toOpen = SynopsisPolicy.settlesOpen(progress.value, velocityPxPerS / density, opening)
        settleTo(toOpen, velocityPxPerS / widthPx)
    }

    fun show() = settleTo(true, 0f)

    fun hide() = settleTo(false, 0f)

    /** Predictive back in flight: the story leans toward home without committing. */
    fun holdForBack(backProgress: Float) {
        settle?.cancel()
        dragging = true
        scope.launch { progress.snapTo(SynopsisPolicy.backHold(backProgress)) }
    }

    private fun settleTo(toOpen: Boolean, velocity: Float) {
        dragging = false
        open = toOpen
        if (toOpen) everShown = true
        settle?.cancel()
        settle = scope.launch {
            progress.animateTo(if (toOpen) 1f else 0f, settleSpec(), initialVelocity = velocity)
        }
    }
}

@Composable
internal fun rememberSynopsisReveal(key: Any, reduceMotion: Boolean): SynopsisReveal {
    val scope = rememberCoroutineScope()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val spec by rememberUpdatedState(if (reduceMotion) tween(SynopsisMotion.CrossfadeMs) else spatial)
    return remember(key) { SynopsisReveal(scope) { spec } }
}

/**
 * The page's chrome stepping back as the story comes: fading out by 1 / 1.6 of the way and
 * drifting left, read in the layer. Under reduce motion it only fades, in step with the story.
 */
internal fun Modifier.synopsisRecede(reveal: SynopsisReveal?, reduceMotion: Boolean): Modifier =
    if (reveal == null) {
        this
    } else {
        graphicsLayer {
            val p = reveal.progress.value.coerceIn(0f, 1f)
            if (reduceMotion) {
                alpha = 1f - p
            } else {
                alpha = SynopsisPolicy.chromeAlpha(p)
                translationX = -SynopsisPolicy.CHROME_DRIFT_DP.dp.toPx() * p
            }
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }
    }

/**
 * The poster's flight onto the strip at travel [p]: its sideways shift in px, so its centre
 * lands on the strip's centre line; its scale, so it lands a frame wide; and its fade, once it
 * is there. The page's poster and the story's copy of it fly on these same three numbers.
 * Under reduce motion it does not travel: it fades where it is, in step with the story.
 */
internal fun SynopsisReveal.posterFlightShiftPx(p: Float, density: Density, reduceMotion: Boolean): Float {
    val seat = posterSeat
    if (reduceMotion || p <= 0f || seat.width <= 0f) return 0f
    val target = pageOrigin.x + with(density) { SynopsisPolicy.STRIP_CENTER_DP.dp.toPx() }
    return (target - seat.center.x) * SynopsisPolicy.easeInOutCubic(p)
}

internal fun SynopsisReveal.posterFlightScale(p: Float, density: Density, reduceMotion: Boolean): Float {
    val seat = posterSeat
    if (reduceMotion || p <= 0f || seat.width <= 0f) return 1f
    val end = with(density) { SynopsisPolicy.FRAME_WIDTH_DP.dp.toPx() } / seat.width
    return SynopsisPolicy.posterScale(p, end)
}

internal fun posterFlightAlpha(p: Float, reduceMotion: Boolean): Float =
    if (reduceMotion) 1f - p.coerceIn(0f, 1f) else SynopsisPolicy.posterAlpha(p)

internal object SynopsisMotion {
    /** Reduce motion: the story crossfades in place on this instead of travelling. */
    const val CrossfadeMs = 180
}
