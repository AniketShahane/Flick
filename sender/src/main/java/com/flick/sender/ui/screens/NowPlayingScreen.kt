package com.flick.sender.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import coil.request.ImageRequest
import coil.request.videoFrameMillis
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
import com.flick.sender.ui.components.LiveDot
import com.flick.sender.ui.components.PhoneScrubBar
import com.flick.sender.ui.components.SignalChip
import com.flick.sender.ui.components.TransportCluster
import com.flick.sender.ui.components.VolumeSlider
import com.flick.sender.ui.components.rememberVideoImageLoader
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
import com.flick.sender.ui.theme.rememberReduceMotion
import kotlin.math.roundToInt

/** S6/S7/S8/S9 — the remote. Everything that matters is under the thumb. */
@Composable
fun NowPlayingScreen(controller: FlickController) {
    // Forced dark: the remote is cinematic regardless of the system theme.
    FlickCinematicTheme {
        RemoteScreen(controller)
    }
}

@Composable
private fun RemoteScreen(controller: FlickController) {
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
    val compactWidth = isCompactWidth(LocalConfiguration.current.screenWidthDp)
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val dense = screenHeight < DenseScreenDp
    val showStats = screenHeight >= StatStripFloorDp
    val gap = if (dense) 11.dp else 15.dp
    val clusterGap = if (dense) 16.dp else 24.dp
    val chrome = if (showStats) (if (dense) DenseChromeDp else FullChromeDp) else BareChromeDp
    val posterHeight = (screenHeight - chrome).coerceIn(PosterMinDp, PosterMaxDp).dp

    val hdr by produceState(initialValue = HdrType.NONE, item?.uri) {
        val uri = item?.uri
        value = if (uri != null) MediaProbe.detectHdr(context, uri) else HdrType.NONE
    }

    // Spark pulse ring on ghost↔target reconcile. Read only inside the ring's drawBehind
    // (via the lambda) so a 500ms pulse animation never recomposes the transport tree.
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        controller.pulses.collect {
            pulse.snapTo(0f)
            pulse.animateTo(1f, tween(500))
        }
    }

    val phase by remember { derivedStateOf { playbackState.value.phase } }
    val scrubbing by remember { derivedStateOf { playbackState.value.scrubbing } }

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
                .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 22.dp),
        ) {
            TopRow(
                serving = server.status == ServerStatus.RUNNING,
                signal = signal,
                compactWidth = compactWidth,
                onMinimize = { controller.minimizeNowPlaying() },
                onSignal = { controller.toggleQualitySheet(true) },
            )

            // Swap to the full buffering face only when NOT scrubbing: a scrub itself
            // drives the TV into STATE_BUFFERING (seek fill), and replacing the bar
            // mid-drag would strand the gesture (onScrubEnd never fires, scrubbing sticks).
            if (phase == PlaybackPhase.BUFFERING && !scrubbing) {
                BufferingContent(signal = signal)
            } else {
                RemoteContent(
                    controller = controller,
                    playbackState = playbackState,
                    item = item,
                    tvName = tv?.name ?: stringResource(R.string.np_tv_generic),
                    hdr = hdr,
                    signal = signal,
                    posterHeight = posterHeight,
                    gap = gap,
                    clusterGap = clusterGap,
                    showStats = showStats,
                    pulse = { pulse.value },
                )
            }
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
) {
    val colors = LocalFlickColors.current
    val live = signal.value
    val signalText = if (compactWidth) live.bandLabel() else live.chipText()
    val signalHealthy = live.healthy
    val minimizeDescription = stringResource(R.string.a11y_minimize_now_playing)
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .semantics { contentDescription = minimizeDescription }
                .clickable(role = Role.Button, onClick = onMinimize),
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
        SignalChip(text = signalText, onClick = onSignal, healthy = signalHealthy)
    }
}

@Composable
private fun ColumnScope.RemoteContent(
    controller: FlickController,
    playbackState: State<PlaybackUiState>,
    item: MediaItem?,
    tvName: String,
    hdr: HdrType,
    signal: State<SignalInfo>,
    posterHeight: Dp,
    gap: Dp,
    clusterGap: Dp,
    showStats: Boolean,
    pulse: () -> Float,
) {
    val colors = LocalFlickColors.current

    // Structural signals — derived so a pointer-rate playhead update (targetMs) never
    // recomposes this scope; those reads happen via lambdas in the draw/layout phase.
    val scrubbing by remember { derivedStateOf { playbackState.value.scrubbing } }
    val playing by remember { derivedStateOf { playbackState.value.playing } }
    val durationMs by remember { derivedStateOf { playbackState.value.durationMs } }
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

    val stopCastingDescription = stringResource(R.string.a11y_stop_casting)
    val seekTargetDescription = stringResource(R.string.a11y_seek_target)
    val confirmedDescription = stringResource(R.string.a11y_tv_confirmed)
    val adjustSeekDescription = stringResource(R.string.a11y_adjust_seek)
    val backDescription = stringResource(R.string.a11y_skip_back)
    val playDescription = stringResource(if (playing) R.string.a11y_pause else R.string.a11y_play)
    val playbackStateDescription =
        stringResource(if (playing) R.string.a11y_playing_state else R.string.a11y_paused_state)
    val forwardDescription = stringResource(R.string.a11y_skip_forward)

    Spacer(Modifier.height(gap))
    Poster(item = item, hdr = hdr, durationMs = durationMs, height = posterHeight)

    Spacer(Modifier.height(gap))
    Text(
        title,
        style = FlickText.headlineLarge.copy(color = colors.onSurface),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        meta,
        style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )

    if (showStats) {
        Spacer(Modifier.height(gap))
        StatStrip(playbackState = playbackState, signal = signal)
    }

    Spacer(Modifier.weight(1f))

    // --- transport region ---
    val preview = rememberScrubFrame(item?.uri, { playbackState.value.targetMs }, scrubbing)

    PhoneScrubBar(
        targetFraction = { playbackState.value.targetFraction },
        ghostFraction = { playbackState.value.confirmedFraction },
        syncing = syncing,
        framePreview = preview,
        previewLabel = { Format.timecode(playbackState.value.targetMs) },
        onScrubStart = { controller.scrubStart() },
        onScrub = { controller.scrubTo(it) },
        onScrubEnd = { controller.scrubEnd() },
        bufferedFraction = { playbackState.value.bufferedFraction() },
        positionMs = { playbackState.value.targetMs },
        durationMs = { playbackState.value.durationMs },
        targetLabel = seekTargetDescription,
        confirmedLabel = confirmedDescription,
        stateLabel = if (syncing) stringResource(R.string.syncing) else null,
        adjustableActionLabel = adjustSeekDescription,
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
    SegmentedRow(onSignal = { controller.toggleQualitySheet(true) })

    // The mock has no stop control on the remote, but this is the only in-app
    // affordance for the terminal stop; the notification action is the other one.
    Box(
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .padding(top = 6.dp)
            .heightIn(min = 48.dp)
            .clip(PillShape)
            .semantics(mergeDescendants = true) { contentDescription = stopCastingDescription }
            .clickable(role = Role.Button) { controller.stopCast() }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.np_stop),
            style = FlickText.labelMedium.copy(color = colors.trouble),
        )
    }
}

/** Isolated: a volume drag writes the optimistic level at pointer rate. */
@Composable
private fun VolumeRow(playbackState: State<PlaybackUiState>, onVolume: (Float) -> Unit) {
    val level = playbackState.value.volume.coerceIn(0f, 1f)
    val percent = (level * 100f).roundToInt()
    VolumeSlider(
        value = level,
        onValueChange = onVolume,
        modifier = Modifier.fillMaxWidth(),
        percentLabel = stringResource(R.string.np_volume_percent, percent),
        accessibilityLabel = stringResource(R.string.a11y_volume),
        valueDescription = stringResource(R.string.a11y_volume_value, percent),
        adjustableActionLabel = stringResource(R.string.a11y_adjust_volume),
    )
}

/** The real local frame, scrimmed, with only the badges the file actually earns. */
@Composable
private fun ColumnScope.Poster(
    item: MediaItem?,
    hdr: HdrType,
    durationMs: Long,
    height: Dp,
) {
    val colors = LocalFlickColors.current
    val context = LocalContext.current
    val imageLoader = rememberVideoImageLoader()
    val shape = RoundedCornerShape(FlickCorners.poster)
    val request = remember(item?.uri, durationMs) {
        item?.uri?.let { uri ->
            ImageRequest.Builder(context)
                .data(uri)
                .videoFrameMillis((durationMs / 3L).coerceAtLeast(1_000L))
                .crossfade(true)
                .build()
        }
    }
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
                modifier = Modifier.fillMaxSize(),
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
 * SOURCE / RESERVE / LINK. The mock's DECODER card has no source — the TV picks the
 * decoder and no control frame reports it — so the slot carries the buffer reserve
 * the phone can genuinely measure, and LINK shows RSSI rather than a round trip the
 * protocol never times.
 */
@Composable
private fun StatStrip(
    playbackState: State<PlaybackUiState>,
    signal: State<SignalInfo>,
) {
    val colors = LocalFlickColors.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatCard(
            eyebrow = stringResource(R.string.np_stat_source),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                stringResource(R.string.np_stat_source_value),
                style = FlickText.bodyMedium.copy(color = colors.onSurface),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        StatCard(
            eyebrow = stringResource(R.string.np_stat_reserve),
            modifier = Modifier.weight(1f),
        ) {
            ReserveValue(playbackState)
        }
        StatCard(
            eyebrow = stringResource(R.string.np_stat_link),
            modifier = Modifier.weight(1f),
        ) {
            LinkValue(signal)
        }
    }
}

/** Isolated as a leaf: RSSI moves with every telemetry poll. */
@Composable
private fun LinkValue(signal: State<SignalInfo>) {
    val colors = LocalFlickColors.current
    val live = signal.value
    // rssi 0 with no band means "not on Wi-Fi", not "0 dBm". The full band + strength
    // form is too wide for a third of the row.
    val known = live.hasLink && live.rssiDbm != 0
    Text(
        if (known) {
            stringResource(R.string.np_stat_link_value, live.rssiDbm)
        } else {
            stringResource(R.string.media_unknown)
        },
        style = FlickText.monoSmall.copy(color = if (live.healthy) colors.link else colors.caution),
        maxLines = 1,
    )
}

/** The eyebrow and the value stay separate nodes so a screen reader speaks the value, not a label for it. */
@Composable
private fun StatCard(
    eyebrow: String,
    modifier: Modifier = Modifier,
    value: @Composable () -> Unit,
) {
    val colors = LocalFlickColors.current
    Column(
        modifier
            .clip(RoundedCornerShape(FlickCorners.statCard))
            .background(colors.fillCard)
            .padding(12.dp),
    ) {
        Text(eyebrow, style = FlickText.monoEyebrow.copy(color = colors.onSurfaceFaint), maxLines = 1)
        Spacer(Modifier.height(6.dp))
        value()
    }
}

/**
 * `bufferedMs` is the TV's absolute buffered position, so the honest reserve is what
 * sits ahead of the confirmed playhead. Isolated as a leaf: it moves with the clock.
 */
@Composable
private fun ReserveValue(playbackState: State<PlaybackUiState>) {
    val colors = LocalFlickColors.current
    val state = playbackState.value
    val known = state.durationMs > 0L && state.bufferedMs > 0L
    Text(
        if (known) {
            stringResource(
                R.string.np_stat_reserve_value,
                (state.bufferedMs - state.confirmedMs).coerceAtLeast(0L) / 1000f,
            )
        } else {
            stringResource(R.string.media_unknown)
        },
        style = FlickText.monoSmall.copy(color = colors.onSurface),
        maxLines = 1,
    )
}

/**
 * Subtitle and audio-track selection carry no state and no command in the control
 * protocol, so those two segments are shown disabled rather than wired to a no-op.
 */
@Composable
private fun ColumnScope.SegmentedRow(onSignal: () -> Unit) {
    val colors = LocalFlickColors.current
    val unavailable = stringResource(R.string.np_segment_unavailable)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(PillShape)
            .background(colors.fillCard)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Segment(
            icon = FlickIcons.Captions,
            label = stringResource(R.string.np_segment_subs),
            description = stringResource(R.string.a11y_np_subs),
            unavailableLabel = unavailable,
        )
        Segment(
            icon = FlickIcons.AudioTrack,
            label = stringResource(R.string.np_segment_audio),
            description = stringResource(R.string.a11y_np_audio),
            unavailableLabel = unavailable,
        )
        Segment(
            icon = FlickIcons.Speed,
            label = stringResource(R.string.np_segment_signal),
            description = stringResource(R.string.a11y_open_quality),
            onClick = onSignal,
        )
    }
}

@Composable
private fun RowScope.Segment(
    icon: ImageVector,
    label: String,
    description: String,
    unavailableLabel: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalFlickColors.current
    val active = onClick != null
    val ink = if (active) colors.onInverseSurface else colors.onSurfaceDim.copy(alpha = 0.5f)
    Row(
        Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .clip(PillShape)
            .background(if (active) colors.inverseSurface else Color.Transparent)
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier.semantics {
                        disabled()
                        unavailableLabel?.let { stateDescription = it }
                    }
                },
            )
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, style = FlickText.labelMedium.copy(color = ink), maxLines = 1)
    }
}

@Composable
private fun ColumnScope.BufferingContent(signal: State<SignalInfo>) {
    val colors = LocalFlickColors.current
    val chip = signal.value.chipText()
    Column(
        Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = colors.spark, strokeWidth = 5.dp, modifier = Modifier.size(56.dp))
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

// The remote has no scroll container (a vertical scroller would race the scrub
// gesture), so the poster carries the height budget: it shrinks first, and the
// stat strip drops out entirely before anything can be pushed off-screen.
private const val DenseScreenDp = 780
private const val StatStripFloorDp = 700
private const val FullChromeDp = 585
private const val DenseChromeDp = 545
private const val BareChromeDp = 500
private const val PosterMinDp = 104
private const val PosterMaxDp = 232
