package com.flick.receiver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.session.ErrorKind
import com.flick.receiver.ui.components.FlickTvButton
import com.flick.receiver.ui.components.GlassPanel
import com.flick.receiver.ui.components.GlassPanelTone
import com.flick.receiver.ui.components.LiveDot
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickMotion
import com.flick.receiver.ui.theme.FlickShape
import com.flick.receiver.ui.theme.FlickType
import com.flick.receiver.ui.theme.rememberTvSafeAreaPadding
import kotlin.math.max

/**
 * T9 · Errors, calm and specific. Amber [ErrorKind.NotServing] = "reachable, not
 * serving" (the stream ended); crimson [ErrorKind.Unreachable] = the phone left
 * the network. Either way the held position is promised and D-pad recovery is one
 * press away.
 *
 * The two diagnoses stay visually distinct: the accent tints the ambient wash,
 * the phone glyph's status dot and the primary action. While we are still waiting
 * (unreachable) the dot breathes; a stream that simply ended holds still.
 */
@Composable
fun ErrorScreen(
    kind: ErrorKind,
    deviceLabel: String?,
    onPrimary: (() -> Unit)?,
    onSecondary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val safeArea = rememberTvSafeAreaPadding()
    val primaryFocus = remember { FocusRequester() }
    val secondaryFocus = remember { FocusRequester() }
    LaunchedEffect(kind, onPrimary != null) {
        runCatching {
            (if (onPrimary != null) primaryFocus else secondaryFocus).requestFocus()
        }
    }

    val unreachable = kind == ErrorKind.Unreachable
    val accent = if (unreachable) FlickColor.Trouble else FlickColor.Caution
    val device = deviceLabel ?: stringResource(R.string.device_fallback)
    val title = stringResource(
        if (unreachable) R.string.error_unreachable_title else R.string.error_not_serving_title,
    )
    val detail = stringResource(
        if (unreachable) R.string.error_unreachable_detail else R.string.error_not_serving_detail,
        device,
    )
    val primaryLabel = stringResource(
        if (unreachable) R.string.error_keep_waiting else R.string.error_try_again,
    )
    val secondaryLabel = stringResource(
        if (unreachable) R.string.error_end_session else R.string.error_back_to_standby,
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(FlickColor.Canvas)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.20f), Color.Transparent),
                        center = Offset(size.width * 0.5f, size.height * 0.14f),
                        radius = max(size.width, size.height) * 0.68f,
                    ),
                )
            }
            .padding(safeArea),
        contentAlignment = Alignment.Center,
    ) {
        GlassPanel(
            modifier = Modifier.fillMaxWidth(0.7f),
            shape = FlickShape.Hero,
            tone = GlassPanelTone.Panel,
            contentPadding = PaddingValues(horizontal = 34.dp, vertical = 30.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            riseDistance = FlickMotion.TvRiseCard,
        ) {
            PhoneGlyph(accent = accent, waiting = unreachable)
            Text(
                text = title,
                style = FlickType.display(sizeSp = 34),
                color = FlickColor.OnSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = detail,
                style = FlickType.body(sizeSp = 24),
                color = FlickColor.OnSurfaceDim,
                textAlign = TextAlign.Center,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onPrimary != null) {
                    FlickTvButton(
                        onClick = onPrimary,
                        focusRequester = primaryFocus,
                        containerColor = FlickColor.Spark,
                        borderColor = Color.Transparent,
                        ringColor = FlickColor.FocusRingOnSpark,
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 11.dp),
                    ) {
                        Text(
                            text = primaryLabel,
                            style = FlickType.body(sizeSp = 24, weight = FontWeight.Bold),
                            color = FlickColor.OnSpark,
                        )
                    }
                }
                FlickTvButton(
                    onClick = onSecondary,
                    focusRequester = secondaryFocus,
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 11.dp),
                ) {
                    Text(
                        text = secondaryLabel,
                        style = FlickType.body(sizeSp = 24),
                        color = FlickColor.OnSurfaceDim,
                    )
                }
            }
        }
    }
}

/** The phone that went quiet: a dim handset with an accent status light. */
@Composable
private fun PhoneGlyph(accent: Color, waiting: Boolean) {
    Box(contentAlignment = Alignment.TopEnd) {
        Box(
            modifier = Modifier
                .size(width = 46.dp, height = 70.dp)
                .background(FlickColor.ControlFill, FlickShape.Md)
                .border(2.dp, FlickColor.Outline, FlickShape.Md),
        )
        Box(
            modifier = Modifier
                .offset(x = 7.dp, y = (-7).dp)
                .size(22.dp)
                .drawBehind { drawCircle(FlickColor.CanvasPair) },
            contentAlignment = Alignment.Center,
        ) {
            LiveDot(color = accent, size = 13.dp, pulsing = waiting)
        }
    }
}
