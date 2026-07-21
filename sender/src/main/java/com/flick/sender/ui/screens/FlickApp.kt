package com.flick.sender.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.flick.sender.net.FlickController
import com.flick.sender.net.Route
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.Motion

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
    val route by controller.route.collectAsState()
    val connectFromLibrary by controller.connectFromLibrary.collectAsState()

    // Connect is a dead-end for back only when it's the launch destination (no pairing
    // yet). When it was opened in-flow from Library (cast icon / flick with no TV),
    // back returns to Library instead of exiting the app.
    BackHandler(
        enabled = route !is Route.Library && (route !is Route.Connect || connectFromLibrary),
    ) {
        controller.back()
    }

    Box(Modifier.fillMaxSize().background(colors.surface)) {
        Crossfade(
            targetState = route,
            animationSpec = tween(Motion.CrossDissolveMs, easing = Motion.CrossDissolve),
            label = "route",
        ) { r ->
            when (r) {
                Route.Connect -> ConnectScreen(controller)
                Route.Library -> LibraryScreen(controller, onRequestVideoPermission)
                is Route.Detail -> DetailScreen(controller, r.item)
                Route.Connecting -> ConnectingScreen(controller)
                Route.NowPlaying -> NowPlayingScreen(controller)
                is Route.Failure -> ErrorScreen(controller, r.kind, r.failure, onOpenWifiSettings)
            }
        }

        val showQuality by controller.showQualitySheet.collectAsState()
        if (showQuality) {
            QualitySheet(controller = controller, onDismiss = { controller.toggleQualitySheet(false) })
        }

        val showAdvisories by controller.showAdvisories.collectAsState()
        if (showAdvisories) {
            AdvisoriesScreen(
                batteryExempt = batteryExempt,
                onOpenWifiSettings = onOpenWifiSettings,
                onRequestBatteryExemption = onRequestBatteryExemption,
                onDismiss = { controller.toggleAdvisories(false) },
            )
        }
    }
}
