package com.flick.sender.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flick.sender.R
import com.flick.sender.net.CastStartState
import com.flick.sender.net.FlickController
import com.flick.sender.net.LinkVerdict
import com.flick.sender.ui.Format
import com.flick.sender.ui.components.AdvisoryCard
import com.flick.sender.ui.components.AdvisoryTone
import com.flick.sender.ui.components.CastPosterKey
import com.flick.sender.ui.components.FlickSubtleButton
import com.flick.sender.ui.components.Hyperspace
import com.flick.sender.ui.components.HyperspaceField
import com.flick.sender.ui.components.LightSpeed
import com.flick.sender.ui.components.StatusKind
import com.flick.sender.ui.components.StatusPill
import com.flick.sender.ui.components.flickSharedFrame
import com.flick.sender.ui.displayName
import com.flick.sender.ui.motion.loopFrameRate
import com.flick.sender.ui.theme.CinemaInkDim
import com.flick.sender.ui.theme.FlickCinematicTheme
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.rememberFlickTouchHaptics
import com.flick.sender.ui.theme.rememberIsResumed
import com.flick.sender.ui.theme.rememberReduceMotion

private enum class StepState { DONE, ACTIVE, PENDING }

/**
 * S5 — connecting, as hyperspace. The screen opens mid-jump: the poster the detail page threw
 * streaks off the top as that page fades over this one, a flash breaks, and the field surges
 * and then keeps cruising for as long as the handshake does; one honest line names the stage.
 */
@Composable
fun ConnectingScreen(
    controller: FlickController,
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    val castStart by controller.castStart.collectAsState()
    val tv by controller.connectedTv.collectAsState()
    val castingItem by controller.castingItem.collectAsState()
    val retryItem by controller.startupRetryItem.collectAsState()
    val item = castingItem ?: retryItem
    val context = LocalContext.current
    val cancelDescription = stringResource(R.string.a11y_cancel_connecting)
    val connectingDescription = stringResource(R.string.a11y_pairing_status, stringResource(R.string.connecting_status))

    // Held as State and narrowed to one boolean here: a verdict republishes with a fresh
    // measurement every second, and this screen is a warp and a moving field that must
    // not be rebuilt to move a number the card reads for itself. There is
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
    // AwaitingFirstFrame alone: acceptance comes before the TV requests its first byte, so
    // no Starved verdict can exist while awaiting it, and naming that state too would only
    // claim one this can never be reached in. A file with no name has no sentence to put
    // in the body, and nothing here is guessed.
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

    // Bound to the handshake's failure, never to arriving here: the route only reaches
    // this screen mid-handshake, so the seeded previous value keeps the first composition
    // silent. Success is confirmed by the shell on the landing itself, which also flashes.
    val haptics = rememberFlickTouchHaptics()
    var lastCastStart by remember { mutableStateOf(castStart) }
    LaunchedEffect(castStart) {
        val previous = lastCastStart
        lastCastStart = castStart
        if (previous == castStart) return@LaunchedEffect
        if (castStart is CastStartState.Failed) haptics.reject()
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

    val filmName = film?.displayName()
    val tvName = tv?.name ?: stringResource(R.string.np_tv_generic)
    val routeLine = filmName?.let { stringResource(R.string.connecting_route, it, tvName) } ?: tvName

    FlickCinematicTheme {
        val colors = LocalFlickColors.current
        val motionScheme = MaterialTheme.motionScheme
        val reduceMotion = rememberReduceMotion()
        val resumed = rememberIsResumed()
        // Saved so a process restored mid-handshake does not make the jump a second time;
        // rotation never gets here, since the activity handles that change itself.
        var warped by rememberSaveable { mutableStateOf(false) }
        // The jump's own clock in ms, read in the draw phase only, so no track of it ever
        // recomposes the screen.
        val jumpMs = Hyperspace.JumpMs.toFloat()
        val jump = remember { Animatable(if (warped || reduceMotion) jumpMs else 0f) }
        LaunchedEffect(Unit) {
            if (!warped && !reduceMotion) {
                jump.animateTo(jumpMs, tween(Hyperspace.JumpMs, easing = LinearEasing))
            } else {
                jump.snapTo(jumpMs)
            }
            warped = true
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(Hyperspace.Edge)) {
            // A short window — landscape, split screen — sheds spacing and then lifts the
            // whole scene, so the copy and Cancel stay above the fold. The vanishing point is
            // derived from where the copy starts, so the poster and the landing seat are
            // always above it.
            val fontScale = LocalDensity.current.fontScale
            val roomy = connectingIsRoomy(maxHeight.value, fontScale)
            val copyGap = if (roomy) CopyGapRoomy else CopyGapCompact
            val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val copyTop = connectingCopyTopDp(maxHeight.value, navInset.value, copyGap.value, fontScale).dp
            val vanishingY = copyTop - copyGap
            val vanishingFraction = if (maxHeight.value > 0f) vanishingY / maxHeight else Hyperspace.VanishingY

            // The peak only for the jump; the wait after it is a slow loop, and the landing is
            // under the shell's own vote for the shared transition.
            HyperspaceField(
                loop = !reduceMotion && resumed,
                intensity = { LightSpeed.field(jump.value) },
                surge = { LightSpeed.surge(jump.value) },
                vanishingFraction = vanishingFraction,
                modifier = Modifier.fillMaxSize().loopFrameRate(!reduceMotion && resumed && !warped),
            )

            // The seat the remote's poster grows out of when the first frame lands: empty,
            // at the vanishing point, so the film comes back out of the warp it went into.
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = vanishingY - LandingSeatHeight / 2)
                    .flickSharedFrame(
                        sharedScope = sharedScope,
                        animatedScope = animatedScope,
                        key = CastPosterKey,
                        restCorner = FlickCorners.detailPoster,
                    )
                    .size(width = LandingSeatWidth, height = LandingSeatHeight),
            )

            // The status pill owns the foot of the window; the copy scrolls rather than
            // pushing Cancel under it. The slow-link card costs this column another ~170 dp,
            // which no lifting of the scene can give back, and the row it would push off is
            // Cancel.
            Box(
                Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .padding(
                        top = copyTop,
                        bottom = StatusPillBand,
                    ),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(slowLinkScroll)
                        .padding(horizontal = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(if (roomy) 18.dp else 10.dp),
                ) {
                    Column(
                        modifier = Modifier.graphicsLayer {
                            val p = LightSpeed.copy(jump.value)
                            alpha = p
                            translationY = Hyperspace.CopyRiseDp.dp.toPx() * (1f - p)
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.connecting_hyperspace_title),
                            style = FlickText.headlineMedium.copy(color = Color.White),
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = routeLine,
                            style = FlickText.bodyLarge.copy(color = CinemaInkDim),
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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

            if (!reduceMotion) {
                // Over everything, the pill included: the light floods the whole window.
                Box(
                    Modifier.fillMaxSize().drawWithCache {
                        val glare = Brush.radialGradient(
                            0f to Hyperspace.FlashCore,
                            1f to Hyperspace.FlashEdge,
                            center = Offset(size.width / 2f, 0f),
                            radius = size.height * Hyperspace.FlashReach,
                        )
                        onDrawBehind {
                            val a = LightSpeed.flash(jump.value)
                            if (a > 0f) drawRect(glare, alpha = a)
                        }
                    },
                )
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

// Room kept above the vanishing point in the roomy budget: half the poster the warp used to
// throw from there. The remote's poster still grows out of the point on the landing, so the
// scene keeps that room rather than moving the copy up under it.
private const val SceneAboveVanishingDp = 78f

// A 16:9 speck at the vanishing point. Only its bounds matter: the remote's poster is what
// is drawn across the flight, so this seat never shows anything of its own.
private val LandingSeatWidth = 32.dp
private val LandingSeatHeight = 18.dp

// Between the vanishing point and the first line of copy: clear of the ring births.
private val CopyGapRoomy = 72.dp
private val CopyGapCompact = 40.dp

// Title, two route lines, the two-line stage and Cancel with full spacing, at type scale 1.
// The slow-link card is not in it: that scrolls.
private const val CopyBudgetDp = 212f

// The vanishing point never rises closer than this to the top: the landing seat is drawn
// around it and must stay in the window.
private const val MinVanishingDp = 24f

// Allowance for a three-button navigation bar under the pill band.
private const val NavAllowanceDp = 48f

// The room above the vanishing point, the full gap, the copy, the pill band and
// a navigation bar, at scale 1. Below this the column would start clipping its own terminal
// control at full spacing.
private val RoomyColumnHeightDp =
    SceneAboveVanishingDp + CopyGapRoomy.value + CopyBudgetDp + StatusPillBand.value + NavAllowanceDp

/**
 * Where the copy starts, in dp from the top: just under the vanishing point at its
 * [Hyperspace.VanishingY] height when there is room, and lifted on a short window so the
 * copy budget, scaled with type, still ends above the pill band and the navigation bar.
 */
internal fun connectingCopyTopDp(
    viewportHeightDp: Float,
    bottomInsetDp: Float,
    gapDp: Float,
    fontScale: Float,
): Float {
    val natural = viewportHeightDp * Hyperspace.VanishingY + gapDp
    val ceiling = viewportHeightDp - StatusPillBand.value - bottomInsetDp -
        CopyBudgetDp * fontScale.coerceAtLeast(1f)
    return minOf(natural, ceiling).coerceAtLeast(gapDp + MinVanishingDp)
}

/**
 * Whether the window can still afford the full-spacing stack. The figure it is compared
 * against is type-scaled because every line in that stack grows with the user's type
 * size while the dp budget does not: a 540 dp window is roomy at scale 1 and cannot
 * carry the same column at 1.5. A scale below 1 buys nothing back — the spacing is the
 * design's, not the viewport's.
 */
internal fun connectingIsRoomy(viewportHeightDp: Float, fontScale: Float): Boolean =
    viewportHeightDp >= RoomyColumnHeightDp * fontScale.coerceAtLeast(1f)
