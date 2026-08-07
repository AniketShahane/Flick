@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.flick.sender.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.flick.sender.PairLaunchEventIds
import com.flick.sender.R
import com.flick.sender.model.ConnectionStatus
import com.flick.sender.model.DiscoveredTv
import com.flick.sender.model.TvAvailability
import com.flick.sender.net.FlickController
import com.flick.sender.net.ManualPairAttemptEvent
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
import com.flick.sender.ui.theme.PillMorphShape
import com.flick.sender.ui.theme.PressedPillShape
import com.flick.sender.ui.theme.Spark
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
    val manualPairAttempt by controller.manualPairAttempt.collectAsState()
    val connectedTv by controller.connectedTv.collectAsState()
    val castingItem by controller.castingItem.collectAsState()
    // Two states rather than one: a sheet the screen closes has an exit to play before it
    // may leave the composition. See [SheetSwitch].
    val manual = rememberSheetSwitch()
    val scan = rememberSheetSwitch()
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
    val connecting = pairAttemptInFlight(connection)
    val awaitingTvConfirmation = connection == ConnectionStatus.CONFIRM_ON_TV

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
        val launch = pendingPairLaunch
        if (launch != null) {
            // A launch REPLACES the scanner rather than dismissing it: the sheet it hands
            // over to is what the scan was for, so the two swap in place instead of
            // playing an exit into an empty screen and then a second entrance over it.
            scan.gone()
            // A scanned v4 payload arrives with the code already read, so the form has
            // nothing left to ask for — the confirmation below is the whole of what
            // remains. Anything older still opens the form and still gets typed into.
            if (launch.codeInHand) manual.gone() else manual.open()
        }
    }
    LaunchedEffect(pairError) {
        // The error belongs to the screen underneath, so both sheets leave rather than
        // vanish: this is the app closing them, not the user.
        if (pairError == PairErrorKind.INVALID_QR) {
            scan.close()
            manual.close()
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
    // pairing card sit under a bar that answers taps meant for them.
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = stringResource(R.string.connect_heading),
                    style = FlickText.displayLarge.copy(color = colors.onSurface),
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(
                    onClick = { controller.toggleDiagnostics(true) },
                    shapes = ButtonDefaults.shapes(
                        shape = PillMorphShape,
                        pressedShape = PressedPillShape,
                    ),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = colors.primaryContainer,
                        contentColor = colors.onPrimaryContainer,
                    ),
                    contentPadding = PaddingValues(horizontal = 15.dp, vertical = 15.dp),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        text = stringResource(R.string.diagnostics_title),
                        style = FlickText.labelMedium,
                    )
                }
            }
            PrivacyPill()
        }

        if (pairErrorText != null && pairTarget == null && !manual.composed) {
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
            onScan = { scan.open() },
            onEnterAddress = { manual.open() },
        )

        // The record outlives the process, so a returning user is never told this twice.
        if (showTvAppNote(connectedTv, controller.pairedBeforeThisLaunch)) TvAppNote()
    }

    val target = pairTarget
    val scanned = pendingPairLaunch?.takeIf { it.codeInHand }
    if (target != null) {
        CodeSheet(
            tvName = target.name,
            endpoint = "${target.host}:${target.port}",
            error = pairErrorText,
            connecting = connecting,
            awaitingTvConfirmation = awaitingTvConfirmation,
            codeRevision = codeRevision,
            // The whole confirmed record is submitted, not just its id: the endpoint
            // rendered above is the only one the typed code may be sent to.
            onSubmit = { code -> controller.submitDiscoveredPair(target, code) },
            onDismiss = { controller.cancelPairing() },
        )
    } else if (scanned != null) {
        // The payload names an address, never a TV. Discovery may have heard a name at
        // that exact endpoint, and that is a hint for the person holding the phone rather
        // than evidence about who answers there — mDNS is unauthenticated and its records
        // go stale. So the card has two shapes, one naming the TV and one naming only the
        // address, instead of one shape with a placeholder standing in for a name Flick
        // would then be claiming to know.
        val advertised = devices.firstOrNull { it.host == scanned.host && it.port == scanned.port }?.name
        ScannedSheet(
            tvName = advertised,
            endpoint = "${scanned.host}:${scanned.port}",
            error = pairErrorText,
            connecting = connecting,
            awaitingTvConfirmation = awaitingTvConfirmation,
            // Only this tap spends the code. The scan proved a QR was in front of the
            // camera; the confirmation is the user saying it was on their own TV.
            onPair = { controller.confirmScannedPair(scanned.eventId) },
            onDismiss = { controller.dismissPairLaunch(scanned.eventId) },
        )
    } else if (manual.composed) {
        // Kept open through the attempt so a wrong code / unreachable host on the manual
        // escape-hatch reports the result instead of dismissing silently; a successful
        // connect changes the route, which unmounts this screen (and the sheet).
        val launch = pendingPairLaunch
        val launchId = launch?.eventId
        key(launchId) {
            ManualSheet(
                visible = manual.visible,
                initialHost = launch?.host.orEmpty(),
                initialPort = launch?.port?.toString() ?: PairLaunch.DEFAULT_CONTROL_PORT.toString(),
                fromQr = launch?.host != null && launch.port != null,
                error = pairErrorText,
                connection = connection,
                manualPairAttempt = manualPairAttempt,
                codeRevision = codeRevision,
                onConnect = { host, port, code ->
                    controller.submitTvDisplayedPair(
                        eventId = launchId ?: 0L,
                        host = host,
                        port = port,
                        code = code,
                        manualSubmission = true,
                    )
                },
                // Whose dismissal this is, settled on the frame the exit starts. An
                // INVALID_QR landing during a user's own exit runs `manual.close()`, and
                // read any later this would call that close the app's — and skip the
                // cleanup the user's dismissal owed.
                onLeaving = { manual.leaving() },
                onDismiss = {
                    // A close the screen started has already done this bookkeeping, and
                    // cancelling here would clear the very error it closed to reveal.
                    val byUser = !manual.closingByApp
                    manual.gone()
                    if (byUser) {
                        if (launchId != null) controller.dismissPairLaunch(launchId) else controller.cancelPairing()
                    }
                },
            )
        }
    } else if (scan.composed) {
        ScanSheet(
            visible = scan.visible,
            onPayload = { raw ->
                // The scanner is the one ingress allowed to keep a v4 code: the camera is
                // in this process, so the payload came off a QR in front of the user
                // rather than out of an Intent any installed app can fire. The endpoint is
                // still untrusted — what the code authorises is that address and no other.
                controller.acceptScannedPair(PairLaunchEventIds.next(), PairLaunch.parseScanned(raw))
            },
            onEnterCode = {
                scan.gone()
                manual.open()
            },
            onDismiss = { scan.gone() },
        )
    }
}

/**
 * Whether a pairing attempt is still running, and therefore whether the sheets hold
 * their spinner and refuse a second submit.
 *
 * [ConnectionStatus.CONFIRM_ON_TV] belongs in here and not beside it. It is a pairing
 * attempt that is very much in flight — a correct-shaped code is already on the wire
 * and a person at the TV is being asked about it — and re-submitting during it would
 * tear the socket the receiver is answering down and dial again with a code that has
 * already been consumed.
 */
internal fun pairAttemptInFlight(connection: ConnectionStatus): Boolean =
    connection == ConnectionStatus.CONNECTING ||
        connection == ConnectionStatus.PAIRING ||
        connection == ConnectionStatus.CONFIRM_ON_TV

/** A paired record only describes a live TV while the control link is actually up. */
internal fun linkLive(connection: ConnectionStatus, connected: PairedTv?): Boolean =
    connection == ConnectionStatus.CONNECTED && connected != null

/**
 * Whether the foot of the screen still says that the TV needs Flick installed on it too.
 *
 * A stored pairing decides it, and [pairedEarlier] is why there are two arguments rather
 * than one: [connected] is seeded from the **v2** record alone, so a phone that paired
 * under the old host-keyed scheme and has not re-paired since would read as never-paired.
 * `FlickController.pairedBeforeThisLaunch` consults both. Either one is durable proof that
 * this phone has reached a running receiver at least once, and it outlives the process,
 * which is what the claim needs: the user learns this fact once, and a rule that forgot it
 * would put the note back on a returning user's screen every evening they were not already
 * casting.
 *
 * Nothing weaker than a record would do. A live link proves the same thing but only while
 * a cast is up, and a populated list proves less still: only a TV already running the
 * receiver ever advertises, so a row is evidence about that TV and about no other set in
 * the house.
 *
 * The one person this exists for keeps it: a pairing that fails because the TV app is
 * missing never writes a record of either kind.
 */
internal fun showTvAppNote(connected: PairedTv?, pairedEarlier: Boolean): Boolean =
    connected == null && !pairedEarlier

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
 * Whether the code typed for a TV picked out of the discovered list may be sent now.
 *
 * [connecting] belongs in the guard rather than beside it: the sheet is held open
 * through an attempt, and a second submit arriving during one tears the first down and
 * dials again. A button can be tapped twice; a return key can be held down.
 */
internal fun canSubmitDiscoveredPair(code: String, connecting: Boolean): Boolean =
    !connecting && PairLaunch.isCode(code)

/**
 * Whether the manually entered endpoint may be dialled.
 *
 * All three fields are judged together because the endpoint is an untrusted prefill
 * however it was filled in — typed, deep-linked or scanned — and the code read off the
 * TV is the only thing that authorises it. There is deliberately no shape of this form
 * that connects without one. The host is trimmed before it is judged because a paste is
 * the one way it acquires surrounding space, and the trimmed value is what is sent.
 */
internal fun canSubmitManualPair(
    host: String,
    port: String,
    code: String,
    connecting: Boolean,
): Boolean =
    !connecting && PairLaunch.isCanonicalIpv4(host.trim()) &&
        PairLaunch.isCanonicalPort(port) && PairLaunch.isCode(code)

/**
 * Return, from either keyboard. An external keyboard's numeric pad sends its own key
 * rather than the main one, and the code is typed on a numeric layout.
 */
private fun isSubmitKey(event: KeyEvent): Boolean =
    event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)

/**
 * The camera route into pairing. Where it ends depends on what the QR turned out to
 * carry: a v4 payload lands on the confirmation card, anything older on the manual sheet
 * with the address filled in and the code still to type.
 */
@Composable
private fun ScanSheet(
    visible: Boolean,
    onPayload: (String) -> Unit,
    onEnterCode: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalFlickColors.current
    BottomSheet(
        onDismiss = onDismiss,
        visible = visible,
        header = {
            SheetGrabber()
            Text(
                stringResource(R.string.scan_heading),
                style = FlickText.titleLarge.copy(color = colors.onSurface),
            )
        },
    ) {
        Text(
            stringResource(R.string.scan_sub),
            style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
            modifier = Modifier.padding(bottom = 16.dp),
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

/**
 * The confirmation a scanned v4 QR lands on. The payload carries the code, so the scan
 * has done the typing — but not the authorising. Nothing is dialled until this card's
 * action is pressed, because the only thing making the address in it a television is that
 * the user watched the camera read it off one.
 *
 * [tvName] is null whenever discovery has not heard that exact endpoint advertise itself,
 * and the copy then names the address alone rather than a TV nobody can vouch for.
 */
@Composable
private fun ScannedSheet(
    tvName: String?,
    endpoint: String,
    error: String?,
    connecting: Boolean,
    awaitingTvConfirmation: Boolean,
    onPair: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalFlickColors.current
    BottomSheet(
        onDismiss = onDismiss,
        header = {
            SheetGrabber()
            Text(
                stringResource(R.string.pair_scanned_title),
                style = FlickText.titleLarge.copy(color = colors.onSurface),
            )
        },
        footer = {
            // The outcome travels with the action it belongs to: a denial is what tells
            // the user the TV has moved on and the QR needs scanning again.
            if (error != null) {
                Text(
                    error,
                    style = FlickText.bodySmall.copy(color = colors.trouble),
                    modifier = Modifier.padding(bottom = 14.dp),
                )
            }
            if (connecting) {
                PairingProgress(awaitingTvConfirmation)
            } else {
                FlickPrimaryButton(
                    text = tvName?.let { stringResource(R.string.pair_scanned_action, it) }
                        ?: stringResource(R.string.pair_scanned_action_unknown),
                    onClick = onPair,
                )
                // Read inside the footer, which is composed within the sheet that
                // provides it: "Not now" is the same dismissal the scrim is, so it takes
                // the same exit rather than blinking the card away under the finger.
                val dismiss = LocalSheetDismiss.current
                FlickSubtleButton(
                    text = stringResource(R.string.pair_scanned_dismiss),
                    onClick = dismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) {
        Text(
            text = tvName?.let { stringResource(R.string.pair_scanned_body, it, endpoint) }
                ?: stringResource(R.string.pair_scanned_body_unknown, endpoint),
            style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
        )
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

/** Matches the Settings support row, which is the other card in this app built to be found. */
private val TvAppBadgeSize = 46.dp

/**
 * Set so the two badges stand the same height: this glyph occupies 16.3 of its 24 grid
 * units against the heart's 17.2, which at 30 dp puts both marks a shade over 20 dp tall.
 * It comes out wider than the heart, and is meant to — a television is a wide object, and
 * a box sized to hold the same width would leave it standing shorter than everything it
 * is being matched against. Solid fill does not buy it a smaller box either: the arrow is
 * knocked out of the screen, which takes back most of what the slab put on.
 */
private val TvAppGlyphSize = 30.dp

/**
 * The other half of the product, at the foot of the screen that would otherwise be the
 * whole of it — and the one card here that has to survive being scrolled past. A phone
 * whose TV has no receiver on it sees a screen reporting nothing wrong: the TV never
 * advertises, so it is simply absent, and this note is the only thing that explains the
 * silence. On the quiet card fill it read as a footnote to a list that looked complete.
 *
 * So it takes the Settings support row's treatment exactly — the spark fill, the dark
 * disc, the same title weight — because those two are the only cards in this app whose
 * job is to be found rather than to answer something the user just did.
 *
 * It stops short of that row in the one way that matters: no chevron, no ripple, nothing
 * clickable. The install happens on the television, and there is no route from this phone
 * to a particular TV's store that Flick could stand behind. A card wearing this much
 * weight has to be honest about leading nowhere.
 */
@Composable
private fun TvAppNote() {
    val colors = LocalFlickColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FlickCorners.warning))
            .background(colors.sparkPale)
            // One sentence across two lines. Read separately they arrive as a bare
            // requirement and an instruction with no subject.
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 17.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(TvAppBadgeSize)
                .clip(CircleShape)
                .background(colors.onSpark),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = FlickIcons.TvInstall,
                contentDescription = null,
                // Fixed, like the support badge's heart and for the same reason: `spark`
                // is amber in Light and Dark and blue in the cinematic set, where
                // `primary` holds the warm end. The disc under it is dark in every set.
                tint = Spark,
                modifier = Modifier.size(TvAppGlyphSize),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = stringResource(R.string.connect_tv_app_title),
                style = FlickText.titleMediumEmphasized.copy(color = colors.onSpark),
            )
            Text(
                text = stringResource(R.string.connect_tv_app_body),
                style = FlickText.bodyMedium.copy(color = colors.onSpark.copy(alpha = 0.82f)),
            )
        }
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
/**
 * The attempt in flight, and — when the phone can honestly say so — what it is
 * waiting for.
 *
 * A first-time pairing now stops on a person: the receiver holds the socket while it
 * asks the room whether to admit this phone, which can take tens of seconds. A bare
 * spinner for that long reads as a hang, and the user is the one who has to act, so
 * the line names the TV as where the next move is. It appears ONLY in that state; the
 * ordinary dial and handshake are still a silent couple of seconds.
 */
@Composable
private fun PairingProgress(awaitingTvConfirmation: Boolean) {
    val colors = LocalFlickColors.current
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PairingIndicator()
        if (awaitingTvConfirmation) {
            Text(
                stringResource(R.string.pair_confirm_wait),
                style = FlickText.bodySmall.copy(
                    color = colors.onSurfaceDim,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }
}

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
    awaitingTvConfirmation: Boolean,
    codeRevision: Long,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalFlickColors.current
    var code by remember { mutableStateOf("") }
    LaunchedEffect(codeRevision) { code = "" }
    // One submit behind both the button and the keyboard's return key: the guard that
    // decides whether a code may leave the phone cannot be allowed to differ between
    // the two ways of asking for it.
    val submit: () -> Unit = { if (canSubmitDiscoveredPair(code, connecting)) onSubmit(code) }
    BottomSheet(
        onDismiss = onDismiss,
        // Pinned, because the keyboard this sheet raises leaves the code cells needing
        // most of what is left and the instruction is what the user came here to read.
        header = {
            SheetGrabber()
            Text(
                stringResource(R.string.pair_code_heading, tvName),
                style = FlickText.titleLarge.copy(color = colors.onSurface),
            )
        },
        footer = {
            // The outcome travels with the action it belongs to: the sheet is held open
            // through the attempt so the result shows, and a result that scrolled off
            // under a raised keyboard would not have shown.
            if (error != null) {
                Text(
                    error,
                    style = FlickText.bodySmall.copy(color = colors.trouble),
                    modifier = Modifier.padding(bottom = 14.dp),
                )
            }
            if (connecting) {
                PairingProgress(awaitingTvConfirmation)
            } else {
                FlickPrimaryButton(
                    text = stringResource(R.string.pair_connect),
                    onClick = submit,
                    enabled = canSubmitDiscoveredPair(code, connecting),
                )
            }
        },
    ) {
        Text(
            stringResource(R.string.pair_code_sub),
            style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
        )
        Text(
            stringResource(R.string.pair_code_endpoint, endpoint),
            // A mono value, not an eyebrow: onSurfaceFaint is only cleared for tracked
            // uppercase labels, and this line is what the user checks before typing.
            style = FlickText.monoSmall.copy(color = colors.onSurfaceDim),
            modifier = Modifier.padding(top = 8.dp, bottom = 18.dp),
        )
        PairCodeField(
            code = code,
            onCodeChange = { code = it },
            // PairCodeField fixes its own KeyboardOptions and is not singleLine, so a
            // return that reaches it is committed as text and buzzes off the digit
            // filter — it has to be caught ahead of the field. That is also the whole of
            // what a call site can do: advertising no ImeAction, the field's soft
            // keyboard routes its action key through performEditorAction and sends no key
            // event, so until PairCodeField carries ImeAction.Go itself only a hardware
            // return submits here.
            modifier = Modifier.onPreviewKeyEvent { event ->
                if (isSubmitKey(event)) {
                    submit()
                    true
                } else {
                    false
                }
            },
        )
    }
}

/**
 * The three-field escape hatch. A QR-supplied endpoint only PREFILLS it: the user
 * still types the code read off the TV and presses connect, so the phone never
 * accepts an endpoint that was not authorized by an out-of-band secret.
 */
@Composable
private fun ManualSheet(
    visible: Boolean,
    initialHost: String,
    initialPort: String,
    fromQr: Boolean,
    error: String?,
    connection: ConnectionStatus,
    manualPairAttempt: ManualPairAttemptEvent,
    codeRevision: Long,
    onConnect: (String, String, String) -> Long?,
    onLeaving: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalFlickColors.current
    var host by remember { mutableStateOf(initialHost) }
    var port by remember { mutableStateOf(initialPort) }
    var code by remember { mutableStateOf("") }
    var attempt by remember { mutableStateOf(ManualPairAttemptState()) }
    val codeFocus = remember { FocusRequester() }
    LaunchedEffect(codeRevision) { code = "" }
    LaunchedEffect(manualPairAttempt) {
        attempt = attempt.onControllerState(manualPairAttempt)
    }
    LaunchedEffect(Unit) { runCatching { codeFocus.requestFocus() } }
    // Stay open — the connect result (spinner → error) shows here; a successful connect
    // changes the route, which unmounts the sheet. One lambda for the button and the
    // keyboard's action key, so neither can submit what the other would have refused.
    val submit: () -> Unit = {
        if (canSubmitManualPair(host, port, code, connecting = attempt.submitted)) {
            // The controller returns its monotonic generation synchronously. This makes the
            // local token authoritative before Compose can observe a replacement socket's
            // DISCONNECTED pulse, and distinguishes repeated identical PairErrorKinds.
            onConnect(host.trim(), port, code)?.let { generation ->
                attempt = attempt.begin(generation)
            }
        }
    }
    BottomSheet(
        onDismiss = {
            // The exit may remain composed long enough to animate. Retire the local token
            // at the user's dismissal rather than leaving an invisible attempt behind.
            attempt = ManualPairAttemptState()
            onDismiss()
        },
        visible = visible,
        onLeaving = onLeaving,
        // Pinned above the scroll: this form focuses its LAST field on arrival, so the
        // region scrolls to the bottom before the user has touched it and a heading
        // inside that region would already be gone — which is exactly what "the card is
        // cut off at the top" was.
        header = {
            SheetGrabber()
            Text(
                stringResource(if (fromQr) R.string.manual_heading_qr else R.string.manual_heading),
                style = FlickText.titleLarge.copy(color = colors.onSurface),
            )
        },
        footer = {
            // Pinned with the action: the sheet is held open through the attempt so the
            // result shows, which it cannot do from under a raised keyboard.
            // An error that was already on the screen belongs to an older attempt. Keep
            // it from sitting beside this form's newly-owned progress until a new outcome
            // releases the local token.
            if (error != null && !attempt.submitted) {
                Text(
                    error,
                    style = FlickText.bodySmall.copy(color = colors.trouble),
                    modifier = Modifier.padding(bottom = 14.dp),
                )
            }
            if (manualPairShowsProgress(attempt)) {
                PairingProgress(manualPairAwaitsTvConfirmation(attempt, connection))
            } else {
                FlickPrimaryButton(
                    text = stringResource(R.string.manual_connect),
                    onClick = submit,
                    enabled = canSubmitManualPair(host, port, code, connecting = attempt.submitted),
                )
            }
        },
    ) {
        if (fromQr) {
            Text(
                stringResource(R.string.manual_from_qr),
                style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
            )
        }
        Spacer(Modifier.height(14.dp))
        val fieldShape = RoundedCornerShape(FlickCorners.tuneBtn)
        val fieldColors = manualFieldColors()
        // The address is entered before the port and the port before the code, so Next
        // walks the form the way it is read — and the row below keeps that order running
        // left to right, which is the order one-dimensional focus traversal takes through
        // it. Only the code field ends the run: it is the secret that authorises the
        // endpoint above it, so nothing earlier may connect.
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text(stringResource(R.string.manual_host_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            shape = fieldShape,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        // A port and a code are a handful of digits each and were taking a full row apiece.
        // Paired, they also read as what they are: the two short numbers under the address.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                label = { Text(stringResource(R.string.manual_port_label)) },
                // Only ever seen if the prefilled port is cleared, which is the one moment
                // nothing on screen says what belongs here. Read off the constant so the
                // hint cannot drift from the value the form actually starts with.
                placeholder = { Text(PairLaunch.DEFAULT_CONTROL_PORT.toString()) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                ),
                shape = fieldShape,
                colors = fieldColors,
                // Five digits against the code's four, over the same field padding on both
                // — so the split is 1.15, not the 5:4 the digit counts alone would suggest.
                modifier = Modifier.weight(1.15f),
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter { c -> c.isDigit() }.take(4) },
                label = { Text(stringResource(R.string.manual_code_label)) },
                // Carries the length the label used to spell out, in the one place it is
                // needed: an empty, focused code field with nothing typed into it yet.
                placeholder = { Text(stringResource(R.string.manual_code_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                shape = fieldShape,
                colors = fieldColors,
                modifier = Modifier.weight(1f).focusRequester(codeFocus),
            )
        }
    }
}

/**
 * An edge for the manual form's fields without introducing another surface inside the
 * sheet. The field container is the sheet's own `surfaceRaised` in every state.
 *
 * Material's outlined field is transparent and strokes itself in `outline`, and on this
 * palette that stroke measures 1.43:1 on the light sheet and 1.76:1 on the dark one —
 * under the 3:1 a control owes the surface behind it, and the whole of why three inputs
 * read as loose lines of text. The resting stroke remains `onSurfaceFaint`, the quietest
 * ink in the set that still clears the floor. It is an ink role doing a stroke's job
 * because no outline role in this palette reaches 3:1 on either theme.
 *
 * Focus buys a colour AND a weight. Material draws the resting stroke at 1 dp and the
 * focused one at 2 dp and exposes neither thickness to a call site, so the step is fixed
 * and `primary` arriving at double weight is the signal — 7.18:1 on the light sheet and
 * 10.92:1 on the dark one. Nothing here names a hex: the two themes do not share an action
 * colour (light is the brand blue, dark the amber) and either is free to move again.
 */
@Composable
private fun manualFieldColors(): TextFieldColors {
    val colors = LocalFlickColors.current
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = colors.onSurface,
        unfocusedTextColor = colors.onSurface,
        disabledTextColor = colors.onSurfaceDim,
        errorTextColor = colors.onSurface,
        focusedContainerColor = colors.surfaceRaised,
        unfocusedContainerColor = colors.surfaceRaised,
        disabledContainerColor = colors.surfaceRaised,
        errorContainerColor = colors.surfaceRaised,
        cursorColor = colors.primary,
        focusedBorderColor = colors.primary,
        unfocusedBorderColor = colors.onSurfaceFaint,
        focusedLabelColor = colors.primary,
        unfocusedLabelColor = colors.onSurfaceDim,
        // The dim ink rather than the faint one: a hint is still text, and `onSurfaceFaint`
        // on this fill is 3.60:1 in light — the floor it clears as a stroke is not the floor
        // it has to clear as a glyph.
        focusedPlaceholderColor = colors.onSurfaceDim,
        unfocusedPlaceholderColor = colors.onSurfaceDim,
    )
}
