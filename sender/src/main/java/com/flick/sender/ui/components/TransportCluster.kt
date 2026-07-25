package com.flick.sender.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
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
import com.flick.sender.ui.theme.pressScale
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
    Canvas(modifier) {
        // Read in the draw scope, so the morph repaints without recomposing the caller.
        val f = morph.value
        val u = size.minDimension / 24f
        fun blend(ax: Float, ay: Float, bx: Float, by: Float) =
            Offset(lerp(ax, bx, f) * u, lerp(ay, by, f) * u)

        // Left half: play quad → left pause bar.
        val left = Path().apply {
            val a = blend(8f, 5f, 7f, 5f)
            val b = blend(13.5f, 8.5f, 10f, 5f)
            val c = blend(13.5f, 15.5f, 10f, 19f)
            val d = blend(8f, 19f, 7f, 19f)
            moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close()
        }
        // Right half: play tip (degenerate quad) → right pause bar.
        val right = Path().apply {
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

/** Primary play control — the amber FAB carrying the morph. */
@Composable
fun PrimaryPlayButton(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = FabSize,
    accessibilityLabel: String? = null,
    accessibilityState: String? = null,
) {
    val shape = RoundedCornerShape(FlickCorners.fab)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(size)
            .pressScale(interaction, Motion.PressFab)
            .shadow(16.dp, shape, clip = false, ambientColor = FabShadow, spotColor = FabShadow)
            .pressMorph(interaction, restRadius = FlickCorners.fab, pressedRadius = 20.dp)
            .background(FlickGradients.fab)
            .clickable(
                interactionSource = interaction,
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
) {
    val colors = LocalFlickColors.current
    val glyph: ImageVector = if (forward) FlickIcons.Fwd10 else FlickIcons.Back10
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(size)
            .pressScale(interaction, Motion.PressSeek)
            .pressMorph(interaction, restRadius = FlickCorners.seekBtn, pressedRadius = 16.dp)
            .background(colors.fillControl)
            .clickable(
                interactionSource = interaction,
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

/** back-10 · play/pause · fwd-10. */
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
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeekButton(forward = false, onClick = onBack10, tint = tint, accessibilityLabel = back10Label)
        PrimaryPlayButton(
            playing = playing,
            onClick = onPlayPause,
            accessibilityLabel = playPauseLabel,
            accessibilityState = playPauseState,
        )
        SeekButton(forward = true, onClick = onFwd10, tint = tint, accessibilityLabel = forward10Label)
    }
}

private val FabSize = 76.dp
private val SeekSize = 60.dp
