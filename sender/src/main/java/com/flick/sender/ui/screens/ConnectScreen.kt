package com.flick.sender.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.flick.sender.R
import com.flick.sender.model.ConnectionStatus
import com.flick.sender.net.FlickController
import com.flick.sender.net.PairErrorKind
import com.flick.sender.net.PairLaunch
import com.flick.sender.ui.components.DeviceRow
import com.flick.sender.ui.components.FlickPrimaryButton
import com.flick.sender.ui.components.PairCodeField
import com.flick.sender.ui.components.PairOptionCard
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors

/** S1 — first-run connect & pair. Discovery first; QR / code are equal citizens. */
@Composable
fun ConnectScreen(controller: FlickController) {
    val colors = LocalFlickColors.current
    val devices by controller.devices.collectAsState()
    val pairTarget by controller.pairTarget.collectAsState()
    val pairError by controller.pairError.collectAsState()
    val pendingPairLaunch by controller.pendingPairLaunch.collectAsState()
    val codeRevision by controller.pairCodeRevision.collectAsState()
    val connection by controller.connection.collectAsState()
    val manualLabel = stringResource(R.string.connect_manual)
    val diagnosticsLabel = stringResource(R.string.a11y_diagnostics)
    var manualOpen by remember { mutableStateOf(false) }
    var qrHint by remember { mutableStateOf(false) }

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

    LaunchedEffect(Unit) { controller.onStart() }
    // Every accepted QR is a new launch event. Keying the sheet by eventId discards
    // prior host/port/code text before any pairing socket can open.
    LaunchedEffect(pendingPairLaunch?.eventId) {
        if (pendingPairLaunch != null) manualOpen = true
    }
    LaunchedEffect(pairError) { if (pairError == PairErrorKind.INVALID_QR) manualOpen = false }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.surface)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(22.dp))
            Text(
                text = stringResource(R.string.connect_title),
                style = FlickText.heading.copy(color = colors.onSurface),
            )
            Row(Modifier.padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(FlickIcons.Private, contentDescription = null, tint = colors.live, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.connect_wifi_note),
                    style = FlickText.caption.copy(color = colors.onSurfaceDim),
                )
            }
            if (pairErrorText != null && pairTarget == null && !manualOpen) {
                Text(
                    pairErrorText,
                    style = FlickText.caption.copy(color = colors.trouble),
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.connect_found_label, devices.size),
                style = FlickText.monoLabel.copy(color = colors.onSurfaceFaint),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(8.dp))

            if (devices.isEmpty()) {
                Text(
                    stringResource(R.string.connect_searching),
                    style = FlickText.caption.copy(color = colors.onSurfaceFaint),
                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                )
            } else {
                devices.forEach { tv ->
                    DeviceRow(
                        tv = tv,
                        selected = pairTarget?.host == tv.host,
                        onClick = { controller.selectDevice(tv) },
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }

            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PairOptionCard(
                    title = stringResource(R.string.connect_qr_title),
                    subtitle = stringResource(R.string.connect_qr_sub),
                    // Scanning happens in the system camera, which re-launches Flick
                    // through the flick://pair deep link; there is no in-app scanner.
                    onClick = { qrHint = true },
                    icon = FlickIcons.Qr,
                    modifier = Modifier.weight(1.2f),
                )
                PairOptionCard(
                    title = stringResource(R.string.connect_code_title),
                    subtitle = stringResource(R.string.connect_code_sub),
                    onClick = {
                        val single = devices.singleOrNull { it.tvId != null }
                        if (single != null) controller.selectDevice(single) else manualOpen = true
                    },
                    codeHint = "····",
                    modifier = Modifier.weight(1f),
                )
            }
            if (qrHint) {
                Text(
                    stringResource(R.string.connect_qr_hint),
                    style = FlickText.caption.copy(color = colors.onSurfaceDim),
                    modifier = Modifier.padding(start = 4.dp, top = 10.dp, end = 4.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.connect_manual),
                style = FlickText.caption.copy(
                    color = colors.onSurfaceFaint,
                    textDecoration = TextDecoration.Underline,
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = manualLabel }
                    .clickable { manualOpen = true }
                    .padding(6.dp),
            )
            // The pairing screen is where failures surface, so the log has to be
            // openable from here — the advisories sheet has no reachable trigger.
            Text(
                text = stringResource(R.string.connect_diagnostics),
                style = FlickText.caption.copy(
                    color = colors.onSurfaceFaint,
                    textDecoration = TextDecoration.Underline,
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = diagnosticsLabel }
                    .clickable { controller.toggleDiagnostics(true) }
                    .padding(6.dp),
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
                fromQr = launch?.host != null && launch?.port != null,
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
    }
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
            style = FlickText.heading.copy(color = colors.onSurface),
        )
        Text(
            stringResource(R.string.pair_code_sub),
            style = FlickText.caption.copy(color = colors.onSurfaceDim),
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            stringResource(R.string.pair_code_endpoint, endpoint),
            style = FlickText.mono.copy(color = colors.onSurfaceFaint),
            modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
        )
        PairCodeField(code = code, onCodeChange = { code = it })
        if (error != null) {
            Text(
                error,
                style = FlickText.caption.copy(color = colors.trouble),
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        if (connecting) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = colors.link, modifier = Modifier.size(28.dp))
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
            style = FlickText.heading.copy(color = colors.onSurface),
        )
        if (fromQr) {
            Text(
                stringResource(R.string.manual_from_qr),
                style = FlickText.caption.copy(color = colors.onSurfaceDim),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text(stringResource(R.string.manual_host_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
            label = { Text(stringResource(R.string.manual_port_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter { c -> c.isDigit() }.take(4) },
            label = { Text(stringResource(R.string.manual_code_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth().focusRequester(codeFocus),
        )
        if (error != null) {
            Text(
                error,
                style = FlickText.caption.copy(color = colors.trouble),
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        if (connecting) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = colors.link, modifier = Modifier.size(28.dp))
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
