package com.flick.receiver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
    val metricsFocus = remember { FocusRequester() }
    var confirmForget by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { metricsFocus.requestFocus() } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(FlickColor.Surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .align(Alignment.TopStart)
                .padding(horizontal = 48.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                fontFamily = FlickType.Display,
                fontWeight = FontWeight.Bold,
                fontSize = 34.sp,
                color = FlickColor.OnSurface,
            )

            FlickTvRow(onClick = onRename, modifier = Modifier.fillMaxWidth()) {
                LabeledColumn(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.settings_device_name),
                    summary = tvName,
                )
                Text("›", fontSize = 24.sp, color = FlickColor.OnSurfaceFaint)
            }

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

            // Self-diagnosing TV: the same FlickTV lines adb would show, without a
            // laptop. Memory-only; nothing here is persisted.
            FlickTvRow(onClick = onToggleDiagnostics, modifier = Modifier.fillMaxWidth()) {
                LabeledColumn(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.settings_diagnostics_title),
                    summary = stringResource(R.string.settings_diagnostics_summary),
                )
                ToggleGlyph(enabled = diagnosticsVisible)
            }

            if (diagnosticsVisible) {
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
                            style = FlickType.monoTabular(sizeSp = 20, weight = FontWeight.Normal),
                            color = FlickColor.OnSurfaceFaint,
                        )
                    } else {
                        val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
                        diagnostics.forEach { entry ->
                            Text(
                                text = "${clock.format(Date(entry.atMs))} ${entry.level} [${entry.area}] ${entry.message}",
                                style = FlickType.monoTabular(sizeSp = 20, weight = FontWeight.Normal),
                                color = FlickColor.OnSurfaceDim,
                                maxLines = 1,
                            )
                        }
                    }
                }
                FlickTvRow(onClick = onClearDiagnostics, modifier = Modifier.fillMaxWidth()) {
                    LabeledColumn(
                        modifier = Modifier.weight(1f),
                        title = stringResource(R.string.settings_diagnostics_clear),
                        summary = stringResource(R.string.settings_diagnostics_capture),
                    )
                }
            }

            Box(Modifier.padding(top = 10.dp)) {
                FlickTvButton(onClick = onDone) {
                    Text(stringResource(R.string.settings_done), fontSize = 24.sp, color = FlickColor.OnSurface)
                }
            }
        }
    }
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
