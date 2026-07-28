package com.flick.sender.ui.screens

import android.net.Uri
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.flick.sender.R
import com.flick.sender.ServerStateHolder
import com.flick.sender.ServerStatus
import com.flick.sender.media.MediaProbe
import com.flick.sender.media.rememberScrubFrame
import com.flick.sender.model.HdrType
import com.flick.sender.model.MediaItem
import com.flick.sender.model.PlaybackPhase
import com.flick.sender.model.PlaybackUiState
import com.flick.sender.net.FlickController
import com.flick.sender.ui.Format
import com.flick.sender.ui.components.CastPosterKey
import com.flick.sender.ui.components.LiveDot
import com.flick.sender.ui.components.LocalQualityRevealOrigin
import com.flick.sender.ui.components.PhoneScrubBar
import com.flick.sender.ui.components.SignalChip
import com.flick.sender.ui.components.TransportCluster
import com.flick.sender.ui.components.VolumeSlider
import com.flick.sender.ui.components.flickSharedFrame
import com.flick.sender.ui.components.rememberVideoFrameRequest
import com.flick.sender.ui.components.rememberVideoImageLoader
import com.flick.sender.ui.components.revealOrigin
import com.flick.sender.ui.theme.FlickCinematicTheme
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickGradients
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.Motion
import com.flick.sender.ui.theme.PillShape
import com.flick.sender.ui.theme.PosterShadow
import com.flick.sender.ui.theme.Spark
import com.flick.sender.ui.theme.flickRipple
import com.flick.sender.ui.theme.pressScale
import com.flick.sender.ui.theme.rememberReduceMotion
import kotlin.math.roundToInt

/** S6/S7/S8/S9 — the remote. Everything that matters is under the thumb. */
@Composable
fun NowPlayingScreen(
    controller: FlickController,
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    // Forced dark: the remote is cinematic regardless of the system theme.
    FlickCinematicTheme {
        RemoteScreen(controller, sharedScope, animatedScope)
    }
}

@Composable
private fun RemoteScreen(
    controller: FlickController,
    sharedScope: SharedTransitionScope?,
    animatedScope: AnimatedVisibilityScope?,
) {
    val context = LocalContext.current
    // Kept as State (not unwrapped) so the pointer-rate playhead reads can be deferred
    // into leaf composables / the draw phase instead of recomposing the whole hero
    // ~120x/s while scrubbing. Structural signals below use derivedStateOf so they only
    // invalidate when they actually change.
    val playbackState = controller.playback.collectAsState()
    val tv by controller.connectedTv.collectAsState()
    val item by controller.castingItem.collectAsState()
    val server by ServerStateHolder.state.collectAsState()
    // Kept as State for the same reason the playback clock is: the telemetry poll ticks
    // every 2 s and must stop at the leaves that show it, not rebuild the whole remote
    // (including the scrub bar under a live drag).
    val signal = rememberSignalState()
    val configuration = LocalConfiguration.current
    val compactWidth = isCompactWidth(configuration.screenWidthDp)
    val fontScale = configuration.fontScale
    val reduceMotion = rememberReduceMotion()
    val remoteScrollState = rememberScrollState()
    // Owned here rather than by the shell: the subtitles sheet belongs to the video on
    // this screen, and the shell's overlay channel is the pairing/quality one.
    var showSubtitles by rememberSaveable { mutableStateOf(false) }
    val subtitleAttached = controller.selectedSubtitle.collectAsState().value != null

    val hdr by produceState(initialValue = HdrType.NONE, item?.uri) {
        val uri = item?.uri
        value = if (uri != null) MediaProbe.detectHdr(context, uri) else HdrType.NONE
    }

    // Spark pulse ring on ghost↔target reconcile. Read only inside the ring's drawBehind
    // (via the lambda) so a 500ms pulse animation never recomposes the transport tree.
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) pulse.snapTo(1f)
        controller.pulses.collect {
            if (reduceMotion) {
                // State still reconciles; only the decorative ring is suppressed.
                pulse.snapTo(1f)
            } else {
                pulse.snapTo(0f)
                pulse.animateTo(1f, tween(500))
            }
        }
    }

    val phase by remember { derivedStateOf { playbackState.value.phase } }
    val scrubbing by remember { derivedStateOf { playbackState.value.scrubbing } }
    // Buffering stays a bounded, centered face. It must never enter the scroll
    // container because its weighted body needs finite height constraints.
    val showBuffering = phase == PlaybackPhase.BUFFERING && !scrubbing

    Box(
        Modifier
            .fillMaxSize()
            .background(FlickGradients.nowPlayingBackdrop),
    ) {
        AmbientGlow()

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                // Horizontal inset belongs to the content, not to this column: a scroll
                // container clips to its own bounds, and the poster's 28 dp drop shadow
                // needs that gutter INSIDE the clip or it is sheared off at both edges.
                .padding(top = 8.dp, bottom = 22.dp),
        ) {
            // Outside the scroll container: minimize is the way off this screen and must
            // not be something the user has to scroll back up to find.
            TopRow(
                serving = server.status == ServerStatus.RUNNING,
                signal = signal,
                compactWidth = compactWidth,
                onMinimize = { controller.minimizeNowPlaying() },
                onSignal = { controller.toggleQualitySheet(true) },
                modifier = Modifier.padding(horizontal = RemoteGutter),
            )

            // Swap to the full buffering face only when NOT scrubbing: a scrub itself
            // drives the TV into STATE_BUFFERING (seek fill), and replacing the bar
            // mid-drag would strand the gesture (onScrubEnd never fires, scrubbing sticks).
            if (showBuffering) {
                BufferingContent(signal = signal)
            } else {
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    // Measured, not read off the display: this is the box the stack is
                    // actually laid out in, so the window insets, the screen padding and
                    // the row above are already out of the figure — the only way a budget
                    // survives a gesture bar, a cutout or a display-size setting that the
                    // constants never saw.
                    val viewport = maxHeight
                    val plan = remoteHeightPlan(viewport.value.roundToInt(), fontScale)
                    Column(
                        Modifier
                            .fillMaxSize()
                            // Unconditional. A threshold that opts into scrolling can only
                            // be right for the font scale, inset depth and title length it
                            // was measured against, and everything past the miss is what
                            // gets cut off. Enabled is still gated on the drag so the
                            // scrub bar keeps the pointer through it; scrolling resumes at
                            // the terminal seek.
                            .verticalScroll(remoteScrollState, enabled = !scrubbing),
                    ) {
                        // At least a viewport tall. A weight inside a scroll container
                        // resolves against the container's MINIMUM main-axis size — with
                        // no floor the surplus spacer collapses and a roomy window renders
                        // a cramped stack with dead space under it.
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = viewport)
                                .padding(horizontal = RemoteGutter),
                        ) {
                            RemoteContent(
                                controller = controller,
                                playbackState = playbackState,
                                item = item,
                                tvName = tv?.name ?: stringResource(R.string.np_tv_generic),
                                hdr = hdr,
                                posterHeight = plan.posterHeightDp.dp,
                                gap = plan.gapDp.dp,
                                captionGap = plan.captionGapDp.dp,
                                clusterGap = plan.clusterGapDp.dp,
                                reservePreview = plan.reservePreview,
                                pulse = { pulse.value },
                                subtitleAttached = subtitleAttached,
                                onSubtitles = { showSubtitles = true },
                                sharedScope = sharedScope,
                                animatedScope = animatedScope,
                            )
                        }
                    }
                }
            }
        }

        if (showSubtitles) {
            SubtitlesSheet(controller = controller, onDismiss = { showSubtitles = false })
        }
    }
}

/** 360×250dp amber ellipse breathing above the poster; never takes a pointer. */
@Composable
private fun BoxScope.AmbientGlow() {
    val reduceMotion = rememberReduceMotion()
    val breathing = if (reduceMotion) {
        null
    } else {
        rememberInfiniteTransition(label = "glow").animateFloat(
            initialValue = Motion.GlowMinAlpha,
            targetValue = Motion.GlowMaxAlpha,
            animationSpec = infiniteRepeatable(
                tween(Motion.GlowMs, easing = Motion.Breathe),
                RepeatMode.Reverse,
            ),
            label = "glowAlpha",
        )
    }
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .offset(y = (-80).dp)
            .size(width = 360.dp, height = 250.dp)
            .graphicsLayer { alpha = breathing?.value ?: Motion.GlowMaxAlpha }
            .background(FlickGradients.ambientGlow),
    )
}

@Composable
private fun ColumnScope.TopRow(
    serving: Boolean,
    signal: State<SignalInfo>,
    compactWidth: Boolean,
    onMinimize: () -> Unit,
    onSignal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFlickColors.current
    val live = signal.value
    val signalText = if (compactWidth) live.bandLabel() else live.chipText()
    val signalHealthy = live.healthy
    val minimizeDescription = stringResource(R.string.a11y_minimize_now_playing)
    val minimizeInteraction = remember { MutableInteractionSource() }
    // The chip opens the same sheet the Metrics segment does, so it publishes its own
    // bounds too. One of the two publishing alone is the fault: the channel is spent on
    // every open, so an unarmed control would either inherit the other's origin or fly
    // the sheet out of the wrong end of the screen.
    val revealOrigin = LocalQualityRevealOrigin.current
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .pressScale(minimizeInteraction)
                .semantics { contentDescription = minimizeDescription }
                .clickable(
                    interactionSource = minimizeInteraction,
                    // The touch target is 48 dp but the fill is only 42 dp, so the
                    // ripple is sized to the fill instead of the target — a bounded
                    // one would wash 3 dp of bare backdrop on every tap.
                    indication = flickRipple(colors.onSurface, bounded = false, radius = 21.dp),
                    role = Role.Button,
                    onClick = onMinimize,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(FlickCorners.backBtn))
                    .background(colors.fillControl),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    FlickIcons.ChevronDown,
                    contentDescription = null,
                    tint = colors.onSurface,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Row(
            Modifier.weight(1f).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveDot(colors.sparkLight, size = 7.dp, pulsing = serving)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.np_serving_eyebrow),
                style = FlickText.monoEyebrow.copy(color = colors.onSurfaceDim),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SignalChip(
            text = signalText,
            onClick = onSignal,
            modifier = Modifier.revealOrigin(revealOrigin),
            healthy = signalHealthy,
        )
    }
}

@Composable
private fun ColumnScope.RemoteContent(
    controller: FlickController,
    playbackState: State<PlaybackUiState>,
    item: MediaItem?,
    tvName: String,
    hdr: HdrType,
    posterHeight: Dp,
    gap: Dp,
    captionGap: Dp,
    clusterGap: Dp,
    reservePreview: Boolean,
    pulse: () -> Float,
    subtitleAttached: Boolean,
    onSubtitles: () -> Unit,
    sharedScope: SharedTransitionScope?,
    animatedScope: AnimatedVisibilityScope?,
) {
    val colors = LocalFlickColors.current

    // Structural signals — derived so a pointer-rate playhead update (targetMs) never
    // recomposes this scope; those reads happen via lambdas in the draw/layout phase.
    val scrubbing by remember { derivedStateOf { playbackState.value.scrubbing } }
    val playing by remember { derivedStateOf { playbackState.value.playing } }
    val syncing by remember { derivedStateOf { playbackState.value.syncing } }
    val title by remember(item) {
        derivedStateOf { item?.name ?: playbackState.value.title ?: "" }
    }

    val unknown = stringResource(R.string.media_unknown)
    val hdrLabel = when (hdr) {
        HdrType.DOLBY_VISION -> stringResource(R.string.media_hdr_dolby_vision)
        HdrType.HDR10 -> stringResource(R.string.media_hdr10)
        HdrType.NONE -> stringResource(R.string.media_sdr)
    }
    val format = stringResource(
        R.string.np_meta_format,
        item?.resolutionLabel ?: unknown,
        hdrLabel,
    )
    val meta = stringResource(R.string.np_meta, format, Format.bytes(item?.sizeBytes ?: -1L), tvName)

    val seekTargetDescription = stringResource(R.string.a11y_seek_target)
    val confirmedDescription = stringResource(R.string.a11y_tv_confirmed)
    val adjustSeekDescription = stringResource(R.string.a11y_adjust_seek)
    val backDescription = stringResource(R.string.a11y_skip_back)
    val playDescription = stringResource(if (playing) R.string.a11y_pause else R.string.a11y_play)
    val playbackStateDescription =
        stringResource(if (playing) R.string.a11y_playing_state else R.string.a11y_paused_state)
    val forwardDescription = stringResource(R.string.a11y_skip_forward)

    Spacer(Modifier.height(gap))
    Poster(
        item = item,
        hdr = hdr,
        height = posterHeight,
        sharedScope = sharedScope,
        animatedScope = animatedScope,
    )

    Spacer(Modifier.height(captionGap))
    Text(
        title,
        style = FlickText.headlineLarge.copy(color = colors.onSurface),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(MetaLead))
    Text(
        meta,
        style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )

    // Two spacers, not one: the fixed gap is the hierarchy break the stack always owes,
    // and the weight is only the surplus a roomy viewport has left over. The weight
    // resolves to zero the moment the stack is taller than its container's minimum,
    // which is exactly when the fixed gap has to carry the separation alone.
    Spacer(Modifier.height(gap))
    Spacer(Modifier.weight(1f))

    // --- transport region ---
    // A still lands as often as ten times a second under a drag, and a composable that
    // returns a value has no restart scope of its own — calling rememberScrubFrame inline
    // put the whole transport region behind that decode. The pump owns the subscription;
    // only the preview card inside the bar reads what it publishes.
    val preview = remember { mutableStateOf<ImageBitmap?>(null) }
    ScrubFramePump(
        uri = item?.uri,
        positionMs = { playbackState.value.targetMs },
        enabled = scrubbing,
        sink = preview,
    )

    PhoneScrubBar(
        targetFraction = { playbackState.value.targetFraction },
        ghostFraction = { playbackState.value.confirmedFraction },
        syncing = syncing,
        framePreview = { preview.value },
        previewLabel = { Format.timecode(playbackState.value.targetMs) },
        onScrubStart = { controller.scrubStart() },
        onScrub = { controller.scrubTo(it) },
        onScrubEnd = { controller.scrubEnd() },
        bufferedFraction = { playbackState.value.bufferedFraction() },
        positionMs = { playbackState.value.targetMs },
        durationMs = { playbackState.value.durationMs },
        targetLabel = seekTargetDescription,
        confirmedLabel = confirmedDescription,
        // Only where the body is short enough for the bar to reach the top 96 dp of the
        // scroll viewport, which is the only place the preview can be clipped. Carrying
        // the headroom anywhere else buys nothing and costs a permanent 104 dp band in
        // a stack that is already scrolling.
        reservePreviewSpace = reservePreview,
        stateLabel = if (syncing) stringResource(R.string.syncing) else null,
        adjustableActionLabel = adjustSeekDescription,
        // Read in the draw phase: the wave amplitude follows the TV's own play state,
        // and a value read here would recompose the bar on every toggle.
        playing = { playbackState.value.playing },
    )

    Spacer(Modifier.height(clusterGap))
    Box(
        Modifier
            .align(Alignment.CenterHorizontally)
            .drawBehind {
                val p = pulse()
                if (p > 0f && p < 1f) {
                    drawCircle(
                        color = Spark.copy(alpha = (1f - p) * 0.5f),
                        radius = size.minDimension * (0.5f + p * 0.9f),
                        center = Offset(size.width / 2f, size.height / 2f),
                        style = Stroke(width = 3.dp.toPx()),
                    )
                }
            },
    ) {
        TransportCluster(
            playing = playing,
            onBack10 = { controller.skip(-10_000L) },
            onPlayPause = { controller.playPause() },
            onFwd10 = { controller.skip(10_000L) },
            back10Label = backDescription,
            playPauseLabel = playDescription,
            playPauseState = playbackStateDescription,
            forward10Label = forwardDescription,
        )
    }

    Spacer(Modifier.height(clusterGap))
    VolumeRow(playbackState) { controller.setVolume(it) }

    Spacer(Modifier.height(clusterGap))
    SegmentedRow(
        subtitleAttached = subtitleAttached,
        onSubtitles = onSubtitles,
        onMetrics = { controller.toggleQualitySheet(true) },
    )

    // The mock has no stop control on the remote, but this is the only in-app
    // affordance for the terminal stop; the notification action is the other one.
    StopCastControl(onStop = { controller.stopCast() })
}

/**
 * Holds the scrub decode's own subscription so nothing above it carries one, and hands
 * the result on as plain state the preview card can read for itself. The write lands in
 * the apply phase, never during composition.
 */
@Composable
private fun ScrubFramePump(
    uri: Uri?,
    positionMs: () -> Long,
    enabled: Boolean,
    sink: MutableState<ImageBitmap?>,
) {
    val frame = rememberScrubFrame(uri, positionMs, enabled)
    SideEffect { sink.value = frame }
}

/**
 * Isolated as its own scope: the press state a scale response reads would otherwise
 * recompose the whole transport tree on every touch down and up.
 */
@Composable
private fun ColumnScope.StopCastControl(onStop: () -> Unit) {
    val colors = LocalFlickColors.current
    val interaction = remember { MutableInteractionSource() }
    val stopCastingDescription = stringResource(R.string.a11y_stop_casting)
    Box(
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .padding(top = StopCastLead)
            .pressScale(interaction)
            .heightIn(min = ControlMinHeight)
            .clip(PillShape)
            .semantics(mergeDescendants = true) { contentDescription = stopCastingDescription }
            .clickable(
                interactionSource = interaction,
                indication = flickRipple(colors.onSurface),
                role = Role.Button,
                onClick = onStop,
            )
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.np_stop),
            style = FlickText.labelMedium.copy(color = colors.trouble),
        )
    }
}

/**
 * Isolated, and now inert: every reader of the level is a lambda consumed in the draw,
 * layout or semantics phase, so a volume drag — and the 10 Hz clock that shares its state
 * object — recomposes nothing here at all.
 */
@Composable
private fun VolumeRow(playbackState: State<PlaybackUiState>, onVolume: (Float) -> Unit) {
    val context = LocalContext.current
    val level: () -> Float = remember(playbackState) { { playbackState.value.volume } }
    // Formatters, not strings: building either here would put this scope back behind the
    // level it exists to keep out of composition. One is spent inside a leaf that reads
    // the level for itself, the other inside a semantics block.
    val readout: (Int) -> String = remember(context) {
        { percent -> context.getString(R.string.np_volume_percent, percent) }
    }
    val spokenValue: (Int) -> String = remember(context) {
        { percent -> context.getString(R.string.a11y_volume_value, percent) }
    }
    VolumeSlider(
        value = level,
        onValueChange = onVolume,
        modifier = Modifier.fillMaxWidth(),
        percentLabel = readout,
        accessibilityLabel = stringResource(R.string.a11y_volume),
        valueDescription = spokenValue,
        adjustableActionLabel = stringResource(R.string.a11y_adjust_volume),
    )
}

/**
 * The real local frame, scrimmed, with only the badges the file actually earns. This
 * is where the still lands when the TV confirms its first frame, so the request is
 * keyed off the file's own duration — the TV-reported clock would ask for a different
 * frame and cost the landing its cache entry.
 */
@Composable
private fun ColumnScope.Poster(
    item: MediaItem?,
    hdr: HdrType,
    height: Dp,
    sharedScope: SharedTransitionScope?,
    animatedScope: AnimatedVisibilityScope?,
) {
    val colors = LocalFlickColors.current
    val imageLoader = rememberVideoImageLoader()
    val shape = RoundedCornerShape(FlickCorners.poster)
    val request = rememberVideoFrameRequest(item?.uri, item?.durationMs ?: 0L)
    val hdrBadge = when (hdr) {
        HdrType.DOLBY_VISION -> stringResource(R.string.media_dolby_vision_badge)
        HdrType.HDR10 -> stringResource(R.string.media_hdr10_badge)
        HdrType.NONE -> null
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .shadow(28.dp, shape, clip = false, ambientColor = PosterShadow, spotColor = PosterShadow)
            .clip(shape)
            .background(colors.surfaceRaisedAlt),
    ) {
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = item?.name,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                // In the shared overlay: the flight starts outside this rounded clip.
                modifier = Modifier
                    .flickSharedFrame(sharedScope, animatedScope, CastPosterKey)
                    .fillMaxSize(),
            )
        }
        Box(Modifier.fillMaxSize().background(FlickGradients.nowPosterScrim))
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 15.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (hdrBadge != null) {
                Text(
                    hdrBadge,
                    style = FlickText.monoBadge.copy(color = colors.onSpark),
                    modifier = Modifier
                        .clip(PillShape)
                        .background(FlickGradients.premiumSheen)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }
            Text(
                stringResource(R.string.np_direct_play_badge),
                style = FlickText.monoBadge.copy(color = colors.onSpark),
                modifier = Modifier
                    .clip(PillShape)
                    .background(colors.spark)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }
    }
}

/**
 * Subtitles select an EXTERNAL file the phone serves alongside the video, so that
 * segment carries state and a command. Audio-track selection still has neither in the
 * control protocol, so it stays disabled rather than wired to a no-op.
 *
 * Metrics takes the amber: spark is the accent this product spends on the media and on
 * what it is measuring — the playhead, the DIRECT PLAY badge, the volume blade — and
 * this is the one segment that opens a reading rather than changing the cast. Two filled
 * pills and one empty still read as a set because only the hue differs; the pill, the
 * height and the icon-plus-label lockup are the same three.
 */
@Composable
private fun ColumnScope.SegmentedRow(
    subtitleAttached: Boolean,
    onSubtitles: () -> Unit,
    onMetrics: () -> Unit,
) {
    val colors = LocalFlickColors.current
    val unavailable = stringResource(R.string.np_segment_unavailable)
    // The sheet this segment opens is born at it, so the press publishes the segment's
    // own bounds to the shell's channel.
    val revealOrigin = LocalQualityRevealOrigin.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(PillShape)
            .background(colors.fillCard)
            .padding(SegmentInset),
        horizontalArrangement = Arrangement.spacedBy(SegmentInset),
    ) {
        Segment(
            icon = FlickIcons.Captions,
            label = stringResource(R.string.np_segment_subs),
            description = stringResource(R.string.a11y_np_subs),
            stateLabel = stringResource(R.string.a11y_subs_attached).takeIf { subtitleAttached },
            onClick = onSubtitles,
        )
        Segment(
            icon = FlickIcons.AudioTrack,
            label = stringResource(R.string.np_segment_audio),
            description = stringResource(R.string.a11y_np_audio),
            unavailableLabel = unavailable,
        )
        Segment(
            // Still the dial: the sheet behind this segment is a pair of gauges, and a
            // signal-bars glyph would both narrow the claim to link strength and repeat
            // the chip already reading it at the top of the screen.
            icon = FlickIcons.Speed,
            label = stringResource(R.string.np_segment_metrics),
            description = stringResource(R.string.a11y_np_metrics),
            accent = true,
            modifier = Modifier.revealOrigin(revealOrigin),
            onClick = onMetrics,
        )
    }
}

@Composable
private fun RowScope.Segment(
    icon: ImageVector,
    label: String,
    description: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    unavailableLabel: String? = null,
    stateLabel: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalFlickColors.current
    val active = onClick != null
    val fill = when {
        !active -> Color.Transparent
        accent -> colors.spark
        else -> colors.inverseSurface
    }
    val ink = when {
        !active -> colors.onSurfaceDim.copy(alpha = 0.5f)
        // 8.6:1 on the amber fill; the pale ink the other live segment carries would
        // fall to 1.6:1 on it.
        accent -> colors.onSpark
        else -> colors.onInverseSurface
    }
    val interaction = remember { MutableInteractionSource() }
    // A live segment is a filled pill on the cinematic backdrop, so its ripple takes the
    // ink that reads on its own fill, not the one that reads on the screen.
    val indication = flickRipple(ink)
    Row(
        Modifier
            .weight(1f)
            // Ahead of the press response: a scale the finger drives must not move the
            // bounds the sheet is told to be born at.
            .then(modifier)
            .then(if (active) Modifier.pressScale(interaction) else Modifier)
            .heightIn(min = ControlMinHeight)
            .clip(PillShape)
            .background(fill)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = indication,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier.semantics {
                        disabled()
                        unavailableLabel?.let { stateDescription = it }
                    }
                },
            )
            .semantics(mergeDescendants = true) {
                contentDescription = description
                if (onClick != null) stateLabel?.let { stateDescription = it }
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, style = FlickText.labelMedium.copy(color = ink), maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ColumnScope.BufferingContent(signal: State<SignalInfo>) {
    val colors = LocalFlickColors.current
    val chip = signal.value.chipText()
    val reduceMotion = rememberReduceMotion()
    // The TV is filling its reserve; nothing here is transcoding, so the indicator
    // carries no percentage and morphs rather than fills.
    val bufferingShapes = remember {
        listOf(
            MaterialShapes.SoftBurst,
            MaterialShapes.Cookie6Sided,
            MaterialShapes.Flower,
            MaterialShapes.Circle,
        )
    }
    Column(
        Modifier
            .fillMaxWidth()
            .weight(1f)
            // Carries the screen gutter itself: the column above it no longer does.
            .padding(horizontal = RemoteGutter + 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (reduceMotion) {
            // A frozen morph reads as a hang, so the reduced form is a resting shape.
            Box(
                Modifier
                    .size(BufferingIndicatorDp)
                    .background(colors.spark, MaterialShapes.Cookie6Sided.toShape()),
            )
        } else {
            ContainedLoadingIndicator(
                modifier = Modifier.size(BufferingIndicatorDp),
                containerColor = colors.fillCard,
                indicatorColor = colors.spark,
                polygons = bufferingShapes,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.buffering_title),
            style = FlickText.headlineSmall.copy(color = colors.onSurface),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.buffering_body),
            style = FlickText.bodySmall.copy(color = colors.onSurfaceDim),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(R.string.buffering_chip, chip),
            style = FlickText.monoSmall.copy(color = colors.caution),
            modifier = Modifier
                .clip(PillShape)
                .background(colors.caution.copy(alpha = 0.14f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}


private fun PlaybackUiState.bufferedFraction(): Float =
    if (durationMs > 0L) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

// Every figure below is in the SCROLLED BODY's frame — the box left after the window
// insets, the screen padding and the top row, as BoxWithConstraints measures it. None
// of them can clip a control: the body scrolls, so they only decide how generous it is.
private const val DenseViewportDp = 640

/**
 * Roughly what the transport region costs from the scrub bar down. A body this short
 * has no surplus left to spend and has to buy space back from the poster — and it is
 * the same figure that decides how near the top edge the bar can be scrolled.
 */
private const val RoomyViewportDp = 480

/**
 * The share had to come down with the stat strip, not stay where it was. Everything
 * under the poster is a control the thumb reaches for, and on the reference phone —
 * 384×832 dp, a 690 dp body — the rest of the stack costs what [remoteStackCostDp]
 * computes without it. A third of the body left the segmented row and the stop control
 * under the fold; a quarter clears both with room to spare, and the poster is still the
 * widest thing on the screen.
 */
private const val PosterViewportShare = 0.24f
private const val PosterMinDp = 104
private const val PosterMaxDp = 192
private const val CompactPosterMinDp = 72
private const val CompactPosterMaxDp = 160
private val BufferingIndicatorDp = 56.dp

// The screen's side gutter. Applied per region rather than to the whole column so the
// scrolled body's clip encloses it.
private val RemoteGutter = 18.dp

/** Android's minimum touch target, and the height every control in the cluster carries. */
private val ControlMinHeight = 48.dp

/** The segmented row's inset, around its three pills and between them. */
private val SegmentInset = 6.dp

/** The stop control's break from the segmented row above it. */
private val StopCastLead = 6.dp

// The rest of the stack, in dp at font scale 1. All five are MIRRORED rather than read —
// three are private layout constants inside components this screen does not own, two are
// line heights of a type scale that resolves fonts — and a pure JVM test cannot measure
// Compose either way. So this is the one place they are written down, and moving one of
// those components or its type means moving the figure with it. The segmented row and the
// stop control are absent on purpose: the remote lays those two out itself, from the Dp
// above, so they are the same symbols and cannot drift at all.
private const val ScrubBarDp = 75 // PhoneScrubBar: 48 dp grab + 11 dp + the ~16 dp time row
private const val TransportClusterDp = 76 // TransportCluster: the play FAB, its tallest key
private const val VolumeRowDp = 48 // VolumeSlider's own box
private const val TitleLineDp = 31 // FlickText.headlineLarge, one 31 sp line
private const val MetaLineDp = 17 // FlickText.bodyMedium, one 17 sp line

/** The fixed lead-in between the title and the meta line under it. */
private val MetaLead = 4.dp

/** How generous the remote's stack may be in the viewport it was actually given. */
internal data class RemoteHeightPlan(
    val posterHeightDp: Int,
    val dense: Boolean,
    val reservePreview: Boolean,
    val gapDp: Int,
    val captionGapDp: Int,
    val clusterGapDp: Int,
)

/**
 * What the remote's stack costs in the spacing [plan] chose, with a title of
 * [titleLines]. This is the budget [PosterViewportShare] was tuned against, written as
 * arithmetic rather than as prose so the share cannot quietly stop clearing the fold:
 * every figure the remote owns is the symbol that lays it out, and the rest are the
 * mirrored component heights above.
 *
 * Font scale is already in [plan] — in the poster and in the gaps — but not in these
 * line heights, so the figure is a floor above scale 1 rather than a reading. Scale 1 is
 * the fit that is promised; the body scrolls unconditionally for everything else.
 */
internal fun remoteStackCostDp(plan: RemoteHeightPlan, titleLines: Int): Int {
    val cluster = (ControlMinHeight * 2 + SegmentInset * 2 + StopCastLead).value.roundToInt()
    val transport = ScrubBarDp + TransportClusterDp + VolumeRowDp + cluster +
        plan.clusterGapDp * 3
    return plan.gapDp + plan.posterHeightDp + plan.captionGapDp +
        TitleLineDp * titleLines + MetaLead.value.roundToInt() + MetaLineDp +
        plan.gapDp + transport
}

/**
 * The poster is the one elastic element, so it is taken as a share of the viewport
 * rather than as what a fixed chrome figure leaves over: a subtraction is only ever
 * right for the chrome it was measured against, and it collapses the poster to its
 * floor the moment a real device disagrees. Type scale divides the share because every
 * other control in the stack grows with it and the poster is what gives that back.
 *
 * A scale below 1 shrinks the text but must not let the poster claim the room it frees
 * — the band's own ceiling is the design's, not the viewport's.
 */
internal fun remoteHeightPlan(viewportHeightDp: Int, fontScale: Float): RemoteHeightPlan {
    val scale = fontScale.coerceAtLeast(1f)
    val cramped = viewportHeightDp < RoomyViewportDp * scale
    val dense = viewportHeightDp < DenseViewportDp * scale
    return RemoteHeightPlan(
        posterHeightDp = (viewportHeightDp * PosterViewportShare / scale).roundToInt().coerceIn(
            if (cramped) CompactPosterMinDp else PosterMinDp,
            if (cramped) CompactPosterMaxDp else PosterMaxDp,
        ),
        dense = dense,
        // The scrub preview lifts 96 dp above the bar and the body clips at its
        // viewport — but scrolled to the foot, the bar still sits a whole control stack
        // (bar, transport, volume, segments, stop: ~365 dp at this band's spacing) above
        // the bottom edge. Only a viewport short enough to close that gap can ever carry
        // the bar into the top 96 dp; everywhere else the reservation is 104 dp of blank
        // to scroll past and a relocation jump on every grab.
        reservePreview = cramped,
        // Spacing is the plan's to decide, not the layout's: the fold budget is measured
        // in these three, so a rhythm the screen chose inline would be a figure the
        // budget could only ever copy.
        gapDp = if (dense) 11 else 15,
        // The title and the meta line are the poster's caption, not a band of their own:
        // with the stat strip gone they are the only thing between the still and the
        // transport, and an equal gap on both sides would leave them floating between two
        // things they belong to neither of.
        captionGapDp = if (dense) 8 else 10,
        clusterGapDp = if (dense) 16 else 24,
    )
}
