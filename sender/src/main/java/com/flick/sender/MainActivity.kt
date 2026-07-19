package com.flick.sender

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FlickTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    SenderApp()
                }
            }
        }
    }
}

@Composable
private fun SenderApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by ServerStateHolder.state.collectAsState()

    // POST_NOTIFICATIONS (API 33+) so the foreground-service notification shows.
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result ignored: serving still works, notification just stays hidden */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Whether the OS may kill the server for battery reasons. Re-checked on every
    // resume so the exemption card disappears once the user grants it in Settings.
    var ignoringBattery by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ignoringBattery = isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) {
            ServerStateHolder.beginStarting()
            scope.launch {
                val meta = withContext(Dispatchers.IO) {
                    MediaMeta.resolve(context.contentResolver, uri)
                }
                val ip = withContext(Dispatchers.IO) { NetworkUtils.getSiteLocalIpv4() }
                if (ip == null) {
                    // No point starting a foreground service with no reachable IP.
                    // If one was already serving the previous video, stop it too —
                    // otherwise the UI shows ERROR while the old server stays up,
                    // leaving state and reality divergent.
                    CastServerService.stop(context)
                    ServerStateHolder.setError(context.getString(R.string.error_no_lan))
                } else {
                    ServerStateHolder.setStarting(meta.displayName, meta.size, ip)
                    CastServerService.start(context, uri, meta.displayName, meta.size)
                }
            }
        }
    }

    SenderScreen(
        state = state,
        showBatteryWarning = !ignoringBattery,
        onPick = {
            pickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
            )
        },
        onStop = { CastServerService.stop(context) },
        onRequestBatteryExemption = {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }
        },
    )
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(PowerManager::class.java) ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

@Composable
private fun SenderScreen(
    state: ServerUiState,
    showBatteryWarning: Boolean,
    onPick: () -> Unit,
    onStop: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Flick Sender",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Direct-play your own videos to the TV over Wi-Fi — no transcoding, no mirroring.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))

        when (state.status) {
            ServerStatus.IDLE -> IdleContent(onPick)
            ServerStatus.STARTING -> StartingContent(state)
            ServerStatus.RUNNING -> RunningContent(
                state = state,
                showBatteryWarning = showBatteryWarning,
                onPick = onPick,
                onStop = onStop,
                onRequestBatteryExemption = onRequestBatteryExemption,
            )
            ServerStatus.ERROR -> ErrorContent(state, onPick)
        }
    }
}

@Composable
private fun IdleContent(onPick: () -> Unit) {
    Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
        Text("Pick video")
    }
    Spacer(Modifier.height(12.dp))
    Text(
        text = "You'll be able to choose a video without granting any storage permission.",
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StartingContent(state: ServerUiState) {
    CircularProgressIndicator()
    Spacer(Modifier.height(16.dp))
    Text(
        text = state.displayName?.let { "Starting server for \"$it\"…" } ?: "Starting server…",
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun RunningContent(
    state: ServerUiState,
    showBatteryWarning: Boolean,
    onPick: () -> Unit,
    onStop: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
) {
    if (showBatteryWarning) {
        var batteryCardDismissed by remember { mutableStateOf(false) }
        if (!batteryCardDismissed) {
            BatteryOptimizationCard(
                onAllow = onRequestBatteryExemption,
                onDismiss = { batteryCardDismissed = true },
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            LabeledValue("Now serving", state.displayName ?: "Selected video")
            Spacer(Modifier.height(4.dp))
            LabeledValue("Size", formatBytes(state.sizeBytes))
        }
    }

    Spacer(Modifier.height(16.dp))
    ServingDiagnostics()

    Spacer(Modifier.height(24.dp))

    Text(
        text = "Open this on the TV",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(8.dp))
    SelectionContainer {
        Text(
            text = state.videoUrl ?: "",
            fontFamily = FontFamily.Monospace,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Spacer(Modifier.height(16.dp))
    Text(
        text = "Health check",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    SelectionContainer {
        Text(
            text = state.pingUrl ?: "",
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Spacer(Modifier.height(32.dp))
    Button(
        onClick = onStop,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
    ) {
        Text("Stop serving")
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
        Text("Pick a different video")
    }
}

@Composable
private fun BatteryOptimizationCard(onAllow: () -> Unit, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.battery_opt_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.battery_opt_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.battery_opt_dismiss))
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onAllow) {
                    Text(stringResource(R.string.battery_opt_allow))
                }
            }
        }
    }
}

/**
 * Live serving diagnostics: server throughput / total / last-request age and this
 * phone's own Wi-Fi band. Owns its own ~1s (telemetry) and ~3s (Wi-Fi) tickers,
 * gated on the Activity being STARTED so it only polls while the session is
 * actually on screen — backgrounding / screen-off pauses the polling.
 */
@Composable
private fun ServingDiagnostics() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val stats by TransferTelemetry.stats.collectAsState()
    var nowMs by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    var wifi by remember { mutableStateOf<WifiLinkInfo?>(null) }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                TransferTelemetry.refresh()
                nowMs = SystemClock.elapsedRealtime()
                delay(TELEMETRY_REFRESH_MS)
            }
        }
    }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                wifi = withContext(Dispatchers.IO) { NetworkUtils.getWifiLinkInfo(context) }
                delay(WIFI_POLL_MS)
            }
        }
    }

    val lastRequestText = if (stats.lastRequestAtMs == 0L) {
        stringResource(R.string.telemetry_last_request_idle)
    } else {
        val agoS = ((nowMs - stats.lastRequestAtMs) / 1000L).coerceAtLeast(0L)
        stringResource(R.string.telemetry_last_request_value, agoS)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            LabeledValue(
                stringResource(R.string.telemetry_throughput),
                stringResource(R.string.telemetry_throughput_value, stats.bitsPerSec / 1_000_000.0),
            )
            Spacer(Modifier.height(4.dp))
            LabeledValue(stringResource(R.string.telemetry_total), formatBytes(stats.totalBytes))
            Spacer(Modifier.height(4.dp))
            LabeledValue(stringResource(R.string.telemetry_last_request), lastRequestText)
            Spacer(Modifier.height(4.dp))
            LabeledValue(stringResource(R.string.telemetry_inflight), stats.inFlight.toString())
            wifi?.let { link ->
                Spacer(Modifier.height(4.dp))
                LabeledValue(stringResource(R.string.wifi_link_label), wifiLinkText(link))
            }
        }
    }

    if (wifi?.band == WifiBand.GHZ_24) {
        Spacer(Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Text(
                text = stringResource(R.string.wifi_24ghz_warning),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun wifiLinkText(link: WifiLinkInfo): String {
    val band = when (link.band) {
        WifiBand.GHZ_6 -> stringResource(R.string.wifi_band_6ghz)
        WifiBand.GHZ_5 -> stringResource(R.string.wifi_band_5ghz)
        WifiBand.GHZ_24 -> stringResource(R.string.wifi_band_24ghz)
    }
    return stringResource(R.string.wifi_link_value, band, link.linkSpeedMbps, link.rssiDbm)
}

@Composable
private fun ErrorContent(state: ServerUiState, onPick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Text(
            text = state.errorMessage ?: "Something went wrong.",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Spacer(Modifier.height(24.dp))
    Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
        Text("Pick video")
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

private const val TELEMETRY_REFRESH_MS = 1000L
private const val WIFI_POLL_MS = 3000L

private fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "Unknown size"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var i = 0
    while (value >= 1024.0 && i < units.size - 1) {
        value /= 1024.0
        i++
    }
    return String.format(Locale.US, "%.2f %s", value, units[i])
}
