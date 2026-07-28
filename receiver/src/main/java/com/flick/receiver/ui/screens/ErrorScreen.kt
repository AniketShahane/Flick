package com.flick.receiver.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.session.ErrorKind
import com.flick.receiver.ui.components.FlickTvButton
import com.flick.receiver.ui.components.GlassPanel
import com.flick.receiver.ui.components.GlassPanelTone
import com.flick.receiver.ui.components.LiveDot
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickDimens
import com.flick.receiver.ui.theme.FlickMotion
import com.flick.receiver.ui.theme.FlickShape
import com.flick.receiver.ui.theme.FlickSpace
import com.flick.receiver.ui.theme.FlickType
import com.flick.receiver.ui.theme.LocalReducedMotion
import com.flick.receiver.ui.theme.errorAmbientBackground
import com.flick.receiver.ui.theme.tvOverscanSafeArea

/**
 * T9 · Errors, calm and specific. Amber [ErrorKind.NotServing] = "reachable, not
 * serving" (the stream ended); crimson [ErrorKind.Unreachable] = the phone left
 * the network. Either way the held position is promised.
 *
 * The two diagnoses stay visually distinct: the accent tints the ambient wash and
 * the phone glyph's status dot.
 *
 * There is exactly ONE action, and it carries the primary treatment. Retrying a
 * cast is not the TV's to offer: every retry needs a fresh castId and a fresh
 * media token, both minted by the sender (control-channel.md §6/§8 — "retry is
 * user initiated"), and the terminal frame this screen was raised by has already
 * put that affordance on the phone. A second, de-emphasised key here would be an
 * offer the receiver cannot honour.
 *
 * NOTHING on this screen moves after the single fade that brings it in. A
 * diagnosed fault is presented still: a card that springs into place and a status
 * light that breathes over it read as an app being playful about a failure, and
 * the fade is on an effects spring precisely so the arrival cannot overshoot.
 */
@Composable
fun ErrorScreen(
    kind: ErrorKind,
    deviceLabel: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actionFocus = remember { FocusRequester() }
    LaunchedEffect(kind) { runCatching { actionFocus.requestFocus() } }

    // The whole entrance: one fade, no geometry. Read inside the layer block so
    // even that costs no recomposition.
    val reducedMotion = LocalReducedMotion.current
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val fade = animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = if (reducedMotion) snap() else FlickMotion.stateEffects(),
        label = "errorCardFade",
    )

    val unreachable = kind == ErrorKind.Unreachable
    val accent = if (unreachable) FlickColor.Trouble else FlickColor.Caution
    val device = deviceLabel ?: stringResource(R.string.device_fallback)
    val title = stringResource(
        if (unreachable) R.string.error_unreachable_title else R.string.error_not_serving_title,
    )
    val detail = stringResource(
        if (unreachable) R.string.error_unreachable_detail else R.string.error_not_serving_detail,
        device,
    )
    val actionLabel = stringResource(
        if (unreachable) R.string.error_end_session else R.string.error_back_to_standby,
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .errorAmbientBackground(accent)
            // After the wash, so the gradient still runs to the panel edge while
            // the card inside it stops at the overscan inset.
            .tvOverscanSafeArea(),
        contentAlignment = Alignment.Center,
    ) {
        GlassPanel(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .graphicsLayer { alpha = fade.value },
            shape = FlickShape.Hero,
            tone = GlassPanelTone.Panel,
            contentPadding = FlickDimens.PanelPadding,
            verticalArrangement = Arrangement.spacedBy(FlickSpace.Md),
            horizontalAlignment = Alignment.CenterHorizontally,
            animateEntrance = false,
        ) {
            PhoneGlyph(accent = accent)
            Text(
                text = title,
                style = FlickType.display(sizeSp = 27),
                color = FlickColor.OnSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = detail,
                style = FlickType.body(sizeSp = 18),
                color = FlickColor.OnSurfaceDim,
                textAlign = TextAlign.Center,
            )
            FlickTvButton(
                onClick = onDismiss,
                focusRequester = actionFocus,
                containerColor = FlickColor.Spark,
                borderColor = Color.Transparent,
                ringColor = FlickColor.FocusRingOnSpark,
                contentPadding = FlickDimens.ControlPadding,
            ) {
                Text(
                    text = actionLabel,
                    style = FlickType.body(sizeSp = 16, weight = FontWeight.Bold),
                    color = FlickColor.OnSpark,
                )
            }
        }
    }
}

/**
 * The phone that went quiet: a dim handset with an accent status light. The light
 * is held, never breathing — the fault is the message, and this screen is mounted
 * by instrumentation tests that wait for the frame clock to settle.
 */
@Composable
private fun PhoneGlyph(accent: Color) {
    Box(contentAlignment = Alignment.TopEnd) {
        Box(
            modifier = Modifier
                .size(width = 37.dp, height = 56.dp)
                .background(FlickColor.ControlFill, FlickShape.Md)
                // Not a hairline: this stroke is the handset, so it holds its
                // weight while the form around it comes down.
                .border(2.dp, FlickColor.Outline, FlickShape.Md),
        )
        Box(
            modifier = Modifier
                .offset(x = 6.dp, y = (-6).dp)
                .size(18.dp)
                .drawBehind { drawCircle(FlickColor.CanvasPair) },
            contentAlignment = Alignment.Center,
        ) {
            LiveDot(color = accent, size = 10.dp)
        }
    }
}
