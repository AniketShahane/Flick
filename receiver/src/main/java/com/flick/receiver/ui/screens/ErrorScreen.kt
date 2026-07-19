package com.flick.receiver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.session.ErrorKind
import com.flick.receiver.ui.components.FlickTvButton
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickType

/**
 * T9 · Errors, calm and specific. Amber [ErrorKind.NotServing] = "reachable, not
 * serving" (the stream ended); crimson [ErrorKind.Unreachable] = the phone left
 * the network. Either way the held position is promised and D-pad recovery is one
 * press away.
 */
@Composable
fun ErrorScreen(
    kind: ErrorKind,
    deviceLabel: String?,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primaryFocus = remember { FocusRequester() }
    LaunchedEffect(kind) { runCatching { primaryFocus.requestFocus() } }

    val accent = if (kind == ErrorKind.Unreachable) FlickColor.Trouble else FlickColor.Caution
    val device = deviceLabel ?: stringResource(R.string.device_fallback)
    val title = stringResource(
        if (kind == ErrorKind.Unreachable) R.string.error_unreachable_title
        else R.string.error_not_serving_title,
    )
    val detail = stringResource(
        if (kind == ErrorKind.Unreachable) R.string.error_unreachable_detail
        else R.string.error_not_serving_detail,
        device,
    )
    val primaryLabel = stringResource(
        if (kind == ErrorKind.Unreachable) R.string.error_keep_waiting
        else R.string.error_try_again,
    )
    val secondaryLabel = stringResource(
        if (kind == ErrorKind.Unreachable) R.string.error_end_session
        else R.string.error_back_to_standby,
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    listOf(FlickColor.SurfaceRaisedAlt, FlickColor.Canvas),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.6f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PhoneGlyph(accent = accent)
            Text(
                text = title,
                fontFamily = FlickType.Display,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                fontSize = 36.sp,
                color = FlickColor.OnSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = detail,
                fontSize = 24.sp,
                lineHeight = 33.sp,
                color = FlickColor.OnSurfaceDim,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                FlickTvButton(onClick = onPrimary, focusRequester = primaryFocus) {
                    Text(primaryLabel, fontSize = 24.sp, color = FlickColor.OnSurface)
                }
                FlickTvButton(onClick = onSecondary) {
                    Text(secondaryLabel, fontSize = 24.sp, color = FlickColor.OnSurfaceDim)
                }
            }
        }
    }
}

@Composable
private fun PhoneGlyph(accent: androidx.compose.ui.graphics.Color) {
    Box(contentAlignment = Alignment.TopEnd) {
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 68.dp)
                .border(2.dp, FlickColor.OnSurface.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
        )
        Box(
            modifier = Modifier
                .size(18.dp)
                .padding(0.dp)
                .drawBehind {
                    drawCircle(FlickColor.Canvas)
                    drawCircle(accent, radius = size.minDimension / 2f * 0.72f)
                },
        )
    }
}
