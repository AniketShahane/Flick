package com.flick.receiver

import android.graphics.Color as AndroidColor
import android.os.Build
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.flick.receiver.net.ControlServer
import com.flick.receiver.net.LanAddress
import com.flick.receiver.net.NsdAdvertiser
import com.flick.receiver.net.PairingManager
import com.flick.receiver.player.DiagnosticsSnapshot
import com.flick.receiver.player.HdrType
import com.flick.receiver.player.PlaybackFrame
import com.flick.receiver.player.PlaybackPhase
import com.flick.receiver.player.PlayerController
import com.flick.receiver.session.MediaStage
import com.flick.receiver.session.SessionController
import com.flick.receiver.ui.components.FlickWordmark
import com.flick.receiver.ui.screens.ErrorScreen
import com.flick.receiver.ui.screens.IdleScreen
import com.flick.receiver.ui.screens.MetricsOverlay
import com.flick.receiver.ui.screens.PairScreen
import com.flick.receiver.ui.screens.PlaybackScreen
import com.flick.receiver.ui.screens.QualityInfo
import com.flick.receiver.ui.screens.SettingsScreen
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickTvTheme
import com.flick.receiver.ui.theme.FlickType
import com.flick.receiver.ui.theme.OverscanSafe
import com.flick.receiver.util.RefreshRateHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive

/** Friendly-name presets cycled by the on-screen "Rename TV" (no keyboard on TV). */
private val TV_NAME_PRESETS = listOf("Living Room TV", "Bedroom TV", "Den TV", "Office TV", "Flick TV")

/**
 * The whole TV app: fixed cinematic dark, D-pad driven. It advertises the control
 * server over NSD, gates pairing, drives the player from control-channel commands,
 * and streams the confirmed position back at ~10 Hz — while preserving the
 * hardened media path (pre-flight probe, terminal-stop, hardware-only decode).
 */
@Composable
fun ReceiverApp(window: Window) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val controller = remember { PlayerController(context) }
    val pairing = remember { PairingManager(context) }
    val nsd = remember { NsdAdvertiser(context) }
    val scope = rememberCoroutineScope()
    val playbackFlow = remember { MutableStateFlow(PlaybackFrame.IDLE) }
    val session = remember {
        SessionController(
            controller = controller,
            scope = scope,
            lifecycleStarted = {
                lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            },
        )
    }
    val server = remember { ControlServer(pairing, session, { playbackFlow.value }) }

    var boundHost by remember { mutableStateOf<String?>(null) }
    var boundPort by remember { mutableStateOf(-1) }
    var tvName by remember { mutableStateOf(pairing.tvName) }
    var snapshot by remember { mutableStateOf(DiagnosticsSnapshot.EMPTY) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }

    var showSettings by remember { mutableStateOf(false) }
    var forcePairing by remember { mutableStateOf(false) }
    var metricsEnabled by rememberSaveable { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }

    // Pairing state read from private storage. Mirrored into Compose state (below)
    // because the pairing write happens off-composition on a control thread; the
    // poll loop republishes it so the UI reacts when a phone pairs.
    var paired by remember { mutableStateOf(pairing.isPaired()) }
    var pairedLabel by remember { mutableStateOf(pairing.pairedLabel()) }
    var pairCode by remember { mutableStateOf(pairing.currentCode()) }

    val playFocus = remember { FocusRequester() }
    // Holds D-pad focus while the transport chrome is hidden, so the remote never
    // goes dead (no focusable → key events would otherwise be unrouted).
    val rootFocus = remember { FocusRequester() }

    // Player lifecycle (preserved): create on ON_START, release decoder on ON_STOP,
    // terminal release + control server + NSD teardown on dispose (no leaks).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    controller.onStart()
                    if (boundPort > 0) {
                        nsd.register(tvName, boundPort, Build.MODEL ?: "Android TV", NsdAdvertiser.STATE_READY)
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    controller.onStop()
                    // Publish a terminal sample so the phone stops rendering a
                    // healthy, playing, frozen playhead while the decoder is
                    // released and the TV is backgrounded (the state feed keeps
                    // emitting whatever value this flow holds).
                    playbackFlow.value = PlaybackFrame.IDLE
                    if (boundPort > 0) {
                        nsd.register(tvName, boundPort, Build.MODEL ?: "Android TV", NsdAdvertiser.STATE_SLEEPING)
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        controller.onStart()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.release()
            server.stop()
            nsd.unregister()
        }
    }

    // Bring up the control server + NSD advertising (LAN-bound; ephemeral port),
    // polling until the LAN IP is available and re-binding whenever it changes.
    // A one-shot bring-up left the TV undiscoverable forever when the app opened
    // before Wi-Fi associated, or bound to a dead IP after a DHCP lease change.
    LaunchedEffect(Unit) {
        while (isActive) {
            val host = LanAddress.current()
            if (host != null && host != boundHost) {
                // server.start() stops any previous binding before rebinding.
                val port = runCatching { server.start(host) }.getOrDefault(-1)
                if (port > 0) {
                    boundHost = host
                    boundPort = port
                    nsd.register(
                        serviceName = tvName,
                        port = port,
                        model = Build.MODEL ?: "Android TV",
                        state = NsdAdvertiser.STATE_READY,
                    )
                }
            }
            delay(2_000L)
        }
    }

    // ~10 Hz confirmed-position feed + slower diagnostics sampling. Gated on
    // STARTED so nothing polls a released player while backgrounded.
    LaunchedEffect(controller, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var tick = 0
            while (isActive) {
                val frame = controller.readPlaybackState()
                playbackFlow.value = frame
                session.syncTick(frame.posMs)
                if (frame.phase == PlaybackPhase.Error) session.onFatalPlaybackError()
                if (tick % 5 == 0) {
                    snapshot = controller.snapshot()
                    // Republish pairing state (mutableStateOf ignores equal writes,
                    // so this only recomposes when it actually changes).
                    paired = pairing.isPaired()
                    pairedLabel = pairing.pairedLabel()
                    pairCode = pairing.currentCode()
                }
                tick++
                delay(100L)
            }
        }
    }

    val frame by playbackFlow.collectAsState()
    val stage = session.stage

    // Let the session push TV→phone `error` frames through the live control
    // socket (preflight/backgrounded/fatal → phone S12 instead of a frozen UI).
    LaunchedEffect(server, session) { session.attachErrorChannel(server::sendError) }

    // Gate code-pairing on the pairing screen actually being displayed, so the
    // 4-digit code is never attackable while shown to no one (idle/playing).
    val pairScreenShown = stage == MediaStage.None && !showSettings && (forcePairing || !paired)
    LaunchedEffect(pairScreenShown) { server.acceptingPairings = pairScreenShown }

    // Return to baseline between casts: drop the detached PlayerView (+ its
    // SurfaceView) once the session leaves Active rather than holding it for hours.
    LaunchedEffect(stage) {
        if (stage != MediaStage.Active) playerView = null
    }

    // With chrome hidden there is no focusable transport, so park focus on the
    // root catcher; any D-pad press then re-summons the chrome (see rootKeys).
    LaunchedEffect(stage, chromeVisible) {
        if (stage == MediaStage.Active && !chromeVisible) {
            runCatching { rootFocus.requestFocus() }
        }
    }

    // Quality flourish (T8): show briefly whenever a fresh session becomes active.
    LaunchedEffect(stage) {
        if (stage == MediaStage.Active) {
            showQuality = true
            delay(4500L)
            showQuality = false
        } else {
            showQuality = false
        }
    }

    // Chrome reveal + auto-hide (chromeFade). Re-armed on every poke / state change.
    LaunchedEffect(session.chromePoke, frame.phase, session.seeking) {
        chromeVisible = true
        if (frame.phase == PlaybackPhase.Playing && !session.seeking) {
            delay(4000L)
            chromeVisible = false
        }
    }

    // Refresh-rate matching (preserved), best-effort once the frame rate is known.
    LaunchedEffect(snapshot.frameRate) {
        val fps = snapshot.frameRate
        if (fps > 0f) {
            RefreshRateHelper.applyToWindow(window, fps)
            val surface = (playerView?.videoSurfaceView as? SurfaceView)?.holder?.surface
            RefreshRateHelper.applyToSurface(surface, fps)
        }
    }

    val deviceLabel = pairedLabel

    // Root: media transport keys are tunnelled so the remote drives playback no
    // matter what holds D-pad focus; any key reveals the chrome.
    val rootKeys = Modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val active = stage == MediaStage.Active
        if (active) session.pokeChrome()
        when (event.key) {
            Key.MediaFastForward -> { session.onSkip(15_000L); true }
            Key.MediaRewind -> { session.onSkip(-15_000L); true }
            Key.MediaNext -> { session.onSkip(300_000L); true }
            Key.MediaPrevious -> { session.onSkip(-300_000L); true }
            Key.MediaPlay -> { session.onPlay(); true }
            Key.MediaPause -> { session.onPause(); true }
            Key.MediaPlayPause -> {
                if (frame.playing) session.onPause() else session.onPlay(); true
            }
            // While the chrome is hidden the root holds focus; the first D-pad
            // press only reveals the chrome (poked above) and is consumed so it
            // doesn't also activate a control on the way in.
            else -> active && !chromeVisible && isDpadKey(event.key)
        }
    }

    // TV Back convention: dismiss the top surface rather than kill the app + the
    // whole cast (finish() would release the player and tear down the servers).
    BackHandler(
        enabled = showSettings || forcePairing ||
            stage == MediaStage.Active || stage is MediaStage.Error,
    ) {
        when {
            showSettings -> showSettings = false
            forcePairing -> forcePairing = false
            stage == MediaStage.Active && chromeVisible -> chromeVisible = false
            stage == MediaStage.Active -> session.backToStandby()
            stage is MediaStage.Error -> session.backToStandby()
        }
    }

    FlickTvTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(FlickColor.Canvas)
                .then(rootKeys)
                .focusRequester(rootFocus)
                .focusable(enabled = stage == MediaStage.Active && !chromeVisible),
        ) {
            when (stage) {
                MediaStage.Active -> {
                    PlaybackScreen(
                        playing = frame.playing,
                        phase = frame.phase,
                        positionMs = frame.posMs,
                        durationMs = frame.durationMs,
                        bufferedMs = frame.bufferedMs,
                        targetMs = session.seekTargetMs,
                        seeking = session.seeking,
                        volume = frame.volume,
                        title = session.title,
                        deviceLabel = deviceLabel,
                        hdr = snapshot.hdrType,
                        chromeVisible = chromeVisible,
                        quality = if (showQuality) qualityInfo(snapshot) else null,
                        onBack10 = { session.onSkip(-10_000L) },
                        onPlayPause = { if (frame.playing) session.onPause() else session.onPlay() },
                        onForward10 = { session.onSkip(10_000L) },
                        onSetVolume = { session.onSetVolume(it) },
                        playFocusRequester = playFocus,
                    ) {
                        PlayerSurface(
                            controller = controller,
                            onViewAvailable = { playerView = it },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (metricsEnabled) {
                        MetricsOverlay(
                            snapshot = snapshot,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(OverscanSafe),
                        )
                    }
                }

                MediaStage.Checking -> ConnectingScreen()

                is MediaStage.Error -> ErrorScreen(
                    kind = stage.kind,
                    deviceLabel = deviceLabel,
                    // Both primaries re-probe: "Try again" now, "Keep waiting"
                    // retries until the phone is back (the position is held).
                    onPrimary = { session.retry() },
                    onSecondary = { session.backToStandby() },
                )

                MediaStage.None -> {
                    // Rendered exclusively (never overlaid) so no hidden screen's
                    // buttons stay focusable underneath — that let the D-pad reach
                    // invisible controls and lost focus on dismiss.
                    when {
                        showSettings -> SettingsScreen(
                            tvName = tvName,
                            pairedSummary = deviceLabel
                                ?: stringResource(R.string.settings_paired_none),
                            metricsEnabled = metricsEnabled,
                            onRename = {
                                val next = nextName(tvName)
                                pairing.tvName = next
                                tvName = next
                                if (boundPort > 0) {
                                    nsd.register(next, boundPort, Build.MODEL ?: "Android TV", NsdAdvertiser.STATE_READY)
                                }
                            },
                            onToggleMetrics = { metricsEnabled = !metricsEnabled },
                            onDone = { showSettings = false },
                        )

                        forcePairing || !paired -> PairScreen(
                            tvName = tvName,
                            code = pairCode,
                            qrPayload = pairing.qrPayload(boundHost ?: "", boundPort),
                            networkReady = boundHost != null && boundPort > 0,
                            onRename = {
                                val next = nextName(tvName)
                                pairing.tvName = next
                                tvName = next
                                if (boundPort > 0) {
                                    nsd.register(next, boundPort, Build.MODEL ?: "Android TV", NsdAdvertiser.STATE_READY)
                                }
                            },
                        )

                        else -> IdleScreen(
                            pairedLabel = deviceLabel,
                            onPairAnother = { forcePairing = true },
                            onOpenSettings = { showSettings = true },
                        )
                    }
                }
            }
        }
    }

    // A pairing screen forced from Idle returns to Idle once a new phone attaches.
    LaunchedEffect(forcePairing, stage) {
        if (forcePairing && stage != MediaStage.None) forcePairing = false
    }
}

@Composable
private fun ConnectingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FlickColor.Canvas),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FlickWordmark()
            Text(
                text = stringResource(R.string.connecting_title),
                fontFamily = FlickType.Display,
                fontWeight = FontWeight.Bold,
                fontSize = 34.sp,
                color = FlickColor.OnSurface,
            )
            Text(
                text = stringResource(R.string.connecting_detail),
                fontSize = 24.sp,
                color = FlickColor.OnSurfaceDim,
            )
        }
    }
}

@Composable
private fun PlayerSurface(
    controller: PlayerController,
    onViewAvailable: (PlayerView) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                setKeepContentOnPlayerReset(true)
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setBackgroundColor(AndroidColor.parseColor("#FF08070C"))
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

private fun nextName(current: String): String {
    val idx = TV_NAME_PRESETS.indexOf(current)
    return TV_NAME_PRESETS[(idx + 1).mod(TV_NAME_PRESETS.size)]
}

private fun isDpadKey(key: Key): Boolean =
    key == Key.DirectionLeft || key == Key.DirectionRight ||
        key == Key.DirectionUp || key == Key.DirectionDown ||
        key == Key.DirectionCenter || key == Key.Enter

private fun qualityInfo(s: DiagnosticsSnapshot): QualityInfo {
    // Honest quality read: real resolution + the HDR class actually being decoded
    // (never a hardcoded "Dolby Vision" for every stream).
    val resolution = when {
        s.width >= 3840 || s.height >= 2160 -> "4K"
        s.height >= 1440 -> "1440p"
        s.height >= 1080 -> "1080p"
        s.height >= 720 -> "720p"
        s.height > 0 -> "${s.height}p"
        else -> null
    }
    val hdr = when (s.hdrType) {
        HdrType.DOLBY_VISION -> "Dolby Vision"
        HdrType.HDR10 -> "HDR10"
        HdrType.NONE -> null
    }
    val label = listOfNotNull(resolution, hdr).joinToString(" · ").ifBlank { "Direct-play" }
    val throughput = buildString {
        val mbps = if (s.bitrateEstimateBps > 0L) s.bitrateEstimateBps / 1_000_000.0 else 0.0
        if (mbps > 0.0) append(String.format(java.util.Locale.US, "%.0f Mb/s", mbps))
        if (s.wifiBand != null) {
            if (isNotEmpty()) append(" · ")
            append(s.wifiBand)
        }
        if (isEmpty()) append("—")
    }
    val bars = when {
        s.wifiRssiDbm >= -55 -> 4
        s.wifiRssiDbm >= -65 -> 3
        s.wifiRssiDbm >= -75 -> 2
        s.wifiRssiDbm < 0 -> 1
        else -> 4
    }
    return QualityInfo(
        qualityLabel = label,
        decoder = s.decoderName ?: "—",
        throughput = throughput,
        bars = bars,
    )
}
