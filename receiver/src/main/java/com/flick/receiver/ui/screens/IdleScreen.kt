package com.flick.receiver.ui.screens

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
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.ui.components.FlickTvButton
import com.flick.receiver.ui.theme.BrandMark
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickType
import com.flick.receiver.ui.theme.OverscanSafe
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * T2 · Idle — "ready to cast". Screensaver-grade standby: the mark breathes, the
 * ambient gradient sits low. The pairing hint whispers; focus rests on the pill.
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

    val breathe = rememberInfiniteTransition(label = "idleBreathe")
    val markAlpha by breathe.animateFloat(
        initialValue = 0.35f, targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(2600), RepeatMode.Reverse),
        label = "markAlpha",
    )

    var clock by remember { mutableStateOf(nowHhMm()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = nowHhMm()
            delay(10_000L)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(FlickColor.Canvas)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(FlickColor.SurfaceRaised.copy(alpha = 0.6f), FlickColor.Canvas),
                        center = center.copy(x = size.width * 0.7f, y = size.height * 0.85f),
                        radius = size.maxDimension * 0.7f,
                    ),
                )
            },
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BrandMark(
                size = 64.dp,
                modifier = Modifier
                    .size(64.dp)
                    .alpha(markAlpha),
            )
            Text(
                text = clock,
                style = FlickType.monoTabular(sizeSp = 56, weight = FontWeight.Bold),
                color = FlickColor.OnSurface.copy(alpha = 0.9f),
                modifier = Modifier.padding(top = 20.dp),
            )
            Text(
                text = stringResource(R.string.idle_ready),
                fontSize = 24.sp,
                color = FlickColor.OnSurfaceDim,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(OverscanSafe),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .drawBehind { drawCircle(FlickColor.Live) },
            )
            Text(
                text = if (pairedLabel != null) {
                    stringResource(R.string.idle_paired_with, pairedLabel)
                } else {
                    stringResource(R.string.idle_ready)
                },
                fontSize = 24.sp,
                color = FlickColor.OnSurfaceFaint,
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(OverscanSafe),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            FlickTvButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.idle_settings), fontSize = 24.sp, color = FlickColor.OnSurfaceDim)
            }
            FlickTvButton(onClick = onPairAnother, focusRequester = pairFocus) {
                Text(stringResource(R.string.idle_pair_another), fontSize = 24.sp, color = FlickColor.OnSurface)
            }
        }
    }
}

private fun nowHhMm(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
