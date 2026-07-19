package com.flick.receiver

import android.graphics.Color as AndroidColor
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.Window
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import com.flick.receiver.net.PreflightProbe
import com.flick.receiver.net.ProbeResult
import com.flick.receiver.player.DiagnosticsSnapshot
import com.flick.receiver.player.PlayerController
import com.flick.receiver.ui.CheckingCard
import com.flick.receiver.ui.DebugOverlay
import com.flick.receiver.ui.DiagnosisCard
import com.flick.receiver.util.RefreshRateHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Pre-filled URL — the user types the phone's LAN IP into the blank between
 * "//" and ":8080". Matches the shared HTTP contract (GET /video on :8080).
 */
const val DEFAULT_VIDEO_URL: String = "http://:8080/video"

/** Overlay refresh cadence: ~2x/sec. */
private const val OVERLAY_REFRESH_MS = 500L

/**
 * Drives what the play area shows: the live overlay ([Idle]/[Playing]), the brief
 * pre-flight [Checking] state, or a [Failed] diagnosis card. A pre-flight probe
 * gates every user-initiated session so a doomed cast never starts silently.
 */
private sealed interface SessionState {
    data object Idle : SessionState
    data object Checking : SessionState
    data object Playing : SessionState
    data class Failed(val result: ProbeResult) : SessionState
}

@Composable
fun ReceiverApp(window: Window) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { PlayerController(context) }

    // Player lifecycle: create/restore on ON_START, release decoder on ON_STOP,
    // terminal release on dispose. onStart() is idempotent and also called once
    // up-front (the observer misses an ON_START that already fired).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.onStart()
                Lifecycle.Event.ON_STOP -> controller.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        controller.onStart()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.release()
        }
    }

    var urlText by rememberSaveable { mutableStateOf(DEFAULT_VIDEO_URL) }
    var snapshot by remember { mutableStateOf(DiagnosticsSnapshot.EMPTY) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var sessionState by remember { mutableStateOf<SessionState>(SessionState.Idle) }

    // Pre-flight probe job — cancelled (and superseded) on every re-run so a stale
    // probe can never resolve over a newer one. The scope is composition-bound, so
    // the probe is torn down with the screen.
    val scope = rememberCoroutineScope()
    var probeJob by remember { mutableStateOf<Job?>(null) }

    // URL known -> probe off the main thread -> play only on success, else diagnose.
    val startSession: () -> Unit = {
        val url = urlText.trim()
        probeJob?.cancel()
        sessionState = SessionState.Checking
        probeJob = scope.launch {
            when (val result = PreflightProbe.probe(url)) {
                is ProbeResult.Ok -> {
                    // If ON_STOP fired mid-probe (Home pressed during the 1-8s window),
                    // controller.onStop() already released the player. Never start a new
                    // session into a stopped activity — that would create + prepare an
                    // ExoPlayer and play audio into the home screen, holding the decoder
                    // and network while backgrounded (breaks onStop's contract).
                    if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        controller.play(url)
                        controller.recordProbeLatency(result.latencyMs)
                        sessionState = SessionState.Playing
                    } else {
                        sessionState = SessionState.Idle
                    }
                }
                else -> sessionState = SessionState.Failed(result)
            }
        }
    }
    val stopSession: () -> Unit = {
        probeJob?.cancel()
        controller.stop()
        sessionState = SessionState.Idle
    }

    // Poll the controller ~2x/sec and push a fresh snapshot into Compose state.
    LaunchedEffect(controller) {
        while (isActive) {
            snapshot = controller.snapshot()
            delay(OVERLAY_REFRESH_MS)
        }
    }

    // Refresh-rate matching, best-effort, once the content frame rate is known.
    LaunchedEffect(snapshot.frameRate) {
        val fps = snapshot.frameRate
        if (fps > 0f) {
            RefreshRateHelper.applyToWindow(window, fps)
            val surface = (playerView?.videoSurfaceView as? SurfaceView)?.holder?.surface
            RefreshRateHelper.applyToSurface(surface, fps)
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                ControlBar(
                    url = urlText,
                    onUrlChange = { urlText = it },
                    onPlay = startSession,
                    onStop = stopSession,
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    PlayerSurface(
                        controller = controller,
                        onViewAvailable = { playerView = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                    val cardModifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp)
                    when (val state = sessionState) {
                        SessionState.Checking -> CheckingCard(modifier = cardModifier)
                        is SessionState.Failed -> DiagnosisCard(
                            result = state.result,
                            onRetry = startSession,
                            modifier = cardModifier,
                        )
                        else -> DebugOverlay(
                            snapshot = snapshot,
                            url = urlText,
                            modifier = cardModifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlBar(
    url: String,
    onUrlChange: (String) -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
) {
    val urlFocusRequester = remember { FocusRequester() }
    // Land initial D-pad focus on the URL field so the remote can open the IME.
    LaunchedEffect(Unit) { runCatching { urlFocusRequester.requestFocus() } }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // OutlinedTextField comes from Compose material3 (TV material ships no
        // text field). Wrap it in a local dark material3 theme so it stays
        // readable over the dark TV background.
        androidx.compose.material3.MaterialTheme(
            colorScheme = androidx.compose.material3.darkColorScheme(),
        ) {
            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                singleLine = true,
                label = { androidx.compose.material3.Text("Sender video URL — type phone IP into the blank") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onPlay() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(urlFocusRequester),
            )
        }
        Button(onClick = onPlay) { Text("Play") }
        Button(onClick = onStop) { Text("Stop") }
    }
}

@Composable
private fun PlayerSurface(
    controller: PlayerController,
    onViewAvailable: (PlayerView) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier.clip(RoundedCornerShape(8.dp)),
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                setKeepContentOnPlayerReset(true)
                setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setBackgroundColor(AndroidColor.BLACK)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }.also(onViewAvailable)
        },
        // controller.player is Compose state — the view rebinds whenever the
        // ExoPlayer instance is recreated across stop/start cycles.
        update = { view -> view.player = controller.player },
    )
}
