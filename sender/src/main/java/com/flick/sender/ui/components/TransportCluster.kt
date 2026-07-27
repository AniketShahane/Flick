@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.flick.sender.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.graphics.shapes.Morph
import com.flick.sender.R
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickGradients
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.FabShadow
import com.flick.sender.ui.theme.Ink
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.Motion
import com.flick.sender.ui.theme.flickRipple
import com.flick.sender.ui.theme.pressMorph
import com.flick.sender.ui.theme.rememberReduceMotion

/**
 * Play/pause that **morphs** (triangle ↔ bars) — never a hard swap. The two
 * half-shapes interpolate corner-for-corner between the play triangle and the two
 * pause bars as [playing] flips.
 */
@Composable
fun PlayPauseMorph(
    playing: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val morph = animateFloatAsState(
        targetValue = if (playing) 1f else 0f,
        animationSpec = Motion.orSnap(reduceMotion, MaterialTheme.motionScheme.defaultSpatialSpec<Float>()),
        label = "morph",
    )
    // Both halves are rebuilt on every frame of the morph, and this is the control the
    // remote gets hammered on, so the paths are buffers rather than per-frame garbage.
    val left = remember { Path() }
    val right = remember { Path() }
    Canvas(modifier) {
        // Read in the draw scope, so the morph repaints without recomposing the caller.
        val f = morph.value
        val u = size.minDimension / 24f
        fun blend(ax: Float, ay: Float, bx: Float, by: Float) =
            Offset(lerp(ax, bx, f) * u, lerp(ay, by, f) * u)

        // Left half: play quad → left pause bar.
        left.rewind()
        with(left) {
            val a = blend(8f, 5f, 7f, 5f)
            val b = blend(13.5f, 8.5f, 10f, 5f)
            val c = blend(13.5f, 15.5f, 10f, 19f)
            val d = blend(8f, 19f, 7f, 19f)
            moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close()
        }
        // Right half: play tip (degenerate quad) → right pause bar.
        right.rewind()
        with(right) {
            val a = blend(13.5f, 8.5f, 14f, 5f)
            val b = blend(19f, 12f, 17f, 5f)
            val c = blend(19f, 12f, 17f, 19f)
            val d = blend(13.5f, 15.5f, 14f, 19f)
            moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close()
        }
        drawPath(left, color)
        drawPath(right, color)
    }
}

/**
 * Primary play control — the amber FAB carrying the morph. The *container* morphs too:
 * a circle while paused, a nine-lobed cookie while the TV is playing, so the button's
 * own outline reports the state the glyph inside it is reporting.
 */
@Composable
fun PrimaryPlayButton(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = FabSize,
    accessibilityLabel: String? = null,
    accessibilityState: String? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val reduceMotion = rememberReduceMotion()
    val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
    val progress = animateFloatAsState(
        targetValue = if (playing) 1f else 0f,
        animationSpec = Motion.orSnap(reduceMotion, MaterialTheme.motionScheme.defaultSpatialSpec<Float>()),
        label = "fab container morph",
    )
    // One buffer per silhouette: the two glow rings and the clip outline are all alive
    // within a single frame, so they cannot share a path between them.
    val glowOuter = remember(morph) { MorphSilhouette(morph) }
    val glowInner = remember(morph) { MorphSilhouette(morph) }
    val clipOutline = remember(morph) { MorphSilhouette(morph) }
    Box(
        modifier = modifier
            .size(size)
            .drawBehind { drawFabBloom(glowOuter, glowInner, progress.value) }
            // Read inside the layer block, so the outline is rebuilt in the draw phase
            // and the caller never recomposes for it.
            .graphicsLayer {
                clip = true
                shape = MorphShape(clipOutline, progress.value)
            }
            .background(FlickGradients.fab)
            .clickable(
                interactionSource = interactionSource,
                // The amber fill never inverts, so the ripple takes the glyph's ink.
                indication = flickRipple(Ink),
                // No haptic here: PlaybackSession already pulses the vibrator when it
                // sends the play/pause command, and the two would double every tap.
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                role = Role.Button
                accessibilityLabel?.let { contentDescription = it }
                accessibilityState?.let { stateDescription = it }
            },
        contentAlignment = Alignment.Center,
    ) {
        // The amber fill is palette-independent, so its ink is the fixed navy rather
        // than a surface role that would invert on the light theme.
        PlayPauseMorph(
            playing = playing,
            color = Ink,
            modifier = Modifier.size(size * 0.53f),
        )
    }
}

/** ±10s skip — the arc glyph with the seek amount overlaid (the vector can't carry text). */
@Composable
fun SeekButton(
    forward: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = SeekSize,
    tint: Color = LocalFlickColors.current.onSurface,
    accessibilityLabel: String? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val colors = LocalFlickColors.current
    val glyph: ImageVector = if (forward) FlickIcons.Fwd10 else FlickIcons.Back10
    Box(
        modifier = modifier
            .size(size)
            .pressMorph(interactionSource, restRadius = FlickCorners.seekBtn, pressedRadius = 16.dp)
            .background(colors.fillControl)
            .clickable(
                interactionSource = interactionSource,
                indication = flickRipple(colors.onSurface),
                // No haptic here: PlaybackSession already pulses the vibrator when it
                // sends the seek, and this is the control that gets hammered.
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                role = Role.Button
                accessibilityLabel?.let { contentDescription = it }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = glyph, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
        Text(
            text = stringResource(R.string.np_seek_seconds),
            style = FlickText.monoBadge.copy(color = tint),
            modifier = Modifier.offset(y = size * 0.10f),
        )
    }
}

/**
 * back-10 · play/pause · fwd-10, laid out as one group: pressing a key widens it and
 * squeezes its neighbours aside, so the three controls behave like a single physical
 * strip rather than three separate targets. The squeeze *is* the press response —
 * nothing here also scales, or one touch would be answered twice.
 */
@Composable
fun TransportCluster(
    playing: Boolean,
    onBack10: () -> Unit,
    onPlayPause: () -> Unit,
    onFwd10: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalFlickColors.current.onSurface,
    back10Label: String? = null,
    playPauseLabel: String? = null,
    playPauseState: String? = null,
    forward10Label: String? = null,
) {
    val backSource = remember { MutableInteractionSource() }
    val playSource = remember { MutableInteractionSource() }
    val forwardSource = remember { MutableInteractionSource() }
    ButtonGroup(
        // Three fixed keys that always fit the remote's width; there is nothing to
        // overflow into a menu, and a transport control the user cannot see is a bug.
        overflowIndicator = {},
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        customItem(
            buttonGroupContent = {
                SeekButton(
                    forward = false,
                    onClick = onBack10,
                    modifier = Modifier.animateWidth(backSource),
                    tint = tint,
                    accessibilityLabel = back10Label,
                    interactionSource = backSource,
                )
            },
            menuContent = {},
        )
        customItem(
            buttonGroupContent = {
                PrimaryPlayButton(
                    playing = playing,
                    onClick = onPlayPause,
                    modifier = Modifier.animateWidth(playSource),
                    accessibilityLabel = playPauseLabel,
                    accessibilityState = playPauseState,
                    interactionSource = playSource,
                )
            },
            menuContent = {},
        )
        customItem(
            buttonGroupContent = {
                SeekButton(
                    forward = true,
                    onClick = onFwd10,
                    modifier = Modifier.animateWidth(forwardSource),
                    tint = tint,
                    accessibilityLabel = forward10Label,
                    interactionSource = forwardSource,
                )
            },
            menuContent = {},
        )
    }
}

/**
 * One frame of a [Morph], rebuilt into a path and matrix it owns for the lifetime of the
 * control. Material's own polygon shapes are normalised to a unit box, so the path is
 * scaled to the drawn size and re-centred on it.
 *
 * A returned path is only valid until the next [outlineOf] on the same instance, so a
 * silhouette that has to survive alongside another one needs its own.
 */
private class MorphSilhouette(private val morph: Morph) {
    private val path = Path()
    private val matrix = Matrix()

    fun outlineOf(progress: Float, size: Size, spread: Float, dy: Float): Path {
        // Progress is not clamped to 0..1: the spatial spring overshoots and the lobes
        // are a plain interpolation of matched cubics, so the container bounces with the
        // glyph in it rather than parking at the end state while the glyph is still
        // travelling.
        morph.toPath(progress, path)
        matrix.reset()
        matrix.scale(size.width * spread, size.height * spread)
        path.transform(matrix)
        val bounds = path.getBounds()
        path.translate(Offset(size.center.x - bounds.center.x, size.center.y + dy - bounds.center.y))
        return path
    }
}

/** A [Shape] over one frame of a morph, backed by [silhouette]'s reused path. */
private class MorphShape(private val silhouette: MorphSilhouette, private val progress: Float) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = Outline.Generic(silhouette.outlineOf(progress, size, 1f, 0f))
}

/**
 * Compose renders a shadow from an elevation and a convex outline; a nine-lobed cookie
 * is neither, so the FAB's amber lift is drawn as two oversized copies of the very
 * silhouette that is morphing — the glow cannot fall out of step with the shape.
 */
private fun DrawScope.drawFabBloom(outer: MorphSilhouette, inner: MorphSilhouette, progress: Float) {
    val drop = 6.dp.toPx()
    drawPath(outer.outlineOf(progress, size, 1.16f, drop), FabShadow.copy(alpha = 0.22f))
    drawPath(inner.outlineOf(progress, size, 1.07f, drop * 0.6f), FabShadow.copy(alpha = 0.34f))
}

private val FabSize = 76.dp
private val SeekSize = 60.dp
