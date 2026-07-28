@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.flick.sender.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.flick.sender.PairLaunchEventIds
import com.flick.sender.R
import com.flick.sender.model.ConnectionStatus
import com.flick.sender.model.DiscoveredTv
import com.flick.sender.model.TvAvailability
import com.flick.sender.net.FlickController
import com.flick.sender.net.IncomingPairEvent
import com.flick.sender.net.PairErrorKind
import com.flick.sender.net.PairLaunch
import com.flick.sender.net.PairedTv
import com.flick.sender.ui.components.DeviceRow
import com.flick.sender.ui.components.FlickPrimaryButton
import com.flick.sender.ui.components.FlickSubtleButton
import com.flick.sender.ui.components.NowPlayingDockClearance
import com.flick.sender.ui.components.PairCodeField
import com.flick.sender.ui.components.PairQrCard
import com.flick.sender.ui.components.QrScannerPanel
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.PillShape
import com.flick.sender.ui.theme.flickRipple
import com.flick.sender.ui.theme.rememberFlickTouchHaptics
import com.flick.sender.ui.theme.rememberReduceMotion

/** S1 — first-run connect & pair. Discovery leads; the code card is the escape hatch. */
@Composable
fun ConnectScreen(controller: FlickController) {
    val colors = LocalFlickColors.current
    val devices by controller.devices.collectAsState()
    val pairTarget by controller.pairTarget.collectAsState()
    val pairError by controller.pairError.collectAsState()
    val pendingPairLaunch by controller.pendingPairLaunch.collectAsState()
    val codeRevision by controller.pairCodeRevision.collectAsState()
    val connection by controller.connection.collectAsState()
    val connectedTv by controller.connectedTv.collectAsState()
    val castingItem by controller.castingItem.collectAsState()
    val manualLabel = stringResource(R.string.connect_manual)
    val diagnosticsLabel = stringResource(R.string.a11y_diagnostics)
    var manualOpen by remember { mutableStateOf(false) }
    var scanOpen by remember { mutableStateOf(false) }
    val haptics = rememberFlickTouchHaptics()

    // Map the typed pairing outcome to localized copy (never raw exception text).
    val pairErrorText: String? = when (pairError) {
        PairErrorKind.CODE_MISMATCH -> stringResource(R.string.pair_error_code)
        PairErrorKind.UNREACHABLE -> stringResource(R.string.pair_error_unreachable)
        PairErrorKind.INVALID_QR -> stringResource(R.string.pair_error_qr)
        PairErrorKind.UPDATE_REQUIRED -> stringResource(R.string.pair_error_update)
        PairErrorKind.INVALID_ENTRY -> stringResource(R.string.pair_error_invalid)
        PairErrorKind.PAIRING_REQUIRED -> stringResource(R.string.pair_error_pair_required)
        PairErrorKind.LOCAL_STORAGE -> stringResource(R.string.pair_error_storage)
        PairErrorKind.TIMED_OUT -> stringResource(R.string.pair_error_timeout)
        PairErrorKind.REJECTED -> stringResource(R.string.pair_error_rejected)
        PairErrorKind.CODE_EXPIRED -> stringResource(R.string.pair_error_expired)
        PairErrorKind.TV_SURFACE -> stringResource(R.string.pair_error_surface)
        PairErrorKind.LOCKED -> stringResource(R.string.pair_error_locked)
        PairErrorKind.TV_STORAGE -> stringResource(R.string.pair_error_tv_storage)
        PairErrorKind.REPAIR_NEEDED -> stringResource(R.string.pair_error_repair)
        PairErrorKind.ENDPOINT_CHANGED -> stringResource(R.string.pair_error_endpoint_changed)
        null -> null
    }
    val connecting = connection == ConnectionStatus.CONNECTING || connection == ConnectionStatus.PAIRING

    // Both pulses come from a real outcome, never from arriving here: CONNECTED is
    // published only once the receiver has accepted the code, and pairError only moves
    // when an attempt ends. Seeding the previous value keeps the first composition —
    // including a screen entered with a stale error already on it — silent.
    var lastConnection by remember { mutableStateOf(connection) }
    LaunchedEffect(connection) {
        val previous = lastConnection
        lastConnection = connection
        if (connection == ConnectionStatus.CONNECTED && previous != ConnectionStatus.CONNECTED) {
            haptics.confirm()
        }
    }
    var lastPairError by remember { mutableStateOf(pairError) }
    LaunchedEffect(pairError) {
        val previous = lastPairError
        lastPairError = pairError
        if (pairError != null && previous != pairError) haptics.reject()
    }

    LaunchedEffect(Unit) { controller.onStart() }
    // Every accepted QR is a new launch event. Keying the sheet by eventId discards
    // prior host/port/code text before any pairing socket can open.
    LaunchedEffect(pendingPairLaunch?.eventId) {
        if (pendingPairLaunch != null) {
            scanOpen = false
            manualOpen = true
        }
    }
    LaunchedEffect(pairError) {
        if (pairError == PairErrorKind.INVALID_QR) {
            scanOpen = false
            manualOpen = false
        }
    }

    // The blue row is the recommendation: whichever TV the code sheet is open for,
    // otherwise the first one advertising itself awake (discovery sorts READY first).
    // A live link retires the automatic half of that — recommending a second TV beside
    // the one already carrying playback reads as a choice the user does not have.
    val recommended = devices
        .takeIf { !linkLive(connection, connectedTv) }
        ?.firstOrNull { it.state == TvAvailability.READY }
    val featuredHost = pairTarget?.host ?: recommended?.host

    // NSD re-advertises on its own schedule and drops entries that miss a probe, but
    // the link is a fact independent of both. A connected TV no advertisement accounts
    // for is still surfaced, from the pairing record — the only place the verified
    // endpoint is held — so the section never reports "nothing here" while the phone is
    // driving that TV, and never hands the claim to a row advertising some other address.
    val undiscoveredConnection = connectedTv?.takeIf { paired ->
        linkLive(connection, paired) && devices.none { isConnectedDevice(connection, paired, it) }
    }

    // The dock floats over this surface too, above the nav, so the foot of the scroll has
    // to clear both of them while a cast is live — otherwise the last device row and the
    // footer actions sit under a bar that answers taps meant for them.
    val bottomClearance = 116.dp + if (castingItem != null) NowPlayingDockClearance else 0.dp

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = bottomClearance),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = stringResource(R.string.connect_heading),
                style = FlickText.displayLarge.copy(color = colors.onSurface),
            )
            PrivacyPill()
        }

        if (pairErrorText != null && pairTarget == null && !manualOpen) {
            PairErrorCard(pairErrorText)
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(R.string.connect_nearby_label, devices.size),
                style = FlickText.monoEyebrowWide.copy(color = colors.onSurfaceFaint),
                modifier = Modifier.padding(start = 4.dp),
            )
            if (undiscoveredConnection != null) {
                DeviceRow(
                    tv = DiscoveredTv(
                        name = undiscoveredConnection.name,
                        host = undiscoveredConnection.host,
                        port = undiscoveredConnection.port,
                        tvId = undiscoveredConnection.tvId,
                        // Nothing about this row comes from an advertisement, so it
                        // claims no model and no availability it cannot vouch for.
                        model = null,
                        state = TvAvailability.UNKNOWN,
                    ),
                    featured = false,
                    connected = true,
                    onClick = {},
                )
            }
            if (devices.isEmpty()) {
                Text(
                    text = stringResource(R.string.connect_searching),
                    style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 6.dp),
                )
            } else {
                devices.forEach { tv ->
                    DeviceRow(
                        tv = tv,
                        featured = tv.host == featuredHost,
                        connected = isConnectedDevice(connection, connectedTv, tv),
                        onClick = { controller.selectDevice(tv) },
                    )
                }
            }
        }

        PairQrCard(
            onScan = { scanOpen = true },
            onEnterCode = {
                val single = devices.singleOrNull { it.tvId != null }
                if (single != null) controller.selectDevice(single) else manualOpen = true
            },
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FooterAction(
                text = stringResource(R.string.connect_manual),
                accessibilityLabel = manualLabel,
                onClick = { manualOpen = true },
            )
            // The pairing screen is where failures surface, so the log has to be
            // openable from here — the advisories sheet has no reachable trigger.
            FooterAction(
                text = stringResource(R.string.connect_diagnostics),
                accessibilityLabel = diagnosticsLabel,
                onClick = { controller.toggleDiagnostics(true) },
            )
        }
    }

    val target = pairTarget
    if (target != null) {
        CodeSheet(
            tvName = target.name,
            endpoint = "${target.host}:${target.port}",
            error = pairErrorText,
            connecting = connecting,
            codeRevision = codeRevision,
            // The whole confirmed record is submitted, not just its id: the endpoint
            // rendered above is the only one the typed code may be sent to.
            onSubmit = { code -> controller.submitDiscoveredPair(target, code) },
            onDismiss = { controller.cancelPairing() },
        )
    } else if (manualOpen) {
        // Kept open through the attempt so a wrong code / unreachable host on the manual
        // escape-hatch reports the result instead of dismissing silently; a successful
        // connect changes the route, which unmounts this screen (and the sheet).
        val launch = pendingPairLaunch
        val launchId = launch?.eventId
        key(launchId) {
            ManualSheet(
                initialHost = launch?.host.orEmpty(),
                initialPort = launch?.port?.toString() ?: PairLaunch.DEFAULT_CONTROL_PORT.toString(),
                fromQr = launch?.host != null && launch.port != null,
                error = pairErrorText,
                connecting = connecting,
                codeRevision = codeRevision,
                onConnect = { host, port, code -> controller.submitTvDisplayedPair(launchId ?: 0L, host, port, code) },
                onDismiss = {
                    manualOpen = false
                    if (launchId != null) controller.dismissPairLaunch(launchId) else controller.cancelPairing()
                },
            )
        }
    } else if (scanOpen) {
        ScanSheet(
            onPayload = { raw ->
                // The scanner is only a second way to obtain the launch string: it goes
                // through the deep link's parser and the same controller entry point,
                // under a freshly minted event id. The endpoint it carries stays an
                // untrusted prefill that the code typed off the TV has to authorize.
                scanOpen = false
                controller.acceptPairLaunch(
                    IncomingPairEvent(PairLaunchEventIds.next(), PairLaunch.parse(raw)),
                )
            },
            onEnterCode = {
                scanOpen = false
                manualOpen = true
            },
            onDismiss = { scanOpen = false },
        )
    }
}

/** A paired record only describes a live TV while the control link is actually up. */
internal fun linkLive(connection: ConnectionStatus, connected: PairedTv?): Boolean =
    connection == ConnectionStatus.CONNECTED && connected != null

/**
 * Whether [tv] is the TV the phone is connected to right now.
 *
 * The endpoint decides it. While the link is live the record carries the address the
 * socket was actually verified against — both pairing and resume commit it before
 * publishing the record — so there is no stale endpoint here for an identity to rescue.
 * An advertised `tvId` therefore cannot buy the badge for another address: mDNS is
 * unauthenticated (see `submitDiscoveredPair`, which refuses to re-derive a host from
 * it), and the badged row is also the one printing the address the user reads off the
 * phone. The identity is only a veto — an address that changed hands between
 * advertisements is not the live link either. Names are never compared: two TVs in one
 * house routinely ship with the same one.
 */
internal fun isConnectedDevice(
    connection: ConnectionStatus,
    connected: PairedTv?,
    tv: DiscoveredTv,
): Boolean {
    if (connected == null || !linkLive(connection, connected)) return false
    if (connected.host != tv.host || connected.port != tv.port) return false
    val pairedId = connected.tvId.takeIf { it.isNotBlank() }
    val advertisedId = tv.tvId?.takeIf { it.isNotBlank() }
    return pairedId == null || advertisedId == null || pairedId == advertisedId
}

/**
 * The camera route into pairing. It ends where the QR deep link ends — at the manual
 * sheet with the address filled in and the code still to type.
 */
@Composable
private fun ScanSheet(
    onPayload: (String) -> Unit,
    onEnterCode: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalFlickColors.current
    BottomSheet(onDismiss = onDismiss) {
        SheetGrabber()
        Text(
            stringResource(R.string.scan_heading),
            style = FlickText.titleLarge.copy(color = colors.onSurface),
        )
        Text(
            stringResource(R.string.scan_sub),
            style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
            modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
        )
        QrScannerPanel(onPayload = onPayload)
        Spacer(Modifier.height(14.dp))
        // Reachable in every scanner state, including the ones with no camera to offer.
        FlickSubtleButton(
            text = stringResource(R.string.scan_manual_instead),
            onClick = onEnterCode,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
    }
}

/** The one promise the product makes before anything is tapped. */
@Composable
private fun PrivacyPill() {
    val colors = LocalFlickColors.current
    Row(
        Modifier
            .clip(PillShape)
            .background(colors.spark)
            .padding(start = 13.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = FlickIcons.Private,
            contentDescription = null,
            tint = colors.onSpark,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = stringResource(R.string.connect_wifi_note),
            style = FlickText.labelMedium.copy(color = colors.onSpark),
        )
    }
}

/** The last pairing outcome, held on screen until the next attempt replaces it. */
@Composable
private fun PairErrorCard(message: String) {
    val colors = LocalFlickColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FlickCorners.warning))
            .background(colors.trouble.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = FlickIcons.Warning,
            contentDescription = null,
            tint = colors.trouble,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = message,
            style = FlickText.bodySmall.copy(color = colors.trouble),
        )
    }
}

/**
 * The pairing attempt in flight. Un-contained: inside a sheet the shape morph is the
 * whole signal and a filled container would shout over the code the user just typed.
 * Nothing here is determinate — the handshake reports steps, never a fraction.
 */
@Composable
private fun PairingIndicator() {
    val colors = LocalFlickColors.current
    if (rememberReduceMotion()) {
        // A loop never reaches an end state, so reduce motion gets the resting
        // silhouette rather than a frozen spin.
        Box(
            Modifier
                .size(28.dp)
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(colors.primary),
        )
    } else {
        LoadingIndicator(modifier = Modifier.size(28.dp), color = colors.primary)
    }
}

/** Quiet text action. Interactive copy has to clear 4.5:1, so it uses the dim ink. */
@Composable
private fun FooterAction(
    text: String,
    accessibilityLabel: String,
    onClick: () -> Unit,
) {
    val colors = LocalFlickColors.current
    val interaction = remember { MutableInteractionSource() }
    Text(
        text = text,
        style = FlickText.labelMedium.copy(color = colors.onSurfaceDim, textAlign = TextAlign.Center),
        modifier = Modifier
            .clip(PillShape)
            .semantics { contentDescription = accessibilityLabel }
            .clickable(
                interactionSource = interaction,
                indication = flickRipple(colors.primary),
                onClick = onClick,
            )
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp, vertical = 15.dp),
    )
}

/**
 * Code-only pairing for a TV the user tapped in the discovered list. The endpoint is
 * shown read-only for confirmation — it comes from the live advertisement, and the
 * typed code is still what authorizes it.
 */
@Composable
private fun CodeSheet(
    tvName: String,
    endpoint: String,
    error: String?,
    connecting: Boolean,
    codeRevision: Long,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalFlickColors.current
    var code by remember { mutableStateOf("") }
    LaunchedEffect(codeRevision) { code = "" }
    BottomSheet(onDismiss = onDismiss) {
        SheetGrabber()
        Text(
            stringResource(R.string.pair_code_heading, tvName),
            style = FlickText.titleLarge.copy(color = colors.onSurface),
        )
        Text(
            stringResource(R.string.pair_code_sub),
            style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            stringResource(R.string.pair_code_endpoint, endpoint),
            // A mono value, not an eyebrow: onSurfaceFaint is only cleared for tracked
            // uppercase labels, and this line is what the user checks before typing.
            style = FlickText.monoSmall.copy(color = colors.onSurfaceDim),
            modifier = Modifier.padding(top = 8.dp, bottom = 18.dp),
        )
        PairCodeField(code = code, onCodeChange = { code = it })
        if (error != null) {
            Text(
                error,
                style = FlickText.bodySmall.copy(color = colors.trouble),
                modifier = Modifier.padding(top = 14.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        if (connecting) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                PairingIndicator()
            }
        } else {
            FlickPrimaryButton(
                text = stringResource(R.string.pair_connect),
                onClick = { onSubmit(code) },
                enabled = PairLaunch.isCode(code),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * The three-field escape hatch. A QR-supplied endpoint only PREFILLS it: the user
 * still types the code read off the TV and presses connect, so the phone never
 * accepts an endpoint that was not authorized by an out-of-band secret.
 */
@Composable
private fun ManualSheet(
    initialHost: String,
    initialPort: String,
    fromQr: Boolean,
    error: String?,
    connecting: Boolean,
    codeRevision: Long,
    onConnect: (String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalFlickColors.current
    var host by remember { mutableStateOf(initialHost) }
    var port by remember { mutableStateOf(initialPort) }
    var code by remember { mutableStateOf("") }
    val codeFocus = remember { FocusRequester() }
    LaunchedEffect(codeRevision) { code = "" }
    LaunchedEffect(Unit) { runCatching { codeFocus.requestFocus() } }
    BottomSheet(onDismiss = onDismiss) {
        SheetGrabber()
        Text(
            stringResource(if (fromQr) R.string.manual_heading_qr else R.string.manual_heading),
            style = FlickText.titleLarge.copy(color = colors.onSurface),
        )
        if (fromQr) {
            Text(
                stringResource(R.string.manual_from_qr),
                style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text(stringResource(R.string.manual_host_label)) },
            singleLine = true,
            shape = RoundedCornerShape(FlickCorners.tuneBtn),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
            label = { Text(stringResource(R.string.manual_port_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(FlickCorners.tuneBtn),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter { c -> c.isDigit() }.take(4) },
            label = { Text(stringResource(R.string.manual_code_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            shape = RoundedCornerShape(FlickCorners.tuneBtn),
            modifier = Modifier.fillMaxWidth().focusRequester(codeFocus),
        )
        if (error != null) {
            Text(
                error,
                style = FlickText.bodySmall.copy(color = colors.trouble),
                modifier = Modifier.padding(top = 14.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        if (connecting) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                PairingIndicator()
            }
        } else {
            FlickPrimaryButton(
                text = stringResource(R.string.manual_connect),
                onClick = {
                    // Stay open — the connect result (spinner → error) shows here; a
                    // successful connect changes the route, which unmounts the sheet.
                    if (host.isNotBlank()) onConnect(host.trim(), port, code)
                },
                enabled = PairLaunch.isCanonicalIpv4(host) &&
                    PairLaunch.isCanonicalPort(port) && PairLaunch.isCode(code),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
