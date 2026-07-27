package com.flick.sender.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
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
import androidx.compose.ui.graphics.Shape
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
import coil.compose.AsyncImage
import com.flick.sender.R
import com.flick.sender.model.MediaItem
import com.flick.sender.model.PlaybackUiState
import com.flick.sender.net.FlickController
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.flickGlass
import com.flick.sender.ui.theme.pressScale
import com.flick.sender.ui.theme.rememberReduceMotion

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
private const val CornerResolve = 0.7f

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
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun NowPlayingDock(
    controller: FlickController,
    allowed: Boolean,
    morphing: Boolean,
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
        if (departing != null) {
            DockBar(
                item = departing,
                tvName = tvName,
                playback = playback,
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
 * or its own surface would spill past the card's edge. Both scopes null leaves the
 * modifier inert.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.remoteCardBounds(
    sharedScope: SharedTransitionScope?,
    animatedScope: AnimatedVisibilityScope?,
    leaving: Boolean,
    shadowBleed: Dp = 0.dp,
): Modifier {
    if (sharedScope == null || animatedScope == null) return this
    val reduceMotion = rememberReduceMotion()
    val travel = MaterialTheme.motionScheme.defaultSpatialSpec<Rect>()
    val dissolve = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val bounds = remember(reduceMotion, travel) {
        BoundsTransform { _, _ -> if (reduceMotion) snap<Rect>() else travel }
    }
    val clip = rememberCardMorphClip(sharedScope, shadowBleed)
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
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun rememberCardMorphClip(
    sharedScope: SharedTransitionScope,
    shadowBleed: Dp,
): SharedTransitionScope.OverlayClip {
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
    return remember(sharedScope, shape) { with(sharedScope) { OverlayClip(shape) } }
}

private class CardMorphShape(
    private val barHeightPx: Float,
    private val spanPx: Float,
    private val barRadiusPx: Float,
    private val cardRadiusPx: Float,
    private val bleedPx: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        // Measured off the bounds, never off the bled outline: the radius answers how far
        // the container has grown, and the room left for a shadow is not growth.
        val grown = ((size.height - barHeightPx) / spanPx).coerceIn(0f, 1f)
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

@Composable
private fun DockBar(
    item: MediaItem,
    tvName: String,
    playback: State<PlaybackUiState>,
    morphBounds: Modifier,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
) {
    val colors = LocalFlickColors.current
    val shape = RoundedCornerShape(DockCorner)
    val openSource = remember { MutableInteractionSource() }
    val imageLoader = rememberVideoImageLoader()
    val request = rememberVideoFrameRequest(item.uri, item.durationMs)
    val playing by remember(playback) { derivedStateOf { playback.value.playing } }
    val description = stringResource(R.string.a11y_now_playing_dock, item.name, tvName)
    val openLabel = stringResource(R.string.a11y_open_remote)
    val track = colors.fillTrack
    val played = colors.spark

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
            // The whole bar answers a press on it, but only the left region opens the
            // remote: the transport key beside it is a second, separate target.
            .pressScale(openSource)
            .flickGlass(colors, shape)
            .clip(shape)
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
    ) {
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
                        text = item.name,
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

/** The remote's amber FAB, shrunk to one key. Same glyph, same morph, same ink. */
@Composable
private fun DockKey(playing: Boolean, onClick: () -> Unit) {
    val colors = LocalFlickColors.current
    val source = remember { MutableInteractionSource() }
    val label = stringResource(if (playing) R.string.a11y_pause else R.string.a11y_play)
    val state = stringResource(if (playing) R.string.a11y_playing_state else R.string.a11y_paused_state)
    Box(
        modifier = Modifier
            .size(DockKeySize)
            .pressScale(source)
            .clip(CircleShape)
            .background(colors.spark)
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
            color = colors.onSpark,
            modifier = Modifier.size(21.dp),
        )
    }
}
