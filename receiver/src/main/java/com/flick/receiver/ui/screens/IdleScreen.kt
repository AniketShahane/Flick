package com.flick.receiver.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.ui.components.FlickTvButton
import com.flick.receiver.ui.components.LiveDot
import com.flick.receiver.ui.theme.BrandMark
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickMotion
import com.flick.receiver.ui.theme.FlickType
import com.flick.receiver.ui.theme.idleAmbientBackground
import com.flick.receiver.ui.theme.rememberReducedMotion
import com.flick.receiver.ui.theme.rememberTvSafeAreaPadding
import kotlinx.coroutines.delay
import java.util.Date

/**
 * T2 · Idle — "ready to cast". Screensaver-grade standby on the ambient blue
 * wash (spec §5.6): the mark breathes, the clock runs, and the live dot says the
 * TV is still listening. Focus rests on "Pair another phone".
 */
@Composable
fun IdleScreen(
    pairedLabel: String?,
    onPairAnother: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val safeArea = rememberTvSafeAreaPadding()
    val pairFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { pairFocus.requestFocus() } }

    val reducedMotion = rememberReducedMotion()
    val markAlpha = if (reducedMotion) {
        0.85f
    } else {
        val breathe = rememberInfiniteTransition(label = "idleBreathe")
        val alpha by breathe.animateFloat(
            initialValue = 0.42f,
            targetValue = 0.92f,
            animationSpec = infiniteRepeatable(
                tween(2600, easing = FlickMotion.Breathe),
                RepeatMode.Reverse,
            ),
            label = "markAlpha",
        )
        alpha
    }

    val clock = rememberIdleWallClock()

    Box(
        modifier = modifier
            .fillMaxSize()
            .idleAmbientBackground(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BrandMark(
                size = 72.dp,
                tint = FlickColor.PrimaryOnDark,
                modifier = Modifier.alpha(markAlpha),
            )
            Text(
                text = clock,
                style = FlickType.monoTabular(sizeSp = 56, weight = FontWeight.SemiBold),
                color = FlickColor.OnSurface,
                modifier = Modifier.padding(top = 22.dp),
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(safeArea),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LiveDot(color = FlickColor.Live, size = 7.dp, pulsing = true)
            Text(
                text = if (pairedLabel != null) {
                    stringResource(R.string.idle_paired_with, pairedLabel)
                } else {
                    stringResource(R.string.idle_ready)
                },
                style = FlickType.body(sizeSp = 24, lineHeightRatio = 1.1f),
                color = FlickColor.OnSurfaceDim,
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(safeArea),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            FlickTvButton(
                onClick = onOpenSettings,
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp),
            ) {
                Text(
                    text = stringResource(R.string.idle_settings),
                    style = FlickType.body(sizeSp = 24, lineHeightRatio = 1.1f),
                    color = FlickColor.OnSurfaceDim,
                )
            }
            FlickTvButton(
                onClick = onPairAnother,
                focusRequester = pairFocus,
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp),
            ) {
                Text(
                    text = stringResource(R.string.idle_pair_another),
                    style = FlickType.body(sizeSp = 24, lineHeightRatio = 1.1f),
                    color = FlickColor.OnSurface,
                )
            }
        }
    }
}

/**
 * The real device time in the TV's own 12-/24-hour setting, re-read on each minute
 * boundary. `DateFormat.getTimeFormat` is the only source that honours the
 * platform toggle — a hardcoded pattern would disagree with the playback chrome
 * clock on a 12-hour TV.
 */
@Composable
private fun rememberIdleWallClock(): String {
    val context = LocalContext.current
    val formatter = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            nowMs = System.currentTimeMillis()
        }
    }
    return remember(formatter, nowMs) { formatter.format(Date(nowMs)) }
}
