package com.flick.sender.ui.screens

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.activity.compose.PredictiveBackHandler
import coil.ImageLoader
import coil.compose.AsyncImage
import com.flick.sender.R
import com.flick.sender.media.FilmArt
import com.flick.sender.media.FilmPosterPolicy
import com.flick.sender.media.MediaProbe
import com.flick.sender.media.PlaybackProgressState
import com.flick.sender.model.HdrType
import com.flick.sender.model.MediaItem
import com.flick.sender.net.CastStartState
import com.flick.sender.net.DecoderFaultCode
import com.flick.sender.net.FlickController
import com.flick.sender.net.LinkCapacityPolicy
import com.flick.sender.net.PreCastLinkAdvisory
import com.flick.sender.net.Route
import com.flick.sender.ui.FilmSoftBackdrop
import com.flick.sender.ui.FilmTitleLogo
import com.flick.sender.ui.rememberIntertitleInks
import com.flick.sender.ui.rememberIntertitleSource
import com.flick.sender.ui.Format
import com.flick.sender.ui.SoftBackdropRadiusPx
import com.flick.sender.ui.SoftBackdropWidthPx
import com.flick.sender.ui.components.CardFlipMotion
import com.flick.sender.ui.components.CardFlipSeat
import com.flick.sender.ui.components.FlickPad
import com.flick.sender.ui.components.FlickPadMotion
import com.flick.sender.ui.components.FactChip
import com.flick.sender.ui.components.RuntimeChip
import com.flick.sender.ui.components.SynopsisPolicy
import com.flick.sender.ui.components.rememberSynopsisReveal
import com.flick.sender.ui.components.synopsisRecede
import com.flick.sender.ui.components.LocalCardFlipHandoff
import com.flick.sender.ui.components.TileFace
import com.flick.sender.ui.components.TileMarkings
import com.flick.sender.ui.components.cardFlipKey
import com.flick.sender.ui.components.fadeUp
import com.flick.sender.ui.components.flipGlowPresence
import com.flick.sender.ui.components.rememberCardFlipTracks
import com.flick.sender.ui.components.rememberDetailVideoFrameRequest
import com.flick.sender.ui.components.rememberSoftFrameRequest
import com.flick.sender.ui.components.rememberVideoImageLoader
import com.flick.sender.ui.displayName
import com.flick.sender.ui.rememberFilmArt
import com.flick.sender.ui.rememberFilmDetails
import com.flick.sender.ui.simplifiedName
import com.flick.sender.ui.titleWithoutYear
import com.flick.sender.ui.softBackdropHeightPx
import com.flick.sender.ui.theme.CinemaDeep
import com.flick.sender.ui.theme.CinemaDisc
import com.flick.sender.ui.theme.CinemaDiscEdge
import com.flick.sender.ui.theme.CinemaGlass
import com.flick.sender.ui.theme.CinemaGlassEdge
import com.flick.sender.ui.theme.CinemaInkDim
import com.flick.sender.ui.theme.CinemaNoticeFoot
import com.flick.sender.ui.theme.CinemaNoticeTop
import com.flick.sender.ui.theme.FlickCinematicTheme
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.PillShape
import com.flick.sender.ui.theme.flickRipple
import com.flick.sender.ui.theme.pressScale
import com.flick.sender.ui.theme.rememberReduceMotion
import kotlinx.coroutines.CancellationException

/**
 * S4 — detail / "cast this". A dark cinema page in every palette: the film's backdrop, blurred
 * and deeply scrimmed, under the film's identity (logo, facts) and the poster you flick. The
 * poster is the page's only send; there is no button. The back disc routes through
 * [FlickController.back], as the shell's BackHandler does for system back.
 *
 * A leftward push on the poster brings the film's synopsis up as a silent-film intertitle in
 * the poster's own colours ([SynopsisIntertitle]). While it is open the page registers one
 * handler of its own, enabled only then, so back closes the synopsis first; with it away, back
 * is the shell's again.
 *
 * The poster is the tile the user just tapped, turned over (see [FlipCard]): the card flies
 * from the grid into its seat while the page fades up around it, and [cardFlip] is the shell's
 * word that this route pair is that one. On any other pair the page is simply there.
 */
@Composable
fun DetailScreen(
    controller: FlickController,
    item: MediaItem,
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
    cardFlip: Boolean = false,
) {
    val context = LocalContext.current
    val connectedTv by controller.connectedTv.collectAsState()
    val castStart by controller.castStart.collectAsState()
    val playbackProgress by controller.playbackProgress.collectAsState()
    val unplayable by controller.unplayableFiles.collectAsState()
    val selectedSubtitle by controller.selectedSubtitle.collectAsState()
    val subtitleOwnerKey by controller.subtitleOwnerKey.collectAsState()
    val imageLoader = rememberVideoImageLoader()
    val reduceMotion = rememberReduceMotion()
    val tracks = animatedScope?.let { rememberCardFlipTracks(it.transition, cardFlip) }
    val page = { (tracks?.page?.value ?: 1f).coerceIn(0f, 1f) }
    val displayName = item.displayName()
    val tvName = connectedTv?.name ?: stringResource(R.string.np_tv_generic)
    val castDescription = stringResource(R.string.a11y_cast_video, displayName, tvName)
    val progressReady = playbackProgress is PlaybackProgressState.Ready
    val resumeMs = remember(item, playbackProgress) {
        controller.resumePosition(item, playbackProgress)
    }
    val resumeTime = resumeMs?.let(Format::timecode)
    val actionDescription = resumeTime?.let {
        stringResource(R.string.a11y_resume_video, displayName, it, tvName)
    } ?: castDescription
    val actionLabel = resumeTime?.let { stringResource(R.string.detail_resume_cta, it, tvName) }
        ?: connectedTv?.let { stringResource(R.string.detail_cta, it.name) }
        ?: stringResource(R.string.detail_cta_noconnect)
    val committing = castStart.isCommitting()
    val request = rememberDetailVideoFrameRequest(item)
    val art = item.rememberFilmArt()
    val posterPath = art?.poster
    val details = item.rememberFilmDetails(art?.tmdbId)
    val heading = item.simplifiedName()
    val synopsisTitle = item.titleWithoutYear()
    // The card is still flying to its seat until this turns true; nothing new is composed under
    // it before then, and nothing pulls the synopsis over a card in flight.
    val landed = sharedScope?.isTransitionActive != true
    val synopsis = details?.takeIf { it.hasSynopsis }
    val reveal = rememberSynopsisReveal(item.id, reduceMotion)
    val revealable = synopsis != null && landed
    val caption = connectedTv?.let { stringResource(R.string.flick_pad_title_tv, it.name) }
        ?: stringResource(R.string.flick_pad_title)
    // Resume is never silent: the pad says where the film will start before it is thrown.
    val note = when {
        committing -> stringResource(R.string.flick_pad_sending, tvName)
        resumeTime != null -> stringResource(R.string.flick_pad_resume, resumeTime)
        connectedTv == null -> stringResource(R.string.flick_pad_no_tv)
        revealable -> stringResource(R.string.flick_pad_story_hint)
        else -> null
    }
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
    // recompose the page behind the flying frame.
    val advisory = remember(signal, requiredBps) {
        derivedStateOf(structuralEqualityPolicy()) {
            LinkCapacityPolicy.preCastAdvisory(requiredBps, signal.value.link)
        }
    }.value
    // Same predicate the button had: the send waits for the resume point and is single-flight.
    val castEnabled = progressReady && !committing

    val route by controller.route.collectAsState()
    val qualityOpen by controller.showQualitySheet.collectAsState()
    val diagnosticsOpen by controller.showDiagnostics.collectAsState()
    val supportOpen by controller.showSupportSheet.collectAsState()
    // Registered after the shell's, so it answers first — and only while the story is out on
    // this route with no sheet over it; a sheet's own handler, or the shell's for an overlay,
    // keeps back otherwise. Out, not merely open: a back made while it springs home puts it
    // away rather than leaving the page under a story still in flight.
    PredictiveBackHandler(
        enabled = reveal.engaged && route is Route.Detail && !qualityOpen && !diagnosticsOpen && !supportOpen,
    ) { progress ->
        reveal.beginBack()
        try {
            progress.collect { event -> reveal.holdForBack(event.progress) }
            reveal.hide()
        } catch (e: CancellationException) {
            reveal.cancelBack()
            throw e
        }
    }
    // The page leaving puts the story away in that very frame: a story still in flight over a
    // page in flight is two posters. Done where the leaving starts, not on recomposing after
    // it — a route on its way out stops recomposing and its animation clock stands still, so
    // a story springing home would freeze where it was for the length of the exit. The side
    // effect is the backstop for a route change this page did not start.
    val leave = { action: () -> Unit ->
        reveal.shut()
        action()
    }
    val leaving = !landed || route !is Route.Detail
    SideEffect { if (leaving && reveal.engaged) reveal.shut() }
    // Once the story has been out, the pad and the chrome keep the same reveal through the
    // page's exit, so the poster's flight and its hidden face stay one decision.
    val padReveal = reveal.takeIf { synopsis != null && (revealable || reveal.everShown) }
    val pageScroll = rememberScrollState()
    // The wide layout's pad column scrolls on its own, and only if the pad cannot fit it.
    val padScroll = rememberScrollState()
    val onSwitchNetwork = {
        runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
            .onFailure {
                Toast.makeText(context, R.string.error_no_wifi_settings_toast, Toast.LENGTH_SHORT).show()
            }
        Unit
    }
    val onFlick = { leave { controller.flickToTv(item) } }
    val flipKey = cardFlipKey(item.id)
    val handoff = LocalCardFlipHandoff.current
    // The size the tapped tile drew its face at, so the card's front asks Coil for that very
    // decode and is drawn from memory on its first frame.
    val tileFacePx = remember(flipKey) { handoff.tileFaceFor(flipKey) }
    val seat = if (sharedScope != null && animatedScope != null && tracks != null && !reduceMotion) {
        CardFlipSeat(
            sharedScope,
            animatedScope,
            flipKey,
            tracks,
            marks = {
                if (cardFlip) handoff.marksFor(flipKey)?.let { TileMarkings(item, it, Modifier.fillMaxSize()) }
            },
        ) {
            if (cardFlip) {
                TileFace(
                    item = item,
                    filmArt = art,
                    imageLoader = imageLoader,
                    unplayable = refusal != null,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    requestSizePx = tileFacePx,
                )
            }
        }
    } else {
        null
    }

    // Measured, so the poster takes exactly the height the rest of the page leaves it. Seeded
    // from the last page measured rather than from an estimate: the card is already flying to
    // the seat on the first frame, and a seat that moves a frame later bends its path.
    var identityPx by remember { mutableIntStateOf(MeasuredSeat.identityPx) }
    var padChromePx by remember { mutableIntStateOf(MeasuredSeat.padChromePx) }
    var noticePx by remember { mutableIntStateOf(MeasuredSeat.noticePx) }

    FlickCinematicTheme {
        val density = LocalDensity.current
        val bars = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { reveal.pageOrigin = it.positionInRoot() }
                .drawBehind { drawRect(CinemaDeep, alpha = page()) },
        ) {
            val viewport = maxHeight
            // The reveal's travel, known before the story is first composed: the first pull is
            // measured against it on the frame it starts.
            val travelPx = with(density) { SynopsisPolicy.travelDp(maxWidth.value).dp.toPx() }
            SideEffect { reveal.widthPx = travelPx }
            val wide = maxWidth > maxHeight
            val compact = viewport < CompactHeight
            val heroGap = if (compact) HeroGapCompact else HeroGap
            val chevronGap = if (compact) ChevronGapCompact else FlickPadMotion.ChevronGapDp.dp
            val captionGap = if (compact) CaptionGapCompact else FlickPadMotion.TitleGapDp.dp
            val identity = with(density) { identityPx.toDp() }.takeIf { identityPx > 0 }
                ?: IdentityEstimate * density.fontScale.coerceAtLeast(1f)
            val padChrome = with(density) { padChromePx.toDp() }.takeIf { padChromePx > 0 }
                ?: PadChromeEstimate * density.fontScale.coerceAtLeast(1f)
            val notice = with(density) { noticePx.toDp() }
            val posterHeight = if (wide) {
                (viewport - bars - PageVerticalPad * 2 - heroGap * 2 - padChrome - FitSlack)
                    .coerceIn(PosterMinHeightWide, PosterMaxHeight)
            } else {
                // Sized so the page does not scroll: while it can, FlickPadPolicy hands an
                // upward swipe to the page instead of the throw. A notice that cannot fit
                // beside a usable poster shrinks the poster to its floor, so only the notice's
                // overflow goes below the fold, never the whole notice.
                val beside = viewport - bars - PageVerticalPad * 2 - identity - heroGap * 2 -
                    padChrome - FitSlack
                (beside - notice).coerceIn(PosterMinHeight, PosterMaxHeight)
            }
            val posterWidth = posterHeight / FlickPadMotion.PosterAspect
            val posterHeightPx = with(density) { posterHeight.roundToPx() }
            // The story is made of the poster the seat draws: read once the page has landed.
            val inks = rememberIntertitleInks(
                source = rememberIntertitleSource(
                    posterPath = posterPath,
                    frameRequest = request,
                    seatPx = IntSize(with(density) { posterWidth.roundToPx() }, posterHeightPx),
                ),
                imageLoader = imageLoader,
                ready = revealable,
                visible = { reveal.engaged },
            )

            // Blurred once into a small bitmap rather than live: a full-screen blur re-runs on
            // every frame the page is drawn, and the page's entrance is nothing but frames.
            // Top-aligned, where a backdrop keeps its subject. One bitmap, so its fade modulates
            // the draw rather than costing a window-sized offscreen layer for every fading frame.
            val viewportAspect = maxHeight / maxWidth
            val backdropModifier = Modifier.fillMaxSize().graphicsLayer {
                alpha = page()
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            val backdropPath = art?.backdrop
            when {
                backdropPath != null -> FilmSoftBackdrop(
                    path = backdropPath,
                    width = FilmPosterPolicy.BACKDROP_WIDTH,
                    imageLoader = imageLoader,
                    viewportAspect = viewportAspect,
                    modifier = backdropModifier,
                )
                posterPath != null -> FilmSoftBackdrop(
                    path = posterPath,
                    width = FilmPosterPolicy.TILE_WIDTH,
                    imageLoader = imageLoader,
                    viewportAspect = viewportAspect,
                    modifier = backdropModifier,
                )
                else -> AsyncImage(
                    model = rememberSoftFrameRequest(
                        item = item,
                        imageLoader = imageLoader,
                        boxWidthPx = SoftBackdropWidthPx,
                        boxHeightPx = softBackdropHeightPx(viewportAspect),
                        radiusPx = SoftBackdropRadiusPx,
                        biasY = 0f,
                    ),
                    contentDescription = null,
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = backdropModifier,
                )
            }
            // It deepens as the page scrolls, so copy carried up never stands on bright art. As
            // the story comes the whole window goes down into the poster's own darkest tone,
            // under the page's chrome as it steps back.
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val lift = (pageScroll.value / ScrimLiftDistance.toPx()).coerceIn(0f, 1f)
                        drawRect(cinemaScrim(lift), alpha = page())
                        val p = reveal.value.coerceIn(0f, 1f)
                        if (p > 0f) {
                            val ground = if (reduceMotion) SynopsisPolicy.GROUND_ALPHA * p else SynopsisPolicy.groundAlpha(p)
                            drawRect(inks.ground(), alpha = ground)
                        }
                    },
            )

            val pad: @Composable (ScrollState) -> Unit = { scroll ->
                FlickPad(
                    posterPath = posterPath,
                    frameRequest = request,
                    imageLoader = imageLoader,
                    enabled = castEnabled,
                    pageScroll = scroll,
                    caption = caption,
                    note = note,
                    actionDescription = actionDescription,
                    actionLabel = actionLabel,
                    onFlick = onFlick,
                    posterWidth = posterWidth,
                    chevronGap = chevronGap,
                    captionGap = captionGap,
                    seat = seat,
                    // The throw waits for the card to land: until then the seat is not where
                    // the finger is.
                    throwReady = sharedScope?.isTransitionActive != true,
                    glowIn = { tracks?.let { flipGlowPresence(it.glow.value, it.move.value) } ?: 1f },
                    chevronsIn = { tracks?.chevrons?.value ?: 1f },
                    captionIn = { tracks?.caption?.value ?: 1f },
                    reveal = padReveal,
                    revealLabel = stringResource(R.string.a11y_synopsis_open),
                    modifier = Modifier
                        .padding(vertical = heroGap)
                        .onSizeChanged {
                            padChromePx = (it.height - posterHeightPx).coerceAtLeast(0)
                            MeasuredSeat.padChromePx = padChromePx
                        },
                )
            }
            val identityBlock: @Composable () -> Unit = {
                // Its dark disc, not its touch target, lines up with the gutter.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .onSizeChanged {
                            identityPx = it.height
                            MeasuredSeat.identityPx = it.height
                        }
                        .synopsisRecede(padReveal, reduceMotion),
                ) {
                    BackDisc(
                        description = backDescription,
                        onClick = { leave { controller.back() } },
                        modifier = Modifier
                            .offset(x = -(BackTouchSize - BackDiscSize) / 2)
                            .fadeUp({ tracks?.disc?.value ?: 1f }, CardFlipMotion.RiseDistance),
                    )
                    Spacer(Modifier.height(4.dp))
                    DetailIdentity(
                        item = item,
                        art = art,
                        title = heading,
                        hdr = hdr,
                        subtitle = subtitle?.let { it.displayName to it.language },
                        imageLoader = imageLoader,
                        logoIn = { tracks?.logo?.value ?: 1f },
                        metaIn = { tracks?.meta?.value ?: 1f },
                        chipsIn = { tracks?.chips?.value ?: 1f },
                    )
                }
            }
            val noticeBlock: @Composable () -> Unit = {
                DetailNotice(
                    refusal = refusal,
                    advisory = advisory,
                    castEnabled = castEnabled,
                    onSwitchNetwork = onSwitchNetwork,
                    onCastAnyway = onFlick,
                    modifier = Modifier
                        .fadeUp({ tracks?.caption?.value ?: 1f }, CardFlipMotion.CaptionRiseDistance)
                        .synopsisRecede(padReveal, reduceMotion),
                )
            }

            // Behind an open synopsis the page is covered, and TalkBack must not wander into it.
            val covered = if (reveal.open) Modifier.clearAndSetSemantics { } else Modifier
            if (wide) {
                Row(
                    Modifier
                        .fillMaxSize()
                        .then(covered)
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = PageGutter),
                    horizontalArrangement = Arrangement.spacedBy(PageGutter),
                ) {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(pageScroll)
                            .padding(vertical = PageVerticalPad),
                    ) {
                        identityBlock()
                        Spacer(Modifier.height(SectionGap))
                        noticeBlock()
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(padScroll)
                            .heightIn(min = viewport - bars - FitSlack)
                            .padding(vertical = PageVerticalPad),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        pad(padScroll)
                    }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .then(covered)
                        .verticalScroll(pageScroll)
                        // A minimum rather than a weight: inside a scroll there is no height
                        // to share out, and SpaceBetween over this centres the pad in
                        // whatever the identity and the notice leave.
                        .heightIn(min = viewport)
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = PageGutter, vertical = PageVerticalPad),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    identityBlock()
                    pad(pageScroll)
                    // Always placed, empty or not, so SpaceBetween has a floor to centre on and
                    // the measured height drops back to zero when the notice clears.
                    Box(
                        Modifier.fillMaxWidth().onSizeChanged {
                            noticePx = it.height
                            MeasuredSeat.noticePx = it.height
                        },
                    ) { noticeBlock() }
                }
            }
            // Not composed until the first reveal starts: most visits never open it, and its
            // copy is not worth laying out under a card in flight on every one.
            if (synopsis != null && reveal.everShown && (landed || reveal.engaged)) {
                SynopsisIntertitle(
                    reveal = reveal,
                    inks = inks,
                    details = synopsis,
                    title = synopsisTitle,
                    reduceMotion = reduceMotion,
                )
            }
        }
    }
}

/**
 * Darker than a poster page usually dares, top to bottom: the backdrop is atmosphere here,
 * and every line of copy, the back disc and the chevrons stand on it. [lift] is how far the
 * page has scrolled toward [ScrimLiftDistance].
 */
private fun cinemaScrim(lift: Float): Brush = Brush.verticalGradient(
    0f to CinemaDeep.copy(alpha = lerp(ScrimHead, ScrolledScrimAlpha, lift)),
    0.35f to CinemaDeep.copy(alpha = lerp(ScrimBand, ScrolledScrimAlpha, lift)),
    0.75f to CinemaDeep.copy(alpha = lerp(ScrimFoot, ScrolledScrimAlpha, lift)),
    1f to CinemaDeep,
)

// The backdrop is blurred on every API level now, so the wash no longer has to stand in for
// a blur the platform could not draw.
private const val ScrimHead = 0.6f
private const val ScrimBand = 0.7f
private const val ScrimFoot = 0.92f
// White and the dim ink both clear 4.5:1 over a white backdrop under this much CinemaDeep.
private const val ScrolledScrimAlpha = 0.95f
private val ScrimLiftDistance = 120.dp

private val PageGutter = 24.dp
private val PageVerticalPad = 12.dp
private val HeroGap = 20.dp
private val HeroGapCompact = 8.dp
private val ChevronGapCompact = 4.dp
private val CaptionGapCompact = 8.dp
private val SectionGap = 24.dp
private val BackDiscSize = 44.dp
private val BackTouchSize = 48.dp
private val LogoMaxWidth = 200.dp
private val LogoMaxHeight = 64.dp

/** Under this the gaps around the pad tighten, so the poster keeps the height instead. */
private val CompactHeight = 700.dp

// First-frame stand-ins for the measured blocks, scaled by the font size; the measured
// values replace them a frame later.
private val IdentityEstimate = 244.dp
private val PadChromeEstimate = 92.dp

/** The last page's measured blocks, for the next page's first frame. */
private object MeasuredSeat {
    var identityPx = 0
    var padChromePx = 0
    var noticePx = 0
}

/** Exactly 2:3 at every size; the corner is 8% of whatever width this gives. */
private val PosterMinHeight = 140.dp
private val PosterMinHeightWide = 120.dp
private val PosterMaxHeight = 390.dp
/** Rounding headroom: one pixel of overflow would make the page scrollable. */
private val FitSlack = 2.dp

@Composable
private fun BackDisc(
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) = CinemaDiscButton(FlickIcons.ChevronLeft, description, onClick, modifier)

/**
 * A 44 dp dark disc inside a 48 dp target, white glyph, white hairline. [enabled] false takes
 * it out of the hit path altogether, not merely inert, so an invisible disc never swallows a
 * touch meant for what is under it.
 */
@Composable
internal fun CinemaDiscButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(BackTouchSize)
            .pressScale(source)
            .clip(CircleShape)
            .then(
                if (enabled) {
                    Modifier
                        .clickable(
                            interactionSource = source,
                            indication = flickRipple(Color.White),
                            role = Role.Button,
                            onClick = onClick,
                        )
                        .semantics { contentDescription = description }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(BackDiscSize)
                .clip(CircleShape)
                .background(CinemaDisc)
                .border(1.dp, CinemaDiscEdge, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * Which film this is, before anything asks to be thrown: title, facts, attached subtitle.
 *
 * Two rows of facts: what the file is (resolution, range, size), then how long it runs. Every
 * one is read off the file itself, so all four are there from the first frame and nothing that
 * arrives later — the synopsis, the art — changes the block's height, or the poster's sized
 * beneath it. Larger type may still wrap the first row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailIdentity(
    item: MediaItem,
    art: FilmArt?,
    title: String,
    hdr: HdrType?,
    subtitle: Pair<String, String?>?,
    imageLoader: ImageLoader,
    logoIn: () -> Float,
    metaIn: () -> Float,
    chipsIn: () -> Float,
) {
    val colors = LocalFlickColors.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        DetailTitle(
            logoPath = art?.logo,
            title = title,
            imageLoader = imageLoader,
            modifier = Modifier.fadeUp(logoIn, CardFlipMotion.RiseDistance, flat = true),
        )
        Spacer(Modifier.height(18.dp))
        // Chips reflow on narrow or large-font windows instead of competing for one fixed row
        // and clipping the resolution or file size.
        FlowRow(
            Modifier.fillMaxWidth().fadeUp(metaIn, CardFlipMotion.RiseDistance),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Resolution, dynamic range and size are the only source facts the phone can
            // read; codec and frame rate never leave MediaStore. A fact it did not read takes
            // the outlined chip: same seat, same type, no claim.
            FactChip(text = resolutionText(item), known = item.knowsResolution)
            FactChip(text = hdrChipLabel(hdr), known = hdr != null)
            FactChip(text = Format.bytes(item.sizeBytes), known = true)
        }
        Spacer(Modifier.height(8.dp))
        RuntimeChip(
            text = runtimeText(item),
            known = item.knowsDuration,
            modifier = Modifier.fadeUp(chipsIn, CardFlipMotion.RiseDistance),
        )
        // With the file's own facts, because it is one of them: what will be sent with the
        // flick. Not a chip — those are the three the phone reads off the file itself.
        if (subtitle != null) {
            Spacer(Modifier.height(12.dp))
            AttachedSubtitleRow(
                displayName = subtitle.first,
                language = subtitle.second,
                modifier = Modifier.fadeUp(chipsIn, CardFlipMotion.RiseDistance, flat = true),
            )
        }
    }
}

/**
 * TMDB's own lettering when it has a logo for this film, the name in type when it has none
 * or the logo will not load. Either way it is the page's heading. The logo's seat is a fixed
 * height, so the facts below do not jump when it lands.
 */
@Composable
private fun DetailTitle(
    logoPath: String?,
    title: String,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
) {
    var logoFailed by remember(logoPath) { mutableStateOf(false) }
    if (logoPath != null && !logoFailed) {
        Box(
            modifier.fillMaxWidth().height(LogoMaxHeight),
            contentAlignment = Alignment.Center,
        ) {
            FilmTitleLogo(
                logoPath = logoPath,
                imageLoader = imageLoader,
                contentDescription = title,
                onFailed = { logoFailed = true },
                modifier = Modifier
                    .size(LogoMaxWidth, LogoMaxHeight)
                    .semantics { heading() },
            )
        }
    } else {
        Text(
            text = title,
            style = FlickText.headlineMedium.copy(color = Color.White),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier.semantics { heading() },
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
private fun AttachedSubtitleRow(displayName: String, language: String?, modifier: Modifier = Modifier) {
    val colors = LocalFlickColors.current
    val label = languageLabel(language) ?: displayName
    val spoken = stringResource(R.string.a11y_detail_subtitle, label)
    Row(
        modifier
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
 * The one notice the page may carry, at its foot. A refusal outranks the advisory: a file this
 * TV has already refused will not play at any bitrate, so warning about the Wi-Fi carrying it
 * is advice about a problem the user cannot reach from here.
 */
@Composable
private fun DetailNotice(
    refusal: String?,
    advisory: PreCastLinkAdvisory?,
    castEnabled: Boolean,
    onSwitchNetwork: () -> Unit,
    onCastAnyway: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        refusal != null -> RefusalCard(refusal, modifier)
        advisory != null -> LinkAdvisory(
            advisory = advisory,
            onSwitchNetwork = onSwitchNetwork,
            castEnabled = castEnabled,
            onCastAnyway = onCastAnyway,
            modifier = modifier,
        )
    }
}

/**
 * Dark glass with a hairline, like the chips and the back disc, and an [accent] only on the
 * icon: on this page the poster is the loudest thing, and a pale card would outshout it.
 */
@Composable
private fun GlassNotice(
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(FlickCorners.warning)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(CinemaNoticeTop, CinemaNoticeFoot)))
            .border(1.dp, CinemaGlassEdge, shape)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .size(NoticeIconDisc)
                .clip(CircleShape)
                .background(accent.copy(alpha = NoticeAccentWash)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(Modifier.weight(1f), content = content)
    }
}

private val NoticeIconDisc = 36.dp
private const val NoticeAccentWash = 0.16f

/**
 * A receiver has refused this file. The caution hue on the icon only: crimson would read as a
 * blocked file, and this file is not blocked — the user may have remuxed it, or be standing in
 * front of a different TV — so the pad above stays live.
 */
@Composable
private fun RefusalCard(code: String, modifier: Modifier = Modifier) {
    val colors = LocalFlickColors.current
    GlassNotice(icon = FlickIcons.Warning, accent = colors.caution, modifier = modifier) {
        Text(
            text = stringResource(R.string.detail_unplayable_title),
            style = FlickText.bodySmall.copy(color = Color.White, fontWeight = FontWeight.ExtraBold),
        )
        Text(
            text = stringResource(refusalBody(code)),
            style = FlickText.bodySmall.copy(color = CinemaInkDim),
            modifier = Modifier.padding(top = 3.dp),
        )
        Text(
            text = stringResource(refusalNote(code)),
            style = FlickText.bodySmall.copy(color = CinemaInkDim.copy(alpha = VerdictNoteAlpha)),
            modifier = Modifier.padding(top = 6.dp),
        )
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
 * play perfectly. The pad above is untouched — same enabled state, same action.
 *
 * Cyan on the icon, the LAN's colour, where the refusal takes caution: the two must not wear
 * the same clothes, and this is the softer claim.
 *
 * "Cast anyway" is the cast, not a dismissal: it runs the pad's own action under the pad's
 * own guard, for a viewer who reads the card and would rather tap than swipe.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun LinkAdvisory(
    advisory: PreCastLinkAdvisory,
    onSwitchNetwork: () -> Unit,
    castEnabled: Boolean,
    onCastAnyway: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFlickColors.current
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
    GlassNotice(
        icon = FlickIcons.Wifi,
        accent = colors.link,
        modifier = modifier.semantics { contentDescription = spoken },
    ) {
        Text(
            text = title,
            style = FlickText.bodySmall.copy(color = Color.White, fontWeight = FontWeight.ExtraBold),
        )
        Text(
            text = body,
            style = FlickText.bodySmall.copy(color = CinemaInkDim),
            modifier = Modifier.padding(top = 3.dp),
        )
        // Wraps rather than ellipsizing "Cast anyway" at large font scales.
        FlowRow(
            Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            GlassAction(
                text = stringResource(R.string.advisory_band_primary),
                filled = true,
                enabled = true,
                onClick = onSwitchNetwork,
            )
            GlassAction(
                text = stringResource(R.string.advisory_band_secondary),
                filled = false,
                enabled = castEnabled,
                onClick = onCastAnyway,
            )
        }
    }
}

/** A notice's action: a glass pill when [filled], bare white type when not; 48 dp tall. */
@Composable
private fun GlassAction(
    text: String,
    filled: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    Text(
        text = text,
        style = FlickText.labelMedium.copy(
            color = Color.White.copy(alpha = if (!enabled) 0.4f else if (filled) 1f else 0.8f),
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .pressScale(source)
            .clip(PillShape)
            .then(
                if (filled) {
                    Modifier.background(CinemaGlass).border(1.dp, CinemaGlassEdge, PillShape)
                } else {
                    Modifier
                },
            )
            .clickable(
                interactionSource = source,
                indication = flickRipple(Color.White),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 15.dp),
    )
}

/**
 * Only the in-flight handshake states. An already-Active session must not strand this
 * screen's pad when the user walks back into a detail page mid-cast.
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
 * The file's own running time. An unscanned duration is the em dash, never `durationHuman`'s
 * "0s" — a two-hour film is not zero seconds long because nothing measured it.
 */
@Composable
private fun runtimeText(item: MediaItem): String =
    if (item.knowsDuration) Format.durationHuman(item.durationMs) else stringResource(R.string.media_unknown)
