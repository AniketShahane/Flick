package com.flick.receiver.ui.screens

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.net.PairNetworkFace
import com.flick.receiver.ui.components.FlickPresence
import com.flick.receiver.ui.components.FlickSwap
import com.flick.receiver.ui.components.FlickTvButton
import com.flick.receiver.ui.components.FlickWordmark
import com.flick.receiver.ui.components.FocusBeaconHost
import com.flick.receiver.ui.components.GlassPanel
import com.flick.receiver.ui.components.GlassPanelTone
import com.flick.receiver.ui.components.LiveDot
import com.flick.receiver.ui.components.LocalShellRetained
import com.flick.receiver.ui.components.QrCode
import com.flick.receiver.ui.components.RollingGlyphs
import com.flick.receiver.ui.components.flickRevealEnter
import com.flick.receiver.ui.components.flickRevealExit
import com.flick.receiver.ui.components.landTvFocus
import com.flick.receiver.ui.theme.BrandMark
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickDimens
import com.flick.receiver.ui.theme.FlickIcons
import com.flick.receiver.ui.theme.FlickMotion
import com.flick.receiver.ui.theme.FlickShape
import com.flick.receiver.ui.theme.FlickSpace
import com.flick.receiver.ui.theme.FlickType
import com.flick.receiver.ui.theme.LocalReducedMotion
import com.flick.receiver.ui.theme.pairAmbientBackground
import com.flick.receiver.ui.theme.rememberTvSafeAreaPadding
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

/**
 * What the shell passes as [PairScreen]'s `code` when no code is live — the
 * surface is locked, or standing by. It is a cross-file agreement, so it is named
 * once here rather than spelled at both ends: `ReceiverApp` chooses it and this
 * screen reads it back to decide whether to render the locked notice.
 */
internal const val PairCodePlaceholder = "—"

/**
 * The white code card. It was 310 dp, which spent a third of the 864 dp usable
 * width on a symbol that does not need it: at 248 dp a 29-module payload still
 * draws ~7 dp a module, which a phone camera reads from across a room.
 */
private val QrCardSize = 248.dp

/**
 * Spec §5.1: the QR column is fixed and the content column takes the rest. It is
 * wider than the card it centres because the `flick://` line under the card is
 * what actually sets the minimum: a 15-character host makes 28 monospaced
 * characters, and at the 14 sp floor those plus the glyph and its gap need 260 dp
 * before the line would ellipsize its own port away.
 */
private val QrColumnWidth = 272.dp

/**
 * Reading measure for the instruction copy. Spec §5.1 item 3 said 410 dp, which
 * wrapped the copy to three lines and pushed the whole column past the vertical
 * budget below — the action row is the last child of an unscrolled Column, so the
 * overflow was spent crushing its labels to nothing. At the 18 sp this copy now
 * renders, 500 dp holds its 91 characters to two lines and still stops short of
 * the QR column.
 */
private val PairBodyMaxWidth = 500.dp

/**
 * Above this system font scale the column genuinely cannot fit the 486 dp the
 * safe area leaves, and scrolling is the only honest answer — an unscrolled
 * Column starves its last child instead, which is how the action row once
 * shipped as an empty pill. At or below it the screen fits and must not scroll.
 */
private const val PairScrollFontScale = 1.15f

/**
 * The staged entrance: five children in reading order, each led by a tenth of the
 * run. On the column's spatial spring that is ~45 ms — long enough for the eye to
 * follow the order, short enough that the screen is settled before anyone could
 * act on it.
 *
 * The lockup at the top of the column is deliberately NOT among them. It is the
 * one element pair, idle and the playback chrome all carry, so it is what the
 * shell exchanges these surfaces around: it holds still while the column
 * assembles beneath it, which is the only way a screen-owned entrance can read as
 * something carried across rather than something that arrived with the screen.
 */
private const val PairStageLead = 0.1f
private const val PairStageCount = 5

/** How far a staged child rises into place. */
private val PairStageRise = 12.dp

/** The QR column arrives by scaling rather than rising; it is a plate, not a line. */
private const val PairQrEnterScale = 0.94f

/** Which question the card and the action row are asking. */
private enum class PairMode { Manual, Confirm, Sealed }

/**
 * Everything the READY card draws, captured as one value so an outgoing card keeps
 * rendering what it showed while the incoming one settles over it.
 */
private data class PairCardState(
    val mode: PairMode,
    val host: String,
    val port: Int,
    val spacedCode: String,
    val locked: Boolean,
    val codeExpiresAtElapsedMs: Long?,
    val lockedRetryAtElapsedMs: Long?,
    val saveFailedLabel: String?,
    val confirmDeviceLabel: String?,
    val confirmExpiresAtElapsedMs: Long?,
    val resumeFailed: Boolean,
)

/** The manual card's footer, keyed on [locked] so only a lock or unlock dissolves. */
private data class PairFooterState(
    val locked: Boolean,
    val lockedRetryAtElapsedMs: Long?,
    val codeExpiresAtElapsedMs: Long?,
)

/** Local progress of one staged child, from the column's single entrance driver. */
private fun pairStageProgress(progress: Float, index: Int): Float {
    val span = 1f - PairStageLead * (PairStageCount - 1)
    return ((progress - PairStageLead * index) / span).coerceIn(0f, 1f)
}

/**
 * Entrance for one staged child. `graphicsLayer` only, and the driver is read
 * inside the layer block: the overscan containment tests measure semantics
 * bounds, and a layout offset would move them.
 *
 * [settled] drops the layer once the column has finished arriving. A layer at
 * alpha 1 and zero translation still costs a render node per staged child for as
 * long as the pair screen is up, and this screen is the one the TV sits on for
 * hours — the entrance may not be a permanent tax on the surface it introduced.
 */
private fun Modifier.pairStage(
    progress: () -> Float,
    index: Int,
    settled: Boolean,
    rise: Dp = PairStageRise,
): Modifier = if (settled) {
    this
} else {
    graphicsLayer {
        val stage = pairStageProgress(progress(), index)
        alpha = stage
        translationY = (1f - stage) * rise.toPx()
    }
}

/** The QR plate's entrance — the same stage clock, scaling instead of rising. */
private fun Modifier.pairStageScaled(
    progress: () -> Float,
    index: Int,
    settled: Boolean,
): Modifier = if (settled) {
    this
} else {
    graphicsLayer {
        val stage = pairStageProgress(progress(), index)
        alpha = stage
        val scale = PairQrEnterScale + (1f - PairQrEnterScale) * stage
        scaleX = scale
        scaleY = scale
    }
}

/**
 * T1 · First-run pair (receiver-expressive-spec.md §5.1). Two columns inside the
 * safe area: the content column carries the lockup, the display headline, the
 * instruction copy, the manual-entry card and the listening status; the QR column
 * carries the white code card and the `flick://` line.
 *
 * Every value on screen is real — the code, host and port come from the live
 * binding, and the rotation countdown renders only when the caller supplies
 * [codeExpiresAtElapsedMs] from `PairingSurface.Open`. Nothing here is invented.
 */
@Composable
fun PairScreen(
    tvName: String,
    code: String,
    /**
     * The v4 payload for [code], or null when there is no real binding and no live
     * code to encode — the QR column is then simply not drawn. It carries the code
     * itself, so the caller must re-derive it on every rotation; a symbol built
     * against a consumed code is a symbol that fails to pair.
     */
    qrPayload: String?,
    host: String,
    port: Int,
    onRename: () -> Unit,
    /** Settings is otherwise unreachable while no phone is paired: this is the only route. */
    onOpenSettings: () -> Unit,
    /**
     * Which of the four network states this TV is in — see [pairNetworkFace]. Only
     * [PairNetworkFace.READY] draws a code, a QR or an endpoint; the other three name
     * what is actually missing rather than sharing one card that guesses.
     */
    networkFace: PairNetworkFace,
    /**
     * Whether this TV is still announcing itself over mDNS. False degrades ONE route:
     * the code, the address and the port on this screen are all still real, so the
     * line this draws promises them and never says the TV is offline.
     */
    discoverable: Boolean = true,
    bindUptimeSec: Long = 0L,
    rebindCount: Int = 0,
    lastTeardown: String? = null,
    /**
     * `PairingSurface.Open.expiresAtElapsedMs` — the real rotation deadline on the
     * `SystemClock.elapsedRealtime` timebase. Null means "no TTL is known", and
     * the card then states only that one sender pairs at a time.
     */
    codeExpiresAtElapsedMs: Long? = null,
    /**
     * `PairingSurface.Locked.retryAtElapsedMs` — when a fresh code appears. Same
     * timebase and the same rule as the rotation line: a countdown renders only
     * against a real deadline. Null keeps the flat locked notice.
     */
    lockedRetryAtElapsedMs: Long? = null,
    /**
     * The last Allow this TV could not write to its own storage. The phone was told
     * `denied reason=storage`; the device that actually failed said nothing at all.
     */
    saveFailedLabel: String? = null,
    /** The last Resume press this TV could not write to its own storage. */
    resumeFailed: Boolean = false,
    /**
     * `PairingSurface.Sealed` — the cumulative-failure ceiling has been reached, so
     * no code exists and none will until someone here presses Resume. It is a
     * distinct state from the ordinary lockout on purpose: a lockout ends on its
     * own and a seal does not, and a screen that said the same thing about both
     * would leave a viewer waiting for a countdown that is never coming.
     */
    pairingSealed: Boolean = false,
    onResumePairing: () -> Unit = {},
    /**
     * `PairingSurface.Confirming.deviceLabel` — a phone has presented the right code
     * and this screen is the gate. Null means no decision is pending.
     *
     * The label is chosen on the phone and canonicalised on the wire, so it is
     * single-line and at most 80 code points; it is still someone else's text and is
     * rendered as such (bounded lines, ellipsized) rather than trusted to fit.
     */
    confirmDeviceLabel: String? = null,
    /** The decision deadline on the `elapsedRealtime` timebase. It expires to a denial. */
    confirmExpiresAtElapsedMs: Long? = null,
    onAllowPair: () -> Unit = {},
    onDenyPair: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val safeArea = rememberTvSafeAreaPadding()
    val reducedMotion = LocalReducedMotion.current
    val retained = LocalShellRetained.current
    // Read inside effects that outlive the composition that launched them.
    val retainedNow = rememberUpdatedState(retained)
    val renameFocus = remember { FocusRequester() }
    val resumeFocus = remember { FocusRequester() }
    val denyFocus = remember { FocusRequester() }
    val spacedCode = code.toCharArray().joinToString("  ")
    val locked = code == PairCodePlaceholder
    val mode = when {
        confirmDeviceLabel != null -> PairMode.Confirm
        pairingSealed -> PairMode.Sealed
        else -> PairMode.Manual
    }

    // Exactly one control takes focus on entry, and it is the first in the action
    // row so the D-pad reads left to right from where the ring lands. A seal puts
    // Resume in that position, and re-runs this so the ring follows the one control
    // the screen is now asking for.
    //
    // A confirmation puts DENY there, which is the one place this screen deliberately
    // does not lead with the action it is proposing. The prompt exists because reading
    // this screen is enough to submit a correct code, so the single press the ring is
    // already sitting on has to be the one that admits nobody: a stray OK on a remote
    // that was already being pressed costs a rescan, where the same press on Allow
    // costs a paired phone. Allow is one step right, which is a deliberate act.
    //
    // The row for the new mode is composed while the old one is still fading out, so
    // the request is repeated until the new row reports it holds focus. Keyed on the
    // mode that owns focus, not a plain flag: the outgoing row loses its focus
    // listener on the same frame it gives up focus, and would leave a flag stuck true.
    var focusedRow by remember { mutableStateOf<PairMode?>(null) }
    LaunchedEffect(mode) {
        if (retainedNow.value) return@LaunchedEffect
        val target = when (mode) {
            PairMode.Confirm -> denyFocus
            PairMode.Sealed -> resumeFocus
            PairMode.Manual -> renameFocus
        }
        landTvFocus(target, target) { focusedRow == mode }
    }

    // One driver for the whole staged entrance; each child reads its own slice of
    // it inside a graphicsLayer.
    val entranceSpec: FiniteAnimationSpec<Float> = FlickMotion.panelSpatial()
    val entrance = remember { Animatable(0f) }
    var entranceSettled by remember { mutableStateOf(false) }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) entrance.snapTo(1f) else entrance.animateTo(1f, entranceSpec)
        entranceSettled = true
    }
    val stage = { entrance.value }

    // `transitionSpec` is not a composable lambda, so the scheme specs are resolved
    // here and captured. A card exchange settles in place; the action row only
    // dissolves, because its buttons already carry the travelling ring.
    val sizeSpec = FlickMotion.panelSpatial<IntSize>()
    val cardSwap = if (reducedMotion) {
        FlickMotion.cut()
    } else {
        ContentTransform(
            targetContentEnter = fadeIn(FlickMotion.stateEffects()) + scaleIn(
                initialScale = 0.98f,
                animationSpec = FlickMotion.flickSettleSpatial(),
            ),
            initialContentExit = fadeOut(FlickMotion.fastStateEffects()),
            sizeTransform = SizeTransform(clip = false) { _, _ -> sizeSpec },
        )
    }
    val rowSwap = if (reducedMotion) {
        FlickMotion.cut()
    } else {
        ContentTransform(
            targetContentEnter = fadeIn(FlickMotion.stateEffects()),
            initialContentExit = fadeOut(FlickMotion.fastStateEffects()),
            sizeTransform = SizeTransform(clip = false) { _, _ -> sizeSpec },
        )
    }

    val cardState = PairCardState(
        mode = mode,
        host = host,
        port = port,
        spacedCode = spacedCode,
        locked = locked,
        codeExpiresAtElapsedMs = codeExpiresAtElapsedMs,
        lockedRetryAtElapsedMs = lockedRetryAtElapsedMs,
        saveFailedLabel = saveFailedLabel,
        confirmDeviceLabel = confirmDeviceLabel,
        confirmExpiresAtElapsedMs = confirmExpiresAtElapsedMs,
        resumeFailed = resumeFailed,
    )

    // The QR slot opens before the plate fades in, and the plate fades before the
    // slot has finished closing, so the text column never widens under a lit plate.
    val qrWanted = networkFace == PairNetworkFace.READY && qrPayload != null
    val slot = remember { Animatable(if (qrWanted) 1f else 0f) }
    val panelSpec = rememberUpdatedState(FlickMotion.panelSpatial<Float>())
    LaunchedEffect(qrWanted, reducedMotion) {
        slot.animateTo(if (qrWanted) 1f else 0f, FlickMotion.orSnap(reducedMotion, panelSpec.value))
    }
    val slotOpen by remember { derivedStateOf { slot.value >= 0.9f } }

    val scrollState = rememberScrollState()
    val allowScroll = LocalDensity.current.fontScale > PairScrollFontScale

    Box(
        modifier = modifier
            .fillMaxSize()
            .pairAmbientBackground(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(safeArea)
                // The content column is NOT scrollable at ordinary text sizes — a
                // 10-foot pair screen must read in one glance, and the column is
                // sized to fit the safe area's 486 dp. Above PairScrollFontScale
                // the enlarged system font is an accessibility request that cannot
                // be met by shrinking, and scrolling beats starving the action row.
                .then(if (allowScroll) Modifier.verticalScroll(scrollState) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The start/bottom inset is the detached focus ring's clearance.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(
                        start = FlickDimens.FocusRingReserve,
                        bottom = FlickDimens.FocusRingReserve,
                    ),
                verticalArrangement = Arrangement.spacedBy(FlickSpace.Sm),
            ) {
                FlickWordmark(
                    // A persistent header lockup, not a headline: it identifies the
                    // room, and the pairing headline under it carries the message.
                    // The constant — held, never staged; see [PairStageCount].
                    markSize = 30.dp,
                    textSizeSp = 18,
                    eyebrow = stringResource(
                        R.string.receiver_eyebrow,
                        // The composition's locale, not the process default: casing
                        // is language-specific (Turkish dotted I is the standard
                        // example) and `Locale.getDefault()` is not observable, so a
                        // language change would leave this reading in the old one.
                        tvName.uppercase(LocalLocale.current.platformLocale),
                    ),
                )
                // The headline follows the question. While a decision is pending
                // "Scan to flick from your phone" is an instruction for a code that
                // no longer exists — it was consumed proving itself.
                FlickSwap(
                    target = if (mode == PairMode.Confirm) R.string.pair_confirm_title else R.string.pair_title,
                    resize = true,
                    modifier = Modifier.pairStage(stage, index = 0, settled = entranceSettled),
                    label = "pairHeadline",
                ) { res ->
                    Text(
                        text = stringResource(res),
                        style = FlickType.display(sizeSp = 40),
                        color = Color.White,
                    )
                }
                FlickSwap(
                    target = confirmDeviceLabel.orEmpty(),
                    resize = true,
                    modifier = Modifier
                        .widthIn(max = PairBodyMaxWidth)
                        .pairStage(stage, index = 1, settled = entranceSettled),
                    label = "pairInstructions",
                ) { label ->
                    Text(
                        text = if (label.isNotEmpty()) {
                            AnnotatedString(stringResource(R.string.pair_confirm_instructions, label))
                        } else {
                            highlightedInstructions()
                        },
                        style = FlickType.body(sizeSp = 18),
                        color = FlickColor.OnSurfaceDim,
                        // Someone else's device name is inside this line, so it is
                        // bounded here rather than trusted to be short.
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                AnimatedContent(
                    targetState = networkFace,
                    transitionSpec = { cardSwap },
                    label = "pairNetworkState",
                ) { face ->
                    if (face == PairNetworkFace.READY) {
                        // Renders only from its argument, so the outgoing card keeps
                        // what it showed — a spent code stays on the manual card as
                        // it fades rather than flipping to the placeholder.
                        AnimatedContent(
                            targetState = cardState,
                            contentKey = { it.mode },
                            transitionSpec = { cardSwap },
                            label = "pairMode",
                        ) { card ->
                            Column(
                                // The outgoing card leaves the accessibility tree on its first exit frame.
                                modifier = if (transition.targetState != EnterExitState.Visible) {
                                    Modifier.clearAndSetSemantics { }
                                } else {
                                    Modifier
                                },
                                verticalArrangement = Arrangement.spacedBy(FlickSpace.Sm),
                            ) {
                                when (card.mode) {
                                    // The manual-entry card and the listening line are
                                    // both withheld, as they are under a seal, and for
                                    // the same reason: the code they describe has already
                                    // been spent, and nothing is listening for another.
                                    PairMode.Confirm -> PairConfirmCard(
                                        deviceLabel = card.confirmDeviceLabel.orEmpty(),
                                        expiresAtElapsedMs = card.confirmExpiresAtElapsedMs,
                                        modifier = Modifier.pairStage(stage, index = 2, settled = entranceSettled),
                                    )
                                    // The manual-entry card and the listening line are
                                    // both withheld here, and both for the same reason:
                                    // there is no code to type into a card that shows
                                    // one, and nothing is listening for one either.
                                    PairMode.Sealed -> PairingSealedCard(
                                        resumeFailed = card.resumeFailed,
                                        modifier = Modifier.pairStage(stage, index = 2, settled = entranceSettled),
                                    )
                                    PairMode.Manual -> {
                                        ManualEntryCard(
                                            host = card.host,
                                            port = card.port,
                                            spacedCode = card.spacedCode,
                                            locked = card.locked,
                                            codeExpiresAtElapsedMs = card.codeExpiresAtElapsedMs,
                                            lockedRetryAtElapsedMs = card.lockedRetryAtElapsedMs,
                                            saveFailedLabel = card.saveFailedLabel,
                                            modifier = Modifier.pairStage(stage, index = 2, settled = entranceSettled),
                                        )
                                        Row(
                                            modifier = Modifier.pairStage(stage, index = 3, settled = entranceSettled),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            LiveDot(color = FlickColor.Live, size = 7.dp, pulsing = !retained)
                                            Text(
                                                text = stringResource(R.string.pair_listening),
                                                style = FlickType.body(sizeSp = 16, weight = FontWeight.Bold),
                                                color = FlickColor.OnSurfaceSoft,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        PairNetworkCard(
                            face = face,
                            modifier = Modifier.pairStage(stage, index = 2, settled = entranceSettled),
                        )
                    }
                }

                // The actions are one beacon group, so the ring slides across rather
                // than jumping — including onto Resume, which joins the row only
                // while the surface is sealed. The host carries the stage layer, so
                // the ring fades and rises with the row it belongs to.
                FocusBeaconHost(modifier = Modifier.pairStage(stage, index = 3, settled = entranceSettled)) {
                    AnimatedContent(
                        targetState = mode,
                        transitionSpec = { rowSwap },
                        label = "pairActions",
                    ) { m ->
                        // The outgoing row is inert from its first exit frame, so a
                        // stray OK during the exchange activates nothing.
                        //
                        // focusProperties blocks run from the focus target outward and
                        // the outermost assignment wins, so this gate may only clear
                        // canFocus: assigning `true` would undo an inner guard, and an
                        // outer container that assigned `true` would undo this one.
                        val live = transition.targetState == EnterExitState.Visible
                        Row(
                            modifier = Modifier
                                .focusProperties { if (!live) canFocus = false }
                                .then(
                                    if (live) {
                                        Modifier.onFocusChanged {
                                            if (it.hasFocus) {
                                                focusedRow = m
                                            } else if (focusedRow == m) {
                                                focusedRow = null
                                            }
                                        }
                                    } else {
                                        Modifier.clearAndSetSemantics { }
                                    },
                                ),
                            horizontalArrangement = Arrangement.spacedBy(FlickSpace.Md),
                        ) {
                            if (m == PairMode.Confirm) {
                                // Deny first, so it is where the ring lands, and Allow
                                // second. Rename and Settings are withheld: while this
                                // question is open the D-pad has exactly two answers, and
                                // a row that let the remote wander off to rename the TV
                                // would be inviting the decision to expire instead.
                                FlickTvButton(
                                    onClick = onDenyPair,
                                    focusRequester = denyFocus.takeIf { live },
                                    contentPadding = FlickDimens.ControlPadding,
                                ) {
                                    Text(
                                        text = stringResource(R.string.pair_confirm_deny),
                                        style = FlickType.body(sizeSp = 16, weight = FontWeight.Bold),
                                        color = FlickColor.OnSurface,
                                    )
                                }
                                FlickTvButton(
                                    onClick = onAllowPair,
                                    containerColor = FlickColor.ControlFillStrong,
                                    contentPadding = FlickDimens.ControlPadding,
                                ) {
                                    Icon(
                                        imageVector = FlickIcons.CheckCircle,
                                        contentDescription = null,
                                        tint = FlickColor.Live,
                                        modifier = Modifier.size(FlickDimens.GlyphSmall),
                                    )
                                    Text(
                                        text = stringResource(R.string.pair_confirm_allow),
                                        style = FlickType.body(sizeSp = 16, weight = FontWeight.Bold),
                                        color = FlickColor.OnSurface,
                                    )
                                }
                            }
                            // The only way back to a live code, and it is here rather
                            // than on the network on purpose: reopening the surface has
                            // to cost physical presence in this room.
                            if (m == PairMode.Sealed) {
                                FlickTvButton(
                                    onClick = onResumePairing,
                                    focusRequester = resumeFocus.takeIf { live },
                                    contentPadding = FlickDimens.ControlPadding,
                                ) {
                                    Text(
                                        text = stringResource(R.string.pair_sealed_resume),
                                        style = FlickType.body(sizeSp = 16, weight = FontWeight.Bold),
                                        color = FlickColor.OnSurface,
                                    )
                                }
                            }
                            if (m != PairMode.Confirm) {
                                FlickTvButton(
                                    onClick = onRename,
                                    // Sealed and Manual both carry Rename, and during
                                    // that exchange only the incoming row may own it.
                                    focusRequester = renameFocus.takeIf { live },
                                    contentPadding = FlickDimens.ControlPadding,
                                ) {
                                    Text(
                                        text = stringResource(R.string.pair_rename),
                                        style = FlickType.body(sizeSp = 16),
                                        color = FlickColor.OnSurface,
                                    )
                                }
                                FlickTvButton(
                                    onClick = onOpenSettings,
                                    contentPadding = FlickDimens.ControlPadding,
                                ) {
                                    Text(
                                        text = stringResource(R.string.pair_settings),
                                        style = FlickType.body(sizeSp = 16),
                                        color = FlickColor.OnSurfaceDim,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // The slot is sized from the column's fixed width, not from what is
            // measured in it, and sits outside the presence: while the plate is absent
            // nothing is composed to measure, and the slot must open before it arrives.
            Box(
                modifier = Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val full = (QrColumnWidth + FlickSpace.Xl).roundToPx()
                    // The spring rings past both ends; a width may not go negative.
                    val open = slot.value.coerceIn(0f, 1f)
                    layout((full * open).roundToInt(), placeable.height) {
                        placeable.placeRelative(0, 0)
                    }
                },
            ) {
                FlickPresence(
                    value = qrPayload.takeIf { qrWanted && slotOpen },
                    scaleFrom = PairQrEnterScale,
                    label = "qrColumn",
                ) { payload ->
                    QrColumn(
                        payload = payload,
                        host = host,
                        port = port,
                        discoverable = discoverable,
                        bindUptimeSec = bindUptimeSec,
                        rebindCount = rebindCount,
                        lastTeardown = lastTeardown,
                        // The gap to the text column lives inside the slot, so it
                        // collapses with it.
                        modifier = Modifier
                            .padding(start = FlickSpace.Xl)
                            .pairStageScaled(stage, index = 4, settled = entranceSettled),
                    )
                }
            }
        }
    }
}

/**
 * Spec §5.1 item 4. `Surface` fill, 20 dp radius, `GlassBorder` hairline — it does
 * not sit over moving video, so it is the opaque [GlassPanelTone.Solid] cut.
 */
@Composable
private fun ManualEntryCard(
    host: String,
    port: Int,
    spacedCode: String,
    locked: Boolean,
    codeExpiresAtElapsedMs: Long?,
    lockedRetryAtElapsedMs: Long?,
    saveFailedLabel: String?,
    modifier: Modifier = Modifier,
) {
    GlassPanel(
        modifier = modifier.fillMaxWidth(),
        shape = FlickShape.Xl,
        tone = GlassPanelTone.Solid,
        // Tighter than FlickDimens.PanelPadding: this is the secondary path on the
        // screen and three label-over-value rows are all it holds, so the panel is
        // sized to that content rather than to the shared card inset.
        contentPadding = PaddingValues(horizontal = FlickSpace.Md, vertical = FlickSpace.Sm),
        verticalArrangement = Arrangement.spacedBy(FlickSpace.Sm),
        // `Modifier.pairStage` at the call site already drives this card's alpha and
        // rise off the staged entrance clock; the panel's own latch would fade it twice.
        animateEntrance = false,
    ) {
        Text(
            text = stringResource(R.string.pair_manual_eyebrow),
            style = FlickType.monoEyebrow(trackingEm = 0.2f),
            color = FlickColor.OnSurfaceFaint,
        )
        // Above the fields, because the fresh code below it is the resolution: the
        // press succeeded, the write did not, and a new code is already on screen.
        //
        // Retained past its own dismissal so the exit has something to draw.
        var retainedSaveFailed by remember { mutableStateOf(saveFailedLabel) }
        LaunchedEffect(saveFailedLabel) {
            if (saveFailedLabel != null) retainedSaveFailed = saveFailedLabel
        }
        AnimatedVisibility(
            visible = saveFailedLabel != null,
            enter = flickRevealEnter(),
            exit = flickRevealExit(),
            label = "pairSaveFailed",
        ) {
            (saveFailedLabel ?: retainedSaveFailed)?.let { label ->
                Column(verticalArrangement = Arrangement.spacedBy(FlickSpace.Sm)) {
                    Text(
                        text = stringResource(R.string.pair_save_failed_title),
                        style = FlickType.body(sizeSp = 16, weight = FontWeight.Bold),
                        color = FlickColor.Caution,
                    )
                    Text(
                        // Someone else's device name, so it is bounded here rather
                        // than trusted to be short.
                        text = stringResource(R.string.pair_save_failed_detail, label),
                        style = FlickType.body(sizeSp = 14),
                        color = FlickColor.OnSurfaceDim,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        // Sized so a 15-character host, the port and the spaced code all clear the
        // 542 dp the content column leaves beside the 272 dp QR column.
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ManualField(
                label = stringResource(R.string.pair_manual_ip_label),
                value = host,
                valueSizeSp = 18,
                valueColor = FlickColor.OnSurface,
            )
            FieldDivider()
            ManualField(
                label = stringResource(R.string.pair_manual_port_label),
                value = port.toString(),
                valueSizeSp = 18,
                valueColor = FlickColor.OnSurface,
            )
            FieldDivider()
            ManualField(
                // The one value a viewer types from memory, so it stays a step
                // above the endpoint beside it.
                label = stringResource(R.string.pair_manual_code_label),
                labelColor = FlickColor.SparkBright,
                value = spacedCode,
                valueSizeSp = 20,
                valueColor = FlickColor.Spark,
                rolls = true,
            )
        }
        FlickSwap(
            target = PairFooterState(locked, lockedRetryAtElapsedMs, codeExpiresAtElapsedMs),
            contentKey = { it.locked },
            label = "pairFooter",
        ) { footer ->
            if (footer.locked) {
                // The countdown renders against the lockout's own real deadline, on the
                // same timebase [rotationLine] already uses — so this obeys the screen's
                // rule rather than bending it. The flat line stands in only when there is
                // no deadline to render, because the lockouts it used to cover run from
                // 30 s to eight minutes and "shortly" was true of neither end.
                Text(
                    text = if (footer.lockedRetryAtElapsedMs == null) {
                        stringResource(R.string.pair_locked)
                    } else {
                        stringResource(R.string.pair_locked_countdown, countdown(footer.lockedRetryAtElapsedMs))
                    },
                    style = FlickType.body(sizeSp = 16),
                    color = FlickColor.Caution,
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Icon(
                        imageVector = FlickIcons.Timer,
                        contentDescription = null,
                        tint = FlickColor.OnSurfaceFaint,
                        modifier = Modifier.size(FlickDimens.GlyphSmall),
                    )
                    Text(
                        text = rotationLine(footer.codeExpiresAtElapsedMs),
                        style = FlickType.monoEyebrow(trackingEm = 0.14f),
                        color = FlickColor.OnSurfaceFaint,
                        // Uppercased mono at this tracking is wide: a second line here
                        // orphans a word AND overflows the column budget above.
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Label over value — the §5.5 stat-cell treatment, reused for the manual fields. */
@Composable
private fun ManualField(
    label: String,
    value: String,
    valueSizeSp: Int,
    valueColor: Color,
    labelColor: Color = FlickColor.OnSurfaceMuted,
    /** Only the pairing code rolls; an address and a port are not events. */
    rolls: Boolean = false,
) {
    val valueStyle = FlickType.monoTabular(sizeSp = valueSizeSp, weight = FontWeight.SemiBold)
    Column(verticalArrangement = Arrangement.spacedBy(FlickSpace.Xs)) {
        Text(
            text = label.uppercase(LocalLocale.current.platformLocale),
            style = FlickType.monoEyebrow(trackingEm = 0.14f),
            color = labelColor,
        )
        if (rolls) {
            RollingGlyphs(text = value, style = valueStyle, color = valueColor)
        } else {
            Text(text = value, style = valueStyle, color = valueColor, maxLines = 1)
        }
    }
}

@Composable
private fun FieldDivider() {
    Box(
        modifier = Modifier
            .width(FlickDimens.Hairline)
            .fillMaxHeight()
            .background(FlickColor.OutlineHairline),
    )
}

/**
 * The cumulative-failure ceiling, said out loud.
 *
 * A surface that simply stopped accepting codes would be indistinguishable from a
 * broken app to the one person most likely to have caused it — someone who
 * mistyped. So this states the cause, states that nothing is being accepted, and
 * names the control that undoes it, which is sitting in the action row directly
 * below. It draws no endpoint and no code because there is no longer either.
 */
@Composable
private fun PairingSealedCard(resumeFailed: Boolean, modifier: Modifier = Modifier) {
    GlassPanel(
        modifier = modifier.fillMaxWidth(),
        shape = FlickShape.Xl,
        tone = GlassPanelTone.Solid,
        contentPadding = FlickDimens.PanelPadding,
        verticalArrangement = Arrangement.spacedBy(FlickSpace.Sm),
        // As with the other two cards in this column: `Modifier.pairStage` at the
        // call site already owns the entrance, and the panel's own latch would
        // fade it a second time.
        animateEntrance = false,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = FlickIcons.Private,
                contentDescription = null,
                tint = FlickColor.Caution,
                modifier = Modifier.size(FlickDimens.GlyphMedium),
            )
            Text(
                text = stringResource(R.string.pair_sealed_title),
                style = FlickType.display(sizeSp = 22),
                color = FlickColor.OnSurface,
            )
        }
        Text(
            text = stringResource(R.string.pair_sealed_body),
            style = FlickType.body(sizeSp = 16),
            color = FlickColor.OnSurfaceDim,
        )
        // The Resume key below is inert until a restart clears the write, and the seal
        // above promises "pairing stays closed until you resume it here" — so silence
        // here leaves a viewer pressing a button that has already failed. `!surfaceSealed`
        // is unreachable from that key, which is what makes the storage claim provable.
        AnimatedVisibility(
            visible = resumeFailed,
            enter = flickRevealEnter(),
            exit = flickRevealExit(),
            label = "pairResumeFailed",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(FlickSpace.Sm)) {
                Text(
                    text = stringResource(R.string.pair_resume_failed_title),
                    style = FlickType.body(sizeSp = 16, weight = FontWeight.Bold),
                    color = FlickColor.Caution,
                )
                Text(
                    text = stringResource(R.string.pair_resume_failed_detail),
                    style = FlickType.body(sizeSp = 14),
                    color = FlickColor.OnSurfaceDim,
                )
            }
        }
    }
}

/**
 * "Allow this phone?" — the one thing on this screen that a phone on the network
 * cannot cause to happen by itself.
 *
 * It names the phone, states plainly what allowing it grants, and draws the decision
 * deadline. The countdown is here rather than left implicit because the prompt
 * genuinely does go away: it expires to a REFUSAL, and a card that vanished with no
 * warning would read as a bug to the one person it is asking.
 */
@Composable
private fun PairConfirmCard(
    deviceLabel: String,
    expiresAtElapsedMs: Long?,
    modifier: Modifier = Modifier,
) {
    GlassPanel(
        modifier = modifier.fillMaxWidth(),
        shape = FlickShape.Xl,
        tone = GlassPanelTone.Solid,
        contentPadding = FlickDimens.PanelPadding,
        verticalArrangement = Arrangement.spacedBy(FlickSpace.Sm),
        // As with every other card in this column: `Modifier.pairStage` at the call
        // site already owns the entrance, and the panel's own latch would fade it
        // a second time.
        animateEntrance = false,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = FlickIcons.Cast,
                contentDescription = null,
                tint = FlickColor.SparkBright,
                modifier = Modifier.size(FlickDimens.GlyphMedium),
            )
            Text(
                // The phone's own name, and the only place on this screen it appears
                // at display size. It is chosen on the phone, so it is held to one
                // line and ellipsized rather than allowed to set this card's height.
                text = deviceLabel,
                style = FlickType.display(sizeSp = 22),
                color = FlickColor.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = stringResource(R.string.pair_confirm_body),
            style = FlickType.body(sizeSp = 16),
            color = FlickColor.OnSurfaceDim,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(
                imageVector = FlickIcons.Timer,
                contentDescription = null,
                tint = FlickColor.Caution,
                modifier = Modifier.size(FlickDimens.GlyphSmall),
            )
            Text(
                text = confirmDeadlineLine(expiresAtElapsedMs),
                style = FlickType.monoEyebrow(trackingEm = 0.14f),
                color = FlickColor.Caution,
                maxLines = 1,
            )
        }
    }
}

/**
 * Why this screen is not offering a code: no address at all, an address Flick cannot
 * use, or an address with no port behind it.
 *
 * Three cards rather than one, because they had one and it guessed. A TV that had just
 * returned a site-local address and then failed every bind was told to "Connect this TV
 * to your home network" — the one thing it had provably already done, in the state
 * where restarting Flick is what actually helps.
 */
@Composable
private fun PairNetworkCard(face: PairNetworkFace, modifier: Modifier = Modifier) {
    GlassPanel(
        modifier = modifier.fillMaxWidth(),
        shape = FlickShape.Xl,
        tone = GlassPanelTone.Solid,
        contentPadding = FlickDimens.PanelPadding,
        verticalArrangement = Arrangement.spacedBy(FlickSpace.Sm),
        // Same as the manual-entry card: `Modifier.pairStage` at the call site is
        // already the entrance, and the `pairNetworkState` AnimatedContent owns the
        // swap between the states.
        animateEntrance = false,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = FlickIcons.Wifi,
                contentDescription = null,
                tint = FlickColor.Caution,
                modifier = Modifier.size(FlickDimens.GlyphMedium),
            )
            Text(
                text = stringResource(
                    when (face) {
                        PairNetworkFace.NO_BIND -> R.string.pair_bind_failed_title
                        PairNetworkFace.NOT_SITE_LOCAL -> R.string.pair_not_site_local_title
                        else -> R.string.pair_waiting_network_title
                    },
                ),
                style = FlickType.display(sizeSp = 22),
                color = FlickColor.OnSurface,
            )
        }
        Text(
            text = stringResource(
                when (face) {
                    PairNetworkFace.NO_BIND -> R.string.pair_bind_failed_detail
                    PairNetworkFace.NOT_SITE_LOCAL -> R.string.pair_not_site_local_detail
                    else -> R.string.pair_waiting_network_detail
                },
            ),
            style = FlickType.body(sizeSp = 16),
            color = FlickColor.OnSurfaceDim,
        )
    }
}

/** Spec §5.1 right column: the white QR card, the endpoint line, and bind health. */
@Composable
private fun QrColumn(
    payload: String,
    host: String,
    port: Int,
    discoverable: Boolean,
    bindUptimeSec: Long,
    rebindCount: Int,
    lastTeardown: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.width(QrColumnWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FlickSpace.Md),
    ) {
        QrCode(
            payload = payload,
            size = QrCardSize,
            // The quiet zone is a ratio, not a constant: it came down with the
            // card so the symbol keeps the same share of the white plate.
            quietZonePadding = 18.dp,
            contentDescription = stringResource(R.string.pair_qr_content_description),
            shape = FlickShape.Hero,
            // All three finder eyes are now one dark ink so the symbol binarizes,
            // which leaves the mark's streaks as the only amber in the code. They
            // are pinned here rather than inherited: the plate sits inside the area
            // the error correction already spends, so it is the one place amber can
            // live without costing a scanner the symbol.
            centerOverlay = { markSize ->
                BrandMark(
                    size = markSize,
                    tint = FlickColor.Primary,
                    streakTint = FlickColor.Spark,
                )
            },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = FlickIcons.Wifi,
                contentDescription = null,
                tint = FlickColor.OnSurfaceMuted,
                modifier = Modifier.size(FlickDimens.GlyphSmall),
            )
            Text(
                text = stringResource(R.string.pair_url, host, port),
                style = FlickType.monoTabular(sizeSp = 14, weight = FontWeight.SemiBold),
                color = FlickColor.OnSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Bind health: a stale port reads differently from a wrong code.
        Text(
            text = stringResource(R.string.pair_bind_health, bindUptimeSec, rebindCount) +
                (lastTeardown?.let { " · " + stringResource(R.string.pair_bind_last_teardown, it) } ?: ""),
            style = FlickType.monoTabular(sizeSp = 14, weight = FontWeight.Medium),
            color = FlickColor.OnSurfaceFaint,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // A line beside the bind readout rather than a card: discovery is ONE route to
        // this TV and the two on this screen — the symbol above and the code beside it —
        // are both still real, so this degrades nothing and blocks nothing.
        AnimatedVisibility(
            visible = !discoverable,
            enter = flickRevealEnter(),
            exit = flickRevealExit(),
            label = "pairNotDiscoverable",
        ) {
            Text(
                text = stringResource(R.string.pair_not_discoverable),
                style = FlickType.body(sizeSp = 14),
                color = FlickColor.OnSurfaceFaint,
            )
        }
    }
}

/** The design's "Flick" pick-out — the highlighted word comes from resources. */
@Composable
private fun highlightedInstructions(): AnnotatedString {
    val copy = stringResource(R.string.pair_instructions)
    val highlight = stringResource(R.string.pair_instructions_highlight)
    return remember(copy, highlight) {
        val at = if (highlight.isEmpty()) -1 else copy.indexOf(highlight)
        buildAnnotatedString {
            if (at < 0) {
                append(copy)
            } else {
                append(copy.substring(0, at))
                withStyle(SpanStyle(color = FlickColor.SparkBright)) { append(highlight) }
                append(copy.substring(at + highlight.length))
            }
        }
    }
}

/**
 * The rotation line. A countdown renders ONLY against a real TTL deadline; with
 * no deadline the card states the honest half of the design's line and no timer.
 */
@Composable
private fun rotationLine(expiresAtElapsedMs: Long?): String {
    var remainingSec by remember(expiresAtElapsedMs) {
        mutableStateOf(expiresAtElapsedMs?.let(::remainingSeconds) ?: 0L)
    }
    LaunchedEffect(expiresAtElapsedMs) {
        if (expiresAtElapsedMs == null) return@LaunchedEffect
        while (true) {
            remainingSec = remainingSeconds(expiresAtElapsedMs)
            delay(1_000L)
        }
    }
    val text = if (expiresAtElapsedMs == null) {
        stringResource(R.string.pair_one_sender)
    } else {
        stringResource(
            R.string.pair_code_rotates,
            String.format(Locale.US, "%d:%02d", remainingSec / 60L, remainingSec % 60L),
        )
    }
    return text.uppercase(LocalLocale.current.platformLocale)
}

private fun remainingSeconds(expiresAtElapsedMs: Long): Long =
    ((expiresAtElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L) + 999L) / 1000L

/**
 * An m:ss countdown to a real `elapsedRealtime` deadline, ticking once a second.
 *
 * m:ss and never a spoken duration: the lockout it renders runs from 30 s to eight
 * minutes, and one phrase covering both ends is what "try again shortly" already was.
 */
@Composable
private fun countdown(deadlineElapsedMs: Long): String {
    var remainingSec by remember(deadlineElapsedMs) {
        mutableStateOf(remainingSeconds(deadlineElapsedMs))
    }
    LaunchedEffect(deadlineElapsedMs) {
        while (true) {
            remainingSec = remainingSeconds(deadlineElapsedMs)
            delay(1_000L)
        }
    }
    return String.format(Locale.US, "%d:%02d", remainingSec / 60L, remainingSec % 60L)
}

/**
 * The confirmation deadline, in whole seconds. Same one-second tick as
 * [rotationLine], and the same rule: a countdown renders only against a real
 * deadline, and with no deadline the line states the outcome without inventing a
 * clock for it.
 */
@Composable
private fun confirmDeadlineLine(expiresAtElapsedMs: Long?): String {
    var remainingSec by remember(expiresAtElapsedMs) {
        mutableStateOf(expiresAtElapsedMs?.let(::remainingSeconds) ?: 0L)
    }
    LaunchedEffect(expiresAtElapsedMs) {
        if (expiresAtElapsedMs == null) return@LaunchedEffect
        while (true) {
            remainingSec = remainingSeconds(expiresAtElapsedMs)
            delay(1_000L)
        }
    }
    val text = if (expiresAtElapsedMs == null) {
        stringResource(R.string.pair_confirm_deadline_unknown)
    } else {
        stringResource(R.string.pair_confirm_deadline, remainingSec)
    }
    return text.uppercase(LocalLocale.current.platformLocale)
}
