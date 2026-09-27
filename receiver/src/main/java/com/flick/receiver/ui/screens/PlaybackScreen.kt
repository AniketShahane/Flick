package com.flick.receiver.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateBounds
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.player.BandNotice
import com.flick.receiver.player.BufferingPlate
import com.flick.receiver.player.DiagnosticsSnapshot
import com.flick.receiver.player.HdrType
import com.flick.receiver.player.OrientationHint
import com.flick.receiver.player.PlaybackPhase
import com.flick.receiver.player.SubtitleTrackInfo
import com.flick.receiver.player.ThroughputSnapshot
import com.flick.receiver.player.TurnNote
import com.flick.receiver.player.VideoRotation
import com.flick.receiver.ui.components.FlickLoader
import com.flick.receiver.ui.components.FlickOutlinedChromeBorderWidth
import com.flick.receiver.ui.components.FlickPresence
import com.flick.receiver.ui.components.FlickSwap
import com.flick.receiver.ui.components.FlickTvButton
import com.flick.receiver.ui.components.FocusBeaconHost
import com.flick.receiver.ui.components.GlassPanel
import com.flick.receiver.ui.components.GlassPanelTone
import com.flick.receiver.ui.components.GlassPill
import com.flick.receiver.ui.components.InkIcon
import com.flick.receiver.ui.components.InkText
import com.flick.receiver.ui.components.PlayPauseGlyph
import com.flick.receiver.ui.components.PrimaryTransportGlyphSize
import com.flick.receiver.ui.components.PrimaryTransportTargetSize
import com.flick.receiver.ui.components.SpecChip
import com.flick.receiver.ui.components.TelemetryReveal
import com.flick.receiver.ui.components.TransportCluster
import com.flick.receiver.ui.components.TvOriginReveal
import com.flick.receiver.ui.components.TvRevealOrigin
import com.flick.receiver.ui.components.TvScrubBar
import com.flick.receiver.ui.components.VolumeCells
import com.flick.receiver.ui.components.landTvFocus
import com.flick.receiver.ui.components.rememberTvRevealOrigin
import com.flick.receiver.ui.components.tvRevealSource
import com.flick.receiver.ui.theme.BOTTOM_SCRIM_FRACTION
import com.flick.receiver.ui.theme.BrandMark
import com.flick.receiver.ui.theme.DimCause
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickDimens
import com.flick.receiver.ui.theme.FlickIcons
import com.flick.receiver.ui.theme.FlickMotion
import com.flick.receiver.ui.theme.FlickShape
import com.flick.receiver.ui.theme.FlickSpace
import com.flick.receiver.ui.theme.FlickType
import com.flick.receiver.ui.theme.LocalReducedMotion
import com.flick.receiver.ui.theme.TOP_SCRIM_FRACTION
import com.flick.receiver.ui.theme.bottomScrimBrush
import com.flick.receiver.ui.theme.dimEase
import com.flick.receiver.ui.theme.dimSpec
import com.flick.receiver.ui.theme.glassPanel
import com.flick.receiver.ui.theme.glassState
import com.flick.receiver.ui.theme.playbackDimCause
import com.flick.receiver.ui.theme.playbackDimTarget
import com.flick.receiver.ui.theme.rememberTvSafeAreaPadding
import com.flick.receiver.ui.theme.seekAccentIntensity
import com.flick.receiver.ui.theme.seekBurstWash
import com.flick.receiver.ui.theme.sparkShadow
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
enum class PlaybackPanel { None, Subtitles, Orientation, Metrics }

// Hoisted once (pure functions of size/weight) so the ~10 Hz chrome doesn't
// allocate a fresh TextStyle every tick while the playhead runs.
private val TimecodeStyle = FlickType.monoTabular(sizeSp = 16, weight = FontWeight.SemiBold)

/**
 * Six characters of the 16 sp tabular advance (0.6 em). A floor, not a width:
 * m:ss cannot make the bar jump, and h:mm:ss measures straight past it.
 */
private val TimecodeMinWidth = 60.dp

/**
 * Each chrome group leaves by sinking this fraction of `TvRise` toward its own
 * edge, on the same faster spring `glassPanelExit` uses. A surface that retraces
 * its whole entrance reads as being dragged off screen; a short sink, quicker,
 * reads as dismissal — which is what it is. It is a fixed distance rather than a
 * share of the group's height because the 250 ms film-exit fade leaves the travel
 * visible, and at a 24 Hz window pin a long one strobes. The translation is a
 * `graphicsLayer` transform rather than the placement
 * offset `glassPanelEnter`/`glassPanelExit` use, because this chrome sits over a
 * live decoder and may not trigger a placement pass while it moves.
 */
private const val CHROME_EXIT_TRAVEL = 0.5f

/**
 * Any panel ↔ any other is one panel changing anchor and width, not two panels
 * swapping places. Damping 0.8 at the medium-low stiffness is the TV bias: enough
 * settle to carry weight, too little overshoot to wobble at 55 inches.
 */
private val PanelTravel = BoundsTransform { _, _ -> FlickMotion.panelTravelRect() }

/** The ±10 s burst occupies the design's 38 %-wide column on the seeked side. */
private const val SEEK_BURST_WIDTH_FRACTION = 0.38f

/** Design `tvBurst` (§6): the glyph enters at 0.7 and leaves at 1.14. */
private const val SEEK_BURST_ENTER_SCALE = 0.7f
private const val SEEK_BURST_EXIT_SCALE = 1.14f

/**
 * Each accepted step lands as an impulse: the glyph column snaps to this scale and
 * springs back. One step of the key, one visible kick — a held seek that emits four
 * pulses kicks four times, so the burst reports the protocol rather than a mood.
 */
private const val SEEK_BURST_IMPULSE_SCALE = 1.06f

/** How far the impulse throws the glyph column toward the seeked edge. */
private val SeekBurstNudge = 4.dp

/** Per-step rotation of the ring glyph — it turns the way the film is moving. */
private const val SEEK_BURST_GLYPH_SPIN = 36f

/**
 * The finished chip's vertical anchor (§5.3). It is 2 % below where the top scrim
 * ends, and it stays there: the chip carries its own plate rather than borrowing
 * a scrim that is already fully transparent at this height — see
 * `UNSCRIMMED_BAND` and `FlickColor.GlassState`.
 */
private const val STATE_CHIP_TOP_FRACTION = 0.28f

/**
 * How much amber is left standing while paused with the chrome down.
 *
 * The key rests here as a **state signal, not a control**: nothing focusable,
 * nothing clickable, and centre/up/down bring the whole chrome back. Left and
 * right do not — they stay the blind seek they are over any hidden chrome, paused
 * or playing, because a paused film is exactly when a viewer wants to step
 * through it without a panel in the way.
 *
 * The 80 % goes on the FILL and not on the whole key. Keeping the ink solid makes
 * the state survive both ends of an arbitrary frame while the brighter fill and
 * the primary key's small amber shadow keep it visibly active:
 *
 * | frame (with the 0.34 paused dim) | `OnSpark` glyph vs its fill | fill vs frame |
 * |---|---|---|
 * | white | solid dark ink carries the glyph | bright amber silhouette |
 * | black | solid dark ink stays distinct | amber silhouette carries the key |
 *
 * See `PlaybackContrastTest` for the measured 3:1 graphical floor.
 */
internal const val PAUSED_REST_FILL_ALPHA = 0.8f

/** A fixed brush: no shader is rebuilt as the live playback surface invalidates. */
private val PausedRestFill = Brush.verticalGradient(
    listOf(
        FlickColor.SparkLight.copy(alpha = PAUSED_REST_FILL_ALPHA),
        FlickColor.SparkBright.copy(alpha = PAUSED_REST_FILL_ALPHA),
        FlickColor.Spark.copy(alpha = PAUSED_REST_FILL_ALPHA),
    ),
)

/**
 * The rebuffer plate's loader. Smaller than [FlickLoader]'s own default: this
 * plate's padding and both of its type sizes were measured around a 40 dp ring.
 */
private val BufferingLoaderSize = 40.dp

/** Below this the net-health dot reads as pressure rather than headroom. */
private const val WEAK_RSSI_DBM = -70

/**
 * Whether the primary transport key is a control at all.
 *
 * `PlaybackPhase.Ended` with no `onReplay` is the one case where it is not. The
 * default play action resumes, and a resume past the end of the film only sets
 * `playWhenReady` on a player that has nothing left to play — so the key would be
 * the single focused, enabled affordance on a screen that has just told the
 * viewer the film is finished, and pressing it would do nothing at all. It is
 * disabled instead, and the entry focus and the row's vertical chain both route
 * around it.
 *
 * This is a fallback, not a feature: give the screen a real `onReplay` and it
 * never applies.
 */
internal fun primaryTransportLive(phase: PlaybackPhase, onReplay: (() -> Unit)?): Boolean =
    phase != PlaybackPhase.Ended || onReplay != null

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
 *
 * **The film is not ours.** Every surface here may end up over a snow field or a
 * daylight exterior, so nothing on this screen leans on the content being dark.
 * The two scrims cover the top 26 % and the bottom 56 % and deliberately leave
 * `UNSCRIMMED_BAND` between them uncovered — extending them would darken the one
 * part of the frame the viewer is actually watching. Everything that lands in
 * that band therefore owns its backdrop: [GlassPanelTone.State].
 *
 * [orientationHint] is the one thing on this screen the viewer cannot read off the
 * film: which way Flick turned the picture, and where the control for it is. The
 * screen renders the decision and never makes it — see `OrientationHintPolicy`.
 *
 * [onReplay] is what makes `PlaybackPhase.Ended` actionable. Without it the state
 * still READS as finished — the chip, the eyebrow and a deeper dim all say so —
 * but the primary key is not offered, because the resume behind it cannot restart
 * a finished player; see [primaryTransportLive].
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
    videoRotation: VideoRotation = VideoRotation.Auto,
    autoVideoRotationDegrees: Int = 0,
    turnNote: TurnNote? = null,
    orientationHint: OrientationHint? = null,
    silentAudioMimeType: String? = null,
    /** The band's third card — see [BandNotice]. Null while the slot has nothing to say. */
    bandNotice: BandNotice? = null,
    openPanel: PlaybackPanel = PlaybackPanel.None,
    onOpenPanel: (PlaybackPanel) -> Unit = {},
    onScrubFocusChanged: (Boolean) -> Unit = {},
    onSelectSubtitleTrack: (String?) -> Unit = {},
    onSelectSubtitleSize: (SubtitleSize) -> Unit = {},
    onSelectVideoRotation: (VideoRotation) -> Unit = {},
    onEndSession: (() -> Unit)? = null,
    onReplay: (() -> Unit)? = null,
    dimReading: com.flick.receiver.ui.components.FilmDimReading? = null,
    videoContent: @Composable () -> Unit,
) {
    val safeArea = rememberTvSafeAreaPadding()
    val subtitlesCardFocus = remember { FocusRequester() }
    val orientationCardFocus = remember { FocusRequester() }
    val metricsCardFocus = remember { FocusRequester() }
    val volumeFocus = remember { FocusRequester() }
    val scrubFocus = remember { FocusRequester() }
    val endSessionFocus = remember { FocusRequester() }

    // A panel REPLACES the transport bar rather than stacking on top of it. The
    // two are siblings, so the panel outlives the bar and hiding the bar can no
    // longer force the panel closed.
    val transportVisible = chromeVisible && openPanel == PlaybackPanel.None

    // Where focus goes when the bar comes back. A panel is summoned from one of
    // the row's three flanking controls, and closing it has to put the remote back
    // on the one that opened it — with the bar gone there is nothing else to
    // return to.
    // Cleared when the chrome goes down so an ordinary reveal still lands on the
    // primary key.
    var panelReturn by remember { mutableStateOf(PlaybackPanel.None) }
    LaunchedEffect(openPanel, chromeVisible) {
        when {
            openPanel != PlaybackPanel.None -> panelReturn = openPanel
            !chromeVisible -> panelReturn = PlaybackPanel.None
        }
    }

    // The panel the slot draws, and — because the slot is composed only while this
    // is set — the panel's whole MOUNT LIFETIME. It deliberately outlives
    // [openPanel]: the wipe that opened the panel runs backwards to close it, and
    // a draw-phase animation cannot run in a subtree that has already been taken
    // out of the composition. The reveal reports when the circle has finally
    // closed, and that report is what clears this.
    //
    // Read only on the closed branch below, so arming it on an open never
    // invalidates the composition that opened the panel.
    var retainedPanel by remember { mutableStateOf(PlaybackPanel.None) }
    LaunchedEffect(openPanel) { if (openPanel != PlaybackPanel.None) retainedPanel = openPanel }
    val renderedPanel = if (openPanel != PlaybackPanel.None) openPanel else retainedPanel

    // Where each side panel is summoned from. The card publishes its own centre
    // while it holds focus, and a D-pad OK can only reach a card that does — so
    // the recorded point is always the control the viewer actually pressed.
    val subtitlesOrigin = rememberTvRevealOrigin()
    val orientationOrigin = rememberTvRevealOrigin()
    val metricsOrigin = rememberTvRevealOrigin()

    // The turn currently on screen, resolved once: the transport tile wears it and
    // the hint over the film names it, so the sign and the control cannot disagree.
    val shownRotation = shownVideoRotation(videoRotation, autoVideoRotationDegrees)
    val shownRotationLabel = stringResource(rotationLabelRes(shownRotation))
    // The same turn as a number, for the tile's mark. `shownVideoRotation` exists
    // to resolve Auto away, and Auto is the only value with no degrees of its own —
    // so this elvis is unreachable and not a default.
    val shownRotationDegrees = shownRotation.extraDegrees ?: 0
    val rotationIsAuto = videoRotation == VideoRotation.Auto
    // The word the tile actually prints under its mark, resolved here so the sign
    // that sends a viewer to that tile can name it by the same one. Under Auto —
    // the only state the sign ever appears in — the tile spends its line on AUTO
    // and the degrees move into the mark, so a sign still saying "the 90° tile"
    // would be pointing at a number that is no longer on screen anywhere.
    val rotationTileLabel = if (rotationIsAuto) {
        stringResource(R.string.video_rotation_card_auto)
    } else {
        shownRotationLabel
    }

    val primaryLive = primaryTransportLive(phase, onReplay)
    val chromeEntryFocus = when {
        panelReturn == PlaybackPanel.Subtitles -> subtitlesCardFocus
        panelReturn == PlaybackPanel.Orientation -> orientationCardFocus
        panelReturn == PlaybackPanel.Metrics -> metricsCardFocus
        primaryLive -> playFocusRequester
        else -> subtitlesCardFocus
    }

    Box(modifier = modifier.fillMaxSize().background(FlickColor.CanvasPlayback)) {
        videoContent()

        // Dim while paused / seeking / buffering — the frame stays visible.
        // Ended goes deepest and is the one state where that is not a compromise:
        // the film is over, so the last frame is a still, not the content.
        val dimCause = playbackDimCause(phase, seeking)
        val dimMemory = remember { DimMemory(dimCause) }
        val reducedMotion = LocalReducedMotion.current
        // The three layers over the film — one dim and two scrims — are composed
        // unconditionally and their alphas are read in the DRAW phase. Branching
        // on the animated value at composition time rebuilt the whole playback
        // stack, and both gradients, once a frame while the decoder was running.
        //
        // The curve is chosen from the change of cause, never from the animated
        // value: blind ±10 taps lift slowly off a seek rather than pulsing the whole
        // picture once per press.
        val dim = animateFloatAsState(
            targetValue = playbackDimTarget(dimCause),
            animationSpec = FlickMotion.orSnap(reducedMotion, dimSpec(dimEase(dimMemory.last, dimCause))),
            label = "playbackStateDim",
        )
        SideEffect { dimMemory.last = dimCause }
        // This is a UI-only layer above the decoded SurfaceView. The video is
        // never placed inside a transition or graphics layer.
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val shade = dim.value
                    dimReading?.drawn = shade
                    if (shade > 0.01f) drawRect(FlickColor.CanvasPlayback, alpha = shade)
                },
        )

        // Scrims are part of the chrome, so they breathe with it rather than
        // sitting permanently over the film. They also LEAD it: the scrim runs the
        // 200 ms fade token while the chrome above it is still travelling in on a
        // settling spring, so the panel lands on a surface that is already dark —
        // and on the way out the chrome is gone long before the film brightens.
        // Seeded at 0 so the chrome up at the first frame still arrives.
        val scrimAlpha = remember { Animatable(0f) }
        LaunchedEffect(chromeVisible, reducedMotion) {
            scrimAlpha.animateTo(
                if (chromeVisible) 1f else 0f,
                FlickMotion.orSnap(
                    reducedMotion,
                    if (chromeVisible) FlickMotion.chromeFadeIn<Float>() else FlickMotion.chromeFadeOut(),
                ),
            )
        }
        // Pure functions of their stops, so one instance each serves every size.
        val topScrim = remember { topScrimBrush() }
        val bottomScrim = remember { bottomScrimBrush() }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .fillMaxHeight(TOP_SCRIM_FRACTION)
                .drawBehind {
                    val veil = scrimAlpha.value
                    if (veil > 0.01f) drawRect(topScrim, alpha = veil)
                },
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(BOTTOM_SCRIM_FRACTION)
                .drawBehind {
                    val veil = scrimAlpha.value
                    if (veil > 0.01f) drawRect(bottomScrim, alpha = veil)
                },
        )

        // T7 buffering. The last plate shown is what fades out, so a STALLED plate
        // never reads TOPPING UP on its way off the film.
        FlickPresence(
            value = diagnostics.bufferingPlate.takeIf { phase == PlaybackPhase.Buffering },
            overFilm = true,
            modifier = Modifier.align(Alignment.Center),
            label = "bufferingPlate",
        ) { plate ->
            BufferingOverlay(plate = plate, deviceLabel = deviceLabel)
        }

        // T5. Only FINISHED still announces itself in the middle of the frame.
        // A viewer who has just pressed pause does not need to be told they did;
        // "the film is over" is the one playback state that carries information
        // nobody can infer from a still frame, so it keeps its chip — and the
        // resting key below is what says "paused" instead.
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.fillMaxHeight(STATE_CHIP_TOP_FRACTION))
            FlickPresence(
                value = Unit.takeIf { phase == PlaybackPhase.Ended },
                overFilm = true,
                rise = FlickMotion.TvRiseCard,
                label = "finishedChip",
            ) {
                PlaybackFinishedChip()
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
            enter = fadeIn(FlickMotion.orSnap(reducedMotion, FlickMotion.tvBurstFadeIn())),
            exit = fadeOut(FlickMotion.orSnap(reducedMotion, FlickMotion.tvBurstExit())),
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
        //
        // `transitionSpec` is NOT a composable lambda, so the scheme specs are
        // resolved here and captured. Every AnimatedContent in this module does
        // the same.
        //
        // No size transform: the default one grows the box from nothing, which
        // wipe-clips the card in and drags the outgoing one toward the end edge.
        val qualityTransform = if (reducedMotion) {
            FlickMotion.cut()
        } else {
            ContentTransform(
                targetContentEnter = fadeIn(FlickMotion.chromeFadeIn()) + scaleIn(
                    initialScale = 0.96f,
                    animationSpec = FlickMotion.flickSettleSpatial(),
                ),
                initialContentExit = fadeOut(FlickMotion.chromeFadeOut()) + scaleOut(
                    targetScale = 1.02f,
                    animationSpec = FlickMotion.flickSettleSpatial(),
                ),
                sizeTransform = null,
            )
        }
        AnimatedContent(
            targetState = quality,
            transitionSpec = { qualityTransform },
            contentAlignment = Alignment.TopEnd,
            contentKey = { it?.qualityLabel ?: "none" },
            label = "qualityFlourish",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(safeArea)
                // Clears the 32.2 dp top-chrome pill row plus a sibling gap.
                .padding(top = 42.dp),
        ) { info ->
            if (info != null) QualityCard(info = info)
        }

        // The picture-orientation hint. Its placement is decided by two rules it
        // has to live inside rather than by where it would be loudest.
        //
        // It is NOT a centred state chip: the middle of the frame carries only
        // FINISHED, the one state a still cannot tell you (§5.3, T5), and this is
        // a pointer to a control, not a state. And it stays out of the bottom
        // third entirely — the transport panel, the resting pause key and media3's
        // own SubtitleView all land there. What is left is the band under the top
        // pill row, inside the top scrim, centred so that it clears END SESSION on
        // the left and the clock on the right in every chrome state — and it holds
        // that position whether the chrome is up or down, which is what says it is
        // not chrome. The T8 quality flourish shares the band; it now hugs its own
        // content at the END rather than spanning the frame, but `OrientationHintPolicy`
        // still waits it out — two transient cards at once are two things talking,
        // and that is a sequencing rule, not a clearance one.
        //
        // The value is retained past its own dismissal so the exit has something
        // to draw, exactly as [retainedPanel] does for the side panel.
        var retainedHint by remember { mutableStateOf<OrientationHint?>(null) }
        LaunchedEffect(orientationHint) { if (orientationHint != null) retainedHint = orientationHint }
        AnimatedVisibility(
            visible = orientationHint != null,
            // The quality flourish's vocabulary, and for the same reason: both are
            // transient cards that resolve over a running decoder. Fade and scale
            // are `graphicsLayer` properties, so nothing here is ever re-laid-out
            // while it moves.
            enter = if (reducedMotion) {
                EnterTransition.None
            } else {
                fadeIn(FlickMotion.chromeFadeIn()) + scaleIn(
                    initialScale = 0.96f,
                    animationSpec = FlickMotion.flickSettleSpatial(),
                )
            },
            exit = if (reducedMotion) {
                ExitTransition.None
            } else {
                fadeOut(FlickMotion.chromeFadeOut()) + scaleOut(
                    targetScale = 1.02f,
                    animationSpec = FlickMotion.flickSettleSpatial(),
                )
            },
            label = "orientationHint",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(safeArea)
                // Clears the 32.2 dp top-chrome pill row plus a sibling gap.
                .padding(top = 42.dp),
        ) {
            retainedHint?.let {
                OrientationHintCard(
                    hint = it,
                    tileLabel = rotationTileLabel,
                    turnDegrees = shownRotationDegrees,
                )
            }
        }

        // The silent-audio notice, in the same band and under the same two rules —
        // it is not a state chip and it stays out of the bottom third. It is
        // sequenced against the hint by `SilentAudioPolicy` rather than placed
        // clear of it, because they are one queue and never two cards.
        //
        // Retained past its own dismissal so the exit has something to draw, exactly
        // as [retainedHint] is.
        var retainedSilentAudio by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(silentAudioMimeType) {
            if (silentAudioMimeType != null) retainedSilentAudio = silentAudioMimeType
        }
        AnimatedVisibility(
            visible = silentAudioMimeType != null,
            // The hint's vocabulary exactly: the two cards share a band and a queue,
            // so arriving differently would make them read as different kinds of thing.
            enter = if (reducedMotion) {
                EnterTransition.None
            } else {
                fadeIn(FlickMotion.chromeFadeIn()) + scaleIn(
                    initialScale = 0.96f,
                    animationSpec = FlickMotion.flickSettleSpatial(),
                )
            },
            exit = if (reducedMotion) {
                ExitTransition.None
            } else {
                fadeOut(FlickMotion.chromeFadeOut()) + scaleOut(
                    targetScale = 1.02f,
                    animationSpec = FlickMotion.flickSettleSpatial(),
                )
            },
            label = "silentAudioNotice",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(safeArea)
                // Clears the 32.2 dp top-chrome pill row plus a sibling gap.
                .padding(top = 42.dp),
        ) {
            retainedSilentAudio?.let { SilentAudioCard(mimeType = it) }
        }

        // The band's event slot, in the same place and the same vocabulary as the two
        // cards above, and sequenced against them by `BandNoticePolicy` — never placed
        // clear of them, because all three are one queue and never two cards.
        var retainedBandNotice by remember { mutableStateOf<BandNotice?>(null) }
        LaunchedEffect(bandNotice) { if (bandNotice != null) retainedBandNotice = bandNotice }
        AnimatedVisibility(
            visible = bandNotice != null,
            enter = if (reducedMotion) {
                EnterTransition.None
            } else {
                fadeIn(FlickMotion.chromeFadeIn()) + scaleIn(
                    initialScale = 0.96f,
                    animationSpec = FlickMotion.flickSettleSpatial(),
                )
            },
            exit = if (reducedMotion) {
                ExitTransition.None
            } else {
                fadeOut(FlickMotion.chromeFadeOut()) + scaleOut(
                    targetScale = 1.02f,
                    animationSpec = FlickMotion.flickSettleSpatial(),
                )
            },
            label = "bandNotice",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(safeArea)
                // Clears the 32.2 dp top-chrome pill row plus a sibling gap.
                .padding(top = 42.dp),
        ) {
            retainedBandNotice?.let { BandNoticeCard(notice = it) }
        }

        // Both chrome surfaces start hidden, so chrome that is already up at the
        // first frame still arrives rather than appearing in place.
        AnimatedVisibility(
            visibleState = remember { MutableTransitionState(false) }.apply { targetState = chromeVisible },
            enter = fadeIn(FlickMotion.orSnap(reducedMotion, FlickMotion.chromeFadeIn())),
            // Alpha on the 250 ms film exit, not the 500 ms chrome fade: the chrome
            // is still off the film well before the scrim behind it has lifted.
            exit = fadeOut(FlickMotion.orSnap(reducedMotion, FlickMotion.filmExit())),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopChrome(
                deviceLabel = deviceLabel,
                diagnostics = diagnostics,
                safeArea = safeArea,
                // A side panel is modal: keep the status chrome visible, but do
                // not leave END SESSION in the focus or accessibility graph.
                interactive = transportVisible,
                transportVisible = transportVisible,
                onEndSession = onEndSession,
                endSessionFocusRequester = endSessionFocus,
                scrubFocusRequester = scrubFocus,
            )
        }

        AnimatedVisibility(
            visibleState = remember { MutableTransitionState(false) }.apply { targetState = transportVisible },
            enter = fadeIn(FlickMotion.orSnap(reducedMotion, FlickMotion.chromeFadeIn())),
            exit = fadeOut(FlickMotion.orSnap(reducedMotion, FlickMotion.filmExit())),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            BottomChrome(
                playing = playing,
                phase = phase,
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedMs = bufferedMs,
                targetMs = targetMs,
                seeking = seeking,
                volume = volume,
                title = title,
                hdr = hdr,
                diagnostics = diagnostics,
                openPanel = openPanel,
                // The card states only that captions are running. Which track is
                // one click away inside the panel, which is also the only surface
                // that has room for a track name.
                subtitlesOn = subtitleTracks.any { it.isSelected },
                rotationLabel = shownRotationLabel,
                rotationIsAuto = rotationIsAuto,
                rotationTurnDegrees = shownRotationDegrees,
                onOpenPanel = onOpenPanel,
                onBack10 = onBack10,
                onPlayPause = onPlayPause,
                onForward10 = onForward10,
                onSetVolume = onSetVolume,
                onReplay = onReplay,
                onScrubFocusChanged = onScrubFocusChanged,
                entryFocusRequester = chromeEntryFocus,
                playFocusRequester = playFocusRequester,
                scrubFocusRequester = scrubFocus,
                subtitlesCardFocusRequester = subtitlesCardFocus,
                orientationCardFocusRequester = orientationCardFocus,
                metricsCardFocusRequester = metricsCardFocus,
                volumeFocusRequester = volumeFocus,
                endSessionFocusRequester = if (onEndSession != null) endSessionFocus else null,
                subtitlesRevealOrigin = subtitlesOrigin,
                orientationRevealOrigin = orientationOrigin,
                metricsRevealOrigin = metricsOrigin,
                safeArea = safeArea,
                interactive = transportVisible,
            )
        }

        // The side panel — a SIBLING of the transport, drawn after it so the two
        // never read as stacked while they exchange. The slot is an empty
        // full-screen box that bounds the panel's height; only the card itself is
        // ever animated, so nothing here buys a full-screen scratch layer.
        //
        // There is no `AnimatedVisibility` around it, in either direction. The
        // origin wipe IS the arrival AND the departure (§5.4/§5.5): the panel is
        // born at the card that summoned it and pulled back into the same card.
        // A fade over either half would be a second transition and a second
        // compositing layer — a full-panel offscreen buffer at 4K — on exactly
        // the frames the panel is most expensive. Presence is therefore a plain
        // `if` on [renderedPanel], whose lifetime already spans the retreat.
        LookaheadScope {
            val panelScope = this
            Box(modifier = Modifier.fillMaxSize().padding(safeArea)) {
                if (renderedPanel != PlaybackPanel.None) {
                    PlaybackSidePanel(
                        openPanel = openPanel,
                        renderedPanel = renderedPanel,
                        diagnostics = diagnostics,
                        throughput = throughput,
                        subtitleTracks = subtitleTracks,
                        subtitleSize = subtitleSize,
                        videoRotation = videoRotation,
                        autoVideoRotationDegrees = autoVideoRotationDegrees,
                        turnNote = turnNote,
                        subtitlesRevealOrigin = subtitlesOrigin,
                        orientationRevealOrigin = orientationOrigin,
                        metricsRevealOrigin = metricsOrigin,
                        onOpenPanel = onOpenPanel,
                        onSelectSubtitleTrack = onSelectSubtitleTrack,
                        onSelectSubtitleSize = onSelectSubtitleSize,
                        onSelectVideoRotation = onSelectVideoRotation,
                        // The reveal also reports itself settled-and-hidden before
                        // it has ever been asked to open, so only a close is
                        // allowed to take the panel out of the composition.
                        onRetreated = {
                            if (openPanel == PlaybackPanel.None) {
                                retainedPanel = PlaybackPanel.None
                            }
                        },
                        modifier = Modifier
                            .align(
                                if (renderedPanel == PlaybackPanel.Metrics) Alignment.BottomEnd
                                else Alignment.BottomStart,
                            )
                            // ONE panel container that travels: switching
                            // Subtitles → Metrics glides it across the screen and
                            // resizes it, instead of cutting one card out and
                            // another in. Neither panel ever retreats on that
                            // path — the swap keeps [openPanel] open throughout.
                            .animateBounds(panelScope, boundsTransform = PanelTravel),
                    )
                }
            }
        }

        // This state signal is deliberately the top playback sibling. Media3's
        // SubtitleView lives inside videoContent's AndroidView, while the seek,
        // chrome and side-panel overlays are later Compose siblings; zIndex makes
        // the ordering explicit instead of relying on their source order.
        //
        // On resume the key stays long enough to morph to the pause bars, so it is
        // seen to play before it goes.
        FlickPresence(
            value = Unit.takeIf { phase == PlaybackPhase.Paused && !chromeVisible },
            overFilm = true,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(safeArea)
                .zIndex(1f),
            label = "restingPauseKey",
        ) {
            RestingPauseKey(
                playing = phase == PlaybackPhase.Playing,
                announced = true,
            )
        }
    }
}

/** The previous dim cause, written after composition so the next change can see where it came from. */
private class DimMemory(var last: DimCause)

/**
 * The paused key at rest (§5.3, T5).
 *
 * It is deliberately NOT a control: no focus target, no click action, nothing the
 * D-pad can land on. Every remote key while the chrome is down is already routed
 * by `TvRemoteKeyPolicy`, and centre/up/down all bring the full chrome back — this
 * only has to be the thing on screen that says the TV is paused and not frozen.
 *
 * Its presence is the parent's: the fade that brings it and takes it away is a
 * `ModulateAlpha` layer, never a default-strategy one. The default would buy an
 * offscreen buffer for a 56 dp key over a live decoder — and modulating per draw
 * op is what keeps the composite mid-fade exactly what [PAUSED_REST_FILL_ALPHA]
 * describes: an 80 %-strength fill under solid ink, not 80 % applied to the
 * entire key.
 *
 * [playing] is passed through to the glyph so that on resume the key visibly
 * plays while it leaves.
 */
@Composable
private fun RestingPauseKey(
    playing: Boolean,
    announced: Boolean,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.paused_title)
    Box(
        modifier = modifier
            .size(PrimaryTransportTargetSize)
            .sparkShadow(FlickShape.Play)
            .clip(FlickShape.Play)
            .background(PausedRestFill)
            .then(
                if (announced) {
                    Modifier.semantics { contentDescription = label }
                } else {
                    Modifier.clearAndSetSemantics { }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // The key exactly as the transport left it: paused, so it offers play.
        // Substituting a pause glyph here would invent a second vocabulary for a
        // state the amber key already carries.
        PlayPauseGlyph(
            playing = playing,
            size = PrimaryTransportGlyphSize,
            tint = FlickColor.OnSpark,
        )
    }
}

// ── Top chrome (spec §5.3) ──────────────────────────────────────────────────

@Composable
private fun AnimatedVisibilityScope.TopChrome(
    deviceLabel: String?,
    diagnostics: DiagnosticsSnapshot,
    safeArea: PaddingValues,
    interactive: Boolean,
    transportVisible: Boolean,
    onEndSession: (() -> Unit)?,
    endSessionFocusRequester: FocusRequester,
    scrubFocusRequester: FocusRequester,
) {
    // The top chrome arrives from beyond the panel edge and leaves on a short
    // sink toward it. The travel is parent-owned so enter and exit are one
    // vocabulary, and it is a graphicsLayer transform so nothing over the decoder
    // is ever re-laid-out while it moves.
    //
    // The exit is an absolute distance, not a fraction of the row: under the
    // 250 ms film-exit fade, half this ~130 dp row sampled at a 24 Hz window pin
    // lands as ~50 px steps drawn at high opacity. Arrival and sink are separate
    // values because one value remapped by state would jump whenever a hide
    // interrupts an entrance or a show interrupts an exit.
    //
    // Held as State and read INSIDE the layer block. Delegating it into a local
    // (`by`) reads the animated value in composition, which recomposed this whole
    // subtree once per frame for the length of every reveal — the transform was
    // already free, the recomposition was not.
    val arrive = transition.animateFloat(
        transitionSpec = { FlickMotion.panelSpatial() },
        label = "topChromeArrive",
    ) { state -> if (state == EnterExitState.PreEnter) 1f else 0f }
    val sink = transition.animateFloat(
        transitionSpec = {
            if (targetState == EnterExitState.Visible) FlickMotion.panelSpatial()
            else FlickMotion.focusSpatial()
        },
        label = "topChromeSink",
    ) { state -> if (state == EnterExitState.PostExit) 1f else 0f }
    // Telemetry already known while the chrome is still arriving rides its
    // entrance instead of replaying its own pop.
    val chromeEntering: () -> Boolean = remember(transition) {
        { transition.currentState == EnterExitState.PreEnter }
    }
    Row(
        modifier = Modifier
            .graphicsLayer {
                translationY = -arrive.value * size.height -
                    sink.value * CHROME_EXIT_TRAVEL * FlickMotion.TvRise.toPx()
            }
            // AnimatedVisibility retains this subtree for its fade-out; its
            // descendants leave the focus graph and the accessibility tree the
            // moment chrome hides.
            .focusProperties { if (!interactive) canFocus = false }
            .then(if (interactive) Modifier else Modifier.clearAndSetSemantics { })
            .fillMaxWidth()
            .padding(safeArea)
            // END SESSION is focusable at the outer left edge. Keep its detached
            // ring inside the same overscan contract as full-width controls.
            .padding(start = FlickDimens.FocusRingReserve),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(FlickSpace.Sm),
            horizontalAlignment = Alignment.Start,
        ) {
            GlassPill(
                text = deviceLabel?.let { stringResource(R.string.now_playing_flicked_from, it) }
                    ?: stringResource(R.string.app_name),
                style = FlickType.body(sizeSp = 16, weight = FontWeight.Bold),
                color = FlickColor.OnSurface,
                contentPadding = PaddingValues(start = 7.dp, top = 6.dp, end = 12.dp, bottom = 6.dp),
                leading = { BrandMark(size = FlickDimens.GlyphSmall, tint = FlickColor.OnSurface) },
            )
            if (onEndSession != null) {
                // The design's outline-only pill, given a plate. It hangs at ~16 %
                // of the frame, where the top scrim has decayed to ~0.30, and it is
                // the only focusable in the top chrome — with a transparent fill
                // the white-18 % border stood 1.2:1 from its own backdrop over a
                // bright frame and the label read 1.1:1. The 2 dp stroke is kept
                // explicitly (a filled control would otherwise take the hairline):
                // it is the pill's identity, it is just no longer load-bearing.
                FlickTvButton(
                    onClick = onEndSession,
                    focusRequester = endSessionFocusRequester,
                    // Down reaches the scrub bar, which is the top of the
                    // transport's own focus stack. While a panel has replaced the
                    // transport there is no bar to point at, and a
                    // `FocusRequester` with no attached node throws when focus
                    // search resolves it — so the link is only wired while the
                    // thing it names is on screen.
                    modifier = Modifier.focusProperties {
                        down = if (transportVisible) scrubFocusRequester else FocusRequester.Default
                    },
                    shape = FlickShape.Pill,
                    containerColor = FlickColor.GlassState,
                    borderColor = FlickColor.OutlineSoft,
                    borderWidth = FlickOutlinedChromeBorderWidth,
                    contentPadding = FlickDimens.ControlPadding,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Icon(
                        imageVector = FlickIcons.LinkOff,
                        contentDescription = null,
                        tint = FlickColor.OnSurfaceDim,
                        modifier = Modifier.size(FlickDimens.GlyphSmall),
                    )
                    Text(
                        text = stringResource(R.string.session_end_pill),
                        style = FlickType.monoEyebrow(trackingEm = 0.14f),
                        color = FlickColor.OnSurfaceDim,
                        maxLines = 1,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.animateContentSize(
                animationSpec = FlickMotion.panelSpatial(),
                alignment = Alignment.CenterEnd,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            // No band read means no pill — the receiver never guesses a radio. The
            // pill resolves in the frame the band is first read; the dBm number
            // inside it then SNAPS between values, because a tweened measurement is
            // a fabricated measurement. A band change dissolves inside the pill
            // rather than disposing it.
            val band = diagnostics.wifiBand
            if (band != null) {
                TelemetryReveal(initiallyVisible = remember { chromeEntering() }) {
                    FlickSwap(target = band, label = "netPill") { b ->
                        GlassPill(
                            text = if (diagnostics.wifiRssiDbm != 0) {
                                stringResource(R.string.net_pill, b, diagnostics.wifiRssiDbm)
                            } else {
                                stringResource(R.string.net_pill_band_only, b)
                            },
                            dotColor = netHealthColor(b, diagnostics.wifiRssiDbm),
                        )
                    }
                }
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

// ── Bottom chrome: the transport panel (spec §5.3) ──────────────────────────

@Composable
private fun AnimatedVisibilityScope.BottomChrome(
    playing: Boolean,
    phase: PlaybackPhase,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    targetMs: Long,
    seeking: Boolean,
    volume: Float,
    title: String?,
    hdr: HdrType,
    diagnostics: DiagnosticsSnapshot,
    openPanel: PlaybackPanel,
    subtitlesOn: Boolean,
    rotationLabel: String,
    rotationIsAuto: Boolean,
    rotationTurnDegrees: Int,
    onOpenPanel: (PlaybackPanel) -> Unit,
    onBack10: () -> Unit,
    onPlayPause: () -> Unit,
    onForward10: () -> Unit,
    onSetVolume: (Float) -> Unit,
    onReplay: (() -> Unit)?,
    onScrubFocusChanged: (Boolean) -> Unit,
    entryFocusRequester: FocusRequester,
    playFocusRequester: FocusRequester,
    scrubFocusRequester: FocusRequester,
    subtitlesCardFocusRequester: FocusRequester,
    orientationCardFocusRequester: FocusRequester,
    metricsCardFocusRequester: FocusRequester,
    volumeFocusRequester: FocusRequester,
    endSessionFocusRequester: FocusRequester?,
    subtitlesRevealOrigin: TvRevealOrigin,
    orientationRevealOrigin: TvRevealOrigin,
    metricsRevealOrigin: TvRevealOrigin,
    safeArea: PaddingValues,
    interactive: Boolean,
) {
    val primaryLive = primaryTransportLive(phase, onReplay)
    // The bar is composed by the same state change that takes away whatever held
    // focus before it — a reveal takes it off the root catcher, a panel close
    // takes it off the panel — so the entry request is retried across frames
    // rather than made once. [entryFocusRequester] is normally play (design §1.7);
    // after a panel it is the card that summoned the panel, and past the end of
    // the film it is the first control that is still live. The scrub bar is the
    // last resort because it is the one control this bar always has.
    var chromeHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(interactive, entryFocusRequester, primaryLive) {
        if (!interactive) return@LaunchedEffect
        landTvFocus(entryFocusRequester, scrubFocusRequester) { chromeHasFocus }
    }

    // The transport rises the design's `tvRise` distance and sinks half of it back
    // out. Both are the parent's, and both are graphicsLayer transforms: nothing
    // over the decoder is re-laid-out while the chrome moves. Read inside the
    // layer block — see the note on `topChromeArrive`.
    val rise = transition.animateFloat(
        transitionSpec = {
            if (targetState == EnterExitState.Visible) FlickMotion.panelSpatial()
            else FlickMotion.focusSpatial()
        },
        label = "bottomChromeRise",
    ) { state ->
        when (state) {
            EnterExitState.PreEnter -> 1f
            EnterExitState.Visible -> 0f
            EnterExitState.PostExit -> CHROME_EXIT_TRAVEL
        }
    }
    val chromeEntering: () -> Boolean = remember(transition) {
        { transition.currentState == EnterExitState.PreEnter }
    }

    Column(
        modifier = Modifier
            .graphicsLayer { translationY = rise.value * FlickMotion.TvRise.toPx() }
            // Aggregates every focus target below it, which is what tells the
            // entry effect above that its request took. No focus target of its
            // own: adding one here would terminate the property walk the controls
            // inside make, and the `canFocus` gate below would stop reaching them.
            .onFocusChanged { chromeHasFocus = it.hasFocus }
            .focusProperties { if (!interactive) canFocus = false }
            .then(if (interactive) Modifier else Modifier.clearAndSetSemantics { })
            .fillMaxWidth()
            .padding(safeArea),
    ) {
        GlassPanel(
            modifier = Modifier.fillMaxWidth(),
            shape = FlickShape.Hero,
            tone = GlassPanelTone.Chrome,
            // The shared panel inset. Its 18 dp bottom is also what clears the
            // play key's 18 dp spark shadow, which is painted outside the
            // button's bounds and would otherwise fall past the panel edge.
            contentPadding = FlickDimens.PanelPadding,
            verticalArrangement = Arrangement.spacedBy(FlickSpace.Md),
            // The chrome above owns enter AND exit now; a second entrance latch
            // here would fight it and leave the panel behind on the way out.
            animateEntrance = false,
        ) {
            TransportHeaderRow(
                title = title,
                phase = phase,
                seeking = seeking,
                hdr = hdr,
                diagnostics = diagnostics,
                chromeEntering = chromeEntering,
            )
            TransportScrubRow(
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedMs = bufferedMs,
                targetMs = targetMs,
                seeking = seeking,
                playing = playing,
                interactive = interactive,
                focusRequester = scrubFocusRequester,
                onFocusChanged = onScrubFocusChanged,
                upFocusRequester = endSessionFocusRequester,
                downFocusRequester = if (primaryLive) playFocusRequester else subtitlesCardFocusRequester,
            )
            TransportControlRow(
                playing = playing,
                phase = phase,
                volume = volume,
                openPanel = openPanel,
                subtitlesOn = subtitlesOn,
                rotationLabel = rotationLabel,
                rotationIsAuto = rotationIsAuto,
                rotationTurnDegrees = rotationTurnDegrees,
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
                onReplay = onReplay,
                playFocusRequester = playFocusRequester,
                scrubFocusRequester = scrubFocusRequester,
                subtitlesCardFocusRequester = subtitlesCardFocusRequester,
                orientationCardFocusRequester = orientationCardFocusRequester,
                metricsCardFocusRequester = metricsCardFocusRequester,
                volumeFocusRequester = volumeFocusRequester,
                subtitlesRevealOrigin = subtitlesRevealOrigin,
                orientationRevealOrigin = orientationRevealOrigin,
                metricsRevealOrigin = metricsRevealOrigin,
            )
        }
    }
}

/**
 * The side panel, now a sibling of the transport rather than a child of it
 * (§5.4/§5.5).
 *
 * Opening a panel takes the control bar off screen: two glass surfaces stacked in
 * the bottom third read as clutter, and the bar is also the single most expensive
 * thing this screen draws — a full-width panel, a scrub canvas running a wave
 * loop, and a row of controls, all recomposing with the 10 Hz position feed. So
 * the panel does not merely sit above the bar, it replaces it.
 *
 * That inverts one hard requirement: with the bar gone the panel holds the ONLY
 * focusables on screen, so focus MUST land inside it. Each panel asks for the
 * control it wants — the selected track row, the close button — through
 * [landTvFocus], which retries across frames rather than once, because a
 * `FocusRequester` whose node has not been placed yet throws and would strand the
 * remote entirely.
 *
 * The panel is composed past its own close so the wipe can run backwards — see
 * [onRetreated] — and the focus gate below is what makes that safe: [openPanel]
 * flips the instant the close starts, so the whole subtree leaves the focus graph
 * and the accessibility tree while it is still being drawn. Focus lands back on
 * the transport bar, which returns on the same state change, rather than waiting
 * out the retreat inside a panel that is already going away.
 */
@Composable
private fun PlaybackSidePanel(
    openPanel: PlaybackPanel,
    renderedPanel: PlaybackPanel,
    diagnostics: DiagnosticsSnapshot,
    throughput: ThroughputSnapshot,
    subtitleTracks: List<SubtitleTrackInfo>,
    subtitleSize: SubtitleSize,
    videoRotation: VideoRotation,
    autoVideoRotationDegrees: Int,
    turnNote: TurnNote?,
    subtitlesRevealOrigin: TvRevealOrigin,
    orientationRevealOrigin: TvRevealOrigin,
    metricsRevealOrigin: TvRevealOrigin,
    onOpenPanel: (PlaybackPanel) -> Unit,
    onSelectSubtitleTrack: (String?) -> Unit,
    onSelectSubtitleSize: (SubtitleSize) -> Unit,
    onSelectVideoRotation: (VideoRotation) -> Unit,
    /** Reported once the wipe has closed back onto the card that summoned it. */
    onRetreated: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val open = openPanel != PlaybackPanel.None
    // Bumped when one panel replaces another, and when a close is reversed before
    // its retreat has finished — the two cases where this subtree is NOT rebuilt by
    // the open itself and would otherwise keep a half-collapsed wipe and a stale
    // focus request. Seeded from the panel this host was composed for, so the
    // ordinary open costs no second pass over the panel's contents.
    var entryKey by remember { mutableStateOf(0) }
    var previousPanel by remember { mutableStateOf(openPanel) }
    LaunchedEffect(openPanel) {
        if (openPanel != previousPanel && openPanel != PlaybackPanel.None) entryKey++
        previousPanel = openPanel
    }

    Box(
        modifier = modifier
            // The modal veto needs a focus target of the panel's OWN to hang on,
            // and `focusProperties` owns none: its block is inherited by every
            // focus target inside that has no other target between it and this
            // Box. That included the non-focusable focus target `verticalScroll`
            // delegates around the subtitles track list — so leaving the list,
            // which is exactly what a D-pad DOWN off the last track does to reach
            // the size selector, read as leaving the panel and was cancelled. The
            // group below is that target, and it is the panel boundary, so only a
            // move that really leaves the panel trips the veto.
            .focusProperties { onExit = { if (open) cancelFocusChange() } }
            .focusGroup()
            // Inside the group, so it reaches the panel's contents and stops
            // there: a retreating panel leaves the focus graph without the veto
            // above following its children down.
            .focusProperties { if (!open) canFocus = false }
            .then(if (open) Modifier else Modifier.clearAndSetSemantics { }),
    ) {
        // The panel's glass is born at the card that summoned it and pulled back
        // into the same card — this screen's one hero moment, and the only reveal
        // on it.
        key(entryKey) {
            var revealed by remember { mutableStateOf(false) }
            // The wipe belongs to a false → true edge, so the open is published a
            // frame after this subtree mounts rather than at composition, where it
            // would arrive already settled. A close landing inside that one frame
            // therefore has no wipe to run backwards and produces no retreat to
            // report — the host is released here instead, so the terminal state is
            // reachable from that path too.
            LaunchedEffect(open) {
                when {
                    open -> revealed = true
                    revealed -> revealed = false
                    else -> onRetreated()
                }
            }
            TvOriginReveal(
                visible = revealed,
                origin = when (renderedPanel) {
                    PlaybackPanel.Metrics -> metricsRevealOrigin
                    PlaybackPanel.Orientation -> orientationRevealOrigin
                    else -> subtitlesRevealOrigin
                },
                color = FlickColor.GlassPanel,
                onRetreated = onRetreated,
            ) {
                when (renderedPanel) {
                    PlaybackPanel.Subtitles -> SubtitlesPanel(
                        tracks = subtitleTracks,
                        size = subtitleSize,
                        onSelectTrack = onSelectSubtitleTrack,
                        onSelectSize = onSelectSubtitleSize,
                        onDismiss = { onOpenPanel(PlaybackPanel.None) },
                        entryKey = entryKey,
                    )

                    PlaybackPanel.Orientation -> OrientationPanel(
                        rotation = videoRotation,
                        autoRotationDegrees = autoVideoRotationDegrees,
                        turnNote = turnNote,
                        onSelectRotation = onSelectVideoRotation,
                        onDismiss = { onOpenPanel(PlaybackPanel.None) },
                        entryKey = entryKey,
                    )

                    PlaybackPanel.Metrics -> StreamMetricsPanel(
                        diagnostics = diagnostics,
                        throughput = throughput,
                        onDismiss = { onOpenPanel(PlaybackPanel.None) },
                        entryKey = entryKey,
                    )

                    PlaybackPanel.None -> Unit
                }
            }
        }
    }
}

@Composable
private fun TransportHeaderRow(
    title: String?,
    phase: PlaybackPhase,
    seeking: Boolean,
    hdr: HdrType,
    diagnostics: DiagnosticsSnapshot,
    chromeEntering: () -> Boolean,
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
            // The eyebrow is the transport's own tense. Left at "NOW PLAYING" past
            // the end of the film it is the chrome asserting something untrue.
            // The word dissolves and never slides: the landing ring is a seek's
            // only spatial motion.
            val eyebrowRes = when {
                seeking -> R.string.syncing_with_phone
                phase == PlaybackPhase.Ended -> R.string.playback_finished_eyebrow
                else -> R.string.now_playing_eyebrow
            }
            FlickSwap(target = eyebrowRes, label = "transportEyebrow") { res ->
                Text(
                    text = stringResource(res),
                    style = FlickType.monoEyebrow(trackingEm = 0.2f),
                    color = if (res == R.string.syncing_with_phone) FlickColor.Spark else FlickColor.SparkBright,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = title.orEmpty(),
                style = FlickType.display(sizeSp = 27),
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
            //
            // The row is sized by `animateContentSize` and anchored at its end, so
            // each chip RESOLVES into place as its telemetry lands instead of the
            // whole group appearing at once. `specChips` still omits anything the
            // receiver has not measured — nothing here fills a gap.
            Row(
                modifier = Modifier.animateContentSize(
                    animationSpec = FlickMotion.panelSpatial(),
                    alignment = Alignment.CenterEnd,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Keyed on the slot, not the text: a refined reading dissolves
                // inside its chip rather than disposing it and popping a new one.
                chips.forEach { entry ->
                    key(entry.slot) {
                        TelemetryReveal(initiallyVisible = remember { chromeEntering() }) {
                            FlickSwap(target = entry.text, label = "specChip") { t ->
                                SpecChip(
                                    text = t,
                                    style = FlickType.monoEyebrow(trackingEm = 0.06f),
                                    contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Row 2 (§5.3) — and, since the D-pad model was corrected, the one control on
 * this chrome that owns physical left/right. Up from the transport row reaches it,
 * Down from it returns; while it holds focus left/right seek instead of moving.
 */
@Composable
private fun TransportScrubRow(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    targetMs: Long,
    seeking: Boolean,
    playing: Boolean,
    interactive: Boolean,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    upFocusRequester: FocusRequester?,
    downFocusRequester: FocusRequester,
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
        // The digits snap between target and position; only the ink eases.
        InkText(
            text = clock(shownMs),
            color = if (seeking) FlickColor.Spark else Color.White,
            style = TimecodeStyle,
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
            // Without this the bar's only gate is its 600 ms clock-stall detector,
            // which counts a seek landing while paused as the film running — so the
            // wave swells over a frozen frame.
            playing = playing,
            interactive = interactive,
            focusRequester = focusRequester,
            onFocusChanged = onFocusChanged,
            modifier = Modifier
                .weight(1f)
                .focusProperties {
                    up = upFocusRequester ?: FocusRequester.Default
                    down = downFocusRequester
                },
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
 * `[SUBS][turn] ⟨−10 · play · +10⟩ [volume][metrics]`.
 *
 * The two flanking boxes carry equal weight, which is what puts the play key on
 * the screen's centre line and lets either card ellipsise rather than overflow
 * the panel on a narrow viewport. Each box carries a pair, not one control: the
 * subtitles card gave up the track name it used to state — the panel behind it is
 * the only surface with room for one — and the square that opens the orientation
 * panel is what the freed width became; volume moved INSIDE the trailing box,
 * beside a stream-metrics key that gave up its lockup for the same reason.
 *
 * **The row is traversed the way it is drawn.** Physical left/right used to be
 * captured as ±10 s seeks at the Activity boundary before Compose could see them,
 * which left a horizontal row of six controls reachable only by pressing Down — a
 * model the product owner rejected on the real device. `TvRemoteKeyPolicy` now
 * hands horizontal keys to focus everywhere except on the scrub bar, so:
 *
 *  - the ±10 s keys are focus targets again and wear the §3 unfocused vocabulary
 *    that means exactly that (`TransportSecondaryKey`);
 *  - the `FocusBeaconHost` comes back (§3a lists this row first). One ring
 *    travels along the row's own axis, which is now also its axis of traversal —
 *    the reason it was pulled was that a Down press sent the ring sideways at
 *    constant y, and Down no longer moves within this row at all.
 *
 * Vertically the row is one rank: every control's Up reaches the scrub bar, which
 * is where seeking lives.
 */
@Composable
private fun TransportControlRow(
    playing: Boolean,
    phase: PlaybackPhase,
    volume: Float,
    openPanel: PlaybackPanel,
    subtitlesOn: Boolean,
    rotationLabel: String,
    rotationIsAuto: Boolean,
    rotationTurnDegrees: Int,
    metricsSubLabel: String,
    onOpenPanel: (PlaybackPanel) -> Unit,
    onBack10: () -> Unit,
    onPlayPause: () -> Unit,
    onForward10: () -> Unit,
    onSetVolume: (Float) -> Unit,
    onReplay: (() -> Unit)?,
    playFocusRequester: FocusRequester,
    scrubFocusRequester: FocusRequester,
    subtitlesCardFocusRequester: FocusRequester,
    orientationCardFocusRequester: FocusRequester,
    metricsCardFocusRequester: FocusRequester,
    volumeFocusRequester: FocusRequester,
    subtitlesRevealOrigin: TvRevealOrigin,
    orientationRevealOrigin: TvRevealOrigin,
    metricsRevealOrigin: TvRevealOrigin,
) {
    // Past the end of the film the primary key stops being a play key. It only
    // says so when the caller actually gave it a restart to run: a key relabelled
    // "Watch again" that resumes nothing is a worse lie than the play triangle it
    // replaced. With no restart wired it is not a key at all, and horizontal
    // traversal simply steps over it.
    val replay = if (phase == PlaybackPhase.Ended) onReplay else null
    val primaryLive = primaryTransportLive(phase, onReplay)
    // No key here takes `enabled` from the bar's visibility. The chrome Column
    // above already drops a hiding bar from focus and semantics, and a key's eased
    // disabled dim, multiplied into the 250 ms film-exit fade, would take the row
    // off the film a frame ahead of the glass it sits on. Only a key that really
    // cannot act dims: the primary key past the end with no replay.
    FocusBeaconHost(modifier = Modifier.fillMaxWidth()) {
        // Exactly three children, and the two outer ones carry the SAME weight:
        // that is what puts [TransportCluster] on the row's centre line. The
        // cluster is internally symmetric (48 / 16 / 56 / 16 / 48), so its centre
        // is the play key's centre, and [FlickDimens.PanelPadding] and the safe
        // area are both horizontally symmetric — so the row's centre is the
        // screen's. That is the same centre `RestingPauseKey` is drawn on, which
        // is the point: summoning the chrome must not slide the key sideways.
        //
        // Nothing unweighted may be added beside the cluster again. Volume sat
        // there until this row was rebuilt, and it pushed the play key left of
        // centre by half its own width.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // One rank: everything in this row answers Up with the scrub bar.
                .focusProperties { up = scrubFocusRequester },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                // One pair, so the gap between them is the sibling step rather
                // than the row's own 14 dp between groups.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FlickSpace.Sm),
                ) {
                    SubtitlesKey(
                        on = subtitlesOn,
                        focusRequester = subtitlesCardFocusRequester,
                        onClick = {
                            onOpenPanel(
                                if (openPanel == PlaybackPanel.Subtitles) PlaybackPanel.None
                                else PlaybackPanel.Subtitles,
                            )
                        },
                        // Applied at exactly one level, outside the button's own
                        // focus target, so the centre it records is the card's
                        // layout centre rather than a point inside the focus lift.
                        modifier = Modifier.tvRevealSource(subtitlesRevealOrigin),
                    )
                    OrientationKey(
                        degrees = rotationLabel,
                        auto = rotationIsAuto,
                        turnDegrees = rotationTurnDegrees,
                        focusRequester = orientationCardFocusRequester,
                        onClick = {
                            onOpenPanel(
                                if (openPanel == PlaybackPanel.Orientation) PlaybackPanel.None
                                else PlaybackPanel.Orientation,
                            )
                        },
                        modifier = Modifier.tvRevealSource(orientationRevealOrigin),
                    )
                }
            }

            TransportCluster(
                playing = playing,
                onBack10 = onBack10,
                onPlayPause = replay ?: onPlayPause,
                onForward10 = onForward10,
                playFocusRequester = playFocusRequester,
                primaryEnabled = primaryLive,
                back10ContentDescription = stringResource(R.string.transport_back_10),
                playPauseContentDescription = stringResource(
                    when {
                        replay != null -> R.string.transport_watch_again
                        playing -> R.string.transport_pause
                        else -> R.string.transport_play
                    },
                ),
                forward10ContentDescription = stringResource(R.string.transport_forward_10),
            )

            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                // The mirror of the left pair, and for the same reason: the gap
                // between these two is the sibling step, not the row's 14 dp.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FlickSpace.Sm),
                ) {
                    VolumeCells(
                        level = volume,
                        onChange = onSetVolume,
                        contentDescription = stringResource(R.string.volume),
                        stateDescription = stringResource(
                            R.string.volume_state,
                            (volume.coerceIn(0f, 1f) * 100).toInt(),
                        ),
                        modifier = Modifier.focusRequester(volumeFocusRequester),
                    )
                    // Glyph only. The Mbps reading this key used to print is not
                    // something a viewer reads from across a room mid-film — it
                    // belongs in the panel the key opens, and in the description
                    // below for anyone who asks. Dropping the lockup is also what
                    // buys back the width that lets the play key sit on the
                    // screen's centre line.
                    GlyphKey(
                        glyph = FlickIcons.Monitoring,
                        contentDescription = stringResource(
                            R.string.metrics_card_description,
                            metricsSubLabel,
                        ),
                        open = openPanel == PlaybackPanel.Metrics,
                        focusRequester = metricsCardFocusRequester,
                        onClick = {
                            onOpenPanel(
                                if (openPanel == PlaybackPanel.Metrics) PlaybackPanel.None
                                else PlaybackPanel.Metrics,
                            )
                        },
                        modifier = Modifier.tvRevealSource(metricsRevealOrigin),
                    )
                }
            }
        }
    }
}

/**
 * The control row's two named keys — captions and picture turn — are ONE lockup:
 * a mark, a gap, a word, on a single line. They are siblings on screen and so they
 * are siblings in code, sharing this padding, this gap and this label style, which
 * is what puts their marks and their words on one common centre line.
 *
 * They used to disagree: the captions card stacked a title over a state line while
 * the turn control was a square stacking a mark over a label, and two controls
 * standing side by side with different internal geometry never quite line up no
 * matter how carefully each one is centred on its own.
 */
private val ControlKeyPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)

/** Mark to word, inside one key. */
private val ControlKeyGap = 9.dp

/**
 * The floor under every key in this row, and `Transport.kt` puts its ±10 s keys on
 * the same 48 dp: a control aimed at with a D-pad is still a target, and a lockup
 * that happens to be short is not a reason to shrink one.
 *
 * It is also what holds the two named keys to ONE plate height. Their marks differ
 * by 4 dp on purpose — see [OrientationGlyphSize] — and left to their content they
 * would hand that difference straight to their plates, which is exactly the kind of
 * near-miss that makes two controls standing side by side look wrong without
 * looking obviously wrong.
 */
private val ControlKeyMinHeight = 48.dp

/**
 * Both keys' word, at one size and one weight. Proportional rather than the mono
 * the turn label used to wear: `SUBS` and `180°` are the same rank of thing here,
 * and a degree sign is a mark on a number rather than a letter after it — mono's
 * eyebrow tracking pushed it off into `0 °`.
 */
@Composable
private fun controlKeyLabelStyle() = FlickType.body(sizeSp = 16, weight = FontWeight.Bold)

/**
 * Every glyph the control row carries, at one size. Larger than the 16 dp the old
 * stacked lockup used because a mark that carries a control's state is no longer a
 * bullet beside a word.
 */
private val ControlGlyphSize = 24.dp

/**
 * The captions key.
 *
 * The WHOLE key lights, not the mark alone: it inverts to the transport's own gold
 * with the play key's ink on it. That is the entire state — there is no `ON` / `OFF`
 * printed under it any more,
 * because a control that has lit up has already said it, and a word repeating it was
 * the reason this key was two lines tall and out of step with the turn key beside it.
 *
 * The state survives with the text gone: it is in [contentDescription], which is what
 * a screen reader reads and what a viewer who cannot separate amber from white still
 * gets. Colour is the only VISUAL channel here, and that is a deliberate cost paid for
 * a one-line key — it is bought back in the semantics rather than waived.
 */
@Composable
private fun SubtitlesKey(
    on: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Lit is the INVERT, not a tint of it. A translucent amber over this panel's
    // blue glass composites to a warm brown — it reads as "something happened to
    // this key" rather than as the transport's gold, and the gold is the whole
    // point. So the lit key takes the play key's own opaque [FlickColor.Spark] and
    // its [FlickColor.OnSpark] ink: one colour means "running" across the bar.
    //
    // The ring has to change with it. Amber on amber is not a ring, which is why
    // `FlickTvButton` documents [FlickColor.FocusRingOnSpark] as the ring any
    // caller overriding the container must pass.
    val plate = if (on) FlickColor.Spark else null
    // One ink for the mark and the word, and plain white at rest — the same white
    // the turn key beside it wears. A calmer OnChrome on the mark alone put two
    // different whites inside one lockup, next to a sibling using a third.
    val ink = if (on) FlickColor.OnSpark else Color.White
    FlickTvButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = ControlKeyMinHeight),
        // No `selected`: with the container and border passed explicitly it changes
        // no pixel, and all it would still do is make a screen reader append
        // "selected" to a button that OPENS A PANEL rather than holding a choice.
        containerColor = plate,
        borderColor = plate,
        ringColor = if (on) FlickColor.FocusRingOnSpark else FlickColor.FocusRing,
        contentDescription = stringResource(
            R.string.subtitles_card_description,
            stringResource(
                if (on) R.string.subtitles_state_on else R.string.subtitles_state_off,
            ),
        ),
        focusRequester = focusRequester,
        shape = FlickShape.Md,
        contentPadding = ControlKeyPadding,
        horizontalArrangement = Arrangement.spacedBy(ControlKeyGap),
    ) {
        InkIcon(
            imageVector = FlickIcons.ClosedCaption,
            tint = ink,
            modifier = Modifier.size(ControlGlyphSize),
        )
        InkText(
            text = stringResource(R.string.subtitles_card_title),
            color = ink,
            style = controlKeyLabelStyle(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The [FlickIcons.PictureTurn] mark, a step above [ControlGlyphSize].
 *
 * dp is not optical size. The captions mark fills its 24-unit grid nearly square,
 * while this one is a landscape frame 11.6 units tall — barely three quarters of
 * the ink in the same box. Matched at 24 dp it read as the smaller mark of the two.
 */
private val OrientationGlyphSize = 28.dp

/**
 * The picture-orientation key — the turn currently on screen as a mark, beside where
 * that turn came from as a word.
 *
 * The mark is what freed the word. [FlickIcons.PictureTurn] states the angle by
 * standing at it, and the one thing it cannot state is which of the five choices put
 * it there — so under Auto the word spends itself on `AUTO` and the degrees are read
 * off the mark. The precise value is still spoken, in `video_rotation_card_description`
 * over `video_rotation_auto_applied`, and still printed in the panel this key opens.
 *
 * No selected state: a panel REPLACES the control bar, so this key is never on screen
 * while its own panel is, and the panel itself is what says the panel is open.
 */
@Composable
private fun OrientationKey(
    degrees: String,
    auto: Boolean,
    turnDegrees: Int,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val readout = if (auto) {
        stringResource(R.string.video_rotation_auto_applied, degrees)
    } else {
        degrees
    }
    // The mark turns with the picture, and a turn is geometry: it takes a spatial
    // spring and is read inside the layer block, because this key sits over a live
    // decoder and may not recompose once a frame to move a glyph.
    //
    // Tracked as a running angle rather than as the modular value: 270° → 0° is a
    // quarter turn clockwise, and a raw target would unwind three quarters backwards
    // to reach the same picture. The Animatable seeds from the first composition, so
    // the key arrives already at the turn rather than spinning up from 0° the first
    // time the chrome appears.
    val reducedMotion = LocalReducedMotion.current
    val turnSpec: FiniteAnimationSpec<Float> = FlickMotion.panelSpatial()
    val turn = remember { Animatable(turnDegrees.toFloat()) }
    LaunchedEffect(turnDegrees, reducedMotion) {
        val delta = ((turnDegrees - turn.value) % 360f + 540f) % 360f - 180f
        val target = turn.value + delta
        if (reducedMotion) turn.snapTo(target) else turn.animateTo(target, turnSpec)
    }
    FlickTvButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = ControlKeyMinHeight),
        // The merged children would announce "AUTO", which is where the turn came
        // from and not what it is. This names the control and the reading both.
        contentDescription = stringResource(R.string.video_rotation_card_description, readout),
        focusRequester = focusRequester,
        shape = FlickShape.Md,
        contentPadding = ControlKeyPadding,
        horizontalArrangement = Arrangement.spacedBy(ControlKeyGap),
    ) {
        Icon(
            imageVector = FlickIcons.PictureTurn,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .size(OrientationGlyphSize)
                .graphicsLayer { rotationZ = turn.value },
        )
        FlickSwap(
            target = if (auto) stringResource(R.string.video_rotation_card_auto) else degrees,
            label = "orientationKeyLabel",
        ) {
            Text(
                text = it,
                style = controlKeyLabelStyle(),
                color = Color.White,
                maxLines = 1,
            )
        }
    }
}

/**
 * Side of the glyph-only keys. The same 48 dp as `SecondaryTransportTargetSize`,
 * so a key with nothing written on it is still the ±10 s keys' target and not a
 * smaller one.
 */
private val GlyphKeySide = 48.dp

/**
 * The mark inside a [GlyphKey], a step above [ControlGlyphSize].
 *
 * The captions mark beside it is a knockout — a solid plate — while the metrics mark
 * is a 1.8-unit stroke on the same grid, which is a fraction of the ink at the same
 * dp. The step is what levels them by eye; it is not a second scale.
 */
private val GlyphKeyGlyphSize = 24.dp

/**
 * A control whose whole content is its mark — the square sibling of [SubtitlesKey],
 * for a control with nothing to print.
 *
 * [contentDescription] is not optional here and is not the control's name alone: with
 * no title and no state line drawn, it is the only place the reading this key stands
 * for can be spoken.
 */
@Composable
private fun GlyphKey(
    glyph: ImageVector,
    contentDescription: String,
    open: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlickTvButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minWidth = GlyphKeySide, minHeight = GlyphKeySide),
        selected = open,
        contentDescription = contentDescription,
        focusRequester = focusRequester,
        shape = FlickShape.Md,
        contentPadding = PaddingValues(0.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = glyph,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(GlyphKeyGlyphSize),
        )
    }
}

// ── Overlays ────────────────────────────────────────────────────────────────

@Composable
private fun AnimatedVisibilityScope.SeekBurst(deltaMs: Long, speedLevel: Int, held: Boolean) {
    val forward = deltaMs >= 0L
    val reducedMotion = LocalReducedMotion.current
    val sign = if (deltaMs < 0L) "−" else "+"
    val seconds = kotlin.math.abs(deltaMs / 1_000L)
    val direction = if (forward) 1f else -1f

    // One accepted step, one kick. The impulse is driven by the accumulated delta
    // itself, so a held seek emitting four pulses kicks four times and a tap kicks
    // once — the burst reports the protocol rather than running a decorative loop.
    val impulseSpec: FiniteAnimationSpec<Float> = FlickMotion.flickSettleSpatial()
    val impulse = remember { Animatable(0f) }
    LaunchedEffect(deltaMs, reducedMotion) {
        if (reducedMotion) {
            impulse.snapTo(0f)
            return@LaunchedEffect
        }
        impulse.snapTo(1f)
        impulse.animateTo(0f, animationSpec = impulseSpec)
    }

    // The speed level eases between steps and is read only in the wash's draw, so
    // a level change neither steps the brightness nor rebuilds the brushes.
    val intensity = animateFloatAsState(
        targetValue = seekAccentIntensity(speedLevel),
        animationSpec = FlickMotion.orSnap(reducedMotion, FlickMotion.stateEffects()),
        label = "seekAccentIntensity",
    )
    val readIntensity = remember(intensity) { { intensity.value } }

    Box(Modifier.fillMaxSize()) {
        // The wash is drawn separately from the glyph column so the impulse can
        // move the glyph without sliding an edge-anchored gradient. It carries NO
        // graphicsLayer: the speed level is the amber accent's alpha inside the
        // wash, not the whole box's, and a layer alpha here would thin the dark
        // bed the glyph is read against — and buy an offscreen buffer the width of
        // a third of the screen over a live decoder to do it. The enclosing
        // AnimatedVisibility fade already composites the whole burst offscreen
        // during its in/out; what this avoids is a second, steady-state layer.
        Box(
            modifier = Modifier
                .align(if (forward) Alignment.CenterEnd else Alignment.CenterStart)
                .fillMaxWidth(SEEK_BURST_WIDTH_FRACTION)
                .fillMaxHeight()
                .seekBurstWash(
                    fromRight = forward,
                    accentIntensity = readIntensity,
                ),
        )
        Column(
            modifier = Modifier
                .align(if (forward) Alignment.CenterEnd else Alignment.CenterStart)
                .fillMaxWidth(SEEK_BURST_WIDTH_FRACTION)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                // Only the glyph and its label carry the scale; the wash behind
                // them is edge-anchored and would slide if it scaled too.
                modifier = (
                    if (reducedMotion) {
                        Modifier
                    } else {
                        Modifier.animateEnterExit(
                            enter = scaleIn(
                                animationSpec = FlickMotion.tvBurstScaleIn(),
                                initialScale = SEEK_BURST_ENTER_SCALE,
                            ),
                            exit = scaleOut(
                                animationSpec = FlickMotion.tvBurstExit(),
                                targetScale = SEEK_BURST_EXIT_SCALE,
                            ),
                            label = "seekBurstScale",
                        )
                    }
                    ).graphicsLayer {
                    val kick = impulse.value
                    val swell = 1f + (SEEK_BURST_IMPULSE_SCALE - 1f) * kick
                    scaleX = swell
                    scaleY = swell
                    translationX = kick * direction * SeekBurstNudge.toPx()
                },
                verticalArrangement = Arrangement.spacedBy(9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = if (forward) FlickIcons.Forward10 else FlickIcons.Replay10,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .graphicsLayer { rotationZ = impulse.value * direction * SEEK_BURST_GLYPH_SPIN }
                        .size(48.dp),
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
                    style = FlickType.display(sizeSp = 20),
                    color = Color.White,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The finished chip (spec §5.3). It is the design's glass pill on the state plate
 * rather than on the top-chrome [FlickColor.Glass] tone: it hangs at
 * [STATE_CHIP_TOP_FRACTION], two points BELOW where the top scrim ends, and on a
 * bright frame the pill tone left its label at 3.0:1 and its amber glyph at 1.7:1.
 *
 * Its twin for Paused is deliberately gone. A viewer who has just pressed pause
 * is being told something they did themselves, in the middle of the frame they
 * paused to look at; "the film is over" is the state nobody can read off a still.
 */
@Composable
private fun PlaybackFinishedChip(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .glassState(FlickShape.Pill)
            .padding(start = 10.dp, top = 7.dp, end = 14.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Icon(
            imageVector = FlickIcons.CheckCircle,
            contentDescription = null,
            tint = FlickColor.Spark,
            modifier = Modifier.size(FlickDimens.GlyphMedium),
        )
        Text(
            text = stringResource(R.string.playback_finished_chip),
            style = FlickType.display(sizeSp = 20),
            color = Color.White,
            maxLines = 1,
        )
    }
}

/**
 * The mini tile the hint carries — short enough to sit inside the two lines
 * beside it, and now sized by the mark rather than by the widest degrees string,
 * because the mark is what the square holds.
 */
private val OrientationHintTileSide = 38.dp

/** The mark inside [OrientationHintTileSide], kept clear of its hairline. */
private val OrientationHintGlyphSize = 20.dp

/**
 * The picture-orientation hint (§ Picture orientation).
 *
 * It leads with the transport's orientation tile in miniature — the same square,
 * the same unfocused control fill and hairline, the same mark standing at the same
 * turn — because that is the thing it is sending the viewer to look for. A
 * silhouette and not a copy: the hint only ever shows while the choice is Auto, so
 * the real key carries an `AUTO` word beside this mark that this square has no
 * room for.
 *
 * The mark is what makes the tile findable now, and [tileLabel] is the word the
 * real tile prints — not the degrees. Naming the tile by its degrees was true
 * while the degrees were the tile's whole content; under Auto they have moved into
 * the mark, and a sign is worthless if it names its target by something the target
 * does not show.
 *
 * The mark here does NOT animate. The real tile's does, because a viewer watching
 * it press a turn is owed the movement; this is a sign that arrives already
 * pointing, and a second spring on a card that lives four seconds would be motion
 * for its own sake.
 *
 * Drawn rather than the control itself: this is a sign, and a sign that could be
 * pressed would be a second control appearing over the film.
 *
 * It is not a control and never becomes one. No focus target, no click, no
 * `tvRevealSource`: every remote key while this is up is still routed exactly as
 * it was, and the chrome's own 4 s countdown never sees it.
 */
@Composable
private fun OrientationHintCard(
    hint: OrientationHint,
    tileLabel: String,
    turnDegrees: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .glassPanel(FlickShape.Md)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            modifier = Modifier
                .size(OrientationHintTileSide)
                .clip(FlickShape.Sm)
                .background(FlickColor.ControlFill)
                .border(FlickDimens.Hairline, FlickColor.Outline, FlickShape.Sm),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = FlickIcons.PictureTurn,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .size(OrientationHintGlyphSize)
                    .graphicsLayer { rotationZ = turnDegrees.toFloat() },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = stringResource(
                    when (hint) {
                        OrientationHint.TurnedUpright -> R.string.orientation_hint_turned_title
                        OrientationHint.ShownAsFiled -> R.string.orientation_hint_as_filed_title
                    },
                ),
                style = FlickType.body(sizeSp = 16, weight = FontWeight.Bold),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    when (hint) {
                        OrientationHint.TurnedUpright -> R.string.orientation_hint_turned_where
                        OrientationHint.ShownAsFiled -> R.string.orientation_hint_as_filed_where
                    },
                    tileLabel,
                ),
                style = FlickType.monoEyebrow(trackingEm = 0.14f),
                color = FlickColor.OnSurfaceDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The audio format a silent film's notice names, or null for the unknown form.
 *
 * DTS is the whole of the known list because it is the whole of the observed
 * problem: it is the one common format the verified TV declares no decoder for.
 * Everything else falls to the unknown form rather than being spelled out —
 * `audio/vnd.dts.uhd;profile=p2` is a true answer and not an answer anyone wants
 * from ten feet, and the sentence it would land in is already complete without it.
 */
internal fun audioSilentFormatLabel(mimeType: String?): String? = when (mimeType?.lowercase()) {
    "audio/vnd.dts" -> "DTS"
    "audio/vnd.dts.hd" -> "DTS-HD"
    else -> null
}

/**
 * The silent-audio notice (§ Playing without sound).
 *
 * Deliberately without the mini tile [OrientationHintCard] leads with, and that
 * absence is the design rather than a simplification. That card draws the control
 * it is sending the viewer to find; this one has no control behind it — no setting
 * on this TV and none on the phone plays a format the hardware has no decoder for
 * — so a square beside these lines would be a picture of a way out that does not
 * exist. Two lines and no affordance is the honest shape for a thing that can only
 * be known.
 *
 * It is not a control and never becomes one: no focus target, no click, no
 * `tvRevealSource`, and the chrome's own 4 s countdown never sees it.
 */
@Composable
private fun SilentAudioCard(mimeType: String, modifier: Modifier = Modifier) {
    val format = audioSilentFormatLabel(mimeType)
    val spoken = if (format != null) {
        stringResource(R.string.audio_silent_card_description, format)
    } else {
        stringResource(R.string.audio_silent_card_description_unknown)
    }
    Column(
        modifier = modifier
            .glassPanel(FlickShape.Md)
            .padding(horizontal = 11.dp, vertical = 9.dp)
            // The drawn detail line is set in caps for the ten-foot read, which is
            // not how it should be heard. One spoken sentence replaces both lines.
            .clearAndSetSemantics { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = stringResource(R.string.audio_silent_title),
            style = FlickType.body(sizeSp = 16, weight = FontWeight.Bold),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (format != null) {
                stringResource(R.string.audio_silent_reason, format)
            } else {
                stringResource(R.string.audio_silent_reason_unknown)
            },
            style = FlickType.monoEyebrow(trackingEm = 0.14f),
            color = FlickColor.OnSurfaceDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The band's event card: what just happened to this film, in two lines and with no
 * affordance.
 *
 * It carries [SilentAudioCard]'s shape rather than [OrientationHintCard]'s, and for
 * [SilentAudioCard]'s reason: there is no control behind any of these three, so a mini
 * tile beside them would be a picture of a way out that does not exist.
 *
 * [BandNotice.TurnUnavailable] deliberately borrows the orientation panel's own two
 * lines rather than getting copy of its own. Both are already film-scoped, which is the
 * only honest scope: the latch proves the turn failed for THIS film, not that this TV
 * can never turn anything.
 *
 * It is not a control and never becomes one: no focus target, no click, no
 * `tvRevealSource`, and the chrome's own 4 s countdown never sees it.
 */
@Composable
private fun BandNoticeCard(notice: BandNotice, modifier: Modifier = Modifier) {
    val spoken = stringResource(
        when (notice) {
            BandNotice.AudioRestart -> R.string.notice_audio_restart_description
            BandNotice.SubtitleDropped -> R.string.notice_subtitle_dropped_description
            BandNotice.TurnUnavailable -> R.string.notice_turn_unavailable_description
        },
    )
    Column(
        modifier = modifier
            .glassPanel(FlickShape.Md)
            .padding(horizontal = 11.dp, vertical = 9.dp)
            // [SilentAudioCard]'s reason: the drawn detail line is set in caps for the
            // ten-foot read, which is not how it should be heard. One spoken sentence
            // replaces both lines.
            .clearAndSetSemantics { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = stringResource(
                when (notice) {
                    BandNotice.AudioRestart -> R.string.notice_audio_restart_title
                    BandNotice.SubtitleDropped -> R.string.notice_subtitle_dropped_title
                    BandNotice.TurnUnavailable -> R.string.orientation_hint_as_filed_title
                },
            ),
            style = FlickType.body(sizeSp = 16, weight = FontWeight.Bold),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(
                when (notice) {
                    BandNotice.AudioRestart -> R.string.notice_audio_restart_line
                    BandNotice.SubtitleDropped -> R.string.notice_subtitle_dropped_line
                    BandNotice.TurnUnavailable -> R.string.video_rotation_note_locked
                },
            ),
            style = FlickType.monoEyebrow(trackingEm = 0.14f),
            color = FlickColor.OnSurfaceDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * T7. The calm rebuffer read — restyled onto the state plate, because it lands
 * dead centre, in the band neither scrim covers, and its only protection was the
 * 0.38 state dim. Over a white frame that left the title at 3.2:1 and both the
 * detail line and the amber loader under 1.9:1 — invisible exactly when the viewer
 * most needs to know the app has not died. The handshake card, which carries a
 * far less urgent message, has had a full-screen veil behind it all along.
 *
 * The words escalate once the buffer this plate is claiming to hold has been spent —
 * see [bufferingPlate]. Nothing about the recovery changes: it was only the narration
 * that went on saying "quality held" for minutes after it had stopped being true.
 */
@Composable
private fun BufferingOverlay(
    plate: BufferingPlate,
    deviceLabel: String?,
    modifier: Modifier = Modifier,
) {
    val device = deviceLabel ?: stringResource(R.string.device_fallback)
    GlassPanel(
        modifier = modifier,
        shape = FlickShape.Xl,
        tone = GlassPanelTone.State,
        contentPadding = PaddingValues(horizontal = 30.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        // A rebuffer is the frame with the least GPU headroom in the whole app, so
        // the shared entrance — a rise and a default-strategy fade — is off. The
        // fade the parent owns is `ModulateAlpha` with no transform: no offscreen
        // buffer, and nothing moving over the film.
        animateEntrance = false,
    ) {
        FlickLoader(
            // The morph runs on its own render node. The plate around it carries
            // no layer of its own, so without this the loader re-records the
            // buffering card's type — and the scrims above it — on every frame of
            // the one state with the least headroom in the app.
            modifier = Modifier.graphicsLayer(),
            // Held at the ring size this plate was measured against: its padding
            // and both type sizes were set around 40 dp, not the loader's own
            // 10-foot default.
            size = BufferingLoaderSize,
        )
        // A width change over a film may only cut, never animate, so the plate is
        // measured once against both plates' words and holds the wider width from
        // its first frame. The two sizers are measured and never placed: they draw
        // nothing and carry no semantics.
        Layout(
            content = {
                BufferingWords(BufferingPlate.TOPPING_UP, device, Modifier.clearAndSetSemantics { })
                BufferingWords(BufferingPlate.STALLED, device, Modifier.clearAndSetSemantics { })
                FlickSwap(target = plate, contentAlignment = Alignment.Center, label = "bufferingWords") { p ->
                    BufferingWords(p, device)
                }
            },
        ) { measurables, constraints ->
            val placeables = measurables.map { it.measure(constraints) }
            val w = placeables.maxOf { it.width }
            val h = placeables.maxOf { it.height }
            layout(w, h) {
                val swap = placeables.last()
                swap.place((w - swap.width) / 2, (h - swap.height) / 2)
            }
        }
    }
}

/** Renders only from its arguments, so the outgoing swap copy keeps what it showed. */
@Composable
private fun BufferingWords(p: BufferingPlate, device: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = stringResource(
                when (p) {
                    BufferingPlate.TOPPING_UP -> R.string.buffering_title
                    BufferingPlate.STALLED -> R.string.buffering_stalled_title
                },
            ),
            style = FlickType.display(sizeSp = 22),
            color = Color.White,
            maxLines = 1,
        )
        Text(
            text = when (p) {
                BufferingPlate.TOPPING_UP -> stringResource(R.string.buffering_detail)
                BufferingPlate.STALLED -> stringResource(R.string.buffering_stalled_detail, device)
            },
            style = FlickType.body(sizeSp = 16),
            color = FlickColor.OnSurfaceDim,
            maxLines = 1,
        )
    }
}

/**
 * The cap on [QualityCard]. Two fifths of the 960 dp Android TV band, which every
 * 1080p and 4K panel reports at its own density.
 *
 * The card is normally narrower than this — on the verified TV the widest row is
 * `decoder` at `c2.mtk.dvhe.sth.decoder`, 23 mono glyphs of 16 sp beside a 14 sp
 * label, which lands around 333 dp. Nothing bounds a vendor's decoder name
 * though, and this card sits at the TopEnd of the same band END SESSION occupies
 * on the left, so an unusually long one has to stop rather than walk back across
 * the frame. Past the cap the value ellipsises, which it is already set up to do.
 */
private val QualityCardMaxWidth = 384.dp

@Composable
private fun QualityCard(info: QualityInfo, modifier: Modifier = Modifier) {
    GlassPanel(
        // Every row below is `fillMaxWidth` + `SpaceBetween`, which is how a label
        // and its value reach the two edges — so the rows need a bounded width to
        // distribute against, and dropping the fill is not available. The card had
        // no width of its own, so what they bounded against was the whole frame the
        // AnimatedContent hands down: the card spanned the band and underlapped END
        // SESSION. `IntrinsicSize.Max` asks the widest row what it actually needs
        // and fixes the card there, which is the one width that keeps both halves
        // true. The second measure pass it costs is paid once per cast — the card's
        // content is fixed for the 4.5 s it is up, and its arrival and dismissal are
        // `graphicsLayer` transforms that never re-measure.
        modifier = modifier
            .widthIn(max = QualityCardMaxWidth)
            .width(IntrinsicSize.Max),
        shape = FlickShape.Xl,
        tone = GlassPanelTone.Panel,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
        // The `qualityFlourish` AnimatedContent above owns both halves of this
        // card's arrival and its 4.5 s dismissal. A second entrance latch here
        // would double the fade and, being one-way, strand the card on the exit.
        animateEntrance = false,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = info.qualityLabel,
                style = FlickType.body(sizeSp = 16, weight = FontWeight.Bold),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.size(6.dp).drawBehind { drawCircle(FlickColor.Live) })
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
                style = FlickType.monoEyebrow(trackingEm = 0.12f),
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
            style = FlickType.monoEyebrow(trackingEm = 0.12f),
            color = FlickColor.OnPanelLabel,
            maxLines = 1,
        )
        Spacer(Modifier.size(18.dp))
        Text(
            text = value,
            style = FlickType.monoTabular(sizeSp = 16, weight = FontWeight.SemiBold),
            color = FlickColor.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── Real-telemetry labels (spec §7 — omit, never fabricate) ─────────────────

/** One chip per telemetry slot, so a refined reading replaces its slot's text in place. */
private enum class SpecChipSlot { Video, Audio, VideoCodec }

private data class SpecChipEntry(val slot: SpecChipSlot, val text: String)

/**
 * The spec's three chips: resolution + HDR class, audio codec + channel count,
 * video codec. Each half is dropped when its source value is unknown, and a chip
 * with nothing left to say is not rendered at all. The design's fourth "18.4 GB"
 * chip is cut — the receiver streams byte ranges and never learns the file's
 * length.
 */
@Composable
private fun specChips(diagnostics: DiagnosticsSnapshot, hdr: HdrType): List<SpecChipEntry> {
    val chips = mutableListOf<SpecChipEntry>()

    val resolution = resolutionChipRes(diagnostics.width, diagnostics.height)?.let { stringResource(it) }
    val hdrLabel = hdrChipRes(hdr, diagnostics.videoMimeType != null)?.let { stringResource(it) }
    val video = when {
        resolution != null && hdrLabel != null -> stringResource(R.string.chip_video, resolution, hdrLabel)
        resolution != null -> resolution
        hdrLabel != null -> hdrLabel
        else -> null
    }
    if (video != null) chips += SpecChipEntry(SpecChipSlot.Video, video)

    val codec = audioCodecRes(diagnostics.audioMimeType)?.let { stringResource(it) }
    val channels = channelsChipLabel(diagnostics.audioChannelCount)
    val audio = when {
        codec != null && channels != null -> stringResource(R.string.chip_audio, codec, channels)
        codec != null -> codec
        channels != null -> channels
        else -> null
    }
    if (audio != null) chips += SpecChipEntry(SpecChipSlot.Audio, audio)

    // A Dolby Vision stream carries the same name in its HDR class and its video
    // MIME; one chip is a spec, two is a stutter.
    val videoCodec = videoCodecRes(diagnostics.videoMimeType)?.let { stringResource(it) }
    if (videoCodec != null && chips.none { it.text.contains(videoCodec) }) {
        chips += SpecChipEntry(SpecChipSlot.VideoCodec, videoCodec)
    }

    return chips
}

private fun resolutionChipRes(width: Int, height: Int): Int? =
    when (videoResolutionClass(width, height)) {
        VideoResolutionClass.Uhd -> R.string.chip_resolution_4k
        VideoResolutionClass.Qhd -> R.string.chip_resolution_1440p
        VideoResolutionClass.Fhd -> R.string.chip_resolution_1080p
        VideoResolutionClass.Hd -> R.string.chip_resolution_720p
        VideoResolutionClass.Sd -> R.string.chip_resolution_sd
        VideoResolutionClass.Unknown -> null
    }

/** What the receiver is allowed to call the frame it is decoding. */
internal enum class VideoResolutionClass { Uhd, Qhd, Fhd, Hd, Sd, Unknown }

/**
 * The frame's resolution class — the ONE place the receiver decides what to call
 * a picture size, shared by the transport's spec chip and the start-of-cast
 * quality card.
 *
 * **Classified on the long edge, not on height.** A 2.39:1 feature is encoded
 * 1920 × 804 with the letterbox baked out, and every scope film on the drive
 * therefore came out as "720p SDR" on the TV while the phone, looking at the same
 * file, correctly said 1080p — because 804 clears the 720 rung and nothing else.
 * Width is what actually tracks the format: 1920 is 1080p, 3840 is 4K, and DCI's
 * 2048 and 4096 land where they should. The short edge is kept as a second test
 * so a portrait or rotated frame — 720 × 1280 from a phone — is still read by the
 * dimension that carries its scale, and is not promoted to 1080p by its height.
 */
internal fun videoResolutionClass(width: Int, height: Int): VideoResolutionClass {
    if (width <= 0 || height <= 0) return VideoResolutionClass.Unknown
    val longEdge = maxOf(width, height)
    val shortEdge = minOf(width, height)
    return when {
        longEdge >= 3840 || shortEdge >= 2160 -> VideoResolutionClass.Uhd
        longEdge >= 2560 || shortEdge >= 1440 -> VideoResolutionClass.Qhd
        longEdge >= 1920 || shortEdge >= 1080 -> VideoResolutionClass.Fhd
        longEdge >= 1280 || shortEdge >= 720 -> VideoResolutionClass.Hd
        else -> VideoResolutionClass.Sd
    }
}

/**
 * The line count an SD frame is named by — its short edge, which is what "480p"
 * has always meant. Only [VideoResolutionClass.Sd] needs it; every rung above has
 * a name of its own.
 */
internal fun videoResolutionLines(width: Int, height: Int): Int = minOf(width, height)

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
