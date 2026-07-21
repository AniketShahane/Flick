package com.flick.receiver.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.FocusRequester
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.player.HdrType
import com.flick.receiver.player.PlaybackPhase
import com.flick.receiver.ui.components.QualityBadge
import com.flick.receiver.ui.components.TransportCluster
import com.flick.receiver.ui.components.TvScrubBar
import com.flick.receiver.ui.components.VolumeCells
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickMotion
import com.flick.receiver.ui.theme.FlickType
import com.flick.receiver.ui.theme.bottomScrimBrush
import com.flick.receiver.ui.theme.glass
import com.flick.receiver.ui.theme.rememberReducedMotion
import com.flick.receiver.ui.theme.rememberTvSafeAreaPadding
import java.util.Locale

/** Transient quality read (T8) — decoder / throughput / band, shown on start. */
data class QualityInfo(
    val qualityLabel: String,
    val decoder: String,
    val throughput: String,
    val bars: Int,
)

// Hoisted once (pure functions of size/weight) so the ~10 Hz chrome doesn't
// allocate a fresh TextStyle every tick while the clock runs.
private val TimecodeStyle = FlickType.monoTabular(sizeSp = 40, weight = FontWeight.Bold)
private val CaptionStyle = FlickType.monoTabular(sizeSp = 24, weight = FontWeight.Medium)

/**
 * T3–T8 · the playback canvas. [videoContent] is the full-bleed ExoPlayer surface
 * (owned by the app). Chrome is summoned by [chromeVisible] and fades on the
 * chromeFade timing; when hidden (T4) nothing is drawn over the film. Seeking
 * shows the ghost/target contract (T6); buffering dims rather than blanks (T7).
 */
@Composable
fun PlaybackScreen(
    playing: Boolean,
    phase: PlaybackPhase,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    targetMs: Long,
    seeking: Boolean,
    volume: Float,
    title: String?,
    deviceLabel: String?,
    hdr: HdrType,
    chromeVisible: Boolean,
    quality: QualityInfo?,
    onBack10: () -> Unit,
    onPlayPause: () -> Unit,
    onForward10: () -> Unit,
    onSetVolume: (Float) -> Unit,
    playFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    videoContent: @Composable () -> Unit,
) {
    val safeArea = rememberTvSafeAreaPadding()
    Box(modifier = modifier.fillMaxSize().background(FlickColor.Canvas)) {
        videoContent()

        // Dim while paused / seeking / buffering — the frame stays visible.
        val dim = when {
            phase == PlaybackPhase.Paused -> 0.5f
            seeking -> 0.42f
            phase == PlaybackPhase.Buffering -> 0.5f
            else -> 0f
        }
        if (dim > 0f) {
            Box(Modifier.fillMaxSize().background(FlickColor.Canvas.copy(alpha = dim)))
        }

        // T7 buffering
        if (phase == PlaybackPhase.Buffering) {
            BufferingOverlay(Modifier.align(Alignment.Center))
        }

        // T5 paused affordance
        if (phase == PlaybackPhase.Paused) {
            Box(Modifier.fillMaxSize()) {
                PausedPill(Modifier.align(Alignment.TopStart).padding(safeArea))
            }
        }

        // T8 quality flourish (glides in on start, holds, fades)
        if (quality != null) {
            QualityCard(quality, Modifier.align(Alignment.TopEnd).padding(safeArea))
        }

        // T3/T5/T6 chrome
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(FlickMotion.chromeFadeIn()),
            exit = fadeOut(FlickMotion.chromeFadeOut()),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ChromeControls(
                playing = playing,
                phase = phase,
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedMs = bufferedMs,
                targetMs = targetMs,
                seeking = seeking,
                volume = volume,
                title = title,
                deviceLabel = deviceLabel,
                hdr = hdr,
                onBack10 = onBack10,
                onPlayPause = onPlayPause,
                onForward10 = onForward10,
                onSetVolume = onSetVolume,
                playFocusRequester = playFocusRequester,
                safeArea = safeArea,
                interactive = chromeVisible,
            )
        }
    }
}

@Composable
private fun ChromeControls(
    playing: Boolean,
    phase: PlaybackPhase,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    targetMs: Long,
    seeking: Boolean,
    volume: Float,
    title: String?,
    deviceLabel: String?,
    hdr: HdrType,
    onBack10: () -> Unit,
    onPlayPause: () -> Unit,
    onForward10: () -> Unit,
    onSetVolume: (Float) -> Unit,
    playFocusRequester: FocusRequester,
    safeArea: androidx.compose.foundation.layout.PaddingValues,
    interactive: Boolean,
) {
    // On each reveal, land focus on play so there is always exactly one focused
    // element while chrome is up (design §1.7).
    LaunchedEffect(interactive) {
        if (interactive) runCatching { playFocusRequester.requestFocus() }
    }
    Box(
        modifier = Modifier
            // AnimatedVisibility retains this subtree for its 500ms fade-out.
            // The visual exit remains, but its descendants immediately leave both
            // the focus graph and accessibility tree when chrome hides.
            .focusProperties { canFocus = interactive }
            .then(if (interactive) Modifier else Modifier.clearAndSetSemantics { })
            .fillMaxWidth()
            .background(bottomScrimBrush()),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(safeArea),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Title / badges / timecode
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = title ?: "",
                        fontFamily = FlickType.Display,
                        fontWeight = FontWeight.Bold,
                        fontSize = 40.sp,
                        letterSpacing = (-0.01).em,
                        color = Color.White,
                    )
                    if (seeking) {
                        SyncingPill()
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            // Honest badge: DV / HDR10 / none — never a hardcoded
                            // "DOLBY VISION" for SDR or HDR10 content.
                            when (hdr) {
                                HdrType.DOLBY_VISION ->
                                    QualityBadge(stringResource(R.string.badge_dolby_vision))
                                HdrType.HDR10 ->
                                    QualityBadge(stringResource(R.string.badge_hdr10), filled = false)
                                HdrType.NONE -> Unit
                            }
                            Text(
                                text = deviceLabel?.let { stringResource(R.string.now_playing_from, it) }
                                    ?: stringResource(R.string.now_playing_source),
                                fontSize = 24.sp,
                                color = FlickColor.OnSurfaceDim,
                            )
                        }
                    }
                }
                Text(
                    text = "${clock(positionMs)} / ${clock(durationMs)}",
                    style = TimecodeStyle,
                    color = Color.White,
                )
            }

            TvScrubBar(
                durationMs = durationMs,
                confirmedMs = positionMs,
                bufferedMs = bufferedMs,
                targetMs = targetMs,
                seeking = seeking,
            )

            // Confirmed / target captions while seeking (T6)
            if (seeking) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "○ " + stringResource(R.string.scrub_confirmed, clock(positionMs)),
                        style = CaptionStyle,
                        color = FlickColor.OnSurfaceDim,
                    )
                    Text(
                        text = "● " + stringResource(R.string.scrub_target, clock(targetMs)),
                        style = CaptionStyle,
                        color = FlickColor.SparkSoft,
                    )
                }
            }

            // Transport + volume
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                TransportCluster(
                    playing = playing,
                    onBack10 = onBack10,
                    onPlayPause = onPlayPause,
                    onForward10 = onForward10,
                    playFocusRequester = playFocusRequester,
                    enabled = interactive,
                    back10ContentDescription = stringResource(R.string.transport_back_10),
                    playPauseContentDescription = stringResource(
                        if (playing) R.string.transport_pause else R.string.transport_play,
                    ),
                    forward10ContentDescription = stringResource(R.string.transport_forward_10),
                )
                Box(Modifier.weight(1f))
                VolumeCells(
                    level = volume,
                    onChange = onSetVolume,
                    enabled = interactive,
                    contentDescription = stringResource(R.string.volume),
                    stateDescription = stringResource(
                        R.string.volume_state,
                        (volume.coerceIn(0f, 1f) * 100).toInt(),
                    ),
                )
            }
        }
    }
}

@Composable
private fun SyncingPill() {
    val reducedMotion = rememberReducedMotion()
    val a = if (reducedMotion) {
        1f
    } else {
        val t = rememberInfiniteTransition(label = "sync")
        val alpha by t.animateFloat(
            initialValue = 0.5f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
            label = "syncAlpha",
        )
        alpha
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(FlickColor.Link.copy(alpha = 0.14f * a + 0.06f))
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            text = stringResource(R.string.syncing_with_phone),
            style = FlickType.monoTabular(sizeSp = 24, weight = FontWeight.Bold),
            color = FlickColor.Link.copy(alpha = a),
            letterSpacing = 0.12.em,
        )
    }
}

@Composable
private fun PausedPill(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(FlickColor.Canvas.copy(alpha = 0.4f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.paused_remote),
            style = FlickType.monoTabular(sizeSp = 24, weight = FontWeight.Bold),
            color = Color.White.copy(alpha = 0.85f),
            letterSpacing = 0.16.em,
        )
    }
}

@Composable
private fun BufferingOverlay(modifier: Modifier = Modifier) {
    val reducedMotion = rememberReducedMotion()
    val sweep = if (reducedMotion) {
        315f
    } else {
        val t = rememberInfiniteTransition(label = "buffer")
        val angle by t.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1100)),
            label = "bufferSweep",
        )
        angle
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .drawBehind {
                    val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.5.dp.toPx())
                    drawArc(
                        color = FlickColor.OnSurface.copy(alpha = 0.14f),
                        startAngle = 0f, sweepAngle = 360f, useCenter = false,
                        style = stroke,
                    )
                    drawArc(
                        color = FlickColor.Spark,
                        startAngle = sweep, sweepAngle = 90f, useCenter = false,
                        style = stroke,
                    )
                },
        )
        Text(
            text = stringResource(R.string.buffering_title),
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.9f),
        )
        Text(
            text = stringResource(R.string.buffering_detail),
            style = FlickType.monoTabular(sizeSp = 24, weight = FontWeight.Medium),
            color = FlickColor.OnSurfaceDim,
        )
    }
}

@Composable
private fun QualityCard(info: QualityInfo, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .glass(RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = info.qualityLabel,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Box(Modifier.size(8.dp).drawBehind { drawCircle(FlickColor.Live) })
        }
        QualityRow(stringResource(R.string.quality_decoder), info.decoder)
        QualityRow(stringResource(R.string.quality_throughput), info.throughput)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.quality_wifi),
                style = FlickType.monoTabular(sizeSp = 24, weight = FontWeight.Medium),
                color = FlickColor.OnSurfaceDim,
            )
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                repeat(4) { i ->
                    Box(
                        modifier = Modifier
                            .size(width = 4.dp, height = (5 + i * 3).dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(
                                if (i < info.bars) FlickColor.Live
                                else FlickColor.OnSurface.copy(alpha = 0.22f),
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun QualityRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = FlickType.monoTabular(sizeSp = 24, weight = FontWeight.Medium),
            color = FlickColor.OnSurfaceDim,
        )
        Text(
            text = value,
            style = FlickType.monoTabular(sizeSp = 24, weight = FontWeight.Medium),
            color = FlickColor.OnSurface,
        )
    }
}

private fun clock(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
