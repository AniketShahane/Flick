package com.flick.receiver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.ui.components.FlickTvButton
import com.flick.receiver.ui.components.FlickWordmark
import com.flick.receiver.ui.components.QrCode
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickIcons
import com.flick.receiver.ui.theme.FlickType
import com.flick.receiver.ui.theme.OverscanSafe

/**
 * T1 · First-run pair. QR (ZXing → Canvas) + short code, zero keyboard hunting.
 * Focus rests on "Show code bigger" — the one thing a distant viewer might need.
 * The privacy promise is stated in 10-ft type.
 */
@Composable
fun PairScreen(
    tvName: String,
    code: String,
    /** Null while no real binding exists; the QR is then simply not drawn. */
    qrPayload: String?,
    host: String,
    port: Int,
    onRename: () -> Unit,
    networkReady: Boolean,
    bindUptimeSec: Long = 0L,
    rebindCount: Int = 0,
    lastTeardown: String? = null,
    modifier: Modifier = Modifier,
) {
    var bigCode by remember { mutableStateOf(false) }
    val showBiggerFocus = remember { FocusRequester() }
    val doneFocus = remember { FocusRequester() }
    val spacedCode = code.toCharArray().joinToString("  ")

    LaunchedEffect(bigCode) {
        runCatching { (if (bigCode) doneFocus else showBiggerFocus).requestFocus() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF141020), FlickColor.Canvas),
                ),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(OverscanSafe),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(48.dp),
        ) {
            Column(
                modifier = Modifier.weight(1.25f),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                FlickWordmark()
                Text(
                    text = stringResource(R.string.pair_title),
                    fontFamily = FlickType.Display,
                    fontWeight = FontWeight.Bold,
                    fontSize = 44.sp,
                    lineHeight = 50.sp,
                    letterSpacing = (-0.01).em,
                    color = FlickColor.OnSurface,
                )
                Text(
                    text = stringResource(R.string.pair_instructions),
                    fontSize = 24.sp,
                    lineHeight = 33.sp,
                    color = FlickColor.OnSurfaceDim,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = FlickIcons.Private,
                        contentDescription = null,
                        tint = FlickColor.Live,
                        modifier = Modifier.width(24.dp),
                    )
                    Text(
                        text = stringResource(R.string.pair_this_tv, tvName),
                        fontSize = 24.sp,
                        color = FlickColor.OnSurfaceFaint,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Gated off while the enlarged-code overlay is up so they are not
                    // focusable behind the scrim (clickable(enabled=false) drops focus).
                    FlickTvButton(onClick = { bigCode = true }, enabled = !bigCode, focusRequester = showBiggerFocus) {
                        Text(stringResource(R.string.pair_show_bigger), fontSize = 24.sp, color = FlickColor.OnSurface)
                    }
                    FlickTvButton(onClick = onRename, enabled = !bigCode) {
                        Text(stringResource(R.string.pair_rename), fontSize = 24.sp, color = FlickColor.OnSurfaceDim)
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (networkReady) {
                    if (qrPayload != null) QrCode(payload = qrPayload, size = 220.dp)
                    Text(
                        text = spacedCode,
                        style = FlickType.monoTabular(sizeSp = 44, weight = FontWeight.Bold),
                        color = FlickColor.Link,
                        letterSpacing = 0.2.em,
                    )
                    Text(
                        text = if (code == "—") stringResource(R.string.pair_locked) else stringResource(R.string.pair_code_hint),
                        fontSize = 24.sp,
                        color = FlickColor.OnSurfaceFaint,
                    )
                    if (host.isNotBlank() && port > 0) {
                        // mDNS-blocked fallback. With a durable control port this
                        // number is finally stable enough to be worth typing.
                        Text(
                            text = stringResource(R.string.pair_manual_hint, host, port),
                            style = FlickType.monoTabular(sizeSp = 24, weight = FontWeight.Medium),
                            color = FlickColor.OnSurfaceFaint,
                        )
                        // Bind health: a stale port reads differently from a wrong code.
                        Text(
                            text = stringResource(R.string.pair_bind_health, bindUptimeSec, rebindCount) +
                                (lastTeardown?.let { " · " + stringResource(R.string.pair_bind_last_teardown, it) } ?: ""),
                            style = FlickType.monoTabular(sizeSp = 20, weight = FontWeight.Normal),
                            color = FlickColor.OnSurfaceFaint,
                        )
                    }
                } else {
                    // No LAN address yet (Wi-Fi not associated / DHCP lease changing),
                    // so the QR host+port and the code aren't reachable — say so
                    // instead of showing an unusable code and a dead QR.
                    Text(
                        text = stringResource(R.string.pair_waiting_network_title),
                        fontFamily = FlickType.Display,
                        fontWeight = FontWeight.Bold,
                        fontSize = 32.sp,
                        color = FlickColor.Link,
                    )
                    Text(
                        text = stringResource(R.string.pair_waiting_network_detail),
                        fontSize = 24.sp,
                        lineHeight = 33.sp,
                        color = FlickColor.OnSurfaceDim,
                    )
                }
            }
        }

        if (bigCode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(FlickColor.Canvas.copy(alpha = 0.94f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    Text(
                        text = spacedCode,
                        style = FlickType.monoTabular(sizeSp = 120, weight = FontWeight.Bold),
                        color = FlickColor.Link,
                        letterSpacing = 0.2.em,
                    )
                    FlickTvButton(onClick = { bigCode = false }, focusRequester = doneFocus) {
                        Text(stringResource(R.string.pair_hide_bigger), fontSize = 24.sp, color = FlickColor.OnSurface)
                    }
                }
            }
        }
    }
}
