package com.flick.receiver.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickDimens
import com.flick.receiver.ui.theme.FlickMotion
import com.flick.receiver.ui.theme.FlickShape
import com.flick.receiver.ui.theme.FlickType

/** A chip enters slightly small; it settles out to full size, never past it. */
private const val TELEMETRY_ENTER_SCALE = 0.9f

/**
 * The bordered mono spec chip (receiver-expressive-spec.md §5.3 row 1) —
 * `4K DOLBY VISION`, `E-AC-3 · 5.1`, `HEVC`. Uppercase mono at the 14 sp
 * micro-label floor with the design's hairline border.
 *
 * Callers pass only chips built from real telemetry; a chip with nothing to say
 * is omitted, never filled with a placeholder.
 *
 * With [onClick] the chip becomes a focus target carrying the receiver's one
 * focus vocabulary — the lift, the corner ease and the travelling amber ring of
 * [FlickTvButton], never a neutral wash. Its own hairline is kept explicitly:
 * the chip's default fill is transparent, which would otherwise pick up the
 * doubled outline-only stroke.
 */
@Composable
fun SpecChip(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = FlickColor.OnChrome,
    borderColor: Color = FlickColor.OutlineHairline,
    containerColor: Color = Color.Transparent,
    shape: Shape = FlickShape.Sm,
    style: TextStyle = FlickType.monoEyebrow(trackingEm = 0.12f),
    contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
    onClick: (() -> Unit)? = null,
    contentDescription: String? = null,
    focusRequester: FocusRequester? = null,
) {
    val label: @Composable () -> Unit = {
        Text(
            text = text,
            color = color,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (onClick != null) {
        FlickTvButton(
            onClick = onClick,
            modifier = modifier,
            contentDescription = contentDescription,
            focusRequester = focusRequester,
            shape = shape,
            containerColor = containerColor,
            borderColor = borderColor,
            borderWidth = FlickDimens.Hairline,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.Center,
        ) {
            label()
        }
        return
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(containerColor)
            .border(FlickDimens.Hairline, borderColor, shape)
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        label()
    }
}

/**
 * The entrance a measured fact makes when the receiver finally learns it.
 *
 * Wrap one chip or pill per fact and key the call site on the fact's own text: the
 * row then resolves one reading at a time as telemetry lands, instead of the whole
 * group materialising complete. It changes WHEN a value appears, never WHETHER —
 * a value the receiver cannot measure is still omitted upstream.
 *
 * Geometry takes the spatial spring; opacity takes the effects spec, which never
 * overshoots — a chip flashing past full opacity would read as a rendering fault
 * rather than a measurement.
 */
@Composable
fun TelemetryReveal(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val entered = remember { MutableTransitionState(false) }
    entered.targetState = true
    AnimatedVisibility(
        visibleState = entered,
        modifier = modifier,
        enter = fadeIn(FlickMotion.stateEffects()) + scaleIn(
            initialScale = TELEMETRY_ENTER_SCALE,
            animationSpec = FlickMotion.flickSettleSpatial(),
        ),
        exit = fadeOut(FlickMotion.stateEffects()),
        label = "telemetryReveal",
    ) {
        content()
    }
}
