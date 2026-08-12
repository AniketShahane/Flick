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
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.WindowCompat
import com.flick.sender.media.MediaPermissionState
import com.flick.sender.net.FlickController
import com.flick.sender.net.Route
import com.flick.sender.net.BackDisposition
import com.flick.sender.net.SenderNavigationPolicy
import com.flick.sender.support.SupportCatalog
import com.flick.sender.ui.components.FlickBottomNav
import com.flick.sender.ui.components.LocalNavMetrics
import com.flick.sender.ui.components.LocalQualityRevealOrigin
import com.flick.sender.ui.components.NavMetrics
import com.flick.sender.ui.components.NavShellMargin
import com.flick.sender.ui.components.NowPlayingDock
import com.flick.sender.ui.components.RevealOrigin
import com.flick.sender.ui.components.RevealTarget
import com.flick.sender.ui.components.originRevealMask
import com.flick.sender.ui.components.remoteCardBounds
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.Motion
import com.flick.sender.ui.theme.ThemePreference
import com.flick.sender.ui.theme.rememberFlickTouchHaptics
import com.flick.sender.ui.theme.rememberReduceMotion
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

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
    // Same reason, one level up: the routes below cross-dissolve, so the library is DISPOSED
    // while a detail sheet is up. Held here it comes back seated where it was left, which is
    // also what puts the tile a returning poster flies into back on the screen — see
    // [SenderShellPolicy.heroReturn], whose whole premise is that the seat is there.
    val libraryUi = rememberLibraryUiState()

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
    // The pair is also all there is to decide on. The grid the frame is flying into has
    // not been measured on this frame — it has not even been composed — so nothing can be
    // asked about the seat, and [libraryUi] is what makes the pair worth trusting: the
    // grid comes back where it was left, so the tile that was tapped is composed again.
    // What survives is a file that left the library while the sheet was up, which spends
    // the hold with nothing in the air; it is bounded by [HeroLandMs] and it is the only
    // arm of this that a wrong answer costs a wait rather than a bar under a poster.
    val heroReturn = remember(route) {
        SenderShellPolicy.heroReturn(history.destination, destination)
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
            delay(HeroLandMs)
        }
        flown = flight
    }
    // Released once the geometry is out of the air, because after that a cast that ends
    // has to be able to take the bar out with an exit of its own.
    val morphing = flightMorph && flown !== flight
    // The frame is drawn in the shell's overlay, which the transition layout paints after
    // every child of its own — so while it is up there, nothing the chrome does underneath
    // can be seen, and where the frame lands the chrome is what it lands on. It gets the
    // window to itself until the flight ENDS rather than until it looks landed: the end is
    // when the overlay copy is handed back.
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
    ) {
        SharedTransitionLayout {
            val sharedScope = this
            Box(Modifier.fillMaxSize().background(colors.surface)) {
                AnimatedContent(
                    modifier = Modifier
                        .fillMaxSize()
                        // One source for the one small navigation effect. Keeping this at
                        // the route boundary lets both themes show the real content behind
                        // the pill without adding blur nodes to the scrolling children.
                        .hazeSource(navHazeState),
                    targetState = route,
                    transitionSpec = {
                        if (reduceMotion) {
                            EnterTransition.None togetherWith ExitTransition.None
                        } else {
                            val from = SenderShellPolicy.destinationOf(initialState)
                            val to = SenderShellPolicy.destinationOf(targetState)
                            when (routeMotion(from, to, flightDockLive)) {
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
                    },
                    label = "route",
                ) { r ->
                    Box(
                        Modifier
                            .fillMaxSize()
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
                            Route.Library -> LibraryScreen(
                                controller = controller,
                                supportAvailable = supportCatalog != null,
                                onOpenSupport = { controller.toggleSupportSheet(true) },
                                onRequestVideoPermission = onRequestVideoPermission,
                                mediaPermission = mediaPermission,
                                onOpenAppSettings = onOpenAppSettings,
                                uiState = libraryUi,
                                sharedScope = sharedScope,
                                animatedScope = this@AnimatedContent,
                            )
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

/** The six arms of the route table. Every pair resolves to exactly one of them. */
private enum class RouteMotion { HERO, CONTAINER, LAUNCH, RETURN, LATERAL, QUIET }

// The launch is the one transition that moves toward the TV rather than sideways: the
// surface being left recedes, the surface arriving overshoots past the viewer.
private const val LaunchNear = 1.06f
private const val LaunchFar = 0.92f

/**
 * How long the bottom stack waits below the window edge while a frame flies back into the
 * grid. It is the FLIGHT's length and not the landing's: the copy in the overlay is handed
 * back to the grid cell only when its bounds animation terminates, so a stack released
 * when the frame merely looks home would be released under it. On the frame's own spring —
 * critically damped, `StiffnessMediumLow`, a pixel per edge — a full-window return
 * terminates at a little over half a second, and this may not be shorter than that without
 * putting a bar back under a poster. Longer only reads as a stall.
 */
private const val HeroLandMs = 560L

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
 * and it outranks everything because the dock's bounds already carry that movement;
 * HERO pairs carry a shared frame, so they drop every translation and let the frame
 * itself be the movement; the launch pair is the cast commit; a fault is always presented
 * still. [dockLive] gates CONTAINER because with no cast there is no bar to grow out of.
 */
private fun routeMotion(
    initial: ShellDestination,
    target: ShellDestination,
    dockLive: Boolean,
): RouteMotion = when {
    dockLive && SenderShellPolicy.dockMorph(initial, target) -> RouteMotion.CONTAINER
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
