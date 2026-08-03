package com.flick.sender.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.flick.sender.R
import com.flick.sender.net.AudioDelayPolicy
import com.flick.sender.net.FlickController
import com.flick.sender.ui.theme.FlickCinematicTheme
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.Motion
import com.flick.sender.ui.theme.PillShape
import com.flick.sender.ui.theme.flickRipple
import com.flick.sender.ui.theme.pressScale
import com.flick.sender.ui.theme.rememberFlickTouchHaptics
import com.flick.sender.ui.theme.rememberReduceMotion
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The A/V nudge, behind the remote's middle segment. Forced cinematic, like every surface
 * the remote raises.
 *
 * Nothing on the wire reports this value back — the TV applies what it is told and says
 * nothing about it — so the phone is the source of truth for what is shown here. The two
 * stay in step because both sides drop the nudge to zero on a new cast and neither touches
 * it for anything else, which is also why a subtitle swap (a reload of the same cast)
 * leaves it exactly where the user put it.
 */
@Composable
fun AudioDelaySheet(controller: FlickController, onDismiss: () -> Unit) {
    FlickCinematicTheme {
        AudioDelayContent(controller, onDismiss)
    }
}

@Composable
private fun AudioDelayContent(controller: FlickController, onDismiss: () -> Unit) {
    val colors = LocalFlickColors.current
    val haptics = rememberFlickTouchHaptics()
    val delayMs by controller.audioDelayMs.collectAsState()

    BottomSheet(
        onDismiss = onDismiss,
        contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 26.dp),
    ) {
        SheetGrabber()
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.audio_delay_title),
            style = FlickText.headlineMedium.copy(color = colors.onSurface),
        )
        Spacer(Modifier.height(5.dp))
        // The one claim this sheet makes about cost, in the seat both sibling sheets put
        // their explanatory line in: the nudge is a decode-time offset on the TV, so it
        // costs neither a re-encode nor the re-buffer a subtitle swap does.
        Text(
            stringResource(R.string.audio_delay_body),
            style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
        )

        Spacer(Modifier.height(16.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(FlickCorners.statCard))
                .background(colors.fillCard)
                .padding(horizontal = 16.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AudioDelayReadout(delayMs)
            Spacer(Modifier.height(16.dp))
            AudioDelayControls(
                delayMs = delayMs,
                onNudge = { later ->
                    controller.nudgeAudioDelay(later)
                    haptics.sliderStep()
                },
                onDelayChange = controller::setAudioDelay,
            )
        }

        // Offered only when there is something to put back: a Reset at zero is a control
        // for a change nobody made.
        if (delayMs != AudioDelayPolicy.IN_SYNC_MS) {
            Spacer(Modifier.height(12.dp))
            ResetRow(
                onReset = {
                    controller.resetAudioDelay()
                    haptics.toggle(false)
                },
            )
        }

        Spacer(Modifier.height(18.dp))
        val doneInteraction = remember { MutableInteractionSource() }
        // Read inside the sheet that provides it: Done is the same dismissal the scrim,
        // Back and a drag down are, and must take the same exit.
        val done = LocalSheetDismiss.current
        Text(
            text = stringResource(R.string.audio_delay_done),
            style = FlickText.titleSmall.copy(color = colors.onInverseSurface),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .pressScale(doneInteraction)
                .clip(PillShape)
                .background(colors.inverseSurface)
                .clickable(
                    interactionSource = doneInteraction,
                    indication = flickRipple(colors.onInverseSurface),
                    role = Role.Button,
                    onClick = done,
                )
                .heightIn(min = 48.dp)
                .padding(vertical = 17.dp),
        )
    }
}

/**
 * The offset as a reading, and the direction of it in words on the line below. A signed
 * figure alone is genuinely ambiguous — half the world reads `+150 ms` as the sound
 * arriving first — so the sign is never the only thing that says which way this went.
 *
 * One merged node to TalkBack: the figure is the reading and the caption is what the
 * figure means, and two focusable halves of one sentence is not how the rest of the app
 * speaks.
 */
@Composable
private fun ColumnScope.AudioDelayReadout(delayMs: Int) {
    val colors = LocalFlickColors.current
    val synced = delayMs == AudioDelayPolicy.IN_SYNC_MS
    val label = stringResource(R.string.a11y_audio_delay)
    val spoken = spokenAudioDelay(delayMs)
    Column(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = spoken
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (synced) {
                stringResource(R.string.audio_delay_in_sync)
            } else {
                stringResource(R.string.audio_delay_value, AudioDelayPolicy.signed(delayMs))
            },
            style = FlickText.monoGauge.copy(color = colors.onSurface),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(
                when {
                    synced -> R.string.audio_delay_caption_synced
                    delayMs > 0 -> R.string.audio_delay_caption_later
                    else -> R.string.audio_delay_caption_earlier
                },
            ),
            style = FlickText.bodySmall.copy(color = colors.onSurfaceDim),
            textAlign = TextAlign.Center,
        )
    }
}

/** The fine control on either side of the coarse one, which is what makes the pair symmetrical. */
@Composable
private fun ColumnScope.AudioDelayControls(
    delayMs: Int,
    onNudge: (Boolean) -> Unit,
    onDelayChange: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Stepper(
            glyph = stringResource(R.string.audio_delay_step_earlier),
            description = stringResource(
                R.string.a11y_audio_delay_step_earlier,
                AudioDelayPolicy.STEP_MS,
            ),
            enabled = AudioDelayPolicy.canStepDown(delayMs),
            onClick = { onNudge(false) },
        )
        AudioDelayBlade(
            delayMs = delayMs,
            onDelayChange = onDelayChange,
            modifier = Modifier.weight(1f),
        )
        Stepper(
            glyph = stringResource(R.string.audio_delay_step_later),
            description = stringResource(
                R.string.a11y_audio_delay_step_later,
                AudioDelayPolicy.STEP_MS,
            ),
            enabled = AudioDelayPolicy.canStepUp(delayMs),
            onClick = { onNudge(true) },
        )
    }
}

/**
 * One 25 ms press. The mark is set in type rather than drawn as a glyph, for the reason
 * the transport cluster sets its "10" in type: a plus and a minus are figures the type
 * scale already carries, and a hand-authored vector of either would be a new mark saying
 * exactly what the font says.
 *
 * At a bound it is disabled rather than inert — a control that answers a press with
 * nothing is indistinguishable from one that is broken.
 */
@Composable
private fun Stepper(
    glyph: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalFlickColors.current
    val ink = if (enabled) colors.onSurface else colors.onSurfaceDim.copy(alpha = DisabledInk)
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(StepperSize)
            .then(if (enabled) Modifier.pressScale(interaction) else Modifier)
            .clip(PillShape)
            .background(if (enabled) colors.fillControl else colors.fillCard)
            .clickable(
                interactionSource = interaction,
                indication = flickRipple(colors.onSurface),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = description
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = FlickText.monoGauge.copy(color = ink))
    }
}

/**
 * The coarse control, in [com.flick.sender.ui.components.VolumeSlider]'s own language —
 * the same 13 dp track, the same swell under the finger, the same amber blade — because
 * this is the second slider on a remote that must not speak two slider dialects.
 *
 * What differs is the origin. Volume fills from the left because zero is the left end;
 * this fills from the CENTRE, because zero is in the middle and the quantity being shown
 * is a direction as much as an amount. The centre carries a detent mark for the same
 * reason: in-sync is the one value on this track worth being able to find without reading
 * the number.
 *
 * The value is quantised by [AudioDelayPolicy.clamp], so the finger lands on exactly the
 * steps the two buttons beside it reach, and a tick is spent only when it crosses one.
 *
 * It reports on every pointer sample that crosses a step and NOT on release. A drag that
 * committed once at the end would be a single frame asking the TV to move the picture by
 * up to a second, which is the one thing the receiver cannot absorb quietly; streaming it
 * means an ordinary drag never asks for more than a step at a time. The session bounds
 * what is left — a tap on the far end of this track is still one sample, and it is walked
 * there rather than jumped.
 */
@Composable
private fun AudioDelayBlade(
    delayMs: Int,
    onDelayChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFlickColors.current
    val haptics = rememberFlickTouchHaptics()
    val scope = rememberCoroutineScope()
    // In an Animatable rather than animateDpAsState so the swell plays entirely in the
    // draw phase, exactly as the volume blade's does.
    val swell = remember { Animatable(0f) }
    val swellSpec = Motion.orSnap(
        rememberReduceMotion(),
        MaterialTheme.motionScheme.fastSpatialSpec<Float>(),
    )
    // The gesture handler is keyed on Unit, so the value it started from has to be read
    // through holders rather than captured at the composition that installed it.
    val currentDelay = rememberUpdatedState(delayMs)
    val report = rememberUpdatedState(onDelayChange)
    val label = stringResource(R.string.a11y_audio_delay)
    val spoken = spokenAudioDelay(delayMs)
    val adjustLabel = stringResource(R.string.a11y_audio_delay_adjust)
    Box(
        modifier
            .height(BladeBoxHeight)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = delayMs.toFloat(),
                    range = AudioDelayPolicy.MIN_MS.toFloat()..AudioDelayPolicy.MAX_MS.toFloat(),
                    // Stated, so a screen reader's own increment lands on the same grid
                    // the two buttons do rather than somewhere between two legal values.
                    steps = AudioDelayPolicy.STEPS_BETWEEN_BOUNDS,
                )
                contentDescription = label
                stateDescription = spoken
                setProgress(adjustLabel) { target ->
                    report.value(AudioDelayPolicy.clamp(target.roundToInt()))
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = { size.width.toFloat().coerceAtLeast(1f) }
                    // Seeded from the value before the gesture: a tap on the blade itself
                    // must stay silent, one that jumps the offset must tick once.
                    var last = AudioDelayPolicy.clamp(currentDelay.value)
                    val emit = { x: Float ->
                        val next = AudioDelayPolicy.clamp(delayAt(x / width()))
                        if (next != last) {
                            last = next
                            haptics.sliderStep()
                            report.value(next)
                        }
                    }
                    scope.launch { swell.animateTo(1f, swellSpec) }
                    try {
                        emit(down.position.x)
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: event.changes.firstOrNull()
                            if (change == null) break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            emit(change.position.x)
                            change.consume()
                        }
                    } finally {
                        // Pointer cancellation kills this coroutine, so the shrink is
                        // launched on the composition scope, not this one.
                        scope.launch { swell.animateTo(0f, swellSpec) }
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val t = swell.value
            val cy = size.height / 2f
            val track = lerp(TrackHeight.toPx(), TrackDragHeight.toPx(), t)
            val r = track / 2f
            drawRoundRect(
                color = colors.fillTrack,
                topLeft = Offset(0f, cy - r),
                size = Size(size.width, track),
                cornerRadius = CornerRadius(r, r),
            )
            val centre = size.width / 2f
            val head = fractionOf(delayMs) * size.width
            val span = abs(head - centre)
            if (span > 0f) {
                drawRoundRect(
                    color = colors.onSurface,
                    topLeft = Offset(minOf(centre, head), cy - r),
                    size = Size(span, track),
                    cornerRadius = CornerRadius(r, r),
                )
            }
            // Drawn after the fill and before the blade: it is the one landmark on this
            // track, so it has to survive the fill running over it and give way only to
            // the blade actually standing on it.
            val detentW = DetentWidth.toPx()
            val detentH = DetentHeight.toPx()
            drawRoundRect(
                color = colors.onSurfaceFaint,
                topLeft = Offset(centre - detentW / 2f, cy - detentH / 2f),
                size = Size(detentW, detentH),
                cornerRadius = CornerRadius(detentW / 2f, detentW / 2f),
            )
            val bladeW = lerp(ThumbWidth.toPx(), ThumbDragWidth.toPx(), t)
            val bladeH = lerp(ThumbHeight.toPx(), ThumbDragHeight.toPx(), t)
            val bx = head.coerceIn(
                bladeW / 2f,
                (size.width - bladeW / 2f).coerceAtLeast(bladeW / 2f),
            )
            drawRoundRect(
                color = colors.spark,
                topLeft = Offset(bx - bladeW / 2f, cy - bladeH / 2f),
                size = Size(bladeW, bladeH),
                // Half the blade's own width, so it stays a blade rather than squaring
                // off as it grows.
                cornerRadius = CornerRadius(bladeW / 2f, bladeW / 2f),
            )
        }
    }
}

/** The one way back to zero, offered only while there is a nudge to undo. */
@Composable
private fun ColumnScope.ResetRow(onReset: () -> Unit) {
    val colors = LocalFlickColors.current
    val interaction = remember { MutableInteractionSource() }
    val description = stringResource(R.string.a11y_audio_delay_reset)
    Text(
        text = stringResource(R.string.audio_delay_reset),
        style = FlickText.labelMedium.copy(color = colors.onSurfaceDim),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .pressScale(interaction)
            .clip(PillShape)
            .semantics { contentDescription = description }
            .clickable(
                interactionSource = interaction,
                indication = flickRipple(colors.onSurface),
                role = Role.Button,
                onClick = onReset,
            )
            .heightIn(min = 48.dp)
            .padding(horizontal = 20.dp, vertical = 15.dp),
    )
}

/**
 * What a screen reader is told the offset is. The direction is spelt out in words and the
 * magnitude is unsigned, because a sign read aloud is the one form of this value nobody
 * can act on.
 */
@Composable
private fun spokenAudioDelay(delayMs: Int): String = when {
    delayMs == AudioDelayPolicy.IN_SYNC_MS -> stringResource(R.string.a11y_audio_delay_synced)
    delayMs > 0 -> stringResource(R.string.a11y_audio_delay_later, delayMs)
    else -> stringResource(R.string.a11y_audio_delay_earlier, -delayMs)
}

/** Where a delay sits along the track: [AudioDelayPolicy.IN_SYNC_MS] is dead centre. */
private fun fractionOf(delayMs: Int): Float {
    val span = (AudioDelayPolicy.MAX_MS - AudioDelayPolicy.MIN_MS).toFloat()
    return ((delayMs - AudioDelayPolicy.MIN_MS) / span).coerceIn(0f, 1f)
}

/** The inverse: what a pointer at [fraction] across the track is asking for, before quantising. */
private fun delayAt(fraction: Float): Int {
    val span = (AudioDelayPolicy.MAX_MS - AudioDelayPolicy.MIN_MS).toFloat()
    return (AudioDelayPolicy.MIN_MS + fraction.coerceIn(0f, 1f) * span).roundToInt()
}

/** Android's minimum touch target, which is also the whole of a stepper. */
private val StepperSize = 48.dp

/** The blade's gesture and semantics box; the painted track inside it is much shorter. */
private val BladeBoxHeight = 48.dp

// Geometry mirrored from VolumeSlider, resting and grabbed. Mirrored rather than shared
// because those are private constants of a component this sheet does not own — moving
// one there means moving it here, and the two sliders reading differently under the same
// finger is the only failure this arrangement can produce.
private val TrackHeight = 13.dp
private val ThumbWidth = 6.dp
private val ThumbHeight = 26.dp
private val TrackDragHeight = 18.dp
private val ThumbDragWidth = 9.dp
private val ThumbDragHeight = 34.dp

/** The in-sync landmark. Narrower than the blade so the blade covers it when it arrives. */
private val DetentWidth = 2.dp
private val DetentHeight = 21.dp

/** A stepper at its bound: legible as a control, unmistakable as an unavailable one. */
private const val DisabledInk = 0.5f
