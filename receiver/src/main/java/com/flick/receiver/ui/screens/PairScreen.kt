package com.flick.receiver.ui.screens

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.ui.components.FlickTvButton
import com.flick.receiver.ui.components.FlickWordmark
import com.flick.receiver.ui.components.FocusBeaconHost
import com.flick.receiver.ui.components.GlassPanel
import com.flick.receiver.ui.components.GlassPanelTone
import com.flick.receiver.ui.components.LiveDot
import com.flick.receiver.ui.components.QrCode
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
import com.flick.receiver.ui.theme.tvOverscanSafeArea
import kotlinx.coroutines.delay
import java.util.Locale

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
 * The staged entrance: six children in reading order, each led by a tenth of the
 * run. On the column's spatial spring that is ~45 ms — long enough for the eye to
 * follow the order, short enough that the screen is settled before anyone could
 * act on it.
 */
private const val PairStageLead = 0.1f
private const val PairStageCount = 6

/** How far a staged child rises into place. */
private val PairStageRise = 12.dp

/** The QR column arrives by scaling rather than rising; it is a plate, not a line. */
private const val PairQrEnterScale = 0.94f

/** Local progress of one staged child, from the column's single entrance driver. */
private fun pairStageProgress(progress: Float, index: Int): Float {
    val span = 1f - PairStageLead * (PairStageCount - 1)
    return ((progress - PairStageLead * index) / span).coerceIn(0f, 1f)
}

/**
 * Entrance for one staged child. `graphicsLayer` only, and the driver is read
 * inside the layer block: the overscan containment tests measure semantics
 * bounds, and a layout offset would move them.
 */
private fun Modifier.pairStage(
    progress: () -> Float,
    index: Int,
    rise: Dp = PairStageRise,
): Modifier = graphicsLayer {
    val stage = pairStageProgress(progress(), index)
    alpha = stage
    translationY = (1f - stage) * rise.toPx()
}

/** The QR plate's entrance — the same stage clock, scaling instead of rising. */
private fun Modifier.pairStageScaled(progress: () -> Float, index: Int): Modifier = graphicsLayer {
    val stage = pairStageProgress(progress(), index)
    alpha = stage
    val scale = PairQrEnterScale + (1f - PairQrEnterScale) * stage
    scaleX = scale
    scaleY = scale
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
    /** Null while no real binding exists; the QR column is then simply not drawn. */
    qrPayload: String?,
    host: String,
    port: Int,
    onRename: () -> Unit,
    networkReady: Boolean,
    bindUptimeSec: Long = 0L,
    rebindCount: Int = 0,
    lastTeardown: String? = null,
    /**
     * `PairingSurface.Open.expiresAtElapsedMs` — the real rotation deadline on the
     * `SystemClock.elapsedRealtime` timebase. Null means "no TTL is known", and
     * the card then states only that one sender pairs at a time.
     */
    codeExpiresAtElapsedMs: Long? = null,
    modifier: Modifier = Modifier,
) {
    val safeArea = rememberTvSafeAreaPadding()
    val reducedMotion = LocalReducedMotion.current
    var bigCode by remember { mutableStateOf(false) }
    // Exit content remains composed long enough to fade. Keep the background
    // hidden from accessibility until that retained modal subtree is gone too.
    val codeOverlayState = remember { MutableTransitionState(false) }
    codeOverlayState.targetState = bigCode
    val codeOverlayBlocking = codeOverlayState.currentState || codeOverlayState.targetState
    val showBiggerFocus = remember { FocusRequester() }
    val doneFocus = remember { FocusRequester() }
    val spacedCode = code.toCharArray().joinToString("  ")
    val locked = code == "—"

    // The overlay's LOGICAL state gates input, not the retained exit subtree:
    // waiting for the fade to finish left the remote doing nothing at all for the
    // length of a chrome fade after "Done" was pressed.
    LaunchedEffect(bigCode) {
        if (bigCode) {
            runCatching { doneFocus.requestFocus() }
        } else {
            runCatching { showBiggerFocus.requestFocus() }
        }
    }
    // Back stays claimed through the fade as well, so a second press during the
    // exit cannot fall through to the Activity and background the app.
    BackHandler(enabled = bigCode || codeOverlayBlocking) { bigCode = false }

    // One driver for the whole staged entrance; each child reads its own slice of
    // it inside a graphicsLayer.
    val entranceSpec: FiniteAnimationSpec<Float> = FlickMotion.panelSpatial()
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) entrance.snapTo(1f) else entrance.animateTo(1f, entranceSpec)
    }
    val stage = { entrance.value }

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
                .then(if (allowScroll) Modifier.verticalScroll(scrollState) else Modifier)
                .focusProperties { canFocus = !bigCode }
                .then(
                    if (codeOverlayBlocking) Modifier.clearAndSetSemantics { }
                    else Modifier
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FlickSpace.Xl),
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
                    markSize = 30.dp,
                    textSizeSp = 18,
                    eyebrow = stringResource(
                        R.string.receiver_eyebrow,
                        tvName.uppercase(Locale.getDefault()),
                    ),
                    modifier = Modifier.pairStage(stage, index = 0),
                )
                Text(
                    text = stringResource(R.string.pair_title),
                    style = FlickType.display(sizeSp = 40),
                    color = Color.White,
                    modifier = Modifier.pairStage(stage, index = 1),
                )
                Text(
                    text = highlightedInstructions(),
                    style = FlickType.body(sizeSp = 18),
                    color = FlickColor.OnSurfaceDim,
                    modifier = Modifier
                        .widthIn(max = PairBodyMaxWidth)
                        .pairStage(stage, index = 2),
                )

                // `transitionSpec` is not a composable lambda, so the scheme specs
                // are resolved here and captured.
                val networkTransform = if (reducedMotion) {
                    fadeIn(tween(durationMillis = 0)).togetherWith(fadeOut(tween(durationMillis = 0)))
                } else {
                    (fadeIn(FlickMotion.stateEffects()) + scaleIn(
                        initialScale = 0.98f,
                        animationSpec = FlickMotion.flickSettleSpatial(),
                    )).togetherWith(fadeOut(FlickMotion.chromeFadeOut()))
                }
                AnimatedContent(
                    targetState = networkReady,
                    transitionSpec = { networkTransform },
                    label = "pairNetworkState",
                ) { ready ->
                    if (ready) {
                        Column(verticalArrangement = Arrangement.spacedBy(FlickSpace.Sm)) {
                            ManualEntryCard(
                                host = host,
                                port = port,
                                spacedCode = spacedCode,
                                locked = locked,
                                codeExpiresAtElapsedMs = codeExpiresAtElapsedMs,
                                modifier = Modifier.pairStage(stage, index = 3),
                            )
                            Row(
                                modifier = Modifier.pairStage(stage, index = 4),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                LiveDot(color = FlickColor.Live, size = 7.dp, pulsing = true)
                                Text(
                                    text = stringResource(R.string.pair_listening),
                                    style = FlickType.body(sizeSp = 16, weight = FontWeight.Bold),
                                    color = FlickColor.OnSurfaceSoft,
                                )
                            }
                        }
                    } else {
                        WaitingForNetworkCard(modifier = Modifier.pairStage(stage, index = 3))
                    }
                }

                // The two actions are one beacon group, so the ring slides across
                // rather than jumping. The host carries the stage layer, so the
                // ring fades and rises with the row it belongs to.
                FocusBeaconHost(modifier = Modifier.pairStage(stage, index = 4)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(FlickSpace.Md)) {
                        // Gated off while the enlarged-code overlay is up so they are
                        // not focusable behind the scrim (clickable(enabled=false)
                        // drops focus).
                        FlickTvButton(
                            onClick = { bigCode = true },
                            enabled = !bigCode,
                            focusRequester = showBiggerFocus,
                            contentPadding = FlickDimens.ControlPadding,
                        ) {
                            Text(
                                text = stringResource(R.string.pair_show_bigger),
                                style = FlickType.body(sizeSp = 16),
                                color = FlickColor.OnSurface,
                            )
                        }
                        FlickTvButton(
                            onClick = onRename,
                            enabled = !bigCode,
                            contentPadding = FlickDimens.ControlPadding,
                        ) {
                            Text(
                                text = stringResource(R.string.pair_rename),
                                style = FlickType.body(sizeSp = 16),
                                color = FlickColor.OnSurfaceDim,
                            )
                        }
                    }
                }
            }

            if (networkReady && qrPayload != null) {
                QrColumn(
                    payload = qrPayload,
                    host = host,
                    port = port,
                    bindUptimeSec = bindUptimeSec,
                    rebindCount = rebindCount,
                    lastTeardown = lastTeardown,
                    modifier = Modifier.pairStageScaled(stage, index = 5),
                )
            }
        }

        AnimatedVisibility(
            visibleState = codeOverlayState,
            enter = if (reducedMotion) fadeIn(tween(durationMillis = 0)) else {
                fadeIn(FlickMotion.chromeFadeIn()) + scaleIn(
                    initialScale = 0.98f,
                    animationSpec = FlickMotion.flickSettleSpatial(),
                )
            },
            exit = if (reducedMotion) fadeOut(tween(durationMillis = 0)) else {
                fadeOut(FlickMotion.chromeFadeOut()) + scaleOut(
                    targetScale = 1.01f,
                    animationSpec = FlickMotion.flickSettleSpatial(),
                )
            },
            label = "pairCodeOverlay",
        ) {
            EnlargedCode(
                spacedCode = spacedCode,
                locked = locked,
                doneFocus = doneFocus,
                interactive = bigCode,
                onDone = { bigCode = false },
            )
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
        if (locked) {
            Text(
                text = stringResource(R.string.pair_locked),
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
                    text = rotationLine(codeExpiresAtElapsedMs),
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
            text = label.uppercase(Locale.getDefault()),
            style = FlickType.monoEyebrow(trackingEm = 0.14f),
            color = labelColor,
        )
        if (rolls) {
            RollingCode(code = value, style = valueStyle, color = valueColor)
        } else {
            Text(text = value, style = valueStyle, color = valueColor, maxLines = 1)
        }
    }
}

/**
 * The pairing code, rolled one character at a time.
 *
 * A rotation replaces the digits the server actually changed, so only those cells
 * move: the card reads as the code being re-issued rather than the whole panel
 * being redrawn. Geist Mono advances every glyph identically, so each cell keeps
 * its width and the row cannot reflow mid-roll.
 */
@Composable
private fun RollingCode(code: String, style: TextStyle, color: Color) {
    val reducedMotion = LocalReducedMotion.current
    // Built once for the whole code: `transitionSpec` is not a composable lambda,
    // and every cell rolls the same way anyway. The size transform snaps and clips,
    // so a glyph on its way out is cut at the cell edge and the row never reflows.
    val roll = if (reducedMotion) {
        ContentTransform(
            targetContentEnter = fadeIn(tween(durationMillis = 0)),
            initialContentExit = fadeOut(tween(durationMillis = 0)),
            sizeTransform = SizeTransform(clip = true) { _, _ -> snap() },
        )
    } else {
        ContentTransform(
            targetContentEnter = slideInVertically(
                animationSpec = FlickMotion.panelSpatial(),
                initialOffsetY = { it },
            ) + fadeIn(FlickMotion.stateEffects()),
            initialContentExit = slideOutVertically(
                animationSpec = FlickMotion.flickSettleSpatial(),
                targetOffsetY = { -it },
            ) + fadeOut(FlickMotion.stateEffects()),
            sizeTransform = SizeTransform(clip = true) { _, _ -> snap() },
        )
    }
    Row {
        code.forEachIndexed { index, character ->
            key(index) {
                AnimatedContent(
                    targetState = character,
                    transitionSpec = { roll },
                    label = "pairCodeGlyph",
                ) { glyph ->
                    Text(text = glyph.toString(), style = style, color = color, maxLines = 1)
                }
            }
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
 * No LAN address yet (Wi-Fi not associated / DHCP lease changing), so the QR host
 * and port are not reachable — say so instead of showing a dead endpoint.
 */
@Composable
private fun WaitingForNetworkCard(modifier: Modifier = Modifier) {
    GlassPanel(
        modifier = modifier.fillMaxWidth(),
        shape = FlickShape.Xl,
        tone = GlassPanelTone.Solid,
        contentPadding = FlickDimens.PanelPadding,
        verticalArrangement = Arrangement.spacedBy(FlickSpace.Sm),
        // Same as the manual-entry card: `Modifier.pairStage` at the call site is
        // already the entrance, and the `pairNetworkState` AnimatedContent owns the
        // swap between the two states.
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
                text = stringResource(R.string.pair_waiting_network_title),
                style = FlickType.display(sizeSp = 22),
                color = FlickColor.OnSurface,
            )
        }
        Text(
            text = stringResource(R.string.pair_waiting_network_detail),
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
            centerOverlay = { markSize -> BrandMark(size = markSize, tint = FlickColor.Primary) },
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
    }
}

/** The enlarged-code mode — the one thing a distant viewer might need bigger. */
@Composable
private fun EnlargedCode(
    spacedCode: String,
    locked: Boolean,
    doneFocus: FocusRequester,
    interactive: Boolean,
    onDone: () -> Unit,
) {
    Box(
        // The scrim covers the panel edge to edge; the code inside it does not.
        // Four digits spaced out is ten monospaced characters — 576 dp at this
        // size — which only clears the overscan inset because the inset is here.
        modifier = Modifier
            .fillMaxSize()
            .background(FlickColor.ScrimVeil)
            .tvOverscanSafeArea()
            // AnimatedVisibility retains the exit subtree. Once dismissed it may
            // finish its fade, but it cannot retain D-pad focus or accessibility.
            .focusProperties { canFocus = interactive }
            .then(if (interactive) Modifier else Modifier.clearAndSetSemantics { }),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(FlickSpace.Lg),
        ) {
            Text(
                text = spacedCode,
                style = FlickType.monoTabular(sizeSp = 96, weight = FontWeight.SemiBold),
                color = FlickColor.Spark,
            )
            Text(
                text = stringResource(if (locked) R.string.pair_locked else R.string.pair_code_hint),
                style = FlickType.body(sizeSp = 18),
                color = if (locked) FlickColor.Caution else FlickColor.OnSurfaceDim,
            )
            FlickTvButton(
                onClick = onDone,
                focusRequester = doneFocus,
                enabled = interactive,
                contentPadding = FlickDimens.ControlPadding,
            ) {
                Text(
                    text = stringResource(R.string.pair_hide_bigger),
                    style = FlickType.body(sizeSp = 16),
                    color = FlickColor.OnSurface,
                )
            }
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
    return text.uppercase(Locale.getDefault())
}

private fun remainingSeconds(expiresAtElapsedMs: Long): Long =
    ((expiresAtElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L) + 999L) / 1000L
