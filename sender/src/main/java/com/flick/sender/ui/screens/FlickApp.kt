package com.flick.sender.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.flick.sender.R
import com.flick.sender.net.FlickController
import com.flick.sender.net.Route
import com.flick.sender.net.BackDisposition
import com.flick.sender.net.SenderNavigationPolicy
import com.flick.sender.ui.components.FlickBottomNav
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.Motion
import com.flick.sender.ui.theme.rememberReduceMotion
import kotlinx.coroutines.delay

private enum class Overlay { QUALITY, ADVISORIES, DIAGNOSTICS }

/** The shell's only transient surface: one message at a time, self-dismissing. */
@Stable
private class ShellToastState {
    var message by mutableStateOf("")
        private set
    var visible by mutableStateOf(false)
        private set

    /** Bumped on every raise so a repeat tap restarts the dwell instead of extending it. */
    var serial by mutableStateOf(0L)
        private set

    fun show(text: String) {
        message = text
        visible = true
        serial++
    }

    fun dismiss() {
        visible = false
    }
}

/**
 * Nav host for the phone. Screens cross-dissolve; the quality sheet (S10) and
 * advisories (S11) float as overlays over whatever is beneath. There's no nav
 * library — a single [Route] StateFlow drives everything (thumb-first, one column).
 */
@Composable
fun FlickApp(
    controller: FlickController,
    batteryExempt: Boolean,
    onRequestVideoPermission: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
) {
    val colors = LocalFlickColors.current
    val motionScheme = MaterialTheme.motionScheme
    val route by controller.route.collectAsState()
    val connectFromLibrary by controller.connectFromLibrary.collectAsState()
    val showQuality by controller.showQualitySheet.collectAsState()
    val showAdvisories by controller.showAdvisories.collectAsState()
    val showDiagnostics by controller.showDiagnostics.collectAsState()
    val reduceMotion = rememberReduceMotion()

    val toast = remember { ShellToastState() }
    val pickATv = stringResource(R.string.toast_pick_a_tv)
    val pickAVideo = stringResource(R.string.toast_pick_a_video)

    // Controller overlay flows are intentionally independent because pairing and
    // playback own their own state. The shell projects them as one visual layer.
    val activeOverlay = when {
        showDiagnostics -> Overlay.DIAGNOSTICS
        showAdvisories -> Overlay.ADVISORIES
        showQuality -> Overlay.QUALITY
        else -> null
    }
    val routeSemantics = if (activeOverlay == null) {
        Modifier.semantics { isTraversalGroup = true }
    } else {
        // The route remains visible behind a modal, but it must not remain reachable
        // to TalkBack until that modal is dismissed.
        Modifier.clearAndSetSemantics {}
    }
    val selectedTab = SenderShellPolicy.selectedTab(route)

    // Owned by the shell, not by the screens: routes cross-dissolve, so two screens are
    // briefly composed at once and per-screen save/restore pairs would fight over the
    // window's single insets controller.
    SystemBarAppearance(darkBackdrop = SenderShellPolicy.darkBackdrop(route, colors.isLight))

    // Connect is a dead-end for back only when it's the launch destination (no pairing
    // yet). When it was opened in-flow from Library (cast icon / flick with no TV),
    // back returns to Library instead of exiting the app.
    BackHandler(
        enabled = activeOverlay == null &&
            SenderNavigationPolicy.backDisposition(route, connectFromLibrary) != BackDisposition.SYSTEM,
    ) {
        controller.back()
    }

    // Declared after route navigation so a modal is always dismissed before the
    // underlying cast/pairing route receives Back.
    BackHandler(enabled = activeOverlay != null) {
        when (activeOverlay) {
            Overlay.QUALITY -> controller.toggleQualitySheet(false)
            Overlay.ADVISORIES -> controller.toggleAdvisories(false)
            Overlay.DIAGNOSTICS -> controller.toggleDiagnostics(false)
            null -> Unit
        }
    }

    Box(Modifier.fillMaxSize().background(colors.surface)) {
        AnimatedContent(
            targetState = route,
            transitionSpec = {
                if (reduceMotion) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    (
                        fadeIn(motionScheme.defaultEffectsSpec()) +
                            scaleIn(
                                motionScheme.defaultSpatialSpec(),
                                initialScale = 0.98f,
                            )
                        ) togetherWith (
                        fadeOut(motionScheme.defaultEffectsSpec()) +
                            scaleOut(
                                motionScheme.defaultSpatialSpec(),
                                targetScale = 0.98f,
                            )
                        )
                }
            },
            label = "route",
        ) { r ->
            Box(Modifier.fillMaxSize().then(routeSemantics)) {
                when (r) {
                    Route.Connect -> ConnectScreen(controller)
                    Route.Library -> LibraryScreen(controller, onRequestVideoPermission)
                    is Route.Detail -> DetailScreen(controller, r.item)
                    Route.Connecting -> ConnectingScreen(controller)
                    Route.NowPlaying -> NowPlayingScreen(controller)
                    is Route.Failure -> ErrorScreen(controller, r.kind, r.failure, onOpenWifiSettings)
                }
            }
        }

        // The nav floats over the route, so it takes the same modal semantics treatment.
        AnimatedVisibility(
            visible = SenderShellPolicy.navVisible(route),
            modifier = Modifier.align(Alignment.BottomCenter).then(routeSemantics),
            enter = if (reduceMotion) {
                EnterTransition.None
            } else {
                fadeIn(motionScheme.defaultEffectsSpec()) +
                    slideInVertically(motionScheme.defaultSpatialSpec()) { it }
            },
            exit = if (reduceMotion) {
                ExitTransition.None
            } else {
                fadeOut(motionScheme.fastEffectsSpec()) +
                    slideOutVertically(motionScheme.defaultSpatialSpec()) { it }
            },
            label = "nav",
        ) {
            FlickBottomNav(
                selected = selectedTab,
                onSelect = { tab ->
                    // Re-selecting the current tab is a no-op: openConnect() also arms the
                    // in-flow back behavior, which would strand Back on the launch route.
                    if (tab != selectedTab) {
                        when (tab) {
                            NavTab.LIBRARY -> controller.openLibrary()
                            NavTab.DEVICES -> controller.openConnect()
                            // Read at tap time so the shell never subscribes to cast state
                            // it does not render.
                            NavTab.REMOTE -> when (
                                SenderShellPolicy.remoteTapOutcome(
                                    hasConnectedTv = controller.connectedTv.value != null,
                                    hasActiveSession = SenderNavigationPolicy.canRestoreNowPlaying(
                                        controller.castStart.value,
                                        controller.castingItem.value != null,
                                    ),
                                )
                            ) {
                                RemoteTapOutcome.NAVIGATE -> controller.restoreNowPlaying()
                                RemoteTapOutcome.TOAST_PICK_A_TV -> toast.show(pickATv)
                                RemoteTapOutcome.TOAST_PICK_A_VIDEO -> toast.show(pickAVideo)
                            }
                        }
                    }
                },
                modifier = Modifier.navigationBarsPadding().padding(16.dp),
            )
        }

        AnimatedContent(
            targetState = activeOverlay,
            transitionSpec = {
                if (reduceMotion) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    fadeIn(motionScheme.defaultEffectsSpec()) togetherWith
                        fadeOut(motionScheme.fastEffectsSpec())
                }
            },
            label = "overlay",
        ) { overlay ->
            when (overlay) {
                Overlay.QUALITY -> QualitySheet(
                    controller = controller,
                    onDismiss = { controller.toggleQualitySheet(false) },
                )
                Overlay.ADVISORIES -> AdvisoriesScreen(
                    batteryExempt = batteryExempt,
                    onOpenWifiSettings = onOpenWifiSettings,
                    onRequestBatteryExemption = onRequestBatteryExemption,
                    onOpenDiagnostics = {
                        controller.toggleAdvisories(false)
                        controller.toggleDiagnostics(true)
                    },
                    onDismiss = { controller.toggleAdvisories(false) },
                )
                Overlay.DIAGNOSTICS -> DiagnosticsSheet(
                    onDismiss = { controller.toggleDiagnostics(false) },
                )
                null -> Unit
            }
        }

        // Reads of the toast live inside the host so raising one never recomposes a route.
        ShellToast(
            state = toast,
            reduceMotion = reduceMotion,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * The window theme sets dark system-bar icons for the light screens; over a cinematic
 * backdrop — or in system dark mode, where every route resolves to it — they have to
 * invert. Both bars move together: the gesture handle sits on the same backdrop the
 * status bar does. Restored on dispose so the setting never outlives the Compose tree
 * that asked for it.
 */
@Composable
private fun SystemBarAppearance(darkBackdrop: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(view, darkBackdrop) {
        val window = view.context.findActivity()?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, view) }
        val previousStatus = insets?.isAppearanceLightStatusBars
        val previousNavigation = insets?.isAppearanceLightNavigationBars
        insets?.isAppearanceLightStatusBars = !darkBackdrop
        insets?.isAppearanceLightNavigationBars = !darkBackdrop
        onDispose {
            if (insets != null) {
                if (previousStatus != null) insets.isAppearanceLightStatusBars = previousStatus
                if (previousNavigation != null) {
                    insets.isAppearanceLightNavigationBars = previousNavigation
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Rises 120 dp above the bottom edge so it clears the floating nav (design §5.7). */
@Composable
private fun ShellToast(
    state: ShellToastState,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFlickColors.current
    val density = LocalDensity.current
    val rise = remember(density) { with(density) { Motion.SheetRiseOffsetDp.dp.roundToPx() } }
    LaunchedEffect(state.serial) {
        if (state.visible) {
            delay(Motion.ToastMs.toLong())
            state.dismiss()
        }
    }
    AnimatedVisibility(
        visible = state.visible,
        modifier = modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 120.dp),
        enter = if (reduceMotion) {
            EnterTransition.None
        } else {
            fadeIn(Motion.sheetRise()) +
                slideInVertically(Motion.sheetRise()) { rise } +
                scaleIn(Motion.sheetRise(), initialScale = Motion.SheetRiseScale)
        },
        exit = if (reduceMotion) ExitTransition.None else fadeOut(Motion.sheetRise()),
        label = "toast",
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(colors.inverseSurface, RoundedCornerShape(FlickCorners.toast))
                .padding(horizontal = 19.dp, vertical = 15.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(
                text = state.message,
                style = FlickText.bodyMedium.copy(color = colors.onInverseSurface),
            )
        }
    }
}
