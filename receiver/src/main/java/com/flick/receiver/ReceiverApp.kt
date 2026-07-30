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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
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
import com.flick.receiver.player.SubtitleTrackInfo
import com.flick.receiver.player.ThroughputHistory
import com.flick.receiver.player.ThroughputSnapshot
import com.flick.receiver.player.reducedSubtitleTextSizeSp
import com.flick.receiver.session.MediaStage
import com.flick.receiver.session.SessionController
import com.flick.receiver.ui.components.FlickLoader
import com.flick.receiver.ui.components.GlassPanel
import com.flick.receiver.ui.components.GlassPanelTone
import com.flick.receiver.ui.screens.ErrorScreen
import com.flick.receiver.ui.screens.IdleScreen
import com.flick.receiver.ui.screens.MetricsOverlay
import com.flick.receiver.ui.screens.PairCodePlaceholder
import com.flick.receiver.ui.screens.PairScreen
import com.flick.receiver.ui.screens.PlaybackPanel
import com.flick.receiver.ui.screens.PlaybackScreen
import com.flick.receiver.ui.screens.QualityInfo
import com.flick.receiver.ui.screens.SettingsScreen
import com.flick.receiver.ui.screens.SubtitleSize
import com.flick.receiver.ui.screens.VideoResolutionClass
import com.flick.receiver.ui.screens.formatMbps
import com.flick.receiver.ui.screens.rememberDiagnosticsLines
import com.flick.receiver.ui.screens.videoResolutionClass
import com.flick.receiver.ui.screens.videoResolutionLines
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickMotion
import com.flick.receiver.ui.theme.FlickShape
import com.flick.receiver.ui.theme.FlickTvTheme
import com.flick.receiver.ui.theme.FlickType
import com.flick.receiver.ui.theme.LocalReducedMotion
import com.flick.receiver.ui.theme.rememberTvSafeAreaPadding
import com.flick.receiver.util.FlickLog
import com.flick.receiver.util.RefreshRateHelper
import com.flick.receiver.util.preferredWindowRefreshRate
import com.flick.receiver.util.refreshRateHintDelayMs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive

/**
 * The address flow is the real trigger; this only covers an address change that
 * produced no connectivity callback at all. It is deliberately slow — the old
 * 2 s poll was the second half of the rebind-churn loop.
 */
private const val RECONCILE_SAFETY_NET_MS = 10_000L

/** Surfaces that do not contain the decoded video and may safely crossfade. */
internal enum class StandbySurface { Pair, PairSuccess, Idle, Settings }

/**
 * Which standby surface the router puts on screen — one decision, read both by the
 * router itself and by [pairingSurfaceRendered].
 *
 * It is a shared function rather than a `when` inline in the router precisely
 * because a pairing code is only legitimate while the surface that DRAWS one is on
 * screen. Two places deciding that separately is how they come to disagree, and a
 * disagreement here is a live code nobody can see.
 *
 * Settings outranks Pair, which is why opening it from the pair screen has to take
 * the code down. A sealed surface routes to Pair at ANY paired count: with no
 * phones the `pairedCount` arm would have caught it anyway; with phones it would
 * not, and the TV would drop to Idle having silently stopped accepting codes —
 * which is the one thing the ceiling must never look like.
 *
 * A pending confirmation routes to Pair at any paired count for the same reason and
 * a sharper one: "Pair another phone" from Idle opens a code with phones already
 * stored, so without this arm the prompt would be asked of a screen that is not
 * drawing it — an unanswerable question that could only ever expire, which is the
 * one shape a physical-presence gate must never take.
 */
internal fun standbySurfaceFor(
    showSettings: Boolean,
    surface: PairingSurface,
    pairedCount: Int,
): StandbySurface = when {
    showSettings -> StandbySurface.Settings
    surface is PairingSurface.Open ||
        surface is PairingSurface.Locked ||
        surface is PairingSurface.Sealed ||
        surface is PairingSurface.Confirming ||
        pairedCount == 0 -> StandbySurface.Pair
    surface is PairingSurface.Success -> StandbySurface.PairSuccess
    else -> StandbySurface.Idle
}

/**
 * Whether the surface that RENDERS a pairing code is the one on screen. This is the
 * single fact `PairingManager` cannot see for itself, and it is what decides whether
 * the app returning to the foreground may ask it for a code.
 *
 * `PairingManager.onForeground` used to decide that alone, from the paired count —
 * a TV with nothing paired is a TV showing the pair screen. It is not always. The
 * pair screen is the only route into Settings on a factory-fresh TV, [showSettings]
 * is composition state that survives a stop/start, and Settings outranks Pair above.
 * A screensaver over an idle Settings screen therefore resumed the app holding a
 * live, rotating code with neither the digits nor the QR anywhere on screen, while
 * the owner believed pairing was closed.
 *
 * Two other shapes were weighed. Gating the CALL SITE on [showSettings] shuts this
 * instance but leaves the manager still guessing at what is on screen for whoever
 * calls it next. Driving the surface from composition instead — a code open for
 * exactly as long as the pair screen is drawn — inverts the two places that
 * deliberately close a surface the pair screen is still drawing: Back over an Open
 * code, and the trip into Settings, both of which would immediately be undone. So
 * the decision is the renderer's and the trigger stays the lifecycle's: this asks
 * [standbySurfaceFor], the router's own answer, and hands it to the manager.
 *
 * [stage] is a parameter because standby surfaces render only under
 * [MediaStage.None]: a cast on screen draws no code whatever the router would
 * otherwise have chosen.
 */
internal fun pairingSurfaceRendered(
    stage: MediaStage,
    showSettings: Boolean,
    surface: PairingSurface,
    pairedCount: Int,
): Boolean = stage is MediaStage.None &&
    standbySurfaceFor(showSettings, surface, pairedCount) == StandbySurface.Pair

/**
 * A standby surface plus the values its screen must keep drawing while it leaves.
 *
 * `AnimatedContent` recomposes the OUTGOING subtree against live state, so a pair
 * screen whose code has just been consumed would flip to its locked "—" halfway
 * through the fade the viewer is still reading. Snapshotting the pair inputs into
 * the transition state freezes the exiting screen on what it was showing.
 *
 * [qrPayload] is in here for the same reason and is now bound to [code]: the v4
 * payload CARRIES the code, so an exiting pair screen that kept re-deriving it
 * would swap its symbol mid-fade for one built against a code that has already
 * been consumed.
 */
private data class StandbyState(
    val surface: StandbySurface,
    val code: String,
    val codeExpiresAtElapsedMs: Long?,
    val qrPayload: String?,
    /**
     * `PairingSurface.Confirming`, snapshotted for the same reason the code is: the
     * prompt resolves the instant someone presses a button, and an exiting pair
     * screen must finish the fade still showing the question it was asking.
     */
    val confirming: PairingSurface.Confirming?,
)

/**
 * Standby surfaces travel a sixth of the axis, not a whole screen: at ten feet a
 * full-width slide reads as a jump cut, while a sixth is unmistakably directional
 * and keeps both surfaces legible through the exchange.
 */
private const val STANDBY_TRAVEL_DIVISOR = 6

/** The pairing confirmation punches in rather than sliding — it is an event, not a place. */
private const val STANDBY_PUNCH_IN_SCALE = 0.92f
private const val STANDBY_PUNCH_OUT_SCALE = 1.04f

/**
 * Whether the Activity-level remote policy is in play at all.
 *
 * An open side panel owns the whole D-pad: it is the only surface on screen with
 * focusables, its rows run horizontally, and nothing on it is a playback gesture.
 * The policy is handed `playbackActive = false` there and consumes nothing, which
 * is what lets Compose route every key inside the panel.
 *
 * There is no longer a volume latch here. Volume's engage-to-adjust mode reads
 * left/right through its own `onKeyEvent` while it holds focus, and horizontal
 * keys only bypass Compose when the scrub bar holds focus — so the two can no
 * longer both claim the same key, and the latch that used to arbitrate them was
 * one more piece of state that could be left set.
 */
internal fun receiverPlaybackGesturesEnabled(
    playbackActive: Boolean,
    panelOpen: Boolean,
): Boolean = playbackActive && !panelOpen

/** Handshake card width (receiver-expressive-spec.md §5.2). */
private val HANDSHAKE_CARD_WIDTH = 450.dp

/** Hoisted so the ordinal↔enum round trip does not allocate on every recomposition. */
private val SUBTITLE_SIZES = SubtitleSize.values()

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
    var tvName by remember { mutableStateOf(pairing.tvName) }
    // Remembered rather than read through stringArrayResource: this composable
    // recomposes with the 10 Hz position feed, and the presets never change under
    // a composition that is still alive.
    val tvNamePresets = remember(context) {
        context.resources.getStringArray(R.array.tv_name_presets)
    }
    // The same idiom for the phones in Settings: there is no keyboard on this TV,
    // so a rename cycles presets rather than typing.
    val phoneNamePresets = remember(context) {
        context.resources.getStringArray(R.array.phone_name_presets)
    }
    var snapshot by remember { mutableStateOf(DiagnosticsSnapshot.EMPTY) }
    // Fed from the existing ~2 Hz diagnostics arm below — the histogram never
    // adds a timer of its own.
    val throughputHistory = remember { ThroughputHistory() }
    var throughput by remember { mutableStateOf(ThroughputSnapshot.EMPTY) }
    var subtitleTracks by remember { mutableStateOf<List<SubtitleTrackInfo>>(emptyList()) }
    // Ordinal rather than the enum itself so the saver never has to reflect on it.
    var subtitleSizeOrdinal by rememberSaveable { mutableStateOf(SubtitleSize.Medium.ordinal) }
    val subtitleSize = SUBTITLE_SIZES[subtitleSizeOrdinal.coerceIn(0, SUBTITLE_SIZES.lastIndex)]
    // Bridges the Compose choice to the detached PlayerView's SubtitleView, which
    // lives outside composition.
    val subtitleSizePreference = remember { SubtitleSizePreference() }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    // Movable content preserves the same AndroidView/Surface across the opaque
    // preparing overlay and the visible playback chrome.
    val playerSurface = remember(controller) {
        movableContentOf {
            PlayerSurface(
                controller = controller,
                subtitleSizePreference = subtitleSizePreference,
                onViewAvailable = { playerView = it },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    var showSettings by remember { mutableStateOf(false) }
    // Whether leaving Settings owes the viewer a pairing code back.
    //
    // Settings outranks Pair in the surface router below, so opening it from the
    // pair screen has to take the code DOWN — a code left open while Settings is
    // on top would be live, rotating and accepting attempts with nothing on screen
    // rendering it, which is exactly the state `PairingManager.closeSurface`
    // exists to refuse. This latch is what makes that trip reversible: the viewer
    // asked for a code, and coming back must return them to it rather than to Idle.
    var reopenPairingOnExit by remember { mutableStateOf(false) }
    var metricsEnabled by rememberSaveable { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    var openPanel by remember { mutableStateOf(PlaybackPanel.None) }
    // The scrub bar's focus is what promotes physical left/right from a focus move
    // to a seek. It is read at the Activity boundary, so it is owned here rather
    // than inside the chrome that draws it.
    var scrubFocused by remember { mutableStateOf(false) }
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
                    // A code may only be minted for a screen that draws one. The
                    // live snapshot is read from the manager, not from
                    // `pairingSnapshot`, which is a frame behind this event — the
                    // same reason `leaveSettings` reads it there.
                    val live = pairing.snapshot.value
                    pairing.onForeground(
                        pairingRendered = pairingSurfaceRendered(
                            stage = session.stage,
                            showSettings = showSettings,
                            surface = live.surface,
                            pairedCount = live.pairedCount,
                        ),
                    )
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
                    // Two ticks per histogram bar, so 40 bars really do span the
                    // 40 s the metrics panel's eyebrow promises.
                    throughputHistory.append(snapshot.bitrateEstimateBps)
                    throughput = throughputHistory.snapshot()
                    // Media3 republishes text tracks as the container is parsed;
                    // the same 2 Hz arm keeps the panel honest without a listener.
                    subtitleTracks = controller.subtitleTracks()
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

    // A new cast must never inherit the previous film's telemetry: stale bars or
    // a stale track list would be fabricated readings of the stream on screen.
    LaunchedEffect((stage as? MediaStage.Active)?.castId) {
        throughputHistory.clear()
        throughput = ThroughputSnapshot.EMPTY
        subtitleTracks = emptyList()
        openPanel = PlaybackPanel.None
        scrubFocused = false
    }

    // The size choice multiplies the viewport-relative caption size the receiver
    // already computes; the platform caption-manager scale still governs the base.
    LaunchedEffect(subtitleSize) { subtitleSizePreference.update(subtitleSize.scale) }

    // With chrome hidden there is no focusable transport, so park focus on the
    // root catcher. Activity-level remote routing handles playback commands;
    // keeping a Compose focus owner also preserves ordinary fallback dispatch.
    LaunchedEffect(stage, chromeVisible) {
        if (rootFocusCatcherEnabled(stage, chromeVisible)) {
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
            scrubFocused = false
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

    // Hiding the chrome disposes the scrub bar, and its focus-loss callback races
    // the disposal. A stale `true` here would leave horizontal keys seeking over a
    // bar that is no longer on screen.
    //
    // The burst carries the mirrored invariant. It is the feedback for a BLIND
    // seek, but a crossing up/down pressed during a hold still pokes the chrome —
    // deliberately, so the remote never looks dead mid-gesture — and the chrome can
    // therefore arrive on top of a burst that began over bare film. Once the scrub
    // bar is drawing the target, the ghost and both timecodes, the burst is a
    // second account of one gesture.
    LaunchedEffect(chromeVisible) {
        if (chromeVisible) remoteSeekVisible = false else scrubFocused = false
    }

    // What reveals the chrome: a remote poke, or the film's own state changing
    // under the viewer (a phone-side pause, the end of the film, a rebuffer).
    //
    // Split out from the countdown below, and deliberately NOT keyed on
    // `session.seeking`. A blind left/right over bare film does not summon the
    // chrome — see the key handler — and while the two lived in one effect the
    // seek flag flipping true summoned it anyway, one frame later, on the app's
    // behalf.
    LaunchedEffect(session.chromePoke, frame.phase) { chromeVisible = true }

    // Auto-hide (chromeFade), re-armed on every poke and state change.
    //
    // Paused counts now. A viewer who pauses and walks away used to be left with
    // the full transport panel across the bottom of a frozen frame for as long as
    // the pause lasted; the resting play key `PlaybackScreen` leaves behind is the
    // whole signal that state needs.
    //
    // An open side panel still suspends the countdown — the metrics histogram
    // spans 40 s and a track list has to be scannable — and now it must: the panel
    // has replaced the transport, so a countdown firing underneath it would take
    // away the bar the viewer expects to come back to.
    LaunchedEffect(session.chromePoke, frame.phase, session.seeking, openPanel, chromeVisible) {
        if (!chromeVisible) return@LaunchedEffect
        val resting = frame.phase == PlaybackPhase.Playing || frame.phase == PlaybackPhase.Paused
        if (!resting || session.seeking || openPanel != PlaybackPanel.None) return@LaunchedEffect
        delay(4000L)
        chromeVisible = false
    }

    // Refresh-rate matching, best-effort and derived from the CURRENT stage rather
    // than latched on the first frame rate ever seen — see
    // [preferredWindowRefreshRate]. The rate is deliberately held for the whole of
    // visible playback, chrome and open panels included: a mode switch on the
    // verified hardware costs a visible HDMI resync, and two of them per chrome
    // reveal is worse than chrome animating at the film's own cadence.
    val requestedRefreshRate = preferredWindowRefreshRate(
        presentingVideo = surfaceMode == PlayerSurfaceMode.VisiblePlayback,
        contentFrameRate = snapshot.frameRate,
    )
    // Same argument applied to the gap BETWEEN two films: a cast arriving over a
    // running one passes through the handshake, and a release committed there is
    // undone a second later at the same cadence — see [refreshRateHintDelayMs].
    // The effect is re-keyed on the delay so the settle is abandoned, not merely
    // outlived, the moment the next stage decides what the hint should be.
    val refreshRateDelayMs = refreshRateHintDelayMs(
        requestedRate = requestedRefreshRate,
        castHandshakeInFlight = surfaceMode == PlayerSurfaceMode.CoveredConnecting,
    )
    LaunchedEffect(window, requestedRefreshRate, refreshRateDelayMs, playerView) {
        if (refreshRateDelayMs > 0L) delay(refreshRateDelayMs)
        RefreshRateHelper.applyToWindow(window, requestedRefreshRate)
        val surface = (playerView?.videoSurfaceView as? SurfaceView)?.holder?.surface
        RefreshRateHelper.applyToSurface(surface, requestedRefreshRate)
    }
    // A torn-down player must not leave the window pinned: nothing else runs after
    // this composable leaves, so the release has to be its own disposal.
    DisposableEffect(window) {
        onDispose { RefreshRateHelper.releaseWindow(window) }
    }

    val deviceLabel = pairingSnapshot.mostRecentDeviceLabel

    // The code the surface is actually offering, and the QR built from it.
    //
    // v4 puts the code IN the payload, so a rotation invalidates the symbol and it
    // must be re-encoded — but re-encoding is a ZXing pass plus a full-card bitmap
    // raster, and this composable recomposes with the 10 Hz position feed. Keying
    // the payload on the code makes that cost exactly one raster per rotation
    // (`CODE_TTL_MS`, 5 min) instead of ten a second: `QrCode` itself remembers on
    // the payload string, so an unchanged code re-uses the bitmap it already has.
    val pairCode = (pairingSnapshot.surface as? PairingSurface.Open)?.code ?: PairCodePlaceholder
    val qrPayload = remember(pairing, boundHost, boundPort, pairCode) {
        pairing.qrPayload(boundHost ?: "", boundPort, pairCode)
    }

    // Every way out of Settings, so the pairing code the pair screen gave up on
    // the way in comes back on the way out.
    val leaveSettings = {
        showSettings = false
        // Skipped when something inside Settings has already opened a code of its
        // own: Forget all and forgetting the last phone both reopen the surface
        // through the manager, and `requestOpen` would rotate that freshly issued
        // code away before anyone could finish reading it. Read from the manager
        // rather than from `pairingSnapshot`, which is a frame behind this press.
        if (reopenPairingOnExit && pairing.snapshot.value.surface is PairingSurface.Standby) {
            pairing.requestOpen()
        }
        reopenPairingOnExit = false
    }

    val handleRemoteKey by rememberUpdatedState<(AndroidKeyEvent) -> Boolean> { event ->
        val button = event.toTvRemoteButton()
        val eventType = when (event.action) {
            AndroidKeyEvent.ACTION_DOWN -> TvRemoteEventType.Down
            AndroidKeyEvent.ACTION_UP -> TvRemoteEventType.Up
            else -> TvRemoteEventType.Other
        }
        val active = stage is MediaStage.Active
        val playbackGestures = receiverPlaybackGesturesEnabled(
            playbackActive = active,
            panelOpen = openPanel != PlaybackPanel.None,
        )
        val capturedBefore = capturedRemoteButton
        val seekButton = button == TvRemoteButton.Left || button == TvRemoteButton.Right
        val decision = tvRemoteDecision(
            button = button,
            eventType = eventType,
            repeatCount = event.repeatCount,
            playbackActive = playbackGestures,
            chromeVisible = chromeVisible,
            scrubFocused = scrubFocused,
            capturedButton = capturedRemoteButton,
        )
        if (playbackGestures && eventType == TvRemoteEventType.Down && seekButton &&
            (decision.capture || capturedBefore == button)
        ) {
            if (decision.capture) {
                remoteSeekDeltaMs = 0L
                remoteSeekSpeedLevel = 1
                remoteSeekHeld = event.repeatCount > 0
                remoteSeekGestureActive = true
                // The burst is the feedback for a BLIND seek. With the chrome up
                // the scrub bar is already drawing the target, the ghost and both
                // timecodes, and a 38 %-wide wash over the panel would be a second
                // account of one gesture — and a full-height layer over the film
                // to give it.
                remoteSeekVisible = !chromeVisible
            } else if (event.repeatCount > 0) {
                remoteSeekHeld = true
            }
        }
        if (decision.capture) capturedRemoteButton = button
        if (decision.releaseCapture) capturedRemoteButton = null
        // The burst belongs to whichever gesture holds the remote, so it is derived
        // from the capture rather than credited to the key that released one. Read
        // the other way round — "this release ended a horizontal hold" — any path
        // that ends a seek on a different button leaves the burst frozen over the
        // film with nothing behind it, and only the next seek clears it.
        if (decision.capture || decision.releaseCapture) {
            remoteSeekGestureActive = capturedRemoteButton == TvRemoteButton.Left ||
                capturedRemoteButton == TvRemoteButton.Right
        }
        // A seek over bare film deliberately does NOT summon the chrome. Quick-seek
        // without bringing up UI is the convention on every TV player, and it is
        // also what keeps the gesture self-consistent: revealing the chrome here
        // would make the second tap of a double-tap a focus move rather than
        // another ten seconds. Center/up/down still reveal, so nothing is
        // unreachable.
        val blindSeek = seekButton && !chromeVisible && playbackGestures
        if (eventType == TvRemoteEventType.Down && active && button != TvRemoteButton.Other && !blindSeek) {
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
            pairingSnapshot.surface is PairingSurface.Confirming ||
            stage is MediaStage.Checking || stage is MediaStage.Preparing || stage is MediaStage.Active || stage is MediaStage.Error,
    ) {
        when {
            showSettings -> leaveSettings()
            // Back over a prompt is a refusal, not a dismissal: it is above the
            // ordinary surface close so that dismissing the question can never be the
            // thing that leaves it unanswered and running down its clock. Denying is
            // also what Back means everywhere else on this TV — undo the top surface —
            // and it is the safe direction for a gate whose whole job is to withhold.
            pairingSnapshot.surface is PairingSurface.Confirming -> pairing.denyPendingPair()
            pairingSnapshot.surface is PairingSurface.Open || pairingSnapshot.surface is PairingSurface.Locked -> pairing.closeSurface()
            stage is MediaStage.Checking || stage is MediaStage.Preparing -> if (!server.stopLocalCast()) session.backToStandby()
            // An open side panel is the topmost surface: dismiss it before Back is
            // allowed to hide the chrome underneath it or end the cast.
            stage is MediaStage.Active && openPanel != PlaybackPanel.None -> openPanel = PlaybackPanel.None
            stage is MediaStage.Active && chromeVisible -> chromeVisible = false
            stage is MediaStage.Active -> if (!server.stopLocalCast()) session.backToStandby()
            stage is MediaStage.Error -> session.backToStandby()
        }
    }

    FlickTvTheme {
        // Keep diagnostics inside the same viewport-relative overscan contract as
        // the redesigned screen chrome at both 1080p and 4K.
        val safeArea = rememberTvSafeAreaPadding()
        val reducedMotion = LocalReducedMotion.current
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(FlickColor.Canvas)
                .focusRequester(rootFocus)
                .focusable(enabled = rootFocusCatcherEnabled(stage, chromeVisible)),
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
                        // Passing this is what lets the Ended state offer "Watch again" at
                        // all: without it the screen deliberately leaves the primary key
                        // inert rather than relabelling a control that would resume nothing.
                        onReplay = { session.onSeek(0L); session.onPlay() },
                        onSetVolume = { session.onSetVolume(it) },
                        playFocusRequester = playFocus,
                        diagnostics = snapshot,
                        throughput = throughput,
                        subtitleTracks = subtitleTracks,
                        subtitleSize = subtitleSize,
                        openPanel = openPanel,
                        onOpenPanel = { openPanel = it },
                        onScrubFocusChanged = { scrubFocused = it },
                        onSelectSubtitleTrack = { id ->
                            controller.selectSubtitleTrack(id)
                            // Media3 confirms the selection asynchronously; show the
                            // command immediately and let the 2 Hz re-read above
                            // reconcile it against what the player actually did.
                            subtitleTracks = subtitleTracks.map { it.copy(isSelected = id != null && it.id == id) }
                        },
                        onSelectSubtitleSize = { subtitleSizeOrdinal = it.ordinal },
                        // Same terminal path as Back on the playback surface.
                        onEndSession = { if (!server.stopLocalCast()) session.backToStandby() },
                    ) { playerSurface() }
                    // The dev HUD may only paint over bare film. The chrome now owns
                    // the top-left corner (source pill above the focusable END
                    // SESSION pill) and its side panels claim the full height above
                    // the transport, so with chrome up this plate would cover
                    // focusables and hide their amber ring; while chrome is up the
                    // Stream metrics panel is the read (spec §5.5).
                    AnimatedVisibility(
                        visible = metricsEnabled && !chromeVisible,
                        enter = fadeIn(FlickMotion.chromeFadeIn()),
                        exit = fadeOut(FlickMotion.chromeFadeOut()),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(safeArea),
                    ) {
                        MetricsOverlay(snapshot = snapshot)
                    }
                }

                is MediaStage.Checking, is MediaStage.Preparing -> if (surfaceMode == PlayerSurfaceMode.CoveredConnecting) {
                    ConnectingScreen(
                        deviceLabel = deviceLabel,
                        title = session.title,
                    ) { playerSurface() }
                }

                // The screen offers no retry: a failed v2 cast must get a fresh
                // cast ID and media token, and only the sender can mint those.
                is MediaStage.Error -> ErrorScreen(
                    kind = stage.kind,
                    deviceLabel = deviceLabel,
                    onDismiss = { session.backToStandby() },
                )

                MediaStage.None -> {
                    // Shared with the foreground gate above, so what the app is
                    // willing to open a code for and what it actually draws can
                    // never be two different answers — see [standbySurfaceFor].
                    val standbySurface = standbySurfaceFor(
                        showSettings = showSettings,
                        surface = pairingSnapshot.surface,
                        pairedCount = pairingSnapshot.pairedCount,
                    )
                    val standbyState = StandbyState(
                        surface = standbySurface,
                        code = pairCode,
                        codeExpiresAtElapsedMs =
                            (pairingSnapshot.surface as? PairingSurface.Open)?.expiresAtElapsedMs,
                        qrPayload = qrPayload,
                        confirming = pairingSnapshot.surface as? PairingSurface.Confirming,
                    )
                    // Only non-video standby surfaces animate. The outgoing subtree
                    // is immediately removed from focus/semantics while it finishes
                    // its exit, so D-pad input cannot reach stale controls.
                    //
                    // `contentKey` is the surface alone: a code rotation must update
                    // the visible pair screen in place rather than cross-fade the
                    // screen with itself.
                    val standbyMotion = rememberStandbyMotion()
                    AnimatedContent(
                        targetState = standbyState,
                        contentKey = { it.surface },
                        transitionSpec = {
                            if (reducedMotion) {
                                fadeIn(tween(durationMillis = 0))
                                    .togetherWith(fadeOut(tween(durationMillis = 0)))
                            } else {
                                standbyTransform(
                                    from = initialState.surface,
                                    to = targetState.surface,
                                    motion = standbyMotion,
                                )
                            }
                        },
                        label = "standbySurface",
                    ) { rendered ->
                        val renderedSurface = rendered.surface
                        val interactive = renderedSurface == standbySurface
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .focusProperties { canFocus = interactive }
                                .then(
                                    if (interactive) Modifier
                                    else Modifier.clearAndSetSemantics { },
                                ),
                        ) {
                            when (renderedSurface) {
                                StandbySurface.Settings -> SettingsScreen(
                                    tvName = tvName,
                                    pairedSummary = if (pairingSnapshot.pairedCount == 0) stringResource(R.string.settings_paired_none)
                                        else stringResource(R.string.settings_paired_count, pairingSnapshot.pairedCount),
                                    pairedPhones = pairingSnapshot.devices,
                                    // Through the server, not straight to the
                                    // manager: forgetting the phone that is
                                    // connected right now must also end its
                                    // session, and only the server owns that.
                                    //
                                    // Reaching zero phones closes this screen for
                                    // the same reason Forget all does. It is not
                                    // only that there is no list left to show:
                                    // `PairingManager.forget` takes the Forget-all
                                    // path when the store empties, which opens a
                                    // live pairing code, and Settings outranks Pair
                                    // in the surface router above — so leaving it open
                                    // would leave a code that is valid, rotating
                                    // and accepting attempts while nothing on
                                    // screen renders it. Closing hands the router
                                    // to `pairedCount == 0 -> Pair`, which does.
                                    // The count is read from the store rather than
                                    // from `pairingSnapshot`, which is a frame
                                    // behind this press.
                                    onForgetPhone = { keyId ->
                                        val forgotten = server.forget(keyId)
                                        if (forgotten && pairing.pairedCount() == 0) leaveSettings()
                                        forgotten
                                    },
                                    // Straight to the manager, NOT through the
                                    // server: a rename changes the label and
                                    // nothing else, so there is no session to
                                    // revoke — and `ControlServer.forget` takes the
                                    // manager monitor before `serverLock`, so
                                    // routing a manager write back through the
                                    // server is the lock order that deadlocks.
                                    //
                                    // The current label is read from the store
                                    // rather than from `pairingSnapshot`, which is
                                    // a frame behind this press, so two quick
                                    // presses step two presets rather than
                                    // computing the same next name twice.
                                    onRenamePhone = { keyId ->
                                        pairing.pairedDevices()
                                            .firstOrNull { it.keyId == keyId }
                                            ?.let { pairing.rename(keyId, nextName(it.label, phoneNamePresets)) }
                                    },
                                    metricsEnabled = metricsEnabled,
                                    onRename = {
                                        val next = nextName(tvName, tvNamePresets)
                                        pairing.tvName = next
                                        tvName = next
                                        if (boundPort > 0) {
                                            nsd.register(next, boundPort, Build.MODEL ?: "Android TV", NsdAdvertiser.STATE_READY, pairing.tvId)
                                        }
                                    },
                                    onToggleMetrics = { metricsEnabled = !metricsEnabled },
                                    onForgetAll = {
                                        if (server.forgetAllPairings()) leaveSettings()
                                    },
                                    onDone = leaveSettings,
                                    diagnosticsVisible = showDiagnostics,
                                    // Subscribed inside this lambda, not at the root
                                    // of this composable — see [rememberDiagnosticsLines].
                                    diagnostics = rememberDiagnosticsLines(showDiagnostics),
                                    onToggleDiagnostics = { showDiagnostics = !showDiagnostics },
                                    onClearDiagnostics = { FlickLog.clear() },
                                )

                                StandbySurface.Pair -> PairScreen(
                                    tvName = tvName,
                                    code = rendered.code,
                                    qrPayload = rendered.qrPayload,
                                    host = boundHost ?: "",
                                    port = boundPort,
                                    networkReady = boundHost != null && boundPort > 0,
                                    bindUptimeSec = bindUptimeSec,
                                    rebindCount = rebindCount,
                                    lastTeardown = lastTeardown,
                                    codeExpiresAtElapsedMs = rendered.codeExpiresAtElapsedMs,
                                    // Read from the live snapshot rather than the
                                    // captured `rendered`, exactly as the Settings
                                    // branch above reads its paired count: the seal
                                    // has to show on the frame it lands.
                                    pairingSealed = pairingSnapshot.surface is PairingSurface.Sealed,
                                    // The physical-presence half of the ceiling.
                                    // Nothing reachable over the LAN can call this;
                                    // it takes a button press in the room.
                                    onResumePairing = { pairing.resumePairing() },
                                    // Snapshotted, so the card finishes its exit
                                    // still naming the phone it was asking about.
                                    confirmDeviceLabel = rendered.confirming?.deviceLabel,
                                    confirmExpiresAtElapsedMs = rendered.confirming?.expiresAtElapsedMs,
                                    // Straight to the manager, NOT through the
                                    // server: there is no lease to revoke or install
                                    // here — the socket that asked is waiting on the
                                    // decision itself — and `ControlServer.forget`
                                    // takes the manager monitor before `serverLock`,
                                    // so routing a manager write back through the
                                    // server is the lock order that deadlocks.
                                    //
                                    // Nothing on the LAN can reach either of these.
                                    // That is the whole point: the QR carries the
                                    // live code, so being able to read the screen is
                                    // enough to submit a correct one — and pressing a
                                    // button on the television is not.
                                    onAllowPair = { pairing.allowPendingPair() },
                                    onDenyPair = { pairing.denyPendingPair() },
                                    onRename = {
                                        val next = nextName(tvName, tvNamePresets)
                                        pairing.tvName = next
                                        tvName = next
                                        if (boundPort > 0) {
                                            nsd.register(next, boundPort, Build.MODEL ?: "Android TV", NsdAdvertiser.STATE_READY, pairing.tvId)
                                        }
                                    },
                                    // With nothing paired the router never reaches
                                    // Idle, so this is the only route into Settings
                                    // on a factory-fresh TV.
                                    //
                                    // The pairing surface comes down on the way in
                                    // and goes back up on the way out. The latch is
                                    // unconditional because every state that renders
                                    // this screen is owed one back: an Open code, a
                                    // Locked countdown that must resume showing
                                    // itself, and the standby-with-no-phones case
                                    // this screen exists to resolve. Which of them
                                    // it is, is the manager's decision, not this
                                    // lambda's — `requestOpen` republishes the
                                    // lockout if one is still running, and refuses
                                    // outright while the surface is sealed.
                                    onOpenSettings = {
                                        reopenPairingOnExit = true
                                        pairing.closeSurface()
                                        showSettings = true
                                    },
                                )

                                StandbySurface.PairSuccess -> Box(
                                    Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = stringResource(R.string.pair_success),
                                        style = FlickType.display(sizeSp = 34),
                                        color = FlickColor.OnSurface,
                                    )
                                }

                                StandbySurface.Idle -> IdleScreen(
                                    pairedLabel = deviceLabel,
                                    onPairAnother = { pairing.requestOpen() },
                                    onOpenSettings = { showSettings = true },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(pairingSnapshot.surface) {
        if (pairingSnapshot.surface is PairingSurface.Success) { delay(1_500); pairing.finishSuccess() }
    }
}

/**
 * The specs a standby transition needs, resolved once in composition.
 *
 * `AnimatedContent`'s `transitionSpec` is NOT a composable lambda, so the scheme
 * accessors cannot be called inside it; they are read here and captured.
 */
private class StandbyMotion(
    val travelIn: FiniteAnimationSpec<IntOffset>,
    val travelOut: FiniteAnimationSpec<IntOffset>,
    val punchIn: FiniteAnimationSpec<Float>,
    val punchOut: FiniteAnimationSpec<Float>,
    val fadeIn: FiniteAnimationSpec<Float>,
    val fadeOut: FiniteAnimationSpec<Float>,
)

@Composable
private fun rememberStandbyMotion(): StandbyMotion = StandbyMotion(
    travelIn = FlickMotion.panelSpatial(),
    travelOut = FlickMotion.flickSettleSpatial(),
    punchIn = FlickMotion.panelSpatial(),
    punchOut = FlickMotion.flickSettleSpatial(),
    fadeIn = FlickMotion.chromeFadeIn(),
    fadeOut = FlickMotion.chromeFadeOut(),
)

/**
 * Standby transitions with a direction a viewer can read from the sofa.
 *
 * Settings is a drill-in and comes from the right; Pair and Idle are two states of
 * one standby and exchange vertically; the pairing confirmation punches in, because
 * it reports an event rather than presenting a place. Geometry takes the spatial
 * springs; alpha keeps the chrome fade tokens, which never overshoot.
 */
private fun standbyTransform(
    from: StandbySurface,
    to: StandbySurface,
    motion: StandbyMotion,
): ContentTransform = when {
    to == StandbySurface.Settings || from == StandbySurface.Settings -> {
        val sign = if (to == StandbySurface.Settings) 1 else -1
        (
            fadeIn(motion.fadeIn) + slideInHorizontally(
                animationSpec = motion.travelIn,
                initialOffsetX = { sign * it / STANDBY_TRAVEL_DIVISOR },
            )
            ).togetherWith(
            fadeOut(motion.fadeOut) + slideOutHorizontally(
                animationSpec = motion.travelOut,
                targetOffsetX = { -sign * it / STANDBY_TRAVEL_DIVISOR },
            ),
        )
    }

    to == StandbySurface.PairSuccess || from == StandbySurface.PairSuccess -> {
        (
            fadeIn(motion.fadeIn) + scaleIn(
                initialScale = STANDBY_PUNCH_IN_SCALE,
                animationSpec = motion.punchIn,
            )
            ).togetherWith(
            fadeOut(motion.fadeOut) + scaleOut(
                targetScale = STANDBY_PUNCH_OUT_SCALE,
                animationSpec = motion.punchOut,
            ),
        )
    }

    else -> {
        val sign = if (to == StandbySurface.Idle) 1 else -1
        (
            fadeIn(motion.fadeIn) + slideInVertically(
                animationSpec = motion.travelIn,
                initialOffsetY = { sign * it / STANDBY_TRAVEL_DIVISOR },
            )
            ).togetherWith(
            fadeOut(motion.fadeOut) + slideOutVertically(
                animationSpec = motion.travelOut,
                targetOffsetY = { -sign * it / STANDBY_TRAVEL_DIVISOR },
            ),
        )
    }
}

/**
 * The handshake (spec §5.2): a veil over the still-covered player surface and one
 * glass card. [deviceLabel] and [title] are the live session's own values — the
 * personalised headline only appears once both are actually known.
 */
@Composable
private fun ConnectingScreen(
    deviceLabel: String?,
    title: String?,
    videoContent: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        videoContent()
        Box(
            modifier = Modifier.fillMaxSize().background(FlickColor.ScrimVeil),
            contentAlignment = Alignment.Center,
        ) {
            GlassPanel(
                modifier = Modifier.width(HANDSHAKE_CARD_WIDTH),
                shape = FlickShape.Hero,
                tone = GlassPanelTone.Panel,
                contentPadding = PaddingValues(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                riseDistance = FlickMotion.TvRiseCard,
            ) {
                // Liveness, not progress. The handshake sits in one stage for as long
                // as the TV takes to answer, so a determinate shape would hold still
                // for the whole wait and read as a hang. It must never imply
                // transcoding, of which this project does none.
                FlickLoader()
                Text(
                    text = if (deviceLabel != null && title != null) {
                        stringResource(R.string.connecting_device_title, deviceLabel, title)
                    } else {
                        stringResource(R.string.connecting_title)
                    },
                    style = FlickType.display(sizeSp = 27),
                    color = FlickColor.OnSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.connecting_detail),
                    style = FlickType.body(sizeSp = 24),
                    color = FlickColor.OnSurfaceDim,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * The Small/Medium/Large caption choice, shared between Compose state and the
 * detached [PlayerView], which lives outside composition. [scale] MULTIPLIES the
 * viewport-relative size the receiver already computes, so the platform caption
 * manager and the layout listeners keep governing the baseline.
 */
private class SubtitleSizePreference {
    var scale: Float = SubtitleSize.Medium.scale
        private set

    /** Non-null only while a subtitle view is attached, so no view is retained. */
    var reapply: (() -> Unit)? = null

    fun update(newScale: Float) {
        if (newScale == scale) return
        scale = newScale
        reapply?.invoke()
    }
}

@Composable
private fun PlayerSurface(
    controller: PlayerController,
    subtitleSizePreference: SubtitleSizePreference,
    onViewAvailable: (PlayerView) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The film itself is the one unlabelled thing a screen reader would otherwise
    // find on the playback surface; the label belongs on the real surface, not only
    // on a test's stand-in for it.
    val surfaceDescription = stringResource(R.string.playback_surface_description)
    AndroidView(
        modifier = modifier.semantics { contentDescription = surfaceDescription },
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                setKeepContentOnPlayerReset(true)
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setBackgroundColor(FlickColor.CanvasPlayback.toArgb())
                configureSubtitles(
                    ctx.getSystemService(CaptioningManager::class.java),
                    subtitleSizePreference,
                )
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

private fun PlayerView.configureSubtitles(
    captions: CaptioningManager?,
    sizePreference: SubtitleSizePreference,
) {
    val subtitles = subtitleView ?: return
    // Text cues use one Media3 cue window as the translucent plate. A partially
    // transparent BACKGROUND_COLOR is painted per glyph/run and overlaps into a
    // harsh near-opaque block. Bitmap subtitles (PGS/VobSub/etc.) have styling
    // baked into their pixels and remain unchanged as the safe fallback.
    subtitles.setApplyEmbeddedStyles(false)
    subtitles.setApplyEmbeddedFontSizes(false)
    // Embedded styles are off, so a null typeface here would leave cues on the
    // platform default — Roboto Regular (400), under the module's ten-foot weight
    // floor and a different family from every other glyph on screen. Media3 takes
    // an android.graphics.Typeface, not a Compose FontFamily, so the bundled face
    // is loaded from res/font directly.
    subtitles.setStyle(
        CaptionStyleCompat(
            AndroidColor.WHITE,
            AndroidColor.argb(SUBTITLE_GLYPH_BACKGROUND_ALPHA, 0, 0, 0),
            AndroidColor.argb(SUBTITLE_WINDOW_ALPHA, 0, 0, 0),
            CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
            AndroidColor.BLACK,
            ResourcesCompat.getFont(context, R.font.geist_semibold),
        ),
    )

    fun applyTextSize(captionScale: Float = if (captions?.isEnabled == true) captions.fontScale else 1f) {
        // The user's size choice multiplies the computed baseline; it never
        // replaces it, so the caption-manager scale keeps its authority.
        val baselineSp = reducedSubtitleTextSizeSp(
            viewHeightPx = subtitles.height,
            scaledDensity = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                1f,
                subtitles.resources.displayMetrics,
            ),
            captionFontScale = captionScale,
            defaultTextSizeFraction = androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION,
        )
        subtitles.setFixedTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            (baselineSp * sizePreference.scale).coerceAtLeast(1f),
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
        // Bound to the same attach window as the platform listeners, so a
        // detached view is never re-measured and never retained.
        sizePreference.reapply = { applyTextSize() }
        listening = true
        applyTextSize()
    }
    fun stopListening() {
        if (!listening) return
        captions?.removeCaptioningChangeListener(captionListener)
        subtitles.removeOnLayoutChangeListener(layoutListener)
        subtitles.context.unregisterComponentCallbacks(configurationListener)
        sizePreference.reapply = null
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

/** Cycles the friendly-name presets behind the on-screen "Rename TV" (no keyboard on TV). */
internal fun nextName(current: String, presets: Array<String>): String {
    if (presets.isEmpty()) return current
    return presets[(presets.indexOf(current) + 1).mod(presets.size)]
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

/**
 * Whether the root catcher must hold D-pad focus, i.e. whether the stage on screen
 * renders no focusable of its own.
 *
 * Playback with hidden chrome is the obvious case. The handshake is the other one:
 * it is a purely informational card, and `TvRemoteKeyPolicy` deliberately declines
 * to consume keys before playback is active — so without a catcher Compose would
 * have no focus owner at all and nothing to hand focus back from when the stage
 * advances. Idle, pair, settings and error all carry their own focusables.
 */
internal fun rootFocusCatcherEnabled(stage: MediaStage, chromeVisible: Boolean): Boolean = when (stage) {
    is MediaStage.Checking, is MediaStage.Preparing -> true
    is MediaStage.Active -> !chromeVisible
    else -> false
}

@Composable
private fun qualityInfo(s: DiagnosticsSnapshot): QualityInfo {
    val unavailable = stringResource(R.string.metrics_unavailable)
    // Honest quality read: real resolution + the HDR class actually being decoded
    // (never a hardcoded "Dolby Vision" for every stream). The class comes from
    // the one shared classifier, so this card and the transport's spec chip can
    // never disagree about the same frame — see [videoResolutionClass].
    val resolution = when (videoResolutionClass(s.width, s.height)) {
        VideoResolutionClass.Uhd -> stringResource(R.string.quality_resolution_4k)
        VideoResolutionClass.Qhd -> stringResource(R.string.quality_resolution_1440p)
        VideoResolutionClass.Fhd -> stringResource(R.string.quality_resolution_1080p)
        VideoResolutionClass.Hd -> stringResource(R.string.quality_resolution_720p)
        VideoResolutionClass.Sd -> stringResource(
            R.string.quality_resolution_lines,
            videoResolutionLines(s.width, s.height),
        )
        VideoResolutionClass.Unknown -> null
    }
    val hdr = when (s.hdrType) {
        HdrType.DOLBY_VISION -> stringResource(R.string.quality_dolby_vision)
        HdrType.HDR10 -> stringResource(R.string.quality_hdr10)
        HdrType.NONE -> null
    }
    val directPlay = stringResource(R.string.quality_direct_play)
    // One formatter for every throughput on screen: this card is up for 4.5 s
    // WITH the chrome, and a card reading "31 Mb/s" over a panel reading
    // "30.6 Mb/s" is two claims about one measurement.
    val mbps = if (s.bitrateEstimateBps > 0L) {
        stringResource(R.string.metrics_value_mbps, formatMbps(s.bitrateEstimateBps))
    } else {
        null
    }
    return QualityInfo(
        qualityLabel = listOfNotNull(resolution, hdr).joinToString(" · ").ifBlank { directPlay },
        decoder = s.decoderName ?: unavailable,
        throughput = listOfNotNull(mbps, s.wifiBand).joinToString(" · ").ifBlank { unavailable },
        // Unlit is the least this can claim: `bars` has no way to say "unmeasured",
        // and only QualityCard can drop the row.
        bars = wifiBars(s.wifiRssiDbm) ?: 0,
    )
}

/**
 * Wi-Fi bars, or null when the TV has no link to report. `wifiRssiDbm` is 0 both
 * on Ethernet and when the read fails, so an unguarded `>= -55` lights all four
 * bars for a radio that was never measured — the one fabricated reading on a card
 * whose whole job is to be believed.
 */
internal fun wifiBars(rssiDbm: Int): Int? = when {
    rssiDbm >= 0 -> null
    rssiDbm >= -55 -> 4
    rssiDbm >= -65 -> 3
    rssiDbm >= -75 -> 2
    else -> 1
}
