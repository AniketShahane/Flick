package com.flick.sender.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import com.flick.sender.net.FlickController
import com.flick.sender.net.Route
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.rememberReduceMotion

private enum class Overlay { QUALITY, ADVISORIES, DIAGNOSTICS }

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

    // Connect is a dead-end for back only when it's the launch destination (no pairing
    // yet). When it was opened in-flow from Library (cast icon / flick with no TV),
    // back returns to Library instead of exiting the app.
    BackHandler(
        enabled = activeOverlay == null && route !is Route.Library && (route !is Route.Connect || connectFromLibrary),
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
    }
}
