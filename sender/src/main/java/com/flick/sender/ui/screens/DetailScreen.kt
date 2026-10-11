package com.flick.sender.ui.screens

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import coil.ImageLoader
import coil.compose.AsyncImage
import com.flick.sender.R
import com.flick.sender.media.MediaProbe
import com.flick.sender.model.HdrType
import com.flick.sender.model.MediaItem
import com.flick.sender.net.CastStartState
import com.flick.sender.net.DecoderFaultCode
import com.flick.sender.net.FlickController
import com.flick.sender.net.LinkCapacityPolicy
import com.flick.sender.net.PreCastLinkAdvisory
import com.flick.sender.media.PlaybackProgressState
import com.flick.sender.ui.FilmBackdropHero
import com.flick.sender.ui.FilmPosterBackdrop
import com.flick.sender.ui.FilmTitleLogo
import com.flick.sender.ui.Format
import com.flick.sender.ui.displayName
import com.flick.sender.ui.rememberFilmArt
import com.flick.sender.ui.components.AdvisoryCard
import com.flick.sender.ui.components.AdvisoryTone
import com.flick.sender.ui.components.FlickPad
import com.flick.sender.ui.components.FlickPosterCard
import com.flick.sender.ui.components.flickSharedFrame
import com.flick.sender.ui.components.posterKey
import com.flick.sender.ui.components.rememberDetailVideoFrameRequest
import com.flick.sender.ui.components.rememberVideoImageLoader
import com.flick.sender.ui.theme.CinemaDeep
import com.flick.sender.ui.theme.CinemaGlass
import com.flick.sender.ui.theme.CinemaGlassEdge
import com.flick.sender.ui.theme.CinemaGlassStrong
import com.flick.sender.ui.theme.FlickCinematicTheme
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.PillShape
import com.flick.sender.ui.theme.flickRipple
import com.flick.sender.ui.theme.pressScale
import com.flick.sender.ui.theme.rememberReduceMotion

/**
 * S4 — detail / "cast this". A dark cinema page in every palette: the film's backdrop under
 * a gradient that is solid by the lower half, the poster to throw, the facts, one CTA. Back
 * routes through [FlickController.back], so the shell's own BackHandler stays the only one on
 * this route.
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
    val context = LocalContext.current
    val connectedTv by controller.connectedTv.collectAsState()
    val castStart by controller.castStart.collectAsState()
    val playbackProgress by controller.playbackProgress.collectAsState()
    val unplayable by controller.unplayableFiles.collectAsState()
    val selectedSubtitle by controller.selectedSubtitle.collectAsState()
    val subtitleOwnerKey by controller.subtitleOwnerKey.collectAsState()
    val imageLoader = rememberVideoImageLoader()
    val rise = rememberSheetRise()
    // Geometry and opacity on separate clocks: the spatial spring overshoots by design, and
    // copy whose transparency rang with it would flicker over the backdrop on the way in.
    val fade = rememberSheetFade()
    val displayName = item.displayName()
    val tvName = connectedTv?.name ?: stringResource(R.string.np_tv_generic)
    val castDescription = stringResource(R.string.a11y_cast_video, displayName, tvName)
    val progressReady = playbackProgress is PlaybackProgressState.Ready
    val resumeMs = remember(item, playbackProgress) {
        controller.resumePosition(item, playbackProgress)
    }
    val resumeTime = resumeMs?.let(Format::timecode)
    val primaryDescription = resumeTime?.let {
        stringResource(R.string.a11y_resume_video, displayName, it, tvName)
    } ?: castDescription
    val backDescription = stringResource(R.string.a11y_back_to_library)
    // Null until the probe answers. Starting at NONE would print "SDR" — a verdict — for
    // every file in the window between opening this page and reading its container.
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
    // recompose the page behind the flying frame. Unwrapped here rather than read in the
    // branch below because raising or lowering the card IS a rebuild of the page's body.
    val advisory = remember(signal, requiredBps) {
        derivedStateOf(structuralEqualityPolicy()) {
            LinkCapacityPolicy.preCastAdvisory(requiredBps, signal.value.link)
        }
    }.value
    // The pad is an invitation to throw the film at the TV. Above a refusal it would be the
    // page cheering for the thing it has just warned about, and a raised link advisory takes
    // the same moment: one arrival at a time between the facts and the CTA.
    val padSeat = refusal == null && advisory == null
    val castEnabled = progressReady && !castStart.isCommitting()

    val request = rememberDetailVideoFrameRequest(item)
    val art = item.rememberFilmArt()
    val posterPath = art?.poster
    val pageScroll = rememberScrollState()

    FlickCinematicTheme {
        val colors = LocalFlickColors.current
        BoxWithConstraints(Modifier.fillMaxSize().background(CinemaDeep)) {
            val viewport = maxHeight
            // In place rather than in the shared overlay: the page has to stay above the
            // frame while both are arriving.
            val backdropModifier = Modifier
                .flickSharedFrame(
                    sharedScope = sharedScope,
                    animatedScope = animatedScope,
                    key = posterKey(item.id),
                    renderInOverlay = false,
                )
                .fillMaxSize()
            val backdropPath = art?.backdrop
            when {
                backdropPath != null ->
                    FilmBackdropHero(backdropPath = backdropPath, imageLoader = imageLoader, modifier = backdropModifier)
                posterPath != null ->
                    FilmPosterBackdrop(posterPath = posterPath, imageLoader = imageLoader, modifier = backdropModifier)
                else -> AsyncImage(
                    model = request,
                    contentDescription = null,
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Crop,
                    modifier = backdropModifier,
                )
            }
            // Not part of the shared frame, so the frame flies under one constant wash. It
            // deepens as the page scrolls: copy carried up into the lit top half would
            // otherwise stand on whatever the backdrop is brightest at.
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val lift = (pageScroll.value / ScrimLiftDistance.toPx()).coerceIn(0f, 1f)
                        drawRect(cinemaScrim(lift))
                    },
            )

            Column(
                Modifier
                    .fillMaxSize()
                    .sheetRiseTransform(rise, fade)
                    .verticalScroll(pageScroll)
                    // A minimum rather than a weight: inside a scroll there is no height to
                    // share out, and SpaceBetween over this spreads the three groups when the
                    // window is taller than they are.
                    .heightIn(min = viewport)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = PageGutter, vertical = 12.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Its glass circle, not its touch target, lines up with the gutter.
                GlassBackButton(
                    description = backDescription,
                    onClick = { controller.back() },
                    modifier = Modifier
                        .align(Alignment.Start)
                        .offset(x = -(BackTouchSize - BackGlassSize) / 2),
                )

                if (padSeat) {
                    // FlickPad holds its first nudge back while the frame is still flying in.
                    FlickPad(
                        posterPath = posterPath,
                        frameRequest = request,
                        imageLoader = imageLoader,
                        enabled = castEnabled,
                        pageScroll = pageScroll,
                        onFlick = { controller.flickToTv(item) },
                        modifier = Modifier.padding(vertical = HeroGap),
                    )
                } else {
                    FlickPosterCard(
                        posterPath = posterPath,
                        frameRequest = request,
                        imageLoader = imageLoader,
                        modifier = Modifier.padding(vertical = HeroGap),
                    )
                }

                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    DetailTitle(logoPath = art?.logo, title = displayName, imageLoader = imageLoader)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = detailMeta(item),
                        style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(16.dp))
                    // Source-fact chips reflow on narrow or large-font windows instead of
                    // competing for one fixed row and clipping the resolution or file size.
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Resolution, dynamic range and size are the only source facts the
                        // phone can read; codec and frame rate never leave MediaStore. A fact
                        // it did not read takes the outlined chip: same seat, same type, no
                        // claim.
                        DetailChip(text = resolutionText(item), known = item.knowsResolution)
                        DetailChip(text = hdrChipLabel(hdr), known = hdr != null)
                        DetailChip(text = Format.bytes(item.sizeBytes), known = true)
                    }

                    // With the file's own facts rather than under the CTA, because it is one
                    // of them: what will be sent when the button is pressed. Not a chip — those
                    // are the three the phone reads off the file itself, and a selection is
                    // not a source fact.
                    if (subtitle != null) {
                        Spacer(Modifier.height(12.dp))
                        AttachedSubtitleRow(subtitle.displayName, subtitle.language)
                    }

                    Spacer(Modifier.height(SectionGap))
                    // A refusal outranks the advisory. A file this TV has already refused will
                    // not play at any bitrate, so warning about the Wi-Fi carrying it is advice
                    // about a problem the user cannot reach from here.
                    if (refusal != null) {
                        RefusalCard(refusal)
                        Spacer(Modifier.height(CardGap))
                    } else if (advisory != null) {
                        LinkAdvisory(
                            advisory = advisory,
                            onSwitchNetwork = {
                                runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
                                    .onFailure {
                                        Toast.makeText(context, R.string.error_no_wifi_settings_toast, Toast.LENGTH_SHORT)
                                            .show()
                                    }
                            },
                            castEnabled = castEnabled,
                            onCastAnyway = { controller.flickToTv(item) },
                        )
                        Spacer(Modifier.height(CardGap))
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
                }
            }
        }
    }
}

/**
 * Light at the very top for the status bar and the back control, lightest across the poster's
 * band, and solid from just past the middle down, which is where every line of copy sits
 * until the page is scrolled. [lift] is how far that scroll has gone toward [ScrimLiftDistance].
 */
private fun cinemaScrim(lift: Float): Brush = Brush.verticalGradient(
    0f to CinemaDeep.copy(alpha = lerp(0.5f, ScrolledScrimAlpha, lift)),
    0.2f to CinemaDeep.copy(alpha = lerp(0.3f, ScrolledScrimAlpha, lift)),
    0.42f to CinemaDeep.copy(alpha = lerp(0.75f, ScrolledScrimAlpha, lift)),
    0.55f to CinemaDeep,
    1f to CinemaDeep,
)

// White and the dim ink both clear 4.5:1 over a white backdrop under this much CinemaDeep.
private const val ScrolledScrimAlpha = 0.95f
private val ScrimLiftDistance = 120.dp

private val PageGutter = 24.dp
private val HeroGap = 20.dp
private val SectionGap = 24.dp
private val CardGap = 16.dp
private val BackGlassSize = 44.dp
private val BackTouchSize = 48.dp

/** A 44 dp glass circle inside a 48 dp target. */
@Composable
private fun GlassBackButton(
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(BackTouchSize)
            .pressScale(source)
            .clip(CircleShape)
            .clickable(
                interactionSource = source,
                indication = flickRipple(Color.White),
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(BackGlassSize)
                .clip(CircleShape)
                .background(CinemaGlassStrong)
                .border(1.dp, CinemaGlassEdge, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = FlickIcons.ChevronLeft,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * TMDB's own lettering when it has a logo for this film, the name in type when it has none
 * or the logo will not load. Either way it is the page's heading.
 */
@Composable
private fun DetailTitle(logoPath: String?, title: String, imageLoader: ImageLoader) {
    var logoFailed by remember(logoPath) { mutableStateOf(false) }
    if (logoPath != null && !logoFailed) {
        FilmTitleLogo(
            logoPath = logoPath,
            imageLoader = imageLoader,
            contentDescription = title,
            onFailed = { logoFailed = true },
            // A fixed box, so the copy below does not jump when the logo lands.
            modifier = Modifier
                .widthIn(max = 240.dp)
                .fillMaxWidth()
                .height(72.dp)
                .semantics { heading() },
        )
    } else {
        Text(
            text = title,
            style = FlickText.headlineMedium.copy(color = Color.White),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
    }
}

/**
 * The subtitle this film is already carrying.
 *
 * A remembered one attaches while the page is being read, and nothing on this route would
 * otherwise admit to it: the only surface that names a selection is a sheet reachable from
 * Now Playing. Without this a viewer flicks a film carrying cues they did not choose in this
 * session and had no way to see. Resume is not silent like that either — the CTA below says
 * where it will start.
 *
 * Read-only on purpose. Removing a subtitle also forgets the memory behind it, and that
 * decision stays where attaching lives rather than becoming a second destructive control on
 * a page whose other controls all start a cast.
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
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
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
 * Whether the detail page for [itemKey] may name the subtitle that is attached right now.
 *
 * There is one selection at a time and it belongs to the film it was picked or recalled
 * for. A live cast owns it, so browsing to another film mid-cast is the ordinary way to
 * arrive here with somebody else's subtitle attached — and `startCast` drops it rather than
 * send it, which is the promise a page that had drawn it would already have broken.
 */
internal fun showAttachedSubtitle(ownerKey: String?, itemKey: String): Boolean =
    ownerKey == itemKey

/**
 * What takes the pad's seat when a receiver has refused this file, in the app's advisory
 * vocabulary rather than its failure one: the caution hue — amber on the light action set,
 * vermilion on the dark one, where the action itself is gold — the link advisory's geometry,
 * and the CTA below it untouched. Crimson would read as a blocked file, and this file is not
 * blocked — the user may have remuxed it, or be standing in front of a different TV.
 */
@Composable
private fun RefusalCard(code: String) {
    val colors = LocalFlickColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FlickCorners.warning))
            .background(colors.caution)
            .padding(horizontal = 17.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Icon(
            imageVector = FlickIcons.Warning,
            contentDescription = null,
            tint = colors.onCaution,
            modifier = Modifier.size(22.dp),
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
                text = stringResource(refusalNote(code)),
                style = FlickText.bodySmall.copy(color = colors.onCaution.copy(alpha = VerdictNoteAlpha)),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * The receiver's own diagnosis, in the page's voice. Every branch continues the card's
 * "Your TV couldn't play this file" opener, so only codes a TV can reach belong here —
 * an unrecognised one, from a newer receiver or a category this build has never seen,
 * says only what is certain: it stopped.
 */
private fun refusalBody(code: String): Int = when (code) {
    "unsupported_container" -> R.string.detail_unplayable_container
    "unsupported_video_codec", "unsupported_video_format" -> R.string.detail_unplayable_video
    "unsupported_hdr_profile" -> R.string.detail_unplayable_hdr
    "malformed_media" -> R.string.detail_unplayable_damaged
    // Only ever reached on the second one running — a single decoder fault never marks a
    // file — so the copy is allowed to say twice, and has to, because that repetition is
    // the entire reason this code is being treated as a verdict at all.
    DecoderFaultCode -> R.string.detail_unplayable_decoder
    else -> R.string.detail_unplayable_generic
}

/** The note under the body. Every other refusal is one attempt; this one is two. */
private fun refusalNote(code: String): Int =
    if (code == DecoderFaultCode) R.string.detail_unplayable_note_twice
    else R.string.detail_unplayable_note

/** The refusal's second register: a note, not a second paragraph of the same weight. */
private const val VerdictNoteAlpha = 0.82f

/**
 * The pre-cast link advisory. It is not a gate and there is none anywhere in this feature:
 * before the first byte moves the phone knows only the negotiated PHY rate, which
 * over-reports usable throughput by 2–4x, so a refusal built on it would refuse casts that
 * play perfectly. The CTA below is untouched — same enabled state, same action.
 *
 * [AdvisoryTone.INFO] rather than the caution fill the band advisory wears: caution is the
 * seat this page gives [RefusalCard], which is the harder claim — that this TV cannot play
 * this file at all — and the two must not wear the same clothes.
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
            // Tinted with its own fill: an ink shadow does not read on the page's near-black.
            .shadow(
                elevation = 14.dp,
                shape = PillShape,
                clip = false,
                ambientColor = colors.primary,
                spotColor = colors.primary,
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

/**
 * One source fact as a glass pill. [known] false is a fact the phone could not read: no fill
 * and the dim ink, so the seat stays without making a claim.
 */
@Composable
private fun DetailChip(text: String, known: Boolean) {
    val colors = LocalFlickColors.current
    Text(
        text = text,
        style = FlickText.monoChip.copy(color = if (known) Color.White else colors.onSurfaceDim),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(PillShape)
            .background(if (known) CinemaGlass else Color.Transparent)
            .border(1.dp, if (known) CinemaGlassEdge else colors.outline, PillShape)
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
 * screen's CTA when the user walks back into a detail page mid-cast.
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
