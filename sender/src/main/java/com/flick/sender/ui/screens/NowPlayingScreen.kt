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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors

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
        HdrType.DOLBY_VISION -> "Dolby Vision"
        HdrType.HDR10 -> "HDR10"
        HdrType.NONE -> "SDR"
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

    val bg = if (colors.isLight) {
        Brush.verticalGradient(listOf(Color(0xFFF6E9DC), Color(0xFFF3E4D4)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFF2A150F), Color(0xFF100C12)))
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(bg),
    ) {
        // Ambient scrim so text stays legible over the warm bed.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0x33000000),
                        0.62f to Color(0xB8000000),
                    ),
                ),
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

    // Poster still.
    Spacer(Modifier.height(16.dp))
    Box(
        Modifier
            .align(Alignment.CenterHorizontally)
            .size(width = 150.dp, height = 88.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF4A2418), Color(0xFFC56A34))))
            .border(1.dp, Color(0x29FFFFFF), RoundedCornerShape(14.dp)),
    ) {
        if (hdrIsDv) {
            Text(
                "DV",
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
        style = FlickText.heading.copy(fontSize = 17.sp, color = Color.White),
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
    Text(
        subtitle,
        style = FlickText.caption.copy(color = Color.White.copy(alpha = 0.6f)),
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
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Isolated so the running playhead recomposes only this Text, not the tree.
        TargetTimecode(scrubbing = scrubbing) { playbackState.value.targetMs }
        Text(
            Format.timecode(durationMs),
            style = FlickText.mono.copy(color = Color.White.copy(alpha = 0.6f)),
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
            tint = Color.White,
        )
    }

    Spacer(Modifier.height(16.dp))
    VolumeSlider(
        value = volume,
        onValueChange = { controller.setVolume(it) },
        modifier = Modifier.fillMaxWidth(),
        tint = Color.White,
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
            style = FlickText.caption.copy(fontSize = 9.sp, color = Color.White.copy(alpha = 0.45f)),
        )
    }
    Text(
        stringResource(R.string.np_stop),
        style = FlickText.caption.copy(fontWeight = FontWeight.SemiBold, color = colors.trouble),
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .navigationBarsPadding()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(999.dp))
            .clickable { controller.stopCast() }
            .padding(vertical = 6.dp, horizontal = 12.dp),
    )
}

/** The live scrub timecode — reads the playhead via a lambda so only this Text
 *  recomposes at pointer rate, not the whole transport region. */
@Composable
private fun TargetTimecode(scrubbing: Boolean, position: () -> Long) {
    val colors = LocalFlickColors.current
    Text(
        Format.timecode(position()),
        style = FlickText.mono.copy(
            color = if (scrubbing) colors.sparkSoft else Color.White.copy(alpha = 0.6f),
        ),
    )
}

@Composable
private fun PausedBadge() {
    val colors = LocalFlickColors.current
    Text(
        stringResource(R.string.np_paused_badge),
        style = FlickText.monoLabel.copy(color = Color.White.copy(alpha = 0.7f), fontWeight = FontWeight.Bold),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(999.dp))
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
            style = FlickText.heading.copy(color = Color.White),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.buffering_body),
            style = FlickText.caption.copy(color = Color.White.copy(alpha = 0.7f)),
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
