package com.flick.sender.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateBounds
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import com.flick.sender.R
import com.flick.sender.media.MediaAccess
import com.flick.sender.media.MediaLibraryAction
import com.flick.sender.media.MediaLibraryActionPolicy
import com.flick.sender.media.MediaProbe
import com.flick.sender.model.HdrType
import com.flick.sender.model.MediaItem
import com.flick.sender.model.PlaybackPhase
import com.flick.sender.net.FlickController
import com.flick.sender.net.PairedTv
import com.flick.sender.ui.Format
import com.flick.sender.ui.components.FlickMark
import com.flick.sender.ui.components.FlickPrimaryButton
import com.flick.sender.ui.components.LiveDot
import com.flick.sender.ui.components.NowPlayingDockClearance
import com.flick.sender.ui.components.RevealOrigin
import com.flick.sender.ui.components.VideoTile
import com.flick.sender.ui.components.rememberVideoImageLoader
import com.flick.sender.ui.components.revealOrigin
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.Motion
import com.flick.sender.ui.theme.PillMorphShape
import com.flick.sender.ui.theme.PillShape
import com.flick.sender.ui.theme.PressedPillShape
import com.flick.sender.ui.theme.PrimaryShadow
import com.flick.sender.ui.theme.flickRipple
import com.flick.sender.ui.theme.pressScale
import com.flick.sender.ui.theme.rememberFlickTouchHaptics
import com.flick.sender.ui.theme.rememberReduceMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Room the floating nav needs at the foot of the scroll (design §5.4). */
private val NavClearance = 116.dp

/** S3 — the library. A gallery, not a file browser: real MediaStore videos. */
@Composable
fun LibraryScreen(
    controller: FlickController,
    onRequestVideoPermission: () -> Unit,
    revealOrigin: RevealOrigin,
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    val colors = LocalFlickColors.current
    val motionScheme = MaterialTheme.motionScheme
    val reduceMotion = rememberReduceMotion()
    val items by controller.mediaItems.collectAsState()
    val loading by controller.libraryLoading.collectAsState()
    val mediaAccess by controller.mediaAccess.collectAsState()
    val connectedTv by controller.connectedTv.collectAsState()
    val castingItem by controller.castingItem.collectAsState()
    val imageLoader = rememberVideoImageLoader()
    // State, not a value: the 2 s telemetry poll must stop at the pill that shows it
    // rather than rebuilding the whole grid.
    val signal = rememberSignalState()
    val on24GHz by remember(signal) { derivedStateOf { signal.value.on24GHz } }
    val compactTiles = isCompactHeight(LocalConfiguration.current.screenHeightDp)
    val mediaAction = MediaLibraryActionPolicy.forAccess(mediaAccess)

    // The dock docks above the nav while a cast is live, so the last row of the grid
    // has to clear both of them, not just the nav.
    val bottomClearance = NavClearance + if (castingItem != null) NowPlayingDockClearance else 0.dp

    var filter by remember { mutableStateOf(LibFilter.ALL) }

    // The grid's answer to a chip tap. The epoch retriggers the wave; the window closes
    // it so a tile the lazy grid composes minutes later is not treated as arriving.
    // Neither moves on a re-tap of the live chip: an exclusive axis reflows nothing.
    var reflowEpoch by remember { mutableIntStateOf(0) }
    var reflowArmed by remember { mutableStateOf(false) }
    LaunchedEffect(reflowEpoch) {
        if (reflowEpoch == 0) return@LaunchedEffect
        delay(ReflowWindowMs)
        reflowArmed = false
    }

    // The entrance plays once, on the first paint after MediaStore resolves — never on
    // a filter switch, which has a wave of its own. The window closes it so scrolling
    // back to the top cannot replay it on tiles the lazy grid recomposes.
    var staggerArmed by remember { mutableStateOf(false) }
    var staggerSpent by remember { mutableStateOf(false) }
    LaunchedEffect(loading, items.isEmpty(), reduceMotion) {
        if (staggerSpent || reduceMotion || loading || items.isEmpty()) return@LaunchedEffect
        staggerSpent = true
        staggerArmed = true
        delay(StaggerWindowMs)
        staggerArmed = false
    }

    if (mediaAccess == MediaAccess.NONE || (items.isEmpty() && !loading)) {
        EmptyState(
            controller = controller,
            connectedTv = connectedTv,
            castingItem = castingItem,
            signal = signal,
            bottomClearance = bottomClearance,
            onChoose = onRequestVideoPermission,
        )
        return
    }

    // Both quality chips read a value MediaStore already reported, so the visible set
    // is a plain function of the library and the chip — no probe, nothing to subscribe
    // to, and no work at all on the frame a tile arrives.
    val filtered = remember(items, filter) {
        LibraryFilterPolicy.apply(
            items = items,
            filter = filter,
            resolutionLabel = { it.resolutionLabel },
        )
    }

    LazyVerticalGrid(
        // Adaptive rather than a fixed pair: rotation and foldables widen the row, and
        // a fixed two-column split would stretch each 16:9 still into a letterbox sliver.
        columns = GridCells.Adaptive(minSize = TileMinWidth),
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = bottomClearance),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        fullWidth {
            Header(
                mediaAction = mediaAction,
                revealOrigin = revealOrigin,
                onMediaAction = {
                    when (mediaAction) {
                        MediaLibraryAction.SELECT_MORE -> onRequestVideoPermission()
                        MediaLibraryAction.REFRESH -> controller.refreshMediaLibrary()
                        MediaLibraryAction.HIDDEN -> Unit
                    }
                },
                onTune = { controller.toggleAdvisories(true) },
            )
        }
        fullWidth {
            LinkPill(
                controller = controller,
                connectedTv = connectedTv,
                castingItem = castingItem,
                signal = signal,
            )
        }
        fullWidth {
            FilterChips(
                filter = filter,
                totalCount = items.size,
                onSelect = { chosen ->
                    filter = chosen
                    // Armed from the tap rather than from the effect that closes it:
                    // the wave has to be in place for the first composition the new
                    // set is measured in, or the tiles paint once at rest and only
                    // then dip.
                    reflowArmed = !reduceMotion
                    reflowEpoch++
                },
            )
        }
        if (on24GHz) {
            fullWidth {
                BandAdvisory(
                    revealOrigin = revealOrigin,
                    onClick = { controller.toggleAdvisories(true) },
                )
            }
        }
        when {
            loading -> fullWidth { Note(stringResource(R.string.library_loading)) }
            // "All" cannot be empty while the library is not: only a quality chip can
            // filter every file away.
            filtered.isEmpty() && filter != LibFilter.ALL -> fullWidth { FilterEmpty(filter) }
        }
        itemsIndexed(filtered, key = { _, item -> item.id }) { index, item ->
            LibraryTile(
                item = item,
                imageLoader = imageLoader,
                compact = compactTiles,
                onClick = { controller.openDetail(item) },
                sharedScope = sharedScope,
                animatedScope = animatedScope,
                // A filter switch reflows the surviving tiles instead of teleporting
                // them; placement is geometry and takes the spatial spring.
                modifier = Modifier
                    .animateItem(
                        fadeInSpec = motionScheme.defaultEffectsSpec(),
                        placementSpec = motionScheme.defaultSpatialSpec(),
                        fadeOutSpec = motionScheme.fastEffectsSpec(),
                    )
                    .staggeredEntrance(index = index, armed = staggerArmed)
                    .reflowWave(index = index, epoch = reflowEpoch, armed = reflowArmed),
            )
        }
    }
}

/**
 * The grid's first tiles arrive in sequence rather than all at once. A graphicsLayer
 * transform only: the lazy grid's own placement must never see moving bounds.
 *
 * [armed] is the window the grid holds open for the whole sequence, and it closes on a
 * timer. A tile that has already joined keeps its layer until its OWN spring is home —
 * the window decides which tiles are part of the entrance, not which of them are allowed
 * to finish it, and a tile composed late or delayed by a janked frame would otherwise be
 * dropped at whatever value the timer found it on.
 */
@Composable
private fun Modifier.staggeredEntrance(index: Int, armed: Boolean): Modifier {
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val progress = remember { Animatable(0f) }
    var rising by remember { mutableStateOf(false) }
    // Keyed on the arming edge and on this tile's own flight, never on the window closing
    // alone: re-keying it while the spring is running would cancel it mid-dip.
    LaunchedEffect(armed || rising) {
        if (!armed) return@LaunchedEffect
        rising = true
        delay(index.coerceAtMost(StaggerCapIndex) * StaggerStepMs)
        progress.animateTo(1f, spec)
        rising = false
    }
    if (!armed && !rising) return this
    return graphicsLayer {
        val p = progress.value
        // Clamped: the spatial spring overshoots by design and opacity must not.
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * StaggerRiseDp.toPx()
    }
}

/**
 * The grid answering the chip that was just tapped: the tiles re-deal in sequence
 * instead of the new set simply existing, so the reflow reads as the consequence of
 * the tap rather than as an unrelated event. [epoch] retriggers it, [armed] closes the
 * window — a tile the lazy grid composes after that has not just arrived, it was
 * scrolled to. A graphicsLayer transform only, like the entrance: the lazy grid's own
 * placement animation is already moving the tiles that survived the switch, and it
 * must never see bounds this changes too. Like the entrance, a tile that has joined the
 * wave keeps its layer until its own spring is home: the window closes for the grid, not
 * for a tile that is still mid-dip when the timer runs out.
 */
@Composable
private fun Modifier.reflowWave(index: Int, epoch: Int, armed: Boolean): Modifier {
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    // Reset by the key, not from the effect: the dip has to be on a surviving tile in the
    // very composition the new set is measured in, or it paints once at rest first.
    val progress = remember(epoch) { Animatable(if (armed) 0f else 1f) }
    var dipping by remember { mutableStateOf(false) }
    LaunchedEffect(epoch) {
        if (!armed) {
            dipping = false
            return@LaunchedEffect
        }
        dipping = true
        delay(index.coerceAtMost(ReflowCapIndex) * ReflowStepMs)
        progress.animateTo(1f, spec)
        dipping = false
    }
    if (!armed && !dipping) return this
    return graphicsLayer {
        val p = progress.value
        alpha = (ReflowFromAlpha + (1f - ReflowFromAlpha) * p).coerceIn(0f, 1f)
        // Unclamped on purpose: the spring's overshoot is the tile landing.
        val s = ReflowFromScale + (1f - ReflowFromScale) * p
        scaleX = s
        scaleY = s
    }
}

/** Section blocks span both columns and carry the extra 5 dp that widens the 13 dp grid gap. */
private fun LazyGridScope.fullWidth(
    content: @Composable () -> Unit,
) = item(span = { GridItemSpan(maxLineSpan) }) {
    Box(Modifier.padding(bottom = 5.dp)) { content() }
}

@Composable
private fun Header(
    mediaAction: MediaLibraryAction,
    revealOrigin: RevealOrigin,
    onMediaAction: () -> Unit,
    onTune: () -> Unit,
) {
    val colors = LocalFlickColors.current
    val tuneLabel = stringResource(R.string.a11y_library_tune)
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FlickMark(modifier = Modifier.size(42.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = FlickText.displayLarge.copy(color = colors.onSurface),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (mediaAction != MediaLibraryAction.HIDDEN) {
            val label = stringResource(
                if (mediaAction == MediaLibraryAction.SELECT_MORE) {
                    R.string.library_add_videos
                } else {
                    R.string.library_refresh_videos
                },
            )
            FilledTonalButton(
                onClick = onMediaAction,
                shapes = ButtonDefaults.shapes(shape = PillMorphShape, pressedShape = PressedPillShape),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = colors.primaryContainer,
                    contentColor = colors.onPrimaryContainer,
                ),
                contentPadding = PaddingValues(horizontal = 15.dp, vertical = 15.dp),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(text = label, style = FlickText.labelMedium)
            }
        }
        FilledTonalIconButton(
            onClick = onTune,
            shapes = IconButtonDefaults.shapes(
                shape = RoundedCornerShape(FlickCorners.tuneBtn),
                pressedShape = PressedPillShape,
            ),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = colors.inverseSurface,
                contentColor = colors.onInverseSurface,
            ),
            modifier = Modifier
                .size(48.dp)
                // The advisories sheet is born here, in the top-right corner it was
                // asked for from.
                .revealOrigin(revealOrigin)
                .semantics { contentDescription = tuneLabel },
        ) {
            Icon(
                FlickIcons.Tune,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** Which of the pill's three honest states is showing. */
private enum class LinkPillState { CASTING, PAIRED, UNPAIRED }

/**
 * Snapshotted so the face being replaced keeps the words it was showing while it
 * leaves; [LinkPillState] is the transition key, so a phase change rewrites the line
 * in place instead of restarting the swap.
 */
@Immutable
private data class LinkPillModel(
    val state: LinkPillState,
    val line: String,
    val playing: Boolean,
)

/**
 * One slot, three honest states: a cast in flight, paired but idle, nothing paired.
 * It is a single pill that changes face rather than three pills that replace each
 * other. The wording follows the TV's own reported phase — `castingItem` stays set
 * through pause and end — and the throughput number only replaces the band once the
 * server is actually writing bytes.
 */
@Composable
private fun LinkPill(
    controller: FlickController,
    connectedTv: PairedTv?,
    castingItem: MediaItem?,
    signal: State<SignalInfo>,
) {
    val colors = LocalFlickColors.current
    val motionScheme = MaterialTheme.motionScheme
    val reduceMotion = rememberReduceMotion()
    val state = when {
        connectedTv != null && castingItem != null -> LinkPillState.CASTING
        connectedTv != null -> LinkPillState.PAIRED
        else -> LinkPillState.UNPAIRED
    }

    // Kept as State so the 10 Hz session clock stops at this pill instead of
    // invalidating the grid behind it; only the phase itself reaches composition.
    val phase = if (state == LinkPillState.CASTING) {
        val playback = controller.playback.collectAsState()
        val derived = remember(playback) { derivedStateOf { playback.value.phase } }
        derived.value
    } else {
        PlaybackPhase.IDLE
    }

    val tvName = connectedTv?.name
    val line = when {
        tvName == null -> stringResource(R.string.empty_no_tv)
        state == LinkPillState.CASTING -> stringResource(castPillLabel(phase), tvName)
        else -> stringResource(R.string.library_ready_pill, tvName)
    }
    val model = LinkPillModel(state = state, line = line, playing = phase == PlaybackPhase.PLAYING)

    val restoreLabel = castingItem?.let { stringResource(R.string.a11y_restore_now_playing, it.name) }
    val connectLabel = stringResource(R.string.a11y_open_connect)
    val description = when (state) {
        LinkPillState.CASTING -> restoreLabel
        LinkPillState.PAIRED -> null
        LinkPillState.UNPAIRED -> connectLabel
    }
    val action: (() -> Unit)? = when (state) {
        LinkPillState.CASTING -> ({ controller.restoreNowPlaying() })
        LinkPillState.PAIRED -> null
        LinkPillState.UNPAIRED -> ({ controller.openConnect() })
    }

    val unpaired = state == LinkPillState.UNPAIRED
    val ink = if (unpaired) colors.onPrimaryContainer else colors.onPrimary
    val container by animateColorAsState(
        targetValue = if (unpaired) colors.primaryContainer else colors.primary,
        // Colour never overshoots; only the pill's geometry is allowed to.
        animationSpec = Motion.orSnap(reduceMotion, motionScheme.defaultEffectsSpec<Color>()),
        label = "link pill container",
    )
    val interaction = remember { MutableInteractionSource() }
    val pillSpatial = motionScheme.defaultSpatialSpec<Rect>()
    val pillBounds = remember(reduceMotion, pillSpatial) {
        BoundsTransform { _, _ -> if (reduceMotion) snap<Rect>() else pillSpatial }
    }

    LookaheadScope {
        Pill(
            container = container,
            modifier = Modifier
                .animateBounds(this@LookaheadScope, boundsTransform = pillBounds)
                .then(
                    if (action != null) {
                        Modifier
                            .pressScale(interaction)
                            // Clipped here too: Pill's own clip sits below this touch
                            // node, so it cannot bound the ripple.
                            .clip(PillShape)
                            .clickable(
                                interactionSource = interaction,
                                indication = flickRipple(ink),
                                onClick = action,
                            )
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (description != null) {
                        // The merged description replaces the visible copy, so the state
                        // the dot animation carries is spoken separately.
                        Modifier.semantics(mergeDescendants = true) {
                            role = Role.Button
                            contentDescription = description
                            if (state == LinkPillState.CASTING) stateDescription = model.line
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            AnimatedContent(
                targetState = model,
                contentKey = { it.state },
                transitionSpec = {
                    if (reduceMotion) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        (
                            fadeIn(motionScheme.defaultEffectsSpec()) +
                                scaleIn(motionScheme.fastSpatialSpec(), initialScale = PillSwapScale)
                            ) togetherWith (
                            fadeOut(motionScheme.fastEffectsSpec()) +
                                scaleOut(motionScheme.fastSpatialSpec(), targetScale = PillSwapScale)
                            )
                    }
                },
                modifier = Modifier.weight(1f),
                label = "link pill face",
            ) { face ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (face.state == LinkPillState.UNPAIRED) {
                        Icon(
                            FlickIcons.Cast,
                            contentDescription = null,
                            tint = colors.onPrimaryContainer,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = face.line,
                            style = FlickText.labelMedium.copy(color = colors.onPrimaryContainer),
                        )
                    } else {
                        LiveDot(
                            color = colors.sparkLight,
                            size = 10.dp,
                            pulsing = face.playing,
                        )
                        Text(
                            text = face.line,
                            style = FlickText.labelMedium.copy(color = colors.onPrimary),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        PillTelemetry(signal)
                    }
                }
            }
        }
    }
}

/**
 * TransferTelemetry only counts bytes this phone's server actually wrote, so a zero is
 * "nothing is moving", never a measured rate. The band is the honest stand-in.
 */
@Composable
private fun RowScope.PillTelemetry(signal: State<SignalInfo>) {
    val colors = LocalFlickColors.current
    val live = signal.value
    Text(
        text = if (live.serving) Format.megabits(live.throughputBitsPerSec) else live.bandLabel(),
        style = FlickText.monoSmall.copy(color = colors.onPrimaryMuted),
    )
}

/** The TV's phase, not the presence of a cast record: it survives pause and end. */
private fun castPillLabel(phase: PlaybackPhase): Int = when (phase) {
    PlaybackPhase.PLAYING -> R.string.library_live_pill
    PlaybackPhase.BUFFERING -> R.string.library_buffering_pill
    PlaybackPhase.PAUSED -> R.string.library_paused_pill
    PlaybackPhase.IDLE, PlaybackPhase.ENDED, PlaybackPhase.ERROR -> R.string.library_ready_pill
}

@Composable
private fun Pill(
    container: Color,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(PillShape)
            .background(container)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/**
 * The library's quality axis, and the app's most-tapped control.
 *
 * Selection is ONE fill that travels between the seats rather than chips that
 * cross-fade — the chip left behind and the chip arrived at are the same object
 * moving, which is the language the bottom nav already speaks. That is why the pills
 * are drawn here and not by the chips: the arriving fill has to pass over the seat it
 * is heading for, and a chip that painted its own opaque pill would hide it.
 *
 * Every tap answers, including a re-tap of the chip that is already selected. The kick is
 * therefore driven from the tap itself: an exclusive axis leaves the selection untouched
 * on a re-tap, so selection cannot be what the feedback reads.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterChips(filter: LibFilter, totalCount: Int, onSelect: (LibFilter) -> Unit) {
    val colors = LocalFlickColors.current
    val haptics = rememberFlickTouchHaptics()
    val reduceMotion = rememberReduceMotion()
    val motionScheme = MaterialTheme.motionScheme
    val travel = motionScheme.defaultSpatialSpec<Rect>()
    val kick = motionScheme.fastSpatialSpec<Float>()
    val settle = motionScheme.defaultSpatialSpec<Float>()

    // Seats are measured, not composed: a plain map plus an epoch keeps a layout pass
    // from writing snapshot state the same layout pass reads, and the epoch only moves
    // when a seat genuinely changes (first placement, rotation, font scale).
    val seats = remember { mutableMapOf<LibFilter, Rect>() }
    var seatEpoch by remember { mutableIntStateOf(0) }
    val host = remember { mutableStateOf<LayoutCoordinates?>(null) }
    val reportSeat: (LibFilter, Rect) -> Unit = { chip, rect ->
        if (seats[chip] != rect) {
            seats[chip] = rect
            seatEpoch++
        }
    }

    val fill = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    LaunchedEffect(filter, seatEpoch, reduceMotion) {
        val destination = seats[filter] ?: return@LaunchedEffect
        if (fill.value == destination) return@LaunchedEffect
        // Rect.Zero is "nothing measured yet", so the first placement lands instead of
        // flying in from the corner of the row.
        if (reduceMotion || fill.value == Rect.Zero) {
            fill.snapTo(destination)
        } else {
            fill.animateTo(destination, travel)
        }
    }

    // The kick: out to full stretch, then a spring back that overshoots through rest.
    // One animation per chip rather than one shared value the next tap re-points: at the
    // rhythm this row is actually tapped at, the chip just left is still settling, and it
    // has to spring home from where it had got to instead of being dropped there. Written
    // from the tap and read only from draw scopes, so a deformation never costs a
    // recomposition.
    val pops = remember { LibFilter.entries.associateWith { Animatable(0f) } }
    // Launched off the composition's own scope, not from an effect keyed on the tap: a
    // second tap must not cancel the spring the first one is still running.
    val scope = rememberCoroutineScope()

    val tap: (LibFilter) -> Unit = { chip ->
        if (!reduceMotion) {
            scope.launch {
                val struck = pops.getValue(chip)
                struck.animateTo(1f, kick)
                struck.animateTo(0f, settle)
            }
            pops.forEach { (other, pop) ->
                if (other != chip && (pop.isRunning || pop.value != 0f)) {
                    scope.launch { pop.animateTo(0f, settle) }
                }
            }
        }
        // One exclusive axis: re-tapping the live chip selects nothing, so nothing may
        // reach the actuator. The kick above is the whole answer to that tap.
        if (chip != filter) {
            haptics.toggle(true)
            onSelect(chip)
        }
    }

    val restFill = colors.primaryContainer
    val liveFill = colors.inverseSurface

    Box(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { host.value = it }
            // One draw node for the whole axis: the resting pills, then the travelling
            // fill on top of them. Every value here is read in the draw phase, so
            // hammering a chip repaints this row without recomposing the grid below it.
            .drawBehind {
                // The epoch is this node's subscription to a seat moving; the map
                // itself is deliberately not snapshot state. Zero is "not placed yet".
                if (seatEpoch == 0) return@drawBehind
                LibFilter.entries.forEach { chip ->
                    val seat = seats[chip] ?: return@forEach
                    drawChipPill(seat, restFill, pops.getValue(chip).value)
                }
                // The travelling fill deforms with the seat it is sitting in, which is
                // the chip the selection just moved to.
                drawChipPill(fill.value, liveFill, pops.getValue(filter).value)
            },
    ) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Only "All" carries a count: a per-bucket tally would claim a precision
            // MediaStore's pixel dimensions do not have, and it is the count of the
            // whole library that tells the user what the other chips are hiding.
            Chip(
                text = stringResource(R.string.library_filter_all, totalCount),
                active = filter == LibFilter.ALL,
                host = host,
                onSeat = { reportSeat(LibFilter.ALL, it) },
                squash = { pops.getValue(LibFilter.ALL).value },
                onTap = { tap(LibFilter.ALL) },
            )
            Chip(
                text = stringResource(R.string.library_filter_4k),
                active = filter == LibFilter.FOUR_K,
                host = host,
                onSeat = { reportSeat(LibFilter.FOUR_K, it) },
                squash = { pops.getValue(LibFilter.FOUR_K).value },
                onTap = { tap(LibFilter.FOUR_K) },
            )
            Chip(
                text = stringResource(R.string.library_filter_1080p),
                active = filter == LibFilter.FULL_HD,
                host = host,
                onSeat = { reportSeat(LibFilter.FULL_HD, it) },
                squash = { pops.getValue(LibFilter.FULL_HD).value },
                onTap = { tap(LibFilter.FULL_HD) },
            )
        }
    }
}

/**
 * One chip pill, drawn by the row rather than by the chip. [squash] is the tap kick:
 * 0 at rest, 1 at full stretch, negative on the spring's counter-pose. Wider and
 * shorter about the seat's own centre, which is where the label's layer transform
 * pivots too, so the two deform as one object.
 */
private fun DrawScope.drawChipPill(seat: Rect, color: Color, squash: Float) {
    if (seat.isEmpty) return
    scale(chipStretchX(squash), chipSquashY(squash), pivot = seat.center) {
        drawRoundRect(
            color = color,
            topLeft = seat.topLeft,
            size = seat.size,
            cornerRadius = CornerRadius(seat.height / 2f),
        )
    }
}

private fun chipStretchX(squash: Float): Float = 1f + squash * ChipPopStretch

private fun chipSquashY(squash: Float): Float = 1f - squash * ChipPopSquash

/**
 * Label, press wash and seat report. The chip owns no fill of its own — [onSeat]
 * publishes the bounds the row paints one for, measured off a node that sits above the
 * kick's layer so a deforming chip can never republish its own seat.
 */
@Composable
private fun Chip(
    text: String,
    active: Boolean,
    host: State<LayoutCoordinates?>,
    onSeat: (Rect) -> Unit,
    squash: () -> Float,
    onTap: () -> Unit,
) {
    val colors = LocalFlickColors.current
    val reduceMotion = rememberReduceMotion()
    val motionScheme = MaterialTheme.motionScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // The label rides the travelling fill, so its ink must not resolve before the fill
    // has arrived under it — a slower effects spec, never a spatial one.
    val ink = animateColorAsState(
        targetValue = if (active) colors.onInverseSurface else colors.onPrimaryContainer,
        animationSpec = Motion.orSnap(reduceMotion, motionScheme.slowEffectsSpec<Color>()),
        label = "chip ink",
    )
    // Material's ripple dilutes whatever ink it is handed, and on this palette that
    // lands on a pill as a grey blob. The chip answers a touch with the brand tint that
    // reads against its own fill instead: the pale blue on the selected chip's dark
    // pill, the saturated one on the pale rest pill.
    val wash = animateFloatAsState(
        targetValue = if (pressed) ChipPressWashAlpha else 0f,
        animationSpec = Motion.orSnap(reduceMotion, motionScheme.fastEffectsSpec<Float>()),
        label = "chip press wash",
    )
    val washColor = if (active) colors.primaryFixed else colors.primary
    val label = remember(ink) { ColorProducer { ink.value } }

    Box(
        modifier = Modifier
            .onGloballyPositioned { coordinates ->
                host.value?.let { onSeat(it.localBoundingBoxOf(coordinates, clipBounds = false)) }
            }
            .heightIn(min = 48.dp)
            .graphicsLayer {
                // Read in the layer block, so the kick repaints one chip rather than
                // recomposing the row it sits in.
                val p = squash()
                scaleX = chipStretchX(p)
                scaleY = chipSquashY(p)
            }
            .drawBehind {
                val alpha = wash.value
                if (alpha > 0f) {
                    drawRoundRect(
                        color = washColor,
                        cornerRadius = CornerRadius(size.height / 2f),
                        alpha = alpha,
                    )
                }
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onTap,
            )
            // Merged on the outer node so it wins the collapse: the chips are one
            // exclusive axis and TalkBack must not announce three independent toggles.
            .semantics(mergeDescendants = true) { selected = active }
            .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        // The ink is handed over as a producer rather than as a style, so the tint
        // animation resolves at draw time and never recomposes the label.
        BasicText(text = text, style = FlickText.labelMedium, color = label)
    }
}

@Composable
private fun BandAdvisory(revealOrigin: RevealOrigin, onClick: () -> Unit) {
    val colors = LocalFlickColors.current
    val interaction = remember { MutableInteractionSource() }
    val label = stringResource(R.string.a11y_library_band_advisory)
    val copy = buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) {
            append(stringResource(R.string.advisory_band_title))
        }
        append(" ")
        append(stringResource(R.string.advisory_band_body))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .revealOrigin(revealOrigin)
            .clip(RoundedCornerShape(FlickCorners.warning))
            .background(colors.caution)
            .clickable(
                interactionSource = interaction,
                indication = flickRipple(colors.onCaution),
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
            }
            .padding(horizontal = 17.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Icon(
            FlickIcons.Warning,
            contentDescription = null,
            tint = colors.onCaution,
            modifier = Modifier.size(22.dp),
        )
        Text(text = copy, style = FlickText.bodySmall.copy(color = colors.onCaution))
    }
}

@Composable
private fun Note(text: String) {
    val colors = LocalFlickColors.current
    Text(
        text = text,
        style = FlickText.monoEyebrow.copy(color = colors.onSurfaceFaint),
        modifier = Modifier.padding(vertical = 18.dp),
    )
}

@Composable
private fun FilterEmpty(filter: LibFilter) {
    val colors = LocalFlickColors.current
    Column(Modifier.fillMaxWidth().padding(vertical = 26.dp)) {
        Text(
            // Only a quality chip can empty a library that is not itself empty, so
            // "All" never reaches here.
            text = stringResource(
                if (filter == LibFilter.FULL_HD) {
                    R.string.library_empty_filter_1080p
                } else {
                    R.string.library_empty_filter_4k
                },
            ),
            style = FlickText.titleMedium.copy(color = colors.onSurface),
        )
        Text(
            text = stringResource(R.string.library_empty_filter_body),
            style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun LibraryTile(
    item: MediaItem,
    imageLoader: ImageLoader,
    compact: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    val context = LocalContext.current
    // The badge is the only thing on this screen that still needs a dynamic range, and
    // it needs it per tile. MediaProbe memoizes by uri, so a tile recomposed on scroll
    // costs a map lookup rather than a second container parse.
    val hdr by produceState(initialValue = HdrType.NONE, item.uri) {
        value = MediaProbe.detectHdr(context, item.uri)
    }
    VideoTile(
        item = item,
        hdr = hdr,
        imageLoader = imageLoader,
        compact = compact,
        onClick = onClick,
        modifier = modifier,
        sharedScope = sharedScope,
        animatedScope = animatedScope,
    )
}

@Composable
private fun EmptyState(
    controller: FlickController,
    connectedTv: PairedTv?,
    castingItem: MediaItem?,
    signal: State<SignalInfo>,
    bottomClearance: Dp,
    onChoose: () -> Unit,
) {
    val colors = LocalFlickColors.current
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = bottomClearance),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LinkPill(
            controller = controller,
            connectedTv = connectedTv,
            castingItem = castingItem,
            signal = signal,
        )
        Spacer(Modifier.height(34.dp))
        Box(
            Modifier
                .size(104.dp)
                .shadow(18.dp, CircleShape, clip = false, ambientColor = PrimaryShadow, spotColor = PrimaryShadow)
                .clip(CircleShape)
                .background(colors.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            FlickMark(modifier = Modifier.size(56.dp))
        }
        Spacer(Modifier.height(22.dp))
        Text(
            text = stringResource(R.string.empty_title),
            style = FlickText.headlineMedium.copy(color = colors.onSurface),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.empty_body),
            style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        FlickPrimaryButton(
            text = stringResource(R.string.empty_choose),
            onClick = onChoose,
            modifier = Modifier.width(240.dp),
        )
        if (connectedTv != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.empty_tv_ready, connectedTv.name),
                style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The mock's 412 dp frame lands two 179 dp columns; keeping that as a minimum lets a
 * 360 dp phone still show two and a rotated one reflow to five.
 */
private val TileMinWidth = 150.dp

/** The face arriving grows into place rather than appearing at full size. */
private const val PillSwapScale = 0.9f

// Entrance stagger: one step per tile up to the twelfth, which is roughly two screens
// on the widest column count — beyond that the sequence reads as loading, not arrival.
private const val StaggerStepMs = 35L
private const val StaggerCapIndex = 12
private val StaggerRiseDp = 18.dp

// Long enough for the capped sequence plus its settle. After this the grid is a grid:
// a tile the lazy list recomposes on scroll has not just arrived.
private const val StaggerWindowMs = 1_200L

// The reflow the grid answers a chip tap with. Tighter and shallower than the entrance
// on purpose — it is a rearrangement the user just asked for, not an arrival, and the
// tiles that survived the switch are already sliding under it.
private const val ReflowStepMs = 20L
private const val ReflowCapIndex = 8
private const val ReflowFromAlpha = 0.35f
private const val ReflowFromScale = 0.94f
private const val ReflowWindowMs = 700L

// The chip kick. The stretch is wider than the squash is short so the pill reads as
// pulled rather than merely scaled, and the spring's overshoot through zero supplies
// the counter-pose without a second animation.
private const val ChipPopStretch = 0.10f
private const val ChipPopSquash = 0.08f

// Press wash on one chip. Sits just above the pill it has to read against, and low
// enough that the label never loses contrast against either fill.
private const val ChipPressWashAlpha = 0.18f
