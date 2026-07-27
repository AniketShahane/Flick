package com.flick.receiver.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.tv.material3.Text
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickDimens
import com.flick.receiver.ui.theme.FlickMotion
import com.flick.receiver.ui.theme.FlickShape
import com.flick.receiver.ui.theme.FlickSpace
import com.flick.receiver.ui.theme.FlickType
import com.flick.receiver.ui.theme.LocalReducedMotion
import com.flick.receiver.ui.theme.glass

/**
 * The design `tvPulse` envelope (spec §6). Returns null — meaning "hold the
 * static end-state" — when the caller is not pulsing or the device asked for
 * reduced motion.
 */
@Composable
private fun rememberPulsePhase(active: Boolean): State<Float>? {
    val reducedMotion = LocalReducedMotion.current
    return if (active && !reducedMotion) {
        val transition = rememberInfiniteTransition(label = "tvPulse")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = FlickMotion.tvPulse(),
            label = "tvPulsePhase",
        )
    } else {
        null
    }
}

/**
 * The breathing live dot — the design's `tvPulse` (spec §6) and the only place it
 * lives. Shared by the chrome net-health pill, the metrics-panel health pill, and
 * the pairing / idle / error status rows.
 */
@Composable
fun LiveDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 6.dp,
    pulsing: Boolean = false,
) {
    val phase = rememberPulsePhase(pulsing)
    val reducedMotion = LocalReducedMotion.current
    // Health is a state, so a change of health dissolves rather than cuts — on
    // the effects spec, which never overshoots: a colour that rang past its
    // target would report a health this dot has never measured. Read in the draw
    // phase so the transition costs a repaint, not a recomposition.
    val ink = animateColorAsState(
        targetValue = color,
        animationSpec = if (reducedMotion) snap() else FlickMotion.stateEffects(),
        label = "liveDotColor",
    )
    Box(
        modifier = modifier
            .size(size)
            .then(
                if (phase != null) {
                    Modifier.graphicsLayer {
                        val p = phase.value
                        val s = lerp(FlickMotion.PULSE_SCALE_MIN, FlickMotion.PULSE_SCALE_MAX, p)
                        scaleX = s
                        scaleY = s
                        alpha = lerp(FlickMotion.PULSE_ALPHA_MIN, FlickMotion.PULSE_ALPHA_MAX, p)
                    }
                } else {
                    Modifier
                },
            )
            .drawBehind { drawCircle(color = ink.value) },
    )
}

/**
 * A glass chrome pill floating over the film (spec §5.3 top chrome) — the
 * "Flicked from …" source pill, the net-health pill and the clock pill. Purely
 * informational unless [onClick] is given, in which case see [GlassPillContainer].
 */
@Composable
fun GlassPill(
    text: String,
    modifier: Modifier = Modifier,
    dotColor: Color? = null,
    dotPulsing: Boolean = false,
    style: TextStyle = FlickType.monoEyebrow(trackingEm = 0.12f),
    color: Color = FlickColor.OnChrome,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    onClick: (() -> Unit)? = null,
    contentDescription: String? = null,
    focusRequester: FocusRequester? = null,
    leading: @Composable (() -> Unit)? = null,
) {
    GlassPillContainer(
        modifier = modifier,
        contentPadding = contentPadding,
        onClick = onClick,
        contentDescription = contentDescription,
        focusRequester = focusRequester,
    ) {
        if (leading != null) leading()
        if (dotColor != null) LiveDot(color = dotColor, pulsing = dotPulsing)
        Text(text = text, color = color, style = style)
    }
}

/**
 * The bare glass pill surface, for chrome that needs a custom row of content.
 *
 * With [onClick] the pill becomes a focus target and wears the receiver's ONE
 * focus vocabulary — the lift, the corner ease and the travelling amber ring
 * that [FlickTvButton] carries — instead of a second one invented here. It never
 * grows a neutral wash: there is no hover on a TV and press is a scale, not a
 * grey fill. Without [onClick] the pill is exactly the informational surface it
 * has always been, and adds nothing to the focus graph.
 */
@Composable
fun GlassPillContainer(
    modifier: Modifier = Modifier,
    shape: Shape = FlickShape.Pill,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(FlickSpace.Sm),
    onClick: (() -> Unit)? = null,
    contentDescription: String? = null,
    focusRequester: FocusRequester? = null,
    content: @Composable RowScope.() -> Unit,
) {
    if (onClick != null) {
        FlickTvButton(
            onClick = onClick,
            modifier = modifier,
            contentDescription = contentDescription,
            focusRequester = focusRequester,
            shape = shape,
            // The glass tone verbatim — `Modifier.glass` is a fill plus a
            // hairline, and the button paints both itself.
            containerColor = FlickColor.Glass,
            borderColor = FlickColor.GlassBorder,
            borderWidth = FlickDimens.Hairline,
            contentPadding = contentPadding,
            horizontalArrangement = horizontalArrangement,
            content = content,
        )
        return
    }
    Row(
        modifier = modifier
            .clip(shape)
            .glass(shape)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = horizontalArrangement,
        content = content,
    )
}
