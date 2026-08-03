package com.flick.receiver.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.net.PairedPhone
import com.flick.receiver.ui.components.FlickTvButton
import com.flick.receiver.ui.components.FlickTvRow
import com.flick.receiver.ui.components.FocusBeaconHost
import com.flick.receiver.ui.components.TvOriginReveal
import com.flick.receiver.ui.components.flickPlate
import com.flick.receiver.ui.components.landTvFocus
import com.flick.receiver.ui.components.rememberTvRevealOrigin
import com.flick.receiver.ui.components.tvRevealSource
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickDimens
import com.flick.receiver.ui.theme.FlickMotion
import com.flick.receiver.ui.theme.FlickShape
import com.flick.receiver.ui.theme.FlickSpace
import com.flick.receiver.ui.theme.FlickType
import com.flick.receiver.ui.theme.LocalReducedMotion
import com.flick.receiver.ui.theme.idleAmbientBackground
import com.flick.receiver.ui.theme.rememberTvSafeAreaPadding
import com.flick.receiver.util.FlickLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * Row padding shared by every focusable settings row and the static info row.
 *
 * The title owns a separately measured header viewport, leaving the rows to
 * scroll below it. At 11 dp a row measures 22 + 25.2 + 4 + 21 = 72.2 dp, which
 * keeps the initial focus path compact without crushing labels at large font.
 */
private val RowPadding = PaddingValues(horizontal = 18.dp, vertical = 11.dp)

/**
 * A full-width row scales around its center before its detached ring is drawn.
 * On the 960 dp reference canvas, 32 dp inside the existing 48 dp overscan inset
 * leaves 2.5 dp after the 3% half-width scale expansion and 5.5 dp ring extent.
 */
private val WideRowFocusReserve = 32.dp

private const val SettingsRenameKey = "rename"
private const val SettingsPairedHeaderKey = "pairedPhones"
private const val SettingsPairedRowPrefix = "paired:"
private const val SettingsPairedBackKey = "pairedBack"
private const val SettingsMetricsKey = "metrics"
private const val SettingsForgetKey = "forgetAll"
private const val SettingsDiagnosticsKey = "diagnostics"
private const val SettingsDiagnosticEntriesKey = "diagnosticEntries"
private const val SettingsClearDiagnosticsKey = "clearDiagnostics"
private const val SettingsDoneKey = "done"

/**
 * Which of Settings' two surfaces is on screen.
 *
 * The paired phones are a **drill-in**, and that is why they are a pane swap
 * rather than the [TvOriginReveal] the diagnostics log uses. The log is a
 * disclosure: a blob summoned by the row above it, born at that row and pulled
 * back into it, and it belongs in the column it interrupts. A phone list is a
 * *place* — its own heading, its own bounded scroll, its own way out, two
 * focusables per entry — and rendering it inline is exactly the unbounded column
 * this drill-in exists to remove. It is also the reveal's own constraint: nothing
 * inside a `TvOriginReveal` may be focusable while the wipe runs, and every row
 * here is.
 *
 * Each pane carries its own [FocusBeaconHost]. The two are unrelated regions, and
 * one host spanning both would fly the ring from a settings row into a phone's
 * Rename key across a surface that is itself sliding.
 */
private enum class SettingsPane { Column, PairedPhones }

/**
 * How far a pane travels on the drill. A sixth of the axis, matching the standby
 * surfaces: at ten feet a full-width slide reads as a jump cut, and this is the
 * same drill-from-the-right direction the shell uses to enter Settings itself.
 */
private const val SettingsPaneTravelDivisor = 6

/**
 * The first-appearance stagger. It covers the first five RENDERED rows of the
 * settings column — the row count still moves, because "Forget all phones" and
 * the diagnostics rows come and go — and never carries the two that own
 * `bringIntoViewRequester`s: that machinery reads layout coordinates, and it must
 * not see anything moving. A sixth of the entrance run is ~40 ms on the spring,
 * the gap at which a column reads as arriving in order.
 *
 * The run stays five long. Beyond it the lead has eaten the whole run and
 * [settingsStageProgress] would leave a row at a fraction of its alpha for the
 * length of the entrance, so those rows are not staged at all and simply arrive
 * with the column.
 *
 * The paired-phones pane is deliberately unstaged. Its entrance is the drill
 * itself, and a per-row stagger inside a pane that is already sliding is two
 * entrances on one surface.
 */
private const val SettingsStageLead = 0.16f
private const val SettingsStagedRows = 5

/** Rows arrive from just below their resting line. */
private val SettingsStageRise = 10.dp

/** 45 − 2 × 3 − 18 = 21 dp of knob travel, unchanged by the re-scale. */
private val ToggleKnobTravel = 21.dp

/**
 * The knob stretches to ~22 dp at the middle of its travel and is round again at
 * either end: the squash is a function of speed, not of position, which is what
 * makes it read as the knob being thrown rather than merely moved.
 */
private const val ToggleKnobStretch = 22f / 18f

private fun settingsStageProgress(progress: Float, index: Int): Float {
    val span = 1f - SettingsStageLead * (SettingsStagedRows - 1)
    return ((progress - SettingsStageLead * index) / span).coerceIn(0f, 1f)
}

/**
 * A staged row's entrance. `graphicsLayer` only — a layout offset here would move
 * the bounds the `SettingsLayoutEpoch` / `bringIntoView` machinery measures.
 *
 * [settled] drops the layer once the column has arrived: a finished entrance must
 * not leave a render node per row composited for the life of the screen.
 */
private fun Modifier.settingsStage(
    progress: () -> Float,
    index: Int,
    settled: Boolean,
): Modifier = if (settled || index >= SettingsStagedRows) {
    this
} else {
    graphicsLayer {
        val stage = settingsStageProgress(progress(), index)
        alpha = stage
        translationY = (1f - stage) * SettingsStageRise.toPx()
    }
}

/**
 * Everything that moves the rows Clear and Done sit under. [pairedPhones] is
 * still in it even though the per-phone rows have moved to their own pane: the
 * count is the "Paired phones" summary, and crossing two phones adds or removes
 * the whole "Forget all phones" row above them. The two confirm latches are
 * deliberately out: each swaps one summary for another on the same line.
 */
private data class SettingsLayoutEpoch(
    val diagnosticsVisible: Boolean,
    val diagnostics: List<FlickLog.Entry>,
    val pairedPhones: List<PairedPhone>,
    val density: Float,
    val fontScale: Float,
)

/**
 * Where D-pad focus is owed a landing once a forget disposes the focused row.
 *
 * "Nowhere" is deliberately NOT one of these. A LazyColumn item that goes away
 * takes focus with it, so a forget that resolved to null would leave the remote
 * steering a screen with no focus on it at all, recoverable only with Back —
 * and one paired phone is the common case, not an edge one.
 */
internal sealed interface SettingsFocusReturn {
    /** The surviving neighbour row, which is where the eye already is. */
    data class Phone(val keyId: String) : SettingsFocusReturn

    /**
     * The paired-phone pane's Back key: no phone row survives, and that control
     * is the one thing every state of the pane has — including the empty list a
     * forget can leave behind for the frame before the host closes Settings.
     */
    data object ListBack : SettingsFocusReturn
}

/**
 * Where D-pad focus goes when [keyId]'s row is forgotten: the row below it, the
 * row above when it was the last one, and the pane's Back key when it was the
 * only one.
 *
 * A key id that is not in [phones] cannot name a surviving neighbour either, so
 * it takes the same landing rather than a null the caller would have to invent a
 * meaning for.
 */
internal fun settingsFocusReturnAfterForget(phones: List<PairedPhone>, keyId: String): SettingsFocusReturn {
    val index = phones.indexOfFirst { it.keyId == keyId }
    if (index < 0) return SettingsFocusReturn.ListBack
    val neighbour = phones.getOrNull(index + 1)?.keyId ?: phones.getOrNull(index - 1)?.keyId
    return neighbour?.let(SettingsFocusReturn::Phone) ?: SettingsFocusReturn.ListBack
}

// Which control in the phone pane holds focus, as a token. A pane whose rows
// each carry two keys has no single "focused row" to name, and `landTvFocus`
// has to be told precisely which control it was aiming at.
private fun renameToken(keyId: String) = "rename:$keyId"
private fun forgetToken(keyId: String) = "forget:$keyId"
private const val BackToken = "back"

/**
 * T10a · Settings. The old always-on developer HUD survives here as one row —
 * "Playback metrics overlay", off by default, phrased for the curious. Focus
 * begins on Device name, then moves through Paired phones, metrics, forget-all,
 * diagnostics, and Done along the D-pad path `SettingsScreenFocusTest` walks.
 *
 * Paired phones is a drill-in: the row reports the count and opens a pane listing
 * the phones, one Rename and one Forget key each. See [SettingsPane] for why that
 * is a pane rather than an inline reveal.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun SettingsScreen(
    tvName: String,
    pairedSummary: String,
    metricsEnabled: Boolean,
    onRename: () -> Unit,
    onToggleMetrics: () -> Unit,
    onForgetAll: () -> Unit,
    onDone: () -> Unit,
    pairedPhones: List<PairedPhone> = emptyList(),
    /** Returns whether the credential is actually gone; see `PairingManager.forget`. */
    onForgetPhone: (String) -> Boolean = { false },
    /**
     * Opens the shared text editor for this phone. Saving goes through
     * `PairingManager.rename`, which carries its credentials and pairing date
     * across untouched and does not disturb a live session.
     */
    onRenamePhone: (String) -> Unit = {},
    diagnosticsVisible: Boolean = false,
    diagnostics: List<FlickLog.Entry> = emptyList(),
    onToggleDiagnostics: () -> Unit = {},
    onClearDiagnostics: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val safeArea = rememberTvSafeAreaPadding()
    val layoutDirection = LocalLayoutDirection.current
    val settingsContentPadding = PaddingValues(
        // Full-width rows scale and draw their detached ring outward. This is a
        // larger reserve than a compact control needs, so the painted extent
        // still remains inside the 5% physical overscan inset.
        start = safeArea.calculateStartPadding(layoutDirection) + WideRowFocusReserve,
        // The first row can scale upward and draw a detached ring. Keep that
        // painted extent inside the clipped viewport just below the header.
        top = FlickDimens.FocusRingReserve,
        end = safeArea.calculateEndPadding(layoutDirection) + WideRowFocusReserve,
        // The focused control scales and carries a detached ring outside its own
        // bounds. Keep both inside the scroll viewport instead of allowing the
        // last row to be clipped: a 74 dp row's ring reaches
        // (37.1 + 5.5) × 1.06 − 37.1 = 8.1 dp below its own edge.
        bottom = safeArea.calculateBottomPadding() + FlickDimens.FocusRingReserve,
    )
    val renameFocus = remember { FocusRequester() }
    val pairedRowFocus = remember { FocusRequester() }
    val clearBringIntoView = remember { BringIntoViewRequester() }
    val doneBringIntoView = remember { BringIntoViewRequester() }
    var clearFocused by remember { mutableStateOf(false) }
    var doneFocused by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val layoutEpoch = SettingsLayoutEpoch(
        diagnosticsVisible = diagnosticsVisible,
        diagnostics = diagnostics,
        pairedPhones = pairedPhones,
        density = density.density,
        fontScale = density.fontScale,
    )
    val currentLayoutEpoch by rememberUpdatedState(layoutEpoch)
    var clearPlacedEpoch by remember { mutableStateOf<SettingsLayoutEpoch?>(null) }
    var donePlacedEpoch by remember { mutableStateOf<SettingsLayoutEpoch?>(null) }
    val focusReservePx = with(density) { FlickDimens.FocusRingReserve.toPx() }
    val settingsBringIntoViewSpec = remember(focusReservePx) {
        SettingsBringIntoViewSpec(focusReservePx)
    }
    var confirmForget by remember { mutableStateOf(false) }
    var renameFocused by remember { mutableStateOf(false) }
    var pairedRowFocused by remember { mutableStateOf(false) }

    // ── The drill-in ────────────────────────────────────────────────────────
    var pane by remember { mutableStateOf(SettingsPane.Column) }
    // Whether the column is being RE-entered from the phone list. Coming back out
    // of a drill-in has to land on the row that opened it, so the trip reads as
    // reversible — but a first entry still begins on Device name, the column's own
    // first actionable row.
    var returningFromList by remember { mutableStateOf(false) }
    // The armed phone, by key id rather than by a pane-level flag: arming one row
    // must not arm its neighbours, and an armed key disarms the moment the D-pad
    // leaves it — a latch left set off screen would be fired by a centre press the
    // viewer aimed at something else entirely.
    var armedForget by remember { mutableStateOf<String?>(null) }
    var focusedListControl by remember { mutableStateOf<String?>(null) }
    // Where D-pad focus must land once a forget disposes the focused row, and the
    // requester lent to that row while it is being aimed at. Null means nothing
    // is owed a landing at all — never "land nowhere", which is what
    // [SettingsFocusReturn.ListBack] exists to keep expressible.
    var focusReturn by remember { mutableStateOf<SettingsFocusReturn?>(null) }
    val phoneReturnFocus = remember { FocusRequester() }
    val listEntryFocus = remember { FocusRequester() }
    val listBackFocus = remember { FocusRequester() }

    // Date only, and never a timestamp: a v2 record has no date at all, and the
    // hour a phone was paired is not a fact the viewer needs. The device locale
    // rather than the diagnostics clock's `Locale.US` — that is developer output
    // pinned to one locale, this is user copy.
    val pairedDate = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }
    // Below two phones this row is never the right offer, for two different
    // reasons. With one it is a second, blunter copy of that phone's own Forget,
    // and a TV settings column must not carry the same destructive action twice.
    // With none it is a destructive action with nothing to destroy — still
    // focusable, so a viewer can land on it, press it and watch nothing happen.
    // The host closes this screen when the last phone goes, so the empty list is
    // normally one exit frame; this screen must not depend on that to be coherent.
    val showForgetAll = pairedPhones.size >= 2
    // The drill-in is offered only when there is something to drill into. A row
    // that opens an empty list is the same "control that does nothing" the
    // paragraph above refuses, and with no phones the heading and its "None yet"
    // summary are already the whole answer. Settings is now reachable from the
    // pair screen, so no phones paired is a real state here, not a theoretical one.
    val managePhones = pairedPhones.isNotEmpty()
    val reducedMotion = LocalReducedMotion.current
    val entranceSpec: FiniteAnimationSpec<Float> = FlickMotion.panelSpatial()
    val entrance = remember { Animatable(0f) }
    var entranceSettled by remember { mutableStateOf(false) }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) entrance.snapTo(1f) else entrance.animateTo(1f, entranceSpec)
        entranceSettled = true
    }
    val stage = { entrance.value }
    // The diagnostics log is summoned by one row and appears directly under it,
    // so it is born there — and pulled back into the same row when the toggle
    // goes off. The latch is held at screen level rather than inside the list
    // item: a LazyColumn disposes an item that scrolls out of view, and a latch
    // living there would replay the reveal every time it came back.
    val diagnosticsOrigin = rememberTvRevealOrigin()
    var diagnosticsRevealed by remember { mutableStateOf(false) }
    // The log's MOUNT LIFETIME, which outlives [diagnosticsVisible]: the retreat
    // is a draw-phase animation and cannot run in an item the list has already
    // dropped. Cleared by the reveal once the circle has closed.
    var diagnosticsRetained by remember { mutableStateOf(false) }
    // Whether the list is composing that item at all. A LazyColumn only composes
    // what its viewport reaches, so a log the toggle has scrolled past has no
    // reveal to run the retreat and none to report it — retention held there
    // would keep a full-height slot in the list that nothing draws until it next
    // scrolls in. Held as a State read only from the effect below: read at
    // composition scope, an item composing during the list's own measure pass
    // would invalidate this whole screen.
    val diagnosticsComposed = remember { mutableStateOf(false) }
    LaunchedEffect(diagnosticsVisible) {
        when {
            diagnosticsVisible -> {
                diagnosticsRetained = true
                diagnosticsRevealed = true
            }
            // Closed with the log outside the viewport: nothing is on screen to
            // pull back and no report is coming, so the row is released now. The
            // latch goes with it — left true, the next open would find the reveal
            // already flagged visible and the log would arrive settled instead of
            // wiping out of the row.
            !diagnosticsComposed.value -> {
                diagnosticsRevealed = false
                diagnosticsRetained = false
            }
            diagnosticsRevealed -> diagnosticsRevealed = false
            // The open is published a frame after the item mounts, so the wipe
            // gets the false → true edge it needs. A toggle reversed inside that
            // frame leaves nothing to pull back and no retreat to report, so the
            // row is released here instead of waiting forever on one.
            else -> diagnosticsRetained = false
        }
    }

    val leaveList = {
        returningFromList = true
        pane = SettingsPane.Column
    }
    // Back pops the drill-in before the shell is allowed to close Settings. This
    // handler is composed below the one in `ReceiverApp`, so it is dispatched
    // first for as long as it is enabled.
    BackHandler(enabled = pane == SettingsPane.PairedPhones) { leaveList() }

    // A pane with nothing in it is not a place. The host closes Settings when the
    // last phone goes, but a screen that sat on an empty list waiting for that
    // would be showing a heading with no rows and no way to read why.
    LaunchedEffect(managePhones) {
        if (!managePhones && pane == SettingsPane.PairedPhones) leaveList()
    }

    // Focus entry for whichever pane is now on top. Both go through [landTvFocus]
    // rather than a bare request: `AnimatedContent` composes the arriving pane in
    // the same frame it starts the outgoing one leaving, and a requester can only
    // be honoured once its node is attached AND placed.
    LaunchedEffect(pane) {
        when (pane) {
            SettingsPane.Column -> if (returningFromList) {
                returningFromList = false
                // The row that opened the list. Falls back to Device name, which
                // no state of this column is without — including the one where the
                // last phone has just gone and Paired phones is no longer focusable.
                landTvFocus(pairedRowFocus, renameFocus) { pairedRowFocused }
            } else {
                landTvFocus(renameFocus, renameFocus) { renameFocused }
            }
            // The first phone's Rename key: the pane's leading control, and the
            // non-destructive one of the two.
            SettingsPane.PairedPhones ->
                landTvFocus(listEntryFocus, listBackFocus) { focusedListControl != null }
        }
    }

    // Forgetting a phone disposes the very row focus is on, and a LazyColumn item
    // that goes away takes D-pad focus with it — land nowhere and the remote
    // steers nothing at all. Driven off the ARRIVAL of the new list rather than
    // off the press, because the row is still composed at the moment of the press;
    // [landTvFocus] then repeats the request until the replacement row is attached
    // and placed, and falls back to the pane's Back key, which it always has.
    LaunchedEffect(pairedPhones) {
        when (val target = focusReturn) {
            null -> return@LaunchedEffect
            // Still the neighbour's landing, unless that row has itself gone in
            // the meantime — then Back, which the pane is never without.
            is SettingsFocusReturn.Phone ->
                if (pairedPhones.any { it.keyId == target.keyId }) {
                    landTvFocus(phoneReturnFocus, listBackFocus) {
                        focusedListControl == forgetToken(target.keyId)
                    }
                } else {
                    landTvFocus(listBackFocus, listBackFocus) { focusedListControl == BackToken }
                }
            SettingsFocusReturn.ListBack ->
                landTvFocus(listBackFocus, listBackFocus) { focusedListControl == BackToken }
        }
        focusReturn = null
    }
    LaunchedEffect(layoutEpoch, clearPlacedEpoch, donePlacedEpoch, clearFocused, doneFocused) {
        when {
            clearFocused && clearPlacedEpoch == layoutEpoch -> clearBringIntoView.bringIntoView()
            doneFocused && donePlacedEpoch == layoutEpoch -> doneBringIntoView.bringIntoView()
        }
    }

    // `transitionSpec` is not a composable lambda, so both drill directions are
    // resolved here and captured.
    val drillIn = settingsPaneTransform(reducedMotion, forward = true)
    val drillOut = settingsPaneTransform(reducedMotion, forward = false)

    AnimatedContent(
        targetState = pane,
        transitionSpec = { if (targetState == SettingsPane.PairedPhones) drillIn else drillOut },
        label = "settingsPane",
        modifier = modifier
            // The wash belongs to the screen rather than to either pane: held here
            // it stays put while the panes slide across it.
            .fillMaxSize()
            .idleAmbientBackground(),
    ) { rendered ->
        // `AnimatedContent` retains the OUTGOING pane for the length of its exit.
        // It may finish sliding, but it may not keep a D-pad focus target or an
        // accessibility node while it does.
        val interactive = rendered == pane
        Column(
            modifier = Modifier
                .fillMaxSize()
                .focusProperties { canFocus = interactive }
                .then(if (interactive) Modifier else Modifier.clearAndSetSemantics { }),
        ) {
            SettingsHeader(
                title = stringResource(
                    when (rendered) {
                        SettingsPane.Column -> R.string.settings_title
                        SettingsPane.PairedPhones -> R.string.settings_paired_list_title
                    },
                ),
                safeArea = safeArea,
                layoutDirection = layoutDirection,
            )

            // The travelling ring lives above the scroll viewport, so it glides
            // between rows as one object instead of appearing and vanishing per
            // row — and it is never clipped by the LazyColumn's own scroll clip.
            // One host per pane; see [SettingsPane].
            FocusBeaconHost(modifier = Modifier.fillMaxWidth().weight(1f)) {
                CompositionLocalProvider(LocalBringIntoViewSpec provides settingsBringIntoViewSpec) {
                    when (rendered) {
                        SettingsPane.PairedPhones -> PairedPhonesPane(
                            phones = pairedPhones,
                            contentPadding = settingsContentPadding,
                            pairedDate = pairedDate,
                            armedForget = armedForget,
                            focusReturn = focusReturn,
                            entryFocus = listEntryFocus,
                            backFocus = listBackFocus,
                            phoneReturnFocus = phoneReturnFocus,
                            onRenamePhone = onRenamePhone,
                            onForgetPress = { phone ->
                                // The latch is read here rather than through a
                                // composed flag: a press must be judged against the
                                // state at the moment of the press, never against
                                // whichever recomposition last reached this row.
                                if (armedForget == phone.keyId) {
                                    armedForget = null
                                    // Recorded from the list the viewer is looking
                                    // at, before the row goes away.
                                    focusReturn = settingsFocusReturnAfterForget(pairedPhones, phone.keyId)
                                    // A refused durable write leaves the row exactly
                                    // where it was, still focused, so nothing is
                                    // owed a landing.
                                    if (!onForgetPhone(phone.keyId)) focusReturn = null
                                } else {
                                    armedForget = phone.keyId
                                }
                            },
                            onControlFocus = { token, focused ->
                                if (focused) {
                                    focusedListControl = token
                                } else {
                                    if (focusedListControl == token) focusedListControl = null
                                    if (token == armedForget?.let(::forgetToken)) armedForget = null
                                }
                            },
                            onBack = leaveList,
                        )

                        SettingsPane.Column -> LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("settings-scroll-viewport"),
                            contentPadding = settingsContentPadding,
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            // The stagger's index is a RENDERED position, counted
                            // as the column is built: the optional rows make the
                            // count variable, so it cannot be a constant per row.
                            // Each row captures its own value here rather than
                            // reading the counter from inside its item, which would
                            // compose against the final total.
                            var row = 0

                            val renameRow = row++
                            item(key = SettingsRenameKey) {
                                FlickTvRow(
                                    onClick = onRename,
                                    focusRequester = renameFocus,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onFocusChanged { renameFocused = it.isFocused }
                                        .settingsStage(stage, index = renameRow, settled = entranceSettled)
                                        .testTag("settings-first-row"),
                                    contentPadding = RowPadding,
                                ) {
                                    LabeledColumn(
                                        modifier = Modifier.weight(1f),
                                        title = stringResource(R.string.settings_device_name),
                                        summary = tvName,
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_disclosure),
                                        style = FlickType.body(sizeSp = 18),
                                        color = FlickColor.OnSurfaceFaint,
                                    )
                                }
                            }

                            val pairedHeaderRow = row++
                            item(key = SettingsPairedHeaderKey) {
                                val pairedRowModifier = Modifier
                                    .fillMaxWidth()
                                    .settingsStage(stage, index = pairedHeaderRow, settled = entranceSettled)
                                    .testTag("settings-paired-row")
                                if (managePhones) {
                                    FlickTvRow(
                                        onClick = { pane = SettingsPane.PairedPhones },
                                        focusRequester = pairedRowFocus,
                                        modifier = pairedRowModifier
                                            .onFocusChanged { pairedRowFocused = it.isFocused },
                                        contentPadding = RowPadding,
                                    ) {
                                        LabeledColumn(
                                            modifier = Modifier.weight(1f),
                                            title = stringResource(R.string.settings_paired_phones),
                                            summary = pairedSummary,
                                        )
                                        Text(
                                            text = stringResource(R.string.settings_paired_open),
                                            style = FlickType.body(sizeSp = 18),
                                            color = FlickColor.OnSurfaceFaint,
                                        )
                                    }
                                } else {
                                    // With no phones the heading is still the whole
                                    // answer — "Paired phones / None yet" — and
                                    // there is nothing to open.
                                    Row(
                                        modifier = pairedRowModifier
                                            .clip(FlickShape.Md)
                                            .background(FlickColor.SurfaceRaisedAlt)
                                            .border(FlickDimens.Hairline, FlickColor.OutlineHairline, FlickShape.Md)
                                            .padding(RowPadding),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        LabeledColumn(
                                            modifier = Modifier.weight(1f),
                                            title = stringResource(R.string.settings_paired_phones),
                                            summary = pairedSummary,
                                        )
                                    }
                                }
                            }

                            val metricsRow = row++
                            item(key = SettingsMetricsKey) {
                                FlickTvRow(
                                    onClick = onToggleMetrics,
                                    checked = metricsEnabled,
                                    stateDescription = stringResource(
                                        if (metricsEnabled) R.string.settings_metrics_on else R.string.settings_metrics_off,
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .settingsStage(stage, index = metricsRow, settled = entranceSettled)
                                        .testTag("settings-metrics-row"),
                                    contentPadding = RowPadding,
                                ) {
                                    LabeledColumn(
                                        modifier = Modifier.weight(1f),
                                        title = stringResource(R.string.settings_metrics_title),
                                        summary = stringResource(R.string.settings_metrics_summary),
                                    )
                                    ToggleGlyph(enabled = metricsEnabled)
                                }
                            }

                            if (showForgetAll) {
                                val forgetAllRow = row++
                                item(key = SettingsForgetKey) {
                                    FlickTvRow(
                                        onClick = {
                                            if (confirmForget) onForgetAll() else confirmForget = true
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            // Disarms with focus, exactly as the
                                            // per-phone Forget keys do: a latch that
                                            // survived the D-pad leaving could be
                                            // fired later by a centre press aimed
                                            // elsewhere, and two destructive
                                            // affordances that arm alike must not
                                            // disarm differently.
                                            .onFocusChanged { if (!it.isFocused) confirmForget = false }
                                            .settingsStage(stage, index = forgetAllRow, settled = entranceSettled)
                                            .testTag("settings-forget-row"),
                                        contentPadding = RowPadding,
                                    ) {
                                        LabeledColumn(
                                            modifier = Modifier.weight(1f),
                                            title = stringResource(R.string.settings_forget_all),
                                            summary = stringResource(
                                                if (confirmForget) R.string.settings_forget_all_confirm
                                                else R.string.settings_forget_all_summary,
                                            ),
                                            summaryColor = if (confirmForget) FlickColor.Caution else FlickColor.OnSurfaceDim,
                                        )
                                    }
                                }
                            }

                            // Self-diagnosing TV: the same FlickTV lines adb would
                            // show, without a laptop. Memory-only; nothing here is
                            // persisted.
                            val diagnosticsRow = row++
                            item(key = SettingsDiagnosticsKey) {
                                FlickTvRow(
                                    onClick = onToggleDiagnostics,
                                    checked = diagnosticsVisible,
                                    stateDescription = stringResource(
                                        if (diagnosticsVisible) R.string.settings_metrics_on else R.string.settings_metrics_off,
                                    ),
                                    // Ahead of the entrance layer, so the centre it
                                    // records is the row's resting one rather than a
                                    // point on its way up.
                                    modifier = Modifier
                                        .tvRevealSource(diagnosticsOrigin)
                                        .fillMaxWidth()
                                        .settingsStage(stage, index = diagnosticsRow, settled = entranceSettled)
                                        .testTag("settings-diagnostics-row"),
                                    contentPadding = RowPadding,
                                ) {
                                    LabeledColumn(
                                        modifier = Modifier.weight(1f),
                                        title = stringResource(R.string.settings_diagnostics_title),
                                        summary = stringResource(R.string.settings_diagnostics_summary),
                                    )
                                    ToggleGlyph(enabled = diagnosticsVisible)
                                }
                            }

                            if (diagnosticsVisible || diagnosticsRetained) {
                                item(key = SettingsDiagnosticEntriesKey) {
                                    // A LazyColumn disposes an item scrolled out of
                                    // the viewport, and a retreat that is not
                                    // composed can never report that it finished.
                                    // Leaving the viewport therefore ends the
                                    // retention too, rather than holding a slot in
                                    // the list for a surface nobody is drawing —
                                    // while an item that leaves with the log still
                                    // on keeps it, so scrolling back and closing
                                    // still collapses into the row. The toggle is
                                    // read at disposal rather than captured per
                                    // composition: the list can drop this item in
                                    // the same measure pass that closed the log, and
                                    // would then never recompose it with the new
                                    // value.
                                    val diagnosticsOn = rememberUpdatedState(diagnosticsVisible)
                                    DisposableEffect(Unit) {
                                        diagnosticsComposed.value = true
                                        onDispose {
                                            diagnosticsComposed.value = false
                                            if (!diagnosticsOn.value) diagnosticsRetained = false
                                        }
                                    }
                                    // The log's own fill wipes out of the Diagnostics
                                    // row above it and collapses back into it — this
                                    // screen's hero moment, and the only reveal on
                                    // it. It is a draw-phase effect: the Clear row
                                    // below owns a BringIntoViewRequester, and that
                                    // machinery must never measure a row whose
                                    // position is still moving. Nothing inside here
                                    // is focusable, so no focus target moves either.
                                    TvOriginReveal(
                                        visible = diagnosticsRevealed,
                                        origin = diagnosticsOrigin,
                                        color = FlickColor.SurfaceRaisedAlt,
                                        modifier = Modifier.fillMaxWidth(),
                                        // Also reported by a reveal that composes
                                        // hidden and is never opened, so only a
                                        // close may drop the row.
                                        onRetreated = { if (!diagnosticsVisible) diagnosticsRetained = false },
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("settings-diagnostic-entries")
                                                .clip(FlickShape.Md)
                                                .background(FlickColor.SurfaceRaisedAlt)
                                                .border(FlickDimens.Hairline, FlickColor.OutlineHairline, FlickShape.Md)
                                                .padding(horizontal = 18.dp, vertical = 12.dp),
                                            verticalArrangement = Arrangement.spacedBy(3.dp),
                                        ) {
                                            if (diagnostics.isEmpty()) {
                                                Text(
                                                    text = stringResource(R.string.settings_diagnostics_empty),
                                                    style = FlickType.monoTabular(sizeSp = 14, weight = FontWeight.Medium),
                                                    color = FlickColor.OnSurfaceFaint,
                                                )
                                            } else {
                                                val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
                                                diagnostics.forEach { entry ->
                                                    Text(
                                                        text = stringResource(
                                                            R.string.settings_diagnostics_entry,
                                                            clock.format(Date(entry.atMs)),
                                                            entry.level,
                                                            entry.area,
                                                            redactDiagnostic(entry.message),
                                                        ),
                                                        // One line per entry, so the
                                                        // size sets how much of a log
                                                        // line survives: Geist Mono
                                                        // advances 0.6 em, and the
                                                        // 824 dp available inside the
                                                        // safe area carries 98
                                                        // characters at 14 sp against
                                                        // 58 at 20 sp.
                                                        style = FlickType.monoTabular(sizeSp = 14, weight = FontWeight.Medium),
                                                        color = FlickColor.OnSurfaceDim,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Not retained through the retreat: the log's collapse
                            // is drawn, so it moves nothing, but this row is a focus
                            // target for a log that is already going away and it
                            // leaves with the toggle that owns it.
                            if (diagnosticsVisible) {
                                item(key = SettingsClearDiagnosticsKey) {
                                    FlickTvRow(
                                        onClick = onClearDiagnostics,
                                        // No entrance stage on this row: it owns a
                                        // BringIntoViewRequester, and that machinery
                                        // must never measure a row that is still
                                        // moving.
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .bringIntoViewRequester(clearBringIntoView)
                                            .onGloballyPositioned {
                                                if (currentLayoutEpoch == layoutEpoch) clearPlacedEpoch = layoutEpoch
                                            }
                                            .onFocusChanged { clearFocused = it.isFocused }
                                            .testTag("settings-clear-row"),
                                        contentPadding = RowPadding,
                                    ) {
                                        LabeledColumn(
                                            modifier = Modifier.weight(1f),
                                            title = stringResource(R.string.settings_diagnostics_clear),
                                            summary = stringResource(R.string.settings_diagnostics_capture),
                                        )
                                    }
                                }
                            }

                            item(key = SettingsDoneKey) {
                                Box(Modifier.padding(top = 10.dp)) {
                                    FlickTvButton(
                                        onClick = onDone,
                                        modifier = Modifier
                                            .bringIntoViewRequester(doneBringIntoView)
                                            .onGloballyPositioned {
                                                if (currentLayoutEpoch == layoutEpoch) donePlacedEpoch = layoutEpoch
                                            }
                                            .onFocusChanged { doneFocused = it.isFocused }
                                            .testTag("settings-done-row"),
                                        contentPadding = FlickDimens.ControlPadding,
                                    ) {
                                        Text(
                                            text = stringResource(R.string.settings_done),
                                            style = FlickType.body(sizeSp = 16),
                                            color = FlickColor.OnSurface,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The drill-in: every paired phone, with a Rename and a Forget key each, and one
 * Back key at the foot of the list.
 *
 * A phone's plate is deliberately NOT itself focusable. It carries two keys, and
 * wrapping them in a clickable row would put a third focus target on the same
 * line with no action of its own — so the plate takes the same static treatment
 * the settings column's non-focusable rows do, and the keys are the focus path.
 */
@Composable
private fun PairedPhonesPane(
    phones: List<PairedPhone>,
    contentPadding: PaddingValues,
    pairedDate: SimpleDateFormat,
    armedForget: String?,
    focusReturn: SettingsFocusReturn?,
    entryFocus: FocusRequester,
    backFocus: FocusRequester,
    phoneReturnFocus: FocusRequester,
    onRenamePhone: (String) -> Unit,
    onForgetPress: (PairedPhone) -> Unit,
    onControlFocus: (token: String, focused: Boolean) -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("settings-paired-viewport"),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        phones.forEachIndexed { index, phone ->
            item(key = SettingsPairedRowPrefix + phone.keyId) {
                val armed = armedForget == phone.keyId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings-paired-phone-row")
                        .clip(FlickShape.Md)
                        .background(FlickColor.SurfaceRaisedAlt)
                        .border(FlickDimens.Hairline, FlickColor.OutlineHairline, FlickShape.Md)
                        .padding(RowPadding),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FlickSpace.Md),
                ) {
                    LabeledColumn(
                        modifier = Modifier.weight(1f),
                        title = phone.label,
                        summary = when {
                            armed -> stringResource(R.string.settings_paired_forget_confirm)
                            phone.pairedAtMs != null -> stringResource(
                                R.string.settings_paired_since,
                                pairedDate.format(Date(phone.pairedAtMs)),
                            )
                            else -> stringResource(R.string.settings_paired_undated)
                        },
                        summaryColor = if (armed) FlickColor.Caution else FlickColor.OnSurfaceDim,
                    )
                    FlickTvButton(
                        onClick = { onRenamePhone(phone.keyId) },
                        contentDescription = stringResource(
                            R.string.settings_phone_rename_a11y,
                            phone.label,
                        ),
                        // The pane's entry point is the first phone's Rename key;
                        // every other row leaves the requester unclaimed.
                        focusRequester = entryFocus.takeIf { index == 0 },
                        modifier = Modifier
                            .onFocusChanged { onControlFocus(renameToken(phone.keyId), it.isFocused) }
                            .testTag("settings-phone-rename"),
                        contentPadding = FlickDimens.ControlPadding,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_phone_rename),
                            style = FlickType.body(sizeSp = 16),
                            color = FlickColor.OnSurface,
                        )
                    }
                    FlickTvButton(
                        onClick = { onForgetPress(phone) },
                        contentDescription = stringResource(
                            R.string.settings_paired_forget_a11y,
                            phone.label,
                        ),
                        focusRequester = phoneReturnFocus.takeIf {
                            focusReturn is SettingsFocusReturn.Phone && focusReturn.keyId == phone.keyId
                        },
                        modifier = Modifier
                            .onFocusChanged { onControlFocus(forgetToken(phone.keyId), it.isFocused) }
                            .testTag("settings-phone-forget"),
                        contentPadding = FlickDimens.ControlPadding,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_paired_forget),
                            style = FlickType.body(sizeSp = 16),
                            color = if (armed) FlickColor.Caution else FlickColor.OnSurfaceDim,
                        )
                    }
                }
            }
        }

        item(key = SettingsPairedBackKey) {
            Box(Modifier.padding(top = 10.dp)) {
                FlickTvButton(
                    onClick = onBack,
                    focusRequester = backFocus,
                    modifier = Modifier
                        .onFocusChanged { onControlFocus(BackToken, it.isFocused) }
                        .testTag("settings-paired-back-row"),
                    contentPadding = FlickDimens.ControlPadding,
                ) {
                    Text(
                        text = stringResource(R.string.settings_paired_back),
                        style = FlickType.body(sizeSp = 16),
                        color = FlickColor.OnSurface,
                    )
                }
            }
        }
    }
}

/**
 * The fixed heading above a pane's scroll viewport. It measures its own
 * font-scaled line box, and the viewport is a sibling below it, so no focused row
 * can paint behind the heading or push it into the physical overscan area.
 */
@Composable
private fun SettingsHeader(
    title: String,
    safeArea: PaddingValues,
    layoutDirection: LayoutDirection,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = safeArea.calculateStartPadding(layoutDirection) + WideRowFocusReserve,
                top = safeArea.calculateTopPadding() + FlickSpace.Md,
                end = safeArea.calculateEndPadding(layoutDirection) + WideRowFocusReserve,
                bottom = 14.dp,
            ),
    ) {
        Text(
            text = title,
            style = FlickType.display(sizeSp = 27),
            color = FlickColor.OnSurface,
        )
    }
}

/**
 * One direction of the pane drill. [forward] is the way into the phone list,
 * which arrives from the right — the same direction and the same sixth-of-axis
 * travel the shell uses to enter Settings itself, so a drill inside Settings
 * reads as that gesture one level down. Geometry takes the spatial springs;
 * alpha keeps the chrome fade tokens, which never overshoot.
 */
@Composable
private fun settingsPaneTransform(reducedMotion: Boolean, forward: Boolean): ContentTransform =
    if (reducedMotion) {
        fadeIn(tween(durationMillis = 0)).togetherWith(fadeOut(tween(durationMillis = 0)))
    } else {
        val sign = if (forward) 1 else -1
        val travelIn: FiniteAnimationSpec<IntOffset> = FlickMotion.panelSpatial()
        val travelOut: FiniteAnimationSpec<IntOffset> = FlickMotion.flickSettleSpatial()
        (
            fadeIn(FlickMotion.chromeFadeIn()) + slideInHorizontally(
                animationSpec = travelIn,
                initialOffsetX = { sign * it / SettingsPaneTravelDivisor },
            )
            ).togetherWith(
            fadeOut(FlickMotion.chromeFadeOut()) + slideOutHorizontally(
                animationSpec = travelOut,
                targetOffsetX = { -sign * it / SettingsPaneTravelDivisor },
            ),
        )
    }

/**
 * A focused row may scale and paint a detached ring outside its layout bounds.
 * Keep that reserve inside the viewport, otherwise align the row itself to the
 * reserve instead of retaining a sliced predecessor above it.
 */
private class SettingsBringIntoViewSpec(
    private val focusRingReservePx: Float,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val visibleStart = focusRingReservePx
        val visibleEnd = containerSize - focusRingReservePx
        return if (offset >= visibleStart && offset + size <= visibleEnd) 0f else offset - visibleStart
    }
}

/** Keeps an on-TV diagnostic useful without exposing an address or bearer token. */
@Composable
private fun redactDiagnostic(message: String): String {
    val address = stringResource(R.string.settings_diagnostics_address_redacted)
    val redacted = stringResource(R.string.settings_diagnostics_value_redacted)
    return message
        .replace(Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?\\b"), address)
        .replace(Regex("(?i)(token|secret|authorization)=?[^\\s,;]+"), "${'$'}1=$redacted")
}

@Composable
private fun LabeledColumn(
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    summaryColor: Color = FlickColor.OnSurfaceDim,
) {
    // Title and summary carried the same 24 sp, so a row read as one undifferentiated
    // block. bodyLarge over bodySmall separates them by size as well as weight.
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = FlickType.body(sizeSp = 18, weight = FontWeight.Bold),
            color = FlickColor.OnSurface,
        )
        Text(
            text = summary,
            style = FlickType.body(sizeSp = 15, weight = FontWeight.Medium),
            color = summaryColor,
        )
    }
}

/** Amber = on, per the §2c role split; the track never carries the focus ring. */
@Composable
private fun ToggleGlyph(enabled: Boolean) {
    val reducedMotion = LocalReducedMotion.current
    // Fills and inks take the effects spec: a colour that overshot its target
    // would read as a flash rather than as a state change.
    // All four are held as State and read in the layer / draw lambdas below, so a
    // flick of the toggle repaints the toggle instead of recomposing it once a
    // frame for the length of the throw.
    val fill = animateColorAsState(
        targetValue = if (enabled) FlickColor.SelectedFill else FlickColor.ControlFill,
        animationSpec = if (reducedMotion) tween(durationMillis = 0) else FlickMotion.stateEffects(),
        label = "toggleFill",
    )
    val border = animateColorAsState(
        targetValue = if (enabled) FlickColor.SelectedBorder else FlickColor.Outline,
        animationSpec = if (reducedMotion) tween(durationMillis = 0) else FlickMotion.stateEffects(),
        label = "toggleBorder",
    )
    // The knob is geometry, so it takes the spatial spring and is allowed to
    // arrive with a settle at the end of its travel.
    val knobTravel = animateFloatAsState(
        targetValue = if (enabled) 1f else 0f,
        animationSpec = if (reducedMotion) tween(durationMillis = 0) else FlickMotion.flickSettleSpatial(),
        label = "toggleKnob",
    )
    val knobColor = animateColorAsState(
        targetValue = if (enabled) FlickColor.Spark else FlickColor.OnSurfaceFaint,
        animationSpec = if (reducedMotion) tween(durationMillis = 0) else FlickMotion.stateEffects(),
        label = "toggleKnobColor",
    )
    Box(
        modifier = Modifier
            .size(width = 45.dp, height = 24.dp)
            .clip(FlickShape.Pill)
            .flickPlate(
                shape = FlickShape.Pill,
                fill = fill,
                stroke = border,
                strokeWidth = FlickDimens.Hairline,
            ),
    ) {
        // The knob keeps its 18 dp layout box and 21 dp of travel; the throw and
        // the squash are both graphicsLayer, so a toggle never relayouts its row.
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 3.dp)
                .size(18.dp)
                .graphicsLayer {
                    val travel = knobTravel.value
                    translationX = travel * ToggleKnobTravel.toPx()
                    val speed = sin(travel.coerceIn(0f, 1f) * PI).toFloat()
                    scaleX = 1f + (ToggleKnobStretch - 1f) * speed
                }
                .drawBehind {
                    drawCircle(knobColor.value)
                },
        )
    }
}
