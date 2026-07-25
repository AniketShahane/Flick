package com.flick.receiver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.ui.components.FlickTvButton
import com.flick.receiver.ui.components.FlickTvRow
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickShape
import com.flick.receiver.ui.theme.FlickType
import com.flick.receiver.ui.theme.idleAmbientBackground
import com.flick.receiver.ui.theme.rememberTvSafeAreaPadding
import com.flick.receiver.util.FlickLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Row padding shared by every focusable settings row and the static info row. */
private val RowPadding = PaddingValues(horizontal = 20.dp, vertical = 15.dp)

/**
 * T10a · Settings. The old always-on developer HUD survives here as one row —
 * "Playback metrics overlay", off by default, phrased for the curious. Focus
 * lands on that toggle, and the row order below it (forget-all → diagnostics →
 * done) is the D-pad path `SettingsScreenFocusTest` walks.
 */
@Composable
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
        start = safeArea.calculateStartPadding(layoutDirection),
        top = safeArea.calculateTopPadding() + 16.dp,
        end = safeArea.calculateEndPadding(layoutDirection),
        // The focused control scales and carries a detached ring outside its own
        // bounds. Keep both inside the scroll viewport instead of allowing the
        // last row to be clipped.
        bottom = safeArea.calculateBottomPadding() + 34.dp,
    )
    val metricsFocus = remember { FocusRequester() }
    var confirmForget by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { metricsFocus.requestFocus() } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .idleAmbientBackground(),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.TopStart),
            contentPadding = settingsContentPadding,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "title") {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = FlickType.display(sizeSp = 34),
                    color = FlickColor.OnSurface,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            item(key = "rename") {
                FlickTvRow(
                    onClick = onRename,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = RowPadding,
                ) {
                    LabeledColumn(
                        modifier = Modifier.weight(1f),
                        title = stringResource(R.string.settings_device_name),
                        summary = tvName,
                    )
                    Text(
                        text = stringResource(R.string.settings_disclosure),
                        style = FlickType.body(sizeSp = 24),
                        color = FlickColor.OnSurfaceFaint,
                    )
                }
            }

            item(key = "pairedPhones") {
                // Static info row (not a focus target).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(FlickShape.Md)
                        .background(FlickColor.SurfaceRaisedAlt)
                        .border(1.dp, FlickColor.OutlineHairline, FlickShape.Md)
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

            item(key = "metrics") {
                FlickTvRow(
                    onClick = onToggleMetrics,
                    focusRequester = metricsFocus,
                    modifier = Modifier.fillMaxWidth(),
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

            item(key = "forgetAll") {
                FlickTvRow(
                    onClick = {
                        if (confirmForget) onForgetAll() else confirmForget = true
                    },
                    modifier = Modifier.fillMaxWidth(),
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
            item(key = "diagnostics") {
                FlickTvRow(
                    onClick = onToggleDiagnostics,
                    modifier = Modifier.fillMaxWidth(),
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
                item(key = "diagnosticEntries") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(FlickShape.Md)
                            .background(FlickColor.SurfaceRaisedAlt)
                            .border(1.dp, FlickColor.OutlineHairline, FlickShape.Md)
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        if (diagnostics.isEmpty()) {
                            Text(
                                text = stringResource(R.string.settings_diagnostics_empty),
                                style = FlickType.monoTabular(sizeSp = 20, weight = FontWeight.Medium),
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
                                    style = FlickType.monoTabular(sizeSp = 20, weight = FontWeight.Medium),
                                    color = FlickColor.OnSurfaceDim,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                item(key = "clearDiagnostics") {
                    FlickTvRow(
                        onClick = onClearDiagnostics,
                        modifier = Modifier.fillMaxWidth(),
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

            item(key = "done") {
                Box(Modifier.padding(top = 10.dp)) {
                    FlickTvButton(
                        onClick = onDone,
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 11.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_done),
                            style = FlickType.body(sizeSp = 24),
                            color = FlickColor.OnSurface,
                        )
                    }
                }
            }
        }
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
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = FlickType.body(sizeSp = 24, weight = FontWeight.Bold),
            color = FlickColor.OnSurface,
        )
        Text(
            text = summary,
            style = FlickType.body(sizeSp = 24, weight = FontWeight.Medium),
            color = summaryColor,
        )
    }
}

/** Amber = on, per the §2c role split; the track never carries the focus ring. */
@Composable
private fun ToggleGlyph(enabled: Boolean) {
    Box(
        modifier = Modifier
            .size(width = 56.dp, height = 30.dp)
            .clip(FlickShape.Pill)
            .background(if (enabled) FlickColor.SelectedFill else FlickColor.ControlFill)
            .border(
                1.dp,
                if (enabled) FlickColor.SelectedBorder else FlickColor.Outline,
                FlickShape.Pill,
            ),
        contentAlignment = if (enabled) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .size(22.dp)
                .drawBehind {
                    drawCircle(if (enabled) FlickColor.Spark else FlickColor.OnSurfaceFaint)
                },
        )
    }
}
