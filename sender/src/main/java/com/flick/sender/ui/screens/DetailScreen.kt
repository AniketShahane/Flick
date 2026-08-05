package com.flick.sender.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.flick.sender.R
import com.flick.sender.media.MediaProbe
import com.flick.sender.model.HdrType
import com.flick.sender.model.MediaItem
import com.flick.sender.net.CastStartState
import com.flick.sender.net.FlickController
import com.flick.sender.net.LinkCapacityPolicy
import com.flick.sender.net.PreCastLinkAdvisory
import com.flick.sender.media.PlaybackProgressState
import com.flick.sender.ui.Format
import com.flick.sender.ui.displayName
import com.flick.sender.ui.components.AdvisoryCard
import com.flick.sender.ui.components.AdvisoryTone
import com.flick.sender.ui.components.FlickGesture
import com.flick.sender.ui.components.flickSharedFrame
import com.flick.sender.ui.components.posterKey
import com.flick.sender.ui.components.rememberDetailVideoFrameRequest
import com.flick.sender.ui.components.rememberVideoImageLoader
import com.flick.sender.ui.theme.CinemaDeep
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.PillShape
import com.flick.sender.ui.theme.PrimaryShadow
import com.flick.sender.ui.theme.SheetShape
import com.flick.sender.ui.theme.TileShadow
import com.flick.sender.ui.theme.flickRipple
import com.flick.sender.ui.theme.pressScale
import com.flick.sender.ui.theme.rememberReduceMotion

/**
 * S4 — detail / "cast this". A risen sheet over the video's own frame: honest
 * badges, the direct-play promise, one blue CTA. Back and the scrim both route
 * through [FlickController.back], so the shell's own BackHandler stays the only
 * one on this route.
 *
 * The backdrop is not a new image — it is the tile the user just tapped, expanded.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    controller: FlickController,
    item: MediaItem,
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    val colors = LocalFlickColors.current
    val context = LocalContext.current
    val connectedTv by controller.connectedTv.collectAsState()
    val castStart by controller.castStart.collectAsState()
    val playbackProgress by controller.playbackProgress.collectAsState()
    val unplayable by controller.unplayableFiles.collectAsState()
    val selectedSubtitle by controller.selectedSubtitle.collectAsState()
    val subtitleOwnerKey by controller.subtitleOwnerKey.collectAsState()
    val imageLoader = rememberVideoImageLoader()
    val rise = rememberSheetRise()
    // Geometry and opacity on separate clocks: the sheet's own scrim is fixed here, so its
    // transparency is the only thing standing between the frame and the copy over it.
    val fade = rememberSheetFade()
    val displayName = item.displayName()
    val tvName = connectedTv?.name ?: stringResource(R.string.np_tv_generic)
    val castDescription = stringResource(R.string.a11y_cast_video, displayName, tvName)
    val startOverDescription = stringResource(R.string.a11y_start_video_over, displayName, tvName)
    val playHereDescription = stringResource(R.string.a11y_play_video_here, displayName)
    val progressReady = playbackProgress is PlaybackProgressState.Ready
    val resumeMs = remember(item, playbackProgress) {
        controller.resumePosition(item, playbackProgress)
    }
    val resumeTime = resumeMs?.let(Format::timecode)
    val primaryDescription = resumeTime?.let {
        stringResource(R.string.a11y_resume_video, displayName, it, tvName)
    } ?: castDescription
    val dismissDescription = stringResource(R.string.a11y_back_to_library)
    // Null until the probe answers. Starting at NONE would print "SDR" — a verdict — for
    // every file in the window between opening this sheet and reading its container.
    val hdr by produceState<HdrType?>(initialValue = null, item.uri) {
        value = MediaProbe.detectHdr(context, item.uri)
    }
    val refusal = unplayable[item.uriKey]
    val subtitle = selectedSubtitle?.takeIf { showAttachedSubtitle(subtitleOwnerKey, item.uriKey) }

    // What this file needs, against what this phone's link realistically carries. Polled
    // rather than read once, so the card clears by itself when the user takes its advice:
    // "Switch network" leaves for the system Wi-Fi list and comes back to this same
    // composition, and a one-shot read would still be warning about the old link.
    val signal = rememberSignalState()
    val requiredBps = remember(item.sizeBytes, item.durationMs) {
        LinkCapacityPolicy.requiredBitrateBps(item.sizeBytes, item.durationMs)
    }
    // Structural, so the RSSI moving one dBm — which it does on every poll — cannot
    // recompose the sheet behind the flying poster. Unwrapped here rather than read in the
    // branch below because raising or lowering the card IS a rebuild of the sheet's body.
    val advisory = remember(signal, requiredBps) {
        derivedStateOf(structuralEqualityPolicy()) {
            LinkCapacityPolicy.preCastAdvisory(requiredBps, signal.value.link)
        }
    }.value

    val request = rememberDetailVideoFrameRequest(item)
    val scrimSource = remember { MutableInteractionSource() }
    val sheetSource = remember { MutableInteractionSource() }
    val playHereSource = remember { MutableInteractionSource() }
    val startOverSource = remember { MutableInteractionSource() }

    // Cinematic rather than the pale canvas: this route already declares a dark
    // backdrop to the system bars, and the frame arrives out of darkness.
    Box(Modifier.fillMaxSize().background(CinemaDeep)) {
        AsyncImage(
            model = request,
            contentDescription = null,
            imageLoader = imageLoader,
            contentScale = ContentScale.Crop,
            // In place rather than in the shared overlay: the sheet has to stay above
            // the frame while both are arriving.
            modifier = Modifier
                .flickSharedFrame(
                    sharedScope = sharedScope,
                    animatedScope = animatedScope,
                    key = posterKey(item.id),
                    renderInOverlay = false,
                )
                .fillMaxSize(),
        )
        // The scrim and the sheet body below it are a dismiss target and a click
        // consumer, not controls: a state layer on either would advertise a tap target
        // that isn't one, so both stay unindicated. It is deliberately not part of the
        // shared frame: a fixed scrim means the frame flies under one constant wash.
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.scrim)
                .semantics { contentDescription = dismissDescription }
                .clickable(interactionSource = scrimSource, indication = null) { controller.back() },
        )

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .sheetRiseTransform(rise, fade)
                .clip(SheetShape)
                .background(colors.surface)
                .clickable(interactionSource = sheetSource, indication = null, onClick = {})
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 24.dp),
        ) {
            SheetGrabber()
            Spacer(Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 122.dp, height = 78.dp)
                        .shadow(
                            elevation = 14.dp,
                            shape = RoundedCornerShape(FlickCorners.detailPoster),
                            clip = false,
                            ambientColor = TileShadow,
                            spotColor = TileShadow,
                        )
                        .clip(RoundedCornerShape(FlickCorners.detailPoster))
                        .background(colors.surfaceRaisedAlt),
                )
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = displayName,
                        style = FlickText.titleLarge.copy(color = colors.onSurface),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = detailMeta(item),
                        style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(17.dp))
            // Source-fact chips reflow on narrow or large-font windows instead of
            // competing for one fixed row and clipping the resolution or file size.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                // Resolution, dynamic range and size are the only source facts the
                // phone can read; codec and frame rate never leave MediaStore. A fact it
                // did not read takes the outlined chip: same seat, same type, no claim.
                DetailChip(
                    text = resolutionText(item),
                    containerColor = if (item.knowsResolution) colors.inverseSurface else Color.Transparent,
                    contentColor = if (item.knowsResolution) colors.onInverseSurface else colors.onSurfaceDim,
                    outlineColor = colors.outline.takeIf { !item.knowsResolution },
                )
                DetailChip(
                    text = hdrChipLabel(hdr),
                    containerColor = if (hdr != null) colors.primaryContainer else Color.Transparent,
                    contentColor = if (hdr != null) colors.onPrimaryContainer else colors.onSurfaceDim,
                    outlineColor = colors.outline.takeIf { hdr == null },
                )
                DetailChip(
                    text = Format.bytes(item.sizeBytes),
                    containerColor = colors.primaryContainer,
                    contentColor = colors.onPrimaryContainer,
                )
            }

            // With the file's own facts rather than under the CTA, because it is one of
            // them: what will be sent when the button is pressed. Deliberately not a chip
            // beside them — those are the three the phone reads off the file itself, and a
            // selection is not a source fact. Deliberately not a card either: the seat
            // below belongs to one arrival at a time, and this sheet is capped at 640 dp.
            if (subtitle != null) {
                Spacer(Modifier.height(12.dp))
                AttachedSubtitleRow(subtitle.displayName, subtitle.language)
            }

            Spacer(Modifier.height(17.dp))
            // One card, two truths, never both: "will direct-play at full quality" is a
            // promise this TV has already broken for this file, and printing it above the
            // refusal would make the sheet argue with itself.
            //
            // The gesture belongs to the same verdict. It is an invitation to press the
            // button under it — a thumb flicking the film away — and above a refusal it
            // would be the sheet cheering for the thing it has just warned about. Its seat
            // is here rather than beside the poster because the flick has to point at the
            // CTA, and because the poster overhead is still flying in from the Library
            // when this route opens; FlickGesture holds its own first cycle back for that.
            //
            // The link advisory takes that same seat when it is raised, and does not open a
            // new one. One seat between the promise and the CTA: an invitation to press
            // when there is nothing to say, the sentence when there is. Adding a block
            // instead would put a fourth arrival into the moment the poster, the sheet and
            // the loop already share, and would push the CTA down a sheet that is capped at
            // 640 dp and already scrolls.
            //
            // A refusal outranks it. A file this TV has already refused will not play at any
            // bitrate, so warning about the Wi-Fi carrying it is advice about a problem the
            // user cannot reach from here.
            if (refusal != null) {
                RefusalCard(refusal)
                Spacer(Modifier.height(17.dp))
            } else {
                DirectPlayCard()
                Spacer(Modifier.height(17.dp))
                if (advisory != null) {
                    LinkAdvisory(
                        advisory = advisory,
                        onSwitchNetwork = {
                            runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
                        },
                        castEnabled = progressReady && !castStart.isCommitting(),
                        onCastAnyway = { controller.flickToTv(item) },
                    )
                    Spacer(Modifier.height(17.dp))
                } else {
                    FlickGesture()
                    Spacer(Modifier.height(9.dp))
                }
            }

            FlickToTvButton(
                text = resumeTime?.let { stringResource(R.string.detail_resume_cta, it, tvName) }
                    ?: connectedTv?.let { stringResource(R.string.detail_cta, it.name) }
                    ?: stringResource(R.string.detail_cta_noconnect),
                accessibilityLabel = primaryDescription,
                committing = castStart.isCommitting(),
                enabled = progressReady,
                onClick = { controller.flickToTv(item) },
            )

            Spacer(Modifier.height(10.dp))
            // Both of these are ways NOT to do what the CTA above offers, so they share
            // one row directly under it rather than stacking as two more full-width
            // decisions. Intrinsic height, the same instrument the quality sheet's gauge
            // pair uses: whichever label wraps sets the height and the other grows into
            // it, so the two pills can never sit at different heights on a narrow window
            // or a large font scale.
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Withheld rather than disabled: with no checkpoint there is no position
                // to start over FROM, and a dead control is a promise the sheet cannot
                // keep. The phone action then takes the whole row on its own weight.
                if (resumeMs != null) {
                    SecondaryAction(
                        icon = FlickIcons.Restart,
                        label = stringResource(R.string.detail_start_over),
                        accessibilityLabel = startOverDescription,
                        interactionSource = startOverSource,
                        enabled = !castStart.isCommitting(),
                        onClick = { controller.startOver(item) },
                    )
                }
                SecondaryAction(
                    icon = FlickIcons.Phone,
                    label = stringResource(R.string.detail_play_here),
                    accessibilityLabel = playHereDescription,
                    interactionSource = playHereSource,
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(item.uri, "video/*")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            )
                        }
                    },
                )
            }
        }
    }
}

/**
 * One of the two ways out from under the CTA. Tonal rather than the action blue — a
 * second saturated fill in the same column would read as the sheet asking twice — and it
 * carries a [FlickIcons] glyph rather than a colour-emoji, which is the only kind of mark
 * that survives beside a hand-authored 24 dp stroke set.
 *
 * Icon and label sit in one centred lockup, and the label is allowed two lines: half a
 * 360 dp sheet is not wide enough for "Play on this phone" on one, and truncating the
 * only escape from casting is worse than wrapping it.
 */
@Composable
private fun RowScope.SecondaryAction(
    icon: ImageVector,
    label: String,
    accessibilityLabel: String,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = LocalFlickColors.current
    Row(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .pressScale(interactionSource)
            .heightIn(min = 48.dp)
            .clip(PillShape)
            .background(colors.fillControl)
            .clickable(
                interactionSource = interactionSource,
                indication = flickRipple(colors.onSurface),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) { contentDescription = accessibilityLabel }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.onSurface,
            modifier = Modifier.size(19.dp),
        )
        Text(
            text = label,
            style = FlickText.labelMedium.copy(color = colors.onSurface),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The subtitle this film is already carrying.
 *
 * A remembered one attaches while the sheet is being read, and nothing on this route would
 * otherwise admit to it: the only surface that names a selection is a sheet reachable from
 * Now Playing. Without this a viewer flicks a film carrying cues they did not choose in this
 * session and had no way to see. Resume is not silent like that either — the CTA above says
 * where it will start.
 *
 * Read-only on purpose. Removing a subtitle also forgets the memory behind it, and that
 * decision stays where attaching lives rather than becoming a second destructive control on
 * a sheet whose other controls all start a cast.
 *
 * The language when the record carries one, because that is what the viewer chose; the
 * file's own name when it does not, which is honest and is already what the subtitles sheet
 * shows. Neither is ever logged — a subtitle name names the film and the user's storage.
 */
@Composable
private fun AttachedSubtitleRow(displayName: String, language: String?) {
    val colors = LocalFlickColors.current
    val label = languageLabel(language) ?: displayName
    val spoken = stringResource(R.string.a11y_detail_subtitle, label)
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = FlickIcons.Captions,
            contentDescription = null,
            tint = colors.spark,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = stringResource(R.string.detail_subtitle_attached, label),
            style = FlickText.bodySmall.copy(color = colors.onSurfaceDim),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Whether the sheet for [itemKey] may name the subtitle that is attached right now.
 *
 * There is one selection at a time and it belongs to the film it was picked or recalled
 * for. A live cast owns it, so browsing to another film mid-cast is the ordinary way to
 * arrive here with somebody else's subtitle attached — and `startCast` drops it rather than
 * send it, which is the promise a sheet that had drawn it would already have broken.
 */
internal fun showAttachedSubtitle(ownerKey: String?, itemKey: String): Boolean =
    ownerKey == itemKey

/**
 * The direct-play promise: what happens when nothing has gone wrong. Carried on a solid
 * accent fill with its own inverting ink, which is the one shape the accent is allowed in
 * the dark palette — an area, never a hairline.
 */
@Composable
private fun DirectPlayCard() {
    val colors = LocalFlickColors.current
    Row(
        Modifier
            .clip(RoundedCornerShape(FlickCorners.qualityCard))
            .background(colors.spark)
            .padding(15.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = FlickIcons.CheckCircle,
            contentDescription = null,
            tint = colors.onSpark,
            modifier = Modifier.size(21.dp),
        )
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) {
                    append(stringResource(R.string.detail_directplay_title))
                }
                append(" ")
                append(stringResource(R.string.detail_directplay_body))
            },
            style = FlickText.bodySmall.copy(color = colors.onSpark),
        )
    }
}

/**
 * The promise card's seat when a receiver has refused this file, in the app's advisory
 * vocabulary rather than its failure one: the caution hue — amber on light, vermilion on
 * the dark sets, where the action itself is gold — the same geometry, and the CTA below it
 * untouched. Crimson would read as a blocked file, and this file is not blocked
 * — the user may have remuxed it, or be standing in front of a different TV.
 */
@Composable
private fun RefusalCard(code: String) {
    val colors = LocalFlickColors.current
    Row(
        Modifier
            .clip(RoundedCornerShape(FlickCorners.qualityCard))
            .background(colors.caution)
            .padding(15.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = FlickIcons.Warning,
            contentDescription = null,
            tint = colors.onCaution,
            modifier = Modifier.size(21.dp),
        )
        Column {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) {
                        append(stringResource(R.string.detail_unplayable_title))
                    }
                    append(" ")
                    append(stringResource(refusalBody(code)))
                },
                style = FlickText.bodySmall.copy(color = colors.onCaution),
            )
            Text(
                text = stringResource(R.string.detail_unplayable_note),
                style = FlickText.bodySmall.copy(color = colors.onCaution.copy(alpha = RefusalNoteAlpha)),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * The receiver's own diagnosis, in the sheet's voice. Every branch continues the card's
 * "Your TV couldn't play this file" opener, so only codes a TV can reach belong here —
 * an unrecognised one, from a newer receiver or a category this build has never seen,
 * says only what is certain: it stopped.
 */
private fun refusalBody(code: String): Int = when (code) {
    "unsupported_container" -> R.string.detail_unplayable_container
    "unsupported_video_codec", "unsupported_video_format" -> R.string.detail_unplayable_video
    "unsupported_hdr_profile" -> R.string.detail_unplayable_hdr
    "malformed_media" -> R.string.detail_unplayable_damaged
    else -> R.string.detail_unplayable_generic
}

/** The note is a second register, not a second paragraph of the same weight. */
private const val RefusalNoteAlpha = 0.82f

/**
 * The pre-cast link advisory. It is not a gate and there is none anywhere in this feature:
 * before the first byte moves the phone knows only the negotiated PHY rate, which
 * over-reports usable throughput by 2–4x, so a refusal built on it would refuse casts that
 * play perfectly. The CTA below is untouched — same enabled state, same action.
 *
 * [AdvisoryTone.INFO] rather than the caution fill the band advisory wears, and the reason
 * is the card it always stands under: on the light palette [DirectPlayCard]'s `spark`
 * (#FFB61E) and `caution` (#FFA23A) are one hue step apart, so two saturated warm fills in
 * one column read as a single orange block. Caution is also the seat this sheet already
 * gives [RefusalCard], which is the harder claim — that this TV cannot play this file at
 * all — and the two must not wear the same clothes.
 *
 * "Cast anyway" is the cast, not a dismissal: it runs the CTA's own action under the CTA's
 * own guard. A label that says cast and only hides a card would be the one dishonest thing
 * on this screen, and on a short window this card can be the last thing above the fold.
 */
@Composable
private fun LinkAdvisory(
    advisory: PreCastLinkAdvisory,
    onSwitchNetwork: () -> Unit,
    castEnabled: Boolean,
    onCastAnyway: () -> Unit,
) {
    val title = stringResource(R.string.link_advisory_title)
    val body = stringResource(
        R.string.link_advisory_body,
        Format.bitrate(advisory.requiredBps),
        wifiBandLabel(advisory.band),
        Format.bitrate(advisory.usableBps),
    )
    // Unmerged: the card carries two real buttons, and merging to speak the sentence once
    // would take both of them off TalkBack's traversal.
    val spoken = stringResource(R.string.a11y_link_advisory, "$title $body")
    AdvisoryCard(
        icon = FlickIcons.Wifi,
        title = title,
        body = body,
        tone = AdvisoryTone.INFO,
        primaryLabel = stringResource(R.string.advisory_band_primary),
        onPrimary = onSwitchNetwork,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = spoken },
        secondaryLabel = stringResource(R.string.advisory_band_secondary),
        onSecondary = onCastAnyway,
        secondaryEnabled = castEnabled,
    )
}

/**
 * The blue "Flick to <TV>" CTA. Falls back to pairing when no TV is connected.
 *
 * [committing] is read straight off `castStart`, never off a local boolean: a
 * handshake that fails in its first hop has to clear this label with it.
 */
@Composable
private fun FlickToTvButton(
    text: String,
    accessibilityLabel: String,
    committing: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalFlickColors.current
    val motionScheme = MaterialTheme.motionScheme
    val reduceMotion = rememberReduceMotion()
    val source = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(source)
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
                interactionSource = source,
                indication = flickRipple(colors.onPrimary),
                enabled = enabled && !committing,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) { contentDescription = accessibilityLabel }
            .padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = committing,
            transitionSpec = {
                if (reduceMotion) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    (
                        fadeIn(motionScheme.defaultEffectsSpec()) +
                            scaleIn(motionScheme.fastSpatialSpec(), initialScale = CtaSwapScale)
                        ) togetherWith (
                        fadeOut(motionScheme.fastEffectsSpec()) +
                            scaleOut(motionScheme.fastSpatialSpec(), targetScale = CtaSwapScale)
                        )
                }
            },
            label = "cta",
        ) { busy ->
            if (busy) {
                CommitIndicator()
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(11.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = FlickIcons.Cast,
                        contentDescription = null,
                        tint = colors.onPrimary,
                        modifier = Modifier.size(21.dp),
                    )
                    Text(text, style = FlickText.titleSmall.copy(color = colors.onPrimary))
                }
            }
        }
    }
}

/**
 * The commit is a handshake, not a measurement, so this indicator never carries a
 * percentage — it only says the TV was asked.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CommitIndicator() {
    val colors = LocalFlickColors.current
    if (rememberReduceMotion()) {
        // A frozen spin reads as a hang, so the reduced form is a resting shape.
        Box(
            Modifier
                .size(CommitRestSize)
                .background(colors.onPrimary, MaterialShapes.Cookie4Sided.toShape()),
        )
    } else {
        ContainedLoadingIndicator(
            containerColor = colors.onPrimary.copy(alpha = CommitContainerAlpha),
            indicatorColor = colors.onPrimary,
        )
    }
}

/** The swapped face arrives from under the finger rather than from nowhere. */
private const val CtaSwapScale = 0.72f
private const val CommitContainerAlpha = 0.18f
private val CommitRestSize = 38.dp

@Composable
private fun DetailChip(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    outlineColor: Color? = null,
) {
    Text(
        text = text,
        style = FlickText.monoChip.copy(color = contentColor),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(PillShape)
            .background(containerColor)
            .then(if (outlineColor != null) Modifier.border(1.dp, outlineColor, PillShape) else Modifier)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

/**
 * `4K · 3840 × 2160` when MediaStore knew the pixels. When it knew none of them the chip
 * says that instead of naming the smallest bucket: a 4 GB remux with no scanned row is
 * not an SD file, it is a file nobody measured.
 */
@Composable
private fun resolutionText(item: MediaItem): String =
    if (item.knowsResolution) {
        if (item.width > 0 && item.height > 0) {
            stringResource(R.string.sheet_resolution_pixels, item.resolutionLabel, item.width, item.height)
        } else {
            item.resolutionLabel
        }
    } else {
        stringResource(R.string.detail_resolution_unknown)
    }

/** Null is the probe still reading the container — "SDR" is a result, not a default. */
@Composable
private fun hdrChipLabel(hdr: HdrType?): String = when (hdr) {
    HdrType.DOLBY_VISION -> stringResource(R.string.media_dolby_vision_badge)
    HdrType.HDR10 -> stringResource(R.string.media_hdr10_badge)
    HdrType.NONE -> stringResource(R.string.media_sdr)
    null -> stringResource(R.string.detail_hdr_checking)
}

/**
 * Only the in-flight handshake states. An already-Active session must not strand this
 * screen's CTA when the user walks back into a detail sheet mid-cast.
 */
private fun CastStartState.isCommitting(): Boolean = when (this) {
    is CastStartState.ConnectingControl,
    is CastStartState.StartingSource,
    is CastStartState.AwaitingAcceptance,
    is CastStartState.AwaitingFirstFrame,
    -> true
    CastStartState.Idle, is CastStartState.Active, is CastStartState.Failed -> false
}

/**
 * Duration plus the MediaStore bucket, which is the only provenance we hold. An unscanned
 * duration is the em dash, never `durationHuman`'s "0s" — a two-hour film is not zero
 * seconds long because nothing measured it.
 */
@Composable
private fun detailMeta(item: MediaItem): String {
    val duration = if (item.knowsDuration) {
        Format.durationHuman(item.durationMs)
    } else {
        stringResource(R.string.media_unknown)
    }
    val bucket = item.bucket?.takeIf { it.isNotBlank() } ?: return duration
    return stringResource(R.string.quality_playing_value, duration, bucket)
}
