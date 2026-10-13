package com.flick.sender.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.ExperimentalTransitionApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.createChildTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
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
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flick.sender.media.MediaPermissionState
import com.flick.sender.model.MediaItem
import com.flick.sender.net.BackDisposition
import com.flick.sender.net.FlickController
import com.flick.sender.net.Route
import com.flick.sender.net.SenderNavigationPolicy
import com.flick.sender.support.SupportCatalog
import com.flick.sender.ui.components.CardFlipHandoff
import com.flick.sender.ui.components.FlickBottomNav
import com.flick.sender.ui.components.LocalCardFlipHandoff
import com.flick.sender.ui.components.LocalNavMetrics
import com.flick.sender.ui.components.LocalQualityRevealOrigin
import com.flick.sender.ui.components.NavMetrics
import com.flick.sender.ui.components.NavShellMargin
import com.flick.sender.ui.components.NowPlayingDock
import com.flick.sender.ui.components.RevealOrigin
import com.flick.sender.ui.components.RevealTarget
import com.flick.sender.ui.components.originRevealMask
import com.flick.sender.ui.components.remoteCardBounds
import com.flick.sender.ui.motion.animatingFrameRate
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.Motion
import com.flick.sender.ui.theme.ThemePreference
import com.flick.sender.ui.theme.rememberFlickTouchHaptics
import com.flick.sender.ui.theme.rememberReduceMotion
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The floating surfaces that are summoned over whatever route is beneath them. Each
 * carries the channel a control publishes an origin on, so the layer below can spend
 * whatever was published without knowing which control did it.
 */
internal enum class Overlay(val revealTarget: RevealTarget?) {
    QUALITY(RevealTarget.QUALITY),
    DIAGNOSTICS(RevealTarget.DIAGNOSTICS),
    SUPPORT(null),
}

/**
 * What the chrome was showing before the current route. Deliberately not snapshot state:
 * both fields are read only on the frame a piece of chrome changes visibility, and
 * observing them would recompose the whole shell a second time on every route change.
 */
private class ShellHistory(var destination: ShellDestination, var seat: NavTab)

/** The route the landing check last saw; plain state for the same reason as [ShellHistory]. */
private class RouteMemo(var route: Route)

/**
 * Nav host for the phone. Every route pair takes one of six deliberate arms (see
 * [routeMotion]); the quality sheet (S10) and the diagnostics log float as overlays over
 * whatever is beneath. There's no nav library — a single [Route] StateFlow drives
 * everything (thumb-first, one column).
 *
 * The whole tree sits in one `SharedTransitionLayout` because two surfaces have to
 * survive a route change as a single object: a video's decoded frame (tile -> detail
 * backdrop, connecting -> the remote's poster), and the now-playing dock, whose own
 * bounds are what the remote grows out of and shrinks back into.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun FlickApp(
    controller: FlickController,
    supportCatalog: SupportCatalog?,
    batteryExempt: Boolean,
    notificationsGranted: Boolean,
    mediaPermission: MediaPermissionState,
    themePreference: ThemePreference,
    onSelectTheme: (ThemePreference) -> Unit,
    onRequestVideoPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenCheckout: (String) -> Unit,
) {
    val colors = LocalFlickColors.current
    val motionScheme = MaterialTheme.motionScheme
    val layoutDirection = LocalLayoutDirection.current
    val route by controller.route.collectAsState()
    val connectFromLibrary by controller.connectFromLibrary.collectAsState()
    // The shell subscribes to the cast record because it renders a decision on it: with
    // no dock there is nothing for the remote's card to grow out of, and the route pair
    // has to fall back to a transition of its own.
    val castingItem by controller.castingItem.collectAsState()
    val showQuality by controller.showQualitySheet.collectAsState()
    val showDiagnostics by controller.showDiagnostics.collectAsState()
    val showSupportSheet by controller.showSupportSheet.collectAsState()
    val reduceMotion = rememberReduceMotion()
    // Nav haptics are decided here, not inside the nav bar: only the shell knows whether
    // a tap moved the app or landed on the seat it was already on.
    val haptics = rememberFlickTouchHaptics()

    // A sheet a ROUTE raises is drawn inside that route, which the chrome below floats
    // over — so the chrome has to be told to leave. Counted rather than flagged; see
    // [LocalSheetDepth].
    val sheetDepth = remember { mutableIntStateOf(0) }
    val sheetRaised = sheetDepth.intValue > 0
    // The overlay layer is the last child of the root box, so a sheet hosted there is
    // painted OVER the chrome and already covers it. Those sheets are handed a counter of
    // their own: driving the one above would evict a bar that is not in anybody's way and
    // then play it back in, visibly, through a scrim that is only half opaque.
    val overlaySheetDepth = remember { mutableIntStateOf(0) }
    // One channel, bound to the quality sheet, because that is the sheet two different
    // controls on the remote open. The diagnostics log's own openers publish nothing, and
    // the binding is what guarantees they cannot inherit a remote's origin for it.
    val qualityRevealOrigin = remember { RevealOrigin(RevealTarget.QUALITY) }
    // Owned here because the bar and the routes that reserve room for it are siblings: the
    // shell is the only composition both of them are inside.
    val navMetrics = remember { NavMetrics() }
    val navHazeState = rememberHazeState()
    // Same reason, one level up: a stack left with its page up is disposed, and the one that
    // replaces it comes back seated where the grid was left.
    val libraryUi = rememberLibraryUiState()
    // Library and Detail are one entry to the route switch (see [LibraryStack]): the page is
    // drawn over a grid that stays composed, so closing it only has to draw the grid again.
    // Plain, for [ShellHistory]'s reason; written before the switch reads it below.
    val stackTop = remember { StackTop(Route.Library) }
    if (route == Route.Library || route is Route.Detail) stackTop.route = route
    // A stack left with its page up is a new entry if it comes back, even mid-exit: the
    // entry still leaving keeps its page, and the one arriving starts settled where it is.
    val stackGeneration = remember { StackGeneration() }
    val generation = remember(route) { stackGeneration.after(route) }
    val baseRoute = if (route == Route.Library || route is Route.Detail) {
        RouteEntry(Route.Library, generation)
    } else {
        RouteEntry(route, 0)
    }
    // The stack is composed outside the route switch, whose entry only draws it (see
    // [LibraryHosts]), so another route coming up over it no longer rebuilds it on return.
    val libraryHosts = remember { LibraryHosts() }
    if (baseRoute.route == Route.Library) libraryHosts.entered = generation
    val keptStack = libraryHosts.entered.takeIf { it == generation }
    // Shared by the two ends of the card flip and read here: the card coming back down is what
    // releases the chrome below.
    val cardFlipHandoff = remember { CardFlipHandoff() }

    val destination = SenderShellPolicy.destinationOf(route)
    val dockLive = castingItem != null
    val navShown = SenderShellPolicy.navVisible(route)
    val selectedTab = SenderShellPolicy.selectedTab(route)
    val history = remember { ShellHistory(destination, selectedTab) }
    // Everything the flight is started with, latched in the composition the route flips
    // in and never read live afterwards: a cast the TV ends mid-flight would otherwise
    // swap the specs a running transition began with — removing a fade half way through
    // it, or handing the dock a slide it can only perform where nobody can see it. The
    // pair, not the destination, is what decides whether the dock is morphing.
    val flightDockLive = remember(route) { dockLive }
    val flightMorph = remember(route) {
        dockLive && SenderShellPolicy.dockMorph(history.destination, destination)
    }
    // The latch's other arm, decided off the pair for the same reason and at the same
    // moment: which surface the app came FROM is what says whether a frame is about to
    // land on the chrome's seat, and by the time the flight is running that is gone.
    //
    // The pair is also all there is to decide on: nothing about the seat can be asked on
    // the frame the route flips, and the grid stays composed under the page, so the tile
    // that was tapped is still there to land on.
    // What survives is a file that left the library while the sheet was up, which spends
    // the hold with nothing in the air; it is bounded by [HeroLandMs] and it is the only
    // arm of this that a wrong answer costs a wait rather than a bar under a poster.
    val heroReturn = remember(route) {
        SenderShellPolicy.heroReturn(history.destination, destination)
    }
    // Decided off the pair at the same moment, and handed to the page: its tracks need to
    // know on their first frame whether they are half of the card flip, before it has been
    // measured and long before a match with the tile could be found.
    // Every route that arrives takes the next layer up, so it is drawn over whatever it is
    // leaving. A page keeps its layer for its whole stay, which is what keeps a page that turns
    // round mid-transition on the side it was; the target is otherwise drawn last.
    val routeLayers = remember { RouteLayers() }
    val cardFlip = remember(route) {
        !reduceMotion && routeMotion(history.destination, destination, dockLive) == RouteMotion.FLIP
    }
    // Identity rather than the route itself: re-opening the remote while the minimize is
    // still in the air is a new flight, even though it lands where the last one started.
    val flight = remember(route) { Any() }
    var flown by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(flight) {
        // One latch, two arms, and no pair can take both — see [SenderShellPolicy.heroReturn].
        if (flightMorph) {
            delay(Motion.CardMorphLatchMs)
        } else if (heroReturn && !reduceMotion) {
            // Released by the card itself, a little before it touches down, so the stack's
            // rise overlaps the card's last settle instead of starting after it.
            withTimeoutOrNull(HeroLandMs) {
                snapshotFlow { cardFlipHandoff.current()?.nearContact == true }.first { it }
            }
        }
        flown = flight
    }
    // Released once the geometry is out of the air, because after that a cast that ends
    // has to be able to take the bar out with an exit of its own.
    val morphing = flightMorph && flown !== flight
    // The card is drawn in the shell's overlay, which the transition layout paints after
    // every child of its own — so while it is up there, nothing the chrome does underneath
    // can be seen, and where the card lands the chrome is what it lands on. The stack waits
    // below the window until the card is nearly down; the card hands itself back to its tile
    // as it lands, not when the route transition ends, so the rise is never under it for long.
    val heroLanding = heroReturn && !reduceMotion && flown !== flight
    // The bottom stack rises as ONE movement once the frame is down. Each bar's own
    // entrance would carry it only its own height, which walks the dock through the nav's
    // seat on the way up; this translates the column that already holds them apart. Held
    // off the window by [heroLanding] itself so the very frame the route flips on is
    // already clear, and the animator only ever runs the release.
    val chromeRise = remember { Animatable(0f) }
    LaunchedEffect(heroLanding) {
        when {
            heroLanding -> chromeRise.snapTo(1f)
            chromeRise.value == 0f -> Unit
            reduceMotion -> chromeRise.snapTo(0f)
            // Unconditional: whatever ended the flight — the timer, a tap on another tile,
            // a cast dying — the stack cannot be left below the window edge.
            else -> chromeRise.animateTo(0f, motionScheme.defaultSpatialSpec<Float>())
        }
    }
    // The nav outlives the route that dismissed it, so it keeps the seat it was showing:
    // re-seating the pill for a route the nav does not serve would travel it under the
    // card that is swallowing it.
    if (navShown) history.seat = selectedTab
    SideEffect { history.destination = destination }

    // Controller overlay flows are intentionally independent because pairing and
    // playback own their own state. The shell projects them as one visual layer.
    val activeOverlay = when {
        supportCatalog != null && showSupportSheet -> Overlay.SUPPORT
        showDiagnostics -> Overlay.DIAGNOSTICS
        showQuality -> Overlay.QUALITY
        else -> null
    }
    // The overlay the shell is still HOSTING after the app has stopped asking for it: the
    // frames a sheet spends travelling off the window. Written only from the sheets' own
    // callbacks below, never from composition.
    var outgoingOverlay by remember { mutableStateOf<Overlay?>(null) }
    val hosted = hostedOverlay(
        active = activeOverlay,
        outgoing = outgoingOverlay,
        supportAvailable = supportCatalog != null,
    )
    // The exit has begun, and clearing the controller flag HERE rather than at the end of
    // it is the whole point: the flags are `StateFlow`s, which conflate, so a control
    // pressed while the flag it sets is still true publishes nothing at all — the sheet
    // does not come back and the press is simply spent. Cleared on this frame the control
    // is live again for the whole exit, and [outgoingOverlay] is what holds the sheet in
    // the composition across the gap that opens.
    val overlayLeaving = remember<(Overlay) -> Unit>(controller) {
        { overlay ->
            outgoingOverlay = overlay
            when (overlay) {
                Overlay.QUALITY -> controller.toggleQualitySheet(false)
                Overlay.DIAGNOSTICS -> controller.toggleDiagnostics(false)
                Overlay.SUPPORT -> controller.toggleSupportSheet(false)
            }
        }
    }
    // The sheet is off the window: stop holding the layer for it. Guarded by identity
    // because the layer may already have been claimed by another overlay, whose exit this
    // one must not cancel.
    val overlayGone = remember<(Overlay) -> Unit> {
        { overlay -> if (outgoingOverlay == overlay) outgoingOverlay = null }
    }
    val routeSemantics = if (hosted == null) {
        Modifier.semantics { isTraversalGroup = true }
    } else {
        // The route remains visible behind a modal, but it must not remain reachable
        // to TalkBack until that modal is dismissed.
        Modifier.clearAndSetSemantics {}
    }

    // Owned by the shell, not by the screens: routes cross-dissolve, so two screens are
    // briefly composed at once and per-screen save/restore pairs would fight over the
    // window's single insets controller.
    val backdropDark = SenderShellPolicy.darkBackdrop(route, colors.isLight)
    // Under the dock's container transform the bars belong to the card rather than to the
    // route, and the card does not reach either of them until it is nearly grown — so a
    // flip INTO the remote waits for it instead of inverting the icons over a canvas that
    // is still pale. Coming back the card releases both bars within a few frames, so that
    // flip stays immediate.
    var barsDark by remember { mutableStateOf(backdropDark) }
    LaunchedEffect(backdropDark, morphing, reduceMotion) {
        // Nothing travels when the platform's animators are off, so the card is already
        // there and the bars have to change hands with it.
        if (morphing && backdropDark && !reduceMotion) delay(Motion.CardMorphBarHandoffMs)
        barsDark = backdropDark
    }
    SystemBarAppearance(darkBackdrop = barsDark)

    // The cast landing: the handshake screen handing over to the remote, and only that
    // pair — reopening the remote from the dock is not a landing. Remembered outside
    // snapshot state because only this effect reads it.
    val landingFlash = remember { Animatable(0f) }
    val landedFrom = remember { RouteMemo(route) }
    LaunchedEffect(route) {
        val from = landedFrom.route
        landedFrom.route = route
        if (from == Route.Connecting && route == Route.NowPlaying) {
            haptics.confirm()
            if (!reduceMotion) {
                landingFlash.snapTo(0f)
                landingFlash.animateTo(LandingFlashPeak, tween(LandingFlashInMs, easing = LinearEasing))
                landingFlash.animateTo(0f, tween(LandingFlashOutMs, easing = LandingFlashOut))
            }
        } else if (landingFlash.value != 0f) {
            // A route change mid-flash cancels the effect that was fading it out.
            landingFlash.snapTo(0f)
        }
    }

    // Connect is a dead-end for back only when it's the launch destination (no pairing
    // yet). When it was opened in-flow from Library (cast icon / flick with no TV),
    // back returns to Library instead of exiting the app.
    BackHandler(
        enabled = hosted == null &&
            SenderNavigationPolicy.backDisposition(route, connectFromLibrary) != BackDisposition.SYSTEM,
    ) {
        controller.back()
    }

    // Declared after route navigation so a modal is always dismissed before the
    // underlying cast/pairing route receives Back.
    //
    // Gated on what is HOSTED and not on what the app wants, because those now part for
    // the length of an exit. A press landing in those frames belongs to the sheet that is
    // still on the window and not to the route behind it — the same rule the sheet's own
    // handler states, and the sheet's is registered later and therefore answers first. So
    // in practice this arm runs only for a hosted overlay that raised no sheet of its own,
    // and what it must not do is let the route take a press aimed at a modal still in view.
    BackHandler(enabled = hosted != null) {
        when (hosted) {
            Overlay.QUALITY -> controller.toggleQualitySheet(false)
            Overlay.DIAGNOSTICS -> controller.toggleDiagnostics(false)
            Overlay.SUPPORT -> controller.toggleSupportSheet(false)
            null -> Unit
        }
    }

    // Provided around the whole tree, not around the route alone: a sheet may be
    // raised from any surface the shell hosts, and only the shell knows where the
    // floating chrome is.
    CompositionLocalProvider(
        LocalSheetDepth provides sheetDepth,
        LocalQualityRevealOrigin provides qualityRevealOrigin,
        LocalNavMetrics provides navMetrics,
        LocalCardFlipHandoff provides cardFlipHandoff,
    ) {
        // Over the routes AND over the shared-element overlay, which the transition layout
        // paints after all of its own children: the landing flash covers the whole window.
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    val flash = landingFlash.value
                    if (flash > 0f) drawRect(Color.White, alpha = flash)
                },
        ) {
            SharedTransitionLayout {
                val sharedScope = this
                Box(
                    Modifier
                        .fillMaxSize()
                        // The window takes the highest vote any of its layers casts, so this one
                        // also covers the overlay the travelling copy is drawn in.
                        .animatingFrameRate(sharedScope.isTransitionActive)
                        .background(colors.surface),
                ) {
                    AnimatedContent(
                        modifier = Modifier
                            .fillMaxSize()
                            // One source for the one small navigation effect. Keeping this at
                            // the route boundary lets both themes show the real content behind
                            // the pill without adding blur nodes to the scrolling children.
                            .hazeSource(navHazeState),
                        targetState = baseRoute,
                        transitionSpec = {
                            val from = stackAwareDestination(initialState.route, stackTop)
                            val to = stackAwareDestination(targetState.route, stackTop)
                            val motion = routeMotion(from, to, flightDockLive)
                            val transform = if (reduceMotion) {
                                EnterTransition.None togetherWith ExitTransition.None
                            } else {
                                when (motion) {
                                    // Never a pair this switch sees: Library and Detail share
                                    // one entry, and [LibraryStack] runs the flip between them.
                                    RouteMotion.FLIP ->
                                        EnterTransition.None togetherWith ExitTransition.None

                                    // The frame carries the movement, so the chrome only has to get
                                    // out of its way: any slower fade would ghost the hero.
                                    RouteMotion.HERO ->
                                        fadeIn(motionScheme.fastEffectsSpec()) togetherWith
                                            fadeOut(motionScheme.fastEffectsSpec())

                                    // The dock's own bounds are the transition. Nothing here may
                                    // translate, scale or fade: the arriving remote is drawn only
                                    // inside the growing card, and the surface it is leaving is not
                                    // leaving at all — it is being covered, so it is HELD at full
                                    // opacity until the card owns the window and then dropped where
                                    // no one can see it go. A hold is the only way the shell can
                                    // keep a surface alive that has no animation of its own.
                                    RouteMotion.CONTAINER ->
                                        if (to == ShellDestination.NOW_PLAYING) {
                                            EnterTransition.None togetherWith
                                                fadeOut(snap(delayMillis = Motion.CardMorphHoldMs))
                                        } else {
                                            // Coming back, the remote's own card keeps it composed
                                            // for the whole flight.
                                            EnterTransition.None togetherWith ExitTransition.None
                                        }

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
                            transform.apply { targetContentZIndex = routeLayers.next() }
                        },
                        label = "route",
                    ) { entry ->
                        val r = entry.route
                        Box(
                            Modifier
                                .fillMaxSize()
                                .animatingFrameRate(this@AnimatedContent.transition.isRunning)
                                .then(routeSemantics)
                                .then(
                                    // Declared on the container, never inside the remote: the
                                    // card has to be the whole window's worth of surface, and the
                                    // screen it wraps knows nothing about the bar it came from.
                                    if (r == Route.NowPlaying) {
                                        Modifier.remoteCardBounds(
                                            sharedScope = sharedScope,
                                            animatedScope = this@AnimatedContent,
                                            // The remote is the surface being left exactly when
                                            // the route it is handing over to has a dock to land
                                            // in. A fault or a stop has none, and takes a
                                            // transition of its own instead. Read off the latch,
                                            // so a flight that has begun finishes the geometry it
                                            // started even if the cast dies underneath it.
                                            leaving = morphing && route != Route.NowPlaying,
                                        )
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) {
                            when (r) {
                                Route.Connect -> ConnectScreen(controller, onOpenWifiSettings)
                                Route.Library, is Route.Detail -> {
                                    val stack = entry.generation
                                    DisposableEffect(stack) {
                                        libraryHosts.placed[stack] = Unit
                                        onDispose { libraryHosts.placed.remove(stack) }
                                    }
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .drawBehind { libraryHosts.layers[stack]?.let { drawLayer(it) } },
                                    )
                                }
                                Route.Settings -> PhoneSettingsScreen(
                                    controller = controller,
                                    supportAvailable = supportCatalog != null,
                                    onOpenSupport = { controller.toggleSupportSheet(true) },
                                    batteryExempt = batteryExempt,
                                    notificationsGranted = notificationsGranted,
                                    themePreference = themePreference,
                                    onSelectTheme = onSelectTheme,
                                    onOpenWifiSettings = onOpenWifiSettings,
                                    onRequestBatteryExemption = onRequestBatteryExemption,
                                    onOpenNotificationSettings = onOpenNotificationSettings,
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

                    // After the route switch, so the stack that is the route takes touches ahead
                    // of a screen still leaving, and below the bottom stack that floats over it.
                    LibraryHostLayer(libraryHosts, keptStack) { stack, placed ->
                        // Read here, not off [stackTop] alone, so this stack recomposes when the
                        // page opens or closes over it. A stack that is not the route keeps the
                        // page it left with.
                        val live = baseRoute == RouteEntry(Route.Library, stack)
                        val entryTop = remember { StackTop(stackTop.route) }
                        if (live) entryTop.route = stackTop.route
                        // One grid at a time: every grid shares the one scroll state. A stack
                        // already replaced by a newer one is leaving under its page, so its grid goes.
                        val replaced = !live && baseRoute.route == Route.Library
                        LibraryHost(
                            hosts = libraryHosts,
                            stack = stack,
                            live = live,
                            shown = live || placed,
                            semantics = routeSemantics,
                        ) {
                            LibraryStack(
                                top = entryTop.route,
                                interactive = live && route == Route.Library,
                                sharedScope = sharedScope,
                                reduceMotion = reduceMotion,
                                library = { tileScope ->
                                    if (!replaced) {
                                        LibraryScreen(
                                            controller = controller,
                                            supportAvailable = supportCatalog != null,
                                            onOpenSupport = { controller.toggleSupportSheet(true) },
                                            onRequestVideoPermission = onRequestVideoPermission,
                                            mediaPermission = mediaPermission,
                                            onOpenAppSettings = onOpenAppSettings,
                                            uiState = libraryUi,
                                            sharedScope = sharedScope,
                                            animatedScope = tileScope,
                                            // The tiles' scope only ever moves for the flip, so a
                                            // constant here keeps the grid from recomposing on the
                                            // frame the page opens.
                                            cardFlip = !reduceMotion,
                                            active = { live && route == Route.Library },
                                        )
                                    }
                                },
                                detail = { item, seatScope ->
                                    DetailScreen(
                                        controller = controller,
                                        item = item,
                                        sharedScope = sharedScope,
                                        animatedScope = seatScope,
                                        cardFlip = cardFlip,
                                    )
                                },
                            )
                        }
                    }

                    // The dock and the nav are one bottom stack: the dock has to rise off the
                    // nav's top edge, not off the window's. Both float over the route, so both
                    // take the same modal semantics treatment.
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            // Above the inset and the margin, so the height this travels is the
                            // whole stack's — one of them puts every bar past the window's
                            // bottom edge rather than stopping at the gesture handle. Read in
                            // the placement pass, so the rise never recomposes what it moves.
                            .layout { measurable, constraints ->
                                val placeable = measurable.measure(constraints)
                                layout(placeable.width, placeable.height) {
                                    val lift = if (heroLanding) 1f else chromeRise.value
                                    placeable.place(0, (placeable.height * lift).roundToInt())
                                }
                            }
                            .navigationBarsPadding()
                            // The same margin the routes' own clearance is derived from.
                            .padding(NavShellMargin)
                            .then(routeSemantics),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        NowPlayingDock(
                            controller = controller,
                            // With no Remote seat the dock is the only door to the remote, so it
                            // belongs on every surface the nav floats over — but never over a
                            // sheet the ROUTE raises, which is drawn inside that route and so
                            // passes underneath the dock.
                            allowed = SenderShellPolicy.dockVisible(route) && !sheetRaised,
                            morphing = morphing,
                            // The same source the pill below it reads. Both surfaces are
                            // siblings over the route, so one source at the route boundary
                            // serves the whole bottom stack.
                            hazeState = navHazeState,
                            sharedScope = sharedScope,
                            onOpen = { controller.restoreNowPlaying() },
                        )
                        AnimatedVisibility(
                            visible = navShown && !sheetRaised,
                            enter = when {
                                // Coming back from the remote the nav is already under a card that
                                // covers the window; it is revealed in place as that card shrinks,
                                // and any rise of its own would be seen through the gap.
                                reduceMotion || morphing -> EnterTransition.None
                                // A frame landing needs no arm of its own: this rise is spent
                                // below the window edge while the stack is held there, and it
                                // is long over by the time the stack itself lifts.
                                else -> fadeIn(motionScheme.defaultEffectsSpec()) +
                                    slideInVertically(motionScheme.defaultSpatialSpec()) { it }
                            },
                            exit = when {
                                reduceMotion -> ExitTransition.None
                                // The nav does not leave when the dock above it becomes the card:
                                // it is covered. So it holds its place, unchanged, and is removed
                                // once the card owns the window. A route's sheet rising is not
                                // that — nothing covers the bar there, and holding its place
                                // would leave the pill on top of the sheet for the whole hold.
                                morphing && !sheetRaised ->
                                    fadeOut(snap(delayMillis = Motion.CardMorphHoldMs))
                                else -> fadeOut(motionScheme.fastEffectsSpec()) +
                                    slideOutVertically(motionScheme.defaultSpatialSpec()) { it }
                            },
                            label = "nav",
                        ) {
                            FlickBottomNav(
                                selected = history.seat,
                                hazeState = navHazeState,
                                onSelect = { tab ->
                                    // Re-selecting the current tab is a no-op: openConnect() also arms the
                                    // in-flow back behavior, which would strand Back on the launch route.
                                    if (tab != selectedTab) {
                                        haptics.tabChange()
                                        when (tab) {
                                            NavTab.LIBRARY -> controller.openLibrary()
                                            NavTab.DEVICES -> controller.openConnect()
                                            NavTab.SETTINGS -> controller.openSettings()
                                        }
                                    }
                                },
                            )
                        }
                    }

                    AnimatedContent(
                        targetState = hosted,
                        transitionSpec = {
                            // No enter fade: the radial mask below IS the entrance, and a
                            // simultaneous fade would only wash out the edge it travels on.
                            val exit = if (
                                !reduceMotion &&
                                shouldFadeOverlayExit(
                                    initialPresent = initialState != null,
                                    targetPresent = targetState != null,
                                )
                            ) {
                                fadeOut(motionScheme.fastEffectsSpec())
                            } else {
                                // A sheet is hosted for the whole of its own travel and reports
                                // itself gone only once it is off the window. Retaining that
                                // already-spent full-screen surface for another fade leaves its
                                // invisible scrim on top of Settings/Devices, where it consumes
                                // the next rapid tap.
                                ExitTransition.None
                            }
                            val transform = EnterTransition.None togetherWith exit
                            // Both states are full-screen, so the default size transform has
                            // nothing to interpolate except the empty state's zero size — and
                            // it grew the sheet out of the top-left corner doing it.
                            transform using SizeTransform(clip = false)
                        },
                        label = "overlay",
                    ) { overlay ->
                        // Read in COMPOSITION, and latched for as long as this surface is
                        // composed. An effect would run a frame after the disc had already been
                        // drawn at full coverage, and a fresh read on any later recomposition
                        // would hand the travelling disc the null a spent channel now returns
                        // and snap it back to the surface's own centre mid-flight.
                        val bornAt = remember(overlay) {
                            overlay?.revealTarget?.let { qualityRevealOrigin.consume(it) }
                        }
                        if (overlay != null) {
                            // Whatever took this copy away — its own exit landing, or a second
                            // overlay claiming the layer while this one was still travelling —
                            // the shell no longer has it and must stop holding the layer for
                            // it. Left held, the next close would find an overlay still
                            // outgoing and put this sheet back on the window.
                            DisposableEffect(overlay) { onDispose { overlayGone(overlay) } }
                        }
                        Box(
                            Modifier
                                .fillMaxSize()
                                .originRevealMask(from = bornAt, enabled = overlay != null),
                        ) {
                            // These two are the layer that covers the chrome, not a layer the
                            // chrome covers, so they take the detached counter: the shell's own
                            // is what a route's sheet uses to clear the bars out from under it.
                            CompositionLocalProvider(LocalSheetDepth provides overlaySheetDepth) {
                                // [visible] is what the app still wants, which parts from what
                                // is hosted for exactly one exit — and a sheet asked back
                                // inside that window returns to its seat instead of being torn
                                // down and played in again.
                                when (overlay) {
                                    Overlay.QUALITY -> QualitySheet(
                                        controller = controller,
                                        visible = activeOverlay == Overlay.QUALITY,
                                        onLeaving = { overlayLeaving(Overlay.QUALITY) },
                                        onDismiss = { overlayGone(Overlay.QUALITY) },
                                    )
                                    Overlay.DIAGNOSTICS -> DiagnosticsSheet(
                                        visible = activeOverlay == Overlay.DIAGNOSTICS,
                                        onLeaving = { overlayLeaving(Overlay.DIAGNOSTICS) },
                                        onDismiss = { overlayGone(Overlay.DIAGNOSTICS) },
                                    )
                                    Overlay.SUPPORT -> supportCatalog?.let { catalog ->
                                        SupportFlickSheet(
                                            catalog = catalog,
                                            visible = activeOverlay == Overlay.SUPPORT,
                                            onLeaving = { overlayLeaving(Overlay.SUPPORT) },
                                            onDismiss = { overlayGone(Overlay.SUPPORT) },
                                            onOpenCheckout = { checkoutUrl ->
                                                controller.toggleSupportSheet(false)
                                                onOpenCheckout(checkoutUrl)
                                            },
                                        )
                                    }
                                    null -> Unit
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Ever-higher layers for arriving routes. */
private class RouteLayers {
    private var top = 0f

    fun next(): Float = ++top
}

/** Which of Library or Detail the stack last showed; plain for [ShellHistory]'s reason. */
private class StackTop(var route: Route)

/** The route switch's key: the stack's entry is [Route.Library] in its [generation]. */
private data class RouteEntry(val route: Route, val generation: Int)

/** Counts the times the route left the stack with a page up. Plain, for [ShellHistory]'s reason. */
private class StackGeneration {
    private var last: Route? = null
    private var count = 0

    fun after(route: Route): Int {
        val inStack = route == Route.Library || route is Route.Detail
        if (last is Route.Detail && !inStack) count++
        last = route
        return count
    }
}

/** A route as the pair table sees it: the stack's entry stands for whichever page is on top. */
private fun stackAwareDestination(state: Route, top: StackTop): ShellDestination =
    SenderShellPolicy.destinationOf(if (state == Route.Library) top.route else state)

/**
 * The library with the detail page over it. The grid stays composed — painters, measured text
 * and scroll position — while the page is up, and is skipped at draw once the page is settled
 * over it. Its tiles get a visibility scope that runs opposite to the page's, so the card flip
 * between the two ends still has an exiting and an entering side.
 *
 * [top] is [Route.Library] or a [Route.Detail], and while the stack itself is leaving it is
 * whichever of them it showed last, so the page leaves as it was. [interactive] is whether the
 * grid itself is the route: its tiles' scope stays visible while the stack leaves for a tab,
 * so the tiles cannot tell that on their own.
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalTransitionApi::class)
@Composable
private fun LibraryStack(
    top: Route,
    interactive: Boolean,
    sharedScope: SharedTransitionScope,
    reduceMotion: Boolean,
    library: @Composable (AnimatedVisibilityScope) -> Unit,
    detail: @Composable (MediaItem, AnimatedVisibilityScope) -> Unit,
) {
    val motionScheme = MaterialTheme.motionScheme
    val page = (top as? Route.Detail)?.item
    val stack = updateTransition(page, label = "library-stack")
    val tileTransition = stack.createChildTransition(label = "library") { shown ->
        if (shown != null) EnterExitState.PostExit else EnterExitState.Visible
    }
    val tileScope = remember(tileTransition) { StackVisibilityScope(tileTransition) }
    // With motion reduced the card does not turn: the two pages simply cross.
    val gridAlpha = tileTransition.animateFloat(
        transitionSpec = { motionScheme.fastEffectsSpec() },
        label = "library-fade",
    ) { state -> if (state != EnterExitState.Visible && reduceMotion) 0f else 1f }
    val focusManager = LocalFocusManager.current
    // A search field focused under the page would keep the keyboard up over it.
    LaunchedEffect(page != null) { if (page != null) focusManager.clearFocus() }
    val covered = page != null
    Box(Modifier.fillMaxSize().animatingFrameRate(stack.isRunning)) {
        Box(
            Modifier
                .fillMaxSize()
                .then(if (covered) Modifier.clearAndSetSemantics {} else Modifier)
                .focusProperties { onEnter = { if (covered) cancelFocusChange() } }
                .focusGroup()
                .then(if (reduceMotion) Modifier.graphicsLayer { alpha = gridAlpha.value } else Modifier)
                // The page is opaque once it has settled; until then the grid shows through it.
                .drawWithContent {
                    val settled = stack.currentState == stack.targetState && stack.targetState != null &&
                        !sharedScope.isTransitionActive
                    if (!settled) drawContent()
                },
        ) {
            library(tileScope)
            // Over the grid and under the page: anything the page does not take stops here,
            // rather than reaching a tile the page is covering.
            if (!interactive) {
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
                )
            }
        }
        stack.AnimatedContent(
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                if (reduceMotion) {
                    fadeIn(motionScheme.fastEffectsSpec()) togetherWith fadeOut(motionScheme.fastEffectsSpec())
                } else {
                    // The card carries the movement and the page fades up around it on its own
                    // tracks, so this does nothing but hold the page for them.
                    EnterTransition.None togetherWith ExitTransition.None
                }
            },
        ) { item ->
            Box(Modifier.fillMaxSize()) {
                if (item != null) detail(item, this@AnimatedContent)
            }
        }
    }
}

/** A visibility scope over a transition the caller owns, for content that is never disposed. */
private class StackVisibilityScope(
    override val transition: Transition<EnterExitState>,
) : AnimatedVisibilityScope

/**
 * The library stacks composed beside the route switch. Each is recorded into its own layer and
 * drawn only by its entry in the switch, so it takes that entry's fades, slides and layering
 * exactly, and outlives it: the current stack stays composed under every other route until it is
 * left with a page up ([entered] then no longer names the current generation). An older stack is
 * composed for as long as its entry is [placed], which is the length of its exit.
 */
private class LibraryHosts {
    /** The generation the stack last was the route in; plain for [ShellHistory]'s reason. */
    var entered = -1
    val placed = mutableStateMapOf<Int, Unit>()
    val layers = mutableStateMapOf<Int, GraphicsLayer>()
}

/** Its own scope, so an entry coming or going recomposes the stacks and not the shell. */
@Composable
private fun LibraryHostLayer(
    hosts: LibraryHosts,
    kept: Int?,
    host: @Composable (stack: Int, placed: Boolean) -> Unit,
) {
    val stacks = (hosts.placed.keys + listOfNotNull(kept)).sorted()
    for (stack in stacks) {
        key(stack) { host(stack, stack in hosts.placed) }
    }
}

/**
 * One stack, laid out where its entry is and never drawn here: [shown] records it for the entry
 * to draw. While another route is up it is under the switch for touches, out of semantics and
 * focus, and its lifecycle is held short of resumed so nothing in it loops unseen.
 */
@Composable
private fun LibraryHost(
    hosts: LibraryHosts,
    stack: Int,
    live: Boolean,
    shown: Boolean,
    semantics: Modifier,
    content: @Composable () -> Unit,
) {
    val layer = rememberGraphicsLayer()
    DisposableEffect(layer) {
        hosts.layers[stack] = layer
        onDispose { if (hosts.layers[stack] === layer) hosts.layers.remove(stack) }
    }
    val focusManager = LocalFocusManager.current
    val focus = remember { HostFocus() }
    // A search field focused here would keep the keyboard up over the route that replaced it.
    LaunchedEffect(live) { if (!live && focus.held) focusManager.clearFocus() }
    val lifecycle = rememberHeldLifecycle(held = !shown)
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(if (live) 0f else -1f)
            .then(if (live) semantics else Modifier.clearAndSetSemantics {})
            .onFocusChanged { focus.held = it.hasFocus }
            .focusProperties { onEnter = { if (!live) cancelFocusChange() } }
            .focusGroup()
            .drawWithContent { if (shown) layer.record { this@drawWithContent.drawContent() } },
    ) {
        CompositionLocalProvider(LocalLifecycleOwner provides lifecycle, content = content)
    }
}

/** Whether focus is inside a stack; plain, read only when the stack stops being the route. */
private class HostFocus(var held: Boolean = false)

@Composable
private fun rememberHeldLifecycle(held: Boolean): LifecycleOwner {
    val parent = LocalLifecycleOwner.current
    val owner = remember(parent) { HeldLifecycleOwner(parent, held) }
    DisposableEffect(owner) {
        parent.lifecycle.addObserver(owner)
        onDispose { parent.lifecycle.removeObserver(owner) }
    }
    SideEffect { owner.held = held }
    return owner
}

/** [parent]'s lifecycle, held at STARTED while [held]. */
private class HeldLifecycleOwner(
    private val parent: LifecycleOwner,
    held: Boolean,
) : LifecycleOwner, LifecycleEventObserver {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    var held: Boolean = held
        set(value) {
            field = value
            sync()
        }

    init {
        sync()
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) = sync()

    private fun sync() {
        val state = parent.lifecycle.currentState
        val target = if (held && state.isAtLeast(Lifecycle.State.RESUMED)) Lifecycle.State.STARTED else state
        // A registry that never left INITIALIZED has no way down to DESTROYED.
        if (target == Lifecycle.State.DESTROYED && registry.currentState == Lifecycle.State.INITIALIZED) return
        registry.currentState = target
    }
}

/** The seven arms of the route table. Every pair resolves to exactly one of them. */
private enum class RouteMotion { FLIP, HERO, CONTAINER, LAUNCH, RETURN, LATERAL, QUIET }

// The launch is the one transition that moves toward the TV rather than sideways: the
// surface being left recedes, the surface arriving overshoots past the viewer.
private const val LaunchNear = 1.06f
private const val LaunchFar = 0.92f

/**
 * The longest the bottom stack waits below the window edge for the card flying back into the
 * grid to say it is nearly down ([com.flick.sender.ui.components.SeatFlight.nearContact]).
 * Only a back flip with no card in it — a page that never registered a seat — runs it out.
 */
private const val HeroLandMs = 560L

// The landing flash: 0 -> peak -> 0 over 450 ms. docs/flick-hyperspace-phone.md has the
// same numbers and has to move with them.
private const val LandingFlashPeak = 0.85f
private const val LandingFlashInMs = 120
private const val LandingFlashOutMs = 330
private val LandingFlashOut = CubicBezierEasing(0.4f, 0f, 1f, 1f)

/**
 * Only two live overlays cross-dissolve. A transition to the empty state follows a
 * sheet's already-completed exit and must dispose the old full-screen input layer in the
 * same frame; a transition from empty has no visible outgoing surface to fade.
 */
internal fun shouldFadeOverlayExit(initialPresent: Boolean, targetPresent: Boolean): Boolean =
    initialPresent && targetPresent

/**
 * Which overlay the shell COMPOSES, which is not the same question as which one the app
 * still wants on screen.
 *
 * [outgoing] is a sheet whose exit has begun. The flag that summoned it is cleared on that
 * first frame — that is what leaves the control free to summon it again — so from then
 * until the sheet reports itself off the window nothing the app wants names it, and only
 * this keeps it composed for the frames it still needs. [active] outranks it, so asking
 * for that same sheet back inside the window simply hosts it again, with no teardown and
 * no second entrance: it returns to its seat from wherever the exit had carried it.
 *
 * [supportAvailable] gates BOTH arms and not the live one alone. Support is the overlay
 * whose content is conditional, and hosted with no catalog it raises no sheet at all —
 * so nothing would ever report it gone, and the empty full-screen layer left standing
 * holds the route out of TalkBack for the rest of the session. The live arm is gated
 * again where it is derived; this is the same invariant stated where the layer is decided.
 */
internal fun hostedOverlay(active: Overlay?, outgoing: Overlay?, supportAvailable: Boolean): Overlay? =
    (active ?: outgoing)?.takeIf { it != Overlay.SUPPORT || supportAvailable }

/**
 * Which arm a route pair takes. CONTAINER is the dock growing into the remote and back,
 * and it outranks everything because the dock's bounds already carry that movement; FLIP is
 * the library tile turning over into the detail page's poster and back; HERO pairs carry a
 * shared frame, so they drop every translation and let the frame itself be the movement; the
 * launch pair is the cast commit; a fault is always presented still. [dockLive] gates
 * CONTAINER because with no cast there is no bar to grow out of.
 */
private fun routeMotion(
    initial: ShellDestination,
    target: ShellDestination,
    dockLive: Boolean,
): RouteMotion = when {
    dockLive && SenderShellPolicy.dockMorph(initial, target) -> RouteMotion.CONTAINER
    initial == ShellDestination.LIBRARY && target == ShellDestination.DETAIL -> RouteMotion.FLIP
    initial == ShellDestination.DETAIL && target == ShellDestination.LIBRARY -> RouteMotion.FLIP
    initial == ShellDestination.CONNECTING && target == ShellDestination.NOW_PLAYING -> RouteMotion.HERO
    initial == ShellDestination.DETAIL && target == ShellDestination.CONNECTING -> RouteMotion.LAUNCH
    target == ShellDestination.FAILURE || initial == ShellDestination.FAILURE -> RouteMotion.QUIET
    // Leaving a committed cast — minimize, stop, cancel — inverts the launch.
    initial == ShellDestination.NOW_PLAYING || initial == ShellDestination.CONNECTING -> RouteMotion.RETURN
    lateralDirection(initial, target) != 0 -> RouteMotion.LATERAL
    else -> RouteMotion.QUIET
}

/**
 * Left-to-right seat in the floating nav; -1 for the routes it does not represent. The
 * remote claims none: it is not a seat, it is the dock's own card filling the window.
 */
private fun navSeat(destination: ShellDestination): Int = when (destination) {
    ShellDestination.LIBRARY -> 0
    ShellDestination.CONNECT -> 1
    ShellDestination.SETTINGS -> 2
    ShellDestination.DETAIL,
    ShellDestination.CONNECTING,
    ShellDestination.NOW_PLAYING,
    ShellDestination.FAILURE,
    -> -1
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
 * `MainActivity` seeds both bars from the palette the appearance preference resolved to,
 * which is dark icons for a light one; over a cinematic backdrop — and over every route
 * once that palette is itself the dark one — they have to invert. Both bars move
 * together: the gesture handle sits on the same backdrop the status bar does. Restored on
 * dispose so the setting never outlives the Compose tree that asked for it.
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
