package com.flick.receiver.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickIcons
import com.flick.receiver.ui.theme.FlickMotion
import com.flick.receiver.ui.theme.SkipGlyph

/**
 * A circular transport button with the standard TV focus treatment (scale 1.08 +
 * cyan border + glow). [primary] paints the Spark-gradient play circle; secondary
 * buttons are outlined ghosts (back-10 / fwd-10).
 */
@Composable
private fun TransportButton(
    onClick: () -> Unit,
    diameter: Dp,
    primary: Boolean,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.08f else 1f,
        animationSpec = FlickMotion.focusPop(),
        label = "transportScale",
    )
    val borderColor = when {
        focused -> FlickColor.Link
        primary -> Color.Transparent
        else -> FlickColor.OnSurface.copy(alpha = 0.25f)
    }
    Box(
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .size(diameter)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(
                elevation = if (focused) 18.dp else 0.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = FlickColor.Link,
                spotColor = FlickColor.Link,
            )
            .clip(CircleShape)
            .then(if (primary) Modifier.background(FlickColor.SparkGradient) else Modifier)
            .border(if (focused) 2.dp else 1.5.dp, borderColor, CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Play/pause that MORPHS rather than hard-swaps (§7): a synchronized crossfade +
 * scale on [FlickMotion.FlickSettle]. Shows the pause bars while [playing], the
 * play triangle while paused.
 */
@Composable
fun PlayPauseGlyph(
    playing: Boolean,
    size: Dp,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val p by animateFloatAsState(
        targetValue = if (playing) 1f else 0f,
        animationSpec = FlickMotion.flickSettle(),
        label = "playPauseMorph",
    )
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = FlickIcons.Play,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(size)
                .graphicsLayer { alpha = 1f - p; scaleX = 1f - 0.2f * p; scaleY = 1f - 0.2f * p },
        )
        Icon(
            imageVector = FlickIcons.Pause,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(size)
                .graphicsLayer { alpha = p; scaleX = 0.8f + 0.2f * p; scaleY = 0.8f + 0.2f * p },
        )
    }
}

/**
 * The transport cluster (§7): back-10 / play-pause / fwd-10. The primary play
 * circle is a 64dp Spark gradient; skip buttons are 44dp outlined ghosts. On
 * entry, focus lands on play via [playFocusRequester].
 */
@Composable
fun TransportCluster(
    playing: Boolean,
    onBack10: () -> Unit,
    onPlayPause: () -> Unit,
    onForward10: () -> Unit,
    modifier: Modifier = Modifier,
    playFocusRequester: FocusRequester? = null,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        TransportButton(onClick = onBack10, diameter = 44.dp, primary = false) {
            SkipGlyph(forward = false, size = 24.dp, tint = FlickColor.OnSurface)
        }
        TransportButton(
            onClick = onPlayPause,
            diameter = 64.dp,
            primary = true,
            focusRequester = playFocusRequester,
        ) {
            PlayPauseGlyph(playing = playing, size = 30.dp, tint = FlickColor.Canvas)
        }
        TransportButton(onClick = onForward10, diameter = 44.dp, primary = false) {
            SkipGlyph(forward = true, size = 24.dp, tint = FlickColor.OnSurface)
        }
    }
}

/**
 * Stepped-cell volume (§7) — D-pad friendly, engage-to-adjust so it is never a
 * focus trap. Focused-but-idle passes Left/Right through to the focus system so
 * the D-pad can leave the control. DPAD-center toggles an "engaged" mode (Spark
 * emphasis); only while engaged do Left/Right step [onChange] by 10% and stay
 * captured. Center/Back disengages; losing focus disengages. Filled cells
 * reflect [level] (0..1).
 */
@Composable
fun VolumeCells(
    level: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    cells: Int = 10,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var engaged by remember { mutableStateOf(false) }
    // Never leave the control stuck in adjust mode after focus moves away.
    LaunchedEffect(focused) { if (!focused) engaged = false }
    val filled = (level.coerceIn(0f, 1f) * cells).toInt()
    val borderColor = when {
        engaged -> FlickColor.Spark
        focused -> FlickColor.Link
        else -> FlickColor.OutlineHairline
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (focused || engaged) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        engaged = !engaged; true
                    }
                    Key.Back -> {
                        // Only claim Back to exit adjust mode; otherwise let it bubble.
                        if (engaged) { engaged = false; true } else false
                    }
                    Key.DirectionLeft ->
                        if (engaged) { onChange((level - 0.1f).coerceIn(0f, 1f)); true } else false
                    Key.DirectionRight ->
                        if (engaged) { onChange((level + 0.1f).coerceIn(0f, 1f)); true } else false
                    else -> false
                }
            }
            .clickable(interactionSource = interaction, indication = null, onClick = { engaged = !engaged }),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = FlickIcons.Volume,
            contentDescription = null,
            tint = if (engaged) FlickColor.Spark else FlickColor.OnSurface,
            modifier = Modifier.size(28.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(cells) { i ->
                Box(
                    modifier = Modifier
                        .size(width = 6.dp, height = 20.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            when {
                                i < filled && engaged -> FlickColor.Spark
                                i < filled -> FlickColor.OnSurface
                                else -> FlickColor.OnSurface.copy(alpha = 0.22f)
                            },
                        ),
                )
            }
        }
    }
}
