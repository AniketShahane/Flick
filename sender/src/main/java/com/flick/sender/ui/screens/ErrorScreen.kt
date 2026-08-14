package com.flick.sender.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.flick.sender.R
import com.flick.sender.model.CastErrorKind
import com.flick.sender.model.CastFailure
import com.flick.sender.model.TerminalOrigin
import com.flick.sender.net.FlickController
import com.flick.sender.net.LinkVerdict
import com.flick.sender.ui.Format
import com.flick.sender.ui.components.StatusKind
import com.flick.sender.ui.components.StatusPill
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.PillShape
import com.flick.sender.ui.theme.PrimaryShadow
import com.flick.sender.ui.theme.flickRipple
import com.flick.sender.ui.theme.pressScale

/**
 * The distinct diagnoses S12 can wear. A face is chosen by what the receiver actually
 * reported, so a permanent, precisely-named fault never borrows the transient face —
 * and a code this build has never seen degrades to [GENERIC] rather than being guessed at.
 */
internal enum class CastErrorFace {
    UNSUPPORTED_CONTAINER, UNSUPPORTED_VIDEO, UNSUPPORTED_HDR, DAMAGED_FILE, UNREADABLE_SOURCE,
    DECODER_UNAVAILABLE, TV_APP_CLOSED, TV_BUSY, UPDATE_REQUIRED, SLOW_START, SLOW_LINK,
    NOT_SERVING, UNREACHABLE, NO_LAN, GENERIC,
    // Faults this phone raised about itself. Each one used to wear a face that named the
    // TV: an RST from this phone's own 8080, a bind that failed here, a server here that
    // answered with a refusal, and a file that stopped being readable here.
    PHONE_NOT_SERVING, SERVER_NOT_STARTED, PHONE_REFUSED, SERVER_BUSY, SOURCE_LOST,
    PHONE_SLOW_START, COMMAND_NOT_SENT,
    // Android refusing the foreground-service start, which the bind failure's face would
    // otherwise report as a port another app is holding.
    SERVER_NOT_ALLOWED,
    // What a failed dial actually proved, where it used to prove only "unreachable".
    RECEIVER_NOT_OPEN, ROUTER_BLOCKING, NO_ANSWER, LINK_DROPPED, TV_LOST_NETWORK,
    // Three more the residual "unreachable" was standing in for: a TV that answered and
    // closed the session, a TV that could not fetch from THIS phone over a link that is
    // provably alive, and a phone that left the network under its own cast.
    RECEIVER_TURNED_AWAY, MEDIA_PATH_BLOCKED, PHONE_LEFT_NETWORK,
    // A dial that fully succeeded and a write to this phone's storage that did not.
    PAIRING_NOT_SAVED,
}

/** The moves a face may offer. Each one is a thing this phone can actually do. */
internal enum class CastErrorAction { RETRY, OPEN_CONNECT, OPEN_WIFI_SETTINGS, PLAY_ON_PHONE, BACK_TO_LIBRARY }

internal data class CastErrorPresentation(
    val face: CastErrorFace,
    val primary: CastErrorAction,
    val secondary: CastErrorAction?,
)

/**
 * The two throughputs the slow-link body quotes, already formatted. Pre-formatted
 * because these numbers are the whole claim the face makes — both have to carry the
 * same unit spelling, which is [com.flick.sender.ui.Format.bitrate]'s job, not this
 * file's, and this way the copy stays a `when` over faces rather than over verdicts.
 */
internal data class LinkFacts(val required: String, val measured: String)

/**
 * The receiver's terminal code → the face and the moves the user is offered.
 *
 * Two rules this function exists to hold. [CastFailure.retryable] is the only thing that
 * may put [CastErrorAction.RETRY] on the screen — the controller has already resolved it
 * against a re-castable record, so a false here means retrying is not merely unlikely to
 * work, it is not implementable — and a permanent failure still has to offer something,
 * which is why every face carries a move that is not a retry.
 *
 * [canPlayOnPhone] is the same discipline applied to the escape hatch: with no remembered
 * item there is nothing to hand an external player, so the offer collapses to the library
 * rather than rendering a button that opens nothing.
 */
internal fun castErrorPresentation(
    kind: CastErrorKind,
    failure: CastFailure,
    canPlayOnPhone: Boolean,
    linkStarved: Boolean,
): CastErrorPresentation {
    val face = castErrorFace(failure.code, kind, linkStarved, failure.origin, failure.httpStatus)
    val (offered, alternative) = face.moves()
    val primary = offered.available(canPlayOnPhone)
    val secondary = alternative?.available(canPlayOnPhone)?.takeIf { it != primary }
    return if (failure.retryable) {
        // The retry takes the top slot and the permanent move steps down into the second:
        // "try again" is the better bet when the TV says so, never the only one on offer.
        CastErrorPresentation(face, CastErrorAction.RETRY, primary)
    } else {
        CastErrorPresentation(face, primary, secondary)
    }
}

/**
 * Codes are matched before kinds because a kind is a summary: `errorKind` folds every
 * media diagnosis into GENERIC, which is exactly how a container the TV cannot open came
 * to be presented as "something went wrong".
 *
 * [linkStarved] splits `startup_timeout` in two without touching the wire. The receiver
 * reports the same code either way — it timed out and cannot know why — and the phone,
 * which served the bytes, is the only side that measured the path. This is the technique
 * `unsupported_video_codec` already uses in the other direction: one code, resolved to
 * the face the evidence supports. Nothing here is a capability, so no release is coupled.
 *
 * [origin] separates the codes that mean opposite things depending on who raised them.
 * The inbound vocabulary is an allow-list, so `no_compatible_lan` and `unknown` reach
 * this function from this phone's own startup body AND from the receiver, and there is
 * nothing in the code itself to tell them apart.
 *
 * [httpStatus] reaches exactly one arm. A 503 from this phone's server is its transfer
 * cap doing its job, which is a different sentence from a request it refused outright.
 */
internal fun castErrorFace(
    code: String,
    kind: CastErrorKind,
    linkStarved: Boolean,
    origin: TerminalOrigin,
    httpStatus: Int?,
): CastErrorFace = when (code) {
    "unsupported_container" -> CastErrorFace.UNSUPPORTED_CONTAINER
    "unsupported_video_codec", "unsupported_video_format" -> CastErrorFace.UNSUPPORTED_VIDEO
    "unsupported_hdr_profile" -> CastErrorFace.UNSUPPORTED_HDR
    "malformed_media" -> CastErrorFace.DAMAGED_FILE
    "source_unavailable" -> CastErrorFace.UNREADABLE_SOURCE
    "decoder_init" -> CastErrorFace.DECODER_UNAVAILABLE
    "tv_backgrounded" -> CastErrorFace.TV_APP_CLOSED
    "active_cast_busy" -> CastErrorFace.TV_BUSY
    "update_required" -> CastErrorFace.UPDATE_REQUIRED
    "startup_timeout" -> if (linkStarved) CastErrorFace.SLOW_LINK else CastErrorFace.SLOW_START
    "sender_not_serving" -> CastErrorFace.PHONE_NOT_SERVING
    "media_bind_failed" -> CastErrorFace.SERVER_NOT_STARTED
    "media_start_refused" -> CastErrorFace.SERVER_NOT_ALLOWED
    "http_rejected" -> if (httpStatus == 503) CastErrorFace.SERVER_BUSY else CastErrorFace.PHONE_REFUSED
    "source_lost" -> CastErrorFace.SOURCE_LOST
    "source_start_timeout" -> CastErrorFace.PHONE_SLOW_START
    "load_not_sent" -> CastErrorFace.COMMAND_NOT_SENT
    "control_refused" -> CastErrorFace.RECEIVER_NOT_OPEN
    "control_no_route" -> CastErrorFace.ROUTER_BLOCKING
    "control_no_answer" -> CastErrorFace.NO_ANSWER
    "control_rejected" -> CastErrorFace.RECEIVER_TURNED_AWAY
    "pairing_store_failed" -> CastErrorFace.PAIRING_NOT_SAVED
    "no_lan_address", "control_no_network" -> CastErrorFace.NO_LAN
    // The TV could not fetch the film from THIS phone, and it said so over the control
    // socket — which is the proof that "can't reach the TV" is the wrong direction and
    // "rescan for it" the wrong move.
    "media_unreachable" -> CastErrorFace.MEDIA_PATH_BLOCKED
    // The receiver raises this only when the TV's own address went away mid-cast; this
    // phone raises the same code about itself before a byte leaves.
    "no_compatible_lan" ->
        if (origin == TerminalOrigin.RECEIVER) CastErrorFace.TV_LOST_NETWORK else CastErrorFace.NO_LAN
    // A link that carried tens of megabits a second a minute ago is not client isolation:
    // that fault cannot appear mid-stream, so this must not wear the unreachable face.
    // The second code is the same drop with one thing proved about it — this phone held
    // no LAN address when the terminal was raised, so it is the side that left.
    "control_disconnected" -> CastErrorFace.LINK_DROPPED
    "control_disconnected_no_lan" -> CastErrorFace.PHONE_LEFT_NETWORK
    else -> when (kind) {
        CastErrorKind.REACHABLE_NOT_SERVING -> CastErrorFace.NOT_SERVING
        CastErrorKind.UNREACHABLE -> CastErrorFace.UNREACHABLE
        CastErrorKind.NO_LAN -> CastErrorFace.NO_LAN
        CastErrorKind.GENERIC -> CastErrorFace.GENERIC
    }
}

/**
 * Primary and alternative for a failure that will not be retried. Every face names a move
 * the user can actually make from here, because the alternative to a useful action is not
 * a "Try again" that provably cannot succeed — it is a screen with nothing on it.
 */
private fun CastErrorFace.moves(): Pair<CastErrorAction, CastErrorAction?> = when (this) {
    // The TV read the file honestly and could not play it. The film is not broken and
    // this phone can decode it, so watching it here is the better offer than the library.
    CastErrorFace.UNSUPPORTED_CONTAINER,
    CastErrorFace.UNSUPPORTED_VIDEO,
    CastErrorFace.UNSUPPORTED_HDR,
    CastErrorFace.DECODER_UNAVAILABLE,
    -> CastErrorAction.PLAY_ON_PHONE to CastErrorAction.BACK_TO_LIBRARY
    // The one timeout where the phone is led with rather than the library: the file and
    // the TV are both fine and only the path between them was short, so the same bytes
    // decoded locally are the move that provably works right now.
    CastErrorFace.SLOW_LINK -> CastErrorAction.PLAY_ON_PHONE to CastErrorAction.BACK_TO_LIBRARY
    // Still offered, but not led with: a file the TV could not parse, or one that never
    // reached a frame, is a poor bet to send the user off to watch somewhere else.
    CastErrorFace.DAMAGED_FILE,
    CastErrorFace.SLOW_START,
    -> CastErrorAction.BACK_TO_LIBRARY to CastErrorAction.PLAY_ON_PHONE
    // Nothing this phone can do fixes these, and it must not pretend otherwise: the bytes
    // are unreadable here too, the TV belongs to another session, or nobody said why.
    CastErrorFace.UNREADABLE_SOURCE,
    CastErrorFace.TV_BUSY,
    CastErrorFace.UPDATE_REQUIRED,
    CastErrorFace.GENERIC,
    -> CastErrorAction.BACK_TO_LIBRARY to null
    // Every one of these is a fault on this phone, and none of them has a control that
    // fixes it. `castErrorPresentation` promotes RETRY into the top slot wherever the
    // failure is retryable, so they get "Try again" where it is honest and never a
    // button the copy would have to apologise for.
    //
    // SOURCE_LOST does not offer the phone: the same bytes are unreadable here too.
    CastErrorFace.PHONE_NOT_SERVING,
    CastErrorFace.SERVER_NOT_STARTED,
    CastErrorFace.PHONE_REFUSED,
    CastErrorFace.SERVER_BUSY,
    CastErrorFace.SOURCE_LOST,
    CastErrorFace.PHONE_SLOW_START,
    CastErrorFace.COMMAND_NOT_SENT,
    // Its own copy names the move: play the film again from a screen that is in front of
    // the user, which is the state Android refused this start for want of.
    CastErrorFace.SERVER_NOT_ALLOWED,
    CastErrorFace.LINK_DROPPED,
    CastErrorFace.TV_LOST_NETWORK,
    // Its copy indicts this phone, so it may not lead with a button about the TV. That
    // pairing — "This phone stopped sending the film" over "Wake the TV app" — is the
    // contradiction the phone-side faces above were split out to end.
    CastErrorFace.NOT_SERVING,
    CastErrorFace.PAIRING_NOT_SAVED,
    -> CastErrorAction.BACK_TO_LIBRARY to null
    CastErrorFace.TV_APP_CLOSED,
    CastErrorFace.UNREACHABLE,
    // Both of these end at a receiver that is not accepting connections, which is what
    // Connect is for — rescanning, or pairing the TV again.
    CastErrorFace.RECEIVER_NOT_OPEN,
    CastErrorFace.NO_ANSWER,
    // Flick is up on the TV and turned this session away, so the move is to pair it
    // again rather than to look for it.
    CastErrorFace.RECEIVER_TURNED_AWAY,
    -> CastErrorAction.OPEN_CONNECT to CastErrorAction.BACK_TO_LIBRARY
    // No Wi-Fi action. The fix is not on this phone, and sending the user to rejoin a
    // network they are provably already on is the shipped mistake this face exists to end.
    // The blocked media path is the same fault in the other direction, and the same rule.
    CastErrorFace.ROUTER_BLOCKING,
    CastErrorFace.MEDIA_PATH_BLOCKED,
    -> CastErrorAction.BACK_TO_LIBRARY to null
    CastErrorFace.NO_LAN,
    // The one lost link with a control behind it: this phone holds no LAN address, so
    // Wi-Fi settings acts on something the terminal actually observed.
    CastErrorFace.PHONE_LEFT_NETWORK,
    -> CastErrorAction.OPEN_WIFI_SETTINGS to CastErrorAction.BACK_TO_LIBRARY
}

private fun CastErrorAction.available(canPlayOnPhone: Boolean): CastErrorAction =
    if (this == CastErrorAction.PLAY_ON_PHONE && !canPlayOnPhone) CastErrorAction.BACK_TO_LIBRARY else this

/**
 * S12 — error faces. Diagnosis over apology: name the device, name the fault,
 * offer the one move that fixes it. The TV's own classification is carried all the way
 * here, so a container it cannot open says so — and offers no retry, because there is
 * nothing on either side of the LAN that a second attempt would change.
 */
@Composable
fun ErrorScreen(
    controller: FlickController,
    kind: CastErrorKind,
    failure: CastFailure,
    onOpenWifiSettings: () -> Unit,
) {
    val colors = LocalFlickColors.current
    val context = LocalContext.current
    val tv by controller.connectedTv.collectAsState()
    val failedItem by controller.failureItem.collectAsState()
    val tvName = tv?.name ?: stringResource(R.string.np_tv_generic)

    // The verdict the controller froze at the terminal, never the live one: teardown
    // resets the monitor, so by the time this screen composes the live flow reads Unknown
    // and every slow link would arrive here wearing the startup-timeout face.
    val failureVerdict by controller.failureLinkVerdict.collectAsState()
    val starved = failureVerdict as? LinkVerdict.Starved
    // Also frozen at the terminal: both addresses are gone by the time this composes.
    val sameSubnet by controller.failureSameSubnet.collectAsState()
    // Live, unlike the two above: this one is about what the phone is doing now.
    val stillChecking by controller.waitingOutBlock.collectAsState()

    // Resolved once, before anything is drawn. `available` already collapses the offer to
    // the library when there is nothing to hand over; this is the other half of the same
    // truth — a phone with no app claiming video/* has nothing to hand it TO, and the
    // shipped screen drew the button anyway and did nothing when it was pressed.
    val playIntent = remember(failedItem) {
        failedItem?.uri?.let { uri ->
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    val canPlayOnPhone = remember(playIntent) {
        playIntent != null && playIntent.resolveActivity(context.packageManager) != null
    }

    val presentation = castErrorPresentation(
        kind,
        failure,
        canPlayOnPhone = canPlayOnPhone,
        linkStarved = starved != null,
    )
    val face = presentation.face
    val amber = face.tone() == StatusKind.CAUTION
    val dotColor = if (amber) colors.caution else colors.trouble

    // The resolve above is a snapshot: a player uninstalled between it and the tap, or a
    // grant the target refuses, still lands here. The toast follows launchSupportCheckout's
    // shipped precedent — say what happened rather than leave a control that did nothing.
    val playHere: () -> Unit = {
        val intent = playIntent
        if (intent == null) {
            Toast.makeText(context, R.string.error_no_player_toast, Toast.LENGTH_SHORT).show()
        } else {
            runCatching { context.startActivity(intent) }
                .onSuccess {
                    // Handing the film to another player ends this cast attempt; coming
                    // back to a stale error face would be the app still arguing about it.
                    controller.back()
                }
                .onFailure {
                    Toast.makeText(context, R.string.error_no_player_toast, Toast.LENGTH_SHORT).show()
                }
        }
    }
    val perform: (CastErrorAction) -> Unit = { action ->
        when (action) {
            CastErrorAction.RETRY -> controller.retryCast()
            CastErrorAction.OPEN_CONNECT -> controller.openConnect()
            CastErrorAction.OPEN_WIFI_SETTINGS -> onOpenWifiSettings()
            CastErrorAction.PLAY_ON_PHONE -> playHere()
            CastErrorAction.BACK_TO_LIBRARY -> controller.back()
        }
    }

    val linkFacts = starved?.let {
        LinkFacts(required = Format.bitrate(it.requiredBps), measured = Format.bitrate(it.measuredBps))
    }
    val title = face.title(tvName, beforeStart = failure.beforeStart, sameSubnet = sameSubnet)
    val body = face.body(
        tvName,
        retryable = failure.retryable,
        link = linkFacts,
        origin = failure.origin,
        sameSubnet = sameSubnet,
        beforeStart = failure.beforeStart,
        stillChecking = stillChecking,
    )
    val pillText = face.pill(sameSubnet)
    val primary = presentation.primary
    val secondary = presentation.secondary
    val primaryLabel = actionLabel(primary, face)
    val secondaryLabel = secondary?.let { actionLabel(it, face) }

    val statusDescription = stringResource(R.string.a11y_cast_status, pillText)
    val primaryInteraction = remember { MutableInteractionSource() }
    val secondaryInteraction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                // The face is centred while it fits and scrolls when it does not. Two
                // actions at a large type scale outgrow a phone window, and the one that
                // overflows is the primary — the move this screen exists to offer.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 26.dp, vertical = 24.dp)
                // The pill owns the foot of this box, so the stack has to stop above it:
                // nothing else keeps the two apart.
                .padding(bottom = StatusPillBand),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Surplus spacers rather than a weighted face: weighting the face itself
            // would fix it to the room left over, so a face taller than the window could
            // never grow past it to be scrolled back. These collapse instead.
            Spacer(Modifier.weight(1f))
            TvEmblem(dotColor = dotColor, muted = amber)
            Spacer(Modifier.height(24.dp))
            Text(
                title,
                style = FlickText.headlineSmall.copy(color = colors.onSurface),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                body,
                style = FlickText.bodyLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurfaceDim,
                ),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = primaryLabel,
                style = FlickText.titleSmall.copy(color = colors.onPrimary),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .pressScale(primaryInteraction)
                    .shadow(
                        elevation = 14.dp,
                        shape = PillShape,
                        clip = false,
                        ambientColor = PrimaryShadow,
                        spotColor = PrimaryShadow,
                    )
                    .clip(PillShape)
                    .background(colors.primary)
                    .clickable(
                        interactionSource = primaryInteraction,
                        indication = flickRipple(colors.onPrimary),
                        role = Role.Button,
                        onClick = { perform(primary) },
                    )
                    .heightIn(min = 48.dp)
                    .padding(vertical = 19.dp),
            )
            if (secondaryLabel != null && secondary != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = secondaryLabel,
                    style = FlickText.labelMedium.copy(color = colors.onSurfaceDim),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(PillShape)
                        .clickable(
                            interactionSource = secondaryInteraction,
                            indication = flickRipple(colors.primary),
                            role = Role.Button,
                            onClick = { perform(secondary) },
                        )
                        .heightIn(min = 48.dp)
                        .padding(vertical = 15.dp),
                )
            }
            Spacer(Modifier.weight(1f))
        }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .semantics { contentDescription = statusDescription },
        ) {
            StatusPill(pillText, face.tone())
        }
    }
}

/**
 * Amber for the faults that are not the TV's doing — a file it read honestly and could
 * not decode, a session someone else owns. Crimson stays what it is in this palette: the
 * link or the receiver is down.
 */
private fun CastErrorFace.tone(): StatusKind = when (this) {
    CastErrorFace.UNSUPPORTED_CONTAINER,
    CastErrorFace.UNSUPPORTED_VIDEO,
    CastErrorFace.UNSUPPORTED_HDR,
    CastErrorFace.DAMAGED_FILE,
    CastErrorFace.UNREADABLE_SOURCE,
    CastErrorFace.DECODER_UNAVAILABLE,
    CastErrorFace.TV_BUSY,
    CastErrorFace.SLOW_START,
    CastErrorFace.SLOW_LINK,
    CastErrorFace.NOT_SERVING,
    CastErrorFace.PHONE_NOT_SERVING,
    CastErrorFace.SERVER_NOT_STARTED,
    CastErrorFace.PHONE_REFUSED,
    CastErrorFace.SERVER_BUSY,
    CastErrorFace.SOURCE_LOST,
    CastErrorFace.PHONE_SLOW_START,
    CastErrorFace.COMMAND_NOT_SENT,
    CastErrorFace.SERVER_NOT_ALLOWED,
    CastErrorFace.PAIRING_NOT_SAVED,
    -> StatusKind.CAUTION
    CastErrorFace.TV_APP_CLOSED,
    CastErrorFace.UPDATE_REQUIRED,
    CastErrorFace.UNREACHABLE,
    CastErrorFace.NO_LAN,
    CastErrorFace.GENERIC,
    CastErrorFace.RECEIVER_NOT_OPEN,
    CastErrorFace.ROUTER_BLOCKING,
    CastErrorFace.NO_ANSWER,
    CastErrorFace.LINK_DROPPED,
    CastErrorFace.TV_LOST_NETWORK,
    CastErrorFace.RECEIVER_TURNED_AWAY,
    CastErrorFace.MEDIA_PATH_BLOCKED,
    CastErrorFace.PHONE_LEFT_NETWORK,
    -> StatusKind.TROUBLE
}

/**
 * [beforeStart] and [sameSubnet] reach the two titles that would otherwise contradict the
 * body under them: one claims a film was mid-play, the other convicts the router the body
 * deliberately refuses to name.
 */
@Composable
private fun CastErrorFace.title(
    tvName: String,
    beforeStart: Boolean,
    sameSubnet: Boolean?,
): String = when (this) {
    CastErrorFace.UNSUPPORTED_CONTAINER -> stringResource(R.string.error_container_title)
    CastErrorFace.UNSUPPORTED_VIDEO -> stringResource(R.string.error_video_title)
    CastErrorFace.UNSUPPORTED_HDR -> stringResource(R.string.error_hdr_title)
    CastErrorFace.DAMAGED_FILE -> stringResource(R.string.error_damaged_title)
    CastErrorFace.UNREADABLE_SOURCE -> stringResource(R.string.error_source_title)
    CastErrorFace.DECODER_UNAVAILABLE -> stringResource(R.string.error_decoder_title)
    CastErrorFace.TV_APP_CLOSED -> stringResource(R.string.error_tvclosed_title)
    CastErrorFace.TV_BUSY -> stringResource(R.string.error_busy_title, tvName)
    CastErrorFace.UPDATE_REQUIRED -> stringResource(R.string.error_update_title)
    CastErrorFace.SLOW_START -> stringResource(R.string.error_timeout_title)
    CastErrorFace.SLOW_LINK -> stringResource(R.string.error_slowlink_title)
    CastErrorFace.NOT_SERVING -> stringResource(R.string.error_notserving_title)
    CastErrorFace.UNREACHABLE -> stringResource(R.string.error_unreachable_title, tvName)
    CastErrorFace.NO_LAN -> stringResource(R.string.error_nolan_title)
    CastErrorFace.GENERIC -> stringResource(R.string.error_generic_title)
    CastErrorFace.PHONE_NOT_SERVING -> stringResource(R.string.error_phoneserving_title)
    CastErrorFace.SERVER_NOT_STARTED -> stringResource(R.string.error_serverstart_title)
    CastErrorFace.PHONE_REFUSED -> stringResource(R.string.error_refused_title)
    CastErrorFace.SERVER_BUSY -> stringResource(R.string.error_serverbusy_title)
    CastErrorFace.SOURCE_LOST -> stringResource(R.string.error_sourcelost_title)
    CastErrorFace.PHONE_SLOW_START -> stringResource(R.string.error_phonestart_title)
    CastErrorFace.COMMAND_NOT_SENT -> stringResource(R.string.error_notsent_title)
    CastErrorFace.SERVER_NOT_ALLOWED -> stringResource(R.string.error_startrefused_title)
    CastErrorFace.RECEIVER_NOT_OPEN -> stringResource(R.string.error_refuseddial_title)
    CastErrorFace.ROUTER_BLOCKING -> if (sameSubnet == null) {
        stringResource(R.string.error_noroute_title_unproven)
    } else {
        stringResource(R.string.error_noroute_title)
    }
    CastErrorFace.NO_ANSWER -> stringResource(R.string.error_noanswer_title, tvName)
    CastErrorFace.LINK_DROPPED -> if (beforeStart) {
        stringResource(R.string.error_linkdropped_title_beforestart, tvName)
    } else {
        stringResource(R.string.error_linkdropped_title, tvName)
    }
    CastErrorFace.TV_LOST_NETWORK -> stringResource(R.string.error_tvnetwork_title)
    CastErrorFace.RECEIVER_TURNED_AWAY -> stringResource(R.string.error_turnedaway_title, tvName)
    CastErrorFace.MEDIA_PATH_BLOCKED -> stringResource(R.string.error_mediablocked_title, tvName)
    CastErrorFace.PHONE_LEFT_NETWORK -> stringResource(R.string.error_phonewifi_title)
    CastErrorFace.PAIRING_NOT_SAVED -> stringResource(R.string.error_pairstore_title)
}

/**
 * [retryable] reaches only the generic face, and only because the shipped generic body
 * ends in "Try again" — words that have no button under them on a failure that offers no
 * retry. Every other body describes what happened and promises nothing.
 *
 * [link] reaches only [CastErrorFace.SLOW_LINK], the one body that quotes measurements.
 * A null there cannot arrive through [castErrorFace] — the same `Starved` verdict decides
 * the face and carries both numbers — but the two are separate arguments, so the branch
 * falls back to the sentence that is true either way rather than to a formatted blank.
 *
 * [origin] and [sameSubnet] each reach the faces whose honest sentence depends on them. A
 * null [sameSubnet] is the phone having been unable to place itself next to the TV — no
 * address of its own, no Wi-Fi link to make the claim about, or a TV that answered no fresh
 * mDNS resolve and so is not provably on the network at all — which is the one state in
 * which neither "you are on the same network" nor its opposite may be claimed, so those
 * faces fall back to the copy that asserts neither.
 *
 * [beforeStart] is the phase, and it is evidence rather than a guess: the receiver encodes
 * it as the frame type, this phone cross-checks it against its own Active record, and
 * three bodies here would otherwise describe a film that never showed a frame as one that
 * "played fine up to that point".
 *
 * [stillChecking] is the one claim on this screen about the future, so it is read live from
 * the coordinator rather than frozen with the failure: the window that waits a router block
 * out closes on its own after twenty minutes, and the sentence promising it must go with it.
 */
@Composable
private fun CastErrorFace.body(
    tvName: String,
    retryable: Boolean,
    link: LinkFacts?,
    origin: TerminalOrigin,
    sameSubnet: Boolean?,
    beforeStart: Boolean,
    stillChecking: Boolean,
): String = when (this) {
    CastErrorFace.UNSUPPORTED_CONTAINER -> stringResource(R.string.error_container_body, tvName)
    CastErrorFace.UNSUPPORTED_VIDEO -> stringResource(R.string.error_video_body, tvName)
    CastErrorFace.UNSUPPORTED_HDR -> stringResource(R.string.error_hdr_body, tvName)
    CastErrorFace.DAMAGED_FILE -> stringResource(R.string.error_damaged_body, tvName)
    CastErrorFace.UNREADABLE_SOURCE -> stringResource(R.string.error_source_body)
    CastErrorFace.DECODER_UNAVAILABLE -> stringResource(R.string.error_decoder_body, tvName)
    CastErrorFace.TV_APP_CLOSED -> stringResource(R.string.error_tvclosed_body, tvName)
    CastErrorFace.TV_BUSY -> stringResource(R.string.error_busy_body)
    CastErrorFace.UPDATE_REQUIRED -> stringResource(R.string.error_update_body, tvName)
    CastErrorFace.SLOW_START -> stringResource(R.string.error_timeout_body, tvName)
    CastErrorFace.SLOW_LINK -> if (link != null) {
        stringResource(R.string.error_slowlink_body, tvName, link.required, link.measured)
    } else {
        stringResource(R.string.error_timeout_body, tvName)
    }
    CastErrorFace.NOT_SERVING -> stringResource(R.string.error_notserving_body, tvName)
    CastErrorFace.UNREACHABLE -> stringResource(R.string.error_unreachable_body)
    CastErrorFace.NO_LAN -> stringResource(R.string.error_nolan_body)
    CastErrorFace.GENERIC -> when {
        // Every sentence below claims the cast never started, so a failure that landed
        // after the first frame has to be answered before any of them are consulted.
        !beforeStart -> stringResource(R.string.error_generic_body_afterstart, tvName)
        retryable -> stringResource(R.string.error_generic_body)
        // "The TV didn't say why" implies the TV was consulted, on a path where this
        // phone stopped the cast before it was ever asked.
        origin == TerminalOrigin.LOCAL -> stringResource(R.string.error_generic_body_local, tvName)
        else -> stringResource(R.string.error_generic_body_permanent)
    }
    CastErrorFace.PHONE_NOT_SERVING -> stringResource(R.string.error_phoneserving_body, tvName)
    CastErrorFace.SERVER_NOT_STARTED -> stringResource(R.string.error_serverstart_body, tvName)
    CastErrorFace.PHONE_REFUSED -> stringResource(R.string.error_refused_body, tvName)
    CastErrorFace.SERVER_BUSY -> stringResource(R.string.error_serverbusy_body, tvName)
    CastErrorFace.SOURCE_LOST -> stringResource(R.string.error_sourcelost_body, tvName)
    CastErrorFace.PHONE_SLOW_START -> stringResource(R.string.error_phonestart_body, tvName)
    CastErrorFace.COMMAND_NOT_SENT -> stringResource(R.string.error_notsent_body, tvName)
    CastErrorFace.SERVER_NOT_ALLOWED -> stringResource(R.string.error_startrefused_body, tvName)
    CastErrorFace.RECEIVER_NOT_OPEN -> stringResource(R.string.error_refuseddial_body, tvName)
    CastErrorFace.ROUTER_BLOCKING -> when (sameSubnet) {
        true -> if (stillChecking) {
            stringResource(R.string.error_noroute_body_samesubnet_waiting, tvName)
        } else {
            stringResource(R.string.error_noroute_body_samesubnet, tvName)
        }
        // Two networks is a different fault from the transient pair-block the other two
        // bodies describe, and it does not clear itself — so it promises neither.
        false -> stringResource(R.string.error_noroute_body_offsubnet, tvName)
        // The phone could not place itself next to the TV — no address of its own, no
        // Wi-Fi link to make the claim about, or a TV that answered no fresh resolve — so
        // it may state only the half the kernel proved: something between the two refused
        // to forward.
        null -> if (stillChecking) {
            stringResource(R.string.error_noroute_body_waiting, tvName)
        } else {
            stringResource(R.string.error_noroute_body, tvName)
        }
    }
    CastErrorFace.NO_ANSWER -> if (sameSubnet == true) {
        stringResource(R.string.error_noanswer_body_samesubnet, tvName)
    } else {
        stringResource(R.string.error_noanswer_body)
    }
    CastErrorFace.LINK_DROPPED -> when {
        beforeStart -> stringResource(R.string.error_linkdropped_body_beforestart, tvName)
        // A link the monitor had already measured under the film's bitrate did not
        // "play fine up to that point", whatever the last frame looked like.
        link != null ->
            stringResource(R.string.error_linkdropped_body_starved, tvName, link.required, link.measured)
        else -> stringResource(R.string.error_linkdropped_body, tvName)
    }
    CastErrorFace.TV_LOST_NETWORK -> if (beforeStart) {
        stringResource(R.string.error_tvnetwork_body_beforestart, tvName)
    } else {
        stringResource(R.string.error_tvnetwork_body, tvName)
    }
    CastErrorFace.RECEIVER_TURNED_AWAY -> stringResource(R.string.error_turnedaway_body, tvName)
    CastErrorFace.MEDIA_PATH_BLOCKED -> when (sameSubnet) {
        true -> stringResource(R.string.error_mediablocked_body_samesubnet, tvName)
        false -> stringResource(R.string.error_mediablocked_body_offsubnet, tvName)
        null -> stringResource(R.string.error_mediablocked_body, tvName)
    }
    CastErrorFace.PHONE_LEFT_NETWORK -> stringResource(R.string.error_phonewifi_body, tvName)
    CastErrorFace.PAIRING_NOT_SAVED -> stringResource(R.string.error_pairstore_body, tvName)
}

/** [sameSubnet] reaches the one pill that would otherwise name a culprit its body will not. */
@Composable
private fun CastErrorFace.pill(sameSubnet: Boolean?): String = when (this) {
    CastErrorFace.UNSUPPORTED_CONTAINER -> stringResource(R.string.error_container_pill)
    CastErrorFace.UNSUPPORTED_VIDEO -> stringResource(R.string.error_video_pill)
    CastErrorFace.UNSUPPORTED_HDR -> stringResource(R.string.error_hdr_pill)
    CastErrorFace.DAMAGED_FILE -> stringResource(R.string.error_damaged_pill)
    CastErrorFace.UNREADABLE_SOURCE -> stringResource(R.string.error_source_pill)
    CastErrorFace.DECODER_UNAVAILABLE -> stringResource(R.string.error_decoder_pill)
    CastErrorFace.TV_APP_CLOSED -> stringResource(R.string.error_tvclosed_pill)
    CastErrorFace.TV_BUSY -> stringResource(R.string.error_busy_pill)
    CastErrorFace.UPDATE_REQUIRED -> stringResource(R.string.error_update_pill)
    CastErrorFace.SLOW_START -> stringResource(R.string.error_timeout_pill)
    CastErrorFace.SLOW_LINK -> stringResource(R.string.error_slowlink_pill)
    CastErrorFace.NOT_SERVING -> stringResource(R.string.error_notserving_pill)
    CastErrorFace.UNREACHABLE -> stringResource(R.string.error_unreachable_pill)
    CastErrorFace.NO_LAN -> stringResource(R.string.error_nolan_pill)
    CastErrorFace.GENERIC -> stringResource(R.string.error_generic_pill)
    CastErrorFace.PHONE_NOT_SERVING -> stringResource(R.string.error_phoneserving_pill)
    CastErrorFace.SERVER_NOT_STARTED -> stringResource(R.string.error_serverstart_pill)
    CastErrorFace.PHONE_REFUSED -> stringResource(R.string.error_refused_pill)
    CastErrorFace.SERVER_BUSY -> stringResource(R.string.error_serverbusy_pill)
    CastErrorFace.SOURCE_LOST -> stringResource(R.string.error_sourcelost_pill)
    CastErrorFace.PHONE_SLOW_START -> stringResource(R.string.error_phonestart_pill)
    CastErrorFace.COMMAND_NOT_SENT -> stringResource(R.string.error_notsent_pill)
    CastErrorFace.SERVER_NOT_ALLOWED -> stringResource(R.string.error_startrefused_pill)
    CastErrorFace.RECEIVER_NOT_OPEN -> stringResource(R.string.error_refuseddial_pill)
    CastErrorFace.ROUTER_BLOCKING -> if (sameSubnet == null) {
        stringResource(R.string.error_noroute_pill_unproven)
    } else {
        stringResource(R.string.error_noroute_pill)
    }
    CastErrorFace.NO_ANSWER -> stringResource(R.string.error_noanswer_pill)
    CastErrorFace.LINK_DROPPED -> stringResource(R.string.error_linkdropped_pill)
    CastErrorFace.TV_LOST_NETWORK -> stringResource(R.string.error_tvnetwork_pill)
    CastErrorFace.RECEIVER_TURNED_AWAY -> stringResource(R.string.error_turnedaway_pill)
    CastErrorFace.MEDIA_PATH_BLOCKED -> stringResource(R.string.error_mediablocked_pill)
    CastErrorFace.PHONE_LEFT_NETWORK -> stringResource(R.string.error_phonewifi_pill)
    CastErrorFace.PAIRING_NOT_SAVED -> stringResource(R.string.error_pairstore_pill)
}

/**
 * One label per action, except where the shipped per-face copy is more specific than any
 * shared wording could be: waking a receiver that answered and rescanning for one that
 * did not are the same handler and two different requests.
 */
@Composable
private fun actionLabel(action: CastErrorAction, face: CastErrorFace): String = when (action) {
    CastErrorAction.RETRY -> stringResource(R.string.error_generic_primary)
    CastErrorAction.OPEN_CONNECT -> when (face) {
        // Nothing answered, so there is nothing to wake — the honest offer is to look again.
        CastErrorFace.UNREACHABLE, CastErrorFace.NO_ANSWER ->
            stringResource(R.string.error_unreachable_primary)
        // Flick answered and closed the session, so waking it names something already
        // awake and rescanning looks for something already found.
        CastErrorFace.RECEIVER_TURNED_AWAY -> stringResource(R.string.error_unreachable_secondary)
        else -> stringResource(R.string.error_reachable_primary)
    }
    CastErrorAction.OPEN_WIFI_SETTINGS -> stringResource(R.string.error_nolan_primary)
    CastErrorAction.PLAY_ON_PHONE -> stringResource(R.string.error_action_play_here)
    CastErrorAction.BACK_TO_LIBRARY -> stringResource(R.string.error_reachable_secondary_library)
}

// The band the bottom-anchored status pill claims: its own height at the largest type
// scale, plus the 24 dp it floats above the navigation bar. Content stops here.
internal val StatusPillBand = 78.dp

/** An outlined TV with a status lamp — the fault, drawn rather than apologised for. */
@Composable
private fun TvEmblem(dotColor: Color, muted: Boolean) {
    val colors = LocalFlickColors.current
    Box {
        Box(
            Modifier
                .size(width = 96.dp, height = 60.dp)
                .clip(RoundedCornerShape(FlickCorners.backBtn))
                .background(colors.surfaceTonal)
                .border(
                    width = 2.5.dp,
                    color = if (muted) colors.outline else colors.trouble.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(FlickCorners.backBtn),
                ),
        )
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 2.dp, end = 2.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(colors.canvas)
                .padding(3.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
    }
}
