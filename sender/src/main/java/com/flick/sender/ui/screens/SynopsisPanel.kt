package com.flick.sender.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import coil.ImageLoader
import com.flick.sender.R
import com.flick.sender.media.FilmDetails
import com.flick.sender.ui.FilmTitleLogo
import com.flick.sender.ui.components.SynopsisPolicy
import com.flick.sender.ui.components.SynopsisReveal
import com.flick.sender.ui.motion.animatingFrameRate
import com.flick.sender.ui.theme.CinemaDeep
import com.flick.sender.ui.theme.CinemaGlassEdge
import com.flick.sender.ui.theme.CinemaInk
import com.flick.sender.ui.theme.CinemaInkDim
import com.flick.sender.ui.theme.CinemaInkFaint
import com.flick.sender.ui.theme.CinemaLeafFoot
import com.flick.sender.ui.theme.CinemaLeafTop
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors

/**
 * The film's synopsis as a leaf of the cinema page, pulled in from the left over it: the
 * heading again, the facts, the tagline as an epigraph, the overview to read, then who made it
 * and who is in it.
 *
 * Opaque and travelling, never a fading page: the page behind is dimmed by one rect and drifts
 * aside in its own layers, and every frame of travel is a layer property read from [reveal].
 * Under reduce motion it crossfades in place instead.
 *
 * It is composed from the first pull on (the page holds it back until then) and, while away,
 * draws nothing and takes no touch or focus. [title] is the film's own name with no year: the
 * facts line under it carries the year, from one source.
 */
@Composable
internal fun SynopsisPanel(
    reveal: SynopsisReveal,
    details: FilmDetails,
    title: String,
    logoPath: String?,
    imageLoader: ImageLoader,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val engaged = reveal.engaged
    val catching = reveal.catching
    val dragState = rememberDraggableState { delta -> reveal.dragBy(delta) }
    // A push left anywhere on the panel or on what shows of the page beside it takes it home,
    // finger-driven like the pull that brought it.
    val closeDrag = Modifier.draggable(
        state = dragState,
        orientation = Orientation.Horizontal,
        enabled = catching,
        onDragStarted = { reveal.beginDrag() },
        onDragStopped = { velocity -> reveal.release(velocity, density.density, opening = false) },
    )
    BoxWithConstraints(modifier.fillMaxSize()) {
        val panelWidth = SynopsisPolicy.panelWidthDp(maxWidth.value).dp
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val p = reveal.progress.value.coerceIn(0f, 1f)
                    if (p > 0f) drawRect(CinemaDeep, alpha = SynopsisPolicy.SCRIM_ALPHA * p)
                }
                .then(
                    if (catching) {
                        Modifier
                            .pointerInput(reveal) { detectTapGestures { reveal.hide() } }
                            .then(closeDrag)
                    } else {
                        Modifier
                    },
                ),
        )
        val pane = stringResource(R.string.a11y_synopsis_pane)
        Box(
            Modifier
                .fillMaxHeight()
                .width(panelWidth)
                .animatingFrameRate(reveal.moving)
                .graphicsLayer {
                    val p = reveal.progress.value
                    if (reduceMotion) {
                        // Off the window while away: a layer at alpha 0 is still hit, and in
                        // place it would take the poster's own pull before it began.
                        translationX = if (p <= 0f) -size.width else 0f
                        alpha = p.coerceIn(0f, 1f)
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    } else {
                        translationX = -(1f - p) * size.width
                        // Away is no draw at all, not a draw off the left edge.
                        alpha = if (p <= 0f) 0f else 1f
                    }
                }
                .then(if (catching) closeDrag else Modifier)
                .drawWithCache {
                    // Past the leading edge too: the open spring overshoots, and what it pulls
                    // into view there must still be the panel.
                    val bleed = PanelBleed.toPx()
                    val ground = Brush.verticalGradient(listOf(CinemaLeafTop, CinemaLeafFoot))
                    val shade = PanelShade.toPx()
                    val shadow = Brush.horizontalGradient(
                        listOf(Color.Black.copy(alpha = PanelShadeAlpha), Color.Transparent),
                        startX = size.width,
                        endX = size.width + shade,
                    )
                    onDrawBehind {
                        drawRect(shadow, topLeft = Offset(size.width, 0f), size = Size(shade, size.height))
                        drawRect(ground, topLeft = Offset(-bleed, 0f), size = Size(size.width + bleed, size.height))
                        drawLine(
                            CinemaGlassEdge,
                            Offset(size.width, 0f),
                            Offset(size.width, size.height),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                }
                .then(
                    if (reveal.open) {
                        Modifier.semantics { paneTitle = pane }
                    } else if (!engaged) {
                        Modifier.clearAndSetSemantics { }
                    } else {
                        Modifier
                    },
                ),
        ) {
            SynopsisLeaf(
                details = details,
                title = title,
                logoPath = logoPath,
                imageLoader = imageLoader,
                onClose = { reveal.hide() },
                interactive = catching,
                lineIn = { index -> Modifier.lineIn(reveal, index, reduceMotion) },
            )
        }
    }
}

/**
 * What the panel says, top to bottom. [lineIn] is each line's arrival as the panel comes in;
 * [interactive] false is a panel on its way home, which must take no touch. Separate from the
 * panel so it can be drawn and checked with any data.
 */
@Composable
internal fun SynopsisLeaf(
    details: FilmDetails,
    title: String,
    logoPath: String?,
    imageLoader: ImageLoader,
    onClose: () -> Unit,
    interactive: Boolean = true,
    lineIn: (Int) -> Modifier = { Modifier },
) {
    val scroll = rememberScrollState()
    // The insets and the close disc stay outside the scroll: copy carried up passes under a
    // short fade below the status bar rather than under the bar itself, and the way out never
    // scrolls away.
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = LeafStart, end = LeafEnd, top = 12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            CinemaDiscButton(
                icon = FlickIcons.Close,
                description = stringResource(R.string.a11y_synopsis_close),
                onClick = onClose,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxSize()
                    // Off while the panel springs home: a disabled scroll leaves the hit path,
                    // so a throw made as it goes reaches the poster under it.
                    .verticalScroll(scroll, enabled = interactive)
                    .navigationBarsPadding()
                    .padding(start = LeafStart, end = LeafEnd, top = 6.dp, bottom = 48.dp),
            ) {
                SynopsisCopy(details, title, logoPath, imageLoader, lineIn)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(TopFade)
                    .drawBehind {
                        // Only once something has scrolled up into it.
                        if (scroll.value > 0) {
                            drawRect(Brush.verticalGradient(listOf(CinemaLeafTop, CinemaLeafTop.copy(alpha = 0f))))
                        }
                    },
            )
        }
    }
}

@Composable
private fun SynopsisCopy(
    details: FilmDetails,
    title: String,
    logoPath: String?,
    imageLoader: ImageLoader,
    lineIn: (Int) -> Modifier,
) {
    val colors = LocalFlickColors.current
    Column {
        SynopsisHeading(title, logoPath, imageLoader, lineIn(0))
        SynopsisFacts(details, lineIn(1))
        details.tagline?.let { tagline ->
            Spacer(Modifier.height(26.dp))
            Text(
                text = tagline,
                style = FlickText.readingEpigraph.copy(color = CinemaInkDim.copy(alpha = EpigraphInkAlpha)),
                modifier = lineIn(2).widthIn(max = ReadingMeasure),
            )
        }
        details.overview?.let { overview ->
            Spacer(Modifier.height(26.dp))
            Column(lineIn(3)) {
                Box(
                    Modifier
                        .size(width = 28.dp, height = 2.dp)
                        .background(colors.spark, RoundedCornerShape(1.dp)),
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    text = overview,
                    style = FlickText.readingBody.copy(color = CinemaInk),
                    modifier = Modifier.widthIn(max = ReadingMeasure),
                )
            }
        }
        if (details.creators.isNotEmpty() || details.cast.isNotEmpty()) {
            Spacer(Modifier.height(34.dp))
            Column(lineIn(4), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (details.creators.isNotEmpty()) {
                    Credit(
                        label = stringResource(if (details.show) R.string.synopsis_created_by else R.string.synopsis_directed_by),
                        names = details.creators,
                    )
                }
                if (details.cast.isNotEmpty()) {
                    Credit(label = stringResource(R.string.synopsis_starring), names = details.cast)
                }
            }
        }
    }
}

/** TMDB's lettering when it has it, else the film's name, set large and to the left. */
@Composable
private fun SynopsisHeading(title: String, logoPath: String?, imageLoader: ImageLoader, modifier: Modifier) {
    var logoFailed by remember(logoPath) { mutableStateOf(false) }
    if (logoPath != null && !logoFailed) {
        FilmTitleLogo(
            logoPath = logoPath,
            imageLoader = imageLoader,
            contentDescription = title,
            onFailed = { logoFailed = true },
            alignment = Alignment.CenterStart,
            modifier = modifier
                .size(width = LogoWidth, height = LogoHeight)
                .semantics { heading() },
        )
    } else {
        Text(
            text = title,
            style = FlickText.displayMedium.copy(color = Color.White),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier.semantics { heading() },
        )
    }
}

@Composable
private fun Credit(label: String, names: List<String>) {
    Column {
        Eyebrow(label)
        Spacer(Modifier.height(6.dp))
        Text(
            text = names.joinToString(", "),
            style = FlickText.readingCredit.copy(color = CinemaInk.copy(alpha = CreditInkAlpha)),
            modifier = Modifier.widthIn(max = ReadingMeasure),
        )
    }
}

@Composable
private fun Eyebrow(text: String) {
    Text(text = text.uppercase(), style = FlickText.monoEyebrow.copy(color = CinemaInkFaint))
}

/**
 * The film's facts in two registers: `1999 · 2h 16m` on one line that never wraps, then its
 * genres on their own, spaced rather than dotted so a wrap can never leave a separator hanging
 * at a line's end. The year is TMDB's: the filename's may name a festival year or a re-release.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SynopsisFacts(details: FilmDetails, modifier: Modifier) {
    val runtime = details.runtimeMinutes?.let { minutes ->
        if (minutes >= 60) {
            stringResource(R.string.synopsis_runtime, minutes / 60, minutes % 60)
        } else {
            stringResource(R.string.synopsis_runtime_short, minutes)
        }
    }
    val when_ = listOfNotNull(details.year?.toString(), runtime).joinToString(FactsSeparator)
    if (when_.isEmpty() && details.genres.isEmpty()) return
    Column(modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (when_.isNotEmpty()) {
            Text(text = when_, style = FactsStyle, maxLines = 1, softWrap = false)
        }
        if (details.genres.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), maxLines = 2) {
                details.genres.forEach { genre ->
                    Text(text = genre.uppercase(), style = FactsStyle, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Line [index] arriving with the panel: fading in and closing a small lag behind the panel's
 * own travel, so the copy settles a beat after the leaf does. Read in the layer.
 */
private fun Modifier.lineIn(reveal: SynopsisReveal, index: Int, reduceMotion: Boolean): Modifier =
    if (reduceMotion) {
        this
    } else {
        graphicsLayer {
            val linear = SynopsisPolicy.lineIn(reveal.progress.value, index)
            val e = 1f - (1f - linear) * (1f - linear) * (1f - linear)
            alpha = e
            translationX = -(1f - e) * SynopsisPolicy.STAGGER_LAG_DP.dp.toPx()
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }
    }

private val LeafStart = 28.dp
private val LeafEnd = 22.dp
/** About 65 characters of the reading face: past that a line is hard to come back from. */
private val ReadingMeasure: Dp = 460.dp
// The page's own logo seat, so the panel's copy is the decode the page already holds.
private val LogoWidth = 200.dp
private val LogoHeight = 64.dp
private val PanelBleed = 72.dp
private val PanelShade = 28.dp
private const val PanelShadeAlpha = 0.45f
private const val CreditInkAlpha = 0.86f
private const val EpigraphInkAlpha = 0.88f
private const val FactsSeparator = " · "
private val TopFade = 20.dp
/** A long eyebrow at the default 0.11 em reads as letters, not words; this reads as a line. */
private val FactsStyle = FlickText.monoEyebrow.copy(color = CinemaInkFaint, letterSpacing = 0.06.em)
