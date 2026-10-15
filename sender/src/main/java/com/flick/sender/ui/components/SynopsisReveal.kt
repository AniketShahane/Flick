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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Where the synopsis panel is: [progress] 0 is put away, 1 is fully in. The poster's drag, the
 * panel's own drag and predictive back all write it; everything that moves with it reads it in
 * a layer, so a frame of travel recomposes nothing.
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

    /** The panel's travel in px, set by the page from its window before the panel exists. */
    var widthPx by mutableFloatStateOf(1f)

    private var dragFrom = 0f
    private var dragging by mutableStateOf(false)
    private var settle: Job? = null

    /** Anything on screen is in motion, or the panel is anywhere but away. */
    val engaged by derivedStateOf { open || dragging || progress.value > 0f }

    /**
     * Whether the panel and the page beside it take touches: open, or under a finger. Not
     * merely [engaged] — a panel springing home must let a touch through to the poster at
     * once, or a throw made right after closing is swallowed for the length of the spring.
     */
    val catching by derivedStateOf { open || dragging }

    /** Set once the first reveal starts; the panel is not composed before anyone asks for it. */
    var everShown by mutableStateOf(false)
        private set

    val moving: Boolean get() = dragging || progress.isRunning

    /** A finger takes the panel wherever it is, mid-settle included. */
    fun beginDrag() {
        settle?.cancel()
        dragging = true
        everShown = true
        dragFrom = progress.value
    }

    /** [travelPx] from where the finger went down, positive rightward. */
    fun dragTo(travelPx: Float) {
        val next = SynopsisPolicy.progress(dragFrom, travelPx, widthPx)
        scope.launch { progress.snapTo(next) }
    }

    fun dragBy(deltaPx: Float) {
        dragFrom = SynopsisPolicy.progress(dragFrom, deltaPx, widthPx)
        val next = dragFrom
        scope.launch { progress.snapTo(next) }
    }

    /** Settles where the release says; [velocityPxPerS] positive is toward open. */
    fun release(velocityPxPerS: Float, density: Float, opening: Boolean) {
        dragging = false
        val toOpen = SynopsisPolicy.settlesOpen(progress.value, velocityPxPerS / density, opening)
        settleTo(toOpen, velocityPxPerS / widthPx)
    }

    fun show() = settleTo(true, 0f)

    fun hide() = settleTo(false, 0f)

    /** Predictive back in flight: the panel leans toward home without committing. */
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

/** The page behind the panel drifting aside as it comes, read in the layer. */
internal fun Modifier.synopsisDrift(reveal: SynopsisReveal?, reduceMotion: Boolean): Modifier =
    if (reveal == null || reduceMotion) {
        this
    } else {
        graphicsLayer { translationX = SynopsisPolicy.PAGE_DRIFT_DP.dp.toPx() * reveal.progress.value }
    }

internal object SynopsisMotion {
    /** Reduce motion: the panel crossfades in place on this instead of travelling. */
    const val CrossfadeMs = 180
}
