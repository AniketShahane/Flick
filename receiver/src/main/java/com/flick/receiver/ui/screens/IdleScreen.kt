package com.flick.receiver.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.ui.components.FlickTvButton
import com.flick.receiver.ui.components.LiveDot
import com.flick.receiver.ui.theme.BrandMark
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickDimens
import com.flick.receiver.ui.theme.FlickMotion
import com.flick.receiver.ui.theme.FlickSpace
import com.flick.receiver.ui.theme.FlickType
import com.flick.receiver.ui.theme.LocalReducedMotion
import com.flick.receiver.ui.theme.idleAmbientBackground
import com.flick.receiver.ui.theme.tvOverscanSafeArea
import kotlinx.coroutines.delay
import java.util.Date
import kotlin.math.max

/**
 * One drift cycle. This is the single deliberate ambient loop in the whole system:
 * idle is a standby screensaver, the decoder is released, and nothing else on the
 * TV is moving. At 34 s per half-cycle the wash is below the rate the eye reads as
 * motion — the room appears to breathe, rather than the app appearing to animate.
 */
private const val IDLE_DRIFT_MS = 34_000

/** How far the radial centre and its radius wander, as fractions of the viewport. */
private const val IDLE_DRIFT_CENTRE = 0.06f
private const val IDLE_DRIFT_RADIUS = 0.08f

/**
 * Idle's entrance: four children, each led by a sixth of the run — ~80 ms on the
 * entrance spring, the gap at which two corner rows read as arriving in order
 * rather than together.
 */
private const val IdleStageLead = 0.16f
private const val IdleStageCount = 4

/** The mark arrives from just under full size; the clock rises into place. */
private const val IdleMarkEnterScale = 0.9f
private val IdleClockRise = 10.dp

private fun idleStageProgress(progress: Float, index: Int): Float {
    val span = 1f - IdleStageLead * (IdleStageCount - 1)
    return ((progress - IdleStageLead * index) / span).coerceIn(0f, 1f)
}

/** Entrance for one staged child, read inside the layer block. */
private fun Modifier.idleStage(
    progress: () -> Float,
    index: Int,
    rise: Dp = 0.dp,
    fromScale: Float = 1f,
): Modifier = graphicsLayer {
    val stage = idleStageProgress(progress(), index)
    alpha = stage
    translationY = (1f - stage) * rise.toPx()
    val scale = fromScale + (1f - fromScale) * stage
    scaleX = scale
    scaleY = scale
}

/**
 * The drifting variant of the idle bed. It duplicates the static gradient in
 * `Theme.kt` on purpose: the phase has to be read INSIDE the draw lambda so the
 * drift invalidates the draw phase alone, and Settings keeps the static brush.
 */
private fun Modifier.idleWashDrift(phase: () -> Float): Modifier = this
    .background(FlickColor.Canvas)
    .drawBehind {
        val p = phase()
        drawRect(
            Brush.radialGradient(
                colors = listOf(FlickColor.Primary.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(
                    x = size.width * (0.5f + IDLE_DRIFT_CENTRE * p),
                    y = size.height * (-0.10f + IDLE_DRIFT_CENTRE * p),
                ),
                radius = max(size.width, size.height) * (0.65f + IDLE_DRIFT_RADIUS * p),
            ),
        )
    }

/**
 * T2 · Idle — "ready to cast". Screensaver-grade standby on the ambient blue
 * wash (spec §5.6): the wash drifts, the clock runs, and the live dot marks the
 * paired phone. Focus rests on "Pair another phone".
 */
@Composable
fun IdleScreen(
    pairedLabel: String?,
    onPairAnother: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pairFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { pairFocus.requestFocus() } }

    val reducedMotion = LocalReducedMotion.current
    // The screen's ONE loop. Everything else here is a finite entrance.
    val driftPhase: State<Float>? = if (reducedMotion) {
        null
    } else {
        rememberInfiniteTransition(label = "idleDrift").animateFloat(
            initialValue = -1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(IDLE_DRIFT_MS, easing = FlickMotion.Breathe),
                RepeatMode.Reverse,
            ),
            label = "idleWashPhase",
        )
    }

    val entranceSpec: FiniteAnimationSpec<Float> = FlickMotion.panelSpatial()
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) entrance.snapTo(1f) else entrance.animateTo(1f, entranceSpec)
    }
    val stage = { entrance.value }

    val clock = rememberIdleWallClock()

    Box(
        // The wash is full-bleed; everything drawn on it is not. The inset is on
        // the root rather than on each corner row so there is one of it.
        modifier = modifier
            .fillMaxSize()
            .then(
                if (driftPhase == null) Modifier.idleAmbientBackground()
                else Modifier.idleWashDrift { driftPhase.value },
            )
            .tvOverscanSafeArea(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BrandMark(
                size = 58.dp,
                tint = FlickColor.PrimaryOnDark,
                modifier = Modifier.idleStage(stage, index = 0, fromScale = IdleMarkEnterScale),
            )
            Text(
                text = clock,
                style = FlickType.monoTabular(sizeSp = 44, weight = FontWeight.SemiBold),
                color = FlickColor.OnSurface,
                modifier = Modifier
                    .padding(top = FlickSpace.Lg)
                    .idleStage(stage, index = 1, rise = IdleClockRise),
            )
        }

        Row(
            // Held off the safe-area floor by the same reserve as the buttons
            // opposite, so the two bottom rows still read off one line.
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = FlickDimens.FocusRingReserve)
                .idleStage(stage, index = 2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Static: the dot reports that a phone is paired, which is not an
            // event, and the drifting wash is this screen's one moving thing.
            LiveDot(color = FlickColor.Live, size = 7.dp)
            Text(
                text = if (pairedLabel != null) {
                    stringResource(R.string.idle_paired_with, pairedLabel)
                } else {
                    stringResource(R.string.idle_ready)
                },
                style = FlickType.body(sizeSp = 16),
                color = FlickColor.OnSurfaceDim,
            )
        }

        Row(
            // A focused pill's ring is painted outside its bounds, and these two
            // sit in the corner of the safe area — the reserve is the ring's room.
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = FlickDimens.FocusRingReserve,
                    bottom = FlickDimens.FocusRingReserve,
                )
                .idleStage(stage, index = 3),
            horizontalArrangement = Arrangement.spacedBy(FlickSpace.Md),
        ) {
            FlickTvButton(
                onClick = onOpenSettings,
                contentPadding = FlickDimens.ControlPadding,
            ) {
                Text(
                    text = stringResource(R.string.idle_settings),
                    style = FlickType.body(sizeSp = 16),
                    color = FlickColor.OnSurfaceDim,
                )
            }
            FlickTvButton(
                onClick = onPairAnother,
                focusRequester = pairFocus,
                contentPadding = FlickDimens.ControlPadding,
            ) {
                Text(
                    text = stringResource(R.string.idle_pair_another),
                    style = FlickType.body(sizeSp = 16),
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
