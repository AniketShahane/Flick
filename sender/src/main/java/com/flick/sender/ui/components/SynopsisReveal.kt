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
    private val progress = Animatable(0f)

    /**
     * Set by [shut] until the progress itself is home: everything reads the reveal as away from
     * that same frame, though the animation behind it can only stop on the next.
     */
    private var shutting by mutableStateOf(false)

    /** Where the story is, 0 away to 1 open (a spring may overshoot either end). */
    val value: Float get() = if (shutting) 0f else progress.value

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
    private var backFrom = 1f
    private var backWasOpen = true

    /** Anything on screen is in motion, or the story is anywhere but away. */
    val engaged by derivedStateOf { open || dragging || value > 0f }

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
        dragFrom = value
        shutting = false
        dragging = true
        everShown = true
    }

    /**
     * [travelPx] from where the finger went down, positive toward open (leftward). Ignored once
     * something else has settled the story mid-drag (the ✕ under a second finger, back): the
     * finger still down must not pull it back off its settle and strand it there.
     */
    fun dragTo(travelPx: Float) {
        if (!dragging) return
        val next = SynopsisPolicy.progress(dragFrom, travelPx, widthPx)
        scope.launch { progress.snapTo(next) }
    }

    fun dragBy(deltaPx: Float) {
        if (!dragging) return
        dragFrom = SynopsisPolicy.progress(dragFrom, deltaPx, widthPx)
        val next = dragFrom
        scope.launch { progress.snapTo(next) }
    }

    /** Settles where the release says; [velocityPxPerS] positive is toward open (leftward). */
    fun release(velocityPxPerS: Float, density: Float, opening: Boolean) {
        if (!dragging) return
        val toOpen = SynopsisPolicy.settlesOpen(value, velocityPxPerS / density, opening)
        settleTo(toOpen, velocityPxPerS / widthPx)
    }

    /** A drag that ended with no release (its node gone, the gesture cancelled) settles by where it is. */
    fun abandonDrag() {
        if (dragging) settleTo(value >= SynopsisPolicy.CLOSE_AT, 0f)
    }

    fun show() = settleTo(true, 0f)

    fun hide() = settleTo(false, 0f)

    /**
     * Put away at once, this frame: the page is leaving, or a throw is taking the poster
     * while the story springs home. No settle, nothing left composed in flight.
     */
    fun shut() {
        settle?.cancel()
        dragging = false
        open = false
        if (progress.value == 0f && !progress.isRunning) return
        shutting = true
        settle = scope.launch {
            progress.snapTo(0f)
            shutting = false
        }
    }

    /** Predictive back starting: it leans from wherever the story is, toward home. */
    fun beginBack() {
        settle?.cancel()
        backFrom = value.coerceIn(0f, 1f)
        backWasOpen = open
        dragging = true
    }

    /** Predictive back in flight: the story leans toward home without committing. */
    fun holdForBack(backProgress: Float) {
        if (!dragging) return
        scope.launch { progress.snapTo(SynopsisPolicy.backHold(backFrom, backProgress)) }
    }

    /** Predictive back cancelled: back to where it was going. */
    fun cancelBack() = settleTo(backWasOpen, 0f)

    private fun settleTo(toOpen: Boolean, velocity: Float) {
        dragging = false
        shutting = false
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
            val p = reveal.value.coerceIn(0f, 1f)
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
    // Physical left, whatever the layout direction: the story is drawn from the left edge.
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
