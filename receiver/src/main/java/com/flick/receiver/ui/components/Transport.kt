package com.flick.receiver.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import androidx.compose.ui.util.lerp
import androidx.tv.material3.Icon
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickDimens
import com.flick.receiver.ui.theme.FlickIcons
import com.flick.receiver.ui.theme.FlickMotion
import com.flick.receiver.ui.theme.FlickShape
import com.flick.receiver.ui.theme.LocalReducedMotion
import com.flick.receiver.ui.theme.sparkShadow

/** Result of a volume control key-down, kept pure so held-key behavior is testable. */
internal enum class VolumeKeyAction { ToggleEngagement, ConsumeRepeat, Disengage, StepDown, StepUp, PassThrough }

internal fun volumeKeyAction(
    key: Key,
    repeatCount: Int,
    engaged: Boolean,
    enabled: Boolean,
): VolumeKeyAction = when {
    !enabled -> VolumeKeyAction.PassThrough
    key == Key.DirectionCenter || key == Key.Enter -> if (repeatCount == 0) {
        VolumeKeyAction.ToggleEngagement
    } else {
        VolumeKeyAction.ConsumeRepeat
    }
    key == Key.Back && engaged -> VolumeKeyAction.Disengage
    key == Key.DirectionLeft && engaged -> VolumeKeyAction.StepDown
    key == Key.DirectionRight && engaged -> VolumeKeyAction.StepUp
    else -> VolumeKeyAction.PassThrough
}

/**
 * Back-10 / forward-10 square (spec §5.3 row 3). Sits ON the 48 dp TV minimum
 * rather than below it: the re-size shrank the glyphs inside these keys, not the
 * keys themselves, because they are aimed at with a D-pad and not a fingertip.
 */
internal val SecondaryTransportTargetSize = 48.dp

/** The play button — the single largest affordance in the transport. */
internal val PrimaryTransportTargetSize = 56.dp

/** Each glyph keeps at least half of its key to read in. */
internal val TransportGlyphSize = 24.dp
internal val PrimaryTransportGlyphSize = 28.dp

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
    val pressed by interaction.collectIsPressedAsState()
    val reducedMotion = LocalReducedMotion.current
    val hosted = beaconHosted()
    val ringVisible = focused && enabled
    val ringColor = if (primary) FlickColor.FocusRingOnSpark else FlickColor.FocusRing
    // Held as State and read inside the layer / draw lambdas below: these keys sit
    // directly over the decoder, so a D-pad move may repaint them but may not
    // recompose them once a frame.
    val scale = animateFloatAsState(
        targetValue = when {
            reducedMotion -> 1f
            pressed && ringVisible -> 1.02f
            pressed -> FlickMotion.PRESS_SCALE
            ringVisible -> FlickMotion.FOCUS_SCALE
            else -> 1f
        },
        animationSpec = if (reducedMotion) snap() else FlickMotion.focusSpatial(),
        label = "transportFeedbackScale",
    )
    val ringPresence = animateFloatAsState(
        targetValue = if (ringVisible) 1f else 0f,
        animationSpec = if (reducedMotion) snap() else FlickMotion.stateEffects(),
        label = "transportRingPresence",
    )
    val fill by animateColorAsState(
        targetValue = when {
            primary -> FlickColor.Spark
            pressed -> FlickColor.ControlFill
            else -> FlickColor.ControlFillStrong
        },
        animationSpec = if (reducedMotion) snap() else FlickMotion.stateEffects(),
        label = "transportPressFill",
    )
    Box(
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties { canFocus = enabled }
            .size(side)
            .focusBeacon(shape, ringColor)
            .graphicsLayer {
                val lift = scale.value
                scaleX = lift
                scaleY = lift
                alpha = if (enabled) 1f else 0.38f
            }
            .flickFocusRing(
                visible = ringVisible && !hosted,
                shape = shape,
                ringColor = ringColor,
                progress = { ringPresence.value },
            )
            .then(if (primary) Modifier.sparkShadow(shape = shape) else Modifier)
            .clip(shape)
            .background(fill)
            .then(
                if (primary) Modifier
                else Modifier.border(FlickDimens.Hairline, FlickColor.Outline, shape),
            )
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
 * The play triangle and the pause bars as ONE outline, in [FlickIcons]' 24-unit
 * grid so the glyph sits on the same optical centre as the other transport keys.
 *
 * Both states are two quads with matching vertex order, which is what lets a
 * single path interpolate between them: the triangle is split down x = 14 into a
 * left quad and a degenerate right one whose two right vertices meet at the tip.
 * Quads are wound the same way, so the shared edge vanishes under non-zero fill.
 */
private val PlayQuads = floatArrayOf(
    9f, 6f, 14f, 9f, 14f, 15f, 9f, 18f,
    14f, 9f, 19f, 12f, 19f, 12f, 14f, 15f,
)

/** Bar extents chosen so the pause pair centres on 12 like the triangle's centroid. */
private val PauseQuads = floatArrayOf(
    7.6f, 5.8f, 11f, 5.8f, 11f, 18.2f, 7.6f, 18.2f,
    13f, 5.8f, 16.4f, 5.8f, 16.4f, 18.2f, 13f, 18.2f,
)

/**
 * Play/pause that MORPHS rather than hard-swaps: one filled path whose vertices
 * travel corner-for-corner between the triangle and the bars. Because the
 * progress is a spring, a viewer who toggles twice in half a second gets one
 * retargeted flight instead of two tweens fighting over the same glyph. Shows the
 * pause bars while [playing].
 *
 * The progress is deliberately NOT clamped to 0..1: the spatial spring settles
 * past its target and the quads are a plain interpolation, so the bars carry the
 * same overshoot the key under them does rather than parking early.
 */
@Composable
fun PlayPauseGlyph(
    playing: Boolean,
    size: Dp,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = LocalReducedMotion.current
    val morph = animateFloatAsState(
        targetValue = if (playing) 1f else 0f,
        animationSpec = if (reducedMotion) snap() else FlickMotion.flickSettleSpatial(),
        label = "playPauseMorph",
    )
    // This is the control the remote hammers, and the glyph is rebuilt on every
    // frame of the morph: the path is a buffer for the life of the button, and it
    // is rewound rather than reset so it keeps the storage it already sized.
    val path = remember { Path() }
    Canvas(modifier = modifier.size(size)) {
        val unit = this.size.minDimension / 24f
        // Read in the draw scope, so the morph repaints without recomposing the
        // transport row that owns it.
        val p = morph.value
        path.rewind()
        repeat(2) { quad ->
            val base = quad * 8
            repeat(4) { corner ->
                val i = base + corner * 2
                val x = lerp(PlayQuads[i], PauseQuads[i], p) * unit
                val y = lerp(PlayQuads[i + 1], PauseQuads[i + 1], p) * unit
                if (corner == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
        }
        drawPath(path, color = tint)
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
    onEngagementChanged: (Boolean) -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val reducedMotion = LocalReducedMotion.current
    val hosted = beaconHosted()
    var engaged by remember { mutableStateOf(false) }
    // Some Android-target Compose versions expose only the normalized key, not
    // the platform repeat count. Keep repeat ownership locally instead.
    var centerOrEnterHeld by remember { mutableStateOf(false) }
    // Never leave the control stuck in adjust mode after focus moves away.
    LaunchedEffect(focused, enabled) {
        if (!focused || !enabled) {
            engaged = false
            centerOrEnterHeld = false
        }
    }
    LaunchedEffect(engaged) { onEngagementChanged(engaged) }
    // Leaving composition while engaged strands the owner's `volumeEngaged` latch
    // true, which deadens the physical seek keys for the rest of the session. The
    // effect above cannot cover it: the whole control is gone before it can run.
    val latestEngagementChanged = rememberUpdatedState(onEngagementChanged)
    DisposableEffect(Unit) {
        onDispose { if (engaged) latestEngagementChanged.value(false) }
    }
    val filled = (level.coerceIn(0f, 1f) * cells).toInt()
    val ringVisible = focused && enabled
    val shape = FlickShape.Lg
    // Read inside the layer / draw lambdas below — see [TransportButton].
    val scale = animateFloatAsState(
        targetValue = when {
            reducedMotion -> 1f
            pressed && ringVisible -> 1.02f
            pressed -> FlickMotion.PRESS_SCALE
            ringVisible -> FlickMotion.FOCUS_SCALE
            else -> 1f
        },
        animationSpec = if (reducedMotion) snap() else FlickMotion.focusSpatial(),
        label = "volumeFeedbackScale",
    )
    val ringPresence = animateFloatAsState(
        targetValue = if (ringVisible) 1f else 0f,
        animationSpec = if (reducedMotion) snap() else FlickMotion.stateEffects(),
        label = "volumeRingPresence",
    )
    val fill by animateColorAsState(
        targetValue = when {
            engaged && enabled -> FlickColor.SelectedFill
            pressed -> FlickColor.ControlFill
            else -> FlickColor.ControlFillStrong
        },
        animationSpec = if (reducedMotion) snap() else FlickMotion.stateEffects(),
        label = "volumeStateFill",
    )
    val stroke by animateColorAsState(
        targetValue = if (engaged && enabled) FlickColor.SelectedBorder else FlickColor.Outline,
        animationSpec = if (reducedMotion) snap() else FlickMotion.stateEffects(),
        label = "volumeStateStroke",
    )

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = SecondaryTransportTargetSize)
            .focusProperties { canFocus = enabled }
            .focusBeacon(shape)
            .graphicsLayer {
                val lift = scale.value
                scaleX = lift
                scaleY = lift
                alpha = if (enabled) 1f else 0.38f
            }
            .flickFocusRing(
                visible = ringVisible && !hosted,
                shape = shape,
                progress = { ringPresence.value },
            )
            .clip(shape)
            .background(fill)
            .border(FlickDimens.Hairline, stroke, shape)
            .padding(horizontal = 13.dp, vertical = 10.dp)
            .onKeyEvent { event ->
                if (!enabled) return@onKeyEvent false
                val centerOrEnter = event.key == Key.DirectionCenter || event.key == Key.Enter
                if (event.type == KeyEventType.KeyUp && centerOrEnter) {
                    val consumed = centerOrEnterHeld
                    centerOrEnterHeld = false
                    return@onKeyEvent consumed
                }
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val syntheticRepeatCount = if (centerOrEnter && centerOrEnterHeld) 1 else 0
                when (volumeKeyAction(event.key, syntheticRepeatCount, engaged, enabled)) {
                    VolumeKeyAction.ToggleEngagement -> {
                        // Android repeats a held Center key. Consume repeats, but
                        // only the initial press may change adjustment ownership.
                        centerOrEnterHeld = true
                        engaged = !engaged
                        true
                    }
                    VolumeKeyAction.ConsumeRepeat -> true
                    VolumeKeyAction.Disengage -> { engaged = false; true }
                    VolumeKeyAction.StepDown -> { onChange((level - 0.1f).coerceIn(0f, 1f)); true }
                    VolumeKeyAction.StepUp -> { onChange((level + 0.1f).coerceIn(0f, 1f)); true }
                    VolumeKeyAction.PassThrough -> false
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
            modifier = Modifier.size(FlickDimens.GlyphMedium),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(cells) { i ->
                // A step lands as a fill that arrives rather than as a cut, and
                // the colour is read in the DRAW phase: the volume keys repeat,
                // and ten cells recomposing per frame sit over a live decoder.
                val cellColor = animateColorAsState(
                    targetValue = when {
                        i < filled && engaged -> FlickColor.Spark
                        i < filled -> FlickColor.SparkLight
                        else -> FlickColor.TrackBase
                    },
                    animationSpec = if (reducedMotion) snap() else FlickMotion.stateEffects(),
                    label = "volumeCellFill",
                )
                Box(
                    modifier = Modifier
                        .size(width = 5.dp, height = 16.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .drawBehind { drawRect(cellColor.value) },
                )
            }
        }
    }
}
