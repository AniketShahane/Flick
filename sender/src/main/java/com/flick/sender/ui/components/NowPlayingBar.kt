package com.flick.sender.ui.components

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import coil.compose.AsyncImage
import com.flick.sender.R
import com.flick.sender.model.MediaItem
import com.flick.sender.model.PlaybackUiState
import com.flick.sender.net.FlickController
import com.flick.sender.ui.displayName
import com.flick.sender.ui.motion.ambientFrameRate
import com.flick.sender.ui.motion.animatingFrameRate
import com.flick.sender.ui.theme.CinemaMid
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickGradients
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.Ink
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.Motion
import com.flick.sender.ui.theme.flickGlass
import com.flick.sender.ui.theme.pressScale
import com.flick.sender.ui.theme.rememberReduceMotion
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** The dock's own height and the air it keeps between itself and the nav pill. */
private val DockHeight = 66.dp
private val DockGap = 10.dp
private val DockHairline = 3.dp
private val DockThumb = 48.dp
private val DockKeySize = 48.dp

/** The bar's resting corner, and what it eases into once the card owns the window. */
private val DockCorner = FlickCorners.warning
private val CardCorner = 0.dp

/**
 * How far outside the bar its own elevation shadow reaches. The travelling clip has to
 * leave that much room around the bar's end of the morph, or the shadow is cut off on the
 * flight's first frame and handed back on its last — a blink at both ends of a transform
 * whose whole point is that nothing about it is a cut.
 */
private val DockShadowBleed = 32.dp

/** Fraction of the card's growth the corner radius is resolved over. */
internal const val CornerResolve = 0.7f

/** Where the dock's own travel float counts as landed: about a pixel of a phone's height. */
private const val DockTravelThreshold = 0.0005f

/** Room a screen that reserves its own bottom padding has to add while a cast is live. */
internal val NowPlayingDockClearance: Dp = DockHeight + DockGap

/**
 * The one container transform in the shell. Both ends declare it — the dock here, the
 * remote's route container in the shell — so the bar's bounds ARE the card's bounds.
 */
private const val RemoteCardKey = "remote-card"

/**
 * The live-cast dock. A cast that is playing has to be visible while the user browses,
 * not only once they open the remote — so this rides directly above the floating nav
 * and carries the frame, the title, the TV, one transport key and the session clock.
 *
 * It docks with the nav rather than with a route, so [allowed] is the shell's judgment
 * about where there is room for it; the cast record decides the rest.
 *
 * [morphing] is the shell's answer to "is the surface on the other side of this flip the
 * remote". When it is, the bar neither rises nor falls: its own bounds become the card,
 * and any enter/exit of its own would be a second motion fighting that one.
 *
 * [hazeState] is the shell's one haze source, taken at the route boundary. The dock is a
 * sibling of the nav pill in the same bottom stack, so the route it blurs is already in
 * there and this surface needs no source of its own.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun NowPlayingDock(
    controller: FlickController,
    allowed: Boolean,
    morphing: Boolean,
    hazeState: HazeState,
    sharedScope: SharedTransitionScope?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motionScheme = MaterialTheme.motionScheme
    val reduceMotion = rememberReduceMotion()
    val item by controller.castingItem.collectAsState()
    val tv by controller.connectedTv.collectAsState()
    // Kept as State: the session clock ticks ~10x/s and stops at the hairline's draw
    // scope rather than reaching the library behind it.
    val playback = controller.playback.collectAsState()
    val departing = rememberDeparting(item)
    val tvName = tv?.name ?: stringResource(R.string.np_tv_generic)
    // The card's travel, on the morph's own spring. The bounds are driven by the end that is
    // entering: this transition when minimizing, and when opening one that starts on the same
    // frame as this one.
    val travelSpec = Motion.cardMorphSpec(
        motionScheme.defaultSpatialSpec<Float>(),
        visibilityThreshold = DockTravelThreshold,
    )

    AnimatedVisibility(
        visible = allowed && item != null,
        modifier = modifier,
        enter = if (reduceMotion || morphing) {
            EnterTransition.None
        } else {
            fadeIn(motionScheme.defaultEffectsSpec()) +
                slideInVertically(motionScheme.defaultSpatialSpec()) { it }
        },
        exit = if (reduceMotion || morphing) {
            ExitTransition.None
        } else {
            fadeOut(motionScheme.fastEffectsSpec()) +
                slideOutVertically(motionScheme.defaultSpatialSpec()) { it }
        },
        label = "dock",
    ) {
        // How far the card has travelled from the bar toward the window: 0 at rest, 1 as the
        // remote. Not a morph — any other entrance or exit — holds it at 0. Computed off the
        // transition rather than written by the travelling clip during draw, so the glass and
        // the clip read one value on the same frame, and a minimize's first frame is already
        // the card rather than the bar.
        val travel = transition.animateFloat(
            transitionSpec = { if (reduceMotion) snap<Float>() else travelSpec },
            label = "dock-travel",
        ) { state -> if (morphing && state != EnterExitState.Visible) 1f else 0f }
        val growth = remember(travel) { { dockGrowthAt(travel.value) } }
        if (departing != null) {
            DockBar(
                item = departing,
                tvName = tvName,
                playback = playback,
                hazeState = hazeState,
                morphing = morphing,
                growth = growth,
                // The bar is the surface being left only when it is on its way out INTO
                // the remote; every other departure is the cast itself ending.
                morphBounds = Modifier.remoteCardBounds(
                    sharedScope = sharedScope,
                    animatedScope = this@AnimatedVisibility,
                    leaving = morphing &&
                        transition.targetState == EnterExitState.PostExit,
                    // Only this end of the morph carries a shadow, and only the shadow
                    // lives outside the silhouette: the bar clips its own content to its
                    // own corner, so the extra room can never let anything else escape.
                    shadowBleed = DockShadowBleed,
                    growth = growth,
                ),
                onOpen = onOpen,
                onPlayPause = { controller.playPause() },
            )
        }
    }
}

/**
 * Declares one end of the dock↔remote container transform.
 *
 * Both ends run on the same key, the same spring and the same travelling clip, so
 * minimizing is the identical geometry read backwards. [leaving] is what makes the
 * cross-fade legible in either direction: the surface being left keeps full opacity and
 * dissolves off an arrival that is already opaque underneath it, which is the only
 * ordering in which the card never shows the screen behind it through itself and neither
 * end is a cut. [shadowBleed] is room the travelling clip leaves outside the silhouette
 * for an end that draws an elevation shadow — an end that draws none must ask for zero,
 * or its own surface would spill past the card's edge. [growth], when given, is how far the
 * card has grown (see [dockGrowthAt]); the clip rounds by it, so an end that also paints by it
 * cannot disagree with its own silhouette. Without it the clip reads growth off the bounds.
 * Both scopes null leaves the modifier inert.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.remoteCardBounds(
    sharedScope: SharedTransitionScope?,
    animatedScope: AnimatedVisibilityScope?,
    leaving: Boolean,
    shadowBleed: Dp = 0.dp,
    growth: (() -> Float)? = null,
): Modifier {
    if (sharedScope == null || animatedScope == null) return this
    val reduceMotion = rememberReduceMotion()
    val travel = Motion.cardMorphSpec(
        MaterialTheme.motionScheme.defaultSpatialSpec<Rect>(),
        visibilityThreshold = Motion.cardMorphTravelThreshold,
    )
    val dissolve = Motion.cardMorphSpec(MaterialTheme.motionScheme.defaultSpatialSpec<Float>())
    val bounds = remember(reduceMotion, travel) {
        BoundsTransform { _, _ -> if (reduceMotion) snap<Rect>() else travel }
    }
    val clip = rememberCardMorphClip(shadowBleed, growth)
    return with(sharedScope) {
        this@remoteCardBounds.sharedBounds(
            sharedContentState = rememberSharedContentState(RemoteCardKey),
            animatedVisibilityScope = animatedScope,
            // Never a fade in: the arrival has to be opaque from the first frame so the
            // card cannot be seen through while it travels.
            enter = EnterTransition.None,
            exit = if (leaving && !reduceMotion) fadeOut(dissolve) else ExitTransition.None,
            boundsTransform = bounds,
            zIndexInOverlay = if (leaving) 1f else 0f,
            clipInOverlayDuringTransition = clip,
        )
    }
}

/**
 * The silhouette an end of the morph is clipped to while it travels. The radius is a
 * function of the container's own height rather than of a second animation, so it cannot
 * drift out of step with the bounds it is rounding: it IS the bar's corner at the bar's
 * height, and it has reached the card's square edge before the card is full size, so
 * neither end of the flight snaps. [shadowBleed] widens it without widening the radius's
 * reading of how far the container has grown.
 */
@Composable
private fun rememberCardMorphClip(
    shadowBleed: Dp,
    growth: (() -> Float)?,
): CardMorphClip {
    val density = LocalDensity.current
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val shape = remember(density, screenHeightDp, shadowBleed) {
        with(density) {
            CardMorphShape(
                barHeightPx = DockHeight.toPx(),
                // Deliberately shorter than the travel: the radius has to be all the way
                // home BEFORE the card is, because the clip is handed back at the end of
                // the flight and a radius still easing at that moment would snap.
                spanPx = ((screenHeightDp.dp.toPx() - DockHeight.toPx()) * CornerResolve)
                    .coerceAtLeast(1f),
                barRadiusPx = DockCorner.toPx(),
                cardRadiusPx = CardCorner.toPx(),
                bleedPx = shadowBleed.toPx(),
            )
        }
    }
    return remember(shape, growth) { CardMorphClip(shape, growth) }
}

/**
 * The clip both ends travel under. It only reads: writing state from here, during the
 * overlay's draw, reached the bar's glass a frame late, and the first frame of a minimize —
 * drawn before the clip had been asked anything — painted the bar's glass on a card.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
private class CardMorphClip(
    private val shape: CardMorphShape,
    private val growth: (() -> Float)?,
) : SharedTransitionScope.OverlayClip {
    private val path = Path()

    override fun getClipPath(
        sharedContentState: SharedTransitionScope.SharedContentState,
        bounds: Rect,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Path {
        val grown = growth?.invoke() ?: shape.growth(bounds.height)
        path.reset()
        path.addOutline(shape.outline(bounds.size, grown))
        path.translate(bounds.topLeft)
        return path
    }
}

private class CardMorphShape(
    private val barHeightPx: Float,
    private val spanPx: Float,
    private val barRadiusPx: Float,
    private val cardRadiusPx: Float,
    private val bleedPx: Float,
) : Shape {
    /**
     * How far a container [heightPx] tall has grown from the bar toward the card: 0 at the
     * bar, 1 once the radius has resolved. Measured off the bounds, never off the bled
     * outline: the room left for a shadow is not growth.
     */
    fun growth(heightPx: Float): Float = dockGrowth(heightPx, barHeightPx, spanPx)

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = outline(size, growth(size.height))

    fun outline(size: Size, grown: Float): Outline {
        val radius = barRadiusPx + (cardRadiusPx - barRadiusPx) * grown
        return Outline.Rounded(
            RoundRect(
                left = -bleedPx,
                top = -bleedPx,
                right = size.width + bleedPx,
                bottom = size.height + bleedPx,
                cornerRadius = CornerRadius(radius + bleedPx),
            ),
        )
    }
}

/**
 * The cast record clears the moment the TV stops, and the bar still has to leave with
 * the title it was showing.
 */
@Composable
private fun rememberDeparting(item: MediaItem?): MediaItem? {
    val held = remember { mutableStateOf<MediaItem?>(null) }
    LaunchedEffect(item) { if (item != null) held.value = item }
    return item ?: held.value
}

/**
 * The bar itself. Its material follows ONE number, [growth]: how far the morph's travelling
 * bounds have grown from the bar toward the card (0 at rest — see [dockGrowthAt]).
 * Everything it drives is read in a draw or haze block, so the morph repaints the bar and
 * never recomposes it.
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalHazeApi::class)
@Composable
private fun DockBar(
    item: MediaItem,
    tvName: String,
    playback: State<PlaybackUiState>,
    hazeState: HazeState,
    morphing: Boolean,
    growth: () -> Float,
    morphBounds: Modifier,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
) {
    val colors = LocalFlickColors.current
    val displayName = item.displayName()
    val shape = RoundedCornerShape(DockCorner)
    val openSource = remember { MutableInteractionSource() }
    val imageLoader = rememberVideoImageLoader()
    val request = rememberVideoFrameRequest(item.uri, item.durationMs)
    val playing by remember(playback) { derivedStateOf { playback.value.playing } }
    val description = stringResource(R.string.a11y_now_playing_dock, displayName, tvName)
    val openLabel = stringResource(R.string.a11y_open_remote)
    val track = colors.fillTrack
    // The played hairline is the scrub bar's own fill in miniature, so it takes the media
    // role rather than the accent — the accent is a blue in dark, and this line has to be
    // the same substance as the fill the remote shows for the same position.
    val played = colors.playheadLo
    // The dock is cut from the same material as the pill it rides on — same blur, same
    // noise, same tint recipe — and differs only in how much of the route it lets through.
    // See [DockBackdropVisibility] for why the upper of two stacked glass surfaces is the
    // thinner one.
    val hazeStyle = remember(colors) {
        HazeStyle(
            tints = glassHazeTints(colors, DockBackdropVisibility).map { HazeTint(it) },
            blurRadius = GlassBlurRadius,
            noiseFactor = GlassNoiseFactor,
            fallbackTint = HazeTint(glassFallbackTint(colors, DockBackdropVisibility)),
        )
    }
    val blurAvailable = navBackdropBlurEnabled(Build.VERSION.SDK_INT)
    // Zero whenever this is not a morph: the travel only moves when one is in the air.
    val p: () -> Float = growth
    val backdropEffect = Modifier.hazeEffect(state = hazeState, style = hazeStyle) {
        // Read here rather than in composition: this block is observed by the effect node
        // itself, so the morph repaints the blur layer it already has and never recomposes
        // the bar.
        val glass = dockGlass(p())
        blurRadius = GlassBlurRadius * glass
        // The blur stands down as the bar lifts into the card, and is gone well before the
        // bar has travelled far. A hazeEffect node samples the source through its OWN
        // resolved coordinates, and for the length of the flight this bar is drawn in the
        // shared-transition overlay rather than where it lives — so the backdrop it would
        // sample is not the one lying under the silhouette the viewer is watching. Nor could
        // it be cut to that silhouette if it were: flickGlass clips the effect to [shape],
        // which is the bar's resting corner, while the travelling clip is easing the corner
        // from that corner to the card's square edge. Close to the seat neither is visible;
        // past [DockGlassGoneAt] the style paints its own fallback tint instead, which
        // samples nothing. Same floor as the nav's, for the reason [navBackdropBlurEnabled]
        // gives.
        blurEnabled = dockBlurEnabled(blurAvailable, p())
        inputScale = HazeInputScale.Fixed(GlassInputScaleFactor)
    }
    // The flat tint is the style's own fallbackTint — the colour Haze itself paints where
    // there is no blur — so near the seat the fill changes only how much of the backdrop is
    // hidden, never the hue or the weight of the material. Further out it turns toward the
    // remote's own ground, which is what the card is about to become.
    val glassTint = remember(colors) { glassFallbackTint(colors, DockBackdropVisibility) }
    val glassHue = remember(glassTint) { glassTint.copy(alpha = 1f) }

    Box(
        Modifier
            .fillMaxWidth()
            .padding(bottom = DockGap)
            // Declared ABOVE the press response: the overlay redraws only what is below
            // this line while the card is in flight, so a press still springing back when
            // the tap opens the remote has to finish INSIDE the card. Left outside, that
            // spring would settle on a placeholder nobody can see and the flight's first
            // frame would be a cut back to full size.
            .then(morphBounds)
            .animatingFrameRate(morphing)
            // The whole bar answers a press on it, but only the left region opens the
            // remote: the transport key beside it is a second, separate target.
            .pressScale(openSource)
            .flickGlass(
                colors = colors,
                shape = shape,
                // The other half of the haze block above: the haze layer always carries the
                // glass's own tint, so the fill only makes up the rest of the coverage. At
                // rest this is the transparent fill [glassBackdropFill] names.
                fill = {
                    val at = p()
                    lerp(glassHue, CinemaMid, dockFillHue(at))
                        .copy(alpha = dockFillAlpha(glassTint.alpha, at))
                },
                showSheen = navShowsGlassSheen(colors),
                backdropEffect = backdropEffect,
                edges = { dockEdges(p()) },
            )
            .clip(shape)
            .holdsTouches(),
    ) {
        // A layer of its own so the clock that moves it can ask for no more frames than a
        // 3 dp line needs, whatever the bar around it is doing.
        Box(
            Modifier
                .matchParentSize()
                .ambientFrameRate()
                .drawBehind {
                    // Read in the draw scope: the clock must repaint the hairline without
                    // recomposing the bar or the grid under it.
                    val height = DockHairline.toPx()
                    val top = size.height - height
                    drawRect(track, topLeft = Offset(0f, top), size = Size(size.width, height))
                    val fraction = playback.value.confirmedFraction
                    if (fraction > 0f) {
                        drawRect(played, topLeft = Offset(0f, top), size = Size(size.width * fraction, height))
                    }
                },
        )
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = DockHeight)
                .padding(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = openSource,
                        indication = null,
                        onClickLabel = openLabel,
                        role = Role.Button,
                        onClick = onOpen,
                    )
                    .semantics(mergeDescendants = true) { contentDescription = description },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(DockThumb)
                        .clip(RoundedCornerShape(FlickCorners.previewThumb))
                        .background(colors.surfaceRaisedAlt),
                )
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = displayName,
                        style = FlickText.labelLarge.copy(color = colors.onSurface),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        Icon(
                            FlickIcons.Cast,
                            contentDescription = null,
                            tint = colors.onSurfaceDim,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = tvName,
                            style = FlickText.bodySmall.copy(color = colors.onSurfaceDim),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
            DockKey(playing = playing, onClick = onPlayPause)
        }
    }
}

/** How far a container [heightPx] tall has grown from a [restPx] bar over [spanPx]: 0..1. */
internal fun dockGrowth(heightPx: Float, restPx: Float, spanPx: Float): Float =
    ((heightPx - restPx) / spanPx.coerceAtLeast(1f)).coerceIn(0f, 1f)

/**
 * [dockGrowth] for a card [travel] of the way from the bar to the window: the corner resolves
 * over the first [CornerResolve] of the travel, so it is the same number whatever the window.
 */
internal fun dockGrowthAt(travel: Float): Float = (travel / CornerResolve).coerceIn(0f, 1f)

/** Hermite step from 0 at [edge0] to 1 at [edge1], flat at both ends. */
internal fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * How much of the blurred glass the bar still is at growth [p]: all of it at rest, none of
 * it past [DockGlassGoneAt]. It scales the blur radius, so the backdrop sharpens as it goes.
 */
internal fun dockGlass(p: Float): Float = 1f - smoothstep(0f, DockGlassGoneAt, p)

/** Whether the haze layer should still blur at [p]; below the floor it paints its tint flat. */
internal fun dockBlurEnabled(available: Boolean, p: Float): Boolean =
    available && dockGlass(p) > DockBlurFloor

/**
 * The share of the backdrop the bar's material hides at [p]. At rest that is the glass's
 * own tint, [tintAlpha], over a blurred backdrop. As the blur leaves the backdrop sharpens,
 * so the coverage rises with exactly the blur's departure: a sharp library never shows
 * through more than [DockFlightCoverage] leaves of it, and from [DockGlassGoneAt] on the
 * coverage holds there.
 */
internal fun dockCoverage(tintAlpha: Float, p: Float): Float =
    lerp(tintAlpha, DockFlightCoverage, 1f - dockGlass(p))

/**
 * Opacity of the fill painted over a haze layer that already hides [tintAlpha] of the
 * backdrop — blurred or, below the floor, as a flat tint — so that the two together hide
 * [dockCoverage]. Zero at rest.
 */
internal fun dockFillAlpha(tintAlpha: Float, p: Float): Float {
    val remaining = 1f - tintAlpha
    if (remaining <= 0f) return 0f
    return ((dockCoverage(tintAlpha, p) - tintAlpha) / remaining).coerceIn(0f, 1f)
}

/** How far the fill's hue has turned from the glass's own tint toward the remote's ground. */
internal fun dockFillHue(p: Float): Float = smoothstep(DockHueFrom, DockHueTo, p)

/** The sheen, rim and shadow: a floating bar's, which a card filling the window has not. */
internal fun dockEdges(p: Float): Float = 1f - smoothstep(DockEdgesFrom, 1f, p)

internal const val DockGlassGoneAt = 0.25f
internal const val DockBlurFloor = 0.01f
internal const val DockFlightCoverage = 0.9f
internal const val DockHueFrom = 0.15f
internal const val DockHueTo = 0.6f
internal const val DockEdgesFrom = 0.6f

/** The remote's amber FAB, shrunk to one key. Same glyph, same morph, same ink. */
@Composable
private fun DockKey(playing: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val label = stringResource(if (playing) R.string.a11y_pause else R.string.a11y_play)
    val state = stringResource(if (playing) R.string.a11y_playing_state else R.string.a11y_paused_state)
    Box(
        modifier = Modifier
            .size(DockKeySize)
            .pressScale(source)
            .clip(CircleShape)
            // The same brush and the same ink as the remote's FAB, not a flat accent: this
            // key morphs into that FAB, and a shared-element flight that changes hue in the
            // air is the most visible thing a palette swap can produce. The accent is a blue
            // in dark and would also land at 2.64:1 on the dock's own glass.
            .background(FlickGradients.fab)
            // No haptic here: PlaybackSession already pulses the vibrator when it sends
            // the command, and the two would answer one tap twice.
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
                stateDescription = state
            },
        contentAlignment = Alignment.Center,
    ) {
        PlayPauseMorph(
            playing = playing,
            color = Ink,
            modifier = Modifier.size(21.dp),
        )
    }
}
