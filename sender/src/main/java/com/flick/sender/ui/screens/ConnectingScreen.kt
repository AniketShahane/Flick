package com.flick.sender.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.flick.sender.R
import com.flick.sender.model.MediaItem
import com.flick.sender.net.CastStartState
import com.flick.sender.net.FlickController
import com.flick.sender.ui.components.CastPosterKey
import com.flick.sender.ui.components.FlickSubtleButton
import com.flick.sender.ui.components.StatusKind
import com.flick.sender.ui.components.StatusPill
import com.flick.sender.ui.components.TravelingLight
import com.flick.sender.ui.components.flickSharedFrame
import com.flick.sender.ui.components.rememberVideoFrameRequest
import com.flick.sender.ui.components.rememberVideoImageLoader
import com.flick.sender.ui.theme.FlickCinematicTheme
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickGradients
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.PosterShadow
import com.flick.sender.ui.theme.rememberFlickTouchHaptics
import com.flick.sender.ui.theme.rememberReduceMotion

private enum class StepState { DONE, ACTIVE, PENDING }

/** S5 — connecting. The handoff diagram holds the wait; one honest line names it. */
@Composable
fun ConnectingScreen(
    controller: FlickController,
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    val castStart by controller.castStart.collectAsState()
    val tv by controller.connectedTv.collectAsState()
    val item by controller.castingItem.collectAsState()
    val cancelDescription = stringResource(R.string.a11y_cancel_connecting)
    val connectingDescription = stringResource(R.string.a11y_pairing_status, stringResource(R.string.connecting_status))

    // Bound to the handshake's own terminal states, never to arriving here: the route
    // only reaches this screen mid-handshake, so the seeded previous value keeps the
    // first composition silent and every pulse follows a reported outcome.
    val haptics = rememberFlickTouchHaptics()
    var lastCastStart by remember { mutableStateOf(castStart) }
    LaunchedEffect(castStart) {
        val previous = lastCastStart
        lastCastStart = castStart
        if (previous == castStart) return@LaunchedEffect
        when (castStart) {
            is CastStartState.Active -> haptics.confirm()
            is CastStartState.Failed -> haptics.reject()
            else -> Unit
        }
    }

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
    val doneSteps = steps.count { it.second == StepState.DONE }

    FlickCinematicTheme {
        val colors = LocalFlickColors.current
        val motionScheme = MaterialTheme.motionScheme
        val reduceMotion = rememberReduceMotion()
        BoxWithConstraints(Modifier.fillMaxSize().background(FlickGradients.connectingBackdrop)) {
            // The column does not scroll, so a short window has to shed something to
            // keep Cancel on screen. It sheds the decorative diagram and the wide gaps,
            // never the frame: the frame is the surface the remote's poster flies from,
            // and a landing with no departure is the moment lost.
            val roomy = maxHeight >= RoomyColumnHeight
            Column(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (roomy) 24.dp else 13.dp),
            ) {
                // The file itself, waiting on the wire. It is the same frame the remote
                // lands on, so the handshake ends with the still travelling, not fading.
                CastFrame(
                    item = item,
                    sharedScope = sharedScope,
                    animatedScope = animatedScope,
                    width = if (roomy) CastFrameWidth else CastFrameCompactWidth,
                    height = if (roomy) CastFrameHeight else CastFrameCompactHeight,
                )
                if (roomy) HandoffDiagram()
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
                    AnimatedContent(
                        targetState = stage.first,
                        transitionSpec = {
                            if (reduceMotion) {
                                EnterTransition.None togetherWith ExitTransition.None
                            } else {
                                (
                                    slideInVertically(motionScheme.fastSpatialSpec()) { it / 2 } +
                                        fadeIn(motionScheme.fastEffectsSpec())
                                    ) togetherWith (
                                    slideOutVertically(motionScheme.fastSpatialSpec()) { -it / 2 } +
                                        fadeOut(motionScheme.fastEffectsSpec())
                                    )
                            }
                        },
                        label = "stage",
                    ) { line ->
                        Text(
                            text = line,
                            style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
                            textAlign = TextAlign.Center,
                            // Two lines are always reserved so a longer stage name never
                            // walks the Cancel button down the screen mid-handshake.
                            minLines = 2,
                        )
                    }
                }
                HandshakeIndicator(doneSteps = doneSteps)
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

/** The still that will land on the remote, held at card size while the TV answers. */
@Composable
private fun CastFrame(
    item: MediaItem?,
    sharedScope: SharedTransitionScope?,
    animatedScope: AnimatedVisibilityScope?,
    width: Dp,
    height: Dp,
) {
    val colors = LocalFlickColors.current
    val imageLoader = rememberVideoImageLoader()
    val request = rememberVideoFrameRequest(item?.uri, item?.durationMs ?: 0L)
    val shape = RoundedCornerShape(FlickCorners.detailPoster)
    Box(
        Modifier
            .size(width = width, height = height)
            .shadow(20.dp, shape, clip = false, ambientColor = PosterShadow, spotColor = PosterShadow)
            .clip(shape)
            .background(colors.surfaceRaisedAlt),
    ) {
        if (request != null) {
            AsyncImage(
                model = request,
                // Decorative here: the title below already names what is being sent.
                contentDescription = null,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .flickSharedFrame(sharedScope, animatedScope, CastPosterKey)
                    .fillMaxSize(),
            )
        }
    }
}

/**
 * Four discrete protocol steps, never interpolated time. The shape changes when a
 * stage lands and at no other moment, which is also why nothing here may imply
 * transcoding progress — there is none.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HandshakeIndicator(doneSteps: Int) {
    val colors = LocalFlickColors.current
    val stages = remember {
        listOf(
            MaterialShapes.Circle,
            MaterialShapes.Cookie4Sided,
            MaterialShapes.Clover4Leaf,
            MaterialShapes.Pill,
        )
    }
    LoadingIndicator(
        progress = { doneSteps / HandshakeSteps },
        color = colors.sparkBright,
        polygons = stages,
    )
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

// The card is deliberately small: it is the file in transit, not the presentation,
// and the whole point is how far it travels when the TV reports its first frame.
private val CastFrameWidth = 152.dp
private val CastFrameHeight = 86.dp

// The same 16:9 still at thumbnail scale, for windows that cannot afford the card at
// full size. It still has to read as the file, so it does not shrink below this.
private val CastFrameCompactWidth = 104.dp
private val CastFrameCompactHeight = 59.dp

// Card, diagram, copy, indicator and Cancel cost roughly 430 dp at full spacing, and
// the status pill claims the bottom band; below this the centred column would start
// clipping its own terminal control.
private val RoomyColumnHeight = 520.dp
private const val HandshakeSteps = 4f
