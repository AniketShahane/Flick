package com.flick.receiver.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.player.DiagnosticsSnapshot
import com.flick.receiver.player.HdrType
import com.flick.receiver.player.PlaybackPhase
import com.flick.receiver.player.SubtitleTrackInfo
import com.flick.receiver.player.ThroughputSnapshot
import com.flick.receiver.ui.components.FlickTvButton
import com.flick.receiver.ui.components.GlassPanel
import com.flick.receiver.ui.components.GlassPanelTone
import com.flick.receiver.ui.components.GlassPill
import com.flick.receiver.ui.components.GlassPillContainer
import com.flick.receiver.ui.components.SpecChip
import com.flick.receiver.ui.components.TransportCluster
import com.flick.receiver.ui.components.TvScrubBar
import com.flick.receiver.ui.components.VolumeCells
import com.flick.receiver.ui.theme.BrandMark
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickIcons
import com.flick.receiver.ui.theme.FlickMotion
import com.flick.receiver.ui.theme.FlickShape
import com.flick.receiver.ui.theme.FlickType
import com.flick.receiver.ui.theme.bottomScrimBrush
import com.flick.receiver.ui.theme.rememberReducedMotion
import com.flick.receiver.ui.theme.rememberTvSafeAreaPadding
import com.flick.receiver.ui.theme.seekBurstWash
import com.flick.receiver.ui.theme.topScrimBrush
import kotlinx.coroutines.delay
import java.util.Date
import java.util.Locale

/** Transient quality read (T8) — decoder / throughput / band, shown on start. */
data class QualityInfo(
    val qualityLabel: String,
    val decoder: String,
    val throughput: String,
    val bars: Int,
)

/**
 * Which side panel the playback chrome is showing (receiver-expressive-spec.md
 * §5.4 / §5.5). The state is hoisted so the app can close a panel from its own
 * Back handling as well as from the panel itself.
 */
enum class PlaybackPanel { None, Subtitles, Metrics }

// Hoisted once (pure functions of size/weight) so the ~10 Hz chrome doesn't
// allocate a fresh TextStyle every tick while the playhead runs.
private val TimecodeStyle = FlickType.monoTabular(sizeSp = 20, weight = FontWeight.SemiBold)

/** Design 150 px ÷ 2; a minimum rather than a fixed width so h:mm:ss never clips. */
private val TimecodeMinWidth = 75.dp

/** The ±10 s burst occupies the design's 38 %-wide column on the seeked side. */
private const val SEEK_BURST_WIDTH_FRACTION = 0.38f

/** Design `tvBurst` (§6): the glyph enters at 0.7 and leaves at 1.14. */
private const val SEEK_BURST_ENTER_SCALE = 0.7f
private const val SEEK_BURST_EXIT_SCALE = 1.14f

/**
 * Burst fade-out, shorter than `tvBurst`'s own 0.72 s tail: the app clears the
 * accumulated delta 200 ms after it hides the burst, and a longer exit would
 * redraw the label as "+0s" halfway through the fade.
 */
private const val SEEK_BURST_EXIT_MS = 180

/** Design's top / bottom playback scrim coverage (§2d). */
private const val TOP_SCRIM_FRACTION = 0.26f
private const val BOTTOM_SCRIM_FRACTION = 0.56f

/** The paused chip's vertical anchor (§5.3). */
private const val PAUSED_CHIP_TOP_FRACTION = 0.28f

/** Below this the net-health dot reads as pressure rather than headroom. */
private const val WEAK_RSSI_DBM = -70

/**
 * T3–T8 · the playback canvas. [videoContent] is the full-bleed ExoPlayer surface
 * (owned by the app). Chrome is summoned by [chromeVisible] and fades on the
 * chromeFade timing; when hidden (T4) nothing is drawn over the film. Seeking
 * shows the ghost/target contract (T6); buffering dims rather than blanks (T7).
 *
 * Everything the chrome states is measured. [diagnostics] drives the spec chips,
 * the net-health pill and the metrics panel; [throughput] drives the histogram.
 * A field the receiver cannot know is omitted or drawn as an em-dash — the panel
 * never fills a gap with a plausible number.
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
    remoteSeekDeltaMs: Long? = null,
    remoteSeekSpeedLevel: Int = 1,
    remoteSeekHeld: Boolean = false,
    remoteSeekVisible: Boolean = false,
    onBack10: () -> Unit,
    onPlayPause: () -> Unit,
    onForward10: () -> Unit,
    onSetVolume: (Float) -> Unit,
    playFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    diagnostics: DiagnosticsSnapshot = DiagnosticsSnapshot.EMPTY,
    throughput: ThroughputSnapshot = ThroughputSnapshot.EMPTY,
    subtitleTracks: List<SubtitleTrackInfo> = emptyList(),
    subtitleSize: SubtitleSize = SubtitleSize.Medium,
    openPanel: PlaybackPanel = PlaybackPanel.None,
    onOpenPanel: (PlaybackPanel) -> Unit = {},
    onSelectSubtitleTrack: (String?) -> Unit = {},
    onSelectSubtitleSize: (SubtitleSize) -> Unit = {},
    onEndSession: (() -> Unit)? = null,
    videoContent: @Composable () -> Unit,
) {
    val safeArea = rememberTvSafeAreaPadding()
    val subtitlesCardFocus = remember { FocusRequester() }
    val metricsCardFocus = remember { FocusRequester() }
    val volumeFocus = remember { FocusRequester() }
    val endSessionFocus = remember { FocusRequester() }

    // Chrome and its panels are one surface: hiding the transport can never
    // leave a panel stranded over the film with nothing to dismiss it.
    LaunchedEffect(chromeVisible) {
        if (!chromeVisible && openPanel != PlaybackPanel.None) onOpenPanel(PlaybackPanel.None)
    }

    // Closing a panel hands focus back to the card that opened it, so the D-pad
    // never lands on an unrelated control after Back.
    var lastPanel by remember { mutableStateOf(PlaybackPanel.None) }
    LaunchedEffect(openPanel) {
        if (openPanel == PlaybackPanel.None) {
            when (lastPanel) {
                PlaybackPanel.Subtitles -> runCatching { subtitlesCardFocus.requestFocus() }
                PlaybackPanel.Metrics -> runCatching { metricsCardFocus.requestFocus() }
                PlaybackPanel.None -> Unit
            }
        }
        lastPanel = openPanel
    }

    Box(modifier = modifier.fillMaxSize().background(FlickColor.CanvasPlayback)) {
        videoContent()

        // Dim while paused / seeking / buffering — the frame stays visible.
        val dim = when {
            phase == PlaybackPhase.Paused -> 0.34f
            seeking -> 0.30f
            phase == PlaybackPhase.Buffering -> 0.38f
            else -> 0f
        }
        if (dim > 0f) {
            Box(Modifier.fillMaxSize().background(FlickColor.CanvasPlayback.copy(alpha = dim)))
        }

        // Scrims are part of the chrome, so they breathe with it rather than
        // sitting permanently over the film.
        val scrimAlpha by animateFloatAsState(
            targetValue = if (chromeVisible) 1f else 0f,
            animationSpec = if (chromeVisible) FlickMotion.chromeFadeIn() else FlickMotion.chromeFadeOut(),
            label = "scrimAlpha",
        )
        if (scrimAlpha > 0.01f) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = scrimAlpha }) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(TOP_SCRIM_FRACTION)
                        .background(topScrimBrush()),
                )
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(BOTTOM_SCRIM_FRACTION)
                        .background(bottomScrimBrush()),
                )
            }
        }

        // T7 buffering
        if (phase == PlaybackPhase.Buffering) {
            BufferingOverlay(Modifier.align(Alignment.Center))
        }

        // T5 paused affordance
        if (phase == PlaybackPhase.Paused) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.fillMaxHeight(PAUSED_CHIP_TOP_FRACTION))
                PausedChip()
            }
        }

        // ±10 s remote-seek feedback, restyled as the design's side burst. The
        // label stays the real accumulated delta, not a fixed "±10s".
        //
        // The burst carries `tvBurst`'s envelope (§6) — 0.7 → 1 on the way in,
        // 1 → 1.14 with a fade on the way out — but not its fixed 0.72 s
        // lifetime: a held seek must keep the accumulated delta on screen for as
        // long as the key is down, so visibility stays owned by the gesture.
        AnimatedVisibility(
            visible = remoteSeekVisible && remoteSeekDeltaMs != null,
            enter = fadeIn(tween(FlickMotion.TV_BURST_PEAK_MS, easing = FlickMotion.ChromeFade)),
            exit = fadeOut(tween(SEEK_BURST_EXIT_MS, easing = FlickMotion.ChromeFade)),
            modifier = Modifier.matchParentSize(),
        ) {
            SeekBurst(
                deltaMs = remoteSeekDeltaMs ?: 0L,
                speedLevel = remoteSeekSpeedLevel,
                held = remoteSeekHeld,
            )
        }

        // T8 quality flourish (glides in on start, holds, fades). It sits below
        // the top-chrome pills so the two never stack on the same line.
        if (quality != null) {
            QualityCard(
                info = quality,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(safeArea)
                    .padding(top = 46.dp),
            )
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(FlickMotion.chromeFadeIn()),
            exit = fadeOut(FlickMotion.chromeFadeOut()),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopChrome(
                deviceLabel = deviceLabel,
                diagnostics = diagnostics,
                safeArea = safeArea,
                interactive = chromeVisible,
                onEndSession = onEndSession,
                endSessionFocusRequester = endSessionFocus,
                subtitlesCardFocusRequester = subtitlesCardFocus,
            )
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(FlickMotion.chromeFadeIn()),
            exit = fadeOut(FlickMotion.chromeFadeOut()),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            BottomChrome(
                playing = playing,
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedMs = bufferedMs,
                targetMs = targetMs,
                seeking = seeking,
                volume = volume,
                title = title,
                hdr = hdr,
                diagnostics = diagnostics,
                throughput = throughput,
                subtitleTracks = subtitleTracks,
                subtitleSize = subtitleSize,
                openPanel = openPanel,
                onOpenPanel = onOpenPanel,
                onSelectSubtitleTrack = onSelectSubtitleTrack,
                onSelectSubtitleSize = onSelectSubtitleSize,
                onBack10 = onBack10,
                onPlayPause = onPlayPause,
                onForward10 = onForward10,
                onSetVolume = onSetVolume,
                playFocusRequester = playFocusRequester,
                subtitlesCardFocusRequester = subtitlesCardFocus,
                metricsCardFocusRequester = metricsCardFocus,
                volumeFocusRequester = volumeFocus,
                endSessionFocusRequester = if (onEndSession != null) endSessionFocus else null,
                safeArea = safeArea,
                interactive = chromeVisible,
            )
        }
    }
}

// ── Top chrome (spec §5.3) ──────────────────────────────────────────────────

@Composable
private fun TopChrome(
    deviceLabel: String?,
    diagnostics: DiagnosticsSnapshot,
    safeArea: PaddingValues,
    interactive: Boolean,
    onEndSession: (() -> Unit)?,
    endSessionFocusRequester: FocusRequester,
    subtitlesCardFocusRequester: FocusRequester,
) {
    Row(
        modifier = Modifier
            // AnimatedVisibility retains this subtree for its fade-out; its
            // descendants leave the focus graph and the accessibility tree the
            // moment chrome hides.
            .focusProperties { canFocus = interactive }
            .then(if (interactive) Modifier else Modifier.clearAndSetSemantics { })
            .fillMaxWidth()
            .padding(safeArea),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            GlassPill(
                text = deviceLabel?.let { stringResource(R.string.now_playing_flicked_from, it) }
                    ?: stringResource(R.string.app_name),
                style = FlickType.body(sizeSp = 24, weight = FontWeight.Bold, lineHeightRatio = 1.1f),
                color = FlickColor.OnSurface,
                contentPadding = PaddingValues(start = 9.dp, top = 7.dp, end = 15.dp, bottom = 7.dp),
                leading = { BrandMark(size = 17.dp, tint = FlickColor.OnSurface) },
            )
            if (onEndSession != null) {
                FlickTvButton(
                    onClick = onEndSession,
                    focusRequester = endSessionFocusRequester,
                    modifier = Modifier.focusProperties { down = subtitlesCardFocusRequester },
                    shape = FlickShape.Pill,
                    containerColor = Color.Transparent,
                    borderColor = FlickColor.OutlineSoft,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Icon(
                        imageVector = FlickIcons.LinkOff,
                        contentDescription = null,
                        tint = FlickColor.OnSurfaceDim,
                        modifier = Modifier.size(19.dp),
                    )
                    Text(
                        text = stringResource(R.string.session_end_pill),
                        style = FlickType.monoEyebrow(sizeSp = 16, trackingEm = 0.14f),
                        color = FlickColor.OnSurfaceDim,
                        maxLines = 1,
                    )
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            // No band read means no pill — the receiver never guesses a radio.
            val band = diagnostics.wifiBand
            if (band != null) {
                GlassPill(
                    text = if (diagnostics.wifiRssiDbm != 0) {
                        stringResource(R.string.net_pill, band, diagnostics.wifiRssiDbm)
                    } else {
                        stringResource(R.string.net_pill_band_only, band)
                    },
                    dotColor = netHealthColor(band, diagnostics.wifiRssiDbm),
                )
            }
            GlassPill(text = rememberWallClock())
        }
    }
}

/** The real device time, re-read on each minute boundary. */
@Composable
private fun rememberWallClock(): String {
    val context = LocalContext.current
    val formatter = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            // Wake on the minute boundary, not on a fixed interval, so the
            // displayed minute is never stale by more than a frame.
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            nowMs = System.currentTimeMillis()
        }
    }
    return remember(formatter, nowMs) { formatter.format(Date(nowMs)) }
}

/** 2.4 GHz or a weak link is amber; anything else reads healthy. */
private fun netHealthColor(band: String?, rssiDbm: Int): Color {
    val congested = band != null && band.startsWith("2.4")
    val weak = rssiDbm != 0 && rssiDbm <= WEAK_RSSI_DBM
    return if (congested || weak) FlickColor.Caution else FlickColor.Live
}

// ── Bottom chrome: side panels + the transport panel (spec §5.3) ────────────

@Composable
private fun BottomChrome(
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    targetMs: Long,
    seeking: Boolean,
    volume: Float,
    title: String?,
    hdr: HdrType,
    diagnostics: DiagnosticsSnapshot,
    throughput: ThroughputSnapshot,
    subtitleTracks: List<SubtitleTrackInfo>,
    subtitleSize: SubtitleSize,
    openPanel: PlaybackPanel,
    onOpenPanel: (PlaybackPanel) -> Unit,
    onSelectSubtitleTrack: (String?) -> Unit,
    onSelectSubtitleSize: (SubtitleSize) -> Unit,
    onBack10: () -> Unit,
    onPlayPause: () -> Unit,
    onForward10: () -> Unit,
    onSetVolume: (Float) -> Unit,
    playFocusRequester: FocusRequester,
    subtitlesCardFocusRequester: FocusRequester,
    metricsCardFocusRequester: FocusRequester,
    volumeFocusRequester: FocusRequester,
    endSessionFocusRequester: FocusRequester?,
    safeArea: PaddingValues,
    interactive: Boolean,
) {
    // On each reveal, land focus on play so there is always exactly one focused
    // element while chrome is up (design §1.7).
    LaunchedEffect(interactive) {
        if (interactive) runCatching { playFocusRequester.requestFocus() }
    }
    Column(
        modifier = Modifier
            .focusProperties { canFocus = interactive }
            .then(if (interactive) Modifier else Modifier.clearAndSetSemantics { })
            .fillMaxWidth()
            .padding(safeArea),
    ) {
        if (openPanel != PlaybackPanel.None) {
            // The panel row claims only what the transport panel leaves, so a
            // long track list scrolls instead of pushing the transport off-screen.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .padding(bottom = 10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                when (openPanel) {
                    PlaybackPanel.Subtitles -> {
                        SubtitlesPanel(
                            tracks = subtitleTracks,
                            size = subtitleSize,
                            onSelectTrack = onSelectSubtitleTrack,
                            onSelectSize = onSelectSubtitleSize,
                            onDismiss = { onOpenPanel(PlaybackPanel.None) },
                        )
                        Spacer(Modifier.weight(1f))
                    }

                    PlaybackPanel.Metrics -> {
                        Spacer(Modifier.weight(1f))
                        StreamMetricsPanel(
                            diagnostics = diagnostics,
                            throughput = throughput,
                            onDismiss = { onOpenPanel(PlaybackPanel.None) },
                        )
                    }

                    PlaybackPanel.None -> Unit
                }
            }
        }

        GlassPanel(
            modifier = Modifier.fillMaxWidth(),
            shape = FlickShape.Hero,
            tone = GlassPanelTone.Chrome,
            // Spec §5.3: 22 dp top / 26 dp sides / 20 dp bottom, three rows 17 dp
            // apart. The bottom differs from GlassPanel's symmetric default, so
            // the padding stays explicit while the row gap is inherited.
            contentPadding = PaddingValues(start = 26.dp, top = 22.dp, end = 26.dp, bottom = 20.dp),
        ) {
            TransportHeaderRow(
                title = title,
                seeking = seeking,
                hdr = hdr,
                diagnostics = diagnostics,
            )
            TransportScrubRow(
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedMs = bufferedMs,
                targetMs = targetMs,
                seeking = seeking,
            )
            TransportControlRow(
                playing = playing,
                volume = volume,
                openPanel = openPanel,
                selectedSubtitleLabel = selectedSubtitleLabel(subtitleTracks),
                metricsSubLabel = if (diagnostics.bitrateEstimateBps > 0L) {
                    stringResource(R.string.metrics_value_mbps, formatMbps(diagnostics.bitrateEstimateBps))
                } else {
                    stringResource(R.string.metrics_unavailable)
                },
                onOpenPanel = onOpenPanel,
                onBack10 = onBack10,
                onPlayPause = onPlayPause,
                onForward10 = onForward10,
                onSetVolume = onSetVolume,
                playFocusRequester = playFocusRequester,
                subtitlesCardFocusRequester = subtitlesCardFocusRequester,
                metricsCardFocusRequester = metricsCardFocusRequester,
                volumeFocusRequester = volumeFocusRequester,
                endSessionFocusRequester = endSessionFocusRequester,
                interactive = interactive,
            )
        }
    }
}

@Composable
private fun TransportHeaderRow(
    title: String?,
    seeking: Boolean,
    hdr: HdrType,
    diagnostics: DiagnosticsSnapshot,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = if (seeking) {
                    stringResource(R.string.syncing_with_phone)
                } else {
                    stringResource(R.string.now_playing_eyebrow)
                },
                style = FlickType.monoEyebrow(sizeSp = 16, trackingEm = 0.2f),
                color = if (seeking) FlickColor.Spark else FlickColor.SparkBright,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = title.orEmpty(),
                style = FlickType.display(sizeSp = 34, trackingEm = -0.045f, lineHeightRatio = 0.98f),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val chips = specChips(diagnostics, hdr)
        if (chips.isNotEmpty()) {
            // Design `flex:none` on the chip group against `min-width:0` on the
            // title: the chips measure to their own content and the weighted
            // title column takes everything they do not need. Giving the group a
            // weight instead would reserve half the panel for two short chips.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                chips.forEach { chip ->
                    SpecChip(
                        text = chip,
                        style = FlickType.monoEyebrow(sizeSp = 16, trackingEm = 0.06f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TransportScrubRow(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    targetMs: Long,
    seeking: Boolean,
) {
    // While seeking the flanking timecodes read the target the user is steering
    // to; the bar itself keeps drawing the confirmed ghost behind it.
    val shownMs = if (seeking) targetMs else positionMs
    val remainingMs = (durationMs - shownMs).coerceAtLeast(0L)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = clock(shownMs),
            style = TimecodeStyle,
            color = if (seeking) FlickColor.Spark else Color.White,
            modifier = Modifier.widthIn(min = TimecodeMinWidth),
            maxLines = 1,
            softWrap = false,
        )
        TvScrubBar(
            durationMs = durationMs,
            confirmedMs = positionMs,
            bufferedMs = bufferedMs,
            targetMs = targetMs,
            seeking = seeking,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.scrub_remaining, clock(remainingMs)),
            style = TimecodeStyle,
            color = FlickColor.OnSurfaceDim,
            modifier = Modifier.widthIn(min = TimecodeMinWidth),
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * `[Subtitles] ⟨back10 · play · fwd10⟩ [volume] [Stream metrics]`.
 *
 * The two flanking boxes carry equal weight, which centres the transport group
 * and lets either card ellipsise rather than overflow the panel on a narrow
 * viewport.
 *
 * Up/Down are wired explicitly across the row. Physical Left/Right are captured
 * by `TvRemoteKeyPolicy` as ±10 s seeks and never reach Compose during playback,
 * so the vertical axis is the only one available for traversal.
 */
@Composable
private fun TransportControlRow(
    playing: Boolean,
    volume: Float,
    openPanel: PlaybackPanel,
    selectedSubtitleLabel: String?,
    metricsSubLabel: String,
    onOpenPanel: (PlaybackPanel) -> Unit,
    onBack10: () -> Unit,
    onPlayPause: () -> Unit,
    onForward10: () -> Unit,
    onSetVolume: (Float) -> Unit,
    playFocusRequester: FocusRequester,
    subtitlesCardFocusRequester: FocusRequester,
    metricsCardFocusRequester: FocusRequester,
    volumeFocusRequester: FocusRequester,
    endSessionFocusRequester: FocusRequester?,
    interactive: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            PanelCard(
                glyph = FlickIcons.ClosedCaption,
                title = stringResource(R.string.subtitles_card_title),
                state = selectedSubtitleLabel ?: stringResource(R.string.subtitles_state_off),
                open = openPanel == PlaybackPanel.Subtitles,
                enabled = interactive,
                focusRequester = subtitlesCardFocusRequester,
                onClick = {
                    onOpenPanel(
                        if (openPanel == PlaybackPanel.Subtitles) PlaybackPanel.None
                        else PlaybackPanel.Subtitles,
                    )
                },
                modifier = Modifier.focusProperties {
                    up = when {
                        openPanel == PlaybackPanel.Subtitles -> FocusRequester.Default
                        endSessionFocusRequester != null -> endSessionFocusRequester
                        else -> FocusRequester.Default
                    }
                    down = playFocusRequester
                },
            )
        }

        Box(
            modifier = Modifier.focusProperties {
                up = subtitlesCardFocusRequester
                down = volumeFocusRequester
            },
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
        }

        VolumeCells(
            level = volume,
            onChange = onSetVolume,
            enabled = interactive,
            contentDescription = stringResource(R.string.volume),
            stateDescription = stringResource(
                R.string.volume_state,
                (volume.coerceIn(0f, 1f) * 100).toInt(),
            ),
            modifier = Modifier
                .focusRequester(volumeFocusRequester)
                .focusProperties {
                    up = playFocusRequester
                    down = metricsCardFocusRequester
                },
        )

        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            PanelCard(
                glyph = FlickIcons.Monitoring,
                title = stringResource(R.string.metrics_card_title),
                state = metricsSubLabel,
                open = openPanel == PlaybackPanel.Metrics,
                enabled = interactive,
                focusRequester = metricsCardFocusRequester,
                onClick = {
                    onOpenPanel(
                        if (openPanel == PlaybackPanel.Metrics) PlaybackPanel.None
                        else PlaybackPanel.Metrics,
                    )
                },
                modifier = Modifier.focusProperties {
                    up = if (openPanel == PlaybackPanel.Metrics) {
                        FocusRequester.Default
                    } else {
                        volumeFocusRequester
                    }
                },
            )
        }
    }
}

/**
 * A side card in the control row. While its panel is [open] it inverts to an
 * opaque amber fill with `OnSpark` ink — and takes the white ring, because amber
 * on amber vanishes.
 */
@Composable
private fun PanelCard(
    glyph: ImageVector,
    title: String,
    state: String,
    open: Boolean,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlickTvButton(
        onClick = onClick,
        modifier = modifier,
        selected = open,
        enabled = enabled,
        focusRequester = focusRequester,
        shape = FlickShape.Md,
        ringColor = if (open) FlickColor.FocusRingOnSpark else FlickColor.FocusRing,
        containerColor = if (open) FlickColor.Spark else null,
        borderColor = if (open) Color.Transparent else null,
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            imageVector = glyph,
            contentDescription = null,
            tint = if (open) FlickColor.OnSpark else Color.White,
            modifier = Modifier.size(19.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                style = FlickType.body(sizeSp = 24, weight = FontWeight.Bold, lineHeightRatio = 1.1f),
                color = if (open) FlickColor.OnSpark else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state,
                style = FlickType.monoEyebrow(sizeSp = 16, trackingEm = 0.14f),
                color = if (open) FlickColor.OnSparkDim else FlickColor.OnSurfaceDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── Overlays ────────────────────────────────────────────────────────────────

@Composable
private fun AnimatedVisibilityScope.SeekBurst(deltaMs: Long, speedLevel: Int, held: Boolean) {
    val forward = deltaMs >= 0L
    val reducedMotion = rememberReducedMotion()
    val sign = if (deltaMs < 0L) "−" else "+"
    val seconds = kotlin.math.abs(deltaMs / 1_000L)

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(if (forward) Alignment.CenterEnd else Alignment.CenterStart)
                .fillMaxWidth(SEEK_BURST_WIDTH_FRACTION)
                .fillMaxHeight()
                .seekBurstWash(fromRight = forward),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                // Only the glyph and its label carry the scale; the wash behind
                // them is edge-anchored and would slide if it scaled too.
                modifier = if (reducedMotion) {
                    Modifier
                } else {
                    Modifier.animateEnterExit(
                        enter = scaleIn(
                            animationSpec = tween(
                                durationMillis = FlickMotion.TV_BURST_PEAK_MS,
                                easing = FlickMotion.FlickSettle,
                            ),
                            initialScale = SEEK_BURST_ENTER_SCALE,
                        ),
                        exit = scaleOut(
                            animationSpec = tween(
                                durationMillis = SEEK_BURST_EXIT_MS,
                                easing = FlickMotion.ChromeFade,
                            ),
                            targetScale = SEEK_BURST_EXIT_SCALE,
                        ),
                        label = "seekBurstScale",
                    )
                },
                verticalArrangement = Arrangement.spacedBy(9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = if (forward) FlickIcons.Forward10 else FlickIcons.Replay10,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(60.dp),
                )
                Text(
                    text = if (held) {
                        stringResource(
                            R.string.remote_seek_hold,
                            sign,
                            seconds,
                            speedLevel.coerceIn(1, 3),
                        )
                    } else {
                        stringResource(R.string.remote_seek_step, sign, seconds)
                    },
                    style = FlickType.display(sizeSp = 24, trackingEm = -0.03f),
                    color = Color.White,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PausedChip(modifier: Modifier = Modifier) {
    GlassPillContainer(
        modifier = modifier,
        contentPadding = PaddingValues(start = 12.dp, top = 9.dp, end = 17.dp, bottom = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Icon(
            imageVector = FlickIcons.Pause,
            contentDescription = null,
            tint = FlickColor.Spark,
            modifier = Modifier.size(23.dp),
        )
        Text(
            text = stringResource(R.string.paused_title),
            style = FlickType.display(sizeSp = 24, trackingEm = -0.03f),
            color = Color.White,
            maxLines = 1,
        )
    }
}

@Composable
private fun BufferingOverlay(modifier: Modifier = Modifier) {
    val reducedMotion = rememberReducedMotion()
    val sweep = if (reducedMotion) {
        315f
    } else {
        val transition = rememberInfiniteTransition(label = "buffer")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = FlickMotion.tvSpin(),
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
                .size(48.dp)
                .drawBehind {
                    val stroke = Stroke(width = 3.5.dp.toPx())
                    drawArc(
                        color = FlickColor.Spark.copy(alpha = 0.22f),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = stroke,
                    )
                    drawArc(
                        color = FlickColor.Spark,
                        startAngle = sweep,
                        sweepAngle = 90f,
                        useCenter = false,
                        style = stroke,
                    )
                },
        )
        Text(
            text = stringResource(R.string.buffering_title),
            style = FlickType.display(sizeSp = 27, trackingEm = -0.04f, lineHeightRatio = 1.05f),
            color = Color.White,
            maxLines = 1,
        )
        Text(
            text = stringResource(R.string.buffering_detail),
            style = FlickType.body(sizeSp = 24),
            color = FlickColor.OnSurfaceDim,
            maxLines = 1,
        )
    }
}

@Composable
private fun QualityCard(info: QualityInfo, modifier: Modifier = Modifier) {
    GlassPanel(
        modifier = modifier,
        shape = FlickShape.Xl,
        tone = GlassPanelTone.Panel,
        contentPadding = PaddingValues(horizontal = 15.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
        riseDistance = 12.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = info.qualityLabel,
                style = FlickType.body(sizeSp = 24, weight = FontWeight.Bold, lineHeightRatio = 1.1f),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.size(7.dp).drawBehind { drawCircle(FlickColor.Live) })
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
                style = FlickType.monoEyebrow(sizeSp = 16, trackingEm = 0.12f),
                color = FlickColor.OnPanelLabel,
                maxLines = 1,
            )
            Spacer(Modifier.size(18.dp))
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                repeat(4) { i ->
                    Box(
                        modifier = Modifier
                            .size(width = 4.dp, height = (5 + i * 3).dp)
                            .background(
                                if (i < info.bars) FlickColor.Live else FlickColor.TrackBase,
                                FlickShape.Sm,
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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = FlickType.monoEyebrow(sizeSp = 16, trackingEm = 0.12f),
            color = FlickColor.OnPanelLabel,
            maxLines = 1,
        )
        Spacer(Modifier.size(18.dp))
        Text(
            text = value,
            style = FlickType.monoTabular(sizeSp = 20, weight = FontWeight.SemiBold),
            color = FlickColor.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── Real-telemetry labels (spec §7 — omit, never fabricate) ─────────────────

/**
 * The spec's three chips: resolution + HDR class, audio codec + channel count,
 * video codec. Each half is dropped when its source value is unknown, and a chip
 * with nothing left to say is not rendered at all. The design's fourth "18.4 GB"
 * chip is cut — the receiver streams byte ranges and never learns the file's
 * length.
 */
@Composable
private fun specChips(diagnostics: DiagnosticsSnapshot, hdr: HdrType): List<String> {
    val chips = mutableListOf<String>()

    val resolution = resolutionChipRes(diagnostics.width, diagnostics.height)?.let { stringResource(it) }
    val hdrLabel = hdrChipRes(hdr, diagnostics.videoMimeType != null)?.let { stringResource(it) }
    when {
        resolution != null && hdrLabel != null ->
            chips += stringResource(R.string.chip_video, resolution, hdrLabel)
        resolution != null -> chips += resolution
        hdrLabel != null -> chips += hdrLabel
    }

    val codec = audioCodecRes(diagnostics.audioMimeType)?.let { stringResource(it) }
    val channels = channelsChipLabel(diagnostics.audioChannelCount)
    when {
        codec != null && channels != null -> chips += stringResource(R.string.chip_audio, codec, channels)
        codec != null -> chips += codec
        channels != null -> chips += channels
    }

    // A Dolby Vision stream carries the same name in its HDR class and its video
    // MIME; one chip is a spec, two is a stutter.
    val videoCodec = videoCodecRes(diagnostics.videoMimeType)?.let { stringResource(it) }
    if (videoCodec != null && chips.none { it.contains(videoCodec) }) chips += videoCodec

    return chips
}

private fun resolutionChipRes(width: Int, height: Int): Int? = when {
    width >= 3840 || height >= 2160 -> R.string.chip_resolution_4k
    height >= 1440 -> R.string.chip_resolution_1440p
    height >= 1080 -> R.string.chip_resolution_1080p
    height >= 720 -> R.string.chip_resolution_720p
    height > 0 -> R.string.chip_resolution_sd
    else -> null
}

/**
 * `HdrType.NONE` means "SDR" only once the video format is actually known —
 * until then it means "not established yet", and the chip is omitted.
 */
private fun hdrChipRes(hdr: HdrType, formatKnown: Boolean): Int? = when (hdr) {
    HdrType.DOLBY_VISION -> R.string.chip_hdr_dolby_vision
    HdrType.HDR10 -> R.string.chip_hdr_hdr10
    HdrType.NONE -> if (formatKnown) R.string.chip_hdr_sdr else null
}

@Composable
private fun channelsChipLabel(count: Int): String? = when {
    count <= 0 -> null
    count == 1 -> stringResource(R.string.chip_channels_mono)
    count == 2 -> stringResource(R.string.chip_channels_stereo)
    count == 6 -> stringResource(R.string.chip_channels_51)
    count == 8 -> stringResource(R.string.chip_channels_71)
    else -> stringResource(R.string.chip_channels_other, count)
}

/** Video sample MIME → display name; null when the MIME is not one we can name. */
internal fun videoCodecRes(mimeType: String?): Int? = when (mimeType?.lowercase(Locale.US)) {
    "video/dolby-vision" -> R.string.codec_dolby_vision
    "video/hevc", "video/x-h265" -> R.string.codec_hevc
    "video/avc", "video/x-h264" -> R.string.codec_avc
    "video/av01" -> R.string.codec_av1
    "video/x-vnd.on2.vp9" -> R.string.codec_vp9
    "video/mp4v-es" -> R.string.codec_mpeg4
    else -> null
}

/** Audio sample MIME → display name; null when the MIME is not one we can name. */
internal fun audioCodecRes(mimeType: String?): Int? = when (mimeType?.lowercase(Locale.US)) {
    "audio/eac3-joc" -> R.string.codec_eac3_joc
    "audio/eac3" -> R.string.codec_eac3
    "audio/ac3" -> R.string.codec_ac3
    "audio/mp4a-latm" -> R.string.codec_aac
    "audio/vnd.dts" -> R.string.codec_dts
    "audio/vnd.dts.hd" -> R.string.codec_dts_hd
    "audio/true-hd" -> R.string.codec_truehd
    "audio/opus" -> R.string.codec_opus
    "audio/flac" -> R.string.codec_flac
    "audio/vorbis" -> R.string.codec_vorbis
    "audio/mpeg", "audio/mpeg-l1", "audio/mpeg-l2" -> R.string.codec_mp3
    "audio/raw" -> R.string.codec_pcm
    else -> null
}

/** The selected track's own name, uppercased for the card's mono state line. */
@Composable
private fun selectedSubtitleLabel(tracks: List<SubtitleTrackInfo>): String? {
    val selected = tracks.firstOrNull { it.isSelected }
    val numbered = if (selected != null && selected.label == null) {
        stringResource(R.string.subtitles_track_fallback, selected.trackNumber)
    } else {
        null
    }
    val label = selected?.label ?: numbered
    return label?.uppercase(Locale.getDefault())
}

// ── Formatting ──────────────────────────────────────────────────────────────

internal fun formatMbps(bitrateBps: Long): String =
    String.format(Locale.US, "%.1f", bitrateBps.coerceAtLeast(0L) / 1_000_000.0)

internal fun formatSeconds(millis: Long): String =
    String.format(Locale.US, "%.1f", millis.coerceAtLeast(0L) / 1_000.0)

/** 23.976 stays 23.976; 60.0 renders as 60 — no trailing zero noise on a stat. */
internal fun formatFrameRate(fps: Float): String {
    val text = String.format(Locale.US, "%.3f", fps)
    return if (text.contains('.')) text.trimEnd('0').trimEnd('.') else text
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
