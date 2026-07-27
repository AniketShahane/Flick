package com.flick.sender.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
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
import com.flick.sender.ui.theme.rememberFlickTouchHaptics
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
 * Nav host for the phone. Every route pair takes one of five deliberate arms (see
 * [routeMotion]); the quality sheet (S10) and advisories (S11) float as overlays over
 * whatever is beneath. There's no nav library — a single [Route] StateFlow drives
 * everything (thumb-first, one column).
 *
 * The whole tree sits in one `SharedTransitionLayout` because a video's decoded frame
 * has to survive four route changes as a single surface: tile -> detail backdrop, then
 * connecting -> the remote's poster.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
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
    val layoutDirection = LocalLayoutDirection.current
    val route by controller.route.collectAsState()
    val connectFromLibrary by controller.connectFromLibrary.collectAsState()
    val showQuality by controller.showQualitySheet.collectAsState()
    val showAdvisories by controller.showAdvisories.collectAsState()
    val showDiagnostics by controller.showDiagnostics.collectAsState()
    val reduceMotion = rememberReduceMotion()
    // Nav haptics are decided here, not inside the nav bar: a Remote tap can be
    // refused, and only the shell knows whether the tap moved or raised a toast.
    val haptics = rememberFlickTouchHaptics()

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

    SharedTransitionLayout {
        val sharedScope = this
        Box(Modifier.fillMaxSize().background(colors.surface)) {
            AnimatedContent(
                targetState = route,
                transitionSpec = {
                    if (reduceMotion) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        val from = SenderShellPolicy.destinationOf(initialState)
                        val to = SenderShellPolicy.destinationOf(targetState)
                        when (routeMotion(from, to)) {
                            // The frame carries the movement, so the chrome only has to get
                            // out of its way: any slower fade would ghost the hero.
                            RouteMotion.HERO ->
                                fadeIn(motionScheme.fastEffectsSpec()) togetherWith
                                    fadeOut(motionScheme.fastEffectsSpec())

                            RouteMotion.LAUNCH ->
                                (
                                    fadeIn(motionScheme.defaultEffectsSpec()) +
                                        scaleIn(motionScheme.defaultSpatialSpec(), initialScale = LaunchNear)
                                    ) togetherWith (
                                    fadeOut(motionScheme.fastEffectsSpec()) +
                                        scaleOut(motionScheme.defaultSpatialSpec(), targetScale = LaunchFar)
                                    )

                            RouteMotion.RETURN ->
                                (
                                    fadeIn(motionScheme.defaultEffectsSpec()) +
                                        scaleIn(motionScheme.defaultSpatialSpec(), initialScale = LaunchFar)
                                    ) togetherWith (
                                    fadeOut(motionScheme.fastEffectsSpec()) +
                                        scaleOut(motionScheme.defaultSpatialSpec(), targetScale = LaunchNear)
                                    )

                            RouteMotion.LATERAL -> {
                                val direction = physicalRouteDirection(
                                    logicalDirection = lateralDirection(from, to),
                                    layoutDirection = layoutDirection,
                                )
                                (
                                    fadeIn(motionScheme.defaultEffectsSpec()) +
                                        slideInHorizontally(motionScheme.defaultSpatialSpec()) { fullWidth ->
                                            fullWidth * direction / 4
                                        }
                                    ) togetherWith (
                                    fadeOut(motionScheme.defaultEffectsSpec()) +
                                        slideOutHorizontally(motionScheme.defaultSpatialSpec()) { fullWidth ->
                                            -fullWidth * direction / 6
                                        }
                                    )
                            }

                            RouteMotion.QUIET ->
                                fadeIn(motionScheme.defaultEffectsSpec()) togetherWith
                                    fadeOut(motionScheme.fastEffectsSpec())
                        }
                    }
                },
                label = "route",
            ) { r ->
                Box(Modifier.fillMaxSize().then(routeSemantics)) {
                    when (r) {
                        Route.Connect -> ConnectScreen(controller)
                        Route.Library -> LibraryScreen(
                            controller = controller,
                            onRequestVideoPermission = onRequestVideoPermission,
                            sharedScope = sharedScope,
                            animatedScope = this@AnimatedContent,
                        )
                        is Route.Detail -> DetailScreen(
                            controller = controller,
                            item = r.item,
                            sharedScope = sharedScope,
                            animatedScope = this@AnimatedContent,
                        )
                        Route.Connecting -> ConnectingScreen(
                            controller = controller,
                            sharedScope = sharedScope,
                            animatedScope = this@AnimatedContent,
                        )
                        Route.NowPlaying -> NowPlayingScreen(
                            controller = controller,
                            sharedScope = sharedScope,
                            animatedScope = this@AnimatedContent,
                        )
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
                                NavTab.LIBRARY -> {
                                    haptics.tabChange()
                                    controller.openLibrary()
                                }
                                NavTab.DEVICES -> {
                                    haptics.tabChange()
                                    controller.openConnect()
                                }
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
                                    RemoteTapOutcome.NAVIGATE -> {
                                        haptics.tabChange()
                                        controller.restoreNowPlaying()
                                    }
                                    RemoteTapOutcome.TOAST_PICK_A_TV -> {
                                        haptics.reject()
                                        toast.show(pickATv)
                                    }
                                    RemoteTapOutcome.TOAST_PICK_A_VIDEO -> {
                                        haptics.reject()
                                        toast.show(pickAVideo)
                                    }
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
}

/** The five arms of the route table. Every pair resolves to exactly one of them. */
private enum class RouteMotion { HERO, LAUNCH, RETURN, LATERAL, QUIET }

// The launch is the one transition that moves toward the TV rather than sideways: the
// surface being left recedes, the surface arriving overshoots past the viewer.
private const val LaunchNear = 1.06f
private const val LaunchFar = 0.92f

/**
 * Which arm a route pair takes. HERO pairs carry a shared frame, so they drop every
 * translation and let the frame itself be the movement; the launch pair is the cast
 * commit; a fault is always presented still.
 */
private fun routeMotion(initial: ShellDestination, target: ShellDestination): RouteMotion = when {
    initial == ShellDestination.LIBRARY && target == ShellDestination.DETAIL -> RouteMotion.HERO
    initial == ShellDestination.DETAIL && target == ShellDestination.LIBRARY -> RouteMotion.HERO
    initial == ShellDestination.CONNECTING && target == ShellDestination.NOW_PLAYING -> RouteMotion.HERO
    initial == ShellDestination.DETAIL && target == ShellDestination.CONNECTING -> RouteMotion.LAUNCH
    target == ShellDestination.FAILURE || initial == ShellDestination.FAILURE -> RouteMotion.QUIET
    // Leaving a committed cast — minimize, stop, cancel — inverts the launch.
    initial == ShellDestination.NOW_PLAYING || initial == ShellDestination.CONNECTING -> RouteMotion.RETURN
    lateralDirection(initial, target) != 0 -> RouteMotion.LATERAL
    else -> RouteMotion.QUIET
}

/** Left-to-right seat in the floating nav; -1 for the routes it does not represent. */
private fun navSeat(destination: ShellDestination): Int = when (destination) {
    ShellDestination.LIBRARY -> 0
    ShellDestination.NOW_PLAYING -> 1
    ShellDestination.CONNECT -> 2
    ShellDestination.DETAIL, ShellDestination.CONNECTING, ShellDestination.FAILURE -> -1
}

/**
 * Lateral travel only exists where the nav indicator also travels, so the screen and
 * the indicator can never disagree about which way the app just moved.
 */
private fun lateralDirection(initial: ShellDestination, target: ShellDestination): Int {
    val from = navSeat(initial)
    val to = navSeat(target)
    return when {
        from < 0 || to < 0 || from == to -> 0
        to > from -> 1
        else -> -1
    }
}

/** Maps logical forward/back navigation to physical offsets without changing route policy. */
internal fun physicalRouteDirection(logicalDirection: Int, layoutDirection: LayoutDirection): Int =
    if (layoutDirection == LayoutDirection.Rtl) -logicalDirection else logicalDirection

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
