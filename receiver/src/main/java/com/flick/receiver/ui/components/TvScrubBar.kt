package com.flick.receiver.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.flick.receiver.R
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickMotion
import com.flick.receiver.ui.theme.LocalReducedMotion
import com.flick.receiver.ui.theme.playheadBrush
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * Media-time delta that means the clock did NOT simply run: a seek landing. The
 * 10 Hz confirmed-position feed advances the bar by ~100 ms per tick, so anything
 * an order of magnitude larger is a discontinuity worth reconciling on a spring.
 */
private const val RECONCILE_JUMP_MS = 1_000L

/**
 * How long the confirmed clock may stand still before the wave reads it as
 * stopped — six missed ticks of the 10 Hz feed. The wave is a claim about the
 * film, so it flattens on a stalled clock (paused, rebuffering, ended) whether
 * or not the caller passed [TvScrubBar]'s own `playing` flag.
 */
private const val CLOCK_STALL_MS = 600L

/** One swing every 16 dp, with 2 dp of throw either side of the track centre. */
private val WaveLength = 16.dp
private val WaveAmplitude = 2.dp

/**
 * Both ends of the played span settle back onto the centre line over this
 * distance: the left end is pinned to the bar's origin, and the right end has to
 * meet a knob that is drawn centred.
 */
private val WaveTaper = 9.dp

/**
 * The dead space between the playhead and the track ahead of it — the resting
 * knob's own radius plus clearance, so the knob sits in a gap rather than on top
 * of a line.
 */
private val TrackGap = 8.dp

/**
 * A crest crosses one wavelength in ~1.4 s — slower than the eye follows as
 * scrolling, fast enough to read as alive at 3 m. Not the media rate: the wave
 * says *running*, never *how fast*, because there is no speed to report.
 */
private const val WAVE_CYCLES_PER_SECOND = 0.7f
private val WavePeriodNanos: Long = (1_000_000_000.0 / WAVE_CYCLES_PER_SECOND).toLong()

private val TwoPi = (2.0 * PI).toFloat()

/**
 * A wavelength is sampled six times, which is what the stroke can actually hide.
 *
 * The swing is 2 dp under a 6 dp round-capped, round-joined stroke. Six samples put
 * the vertices 60° apart, so the worst crest a chord can cut — one straddled midway —
 * lands at cos 30° of full swing: 0.27 dp short, under a fifteenth of the stroke that
 * covers it. Twelve samples bought a facet correction far under a pixel while building
 * ~650 stroked segments across an ~864 dp span, every frame the chrome is up.
 */
private const val WAVE_SAMPLES_PER_WAVELENGTH = 6

/**
 * The TV scrub bar (receiver-expressive-spec.md §5.3 row 2). One session clock
 * drawn with the target/confirmed contract:
 *  - 6 dp pill track, drawn **only ahead of the playhead** (see below);
 *  - **buffered range** in translucent white ([bufferedMs]), also only ahead;
 *  - **played** = the amber `#FFB61E → #FFD87A` gradient, filled to the target,
 *    as a travelling wave while the film is running;
 *  - **knob ●** = a 12 dp white circle inside a 3 dp `Spark` @ 34 % halo, both
 *    swelling (12→16 dp, 18→26 dp) while [seeking];
 *  - **confirmed ○** = a hollow white ghost ring, drawn only while [seeking]
 *    (trailing the target — "sync is invisible when healthy").
 *
 * When not seeking, [targetMs] == [confirmedMs] and only the knob shows.
 *
 * **Draw order matters.** The inactive track starts [TrackGap] AHEAD of the
 * playhead and is never drawn beneath the played span: a full-width flat track
 * with the wave stroked over it shows a straight line through wherever the wave
 * leaves centre, which is the artefact this bar exists not to have.
 *
 * The wave's swing is a reading, not an ornament: it rises while the film runs,
 * is exactly flat while paused, seeking or under reduced motion, and its phase
 * loop only exists while there is a swing to carry. [playing] is what the caller
 * knows; the confirmed clock standing still for [CLOCK_STALL_MS] is what the bar
 * can see for itself, and either one flattens it.
 *
 * The drawn playhead tracks the confirmed clock **exactly**: a 10 Hz tick moves it
 * a fraction of a pixel, so animating it would only leave the amber fill
 * permanently trailing the film. Only a discontinuity — a seek landing, i.e. a
 * jump larger than [RECONCILE_JUMP_MS] of media — reconciles on
 * [FlickMotion.syncSpring], which is what "snap on release" reads as.
 *
 * **The bar is a focus target** while [interactive], and it is the one control on
 * the chrome that owns physical left/right: `TvRemoteKeyPolicy` reads
 * [onFocusChanged] through the app and turns horizontal keys into seeks only while
 * this bar holds focus. Focus is drawn on the KNOB rather than around the bar —
 * the §3 detached ring and the 1.06 lift are sized for a control, and on a 700 dp
 * span they would stretch the timeline itself — so the knob swells and takes the
 * ring, which is also the only part of the bar left/right actually moves.
 */
@Composable
fun TvScrubBar(
    durationMs: Long,
    confirmedMs: Long,
    bufferedMs: Long,
    modifier: Modifier = Modifier,
    targetMs: Long = confirmedMs,
    seeking: Boolean = false,
    playing: Boolean = true,
    interactive: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    val confirmedFrac = frac(confirmedMs, durationMs)
    val targetFrac = frac(targetMs, durationMs)
    val bufFrac = frac(bufferedMs, durationMs)
    val lagging = seeking && confirmedMs != targetMs
    val targetLabel = stringResource(R.string.scrub_target, clock(targetMs))
    val confirmedLabel = stringResource(R.string.scrub_confirmed, clock(confirmedMs))
    val syncingLabel = stringResource(R.string.syncing)
    val seekHint = stringResource(R.string.scrub_focus_hint)
    val accessibilityLabel = if (lagging) "$targetLabel, $confirmedLabel" else confirmedLabel

    val reducedMotion = LocalReducedMotion.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // Reported to the app, which is what lets the Activity-level policy hand
    // physical left/right to this bar instead of to the focus system.
    LaunchedEffect(focused) { onFocusChanged(focused) }
    val headFrac = if (seeking) targetFrac else confirmedFrac
    val playhead = remember { Animatable(headFrac) }
    val liveFrac = rememberUpdatedState(headFrac)
    val jumpFrac = rememberUpdatedState(
        if (durationMs > 0L) RECONCILE_JUMP_MS.toFloat() / durationMs.toFloat() else Float.MAX_VALUE,
    )
    LaunchedEffect(playhead, reducedMotion) {
        // A tick that lands while the spring is still reconciling re-aims it from
        // wherever the bar has reached, rather than snapping to the tick or queuing
        // behind the running spring: a held D-pad seek issues one jump per key
        // repeat, and either of those reads as a stutter once per repeat. Only a
        // settled bar tracks the clock by snapping, which is what keeps the amber
        // fill exactly on the film instead of permanently trailing it.
        snapshotFlow { liveFrac.value }.collectLatest { f ->
            val settled = !playhead.isRunning
            if (reducedMotion || (settled && abs(f - playhead.value) < jumpFrac.value)) {
                playhead.snapTo(f)
            } else {
                playhead.animateTo(f, FlickMotion.syncSpring())
            }
        }
    }

    // Whether the confirmed clock is actually moving. The first value only
    // establishes a baseline — a bar composed once with a static position (a
    // test, a stopped session) never claims the film is running, and therefore
    // never starts a frame loop.
    var clockRunning by remember { mutableStateOf(false) }
    val liveConfirmed = rememberUpdatedState(confirmedMs)
    LaunchedEffect(Unit) {
        var previous: Long? = null
        snapshotFlow { liveConfirmed.value }.collectLatest { ms ->
            val advanced = previous != null && ms != previous
            previous = ms
            if (!advanced) return@collectLatest
            clockRunning = true
            // Cancelled by the next tick; only a clock that has genuinely stopped
            // reaches the far side of this.
            delay(CLOCK_STALL_MS)
            clockRunning = false
        }
    }

    val amplitude = remember { Animatable(0f) }
    val phase = remember { mutableFloatStateOf(0f) }
    val wavePath = remember { Path() }
    val currentPlaying = rememberUpdatedState(playing)
    val currentSeeking = rememberUpdatedState(seeking)
    val currentReducedMotion = rememberUpdatedState(reducedMotion)
    // Geometry grows on a spatial spring and flattens on effects: an amplitude
    // that rings past zero inverts the wave, which is a claim the bar is not
    // making. Read through holders so a rebuilt spec cannot restart the swell.
    val amplitudeRise = rememberUpdatedState(FlickMotion.panelSpatial<Float>())
    val amplitudeFall = rememberUpdatedState(FlickMotion.fastStateEffects<Float>())
    LaunchedEffect(amplitude) {
        snapshotFlow {
            !currentReducedMotion.value &&
                !currentSeeking.value &&
                currentPlaying.value &&
                clockRunning
        }.collect { swinging ->
            val target = if (swinging) 1f else 0f
            when {
                target == amplitude.value -> Unit
                // A viewer who is steering wants a straight edge to aim at, and a
                // viewer who asked for no motion wants none at all: neither waits
                // out a spring.
                target == 0f && (currentSeeking.value || currentReducedMotion.value) ->
                    amplitude.snapTo(0f)
                target > amplitude.value -> amplitude.animateTo(target, amplitudeRise.value)
                else -> amplitude.animateTo(target, amplitudeFall.value)
            }
        }
    }
    LaunchedEffect(amplitude) {
        // withInfiniteAnimationFrameNanos so the test clock intercepts the loop; a
        // bare frame loop would keep waitForIdle from ever returning. collectLatest
        // ends it the moment the bar goes flat, so a paused or reduced-motion
        // session posts no frame callback at all.
        snapshotFlow { amplitude.value > 0f }.collectLatest { moving ->
            if (!moving) return@collectLatest
            // The frame clock counts from boot and a Float cannot hold days of
            // nanoseconds at frame resolution, so the phase is measured from this
            // loop's own first frame rather than from the epoch.
            var origin = 0L
            while (true) {
                withInfiniteAnimationFrameNanos { nanos ->
                    if (origin == 0L) origin = nanos
                    phase.floatValue =
                        ((nanos - origin) % WavePeriodNanos) / WavePeriodNanos.toFloat()
                }
            }
        }
    }

    // Steering rather than watching — the bar holding focus, or a seek in flight
    // — is the one moment the knob grows to meet the viewer.
    val swell = animateFloatAsState(
        targetValue = if (seeking || focused) 1f else 0f,
        animationSpec = if (reducedMotion) snap() else FlickMotion.focusSpatial(),
        label = "scrubSeekSwell",
    )
    val ringPresence = animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = if (reducedMotion) snap() else FlickMotion.stateEffects(),
        label = "scrubFocusRing",
    )
    val ghost = animateFloatAsState(
        targetValue = if (lagging) 1f else 0f,
        animationSpec = if (reducedMotion) snap() else FlickMotion.stateEffects(),
        label = "scrubGhostFade",
    )

    // Hoisted: the played fill redraws on every position tick, and a gradient
    // rebuilt inside the draw lambda would allocate on each one. The brush
    // resolves against the canvas width, so the played portion shows the left
    // part of one track-wide amber ramp.
    val playedBrush = playheadBrush()

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            // The resting halo is the tallest thing laid out here: 18 dp across.
            // The seeking halo is deliberately NOT budgeted for — see the swell.
            .height(20.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties { canFocus = interactive }
            .focusable(interactionSource = interaction)
            .semantics {
                contentDescription = accessibilityLabel
                when {
                    lagging -> stateDescription = syncingLabel
                    // Left/right are a focus move everywhere else on this chrome,
                    // so the one control where they are not says so.
                    focused -> stateDescription = seekHint
                }
            }
            // The bar's own render node. The wave repaints this canvas on EVERY
            // frame the film is running, and without a layer here that repaint
            // invalidates the transport panel's draw instead — re-recording the
            // display list of the title, both timecodes, the chips and both side
            // cards, sixty times a second, to move a sine. `clip` stays false: the
            // knob's halo and the focus ring are painted outside these 20 dp.
            .graphicsLayer(),
    ) {
        val cy = size.height / 2f
        val barH = 6.dp.toPx()
        val r = barH / 2f
        // Read in the draw phase so the swell costs a redraw, not a recomposition.
        // At full swell the halo is 26 dp across against a 20 dp canvas: like the
        // focus ring, it is painted rather than laid out, so it overhangs into the
        // row's own spacing instead of making the transport panel taller.
        val seekSwell = swell.value
        val knobR = lerp(6.dp.toPx(), 8.dp.toPx(), seekSwell)
        val haloR = lerp(9.dp.toPx(), 13.dp.toPx(), seekSwell)
        fun px(f: Float) = (size.width * f).coerceIn(0f, size.width)

        // Read in the draw phase, not at composition, so the running clock
        // invalidates only this canvas.
        val head = px(playhead.value.coerceIn(0f, 1f))

        // Everything unplayed starts a gap ahead of the head and nothing is ever
        // drawn beneath the played span — see the class doc. At head = 1 there is
        // no track left to draw; at head = 0 the whole bar is track.
        val aheadStart = (head + TrackGap.toPx()).coerceAtMost(size.width)
        val aheadWidth = size.width - aheadStart
        if (aheadWidth > 0.5f) {
            drawRoundRect(
                color = FlickColor.TrackBase,
                topLeft = Offset(aheadStart, cy - r),
                size = Size(aheadWidth, barH),
                cornerRadius = CornerRadius(r, r),
            )
            // A buffer that has not yet reached the playhead has nothing to show.
            val bufferedWidth = px(bufFrac) - aheadStart
            if (bufferedWidth > 0.5f) {
                drawRoundRect(
                    color = FlickColor.TrackBuffered,
                    topLeft = Offset(aheadStart, cy - r),
                    size = Size(bufferedWidth, barH),
                    cornerRadius = CornerRadius(r, r),
                )
            }
        }

        if (head > 0f) {
            val amp = amplitude.value * WaveAmplitude.toPx()
            // Under one stroke width there is no span left to swing through, only
            // the cap — so the first seconds of a file stay a flat nub.
            if (amp <= 0.01f || head <= barH) {
                drawRoundRect(
                    brush = playedBrush,
                    topLeft = Offset(0f, cy - r),
                    size = Size(head, barH),
                    cornerRadius = CornerRadius(r, r),
                )
            } else {
                drawPlayedWave(
                    path = wavePath,
                    brush = playedBrush,
                    width = head,
                    cy = cy,
                    stroke = barH,
                    amplitude = amp,
                    wavelength = WaveLength.toPx(),
                    taper = WaveTaper.toPx(),
                    phase = phase.floatValue,
                )
            }
        }

        // The gap between the confirmed position and the pending target is the
        // only thing that ever draws twice; it fades out the moment sync lands
        // rather than cutting, so a landing reads as catching up, not as a glitch.
        val ghostAlpha = ghost.value
        if (ghostAlpha > 0f) {
            drawLine(
                color = FlickColor.SparkLight.copy(alpha = 0.8f * ghostAlpha),
                start = Offset(px(confirmedFrac), cy),
                end = Offset(px(targetFrac), cy),
                strokeWidth = 1.dp.toPx(),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.82f * ghostAlpha),
                radius = 5.dp.toPx(),
                center = Offset(px(confirmedFrac), cy),
                // Held at 2 dp: the ghost is a hollow ring on moving film and a
                // proportional 1.6 dp stroke would not read across the room.
                style = Stroke(width = 2.dp.toPx()),
            )
        }

        drawCircle(FlickColor.FocusRingSoft, radius = haloR, center = Offset(head, cy))
        drawCircle(Color.White, radius = knobR, center = Offset(head, cy))

        // The §3 ring, concentric with the knob and read in the draw phase like
        // everything else here. It carries the same dark contour the detached ring
        // wears elsewhere: part of this circle lands on the film, where amber
        // alone measures 1.2:1.
        val lit = ringPresence.value
        if (lit > 0.01f) {
            val ringR = knobR + FlickFocusRingOffset.toPx() * lit
            val stroke = FlickFocusRingWidth.toPx()
            drawCircle(
                color = FlickColor.FocusRingContour,
                radius = ringR,
                center = Offset(head, cy),
                alpha = lit,
                style = Stroke(width = stroke + FlickFocusRingContourWidth.toPx() * 2f),
            )
            drawCircle(
                color = FlickColor.FocusRing,
                radius = ringR,
                center = Offset(head, cy),
                alpha = lit,
                style = Stroke(width = stroke),
            )
        }
    }
}

/**
 * The played span as a travelling wave, stroked at the track's own height with
 * round caps so it occupies exactly the span the flat fill would — the wave
 * REPLACES the fill rather than riding on top of it.
 *
 * The swing tapers to zero within [taper] of either end: the left end is the
 * bar's fixed origin and would visibly bob against the timecode beside it, and
 * the right end has to arrive at the centred knob.
 */
private fun DrawScope.drawPlayedWave(
    path: Path,
    brush: Brush,
    width: Float,
    cy: Float,
    stroke: Float,
    amplitude: Float,
    wavelength: Float,
    taper: Float,
    phase: Float,
) {
    val start = stroke / 2f
    val end = (width - stroke / 2f).coerceAtLeast(start)
    val step = (wavelength / WAVE_SAMPLES_PER_WAVELENGTH).coerceAtLeast(1f)
    // Subtracting the phase carries the crests toward the head, the way play runs.
    fun y(x: Float): Float {
        val envelope = if (taper <= 0f) {
            1f
        } else {
            min(1f, min(x - start, end - x) / taper)
        }
        return cy + amplitude * envelope * sin(TwoPi * (x / wavelength - phase))
    }
    path.rewind()
    var x = start
    path.moveTo(x, y(x))
    while (x < end) {
        x = (x + step).coerceAtMost(end)
        path.lineTo(x, y(x))
    }
    drawPath(
        path = path,
        brush = brush,
        style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

private fun frac(ms: Long, durationMs: Long): Float =
    if (durationMs <= 0L) 0f else (ms.coerceAtLeast(0L).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

private fun clock(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / 1_000L
    val hours = seconds / 3_600L
    val minutes = (seconds % 3_600L) / 60L
    val remainder = seconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, remainder)
    else "%d:%02d".format(minutes, remainder)
}
