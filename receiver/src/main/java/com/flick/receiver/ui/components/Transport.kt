package com.flick.receiver.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickIcons
import com.flick.receiver.ui.theme.FlickMotion
import com.flick.receiver.ui.theme.FlickShape
import com.flick.receiver.ui.theme.rememberReducedMotion
import com.flick.receiver.ui.theme.sparkShadow

/** Back-10 / forward-10 square (spec §5.3 row 3). Above the 48 dp touch floor. */
internal val SecondaryTransportTargetSize = 52.dp

/** The play button — the single largest affordance in the transport. */
internal val PrimaryTransportTargetSize = 66.dp

internal val TransportGlyphSize = 26.dp
internal val PrimaryTransportGlyphSize = 35.dp

/** Gap between the three transport buttons. */
internal val TransportClusterGap = 16.dp

/**
 * A squared transport button (spec §5.3 row 3). [primary] paints the amber play
 * key — an opaque `Spark` fill, ink in `OnSpark`, an amber drop shadow, and a
 * WHITE focus ring because amber on amber vanishes. Secondary keys are the cool
 * translucent squares.
 */
@Composable
private fun TransportButton(
    onClick: () -> Unit,
    side: Dp,
    shape: Shape,
    primary: Boolean,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    contentDescription: String? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val reducedMotion = rememberReducedMotion()
    val ringVisible = focused && enabled
    val scale by animateFloatAsState(
        targetValue = if (ringVisible && !reducedMotion) FlickMotion.FOCUS_SCALE else 1f,
        animationSpec = if (reducedMotion) tween(durationMillis = 0) else FlickMotion.focusPop(),
        label = "transportScale",
    )
    Box(
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties { canFocus = enabled }
            .size(side)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.38f
            }
            .flickFocusRing(
                visible = ringVisible,
                shape = shape,
                ringColor = if (primary) FlickColor.FocusRingOnSpark else FlickColor.FocusRing,
            )
            .then(if (primary) Modifier.sparkShadow(shape = shape) else Modifier)
            .clip(shape)
            .background(if (primary) FlickColor.Spark else FlickColor.ControlFillStrong)
            .then(if (primary) Modifier else Modifier.border(1.dp, FlickColor.Outline, shape))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .then(
                if (enabled) {
                    Modifier.semantics(mergeDescendants = true) {
                        this.role = Role.Button
                        if (contentDescription != null) this.contentDescription = contentDescription
                    }
                } else {
                    Modifier.clearAndSetSemantics { }
                },
            ),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Play/pause that MORPHS rather than hard-swaps: a synchronized crossfade +
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
    val reducedMotion = rememberReducedMotion()
    val p by animateFloatAsState(
        targetValue = if (playing) 1f else 0f,
        animationSpec = if (reducedMotion) tween(durationMillis = 0) else FlickMotion.flickSettle(),
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
 * The transport cluster (spec §5.3 row 3): back-10 / play-pause / fwd-10. On
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
    back10ContentDescription: String? = null,
    playPauseContentDescription: String? = null,
    forward10ContentDescription: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TransportClusterGap),
    ) {
        TransportButton(
            onClick = onBack10,
            side = SecondaryTransportTargetSize,
            shape = FlickShape.Lg,
            primary = false,
            contentDescription = back10ContentDescription,
            enabled = enabled,
        ) {
            Icon(
                imageVector = FlickIcons.Replay10,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(TransportGlyphSize),
            )
        }
        TransportButton(
            onClick = onPlayPause,
            side = PrimaryTransportTargetSize,
            shape = FlickShape.Play,
            primary = true,
            focusRequester = playFocusRequester,
            contentDescription = playPauseContentDescription,
            enabled = enabled,
        ) {
            PlayPauseGlyph(
                playing = playing,
                size = PrimaryTransportGlyphSize,
                tint = FlickColor.OnSpark,
            )
        }
        TransportButton(
            onClick = onForward10,
            side = SecondaryTransportTargetSize,
            shape = FlickShape.Lg,
            primary = false,
            contentDescription = forward10ContentDescription,
            enabled = enabled,
        ) {
            Icon(
                imageVector = FlickIcons.Forward10,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(TransportGlyphSize),
            )
        }
    }
}

/**
 * Stepped-cell volume — D-pad friendly, engage-to-adjust so it is never a focus
 * trap. The design omits volume; the app keeps it, restyled onto the amber
 * transport vocabulary.
 *
 * Focused-but-idle passes Left/Right through to the focus system so the D-pad
 * can leave the control. DPAD-center toggles an "engaged" mode (amber emphasis);
 * only while engaged do Left/Right step [onChange] by 10% and stay captured.
 * Center/Back disengages; losing focus disengages. Filled cells reflect [level]
 * (0..1).
 */
@Composable
fun VolumeCells(
    level: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    cells: Int = 10,
    contentDescription: String? = null,
    stateDescription: String? = null,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val reducedMotion = rememberReducedMotion()
    var engaged by remember { mutableStateOf(false) }
    // Never leave the control stuck in adjust mode after focus moves away.
    LaunchedEffect(focused, enabled) { if (!focused || !enabled) engaged = false }
    val filled = (level.coerceIn(0f, 1f) * cells).toInt()
    val ringVisible = focused && enabled
    val shape = FlickShape.Lg
    val scale by animateFloatAsState(
        targetValue = if (ringVisible && !reducedMotion) FlickMotion.FOCUS_SCALE else 1f,
        animationSpec = if (reducedMotion) tween(durationMillis = 0) else FlickMotion.focusPop(),
        label = "volumeScale",
    )
    val fill = if (engaged && enabled) FlickColor.SelectedFill else FlickColor.ControlFillStrong
    val stroke = if (engaged && enabled) FlickColor.SelectedBorder else FlickColor.Outline

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = SecondaryTransportTargetSize)
            .focusProperties { canFocus = enabled }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.38f
            }
            .flickFocusRing(visible = ringVisible, shape = shape)
            .clip(shape)
            .background(fill)
            .border(1.dp, stroke, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .onKeyEvent { event ->
                if (!enabled) return@onKeyEvent false
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
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = { engaged = !engaged },
            )
            .then(
                if (enabled) {
                    Modifier.semantics(mergeDescendants = true) {
                        this.role = Role.Button
                        if (contentDescription != null) this.contentDescription = contentDescription
                        if (stateDescription != null) this.stateDescription = stateDescription
                    }
                } else {
                    Modifier.clearAndSetSemantics { }
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = FlickIcons.Volume,
            contentDescription = null,
            tint = if (engaged) FlickColor.Spark else Color.White,
            modifier = Modifier.size(24.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(cells) { i ->
                Box(
                    modifier = Modifier
                        .size(width = 6.dp, height = 20.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            when {
                                i < filled && engaged -> FlickColor.Spark
                                i < filled -> FlickColor.SparkLight
                                else -> FlickColor.TrackBase
                            },
                        ),
                )
            }
        }
    }
}
