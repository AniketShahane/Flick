package com.flick.sender.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.flick.sender.R
import com.flick.sender.ui.Format
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickGradients
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.Ink
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.Motion
import com.flick.sender.ui.theme.PillShape
import com.flick.sender.ui.theme.PosterShadow
import com.flick.sender.ui.theme.rememberReduceMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The phone scrub bar — the hero instrument (spec §6).
 *
 * The track swells 13→22dp and the thumb 6×28→10×40dp while the finger is down; the
 * amber played fill runs to [targetFraction] (the optimistic head) while a pale-blue
 * echo marks [ghostFraction] (the last position the TV confirmed). A real decoded
 * frame rides above the thumb, and the time row underneath carries the shimmering
 * SYNCING… chip whenever the TV clock has gone stale.
 *
 * The played fill is a **wave**, and its swing is a reading rather than an ornament:
 * it runs while the TV reports [playing], swings wider while its clock is stale, and
 * is exactly flat while paused or under a finger.
 *
 * Every moving value arrives as a lambda and is consumed inside a draw or layout
 * scope: the session clock ticks ~10Hz and must never recompose this tree.
 */
@Composable
fun PhoneScrubBar(
    targetFraction: () -> Float,
    ghostFraction: () -> Float?,
    syncing: Boolean,
    framePreview: ImageBitmap?,
    previewLabel: () -> String?,
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
    playing: () -> Boolean = { false },
    reservePreviewSpace: Boolean = false,
    bufferedFraction: () -> Float = { 0f },
    positionMs: () -> Long = { 0L },
    durationMs: () -> Long = { 0L },
    targetLabel: String? = null,
    confirmedLabel: String? = null,
    stateLabel: String? = null,
    adjustableActionLabel: String? = null,
) {
    val colors = LocalFlickColors.current
    val reduceMotion = rememberReduceMotion()
    val scope = rememberCoroutineScope()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    var dragging by remember { mutableStateOf(false) }
    var widthPx by remember { mutableIntStateOf(1) }
    val endGate = remember { ScrubEndGate() }

    // Track/thumb growth and the detent ripple live in Animatables rather than
    // animateDpAsState so the swell plays entirely in the draw phase; recomposing
    // this subtree ~20x per gesture would drag the frame preview along with it.
    val swell = remember { Animatable(0f) }
    // A spring, so a release that interrupts the grab retargets from the velocity the
    // swell already carries instead of restarting on a fresh clock. Hoisted because
    // reading the motion scheme is a composition read and the gesture scope is not
    // composable; the playhead's own mapping from media time to x stays unsprung.
    val swellSpec = Motion.orSnap(reduceMotion, MaterialTheme.motionScheme.fastSpatialSpec<Float>())
    val previewEffectSpec = Motion.orSnap(reduceMotion, MaterialTheme.motionScheme.fastEffectsSpec<Float>())
    val previewSpatialSpec = Motion.orSnap(reduceMotion, MaterialTheme.motionScheme.defaultSpatialSpec<Float>())
    // Material's wavy-progress amplitude specs are internal to material3, so the
    // wave's swell reads the scheme directly: geometry grows on a spatial spring,
    // and flattens on effects because an amplitude that rings past zero inverts the
    // wave — a claim the bar is not making.
    val amplitudeRise = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val amplitudeFall = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val ripple = remember { DetentRipple() }

    // The wave's swing and its travelling phase, both consumed in the draw scope.
    val amplitude = remember { Animatable(0f) }
    val phase = remember { mutableFloatStateOf(0f) }
    val wavePath = remember { Path() }
    val currentPlaying by rememberUpdatedState(playing)
    val currentSyncing by rememberUpdatedState(syncing)
    val currentReduceMotion by rememberUpdatedState(reduceMotion)
    LaunchedEffect(amplitude) {
        // playing() is read here rather than in composition: the caller's lambda reads
        // the same state object the 10Hz clock lives in, and snapshotFlow emits only
        // when the boolean itself changes. A flat bar is a claim about the TV, so a
        // paused or grabbed bar must reach exactly 0 and not merely approach it.
        snapshotFlow {
            when {
                currentReduceMotion || dragging || !currentPlaying() -> 0f
                currentSyncing -> SyncingAmplitude
                else -> 1f
            }
        }.collect { target ->
            when {
                target == amplitude.value -> Unit
                // The finger wants a straight edge to aim at, immediately.
                target == 0f && (dragging || currentReduceMotion) -> amplitude.snapTo(0f)
                target > amplitude.value -> amplitude.animateTo(target, amplitudeRise)
                else -> amplitude.animateTo(target, amplitudeFall)
            }
        }
    }
    LaunchedEffect(amplitude) {
        // withInfiniteAnimationFrameNanos so the test clock intercepts the loop; a bare
        // frame loop would keep waitForIdle from ever returning. collectLatest ends it
        // the moment the bar goes flat, so a paused remote posts no frame callback.
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

    // If the bar is torn out from under a live drag (e.g. a mid-stream rebuffer swaps
    // the screen), the gesture coroutine is cancelled without reaching onScrubEnd —
    // leaving the session stuck scrubbing with no final seek. Close the gesture on
    // dispose so scrubbing always terminates cleanly.
    val currentOnScrubEnd by rememberUpdatedState(onScrubEnd)
    DisposableEffect(Unit) {
        onDispose { endGate.finish(currentOnScrubEnd) }
    }

    Column(modifier.bringIntoViewRequester(bringIntoViewRequester)) {
        // A scroll container clips to its viewport. Compact remotes reserve this
        // measured space so the preview's upward offset stays inside that viewport.
        if (reservePreviewSpace) Spacer(Modifier.height(PreviewClearance))
        Box(
            Modifier
                .fillMaxWidth()
                // The mock's grab row is 36dp; the node is padded to a legal touch
                // target because the instrumentation contract asserts ≥48dp here.
                .height(HitHeight)
                .onSizeChanged { widthPx = it.width }
                .semantics {
                    // Read through the leaf lambda rather than pulling the live
                    // playhead into the remote's composition scope.
                    progressBarRangeInfo = ProgressBarRangeInfo(targetFraction(), 0f..1f)
                    targetLabel?.let { contentDescription = it }
                    listOfNotNull(confirmedLabel, stateLabel)
                        .takeIf { it.isNotEmpty() }
                        ?.joinToString(separator = ", ")
                        ?.let { stateDescription = it }
                    setProgress(adjustableActionLabel) { fraction ->
                        endGate.start()
                        if (reservePreviewSpace) {
                            scope.launch { bringIntoViewRequester.bringIntoView() }
                        }
                        onScrubStart()
                        onScrub(fraction.coerceIn(0f, 1f))
                        endGate.finish(currentOnScrubEnd)
                        true
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val w = { size.width.toFloat().coerceAtLeast(1f) }
                        // 36 spatial notches across the bar. The tick the hand feels is
                        // emitted by PlaybackSession on its own boundary; the bar owns
                        // only the ripple, so the two must not both drive the vibrator.
                        var detent = -1
                        endGate.start()
                        if (reservePreviewSpace) {
                            // The requester is attached to the full component, including
                            // the preview headroom, before this gesture starts the session
                            // scrub. Normal remotes never request relocation.
                            scope.launch { bringIntoViewRequester.bringIntoView() }
                        }
                        dragging = true
                        scope.launch { swell.animateTo(1f, swellSpec) }
                        try {
                            onScrubStart()
                            val first = (down.position.x / w()).coerceIn(0f, 1f)
                            detent = detentIndex(first)
                            onScrub(first)
                            down.consume()
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: event.changes.firstOrNull()
                                if (change == null) break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                val fraction = (change.position.x / w()).coerceIn(0f, 1f)
                                val crossed = detentIndex(fraction)
                                if (crossed != detent) {
                                    detent = crossed
                                    if (!reduceMotion && durationMs() > 0L) ripple.fire(scope)
                                }
                                onScrub(fraction)
                                change.consume()
                            }
                        } finally {
                            dragging = false
                            ripple.clear(scope)
                            scope.launch { swell.animateTo(0f, swellSpec) }
                            endGate.finish(currentOnScrubEnd)
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                // Every pointer-rate read happens here, inside the draw scope, so a
                // scrub re-runs the draw phase and nothing above it.
                val t = swell.value
                val target = targetFraction().coerceIn(0f, 1f)
                val cy = size.height / 2f
                val trackH = lerp(TrackIdle.toPx(), TrackDrag.toPx(), t)
                val trackR = trackH / 2f
                val w = size.width

                drawRoundRect(
                    color = colors.fillTrackAlt,
                    topLeft = Offset(0f, cy - trackR),
                    size = Size(w, trackH),
                    cornerRadius = CornerRadius(trackR, trackR),
                )
                val bufferedW = bufferedFraction().coerceIn(0f, 1f) * w
                if (bufferedW > 0f) {
                    drawRoundRect(
                        color = colors.fillBuffered,
                        topLeft = Offset(0f, cy - trackR),
                        size = Size(bufferedW, trackH),
                        cornerRadius = CornerRadius(trackR, trackR),
                    )
                }
                val playedW = target * w
                if (playedW > 0f) {
                    val amp = amplitude.value * WaveAmplitude.toPx()
                    // Under one stroke width there is no span left to swing through,
                    // only the cap — so the first seconds of a file stay a flat nub.
                    if (amp <= 0f || playedW < trackH) {
                        drawRoundRect(
                            brush = FlickGradients.playhead,
                            topLeft = Offset(0f, cy - trackR),
                            size = Size(playedW, trackH),
                            cornerRadius = CornerRadius(trackR, trackR),
                        )
                    } else {
                        drawPlayedWave(
                            path = wavePath,
                            width = playedW,
                            cy = cy,
                            stroke = trackH,
                            amplitude = amp,
                            wavelength = WaveLength.toPx(),
                            phase = phase.floatValue,
                        )
                    }
                }

                // The echo paints OVER the amber fill and under the thumb, matching the
                // prototype's z-index 2: during a forward drag the confirmed position
                // always sits behind the head, so putting it under the fill would hide
                // the one thing it exists to show.
                val ghost = ghostFraction()
                if (dragging && ghost != null && abs(ghost - target) > GhostThreshold) {
                    val halfGhost = GhostWidth.toPx() / 2f
                    val gx = (ghost.coerceIn(0f, 1f) * w).coerceIn(halfGhost, (w - halfGhost).coerceAtLeast(halfGhost))
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(colors.ghost.copy(alpha = 0.55f), Color.Transparent),
                            center = Offset(gx, cy),
                            radius = GhostGlow.toPx(),
                        ),
                        radius = GhostGlow.toPx(),
                        center = Offset(gx, cy),
                    )
                    drawRoundRect(
                        color = colors.ghost,
                        topLeft = Offset(gx - halfGhost, cy - GhostHeight.toPx() / 2f),
                        size = Size(GhostWidth.toPx(), GhostHeight.toPx()),
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                    )
                }

                val thumbW = lerp(ThumbIdleW.toPx(), ThumbDragW.toPx(), t)
                val thumbH = lerp(ThumbIdleH.toPx(), ThumbDragH.toPx(), t)
                val halfThumb = thumbW / 2f
                val tx = (target * w).coerceIn(halfThumb, (w - halfThumb).coerceAtLeast(halfThumb))
                drawThumbShadow(tx, cy, thumbW, thumbH)
                drawRoundRect(
                    color = colors.onSurface,
                    topLeft = Offset(tx - halfThumb, cy - thumbH / 2f),
                    size = Size(thumbW, thumbH),
                    cornerRadius = CornerRadius(halfThumb, halfThumb),
                )

                val r = ripple.progress.value
                if (r < 1f) {
                    drawCircle(
                        color = colors.onSurface.copy(alpha = Motion.RippleFromAlpha * (1f - r)),
                        radius = RippleBase.toPx() * lerp(Motion.RippleFromScale, Motion.RippleToScale, r),
                        center = Offset(tx, cy),
                        style = Stroke(width = 2.5.dp.toPx()),
                    )
                }
            }

            // Fully qualified: inside this Box the enclosing Column's scoped overload
            // would otherwise win overload resolution.
            androidx.compose.animation.AnimatedVisibility(
                visible = dragging,
                enter = if (reduceMotion) {
                    EnterTransition.None
                } else {
                    fadeIn(previewEffectSpec) + scaleIn(previewSpatialSpec, initialScale = 0.86f)
                },
                exit = if (reduceMotion) {
                    ExitTransition.None
                } else {
                    fadeOut(previewEffectSpec) + scaleOut(previewSpatialSpec, targetScale = 0.86f)
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    // Tracks the thumb in the layout phase, clamped inside the bar, so
                    // the card itself never recomposes while the finger moves.
                    .offset {
                        val cardW = PreviewWidth.toPx()
                        val centre = targetFraction().coerceIn(0f, 1f) * widthPx
                        val maxX = (widthPx - cardW).coerceAtLeast(0f)
                        IntOffset(
                            (centre - cardW / 2f).coerceIn(0f, maxX).roundToInt(),
                            PreviewLift.toPx().roundToInt(),
                        )
                    },
            ) {
                FramePreview(framePreview, previewLabel)
            }
        }

        Spacer(Modifier.height(11.dp))
        TimeRow(syncing = syncing, positionMs = positionMs, durationMs = durationMs)
    }
}

/** Left: the optimistic head. Right: what is left. Centre: the sync chip, when the TV clock stalls. */
@Composable
private fun TimeRow(
    syncing: Boolean,
    positionMs: () -> Long,
    durationMs: () -> Long,
) {
    Box(Modifier.fillMaxWidth()) {
        PositionTime(Modifier.align(Alignment.CenterStart), positionMs)
        if (syncing) {
            SyncChip(Modifier.align(Alignment.Center))
        }
        RemainingTime(Modifier.align(Alignment.CenterEnd), positionMs, durationMs)
    }
}

/** Isolated so the running clock recomposes one Text, never the instrument. */
@Composable
private fun PositionTime(modifier: Modifier, positionMs: () -> Long) {
    val colors = LocalFlickColors.current
    val text = Format.timecode(positionMs())
    val description = stringResource(R.string.a11y_scrub_target, text)
    Text(
        text = text,
        style = FlickText.monoValue.copy(color = colors.onSurface),
        modifier = modifier.semantics { contentDescription = description },
    )
}

@Composable
private fun RemainingTime(modifier: Modifier, positionMs: () -> Long, durationMs: () -> Long) {
    val colors = LocalFlickColors.current
    val position = positionMs()
    val duration = durationMs()
    // The visible form carries the U+2212 sign; the spoken form does not.
    val description = stringResource(
        R.string.a11y_np_remaining,
        Format.timecode((duration - position).coerceAtLeast(0L)),
    )
    Text(
        text = Format.remaining(position, duration),
        style = FlickText.monoValue.copy(color = colors.onSurfaceFaint),
        modifier = modifier.semantics { contentDescription = description },
    )
}

@Composable
private fun SyncChip(modifier: Modifier) {
    val colors = LocalFlickColors.current
    val reduceMotion = rememberReduceMotion()
    // A loop never reaches an end state, so it is gated rather than snapped.
    val sweep = if (reduceMotion) {
        null
    } else {
        rememberInfiniteTransition(label = "sync").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(Motion.ShimmerMs, easing = Motion.Steady)),
            label = "syncSweep",
        )
    }
    Text(
        text = stringResource(R.string.syncing),
        style = FlickText.monoEyebrow.copy(color = colors.ghost),
        modifier = modifier
            .clip(PillShape)
            .background(colors.ghost.copy(alpha = 0.12f))
            .then(
                if (sweep == null) {
                    Modifier
                } else {
                    Modifier.drawWithContent {
                        val span = size.width * 1.4f
                        drawRect(FlickGradients.syncShimmer(-span + sweep.value * (size.width + 2f * span), span))
                        drawContent()
                    }
                },
            )
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

/** The decoded frame at the drag position, with the target timecode beneath it. */
@Composable
private fun FramePreview(bitmap: ImageBitmap?, label: () -> String?) {
    val colors = LocalFlickColors.current
    val shape = RoundedCornerShape(FlickCorners.previewThumb)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .shadow(14.dp, shape, clip = false, ambientColor = PosterShadow, spotColor = PosterShadow)
                .size(width = PreviewWidth, height = PreviewHeight)
                .clip(shape)
                .background(colors.surfaceRaisedAlt)
                .border(2.5.dp, colors.onSurface, shape),
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        PreviewTimecode(label)
    }
}

/** Only this chip follows the drag clock; the frame card above it stays put. */
@Composable
private fun PreviewTimecode(label: () -> String?) {
    val colors = LocalFlickColors.current
    val text = label() ?: return
    Text(
        text = text,
        // The amber chip is palette-independent, so it carries the fixed navy the FAB
        // uses on the same gradient family rather than a surface role.
        style = FlickText.monoValue.copy(color = Ink, textAlign = TextAlign.Center),
        modifier = Modifier
            .clip(PillShape)
            .background(colors.spark)
            .padding(horizontal = 11.dp, vertical = 7.dp),
    )
}

/**
 * The played fill as a travelling wave, stroked at the track's own height with round
 * caps so it occupies exactly the span the flat fill would. A wavelength is sampled
 * twelve times: under a 13–22dp stroke the facets are invisible, and sampling per pixel
 * would rebuild several hundred points on every frame of a gesture.
 */
private fun DrawScope.drawPlayedWave(
    path: Path,
    width: Float,
    cy: Float,
    stroke: Float,
    amplitude: Float,
    wavelength: Float,
    phase: Float,
) {
    val start = stroke / 2f
    val end = (width - start).coerceAtLeast(start)
    val step = (wavelength / 12f).coerceAtLeast(1f)
    // Subtracting the phase carries the crests toward the head, the way play runs.
    fun y(x: Float) = cy + amplitude * sin(TwoPi * (x / wavelength - phase))
    path.rewind()
    var x = start
    path.moveTo(x, y(x))
    do {
        x = (x + step).coerceAtMost(end)
        path.lineTo(x, y(x))
    } while (x < end)
    drawPath(
        path = path,
        brush = FlickGradients.playhead,
        style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/** Compose takes shadows as elevation, not as a blur radius — two soft passes stand in. */
private fun DrawScope.drawThumbShadow(cx: Float, cy: Float, width: Float, height: Float) {
    val spread = 3.dp.toPx()
    val drop = 4.dp.toPx()
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.20f),
        topLeft = Offset(cx - width / 2f - spread, cy - height / 2f - spread + drop),
        size = Size(width + spread * 2f, height + spread * 2f),
        cornerRadius = CornerRadius((width + spread * 2f) / 2f, (width + spread * 2f) / 2f),
    )
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.28f),
        topLeft = Offset(cx - width / 2f, cy - height / 2f + drop),
        size = Size(width, height),
        cornerRadius = CornerRadius(width / 2f, width / 2f),
    )
}

/** Restartable 420ms ring at the thumb. Kept out of composition so it is a draw-only cost. */
private class DetentRipple {
    val progress = Animatable(1f)
    private var job: Job? = null

    fun fire(scope: CoroutineScope) {
        job?.cancel()
        job = scope.launch {
            progress.snapTo(0f)
            progress.animateTo(1f, Motion.detent<Float>())
        }
    }

    fun clear(scope: CoroutineScope) {
        job?.cancel()
        job = null
        scope.launch { progress.snapTo(1f) }
    }
}

private fun detentIndex(fraction: Float): Int =
    (fraction.coerceIn(0f, 1f) * DetentCount).toInt().coerceAtMost(DetentCount - 1)

private const val DetentCount = 36

/** The echo is noise below this much of the timeline; above it, it is information. */
private const val GhostThreshold = 0.004f

private val TwoPi = (2.0 * PI).toFloat()

/**
 * One swing every 22dp with 3dp of throw. Wide enough to read as motion at arm's
 * length, shallow enough that the fill still reports which pixel the head is on.
 */
private val WaveLength = 22.dp
private val WaveAmplitude = 3.dp

/**
 * A crest crosses one wavelength in ~1.4s — slower than the eye follows as scrolling,
 * fast enough to read as alive. Not the media rate: the wave says *playing*, never
 * *how fast*, because there is no speed to report.
 */
private const val WaveCyclesPerSecond = 0.7f
private val WavePeriodNanos: Long = (1_000_000_000.0 / WaveCyclesPerSecond).toLong()

/** A stale TV clock swings wider than a healthy one — the bar itself is the report. */
private const val SyncingAmplitude = 1.4f

private val HitHeight = 48.dp
private val TrackIdle = 13.dp
private val TrackDrag = 22.dp
private val ThumbIdleW = 6.dp
private val ThumbIdleH = 28.dp
private val ThumbDragW = 10.dp
private val ThumbDragH = 40.dp
private val GhostWidth = 3.5.dp
private val GhostHeight = 28.dp
private val GhostGlow = 14.dp
private val RippleBase = 18.dp
private val PreviewWidth = 116.dp
private val PreviewHeight = 65.dp
private val PreviewLift = (-96).dp
private val PreviewClearance: Dp = 104.dp

/** Keeps release, pointer cancellation, and composition disposal to one terminal callback. */
private class ScrubEndGate {
    private var active = false

    fun start() {
        active = true
    }

    fun finish(onScrubEnd: () -> Unit) {
        if (active) {
            active = false
            onScrubEnd()
        }
    }
}
