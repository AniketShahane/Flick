package com.flick.receiver.ui.screens

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.ui.components.FlickTvButton
import com.flick.receiver.ui.components.FlickWordmark
import com.flick.receiver.ui.components.GlassPanel
import com.flick.receiver.ui.components.GlassPanelTone
import com.flick.receiver.ui.components.LiveDot
import com.flick.receiver.ui.components.QrCode
import com.flick.receiver.ui.theme.BrandMark
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickIcons
import com.flick.receiver.ui.theme.FlickShape
import com.flick.receiver.ui.theme.FlickType
import com.flick.receiver.ui.theme.pairAmbientBackground
import com.flick.receiver.ui.theme.rememberTvSafeAreaPadding
import kotlinx.coroutines.delay
import java.util.Locale

/** Spec §5.1: the QR column is a fixed 310 dp; the content column takes the rest. */
private val QrColumnWidth = 310.dp

/** Spec §5.1 column gap. */
private val PairColumnGap = 40.dp

/**
 * Reading measure for the instruction copy. Spec §5.1 item 3 said 410 dp, which
 * wrapped the copy to three lines and pushed the whole column past the vertical
 * budget below — the action row is the last child of an unscrolled Column, so the
 * overflow was spent crushing its labels to nothing. 500 dp holds the copy to two
 * lines and still stops short of the QR column.
 */
private val PairBodyMaxWidth = 500.dp

/**
 * The content column is NOT scrollable — a 10-foot pair screen must read in one
 * glance — so everything in it has to fit 486 dp (1080p minus the 5 % overscan
 * inset) in EVERY state: code-visible, locked, and waiting-for-network. The locked
 * message is the tallest variant. Anything added here has to be measured against
 * that budget on a real panel, not just in a preview.
 */
private val PairColumnItemGap = 8.dp

/**
 * T1 · First-run pair (receiver-expressive-spec.md §5.1). Two columns inside the
 * safe area: the content column carries the lockup, the display headline, the
 * instruction copy, the manual-entry card and the listening status; the QR column
 * carries the white code card and the `flick://` line.
 *
 * Every value on screen is real — the code, host and port come from the live
 * binding, and the rotation countdown renders only when the caller supplies
 * [codeExpiresAtElapsedMs] from `PairingSurface.Open`. Nothing here is invented.
 */
@Composable
fun PairScreen(
    tvName: String,
    code: String,
    /** Null while no real binding exists; the QR column is then simply not drawn. */
    qrPayload: String?,
    host: String,
    port: Int,
    onRename: () -> Unit,
    networkReady: Boolean,
    bindUptimeSec: Long = 0L,
    rebindCount: Int = 0,
    lastTeardown: String? = null,
    /**
     * `PairingSurface.Open.expiresAtElapsedMs` — the real rotation deadline on the
     * `SystemClock.elapsedRealtime` timebase. Null means "no TTL is known", and
     * the card then states only that one sender pairs at a time.
     */
    codeExpiresAtElapsedMs: Long? = null,
    modifier: Modifier = Modifier,
) {
    val safeArea = rememberTvSafeAreaPadding()
    var bigCode by remember { mutableStateOf(false) }
    val showBiggerFocus = remember { FocusRequester() }
    val doneFocus = remember { FocusRequester() }
    val spacedCode = code.toCharArray().joinToString("  ")
    val locked = code == "—"

    LaunchedEffect(bigCode) {
        runCatching { (if (bigCode) doneFocus else showBiggerFocus).requestFocus() }
    }
    BackHandler(enabled = bigCode) { bigCode = false }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pairAmbientBackground(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(safeArea),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PairColumnGap),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(PairColumnItemGap),
            ) {
                FlickWordmark(
                    eyebrow = stringResource(
                        R.string.receiver_eyebrow,
                        tvName.uppercase(Locale.getDefault()),
                    ),
                )
                Text(
                    text = stringResource(R.string.pair_title),
                    style = FlickType.display(sizeSp = 52, trackingEm = -0.05f, lineHeightRatio = 0.88f),
                    color = Color.White,
                )
                Text(
                    text = highlightedInstructions(),
                    style = FlickType.body(sizeSp = 24, lineHeightRatio = 1.18f),
                    color = FlickColor.OnSurfaceDim,
                    modifier = Modifier.widthIn(max = PairBodyMaxWidth),
                )

                if (networkReady) {
                    ManualEntryCard(
                        host = host,
                        port = port,
                        spacedCode = spacedCode,
                        locked = locked,
                        codeExpiresAtElapsedMs = codeExpiresAtElapsedMs,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        LiveDot(color = FlickColor.Live, size = 7.dp, pulsing = true)
                        Text(
                            text = stringResource(R.string.pair_listening),
                            style = FlickType.body(sizeSp = 24, weight = FontWeight.Bold, lineHeightRatio = 1.1f),
                            color = FlickColor.OnSurfaceSoft,
                        )
                    }
                } else {
                    WaitingForNetworkCard()
                }

                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Gated off while the enlarged-code overlay is up so they are not
                    // focusable behind the scrim (clickable(enabled=false) drops focus).
                    FlickTvButton(
                        onClick = { bigCode = true },
                        enabled = !bigCode,
                        focusRequester = showBiggerFocus,
                        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.pair_show_bigger),
                            style = FlickType.body(sizeSp = 24, lineHeightRatio = 1.1f),
                            color = FlickColor.OnSurface,
                        )
                    }
                    FlickTvButton(
                        onClick = onRename,
                        enabled = !bigCode,
                        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.pair_rename),
                            style = FlickType.body(sizeSp = 24, lineHeightRatio = 1.1f),
                            color = FlickColor.OnSurfaceDim,
                        )
                    }
                }
            }

            if (networkReady && qrPayload != null) {
                QrColumn(
                    payload = qrPayload,
                    host = host,
                    port = port,
                    bindUptimeSec = bindUptimeSec,
                    rebindCount = rebindCount,
                    lastTeardown = lastTeardown,
                )
            }
        }

        if (bigCode) {
            EnlargedCode(
                spacedCode = spacedCode,
                locked = locked,
                doneFocus = doneFocus,
                onDone = { bigCode = false },
            )
        }
    }
}

/**
 * Spec §5.1 item 4. `Surface` fill, 20 dp radius, `GlassBorder` hairline — it does
 * not sit over moving video, so it is the opaque [GlassPanelTone.Solid] cut.
 */
@Composable
private fun ManualEntryCard(
    host: String,
    port: Int,
    spacedCode: String,
    locked: Boolean,
    codeExpiresAtElapsedMs: Long?,
) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = FlickShape.Xl,
        tone = GlassPanelTone.Solid,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(
            text = stringResource(R.string.pair_manual_eyebrow),
            style = FlickType.monoEyebrow(trackingEm = 0.2f),
            color = FlickColor.OnSurfaceFaint,
        )
        // Sized so a 15-character host, the port and the spaced code all clear the
        // 474 dp the content column leaves beside the 310 dp QR card.
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ManualField(
                label = stringResource(R.string.pair_manual_ip_label),
                value = host,
                valueSizeSp = 24,
                valueColor = FlickColor.OnSurface,
            )
            FieldDivider()
            ManualField(
                label = stringResource(R.string.pair_manual_port_label),
                value = port.toString(),
                valueSizeSp = 24,
                valueColor = FlickColor.OnSurface,
            )
            FieldDivider()
            ManualField(
                label = stringResource(R.string.pair_manual_code_label),
                labelColor = FlickColor.SparkBright,
                value = spacedCode,
                valueSizeSp = 26,
                valueColor = FlickColor.Spark,
            )
        }
        if (locked) {
            Text(
                text = stringResource(R.string.pair_locked),
                style = FlickType.body(sizeSp = 24, lineHeightRatio = 1.15f),
                color = FlickColor.Caution,
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Icon(
                    imageVector = FlickIcons.Timer,
                    contentDescription = null,
                    tint = FlickColor.OnSurfaceFaint,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = rotationLine(codeExpiresAtElapsedMs),
                    style = FlickType.monoEyebrow(trackingEm = 0.14f),
                    color = FlickColor.OnSurfaceFaint,
                    // Uppercased mono at this tracking is wide: a second line here
                    // orphans a word AND overflows the column budget above.
                    maxLines = 1,
                )
            }
        }
    }
}

/** Label over value — the §5.5 stat-cell treatment, reused for the manual fields. */
@Composable
private fun ManualField(
    label: String,
    value: String,
    valueSizeSp: Int,
    valueColor: Color,
    labelColor: Color = FlickColor.OnSurfaceMuted,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            text = label.uppercase(Locale.getDefault()),
            style = FlickType.monoEyebrow(trackingEm = 0.14f),
            color = labelColor,
        )
        Text(
            text = value,
            style = FlickType.monoTabular(sizeSp = valueSizeSp, weight = FontWeight.SemiBold),
            color = valueColor,
            maxLines = 1,
        )
    }
}

@Composable
private fun FieldDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(FlickColor.OutlineHairline),
    )
}

/**
 * No LAN address yet (Wi-Fi not associated / DHCP lease changing), so the QR host
 * and port are not reachable — say so instead of showing a dead endpoint.
 */
@Composable
private fun WaitingForNetworkCard() {
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = FlickShape.Xl,
        tone = GlassPanelTone.Solid,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = FlickIcons.Wifi,
                contentDescription = null,
                tint = FlickColor.Caution,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = stringResource(R.string.pair_waiting_network_title),
                style = FlickType.display(sizeSp = 27, trackingEm = -0.04f),
                color = FlickColor.OnSurface,
            )
        }
        Text(
            text = stringResource(R.string.pair_waiting_network_detail),
            style = FlickType.body(sizeSp = 24, lineHeightRatio = 1.2f),
            color = FlickColor.OnSurfaceDim,
        )
    }
}

/** Spec §5.1 right column: the white QR card, the endpoint line, and bind health. */
@Composable
private fun QrColumn(
    payload: String,
    host: String,
    port: Int,
    bindUptimeSec: Long,
    rebindCount: Int,
    lastTeardown: String?,
) {
    Column(
        modifier = Modifier.width(QrColumnWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        QrCode(
            payload = payload,
            size = QrColumnWidth,
            quietZonePadding = 23.dp,
            contentDescription = stringResource(R.string.pair_qr_content_description),
            shape = FlickShape.Hero,
            centerOverlay = { markSize -> BrandMark(size = markSize, tint = FlickColor.Primary) },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = FlickIcons.Wifi,
                contentDescription = null,
                tint = FlickColor.OnSurfaceMuted,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.pair_url, host, port),
                style = FlickType.monoTabular(sizeSp = 16, weight = FontWeight.SemiBold),
                color = FlickColor.OnSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Bind health: a stale port reads differently from a wrong code.
        Text(
            text = stringResource(R.string.pair_bind_health, bindUptimeSec, rebindCount) +
                (lastTeardown?.let { " · " + stringResource(R.string.pair_bind_last_teardown, it) } ?: ""),
            style = FlickType.monoTabular(sizeSp = 16, weight = FontWeight.Medium),
            color = FlickColor.OnSurfaceFaint,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The enlarged-code mode — the one thing a distant viewer might need bigger. */
@Composable
private fun EnlargedCode(
    spacedCode: String,
    locked: Boolean,
    doneFocus: FocusRequester,
    onDone: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FlickColor.ScrimVeil),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                text = spacedCode,
                style = FlickType.monoTabular(sizeSp = 120, weight = FontWeight.SemiBold),
                color = FlickColor.Spark,
            )
            Text(
                text = stringResource(if (locked) R.string.pair_locked else R.string.pair_code_hint),
                style = FlickType.body(sizeSp = 24, lineHeightRatio = 1.15f),
                color = if (locked) FlickColor.Caution else FlickColor.OnSurfaceDim,
            )
            FlickTvButton(onClick = onDone, focusRequester = doneFocus) {
                Text(
                    text = stringResource(R.string.pair_hide_bigger),
                    style = FlickType.body(sizeSp = 24, lineHeightRatio = 1.1f),
                    color = FlickColor.OnSurface,
                )
            }
        }
    }
}

/** The design's "Flick" pick-out — the highlighted word comes from resources. */
@Composable
private fun highlightedInstructions(): AnnotatedString {
    val copy = stringResource(R.string.pair_instructions)
    val highlight = stringResource(R.string.pair_instructions_highlight)
    return remember(copy, highlight) {
        val at = if (highlight.isEmpty()) -1 else copy.indexOf(highlight)
        buildAnnotatedString {
            if (at < 0) {
                append(copy)
            } else {
                append(copy.substring(0, at))
                withStyle(SpanStyle(color = FlickColor.SparkBright)) { append(highlight) }
                append(copy.substring(at + highlight.length))
            }
        }
    }
}

/**
 * The rotation line. A countdown renders ONLY against a real TTL deadline; with
 * no deadline the card states the honest half of the design's line and no timer.
 */
@Composable
private fun rotationLine(expiresAtElapsedMs: Long?): String {
    var remainingSec by remember(expiresAtElapsedMs) {
        mutableStateOf(expiresAtElapsedMs?.let(::remainingSeconds) ?: 0L)
    }
    LaunchedEffect(expiresAtElapsedMs) {
        if (expiresAtElapsedMs == null) return@LaunchedEffect
        while (true) {
            remainingSec = remainingSeconds(expiresAtElapsedMs)
            delay(1_000L)
        }
    }
    val text = if (expiresAtElapsedMs == null) {
        stringResource(R.string.pair_one_sender)
    } else {
        stringResource(
            R.string.pair_code_rotates,
            String.format(Locale.US, "%d:%02d", remainingSec / 60L, remainingSec % 60L),
        )
    }
    return text.uppercase(Locale.getDefault())
}

private fun remainingSeconds(expiresAtElapsedMs: Long): Long =
    ((expiresAtElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L) + 999L) / 1000L
