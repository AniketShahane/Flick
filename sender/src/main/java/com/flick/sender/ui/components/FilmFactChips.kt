package com.flick.sender.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import com.flick.sender.R
import com.flick.sender.media.FilmRatings
import com.flick.sender.media.FilmRatingsPolicy
import com.flick.sender.ui.theme.CinemaGlass
import com.flick.sender.ui.theme.CinemaGlassEdge
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.PillShape
import com.flick.sender.ui.theme.RatingSplatGreen
import com.flick.sender.ui.theme.RatingTomatoLeaf
import com.flick.sender.ui.theme.RatingTomatoRed
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch

/**
 * One fact as a glass pill on the dark detail page. [known] false is a fact the phone could not
 * read: no fill and the dim ink, so the seat stays without making a claim. [mark] sits before
 * the text in a [MarkSize] square, so a chip with a mark is exactly as tall as one with an icon.
 */
@Composable
internal fun FactChip(
    text: String,
    known: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = FlickText.monoChip,
    mark: (@Composable () -> Unit)? = null,
) {
    val colors = LocalFlickColors.current
    Row(
        modifier
            .clip(PillShape)
            .background(if (known) CinemaGlass else Color.Transparent)
            .border(1.dp, if (known) CinemaGlassEdge else colors.outline, PillShape)
            .padding(start = if (mark != null) 10.dp else 12.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mark != null) Box(Modifier.height(MarkSize), contentAlignment = Alignment.Center) { mark() }
        Text(
            text = text,
            style = style.copy(color = if (known) Color.White else colors.onSurfaceDim),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The detail page's second row: the running time from the first frame, the scores joining it
 * when they are known. [scoresArrive] is scores that were not known when the page opened, which
 * fade and scale in; [animate] false (reduce motion) has every chip simply take its place.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RuntimeAndScores(
    runtime: String,
    runtimeKnown: Boolean,
    ratings: FilmRatings?,
    animate: Boolean,
    scoresArrive: Boolean,
    modifier: Modifier = Modifier,
) {
    val arrive = animate && scoresArrive
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RuntimeChip(text = runtime, known = runtimeKnown, modifier = Modifier.glidesInRow(animate))
        ratings?.imdb?.let { ImdbChip(it, Modifier.glidesInRow(animate).arriving(arrive)) }
        ratings?.rottenTomatoes?.let { TomatoChip(it, Modifier.glidesInRow(animate).arriving(arrive)) }
    }
}

/** The file's own running time, with the clock; the em dash when nothing measured it. */
@Composable
internal fun RuntimeChip(text: String, known: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalFlickColors.current
    val spoken = stringResource(R.string.a11y_detail_runtime, text)
    FactChip(
        text = text,
        known = known,
        modifier = modifier.clearAndSetSemantics { contentDescription = spoken },
        style = FlickText.chipFigure,
        mark = {
            Icon(
                imageVector = FlickIcons.Clock,
                contentDescription = null,
                tint = if (known) Color.White.copy(alpha = MarkInkAlpha) else colors.onSurfaceDim,
                modifier = Modifier.size(MarkSize),
            )
        },
    )
}

/**
 * IMDb's score out of ten, as the app's own glass chip: a neutral star and the source named in
 * words. Nothing here borrows IMDb's own badge, colour or lettering.
 */
@Composable
internal fun ImdbChip(score: String, modifier: Modifier = Modifier) {
    val spoken = stringResource(R.string.a11y_rating_imdb, score)
    FactChip(
        text = stringResource(R.string.detail_rating_imdb, score),
        known = true,
        modifier = modifier.clearAndSetSemantics { contentDescription = spoken },
        style = FlickText.chipFigure,
        mark = {
            Icon(
                imageVector = RatingMarks.Star,
                contentDescription = null,
                tint = Color.White.copy(alpha = MarkInkAlpha),
                modifier = Modifier.size(MarkSize),
            )
        },
    )
}

/** The Tomatometer, after a tomato when fresh and a splat when rotten. */
@Composable
internal fun TomatoChip(score: Int, modifier: Modifier = Modifier) {
    val rotten = FilmRatingsPolicy.rotten(score)
    val spoken = stringResource(
        if (rotten) R.string.a11y_rating_tomatoes_rotten else R.string.a11y_rating_tomatoes_fresh,
        score,
    )
    FactChip(
        text = stringResource(R.string.detail_rating_percent, score),
        known = true,
        modifier = modifier.clearAndSetSemantics { contentDescription = spoken },
        style = FlickText.chipFigure,
        mark = {
            Image(
                painter = rememberVectorPainter(if (rotten) RatingMarks.Splat else RatingMarks.Tomato),
                contentDescription = null,
                modifier = Modifier.size(MarkSize),
            )
        },
    )
}

internal val MarkSize = 14.dp
private const val MarkInkAlpha = 0.82f

/**
 * Arrives once, scaling up from a little under its size while it fades in, on the effects
 * spring for the fade and the spatial one for the scale. [animate] false is a chip that was
 * known when the page opened: it is simply there.
 */
@Composable
internal fun Modifier.arriving(animate: Boolean): Modifier {
    if (!animate) return this
    val presence = remember { Animatable(0f) }
    val scale = remember { Animatable(ArrivalFromScale) }
    val effects = MaterialTheme.motionScheme.slowEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(Unit) {
        launch { presence.animateTo(1f, effects) }
        scale.animateTo(1f, spatial)
    }
    return graphicsLayer {
        alpha = presence.value
        scaleX = scale.value
        scaleY = scale.value
    }
}

private const val ArrivalFromScale = 0.82f

/**
 * Glides to a new place in its row instead of jumping there, when a neighbour arrives and the
 * centred row re-centres. The offset is a layer translation, so the glide relayouts nothing.
 */
internal fun Modifier.glidesInRow(enabled: Boolean): Modifier = if (!enabled) this else composed {
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf<IntOffset?>(null) }
    val glide = remember { Animatable(IntOffset.Zero, IntOffset.VectorConverter) }
    this
        .onPlaced { coordinates ->
            val now = coordinates.positionInParent().round()
            val previous = target
            target = now
            if (previous != null && previous != now) {
                scope.launch {
                    // From where it is drawn now, which is the old place plus any glide left.
                    glide.snapTo(glide.value + previous - now)
                    glide.animateTo(IntOffset.Zero, spring(stiffness = GlideStiffness))
                }
            }
        }
        .graphicsLayer {
            translationX = glide.value.x.toFloat()
            translationY = glide.value.y.toFloat()
        }
}

private const val GlideStiffness = 500f

/** The score marks, drawn here on the 24 grid: none is either source's artwork. */
internal object RatingMarks {
    /** A plain five-point star, tinted by the caller. */
    val Star: ImageVector = ImageVector.Builder("Star", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.White)) { star(12f, 12.8f, outer = 10.2f, inner = 4.3f, points = 5, squash = 1f) }
    }.build()

    // Alternating long and short lobes, irregular on purpose: a splat that is symmetric reads
    // as a flower.
    private val SplatRadii = floatArrayOf(9.6f, 6.4f, 8.6f, 5.8f, 10.2f, 6.6f, 8.2f, 6.0f, 9.0f, 5.6f, 9.8f, 6.8f)

    val Tomato: ImageVector = ImageVector.Builder("Tomato", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(RatingTomatoRed)) {
            moveTo(2.6f, 13.6f)
            arcTo(9.4f, 8.6f, 0f, isMoreThanHalf = true, isPositiveArc = false, 21.4f, 13.6f)
            arcTo(9.4f, 8.6f, 0f, isMoreThanHalf = true, isPositiveArc = false, 2.6f, 13.6f)
            close()
        }
        path(fill = SolidColor(RatingTomatoLeaf)) { star(12f, 6.4f, outer = 4.6f, inner = 1.7f, points = 5, squash = 0.72f) }
        path(fill = SolidColor(Color.White.copy(alpha = 0.35f))) {
            moveTo(6.2f, 11.4f)
            arcTo(2.2f, 1.4f, -35f, isMoreThanHalf = false, isPositiveArc = true, 9.4f, 9.6f)
            arcTo(2.2f, 1.4f, -35f, isMoreThanHalf = false, isPositiveArc = true, 6.2f, 11.4f)
            close()
        }
    }.build()

    val Splat: ImageVector = ImageVector.Builder("Splat", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(RatingSplatGreen)) { blob(12f, 12.6f, SplatRadii) }
        path(fill = SolidColor(RatingSplatGreen)) {
            drop(3.2f, 4.6f, 1.3f)
            drop(20.6f, 5.4f, 1.0f)
            drop(20.4f, 20.6f, 1.2f)
        }
    }.build()

    private fun PathBuilder.star(cx: Float, cy: Float, outer: Float, inner: Float, points: Int, squash: Float) {
        val steps = points * 2
        for (i in 0 until steps) {
            val angle = -PI / 2 + i * PI / points
            val r = if (i % 2 == 0) outer else inner
            val x = cx + (r * cos(angle)).toFloat()
            val y = cy + (r * sin(angle) * squash).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    /** A closed curve through [radii] spaced evenly round the centre, smoothed through midpoints. */
    private fun PathBuilder.blob(cx: Float, cy: Float, radii: FloatArray) {
        val n = radii.size
        val points = List(n) { i ->
            val angle = -PI / 2 + i * 2 * PI / n
            Offset(cx + (radii[i] * cos(angle)).toFloat(), cy + (radii[i] * sin(angle)).toFloat())
        }
        fun mid(a: Offset, b: Offset) = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
        val start = mid(points[n - 1], points[0])
        moveTo(start.x, start.y)
        for (i in 0 until n) {
            val next = mid(points[i], points[(i + 1) % n])
            quadTo(points[i].x, points[i].y, next.x, next.y)
        }
        close()
    }

    private fun PathBuilder.drop(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = false, cx + r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = false, cx - r, cy)
        close()
    }
}
