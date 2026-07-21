package com.flick.receiver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.ui.components.FlickTvButton
import com.flick.receiver.ui.components.FlickTvRow
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickType
import com.flick.receiver.ui.theme.rememberTvSafeAreaPadding
import com.flick.receiver.util.FlickLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * T10a · Settings. The old always-on developer HUD survives here as one row —
 * "Playback metrics overlay", off by default, phrased for the curious. Focus
 * lands on that toggle.
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
        // The focused control scales and casts a 16dp shadow. Keep both inside
        // the scroll viewport instead of allowing the last row to be clipped.
        bottom = safeArea.calculateBottomPadding() + 34.dp,
    )
    val metricsFocus = remember { FocusRequester() }
    var confirmForget by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { metricsFocus.requestFocus() } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(FlickColor.Surface),
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
                    fontFamily = FlickType.Display,
                    fontWeight = FontWeight.Bold,
                    fontSize = 34.sp,
                    color = FlickColor.OnSurface,
                )
            }

            item(key = "rename") {
                FlickTvRow(onClick = onRename, modifier = Modifier.fillMaxWidth()) {
                    LabeledColumn(
                        modifier = Modifier.weight(1f),
                        title = stringResource(R.string.settings_device_name),
                        summary = tvName,
                    )
                    Text(
                        stringResource(R.string.settings_disclosure),
                        fontSize = 24.sp,
                        color = FlickColor.OnSurfaceFaint,
                    )
                }
            }

            item(key = "pairedPhones") {
                // Static info row (not a focus target).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(11.dp))
                        .background(FlickColor.SurfaceRaisedAlt)
                        .padding(horizontal = 18.dp, vertical = 16.dp),
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
                ) {
                    LabeledColumn(
                        modifier = Modifier.weight(1f),
                        title = stringResource(R.string.settings_forget_all),
                        summary = stringResource(if (confirmForget) R.string.settings_forget_all_confirm else R.string.settings_forget_all_summary),
                    )
                }
            }

            // Self-diagnosing TV: the same FlickTV lines adb would show, without a
            // laptop. Memory-only; nothing here is persisted.
            item(key = "diagnostics") {
                FlickTvRow(onClick = onToggleDiagnostics, modifier = Modifier.fillMaxWidth()) {
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
                            .clip(RoundedCornerShape(11.dp))
                            .background(FlickColor.SurfaceRaisedAlt)
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (diagnostics.isEmpty()) {
                            Text(
                                text = stringResource(R.string.settings_diagnostics_empty),
                                style = FlickType.monoTabular(sizeSp = 24, weight = FontWeight.Normal),
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
                                    style = FlickType.monoTabular(sizeSp = 24, weight = FontWeight.Normal),
                                    color = FlickColor.OnSurfaceDim,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
                item(key = "clearDiagnostics") {
                    FlickTvRow(onClick = onClearDiagnostics, modifier = Modifier.fillMaxWidth()) {
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
                    FlickTvButton(onClick = onDone) {
                        Text(stringResource(R.string.settings_done), fontSize = 24.sp, color = FlickColor.OnSurface)
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
private fun LabeledColumn(title: String, summary: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = FlickColor.OnSurface)
        Text(summary, fontSize = 24.sp, color = FlickColor.OnSurfaceDim)
    }
}

@Composable
private fun ToggleGlyph(enabled: Boolean) {
    Box(
        modifier = Modifier
            .size(width = 56.dp, height = 30.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (enabled) FlickColor.Link.copy(alpha = 0.9f) else FlickColor.OnSurface.copy(alpha = 0.18f)),
        contentAlignment = if (enabled) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .size(22.dp)
                .drawBehind {
                    drawCircle(if (enabled) FlickColor.Canvas else FlickColor.OnSurfaceDim)
                },
        )
    }
}
