package com.flick.receiver.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.ui.components.FlickTvButton
import com.flick.receiver.ui.components.FlickTvRow
import com.flick.receiver.ui.components.FocusBeaconHost
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
private const val SettingsMetricsKey = "metrics"
private const val SettingsForgetKey = "forgetAll"
private const val SettingsDiagnosticsKey = "diagnostics"
private const val SettingsDiagnosticEntriesKey = "diagnosticEntries"
private const val SettingsClearDiagnosticsKey = "clearDiagnostics"
private const val SettingsDoneKey = "done"

/**
 * The first-appearance stagger. It covers the five rows that are always present
 * and never carries the two that own `bringIntoViewRequester`s — that machinery
 * reads layout coordinates, and it must not see anything moving. A sixth of the
 * entrance run is ~40 ms on the spring, the gap at which a column reads as
 * arriving in order.
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
 */
private fun Modifier.settingsStage(progress: () -> Float, index: Int): Modifier = graphicsLayer {
    val stage = settingsStageProgress(progress(), index)
    alpha = stage
    translationY = (1f - stage) * SettingsStageRise.toPx()
}

private data class SettingsLayoutEpoch(
    val diagnosticsVisible: Boolean,
    val diagnostics: List<FlickLog.Entry>,
    val density: Float,
    val fontScale: Float,
)
/**
 * T10a · Settings. The old always-on developer HUD survives here as one row —
 * "Playback metrics overlay", off by default, phrased for the curious. Focus
 * begins on Device name, then moves through metrics, forget-all, diagnostics,
 * and Done along the D-pad path `SettingsScreenFocusTest` walks.
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
    val clearBringIntoView = remember { BringIntoViewRequester() }
    val doneBringIntoView = remember { BringIntoViewRequester() }
    var clearFocused by remember { mutableStateOf(false) }
    var doneFocused by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val layoutEpoch = SettingsLayoutEpoch(
        diagnosticsVisible = diagnosticsVisible,
        diagnostics = diagnostics,
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
    val reducedMotion = LocalReducedMotion.current
    val entranceSpec: FiniteAnimationSpec<Float> = FlickMotion.panelSpatial()
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) entrance.snapTo(1f) else entrance.animateTo(1f, entranceSpec)
    }
    val stage = { entrance.value }
    LaunchedEffect(Unit) { runCatching { renameFocus.requestFocus() } }
    LaunchedEffect(layoutEpoch, clearPlacedEpoch, donePlacedEpoch, clearFocused, doneFocused) {
        when {
            clearFocused && clearPlacedEpoch == layoutEpoch -> clearBringIntoView.bringIntoView()
            doneFocused && donePlacedEpoch == layoutEpoch -> doneBringIntoView.bringIntoView()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .idleAmbientBackground(),
    ) {
        // The header measures its own font-scaled line box. The scrolling
        // viewport is a sibling below it, so no focused row can paint behind or
        // push the heading into the physical overscan area.
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
                text = stringResource(R.string.settings_title),
                style = FlickType.display(sizeSp = 27),
                color = FlickColor.OnSurface,
            )
        }

        // The travelling ring lives above the scroll viewport, so it glides
        // between rows as one object instead of appearing and vanishing per row —
        // and it is never clipped by the LazyColumn's own scroll clip.
        FocusBeaconHost(modifier = Modifier.fillMaxWidth().weight(1f)) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides settingsBringIntoViewSpec) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("settings-scroll-viewport"),
                contentPadding = settingsContentPadding,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
            item(key = SettingsRenameKey) {
                FlickTvRow(
                    onClick = onRename,
                    focusRequester = renameFocus,
                    modifier = Modifier
                        .fillMaxWidth()
                        .settingsStage(stage, index = 0)
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

            item(key = "pairedPhones") {
                // Static info row (not a focus target).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .settingsStage(stage, index = 1)
                        .testTag("settings-paired-row")
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

            item(key = SettingsMetricsKey) {
                FlickTvRow(
                    onClick = onToggleMetrics,
                    checked = metricsEnabled,
                    stateDescription = stringResource(
                        if (metricsEnabled) R.string.settings_metrics_on else R.string.settings_metrics_off,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .settingsStage(stage, index = 2)
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

            item(key = SettingsForgetKey) {
                FlickTvRow(
                    onClick = {
                        if (confirmForget) onForgetAll() else confirmForget = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .settingsStage(stage, index = 3)
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

            // Self-diagnosing TV: the same FlickTV lines adb would show, without a
            // laptop. Memory-only; nothing here is persisted.
            item(key = SettingsDiagnosticsKey) {
                FlickTvRow(
                    onClick = onToggleDiagnostics,
                    checked = diagnosticsVisible,
                    stateDescription = stringResource(
                        if (diagnosticsVisible) R.string.settings_metrics_on else R.string.settings_metrics_off,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .settingsStage(stage, index = 4)
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

            if (diagnosticsVisible) {
                item(key = SettingsDiagnosticEntriesKey) {
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
                                    // One line per entry, so the size sets how much
                                    // of a log line survives: Geist Mono advances
                                    // 0.6 em, and the 824 dp available inside the
                                    // safe area carries 98 characters at 14 sp
                                    // against 58 at 20 sp.
                                    style = FlickType.monoTabular(sizeSp = 14, weight = FontWeight.Medium),
                                    color = FlickColor.OnSurfaceDim,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                item(key = SettingsClearDiagnosticsKey) {
                    FlickTvRow(
                        onClick = onClearDiagnostics,
                        // No entrance stage on this row: it owns a
                        // BringIntoViewRequester, and that machinery must never
                        // measure a row that is still moving.
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
    val fill by animateColorAsState(
        targetValue = if (enabled) FlickColor.SelectedFill else FlickColor.ControlFill,
        animationSpec = if (reducedMotion) tween(durationMillis = 0) else FlickMotion.stateEffects(),
        label = "toggleFill",
    )
    val border by animateColorAsState(
        targetValue = if (enabled) FlickColor.SelectedBorder else FlickColor.Outline,
        animationSpec = if (reducedMotion) tween(durationMillis = 0) else FlickMotion.stateEffects(),
        label = "toggleBorder",
    )
    // The knob is geometry, so it takes the spatial spring and is allowed to
    // arrive with a settle at the end of its travel.
    val knobTravel by animateFloatAsState(
        targetValue = if (enabled) 1f else 0f,
        animationSpec = if (reducedMotion) tween(durationMillis = 0) else FlickMotion.flickSettleSpatial(),
        label = "toggleKnob",
    )
    val knobColor by animateColorAsState(
        targetValue = if (enabled) FlickColor.Spark else FlickColor.OnSurfaceFaint,
        animationSpec = if (reducedMotion) tween(durationMillis = 0) else FlickMotion.stateEffects(),
        label = "toggleKnobColor",
    )
    Box(
        modifier = Modifier
            .size(width = 45.dp, height = 24.dp)
            .clip(FlickShape.Pill)
            .background(fill)
            .border(
                FlickDimens.Hairline,
                border,
                FlickShape.Pill,
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
                    translationX = knobTravel * ToggleKnobTravel.toPx()
                    val speed = sin(knobTravel.coerceIn(0f, 1f) * PI).toFloat()
                    scaleX = 1f + (ToggleKnobStretch - 1f) * speed
                }
                .drawBehind {
                    drawCircle(knobColor)
                },
        )
    }
}
