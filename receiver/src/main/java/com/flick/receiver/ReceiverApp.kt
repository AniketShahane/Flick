package com.flick.receiver

import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.os.Build
import android.util.TypedValue
import android.view.KeyEvent as AndroidKeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.accessibility.CaptioningManager
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
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.toArgb
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
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.flick.receiver.net.ControlPortStore
import com.flick.receiver.net.ControlServer
import com.flick.receiver.net.LanAddress
import com.flick.receiver.net.LanBindingMonitor
import com.flick.receiver.net.NsdAdvertiser
import com.flick.receiver.net.PairingManager
import com.flick.receiver.net.PairingSurface
import com.flick.receiver.net.ReceiverBindingGate
import com.flick.receiver.net.controlPortTier
import com.flick.receiver.player.DiagnosticsSnapshot
import com.flick.receiver.player.HdrType
import com.flick.receiver.player.PlaybackFrame
import com.flick.receiver.player.PlaybackPhase
import com.flick.receiver.player.PlayerController
import com.flick.receiver.player.SUBTITLE_GLYPH_BACKGROUND_ALPHA
import com.flick.receiver.player.SUBTITLE_WINDOW_ALPHA
import com.flick.receiver.player.reducedSubtitleTextSizeSp
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
import com.flick.receiver.ui.theme.rememberTvSafeAreaPadding
import com.flick.receiver.util.FlickLog
import com.flick.receiver.util.RefreshRateHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive

/** Friendly-name presets cycled by the on-screen "Rename TV" (no keyboard on TV). */
private val TV_NAME_PRESETS = listOf("Living Room TV", "Bedroom TV", "Den TV", "Office TV", "Flick TV")

/**
 * The address flow is the real trigger; this only covers an address change that
 * produced no connectivity callback at all. It is deliberately slow — the old
 * 2 s poll was the second half of the rebind-churn loop.
 */
private const val RECONCILE_SAFETY_NET_MS = 10_000L

/** Newest diagnostics lines rendered on the TV; the ring buffer itself holds 200. */
private const val DIAGNOSTICS_VISIBLE = 14

/**
 * The whole TV app: fixed cinematic dark, D-pad driven. It advertises the control
 * server over NSD, gates pairing, drives the player from control-channel commands,
 * and streams the confirmed position back at ~10 Hz — while preserving the
 * hardened media path (pre-flight probe, terminal-stop, hardware-only decode).
 */
@Composable
internal fun ReceiverApp(window: Window, remoteKeys: TvRemoteKeyDispatcher) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val controller = remember { PlayerController(context) }
    val pairing = remember { PairingManager(context) }
    val pairingSnapshot by pairing.snapshot.collectAsState()
    val nsd = remember { NsdAdvertiser(context) }
    val lanMonitor = remember { LanBindingMonitor(context) }
    val scope = rememberCoroutineScope()
    val playbackFlow = remember { MutableStateFlow(PlaybackFrame.IDLE) }
    var lifecycleStarted by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    val bindingGate = remember { ReceiverBindingGate(lifecycleStarted) }
    val session = remember {
        SessionController(
            controller = controller,
            scope = scope,
            lifecycleStarted = {
                lifecycleStarted
            },
        )
    }
    val server = remember { ControlServer(pairing, session, { playbackFlow.value }) }
    val portStore = remember { ControlPortStore(context) }
    var boundHost by remember { mutableStateOf<String?>(null) }
    var boundPort by remember { mutableStateOf(-1) }
    // Bind health, surfaced low-emphasis on the pair screen so a stale port is
    // visually distinguishable from a wrong code.
    var bindAtMs by remember { mutableStateOf(0L) }
    var bindUptimeSec by remember { mutableStateOf(0L) }
    var rebindCount by remember { mutableStateOf(0) }
    var lastTeardown by remember { mutableStateOf<String?>(null) }
    var showDiagnostics by remember { mutableStateOf(false) }
    val logRevision by FlickLog.revision.collectAsState()
    var tvName by remember { mutableStateOf(pairing.tvName) }
    var snapshot by remember { mutableStateOf(DiagnosticsSnapshot.EMPTY) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    // Movable content preserves the same AndroidView/Surface across the opaque
    // preparing overlay and the visible playback chrome.
    val playerSurface = remember(controller) {
        movableContentOf {
            PlayerSurface(
                controller = controller,
                onViewAvailable = { playerView = it },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    var showSettings by remember { mutableStateOf(false) }
    var metricsEnabled by rememberSaveable { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    var capturedRemoteButton by remember { mutableStateOf<TvRemoteButton?>(null) }
    var remoteSeekDeltaMs by remember { mutableStateOf<Long?>(null) }
    var remoteSeekSpeedLevel by remember { mutableStateOf(1) }
    var remoteSeekHeld by remember { mutableStateOf(false) }
    var remoteSeekGestureActive by remember { mutableStateOf(false) }
    var remoteSeekVisible by remember { mutableStateOf(false) }


    val playFocus = remember { FocusRequester() }
    // Holds D-pad focus while the transport chrome is hidden, so the remote never
    // goes dead (no focusable → key events would otherwise be unrouted).
    val rootFocus = remember { FocusRequester() }

    // Player lifecycle (preserved): create on ON_START, release decoder on ON_STOP,
    // terminal release + control server + NSD teardown on dispose (no leaks).
    DisposableEffect(lifecycleOwner) {
        lanMonitor.start()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    lifecycleStarted = true
                    bindingGate.onForeground()
                    controller.onStart()
                    pairing.onForeground()
                    if (boundPort > 0) {
                        FlickLog.i("nsd", "readvertise trigger=on_start port=$boundPort state=${NsdAdvertiser.STATE_READY}")
                        nsd.register(tvName, boundPort, Build.MODEL ?: "Android TV", NsdAdvertiser.STATE_READY, pairing.tvId)
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    // Stop reconciliation before it can rebind/advertise a released player.
                    lifecycleStarted = false
                    bindingGate.onBackground()
                    pairing.onBackground()
                    val teardown = session.forceLocalTeardown()
                    // The socket is NOT closed below any more, so this terminal
                    // actually reaches the phone instead of racing the close.
                    teardown.castId?.let { server.sendTerminal(it, com.flick.receiver.net.CastFailureCode.TV_BACKGROUNDED, false, beforeReady = teardown.beforeReady) }
                    controller.onStop()
                    // Publish a terminal sample so the phone stops rendering a
                    // healthy, playing, frozen playhead while the decoder is
                    // released and the TV is backgrounded (the state feed keeps
                    // emitting whatever value this flow holds).
                    playbackFlow.value = PlaybackFrame.IDLE
                    // A screensaver, Home press or a system dialog is a visibility
                    // change, not a network event. Tearing the socket down here
                    // rebound a NEW port on every resume, so the number on the pair
                    // screen and every persisted phone-side port died with it.
                    // ReceiverBindingGate already refuses loadMedia while
                    // backgrounded, so the posture is unchanged: the socket simply
                    // stops accepting new casts instead of vanishing.
                    if (boundPort > 0) {
                        lastTeardown = "on_stop"
                        FlickLog.i("nsd", "sleeping trigger=on_stop port=$boundPort")
                        nsd.register(tvName, boundPort, Build.MODEL ?: "Android TV", NsdAdvertiser.STATE_SLEEPING, pairing.tvId)
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleStarted) controller.onStart()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            lifecycleStarted = false
            bindingGate.onBackground()
            session.forceLocalTeardown()
            controller.release()
            FlickLog.i("bind", "teardown trigger=dispose")
            server.stopDetached()
            nsd.unregister()
            lanMonitor.stop()
        }
    }

    // The single owner of bind state. The LAN monitor only WAKES it — a capability
    // burst on an unchanged address resolves to "same address, do nothing" — and
    // the slow tick is a safety net for an address change that produced no
    // callback at all. repeatOnLifecycle (not a Boolean Compose key) drives it:
    // a false→true round trip between two compositions could otherwise complete
    // the coroutine permanently and leave the TV silently undiscoverable.
    LaunchedEffect(lifecycleOwner, server, lanMonitor) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            suspend fun release(trigger: String, previous: String?) {
                FlickLog.i("bind", "teardown trigger=$trigger prev=${previous ?: "none"}")
                lastTeardown = trigger
                session.forceLocalTeardown()
                server.stop()
                nsd.unregister()
                boundHost = null
                boundPort = -1
            }
            suspend fun reconcile() {
                if (!bindingGate.mayBindOrAdvertise()) return
                val host = LanAddress.current()
                if (host == null) {
                    if (boundHost != null) release("no_lan_address", boundHost)
                    return
                }
                if (host == boundHost) return
                // A new address is a control-loss boundary even when the old
                // address still exists: invalidate the cast before advertising a
                // new endpoint so a stale callback cannot reach a re-bound server.
                if (boundHost != null) {
                    FlickLog.i("bind", "rebind trigger=addr_changed old=$boundHost new=$host")
                    release("addr_changed", boundHost)
                    rebindCount++
                }
                val persisted = portStore.lastPort()
                val port = server.start(host, portStore.candidates())
                if (port <= 0) return
                portStore.remember(port)
                boundHost = host
                boundPort = port
                bindAtMs = android.os.SystemClock.elapsedRealtime()
                bindUptimeSec = 0L
                FlickLog.i("bind", "started host=$host port=$port tier=${controlPortTier(port, persisted)}")
                nsd.register(
                    serviceName = tvName,
                    port = port,
                    model = Build.MODEL ?: "Android TV",
                    state = NsdAdvertiser.STATE_READY,
                    tvId = pairing.tvId,
                )
            }
            merge(
                lanMonitor.address.map { Unit },
                flow { while (true) { emit(Unit); delay(RECONCILE_SAFETY_NET_MS) } },
            ).collect { reconcile() }
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
                    pairing.tick()
                }
                // Bind uptime only needs second resolution; refresh it rarely so
                // the diagnostics line does not recompose at 10 Hz.
                if (tick % 20 == 0 && bindAtMs > 0L) {
                    bindUptimeSec = (android.os.SystemClock.elapsedRealtime() - bindAtMs) / 1000L
                }
                tick++
                delay(100L)
            }
        }
    }

    val frame by playbackFlow.collectAsState()
    val stage = session.stage
    val surfaceMode = playerSurfaceMode(stage)

    // Let the session push TV→phone `error` frames through the live control
    // socket (preflight/backgrounded/fatal → phone S12 instead of a frozen UI).
    LaunchedEffect(server, session) { session.attachTerminal(server::sendTerminal); session.attachReady(server::sendReady) }

    // Return to baseline between casts: drop the detached PlayerView (+ its
    // SurfaceView) once the session leaves Active rather than holding it for hours.
    LaunchedEffect(surfaceMode) {
        if (surfaceMode == PlayerSurfaceMode.Hidden) playerView = null
    }

    // With chrome hidden there is no focusable transport, so park focus on the
    // root catcher. Activity-level remote routing handles playback commands;
    // keeping a Compose focus owner also preserves ordinary fallback dispatch.
    LaunchedEffect(stage, chromeVisible) {
        if (stage is MediaStage.Active && !chromeVisible) {
            runCatching { rootFocus.requestFocus() }
        }
    }

    // Quality flourish (T8): show briefly whenever a fresh session becomes active.
    LaunchedEffect(stage) {
        if (stage is MediaStage.Active) {
            showQuality = true
            delay(4500L)
            showQuality = false
        } else {
            showQuality = false
            capturedRemoteButton = null
            remoteSeekDeltaMs = null
            remoteSeekHeld = false
            remoteSeekGestureActive = false
            remoteSeekVisible = false
        }
    }

    // Hold the final seek delta briefly after key-up, then settle back to the
    // normal playback canvas. A new gesture cancels the pending dismissal.
    LaunchedEffect(remoteSeekGestureActive, remoteSeekDeltaMs) {
        if (!remoteSeekGestureActive && remoteSeekDeltaMs != null) {
            delay(700L)
            remoteSeekVisible = false
            delay(200L)
            if (remoteSeekGestureActive) return@LaunchedEffect
            remoteSeekDeltaMs = null
            remoteSeekHeld = false
            remoteSeekSpeedLevel = 1
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

    val deviceLabel = pairingSnapshot.mostRecentDeviceLabel

    val handleRemoteKey by rememberUpdatedState<(AndroidKeyEvent) -> Boolean> { event ->
        val button = event.toTvRemoteButton()
        val eventType = when (event.action) {
            AndroidKeyEvent.ACTION_DOWN -> TvRemoteEventType.Down
            AndroidKeyEvent.ACTION_UP -> TvRemoteEventType.Up
            else -> TvRemoteEventType.Other
        }
        val active = stage is MediaStage.Active
        val capturedBefore = capturedRemoteButton
        val seekButton = button == TvRemoteButton.Left || button == TvRemoteButton.Right
        val decision = tvRemoteDecision(
            button = button,
            eventType = eventType,
            repeatCount = event.repeatCount,
            playbackActive = active,
            chromeVisible = chromeVisible,
            capturedButton = capturedRemoteButton,
        )
        if (active && eventType == TvRemoteEventType.Down && seekButton &&
            (decision.capture || capturedBefore == button)
        ) {
            if (decision.capture) {
                remoteSeekDeltaMs = 0L
                remoteSeekSpeedLevel = 1
                remoteSeekHeld = event.repeatCount > 0
                remoteSeekGestureActive = true
                remoteSeekVisible = true
            } else if (event.repeatCount > 0) {
                remoteSeekHeld = true
            }
        }
        if (decision.capture) capturedRemoteButton = button
        if (decision.releaseCapture) capturedRemoteButton = null
        if (decision.releaseCapture &&
            (capturedBefore == TvRemoteButton.Left || capturedBefore == TvRemoteButton.Right)
        ) {
            remoteSeekGestureActive = false
        }
        if (eventType == TvRemoteEventType.Down && active && button != TvRemoteButton.Other) {
            session.pokeChrome()
        }
        when (val command = decision.command) {
            TvRemoteCommand.RevealChrome -> Unit // pokeChrome above is the reveal signal.
            TvRemoteCommand.TogglePlayPause -> if (frame.playing) session.onPause() else session.onPlay()
            is TvRemoteCommand.SeekBy -> {
                remoteSeekDeltaMs = (remoteSeekDeltaMs ?: 0L) + command.deltaMs
                remoteSeekSpeedLevel = command.speedLevel
                session.onSkip(command.deltaMs)
            }
            null -> Unit
        }
        decision.consume
    }

    DisposableEffect(remoteKeys) {
        val bridge: (AndroidKeyEvent) -> Boolean = { handleRemoteKey(it) }
        remoteKeys.attach(bridge)
        onDispose { remoteKeys.detach(bridge) }
    }

    // TV Back convention: dismiss the top surface rather than kill the app + the
    // whole cast (finish() would release the player and tear down the servers).
    BackHandler(
        enabled = showSettings || pairingSnapshot.surface is PairingSurface.Open || pairingSnapshot.surface is PairingSurface.Locked ||
            stage is MediaStage.Checking || stage is MediaStage.Preparing || stage is MediaStage.Active || stage is MediaStage.Error,
    ) {
        when {
            showSettings -> showSettings = false
            pairingSnapshot.surface is PairingSurface.Open || pairingSnapshot.surface is PairingSurface.Locked -> pairing.closeSurface()
            stage is MediaStage.Checking || stage is MediaStage.Preparing -> if (!server.stopLocalCast()) session.backToStandby()
            stage is MediaStage.Active && chromeVisible -> chromeVisible = false
            stage is MediaStage.Active -> if (!server.stopLocalCast()) session.backToStandby()
            stage is MediaStage.Error -> session.backToStandby()
        }
    }

    FlickTvTheme {
        // Keep diagnostics inside the same viewport-relative overscan contract as
        // the redesigned screen chrome at both 1080p and 4K.
        val safeArea = rememberTvSafeAreaPadding()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(FlickColor.Canvas)
                .focusRequester(rootFocus)
                .focusable(enabled = stage is MediaStage.Active && !chromeVisible),
        ) {
            when (stage) {
                is MediaStage.Active -> if (surfaceMode == PlayerSurfaceMode.VisiblePlayback) {
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
                        remoteSeekDeltaMs = remoteSeekDeltaMs,
                        remoteSeekSpeedLevel = remoteSeekSpeedLevel,
                        remoteSeekHeld = remoteSeekHeld,
                        remoteSeekVisible = remoteSeekVisible,
                        onBack10 = { session.onSkip(-10_000L) },
                        onPlayPause = { if (frame.playing) session.onPause() else session.onPlay() },
                        onForward10 = { session.onSkip(10_000L) },
                        onSetVolume = { session.onSetVolume(it) },
                        playFocusRequester = playFocus,
                    ) { playerSurface() }
                    if (metricsEnabled) {
                        MetricsOverlay(
                            snapshot = snapshot,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(safeArea),
                        )
                    }
                }

                is MediaStage.Checking, is MediaStage.Preparing -> if (surfaceMode == PlayerSurfaceMode.CoveredConnecting) ConnectingScreen { playerSurface() }

                is MediaStage.Error -> ErrorScreen(
                    kind = stage.kind,
                    deviceLabel = deviceLabel,
                    // A failed v2 cast must get a fresh sender cast ID/token.
                    onPrimary = null,
                    onSecondary = { session.backToStandby() },
                )

                MediaStage.None -> {
                    // Rendered exclusively (never overlaid) so no hidden screen's
                    // buttons stay focusable underneath — that let the D-pad reach
                    // invisible controls and lost focus on dismiss.
                    when {
                        showSettings -> SettingsScreen(
                            tvName = tvName,
                            pairedSummary = if (pairingSnapshot.pairedCount == 0) stringResource(R.string.settings_paired_none)
                                else stringResource(R.string.settings_paired_count, pairingSnapshot.pairedCount),
                            metricsEnabled = metricsEnabled,
                            onRename = {
                                val next = nextName(tvName)
                                pairing.tvName = next
                                tvName = next
                                if (boundPort > 0) {
                                    nsd.register(next, boundPort, Build.MODEL ?: "Android TV", NsdAdvertiser.STATE_READY, pairing.tvId)
                                }
                            },
                            onToggleMetrics = { metricsEnabled = !metricsEnabled },
                            onForgetAll = {
                                if (server.forgetAllPairings()) showSettings = false
                            },
                            onDone = { showSettings = false },
                            diagnosticsVisible = showDiagnostics,
                            // Keyed on the log revision so the list re-reads the
                            // ring buffer whenever a line is appended.
                            diagnostics = remember(logRevision, showDiagnostics) {
                                if (showDiagnostics) FlickLog.recent().take(DIAGNOSTICS_VISIBLE) else emptyList()
                            },
                            onToggleDiagnostics = { showDiagnostics = !showDiagnostics },
                            onClearDiagnostics = { FlickLog.clear() },
                        )

                        pairingSnapshot.surface is PairingSurface.Open || pairingSnapshot.surface is PairingSurface.Locked || pairingSnapshot.pairedCount == 0 -> PairScreen(
                            tvName = tvName,
                            code = (pairingSnapshot.surface as? PairingSurface.Open)?.code ?: "—",
                            qrPayload = pairing.qrPayload(boundHost ?: "", boundPort),
                            host = boundHost ?: "",
                            port = boundPort,
                            networkReady = boundHost != null && boundPort > 0,
                            bindUptimeSec = bindUptimeSec,
                            rebindCount = rebindCount,
                            lastTeardown = lastTeardown,
                            onRename = {
                                val next = nextName(tvName)
                                pairing.tvName = next
                                tvName = next
                                if (boundPort > 0) {
                                    nsd.register(next, boundPort, Build.MODEL ?: "Android TV", NsdAdvertiser.STATE_READY, pairing.tvId)
                                }
                            },
                        )

                        pairingSnapshot.surface is PairingSurface.Success -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.pair_success), fontSize = 32.sp, color = FlickColor.OnSurface)
                        }

                        else -> IdleScreen(
                            pairedLabel = deviceLabel,
                            onPairAnother = { pairing.requestOpen() },
                            onOpenSettings = { showSettings = true },
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(pairingSnapshot.surface) {
        if (pairingSnapshot.surface is PairingSurface.Success) { delay(1_500); pairing.finishSuccess() }
    }
}

@Composable
private fun ConnectingScreen(videoContent: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        videoContent()
        Box(
            modifier = Modifier.fillMaxSize().background(FlickColor.Canvas),
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
                setBackgroundColor(FlickColor.Canvas.toArgb())
                configureSubtitles(ctx.getSystemService(CaptioningManager::class.java))
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

private fun PlayerView.configureSubtitles(captions: CaptioningManager?) {
    val subtitles = subtitleView ?: return
    // Text cues use one Media3 cue window as the translucent plate. A partially
    // transparent BACKGROUND_COLOR is painted per glyph/run and overlaps into a
    // harsh near-opaque block. Bitmap subtitles (PGS/VobSub/etc.) have styling
    // baked into their pixels and remain unchanged as the safe fallback.
    subtitles.setApplyEmbeddedStyles(false)
    subtitles.setApplyEmbeddedFontSizes(false)
    subtitles.setStyle(
        CaptionStyleCompat(
            AndroidColor.WHITE,
            AndroidColor.argb(SUBTITLE_GLYPH_BACKGROUND_ALPHA, 0, 0, 0),
            AndroidColor.argb(SUBTITLE_WINDOW_ALPHA, 0, 0, 0),
            CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
            AndroidColor.BLACK,
            null,
        ),
    )

    fun applyTextSize(captionScale: Float = if (captions?.isEnabled == true) captions.fontScale else 1f) {
        subtitles.setFixedTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            reducedSubtitleTextSizeSp(
                viewHeightPx = subtitles.height,
                scaledDensity = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    1f,
                    subtitles.resources.displayMetrics,
                ),
                captionFontScale = captionScale,
                defaultTextSizeFraction = androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION,
            ),
        )
    }

    val captionListener = object : CaptioningManager.CaptioningChangeListener() {
        override fun onFontScaleChanged(fontScale: Float) {
            applyTextSize(if (captions?.isEnabled == true) fontScale else 1f)
        }
        override fun onEnabledChanged(enabled: Boolean) = applyTextSize()
    }
    val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyTextSize() }
    val configurationListener = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) = applyTextSize()
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onLowMemory() = Unit
    }
    var listening = false
    fun startListening() {
        if (listening) return
        captions?.addCaptioningChangeListener(captionListener)
        subtitles.addOnLayoutChangeListener(layoutListener)
        subtitles.context.registerComponentCallbacks(configurationListener)
        listening = true
        applyTextSize()
    }
    fun stopListening() {
        if (!listening) return
        captions?.removeCaptioningChangeListener(captionListener)
        subtitles.removeOnLayoutChangeListener(layoutListener)
        subtitles.context.unregisterComponentCallbacks(configurationListener)
        listening = false
    }
    subtitles.addOnAttachStateChangeListener(
        object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                startListening()
                applyTextSize()
            }

            override fun onViewDetachedFromWindow(view: View) = stopListening()
        },
    )
    if (subtitles.isAttachedToWindow) startListening()
}

private fun nextName(current: String): String {
    val idx = TV_NAME_PRESETS.indexOf(current)
    return TV_NAME_PRESETS[(idx + 1).mod(TV_NAME_PRESETS.size)]
}

private fun AndroidKeyEvent.toTvRemoteButton(): TvRemoteButton = when (keyCode) {
    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
    AndroidKeyEvent.KEYCODE_ENTER,
    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
    AndroidKeyEvent.KEYCODE_BUTTON_SELECT,
    AndroidKeyEvent.KEYCODE_BUTTON_A,
    AndroidKeyEvent.KEYCODE_BUTTON_START -> TvRemoteButton.Select
    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> TvRemoteButton.Left
    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> TvRemoteButton.Right
    AndroidKeyEvent.KEYCODE_DPAD_UP -> TvRemoteButton.Up
    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> TvRemoteButton.Down
    // Dedicated media keys intentionally fall through Activity.dispatchKeyEvent
    // to Media3's platform-backed MediaSession. Intercepting them here would
    // double-handle the same physical button.
    else -> TvRemoteButton.Other
}

internal enum class PlayerSurfaceMode { Hidden, CoveredConnecting, VisiblePlayback }

internal fun playerSurfaceMode(stage: MediaStage): PlayerSurfaceMode = when (stage) {
    is MediaStage.Checking, is MediaStage.Preparing -> PlayerSurfaceMode.CoveredConnecting
    is MediaStage.Active -> PlayerSurfaceMode.VisiblePlayback
    else -> PlayerSurfaceMode.Hidden
}

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
