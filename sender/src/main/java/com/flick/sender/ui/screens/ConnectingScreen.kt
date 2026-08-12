package com.flick.sender.ui.screens

import android.content.Intent
import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import com.flick.sender.net.LinkVerdict
import com.flick.sender.ui.Format
import com.flick.sender.ui.components.AdvisoryCard
import com.flick.sender.ui.components.AdvisoryTone
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
import com.flick.sender.ui.theme.FlickIcons
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
    val context = LocalContext.current
    val cancelDescription = stringResource(R.string.a11y_cancel_connecting)
    val connectingDescription = stringResource(R.string.a11y_pairing_status, stringResource(R.string.connecting_status))

    // Held as State and narrowed to one boolean here: a verdict republishes with a fresh
    // measurement every second, and this screen is a morphing indicator and a travelling
    // light that must not be rebuilt to move a number the card reads for itself. There is
    // no timer of this screen's own and none is owed — LinkCapacityPolicy.MIN_WINDOW_MS is
    // what puts the earliest possible Starved on the far side of a 6 s window.
    val linkVerdict = controller.linkVerdict.collectAsState()
    val starvedLink by remember(linkVerdict) {
        derivedStateOf { linkVerdict.value is LinkVerdict.Starved }
    }
    // The dismissal is this screen's own — the monitor has no hook for it, because "keep
    // waiting" answers one wait rather than saying anything about the link. Held against
    // the cast it was given for, so a retry of the same film is told again: the attempt
    // that answer was about is over.
    var keptWaitingFor by rememberSaveable { mutableStateOf<String?>(null) }
    val film = item
    // AwaitingFirstFrame alone: acceptance upstream is given 2 s, which cannot outlast the
    // 2 s warm-up and 6 s window a Starved verdict costs, so naming that state too would
    // only claim one this can never be reached in. A file with no name has no sentence to
    // put in the body, and nothing here is guessed.
    val starting = castStart as? CastStartState.AwaitingFirstFrame
    val showSlowLink = starvedLink && film != null &&
        starting != null && starting.castId != keptWaitingFor

    // Handing the file to another player is the user ending this attempt, so the cast is
    // torn down — but only once that player has actually taken it. Nothing on this screen
    // touches the 18 s first-frame deadline that still owns the outcome.
    val playHere: () -> Unit = {
        val uri = film?.uri
        if (uri != null) {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "video/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                )
            }
                .onSuccess { controller.cancelCast() }
                // Silence here would leave the escape hatch from a stalling cast looking
                // like a button that simply does not work.
                .onFailure {
                    Toast.makeText(context, R.string.error_no_player_toast, Toast.LENGTH_SHORT).show()
                }
        }
    }
    val slowLinkScroll = rememberScrollState()

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
    val stageIndex = steps.indexOf(stage).coerceAtLeast(0)

    FlickCinematicTheme {
        val colors = LocalFlickColors.current
        val motionScheme = MaterialTheme.motionScheme
        val reduceMotion = rememberReduceMotion()
        BoxWithConstraints(Modifier.fillMaxSize().background(FlickGradients.connectingBackdrop)) {
            // A short window sheds something rather than scrolling for it: Cancel has to
            // be under the thumb, not below the fold. It sheds the decorative diagram and
            // the wide gaps, never the frame — the frame is the surface the remote's
            // poster flies from, and a landing with no departure is the moment lost.
            val roomy = connectingIsRoomy(maxHeight.value, LocalDensity.current.fontScale)
            // The status pill owns the foot of the window; centring the column in what is
            // left is the only thing keeping Cancel out from under it.
            Box(
                Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .padding(bottom = StatusPillBand),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        // Centred while it fits and scrolling when it does not, the same
                        // arrangement the error face carries. The slow-link card costs
                        // this column another ~170 dp — more than the shedding above can
                        // buy back at a large type scale — and the row it would push off
                        // is Cancel.
                        .verticalScroll(slowLinkScroll)
                        .padding(horizontal = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(if (roomy) 24.dp else 13.dp),
                ) {
                    // The file itself, waiting on the wire. It is the same frame the
                    // remote lands on, so the handshake ends with the still travelling,
                    // not fading.
                    CastFrame(
                        item = item,
                        sharedScope = sharedScope,
                        animatedScope = animatedScope,
                        width = if (roomy) CastFrameWidth else CastFrameCompactWidth,
                        height = if (roomy) CastFrameHeight else CastFrameCompactHeight,
                    )
                    // The diagram is decoration and the card is news, so the card takes its
                    // room rather than being stacked under it.
                    if (roomy && !showSlowLink) HandoffDiagram()
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
                                // Two lines are always reserved so a longer stage name
                                // never walks the Cancel button down the screen
                                // mid-handshake.
                                minLines = 2,
                            )
                        }
                    }
                    // Between the stage line it amends and the indicator that answers it:
                    // the card says Flick keeps trying, and the shape below it is still
                    // moving while it does.
                    // showSlowLink already carries both non-null checks, and the compiler
                    // narrows film and starting through it.
                    if (showSlowLink) {
                        StartingLinkCard(
                            verdict = linkVerdict,
                            title = film.name,
                            onPlayHere = playHere,
                            onKeepWaiting = { keptWaitingFor = starting.castId },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    HandshakeIndicator(stageIndex = stageIndex)
                    FlickSubtleButton(
                        text = stringResource(R.string.connecting_cancel),
                        onClick = controller::cancelCast,
                        modifier = Modifier.semantics { contentDescription = cancelDescription },
                    )
                }
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

/**
 * What the phone has measured of its own serving socket, once that measurement is under
 * the film's bitrate for a whole window. Information, never a decision: the 18 s deadline
 * upstream still owns the outcome, so a first frame at second 17 starts the film and takes
 * this away with it.
 *
 * Both numbers or nothing. The copy says "this link is carrying" and names no culprit —
 * a byte counter on the serving socket cannot separate a slow router from a slow
 * `content://` provider, and only the pre-cast advisory, which reads the band directly,
 * is allowed to name one.
 *
 * A composable of its own so the verdict is read here: the reading moves every second and
 * the loop, the morph and the shared-element frame above must not be rebuilt for it.
 */
@Composable
private fun StartingLinkCard(
    verdict: State<LinkVerdict>,
    title: String,
    onPlayHere: () -> Unit,
    onKeepWaiting: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val starved = verdict.value as? LinkVerdict.Starved ?: return
    AdvisoryCard(
        icon = FlickIcons.Wifi,
        title = stringResource(R.string.link_starting_title),
        body = stringResource(
            R.string.link_starting_body,
            title,
            Format.bitrate(starved.requiredBps),
            Format.bitrate(starved.measuredBps),
        ),
        tone = AdvisoryTone.CAUTION,
        primaryLabel = stringResource(R.string.error_action_play_here),
        onPrimary = onPlayHere,
        modifier = modifier,
        secondaryLabel = stringResource(R.string.link_starting_wait),
        onSecondary = onKeepWaiting,
    )
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
                    // This end's own radius. Both seats of this flight are small cards, so
                    // the morph holds the full corner across the whole of it rather than
                    // resolving toward square the way a grid-to-window hero does — without
                    // it the travelling copy is drawn in the overlay with no clip at all
                    // and crosses the screen as a hard rectangle between two rounded seats.
                    .flickSharedFrame(
                        sharedScope = sharedScope,
                        animatedScope = animatedScope,
                        key = CastPosterKey,
                        restCorner = FlickCorners.detailPoster,
                    )
                    .fillMaxSize(),
            )
        }
    }
}

/**
 * Liveness, not progress. The handshake sits in one stage for as long as the TV takes
 * to wake, so a determinate shape would hold still for the whole wait and read as a
 * hang — and the wait is the only thing on screen saying the app has not died. The
 * line above names which of the four protocol steps is in flight; this shape says
 * only that one still is, and it must never imply transcoding, of which there is none.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HandshakeIndicator(stageIndex: Int) {
    val colors = LocalFlickColors.current
    val stages = remember {
        listOf(
            MaterialShapes.Circle,
            MaterialShapes.Cookie4Sided,
            MaterialShapes.Clover4Leaf,
            MaterialShapes.Pill,
        )
    }
    if (rememberReduceMotion()) {
        // A continuous morph never reaches an end state, so reduce motion gets the
        // resting silhouette of the stage instead — it still changes when one lands.
        Box(
            Modifier
                .size(38.dp)
                .clip(stages[stageIndex.coerceIn(stages.indices)].toShape())
                .background(colors.sparkBright),
        )
    } else {
        LoadingIndicator(color = colors.sparkBright, polygons = stages)
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

// The card is deliberately small: it is the file in transit, not the presentation,
// and the whole point is how far it travels when the TV reports its first frame.
private val CastFrameWidth = 152.dp
private val CastFrameHeight = 86.dp

// The same 16:9 still at thumbnail scale, for windows that cannot afford the card at
// full size. It still has to read as the file, so it does not shrink below this.
private val CastFrameCompactWidth = 104.dp
private val CastFrameCompactHeight = 59.dp

// Card, diagram, copy, indicator and Cancel cost roughly 430 dp at full spacing AT
// SCALE 1, and the status pill claims the bottom band; below this the centred column
// would start clipping its own terminal control.
private const val RoomyColumnHeightDp = 520f

/**
 * Whether the window can still afford the full-spacing stack. The figure it is compared
 * against is type-scaled because every line in that stack grows with the user's type
 * size while the dp budget does not: a 540 dp window is roomy at scale 1 and cannot
 * carry the same column at 1.5. A scale below 1 buys nothing back — the spacing is the
 * design's, not the viewport's.
 */
internal fun connectingIsRoomy(viewportHeightDp: Float, fontScale: Float): Boolean =
    viewportHeightDp >= RoomyColumnHeightDp * fontScale.coerceAtLeast(1f)
