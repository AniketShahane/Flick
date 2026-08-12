package com.flick.receiver.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.session.ReceiverErrorFace
import com.flick.receiver.ui.components.FlickTvButton
import com.flick.receiver.ui.components.GlassPanel
import com.flick.receiver.ui.components.GlassPanelTone
import com.flick.receiver.ui.components.LiveDot
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickDimens
import com.flick.receiver.ui.theme.FlickMotion
import com.flick.receiver.ui.theme.FlickShape
import com.flick.receiver.ui.theme.FlickSpace
import com.flick.receiver.ui.theme.FlickType
import com.flick.receiver.ui.theme.LocalReducedMotion
import com.flick.receiver.ui.theme.errorAmbientBackground
import com.flick.receiver.ui.theme.tvOverscanSafeArea

/**
 * T9 · Errors, calm and specific — one sentence per [ReceiverErrorFace].
 *
 * Amber says nothing is broken and this film cannot play here; crimson says the link
 * or a device is down. The screen used to reduce a three-value diagnosis to a Boolean,
 * so every decoder, codec, HDR, container and AudioTrack failure rendered as "Your
 * phone stopped serving" over a phone that was serving perfectly.
 *
 * There is exactly ONE action, and it carries the primary treatment. Retrying a
 * cast is not the TV's to offer: every retry needs a fresh castId and a fresh
 * media token, both minted by the sender (control-channel.md §6/§8 — "retry is
 * user initiated"), and the terminal frame this screen was raised by has already
 * put that affordance on the phone. A second, de-emphasised key here would be an
 * offer the receiver cannot honour.
 *
 * NOTHING on this screen moves after the single fade that brings it in. A
 * diagnosed fault is presented still: a card that springs into place and a status
 * light that breathes over it read as an app being playful about a failure, and
 * the fade is on an effects spring precisely so the arrival cannot overshoot.
 */
@Composable
fun ErrorScreen(
    face: ReceiverErrorFace,
    deviceLabel: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Whether the film had reached its first frame. Read by every face whose copy —
     * or whose action — differs before and after that moment; see [errorCopyFor].
     */
    beforeReady: Boolean = true,
) {
    val actionFocus = remember { FocusRequester() }
    LaunchedEffect(face) { runCatching { actionFocus.requestFocus() } }

    // The whole entrance: one fade, no geometry. Read inside the layer block so
    // even that costs no recomposition.
    val reducedMotion = LocalReducedMotion.current
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val fade = animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = if (reducedMotion) snap() else FlickMotion.stateEffects(),
        label = "errorCardFade",
    )

    val accent = if (face.blamesTheLink()) FlickColor.Trouble else FlickColor.Caution
    val device = deviceLabel ?: stringResource(R.string.device_fallback)
    val copy = errorCopyFor(face, beforeReady, device)
    val actionLabel = stringResource(
        if (face.endsTheSession(beforeReady)) R.string.error_end_session else R.string.error_back_to_standby,
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .errorAmbientBackground(accent)
            // After the wash, so the gradient still runs to the panel edge while
            // the card inside it stops at the overscan inset.
            .tvOverscanSafeArea(),
        contentAlignment = Alignment.Center,
    ) {
        GlassPanel(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .graphicsLayer { alpha = fade.value },
            shape = FlickShape.Hero,
            tone = GlassPanelTone.Panel,
            contentPadding = FlickDimens.PanelPadding,
            verticalArrangement = Arrangement.spacedBy(FlickSpace.Md),
            horizontalAlignment = Alignment.CenterHorizontally,
            animateEntrance = false,
        ) {
            // The emblem names the party the copy names. A phone with a fault light
            // over "This TV can't decode this video" would be the same misattribution
            // the faces exist to end.
            if (face.namesThePhone()) PhoneGlyph(accent = accent) else TvGlyph(accent = accent)
            Text(
                text = copy.title,
                style = FlickType.display(sizeSp = 27),
                color = FlickColor.OnSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = copy.detail,
                style = FlickType.body(sizeSp = 18),
                color = FlickColor.OnSurfaceDim,
                textAlign = TextAlign.Center,
            )
            FlickTvButton(
                onClick = onDismiss,
                focusRequester = actionFocus,
                containerColor = FlickColor.Spark,
                borderColor = Color.Transparent,
                ringColor = FlickColor.FocusRingOnSpark,
                contentPadding = FlickDimens.ControlPadding,
            ) {
                Text(
                    text = actionLabel,
                    style = FlickType.body(sizeSp = 16, weight = FontWeight.Bold),
                    color = FlickColor.OnSpark,
                )
            }
        }
    }
}

/** The two lines a face puts on screen, resolved together so neither can be orphaned. */
internal data class ErrorCopy(val title: String, val detail: String)

/**
 * The face's own sentence.
 *
 * [beforeReady] is read by every face whose evidence changes with it, which is most of
 * the ones reachable on both sides of the first frame: the decoder reclaim is
 * phase-blind, the audio-output rebuild is latched for the process so its second refusal
 * can land inside a later film's startup, and the probe raises the serving and the
 * unreachable faces before anything has been sent. One sentence for those would assert a
 * picture, a playing film, or a film that stopped, that never existed. The faces that
 * resolve a single string are the ones claiming the same thing either side of that
 * moment, and handing the flag to those would only invite copy that does not.
 */
@Composable
internal fun errorCopyFor(
    face: ReceiverErrorFace,
    beforeReady: Boolean,
    device: String,
): ErrorCopy = when (face) {
    ReceiverErrorFace.VIDEO_CODEC_UNSUPPORTED -> ErrorCopy(
        stringResource(R.string.error_video_codec_title),
        stringResource(R.string.error_video_codec_detail),
    )
    ReceiverErrorFace.VIDEO_FORMAT_UNSUPPORTED -> ErrorCopy(
        stringResource(R.string.error_video_format_title),
        stringResource(R.string.error_video_format_detail),
    )
    ReceiverErrorFace.HDR_PROFILE_UNSUPPORTED -> ErrorCopy(
        stringResource(R.string.error_hdr_title),
        stringResource(R.string.error_hdr_detail),
    )
    ReceiverErrorFace.CONTAINER_UNSUPPORTED -> ErrorCopy(
        stringResource(R.string.error_container_title),
        stringResource(R.string.error_container_detail),
    )
    ReceiverErrorFace.MEDIA_MALFORMED -> ErrorCopy(
        stringResource(R.string.error_malformed_title),
        stringResource(R.string.error_malformed_detail),
    )
    ReceiverErrorFace.DECODER_UNAVAILABLE -> ErrorCopy(
        stringResource(R.string.error_decoder_title),
        stringResource(R.string.error_decoder_detail),
    )
    ReceiverErrorFace.DECODER_TAKEN -> ErrorCopy(
        stringResource(R.string.error_decoder_taken_title),
        stringResource(
            if (beforeReady) {
                R.string.error_decoder_taken_detail_beforestart
            } else {
                R.string.error_decoder_taken_detail
            },
        ),
    )
    ReceiverErrorFace.AUDIO_OUTPUT_REFUSED -> ErrorCopy(
        stringResource(R.string.error_audio_output_title),
        stringResource(
            if (beforeReady) {
                R.string.error_audio_output_detail_beforestart
            } else {
                R.string.error_audio_output_detail
            },
        ),
    )
    ReceiverErrorFace.STARTUP_TIMEOUT -> ErrorCopy(
        stringResource(R.string.error_startup_title),
        stringResource(R.string.error_startup_detail),
    )
    ReceiverErrorFace.SENDER_REFUSED -> ErrorCopy(
        stringResource(R.string.error_refused_title),
        stringResource(R.string.error_refused_detail, device),
    )
    ReceiverErrorFace.SENDER_NOT_SERVING -> ErrorCopy(
        stringResource(R.string.error_not_serving_title),
        stringResource(
            if (beforeReady) {
                R.string.error_not_serving_detail_beforestart
            } else {
                R.string.error_not_serving_detail
            },
            device,
        ),
    )
    ReceiverErrorFace.PHONE_UNREACHABLE -> ErrorCopy(
        stringResource(
            if (beforeReady) R.string.error_unreachable_title_beforestart else R.string.error_unreachable_title,
        ),
        stringResource(
            if (beforeReady) R.string.error_unreachable_detail_beforestart else R.string.error_unreachable_detail,
            device,
        ),
    )
    ReceiverErrorFace.LINK_LOST -> ErrorCopy(
        stringResource(R.string.error_link_lost_title),
        stringResource(
            if (beforeReady) R.string.error_link_lost_detail_beforestart else R.string.error_link_lost_detail,
            device,
        ),
    )
    ReceiverErrorFace.TV_NETWORK_CHANGED -> ErrorCopy(
        stringResource(R.string.error_tv_network_title),
        stringResource(
            if (beforeReady) {
                R.string.error_tv_network_detail_beforestart
            } else {
                R.string.error_tv_network_detail
            },
            device,
        ),
    )
    ReceiverErrorFace.PICTURE_STOPPED -> ErrorCopy(
        stringResource(R.string.error_picture_stopped_title),
        stringResource(R.string.error_picture_stopped_detail),
    )
    ReceiverErrorFace.PLAYBACK_STOPPED -> ErrorCopy(
        stringResource(R.string.error_stopped_title),
        stringResource(R.string.error_stopped_detail),
    )
}

/** Crimson: the link or a device is down. Amber: this film cannot play here. */
internal fun ReceiverErrorFace.blamesTheLink(): Boolean = when (this) {
    ReceiverErrorFace.PHONE_UNREACHABLE,
    ReceiverErrorFace.LINK_LOST,
    ReceiverErrorFace.TV_NETWORK_CHANGED,
    ReceiverErrorFace.PICTURE_STOPPED,
    ReceiverErrorFace.PLAYBACK_STOPPED -> true
    else -> false
}

/**
 * Whether the one action ends the session rather than returning to standby. Only the
 * two faces whose phone is not answering: there is nothing left to end for the rest,
 * and calling it "End session" would imply one is still running.
 *
 * Before the first frame the unreachable face is the pre-flight probe's, and that runs
 * over a control socket that is provably alive — the only thing that failed is the file
 * server. Offering to end a link that is answering is the same overclaim the
 * before-start copy exists to undo.
 */
internal fun ReceiverErrorFace.endsTheSession(beforeReady: Boolean): Boolean = when (this) {
    ReceiverErrorFace.PHONE_UNREACHABLE -> !beforeReady
    ReceiverErrorFace.LINK_LOST -> true
    else -> false
}

/** Whether the sentence is about the phone or the path to it, rather than this TV. */
internal fun ReceiverErrorFace.namesThePhone(): Boolean = when (this) {
    ReceiverErrorFace.PHONE_UNREACHABLE,
    ReceiverErrorFace.LINK_LOST,
    ReceiverErrorFace.SENDER_REFUSED,
    ReceiverErrorFace.SENDER_NOT_SERVING -> true
    else -> false
}

/**
 * The phone that went quiet: a dim handset with an accent status light. The light
 * is held, never breathing — the fault is the message, and this screen is mounted
 * by instrumentation tests that wait for the frame clock to settle.
 */
@Composable
private fun PhoneGlyph(accent: Color) {
    FaultGlyph(accent = accent, width = 37.dp, height = 56.dp)
}

/** The same emblem in the panel's proportions, for a fault this TV owns. */
@Composable
private fun TvGlyph(accent: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FaultGlyph(accent = accent, width = 62.dp, height = 40.dp)
        Box(
            modifier = Modifier
                .size(width = 20.dp, height = 4.dp)
                .background(FlickColor.Outline, FlickShape.Sm),
        )
    }
}

@Composable
private fun FaultGlyph(accent: Color, width: Dp, height: Dp) {
    Box(contentAlignment = Alignment.TopEnd) {
        Box(
            modifier = Modifier
                .size(width = width, height = height)
                .background(FlickColor.ControlFill, FlickShape.Md)
                // Not a hairline: this stroke is the device, so it holds its
                // weight while the form around it comes down.
                .border(2.dp, FlickColor.Outline, FlickShape.Md),
        )
        Box(
            modifier = Modifier
                .offset(x = 6.dp, y = (-6).dp)
                .size(18.dp)
                .drawBehind { drawCircle(FlickColor.CanvasPair) },
            contentAlignment = Alignment.Center,
        ) {
            LiveDot(color = accent, size = 10.dp)
        }
    }
}
