package com.flick.sender.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flick.sender.R
import com.flick.sender.media.MediaProbe
import com.flick.sender.media.rememberScrubFrame
import com.flick.sender.model.HdrType
import com.flick.sender.model.MediaItem
import com.flick.sender.model.PlaybackPhase
import com.flick.sender.model.PlaybackUiState
import com.flick.sender.net.FlickController
import com.flick.sender.ui.Format
import com.flick.sender.ui.components.ConnectChip
import com.flick.sender.ui.components.LiveDot
import com.flick.sender.ui.components.PhoneScrubBar
import com.flick.sender.ui.components.SignalChip
import com.flick.sender.ui.components.StatusKind
import com.flick.sender.ui.components.StatusPill
import com.flick.sender.ui.components.TransportCluster
import com.flick.sender.ui.components.VolumeSlider
import com.flick.sender.ui.components.rememberVideoImageLoader
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis

/** S6/S7/S8/S9 — the remote. Everything that matters is under the thumb. */
@Composable
fun NowPlayingScreen(controller: FlickController) {
    val colors = LocalFlickColors.current
    val context = LocalContext.current
    // Kept as State (not unwrapped) so the pointer-rate playhead reads can be deferred
    // into leaf composables / the draw phase instead of recomposing the whole hero
    // ~120x/s while scrubbing. Structural signals below use derivedStateOf so they only
    // invalidate when they actually change.
    val playbackState = controller.playback.collectAsState()
    val tv by controller.connectedTv.collectAsState()
    val item by controller.castingItem.collectAsState()
    val signal = rememberSignalInfo()

    val hdr by produceState(initialValue = HdrType.NONE, item?.uri) {
        val uri = item?.uri
        value = if (uri != null) MediaProbe.detectHdr(context, uri) else HdrType.NONE
    }
    val hdrLabel = when (hdr) {
        HdrType.DOLBY_VISION -> stringResource(R.string.media_hdr_dolby_vision)
        HdrType.HDR10 -> stringResource(R.string.media_hdr10)
        HdrType.NONE -> stringResource(R.string.media_sdr)
    }

    // Spark pulse ring on ghost↔target reconcile. Read only inside the ring's drawBehind
    // (via the lambda) so a 500ms pulse animation never recomposes the transport tree.
    val pulse = remember { Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
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
            .background(colors.surface),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(252.dp)
                .background(colors.surfaceTonal),
        )

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ConnectChip(name = tv?.name ?: stringResource(R.string.np_tv_generic))
                SignalChip(
                    text = signal.chipText(),
                    onClick = { controller.toggleQualitySheet(true) },
                    healthy = signal.healthy,
                )
            }

            // Swap to the full buffering face only when NOT scrubbing: a scrub itself
            // drives the TV into STATE_BUFFERING (seek fill), and replacing the bar
            // mid-drag would strand the gesture (onScrubEnd never fires, scrubbing sticks).
            if (phase == PlaybackPhase.BUFFERING && !scrubbing) {
                val buffered by remember { derivedStateOf { playbackState.value.bufferedMs } }
                BufferingContent(bufferedMs = buffered, chip = signal.chipText())
            } else {
                RemoteContent(
                    controller = controller,
                    playbackState = playbackState,
                    item = item,
                    subtitleResolution = item?.resolutionLabel ?: "4K",
                    hdrLabel = hdrLabel,
                    hdrIsDv = hdr == HdrType.DOLBY_VISION,
                    pulse = { pulse.value },
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.RemoteContent(
    controller: FlickController,
    playbackState: State<PlaybackUiState>,
    item: MediaItem?,
    subtitleResolution: String,
    hdrLabel: String,
    hdrIsDv: Boolean,
    pulse: () -> Float,
) {
    val colors = LocalFlickColors.current
    val context = LocalContext.current
    val imageLoader = rememberVideoImageLoader()

    // Structural signals — derived so a pointer-rate playhead update (targetMs) never
    // recomposes this scope; those reads happen via lambdas in the draw/layout phase.
    val scrubbing by remember { derivedStateOf { playbackState.value.scrubbing } }
    val playing by remember { derivedStateOf { playbackState.value.playing } }
    val volume by remember { derivedStateOf { playbackState.value.volume } }
    val durationMs by remember { derivedStateOf { playbackState.value.durationMs } }
    val syncing by remember { derivedStateOf { playbackState.value.syncing } }
    val title by remember(item) {
        derivedStateOf { item?.name ?: playbackState.value.title ?: "" }
    }
    val dimmed = !playing
    val subtitle = stringResource(R.string.np_sub, subtitleResolution, hdrLabel)
    val stopCastingDescription = stringResource(R.string.a11y_stop_casting)
    val seekTargetDescription = stringResource(R.string.a11y_seek_target)
    val confirmedDescription = stringResource(R.string.a11y_tv_confirmed)
    val adjustSeekDescription = stringResource(R.string.a11y_adjust_seek)
    val backDescription = stringResource(R.string.a11y_skip_back)
    val playDescription = stringResource(if (playing) R.string.a11y_pause else R.string.a11y_play)
    val playbackStateDescription = stringResource(if (playing) R.string.a11y_playing_state else R.string.a11y_paused_state)
    val forwardDescription = stringResource(R.string.a11y_skip_forward)
    val volumeDescription = stringResource(R.string.a11y_volume)
    val volumeValueDescription = stringResource(R.string.a11y_volume_value, (volume * 100).toInt())
    val adjustVolumeDescription = stringResource(R.string.a11y_adjust_volume)

    val posterRequest = remember(item?.uri, durationMs) {
        item?.uri?.let { uri ->
            ImageRequest.Builder(context)
                .data(uri)
                .videoFrameMillis((durationMs / 3L).coerceAtLeast(1_000L))
                .crossfade(true)
                .build()
        }
    }

    // A genuine local frame makes this a personal film remote rather than cloud media chrome.
    Spacer(Modifier.height(16.dp))
    Box(
        Modifier
            .align(Alignment.CenterHorizontally)
            .size(width = 176.dp, height = 104.dp)
            .clip(RoundedCornerShape(24.dp, 24.dp, 18.dp, 18.dp))
            .background(colors.surfaceRaised)
            .border(1.dp, colors.outlineHairline, RoundedCornerShape(24.dp, 24.dp, 18.dp, 18.dp)),
    ) {
        if (posterRequest != null) {
            AsyncImage(
                model = posterRequest,
                contentDescription = item?.name,
                imageLoader = imageLoader,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (hdrIsDv) {
            Text(
                stringResource(R.string.media_dv_badge),
                style = FlickText.mono.copy(fontSize = 7.5.sp, fontWeight = FontWeight.Bold, color = com.flick.sender.ui.theme.PremiumInk),
                modifier = Modifier
                    .padding(7.dp)
                    .clip(RoundedCornerShape(3.5.dp))
                    .background(com.flick.sender.ui.theme.FlickGradients.premiumSheen)
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(
        title,
        style = FlickText.heading.copy(fontSize = 20.sp, color = colors.onSurface),
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
    Text(
        subtitle,
        style = FlickText.caption.copy(color = colors.onSurfaceDim),
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .padding(top = 3.dp),
    )

    if (scrubbing) {
        Spacer(Modifier.height(12.dp))
        Box(Modifier.align(Alignment.CenterHorizontally)) {
            StatusPill(stringResource(R.string.np_following), StatusKind.CONNECTING)
        }
    } else if (dimmed) {
        Spacer(Modifier.height(12.dp))
        Box(Modifier.align(Alignment.CenterHorizontally)) {
            PausedBadge()
        }
    }

    Spacer(Modifier.weight(1f))

    // --- transport region (bottom ~34%) ---
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
        targetLabel = seekTargetDescription,
        confirmedLabel = confirmedDescription,
        stateLabel = if (syncing) stringResource(R.string.syncing) else null,
        adjustableActionLabel = adjustSeekDescription,
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Isolated so the running playhead recomposes only this Text, not the tree.
        TargetTimecode(scrubbing = scrubbing) { playbackState.value.targetMs }
        if (syncing) {
            ConfirmedTimecode { playbackState.value.confirmedMs }
        }
        Text(
            Format.timecode(durationMs),
            style = FlickText.mono.copy(color = colors.onSurfaceDim),
        )
    }

    Spacer(Modifier.height(14.dp))
    Box(
        Modifier
            .align(Alignment.CenterHorizontally)
            .drawBehind {
                val p = pulse()
                if (p > 0f && p < 1f) {
                    drawCircle(
                        color = com.flick.sender.ui.theme.Spark.copy(alpha = (1f - p) * 0.5f),
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
            tint = colors.onSurface,
            back10Label = backDescription,
            playPauseLabel = playDescription,
            playPauseState = playbackStateDescription,
            forward10Label = forwardDescription,
        )
    }

    Spacer(Modifier.height(16.dp))
    VolumeSlider(
        value = volume,
        onValueChange = { controller.setVolume(it) },
        modifier = Modifier.fillMaxWidth(),
        tint = colors.spark,
        accessibilityLabel = volumeDescription,
        valueDescription = volumeValueDescription,
        adjustableActionLabel = adjustVolumeDescription,
    )

    Spacer(Modifier.height(12.dp))
    Row(
        Modifier
            .align(Alignment.CenterHorizontally)
            .padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveDot(colors.live, size = 5.dp, modifier = Modifier.padding(end = 6.dp))
        Text(
            stringResource(R.string.np_footer),
            style = FlickText.caption.copy(fontSize = 11.sp, color = colors.onSurfaceFaint),
        )
    }
    Box(
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .navigationBarsPadding()
            .padding(bottom = 8.dp)
            .heightIn(min = 48.dp)
            .semantics { contentDescription = stopCastingDescription }
            .clickable { controller.stopCast() }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.np_stop),
            style = FlickText.caption.copy(fontWeight = FontWeight.SemiBold, color = colors.trouble),
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .padding(vertical = 6.dp, horizontal = 12.dp),
        )
    }
}

/** The live scrub timecode — reads the playhead via a lambda so only this Text
 *  recomposes at pointer rate, not the whole transport region. */
@Composable
private fun TargetTimecode(scrubbing: Boolean, position: () -> Long) {
    val colors = LocalFlickColors.current
    val targetText = Format.timecode(position())
    val targetDescription = stringResource(R.string.a11y_scrub_target, targetText)
    Text(
        targetText,
        style = FlickText.timecode.copy(
            color = if (scrubbing) colors.spark else colors.onSurfaceDim,
        ),
        modifier = Modifier.semantics { contentDescription = targetDescription },
    )
}

/** The remote exposes the authoritative TV clock only while the optimistic head leads it. */
@Composable
private fun ConfirmedTimecode(position: () -> Long) {
    val colors = LocalFlickColors.current
    val confirmedText = Format.timecode(position())
    val confirmedDescription = stringResource(R.string.a11y_scrub_confirmed, confirmedText)
    Text(
        stringResource(R.string.scrub_confirmed_visual, confirmedText),
        style = FlickText.mono.copy(color = colors.onSurfaceFaint),
        modifier = Modifier.semantics { contentDescription = confirmedDescription },
    )
}

@Composable
private fun PausedBadge() {
    val colors = LocalFlickColors.current
    Text(
        stringResource(R.string.np_paused_badge),
        style = FlickText.monoLabel.copy(color = colors.onSurfaceDim, fontWeight = FontWeight.Bold),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, colors.outline, RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.BufferingContent(
    bufferedMs: Long,
    chip: String,
) {
    val colors = LocalFlickColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = colors.spark, strokeWidth = 5.dp, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.buffering_title),
            style = FlickText.heading.copy(color = colors.onSurface),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.buffering_body),
            style = FlickText.caption.copy(color = colors.onSurfaceDim),
            modifier = Modifier.padding(horizontal = 12.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(R.string.buffering_chip, chip),
            style = FlickText.mono.copy(color = colors.caution),
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(colors.caution.copy(alpha = 0.1f))
                .border(1.dp, colors.caution.copy(alpha = 0.3f), RoundedCornerShape(999.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}
