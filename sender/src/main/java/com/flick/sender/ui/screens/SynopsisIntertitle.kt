package com.flick.sender.ui.screens

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flick.sender.R
import com.flick.sender.media.FilmDetails
import com.flick.sender.ui.IntertitleArt
import com.flick.sender.ui.IntertitleInks
import com.flick.sender.ui.components.SynopsisPolicy
import com.flick.sender.ui.components.SynopsisReveal
import com.flick.sender.ui.components.posterFlightAlpha
import com.flick.sender.ui.components.posterFlightScale
import com.flick.sender.ui.components.posterFlightShiftPx
import com.flick.sender.ui.motion.animatingFrameRate
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.PosterHairline
import com.flick.sender.ui.theme.rememberIsResumed
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The film's synopsis as a silent-film intertitle, in the poster's own colours. As the poster
 * is pushed left the page steps back into the poster's darkest tone, the poster shrinks onto
 * a film strip that runs down the leading edge as its first frame, and a title card framed in
 * the poster's brightest colour, glowing with the poster blurred behind its words, fades up
 * beside it line by line.
 *
 * Every frame of travel, the strip's run and the card's flicker are layer properties or draws
 * read from [reveal] and a frame clock here: nothing recomposes while it moves. Under reduce
 * motion it all crossfades in place, and the strip stands still.
 *
 * It is composed from the first pull on (the page holds it back until then) and, while away,
 * draws nothing and takes no touch or focus. [title] is the film's own name with no year: the
 * card's date line carries the year, from TMDB.
 */
@Composable
internal fun SynopsisIntertitle(
    reveal: SynopsisReveal,
    inks: IntertitleInks,
    details: FilmDetails,
    title: String,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val engaged = reveal.engaged
    val catching = reveal.catching
    val resumed = rememberIsResumed()
    val reel = remember { ReelClock() }
    // Stops settled closed, in the background and under reduce motion; runs while open.
    val running = engaged && resumed && !reduceMotion
    LaunchedEffect(running, density) {
        if (running) reel.run(reveal, density) else reel.rest()
    }
    val art = inks.art
    DisposableEffect(reveal, art) {
        reveal.posterCopyLive = art != null
        onDispose { reveal.posterCopyLive = false }
    }
    val dragState = rememberDraggableState { delta -> reveal.dragBy(-delta) }
    // A push right anywhere on the story takes it home, finger-driven like the pull that
    // brought it; the card's own scroll keeps the vertical drags.
    val closeDrag = Modifier.draggable(
        state = dragState,
        orientation = Orientation.Horizontal,
        enabled = catching,
        onDragStarted = { reveal.beginDrag() },
        onDragStopped = { velocity -> reveal.release(-velocity, density.density, opening = false) },
    )
    val pane = stringResource(R.string.a11y_synopsis_pane)
    Box(
        modifier
            .fillMaxSize()
            .animatingFrameRate(running || reveal.moving)
            .then(if (catching) closeDrag else Modifier)
            .then(
                when {
                    reveal.open -> Modifier.semantics { paneTitle = pane }
                    !engaged -> Modifier.clearAndSetSemantics { }
                    else -> Modifier
                },
            ),
    ) {
        // A tap anywhere the card is not closes it.
        Box(
            Modifier
                .fillMaxSize()
                .then(if (catching) Modifier.pointerInput(reveal) { detectTapGestures { reveal.hide() } } else Modifier),
        )
        FilmStrip(reveal, inks, reel, reduceMotion)
        Box(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(
                    start = SynopsisPolicy.CARD_START_DP.dp,
                    end = SynopsisPolicy.CARD_END_DP.dp,
                    top = CardTop,
                    bottom = CardBottom,
                ),
            contentAlignment = Alignment.Center,
        ) {
            IntertitleCard(
                details = details,
                title = title,
                inks = inks,
                interactive = catching,
                lineIn = { index -> if (reduceMotion) Modifier else Modifier.lineIn(reveal, index) },
                brightness = { reel.brightness },
                modifier = Modifier
                    .widthIn(max = SynopsisPolicy.CARD_MAX_WIDTH_DP.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        val p = reveal.progress.value.coerceIn(0f, 1f)
                        alpha = if (reduceMotion) p else SynopsisPolicy.cardAlpha(p)
                        translationX = reel.jitterX
                        translationY = reel.jitterY
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
            )
        }
        // Over the strip and the card, as the poster is until it has become a frame.
        if (art != null) PosterFlight(reveal, art, reduceMotion)
        CinemaDiscButton(
            icon = FlickIcons.Close,
            description = stringResource(R.string.a11y_synopsis_close),
            onClick = { reveal.hide() },
            enabled = catching,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 12.dp, end = 12.dp)
                .graphicsLayer {
                    val p = reveal.progress.value.coerceIn(0f, 1f)
                    alpha = if (reduceMotion) p else SynopsisPolicy.closeAlpha(p)
                },
        )
    }
}

/**
 * The title card. [lineIn] is each line's arrival; [interactive] false is a card on its way
 * home, which must take no touch; [brightness] is the projector's flicker, read in the draw.
 * Separate from the reveal so it can be drawn and checked with any data.
 */
@Composable
internal fun IntertitleCard(
    details: FilmDetails,
    title: String,
    inks: IntertitleInks,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    lineIn: (Int) -> Modifier = { Modifier },
    brightness: () -> Float = { 1f },
) {
    val scroll = rememberScrollState()
    val fleuron = rememberVectorPainter(FlickIcons.Fleuron)
    val overview = details.overview.orEmpty()
    Box(modifier.drawBehind { drawCardLight(inks) }) {
        Box(Modifier.matchParentSize().drawWithCache { cardFace(inks) })
        // Inside the double rule, so copy carried up by the scroll passes under nothing. Only
        // while it takes touches: even a disabled scroll stays in the hit path, and the card
        // sits over the poster, which must have every touch back the moment the story goes.
        Column(
            Modifier
                .fillMaxWidth()
                .padding(RuleBand)
                .then(if (interactive) Modifier.verticalScroll(scroll) else Modifier)
                .padding(horizontal = CardPadX, vertical = CardPadY),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(LineGap),
        ) {
            var line = 0
            details.year?.let { year ->
                val spoken = year.toString()
                BasicText(
                    text = stringResource(R.string.synopsis_year, year),
                    style = FlickText.intertitleYear,
                    color = { inks.accent() },
                    modifier = lineIn(line++).semantics { contentDescription = spoken },
                )
            }
            Text(
                text = title,
                style = FlickText.intertitleTitle.copy(
                    color = Color.White,
                    fontSize = SynopsisPolicy.titleSp(title).sp,
                ),
                modifier = lineIn(line++).semantics { heading() },
            )
            Box(
                lineIn(line++)
                    .size(OrnamentSize)
                    .drawBehind {
                        with(fleuron) { draw(size, colorFilter = ColorFilter.tint(inks.accent())) }
                    },
            )
            Text(
                text = overview,
                style = FlickText.intertitleBody.copy(
                    color = OverviewInk,
                    fontSize = SynopsisPolicy.overviewSp(overview).sp,
                ),
                modifier = lineIn(line++),
            )
            details.tagline?.let { tagline ->
                BasicText(
                    text = tagline,
                    style = FlickText.intertitleTagline,
                    color = { inks.accent() },
                    modifier = lineIn(line),
                )
            }
        }
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    val b = brightness()
                    if (b < 1f) drawRect(Color.Black, alpha = 1f - b)
                },
        )
    }
}

/**
 * Outside the card: its light on the ground (a 40 dp falloff in the poster's vivid colour,
 * shaped like a blurred box-shadow) and the fine outline 5 dp out from the double rule.
 */
private fun DrawScope.drawCardLight(inks: IntertitleInks) {
    val glow = inks.glow()
    val reach = GlowReach.toPx()
    val stops = GlowFalloff.mapIndexed { i, f ->
        i / (GlowFalloff.size - 1f) to glow.copy(alpha = glow.alpha * f)
    }.toTypedArray()
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(*stops, startY = 0f, endY = -reach), Offset(0f, -reach), Size(w, reach))
    drawRect(Brush.verticalGradient(*stops, startY = h, endY = h + reach), Offset(0f, h), Size(w, reach))
    drawRect(Brush.horizontalGradient(*stops, startX = 0f, endX = -reach), Offset(-reach, 0f), Size(reach, h))
    drawRect(Brush.horizontalGradient(*stops, startX = w, endX = w + reach), Offset(w, 0f), Size(reach, h))
    for ((cx, cy) in listOf(0f to 0f, w to 0f, 0f to h, w to h)) {
        val corner = Offset(cx, cy)
        drawRect(
            Brush.radialGradient(*stops, center = corner, radius = reach),
            topLeft = Offset(if (cx == 0f) -reach else w, if (cy == 0f) -reach else h),
            size = Size(reach, reach),
        )
    }
    val stroke = 1.dp.toPx()
    val out = OutlineOffset.toPx() + stroke / 2f
    drawRect(
        inks.ruleSoft(),
        topLeft = Offset(-out, -out),
        size = Size(w + 2 * out, h + 2 * out),
        style = Stroke(stroke),
    )
}

/**
 * The card itself: the poster blurred and saturated behind the words (one small bitmap scaled
 * up, cropped to the card from a box 30 dp larger so no edge goes thin), a darkening veil over
 * it, and the double rule.
 */
private fun CacheDrawScope.cardFace(inks: IntertitleInks) =
    run {
        val bleed = GlassBleed.toPx()
        val boxW = size.width + 2 * bleed
        val boxH = size.height + 2 * bleed
        val veil = Brush.verticalGradient(listOf(Color.Black.copy(alpha = VeilTop), Color.Black.copy(alpha = VeilFoot)))
        val stroke = 1.dp.toPx()
        onDrawBehind {
            val art = inks.art
            val a = inks.artAlpha()
            if (art != null && a > 0f) {
                val gw = art.glass.width.toFloat()
                val gh = art.glass.height.toFloat()
                val scale = max(boxW / gw, boxH / gh)
                val dx = -bleed + (boxW - gw * scale) / 2f
                val dy = -bleed + (boxH - gh * scale) / 2f
                clipRect {
                    withTransform({
                        translate(dx, dy)
                        scale(scale, scale, pivot = Offset.Zero)
                    }) {
                        drawImage(art.glass, alpha = GlassAlpha * a, filterQuality = FilterQuality.Low)
                    }
                }
            }
            drawRect(veil)
            val rule = inks.rule()
            drawRect(rule, Offset(stroke / 2f, stroke / 2f), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
            val inner = stroke * 2.5f
            drawRect(rule, Offset(inner, inner), Size(size.width - 2 * inner, size.height - 2 * inner), style = Stroke(stroke))
        }
    }

/**
 * The film strip down the leading edge, past the window's top and foot: the film's body, then
 * a reel inside it — sprocket holes down both edges and the poster as frames — run upward by
 * one layer translation a frame, so the strip is recorded once and only moved.
 */
@Composable
private fun FilmStrip(reveal: SynopsisReveal, inks: IntertitleInks, reel: ReelClock, reduceMotion: Boolean) {
    Box(
        Modifier
            .layout { measurable, constraints ->
                val width = SynopsisPolicy.STRIP_WIDTH_DP.dp.roundToPx()
                val bleed = SynopsisPolicy.STRIP_BLEED_DP.dp.roundToPx()
                val placeable = measurable.measure(Constraints.fixed(width, constraints.maxHeight + 2 * bleed))
                layout(width, constraints.maxHeight) {
                    placeable.place(SynopsisPolicy.STRIP_LEFT_DP.dp.roundToPx(), -bleed)
                }
            }
            .graphicsLayer {
                val p = reveal.progress.value.coerceIn(0f, 1f)
                alpha = if (reduceMotion) p else SynopsisPolicy.stripAlpha(p)
                translationX = if (reduceMotion) 0f else SynopsisPolicy.stripShiftDp(p).dp.toPx()
            }
            .drawWithCache {
                val shade = StripShade.toPx()
                val left = Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = StripShadeAlpha)), startX = -shade, endX = 0f)
                val right = Brush.horizontalGradient(listOf(Color.Black.copy(alpha = StripShadeAlpha), Color.Transparent), startX = size.width, endX = size.width + shade)
                onDrawBehind {
                    drawRect(left, Offset(-shade, 0f), Size(shade, size.height))
                    drawRect(right, Offset(size.width, 0f), Size(shade, size.height))
                    drawRect(inks.film())
                }
            },
    ) {
        Box(
            Modifier
                .matchParentSize()
                .clipToBounds()
                .graphicsLayer { translationY = -reel.scrollPx }
                .drawWithCache {
                    val pitch = SynopsisPolicy.FRAME_PITCH_DP.dp.toPx()
                    // One pitch more than the strip, so the reel never shows its end as it runs.
                    val length = size.height + pitch
                    val holes = sprocketHoles(this, length, pitch)
                    val frameX = SynopsisPolicy.FRAME_INSET_DP.dp.toPx()
                    val frameW = SynopsisPolicy.FRAME_WIDTH_DP.dp.toPx()
                    val frameH = SynopsisPolicy.FRAME_HEIGHT_DP.dp.toPx()
                    val frames = (length / pitch).toInt() + 1
                    val rim = 1.dp.toPx()
                    onDrawBehind {
                        drawPath(holes, inks.ground())
                        // The punched edge catching the light: on a near-black poster the film
                        // and the ground are a few levels apart, and the holes would vanish.
                        drawPath(holes, SprocketRim, style = Stroke(rim))
                        val art = inks.art
                        val a = inks.artAlpha()
                        for (k in 0 until frames) {
                            val y = k * pitch
                            if (a < 1f) drawRect(FrameEmpty, Offset(frameX, y), Size(frameW, frameH), alpha = 1f - a)
                            if (art != null && a > 0f) {
                                drawImage(
                                    art.frame,
                                    dstOffset = IntOffset(frameX.roundToInt(), y.roundToInt()),
                                    dstSize = IntSize(frameW.roundToInt(), frameH.roundToInt()),
                                    alpha = a,
                                )
                            }
                        }
                    }
                },
        )
    }
}

private fun sprocketHoles(density: Density, length: Float, pitch: Float): Path = with(density) {
    val step = pitch / SynopsisPolicy.SPROCKETS_PER_FRAME
    val w = SynopsisPolicy.SPROCKET_WIDTH_DP.dp.toPx()
    val h = SynopsisPolicy.SPROCKET_HEIGHT_DP.dp.toPx()
    val edge = SynopsisPolicy.SPROCKET_EDGE_DP.dp.toPx()
    val r = CornerRadius(SynopsisPolicy.SPROCKET_CORNER_DP.dp.toPx())
    val stripW = SynopsisPolicy.STRIP_WIDTH_DP.dp.toPx()
    Path().apply {
        var y = (step - h) / 2f
        while (y < length) {
            addRoundRect(RoundRect(edge, y, edge + w, y + h, r))
            addRoundRect(RoundRect(stripW - edge - w, y, stripW - edge, y + h, r))
            y += step
        }
    }
}

/**
 * The poster as it is drawn over the strip and the card: the page's own decode, in the seat it
 * rests in, flown onto the strip's centre line and shrunk to a frame by layer properties only.
 */
@Composable
private fun PosterFlight(reveal: SynopsisReveal, art: IntertitleArt, reduceMotion: Boolean) {
    Box(
        Modifier
            .layout { measurable, constraints ->
                val seat = reveal.posterSeat.translate(-reveal.pageOrigin)
                val placeable = measurable.measure(
                    Constraints.fixed(seat.width.roundToInt().coerceAtLeast(0), seat.height.roundToInt().coerceAtLeast(0)),
                )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(seat.left.roundToInt(), seat.top.roundToInt())
                }
            }
            .graphicsLayer {
                val p = reveal.progress.value
                alpha = if (p <= 0f) 0f else posterFlightAlpha(p, reduceMotion)
                translationX = reveal.posterFlightShiftPx(p, this, reduceMotion)
                val scale = reveal.posterFlightScale(p, this, reduceMotion)
                scaleX = scale
                scaleY = scale
                shape = PosterShape
                clip = true
            }
            .drawBehind {
                drawImage(
                    art.poster,
                    srcOffset = art.posterSrcOffset,
                    srcSize = art.posterSrcSize,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
                val stroke = 1.dp.toPx()
                val radius = size.width * PosterCornerShare - stroke / 2f
                drawRoundRect(
                    PosterHairline,
                    Offset(stroke / 2f, stroke / 2f),
                    Size(size.width - stroke, size.height - stroke),
                    CornerRadius(radius, radius),
                    style = Stroke(stroke),
                )
            },
    )
}

/**
 * Line [index] arriving with the card: fading in and rising [SynopsisPolicy.LINES_RISE_DP],
 * driven by the travel itself, so the stagger follows the finger, the settle and back alike.
 */
private fun Modifier.lineIn(reveal: SynopsisReveal, index: Int): Modifier = graphicsLayer {
    val t = SynopsisPolicy.lineIn(reveal.progress.value, index)
    alpha = t
    translationY = (1f - t) * SynopsisPolicy.LINES_RISE_DP.dp.toPx()
    compositingStrategy = CompositingStrategy.ModulateAlpha
}

/**
 * The projector: the reel's run and the card's flicker, written once a frame and read only in
 * layers and draws.
 */
@Stable
private class ReelClock {
    var scrollPx by mutableFloatStateOf(0f)
        private set
    var brightness by mutableFloatStateOf(1f)
        private set
    var jitterX by mutableFloatStateOf(0f)
        private set
    var jitterY by mutableFloatStateOf(0f)
        private set

    suspend fun run(reveal: SynopsisReveal, density: Density) {
        val pitch = SynopsisPolicy.FRAME_PITCH_DP * density.density
        val speed = SynopsisPolicy.REEL_DP_PER_S * density.density
        val jitter = SynopsisPolicy.FLICKER_JITTER_DP * density.density
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                // A long gap (a dropped run of frames) is not a jump in the reel.
                val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, MaxStepS)
                last = now
                val p = reveal.progress.value.coerceIn(0f, 1f)
                scrollPx = (scrollPx + dt * p * speed) % pitch
                if (p > SynopsisPolicy.FLICKER_FROM) {
                    brightness = SynopsisPolicy.FLICKER_MIN_BRIGHTNESS +
                        Random.nextFloat() * (1f - SynopsisPolicy.FLICKER_MIN_BRIGHTNESS)
                    jitterX = (Random.nextFloat() * 2f - 1f) * jitter
                    jitterY = (Random.nextFloat() * 2f - 1f) * jitter
                } else {
                    rest()
                }
            }
        }
    }

    fun rest() {
        brightness = 1f
        jitterX = 0f
        jitterY = 0f
    }
}

private const val MaxStepS = 0.05f

private val CardTop = 64.dp
private val CardBottom = 40.dp
/** The double rule: two 1 dp lines 1 dp apart. */
private val RuleBand = 3.dp
private val CardPadX = 14.dp
private val CardPadY = 20.dp
private val LineGap = 11.dp
private val OrnamentSize = 18.dp
private val OverviewInk = Color(0xFFF5F5F5)
private val GlassBleed = 30.dp
private const val GlassAlpha = 0.75f
private const val VeilTop = 0.45f
private const val VeilFoot = 0.62f
private val OutlineOffset = 5.dp
private val GlowReach = 40.dp

/** A box-shadow's blur of 40 (σ 20) outside the edge, sampled at 0, 10, 20, 30 and 40 dp. */
private val GlowFalloff = listOf(0.5f, 0.31f, 0.16f, 0.067f, 0f)
private val StripShade = 28.dp
private const val StripShadeAlpha = 0.5f
private val FrameEmpty = Color(0x14FFFFFF)
private val SprocketRim = Color(0x1AFFFFFF)

/** [com.flick.sender.ui.theme.posterCardShape]'s corner, as a share that scales with the flight. */
private const val PosterCornerShare = 0.08f
private val PosterShape = RoundedCornerShape(percent = 8)
