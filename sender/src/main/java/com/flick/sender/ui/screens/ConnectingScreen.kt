package com.flick.sender.ui.screens

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flick.sender.R
import com.flick.sender.net.CastStartState
import com.flick.sender.net.FlickController
import com.flick.sender.ui.components.FlickSubtleButton
import com.flick.sender.ui.components.StatusKind
import com.flick.sender.ui.components.StatusPill
import com.flick.sender.ui.components.TravelingLight
import com.flick.sender.ui.theme.FlickCinematicTheme
import com.flick.sender.ui.theme.FlickGradients
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.Motion
import com.flick.sender.ui.theme.rememberReduceMotion

private enum class StepState { DONE, ACTIVE, PENDING }

/** S5 — connecting. The handoff diagram holds the wait; one honest line names it. */
@Composable
fun ConnectingScreen(controller: FlickController) {
    val castStart by controller.castStart.collectAsState()
    val tv by controller.connectedTv.collectAsState()
    val cancelDescription = stringResource(R.string.a11y_cancel_connecting)
    val connectingDescription = stringResource(R.string.a11y_pairing_status, stringResource(R.string.connecting_status))

    val control = if (castStart is CastStartState.ConnectingControl) StepState.ACTIVE else StepState.DONE
    val prepare = when (castStart) { is CastStartState.StartingSource -> StepState.ACTIVE; is CastStartState.AwaitingAcceptance, is CastStartState.AwaitingFirstFrame, is CastStartState.Active -> StepState.DONE; else -> StepState.PENDING }
    val checking = when (castStart) { is CastStartState.AwaitingAcceptance, is CastStartState.AwaitingFirstFrame -> StepState.ACTIVE; is CastStartState.Active -> StepState.DONE; else -> StepState.PENDING }
    val firstFrame = if (castStart is CastStartState.AwaitingFirstFrame) StepState.ACTIVE else if (castStart is CastStartState.Active) StepState.DONE else StepState.PENDING

    val steps = listOf(
        stringResource(R.string.connecting_step_handshake) to control,
        stringResource(R.string.connecting_step_prepare) to prepare,
        stringResource(R.string.connecting_step_checking) to checking,
        stringResource(R.string.connecting_step_starting) to firstFrame,
    )
    // All four stages are still tracked; the screen names only the one the handshake
    // is actually in, so the line never claims progress the TV hasn't reported.
    val stage = steps.firstOrNull { it.second == StepState.ACTIVE }
        ?: steps.lastOrNull { it.second == StepState.DONE }
        ?: steps.first()

    FlickCinematicTheme {
        val colors = LocalFlickColors.current
        Box(Modifier.fillMaxSize().background(FlickGradients.connectingBackdrop)) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                HandoffDiagram()
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Text(
                        text = tv?.let { stringResource(R.string.connecting_title, it.name) }
                            ?: stringResource(R.string.connecting_title_generic),
                        style = FlickText.headlineSmall.copy(color = colors.onSurface),
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stage.first,
                        style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
                        textAlign = TextAlign.Center,
                    )
                }
                RingSpinner()
                FlickSubtleButton(
                    text = stringResource(R.string.connecting_cancel),
                    onClick = controller::cancelCast,
                    modifier = Modifier.semantics { contentDescription = cancelDescription },
                )
            }

            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp)
                    .semantics { contentDescription = connectingDescription },
            ) {
                StatusPill(stringResource(R.string.connecting_status), StatusKind.CONNECTING)
            }
        }
    }
}

/** Phone → hairline → TV. Decorative: the copy below it carries the meaning. */
@Composable
private fun HandoffDiagram() {
    val colors = LocalFlickColors.current
    Row(
        Modifier.fillMaxWidth().height(76.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DeviceOutline(width = 42.dp, height = 66.dp, corner = 13.dp)
        TravelingLight(
            modifier = Modifier
                .weight(1f)
                .height(10.dp)
                .padding(start = 6.dp, end = 4.dp),
            trackColor = colors.onSurface.copy(alpha = 0.18f),
        )
        DeviceOutline(width = 74.dp, height = 48.dp, corner = 9.dp)
    }
}

@Composable
private fun DeviceOutline(width: Dp, height: Dp, corner: Dp) {
    val colors = LocalFlickColors.current
    val shape = RoundedCornerShape(corner)
    Box(
        Modifier
            .size(width = width, height = height)
            .border(2.5.dp, colors.onSurface.copy(alpha = 0.55f), shape),
    )
}

/** Indeterminate by design: the receiver reports stages, never a percentage. */
@Composable
private fun RingSpinner() {
    val colors = LocalFlickColors.current
    val reduceMotion = rememberReduceMotion()
    // Kept as State and read in the draw scope: unwrapped here it would rebuild this
    // composable and its Canvas lambda on every frame of the handshake.
    val angle = if (reduceMotion) {
        null
    } else {
        val transition = rememberInfiniteTransition(label = "spinner")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(Motion.SpinMs, easing = Motion.Steady)),
            label = "spinnerAngle",
        )
    }
    val track: Color = colors.fillTrackAlt
    val head: Color = colors.sparkBright
    Canvas(Modifier.size(36.dp)) {
        val sweepStart = (angle?.value ?: 0f) - 90f
        val stroke = 3.5.dp.toPx()
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(
            color = track,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke),
        )
        drawArc(
            color = head,
            startAngle = sweepStart,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}
