package com.flick.sender.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flick.sender.R
import com.flick.sender.model.ConnectionStatus
import com.flick.sender.net.FlickController
import com.flick.sender.net.CastStartState
import com.flick.sender.ui.components.FlickSubtleButton
import com.flick.sender.ui.components.LiveDot
import com.flick.sender.ui.components.StatusKind
import com.flick.sender.ui.components.StatusPill
import com.flick.sender.ui.components.TravelingLight
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors

private enum class StepState { DONE, ACTIVE, PENDING }

/** S5 — connecting. Traveling light debuts; three honest steps name the handshake. */
@Composable
fun ConnectingScreen(controller: FlickController) {
    val colors = LocalFlickColors.current
    val castStart by controller.castStart.collectAsState()
    val tv by controller.connectedTv.collectAsState()

    val control = if (castStart is CastStartState.ConnectingControl) StepState.ACTIVE else StepState.DONE
    val prepare = when (castStart) { is CastStartState.StartingSource -> StepState.ACTIVE; is CastStartState.AwaitingAcceptance, is CastStartState.AwaitingFirstFrame, is CastStartState.Active -> StepState.DONE; else -> StepState.PENDING }
    val checking = when (castStart) { is CastStartState.AwaitingAcceptance, is CastStartState.AwaitingFirstFrame -> StepState.ACTIVE; is CastStartState.Active -> StepState.DONE; else -> StepState.PENDING }
    val firstFrame = if (castStart is CastStartState.AwaitingFirstFrame) StepState.ACTIVE else if (castStart is CastStartState.Active) StepState.DONE else StepState.PENDING

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(horizontal = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeviceGlyph(width = 30.dp, height = 52.dp)
                TravelingLight(Modifier.width(64.dp).height(26.dp))
                DeviceGlyph(width = 74.dp, height = 44.dp)
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = tv?.let { stringResource(R.string.connecting_title, it.name) }
                    ?: stringResource(R.string.connecting_title_generic),
                style = FlickText.heading.copy(color = colors.onSurface),
            )
            Spacer(Modifier.height(18.dp))
            Column(horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                StepRow(stringResource(R.string.connecting_step_handshake), control)
                StepRow(stringResource(R.string.connecting_step_prepare), prepare)
                StepRow(stringResource(R.string.connecting_step_checking), checking)
                StepRow(stringResource(R.string.connecting_step_starting), firstFrame)
            }
            Spacer(Modifier.height(18.dp))
            FlickSubtleButton(text = stringResource(R.string.connecting_cancel), onClick = controller::cancelCast)
        }

        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)) {
            StatusPill(stringResource(R.string.connecting_status), StatusKind.CONNECTING)
        }
    }
}

@Composable
private fun DeviceGlyph(width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp) {
    val colors = LocalFlickColors.current
    Box(
        Modifier
            .size(width = width, height = height)
            .clip(RoundedCornerShape(6.dp))
            .border(1.5.dp, colors.onSurfaceDim.copy(alpha = 0.4f), RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            Modifier
                .padding(bottom = 6.dp, start = 5.dp, end = 5.dp)
                .fillMaxWidth()
                .height(3.dp)
                .clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(colors.sparkLight, colors.spark))),
        )
    }
}

@Composable
private fun StepRow(text: String, state: StepState) {
    val colors = LocalFlickColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (state) {
            StepState.DONE -> Icon(FlickIcons.Check, contentDescription = null, tint = colors.live, modifier = Modifier.size(14.dp))
            StepState.ACTIVE -> CircularProgressIndicator(
                color = colors.link,
                strokeWidth = 2.dp,
                modifier = Modifier.size(13.dp),
            )
            StepState.PENDING -> Box(
                Modifier
                    .size(13.dp)
                    .clip(CircleShape)
                    .border(2.dp, colors.onSurfaceFaint.copy(alpha = 0.4f), CircleShape),
            )
        }
        Spacer(Modifier.width(9.dp))
        Text(
            text,
            style = FlickText.caption.copy(
                color = if (state == StepState.PENDING) colors.onSurfaceFaint else colors.onSurface,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}
