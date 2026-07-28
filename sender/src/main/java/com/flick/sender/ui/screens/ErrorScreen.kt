package com.flick.sender.ui.screens

import android.content.Intent
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
import com.flick.sender.net.FlickController
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
    DECODER_UNAVAILABLE, TV_APP_CLOSED, TV_BUSY, UPDATE_REQUIRED, SLOW_START,
    NOT_SERVING, UNREACHABLE, NO_LAN, GENERIC,
}

/** The moves a face may offer. Each one is a thing this phone can actually do. */
internal enum class CastErrorAction { RETRY, OPEN_CONNECT, OPEN_WIFI_SETTINGS, PLAY_ON_PHONE, BACK_TO_LIBRARY }

internal data class CastErrorPresentation(
    val face: CastErrorFace,
    val primary: CastErrorAction,
    val secondary: CastErrorAction?,
)

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
): CastErrorPresentation {
    val face = castErrorFace(failure.code, kind)
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
 */
internal fun castErrorFace(code: String, kind: CastErrorKind): CastErrorFace = when (code) {
    "unsupported_container" -> CastErrorFace.UNSUPPORTED_CONTAINER
    "unsupported_video_codec", "unsupported_video_format" -> CastErrorFace.UNSUPPORTED_VIDEO
    "unsupported_hdr_profile" -> CastErrorFace.UNSUPPORTED_HDR
    "malformed_media" -> CastErrorFace.DAMAGED_FILE
    "source_unavailable" -> CastErrorFace.UNREADABLE_SOURCE
    "decoder_init" -> CastErrorFace.DECODER_UNAVAILABLE
    "tv_backgrounded" -> CastErrorFace.TV_APP_CLOSED
    "active_cast_busy" -> CastErrorFace.TV_BUSY
    "update_required" -> CastErrorFace.UPDATE_REQUIRED
    "startup_timeout" -> CastErrorFace.SLOW_START
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
    CastErrorFace.TV_APP_CLOSED,
    CastErrorFace.NOT_SERVING,
    CastErrorFace.UNREACHABLE,
    -> CastErrorAction.OPEN_CONNECT to CastErrorAction.BACK_TO_LIBRARY
    CastErrorFace.NO_LAN -> CastErrorAction.OPEN_WIFI_SETTINGS to CastErrorAction.BACK_TO_LIBRARY
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

    val presentation = castErrorPresentation(kind, failure, canPlayOnPhone = failedItem != null)
    val face = presentation.face
    val amber = face.tone() == StatusKind.CAUTION
    val dotColor = if (amber) colors.caution else colors.trouble

    val playHere: () -> Unit = {
        val uri = failedItem?.uri
        if (uri != null) {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "video/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                )
            }.onSuccess {
                // Handing the film to another player ends this cast attempt; coming back
                // to a stale error face would be the app still arguing about it.
                controller.back()
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

    val title = face.title(tvName)
    val body = face.body(tvName, retryable = failure.retryable)
    val pillText = face.pill()
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
    CastErrorFace.NOT_SERVING,
    -> StatusKind.CAUTION
    CastErrorFace.TV_APP_CLOSED,
    CastErrorFace.UPDATE_REQUIRED,
    CastErrorFace.UNREACHABLE,
    CastErrorFace.NO_LAN,
    CastErrorFace.GENERIC,
    -> StatusKind.TROUBLE
}

@Composable
private fun CastErrorFace.title(tvName: String): String = when (this) {
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
    CastErrorFace.NOT_SERVING -> stringResource(R.string.error_reachable_title)
    CastErrorFace.UNREACHABLE -> stringResource(R.string.error_unreachable_title, tvName)
    CastErrorFace.NO_LAN -> stringResource(R.string.error_nolan_title)
    CastErrorFace.GENERIC -> stringResource(R.string.error_generic_title)
}

/**
 * [retryable] reaches only the generic face, and only because the shipped generic body
 * ends in "Try again" — words that have no button under them on a failure that offers no
 * retry. Every other body describes what happened and promises nothing.
 */
@Composable
private fun CastErrorFace.body(tvName: String, retryable: Boolean): String = when (this) {
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
    CastErrorFace.NOT_SERVING -> stringResource(R.string.error_reachable_body, tvName)
    CastErrorFace.UNREACHABLE -> stringResource(R.string.error_unreachable_body)
    CastErrorFace.NO_LAN -> stringResource(R.string.error_nolan_body)
    CastErrorFace.GENERIC -> if (retryable) {
        stringResource(R.string.error_generic_body)
    } else {
        stringResource(R.string.error_generic_body_permanent)
    }
}

@Composable
private fun CastErrorFace.pill(): String = when (this) {
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
    CastErrorFace.NOT_SERVING -> stringResource(R.string.error_reachable_pill)
    CastErrorFace.UNREACHABLE -> stringResource(R.string.error_unreachable_pill)
    CastErrorFace.NO_LAN -> stringResource(R.string.error_nolan_pill)
    CastErrorFace.GENERIC -> stringResource(R.string.error_generic_pill)
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
        CastErrorFace.UNREACHABLE -> stringResource(R.string.error_unreachable_primary)
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
