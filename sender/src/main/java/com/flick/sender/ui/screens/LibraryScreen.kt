package com.flick.sender.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateBounds
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import com.flick.sender.NetworkUtils
import com.flick.sender.R
import com.flick.sender.media.LibraryFolders
import com.flick.sender.media.LibraryScope
import com.flick.sender.media.MediaAccess
import com.flick.sender.media.MediaLibraryAction
import com.flick.sender.media.MediaLibraryActionPolicy
import com.flick.sender.media.MediaProbe
import com.flick.sender.media.PlaybackMediaFingerprint
import com.flick.sender.media.PlaybackProgressState
import com.flick.sender.media.resumeProgress
import com.flick.sender.model.HdrType
import com.flick.sender.model.MediaItem
import com.flick.sender.model.PlaybackPhase
import com.flick.sender.net.FlickController
import com.flick.sender.net.PairedTv
import com.flick.sender.ui.Format
import com.flick.sender.ui.displayName
import com.flick.sender.ui.components.AdvisoryCard
import com.flick.sender.ui.components.AdvisoryTone
import com.flick.sender.ui.components.FlickMark
import com.flick.sender.ui.components.FlickPrimaryButton
import com.flick.sender.ui.components.LibraryFolderChip
import com.flick.sender.ui.components.LibraryFolderSheet
import com.flick.sender.ui.components.LiveDot
import com.flick.sender.ui.components.LocalNavMetrics
import com.flick.sender.ui.components.VideoTile
import com.flick.sender.ui.components.navBottomClearance
import com.flick.sender.ui.components.rememberVideoImageLoader
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
import com.flick.sender.ui.theme.rememberPressAmount
import com.flick.sender.ui.theme.rememberReduceMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * Everything about the grid that is a POSITION in it rather than a fact about the library.
 * Held by the shell because routes cross-dissolve: the `AnimatedContent` that swaps them
 * disposes the surface it left, so anything remembered inside this screen is gone by the
 * time the user comes back from a detail sheet — the grid position and search state survive,
 * and the tile a poster is flying home to is still composed for it to land on.
 *
 * The library itself is not in here. Which files exist and which folder is in force are the
 * controller's, and re-deriving them on arrival is the point of the reload this screen asks
 * for every time it is shown.
 */
@Stable
internal class LibraryUiState(val grid: LazyGridState) {
    var searchOpen by mutableStateOf(false)
    var searchQuery by mutableStateOf("")

    /**
     * The field owes the user focus and a keyboard, and has not given them yet.
     *
     * Held here rather than inside the control because the control is a row of the lazy
     * grid: scrolling the results disposes it, which is the right moment for the keyboard
     * to go away — and a latch that had been disposed with it would raise the keyboard
     * again, unasked, every time that row scrolled back into view.
     */
    var searchFocusPending by mutableStateOf(false)

    /**
     * The entrance belongs to the library ARRIVING — the first paint after MediaStore
     * answers — and a route change is not that. Spent once for as long as the shell holds
     * this, so coming back from a detail sheet restores a grid rather than replaying one.
     */
    var entrancePlayed by mutableStateOf(false)

    /**
     * The folded search index, built once per library rather than once per visit.
     *
     * It lives here, above the route switch, for a measured reason. Building it folds every
     * name twice through Unicode normalization and runs a filename parse behind each one, and
     * a `remember` inside the screen is discarded the moment the tab changes — so returning
     * to the library rebuilt the whole thing on the UI thread, inside the very frame the tap
     * had already given to composing the route. On the verified phone that frame ran 48 ms
     * against a 120 Hz panel's 8.3 ms, and the navigation pill — whose spring advances in the
     * same Choreographer callback — stopped dead for five frames in the middle of its travel.
     * Devices to Settings, the one pairing that never touches this, stayed at 7 ms.
     */
    val searchIndex = LibrarySearchIndexMemo<MediaItem> { it.name }
}

@Composable
internal fun rememberLibraryUiState(): LibraryUiState {
    val grid = rememberLazyGridState()
    return remember(grid) { LibraryUiState(grid) }
}

/**
 * S3 — the library. A gallery, not a file browser: real MediaStore videos.
 *
 * Internal because [LibraryUiState] is: the grid's position is the shell's to hold, and
 * only the shell composes this.
 */
@Composable
internal fun LibraryScreen(
    controller: FlickController,
    supportAvailable: Boolean,
    onOpenSupport: () -> Unit,
    onRequestVideoPermission: () -> Unit,
    uiState: LibraryUiState,
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    val colors = LocalFlickColors.current
    val motionScheme = MaterialTheme.motionScheme
    val reduceMotion = rememberReduceMotion()
    // The scope and its source rows arrive together; search is applied locally below so
    // changing a query never asks MediaStore to walk the library again.
    val library by controller.library.collectAsState()
    val folderScope = library.scope
    val scoped = library.scoped
    val loading by controller.libraryLoading.collectAsState()
    val mediaAccess by controller.mediaAccess.collectAsState()
    val connectedTv by controller.connectedTv.collectAsState()
    val castingItem by controller.castingItem.collectAsState()
    val showSupportInvitation by controller.showSupportInvitation.collectAsState()
    val supportInvitationVisible = supportAvailable && showSupportInvitation
    // Empty until a receiver actually refuses a file, and back to empty on the next
    // launch: this is a witness list, not a verdict the library carries around.
    val unplayable by controller.unplayableFiles.collectAsState()
    // The same witness list one step milder: these films played, and the TV had no decoder
    // for their sound. Also empty until it happens, and also empty again next launch.
    val silentAudio by controller.silentAudioFiles.collectAsState()
    // Kept as State and deliberately never unwrapped at this scope. A live cast writes a
    // checkpoint every ~5 s, and reading the map here would put the whole visible grid
    // through a recomposition for a figure that moved on exactly one of its tiles. Each
    // tile derives its own value instead — see [LibraryTile].
    val playbackProgress = controller.playbackProgress.collectAsState()
    val imageLoader = rememberVideoImageLoader()
    // State, not a value: the 2 s telemetry poll must stop at the pill that shows it
    // rather than rebuilding the whole grid.
    val signal = rememberSignalState()
    val wifiLinkUp = rememberWifiLinkUp(signal)
    val on24GHz by remember(signal) { derivedStateOf { signal.value.on24GHz } }
    val compactTiles = isCompactHeight(LocalConfiguration.current.screenHeightDp)
    val mediaAction = MediaLibraryActionPolicy.forAccess(mediaAccess)

    // The dock docks above the nav while a cast is live, so the last row of the grid
    // has to clear both of them, not just the nav — and the nav's own height is measured
    // rather than assumed, because the label's line box grows with the font scale.
    val bottomClearance = navBottomClearance(
        barHeight = LocalNavMetrics.current.height,
        dockLive = castingItem != null,
    )

    var choosingFolder by remember { mutableStateOf(false) }

    // The entrance plays once, on the first paint after MediaStore resolves. The window
    // closes it so scrolling back to the top cannot replay it on tiles the lazy grid
    // recomposes, and the latch it spends is the shell's so a route change cannot replay it.
    var staggerArmed by remember { mutableStateOf(false) }
    LaunchedEffect(loading, scoped.isEmpty(), reduceMotion) {
        if (uiState.entrancePlayed || reduceMotion || loading || scoped.isEmpty()) return@LaunchedEffect
        uiState.entrancePlayed = true
        staggerArmed = true
        delay(StaggerWindowMs)
        staggerArmed = false
    }

    // Deliberately not snapshot state: it is written and read in the same composition,
    // and observing it would recompose the grid a second time on the frame it flips.
    val emptyLatch = remember { EmptyLatch() }
    // The WHOLE library decides this, never the scoped one: a folder the user narrowed
    // to must not be able to raise the screen that says Flick has not been let into the
    // gallery, or offer to re-open system selection for a library that is right there.
    emptyLatch.shown = libraryEmptyShown(
        access = mediaAccess,
        itemCount = library.items.size,
        loading = loading,
        showing = emptyLatch.shown,
    )
    if (emptyLatch.shown) {
        EmptyState(
            controller = controller,
            connectedTv = connectedTv,
            castingItem = castingItem,
            signal = signal,
            wifiLinkUp = wifiLinkUp,
            mediaAccess = mediaAccess,
            bottomClearance = bottomClearance,
            onChoose = onRequestVideoPermission,
            onRefresh = { controller.refreshMediaLibrary() },
            showSupportInvitation = supportInvitationVisible,
            onOpenSupport = onOpenSupport,
            onDismissSupportInvitation = controller::dismissSupportInvitation,
        )
        return
    }

    // Folder scope comes first. Search only narrows that already-scoped set, never the
    // whole library, and a blank query returns the same list object without allocation.
    //
    // The two remembers are one decision: folding a name costs Unicode normalization and a
    // parse of the filename behind the title on the tile, and doing that to the whole
    // library on every keystroke is a phone dropping frames under the user's own typing.
    // Only the index depends on the library, and only the cheap half depends on the query.
    val searchIndex = uiState.searchIndex.of(scoped)
    val searchResults = remember(searchIndex, uiState.searchQuery) {
        searchIndex.matching(uiState.searchQuery)
    }

    val closeSearch = {
        uiState.searchOpen = false
        uiState.searchQuery = ""
        uiState.searchFocusPending = false
    }
    // Back belongs to the screen, not to the control that raised the field: that control is
    // a row inside the lazy grid, so scrolling down through the results disposes it — and a
    // handler living there would hand Back to the shell, which leaves the library, while
    // search is still open and still filtering what is on screen.
    BackHandler(enabled = uiState.searchOpen, onBack = closeSearch)

    // The pull's own claim on the indicator, which is not the same claim as `loading`:
    // the library also reloads on a permission grant and on every return to this screen,
    // and an indicator that dropped out of the top of the grid unasked would read as the
    // app deciding to refresh itself.
    var pulled by remember { mutableStateOf(false) }
    val pullState = rememberPullToRefreshState()
    LaunchedEffect(pulled) {
        if (!pulled) return@LaunchedEffect
        coroutineScope {
            // The floor and the read run TOGETHER and the indicator goes down when the
            // later of the two is done, so a long read is never cut short and a short one
            // is never a flash. Started first, so it is counted from the release rather
            // than from whenever the read happened to finish.
            //
            // A warm MediaStore answers a re-query in a frame or two. The indicator was
            // therefore appearing and being taken away inside the same handful of frames —
            // long enough to be a flicker at the top of the grid, nowhere near long enough
            // to read as a loader, so a pull that worked perfectly looked like a pull that
            // had not registered. The floor is a claim about legibility, not about the
            // read: it is roughly one beat of the indicator's own shape morph, which is the
            // shortest showing that resolves as an animation rather than a blink.
            val floor = launch { delay(RefreshFloorMs) }
            // The pull owns the indicator until the read it asked for is over, and
            // `libraryLoading` IS that read: a timer would put the indicator down while
            // MediaStore was still walking a large gallery, and would raise it again for
            // the next one. The window bounds only the wait for that read to START — a
            // refresh with no access to run under raises nothing, and must not leave the
            // indicator up for ever waiting for it.
            withTimeoutOrNull(RefreshStartWindowMs) { controller.libraryLoading.first { it } }
            controller.libraryLoading.first { !it }
            floor.join()
        }
        pulled = false
    }

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = pulled,
            onRefresh = {
                pulled = true
                // The controller's one load path, gate and all: a refresh that a newer
                // read overtakes is discarded there rather than publishing behind it.
                controller.refreshMediaLibrary()
            },
            state = pullState,
            indicator = { LibraryRefreshIndicator(state = pullState, refreshing = pulled) },
            // The canvas is painted before the inset, as the grid painted it, so it still
            // reaches under the status bar; the pull region begins below it, which is
            // where the indicator has to come to rest to be seen at all.
            modifier = Modifier
                .fillMaxSize()
                .background(colors.canvas)
                .statusBarsPadding(),
        ) {
            LazyVerticalGrid(
                // Adaptive rather than a fixed pair: rotation and foldables widen the row, and
                // a fixed two-column split would stretch each 16:9 still into a letterbox sliver.
                columns = GridCells.Adaptive(minSize = TileMinWidth),
                state = uiState.grid,
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .semantics { testTagsAsResourceId = true }
                    .testTag(LibraryGridTestTag),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = bottomClearance),
                horizontalArrangement = Arrangement.spacedBy(13.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                fullWidth {
                    Header(
                        mediaAction = mediaAction,
                        onMediaAction = {
                            when (mediaAction) {
                                MediaLibraryAction.SELECT_MORE -> onRequestVideoPermission()
                                MediaLibraryAction.REFRESH -> controller.refreshMediaLibrary()
                                MediaLibraryAction.HIDDEN -> Unit
                            }
                        },
                    )
                }
                fullWidth {
                    LinkPill(
                        controller = controller,
                        connectedTv = connectedTv,
                        castingItem = castingItem,
                        signal = signal,
                        wifiLinkUp = wifiLinkUp,
                    )
                }
                if (supportInvitationVisible) {
                    fullWidth {
                        SupportInvitationCard(
                            onOpenSupport = onOpenSupport,
                            onDismiss = controller::dismissSupportInvitation,
                        )
                    }
                }
                fullWidth {
                    LibraryControls(
                        chooserOffered = LibraryFolders.chooserOffered(
                            library.folders,
                            folderScope,
                            library.items.size,
                        ),
                        scope = folderScope,
                        searchOpen = uiState.searchOpen,
                        query = uiState.searchQuery,
                        focusPending = uiState.searchFocusPending,
                        onChooseFolder = { choosingFolder = true },
                        onOpenSearch = {
                            uiState.searchOpen = true
                            uiState.searchFocusPending = true
                        },
                        onQueryChange = { uiState.searchQuery = it },
                        onFocusHandled = { uiState.searchFocusPending = false },
                        onCloseSearch = closeSearch,
                    )
                }
                if (on24GHz) {
                    fullWidth {
                        // The advisory and its fix live on the same surface now: the banner is a
                        // shortcut to the Settings seat rather than a sheet of its own.
                        BandAdvisory(onClick = { controller.openSettings() })
                    }
                }
                when {
                    // Silent under a pull: the indicator the finger is holding already
                    // says a read is running, and a second row inserted above the tiles
                    // would push the grid down under that same finger. Every other
                    // refresh still gets the line, because nothing else on screen says
                    // it. What the row falls through to is what was already showing.
                    loading && !pulled -> fullWidth { Note(stringResource(R.string.library_loading)) }
                    // A folder that is gone and a folder that is showing nothing are
                    // different facts about the same choice, and neither is silence: the
                    // stored choice survives both until the user takes the offered way out.
                    folderScope is LibraryScope.Missing -> fullWidth {
                        // Under a partial grant the cause is not a mystery and must not be
                        // reported as one: the folder is outside the selection, so the repair
                        // is to widen that selection rather than to give the folder up.
                        if (mediaAccess.canReselect) {
                            FolderHidden(
                                name = folderScope.name,
                                onSelectMore = onRequestVideoPermission,
                            )
                        } else {
                            FolderMissing(
                                name = folderScope.name,
                                onShowAll = { controller.chooseLibraryFolder(null) },
                            )
                        }
                    }
                    // A folder can become empty between the chooser opening and the next
                    // MediaStore read. This condition deliberately precedes search's empty
                    // state: it describes the scope itself, not a query within it.
                    scoped.isEmpty() && folderScope is LibraryScope.Folder ->
                        fullWidth { FolderEmpty(folderScope.name) }
                    uiState.searchQuery.trim().isNotEmpty() && searchResults.isEmpty() ->
                        fullWidth { SearchEmpty() }
                }
                itemsIndexed(
                    searchResults,
                    key = { _, item -> item.id },
                    // Stated: handed a scrolled-away section's slot, a tile is rebuilt from
                    // nothing — a fresh HDR probe and a fresh image request — on a frame
                    // that was meant to be a placement.
                    contentType = { _, _ -> TileContent },
                ) { index, item ->
                    LibraryTile(
                        item = item,
                        imageLoader = imageLoader,
                        compact = compactTiles,
                        unplayable = unplayable.containsKey(item.uriKey),
                        silentAudio = silentAudio.containsKey(item.uriKey),
                        progress = playbackProgress,
                        onClick = { controller.openDetail(item) },
                        sharedScope = sharedScope,
                        animatedScope = animatedScope,
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = motionScheme.defaultEffectsSpec(),
                                placementSpec = motionScheme.defaultSpatialSpec(),
                                fadeOutSpec = motionScheme.fastEffectsSpec(),
                            )
                            .staggeredEntrance(index = index, armed = staggerArmed),
                    )
                }
            }
        }
        if (choosingFolder) {
            LibraryFolderSheet(
                folders = library.folders,
                scope = folderScope,
                // The whole library, because "All videos" is what leaving the folder restores.
                allCount = library.items.size,
                // Only the scope changes here. Removing the sheet is onDismiss's, which
                // the sheet answers once its exit has actually carried it off the window.
                onChoose = { folder ->
                    controller.chooseLibraryFolder(folder)
                },
                onDismiss = { choosingFolder = false },
            )
        }
    }
}

/**
 * What a pull down the library looks like: the same Expressive shape-morph the pairing
 * handshake and the TV's own loader use, so a wait for MediaStore is not a different kind
 * of wait from a wait for the TV.
 *
 * [state] carries the drag, and the indicator reads it in its own layer — the grid behind
 * it is not recomposed by a finger moving 40 dp.
 *
 * Under reduce motion the morph is replaced rather than merely slowed: it is a continuous
 * animation that never reaches an end state, which is exactly what that setting is asking
 * not to be shown. The container still travels with the finger, because that is the
 * gesture answering rather than a decoration.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BoxScope.LibraryRefreshIndicator(state: PullToRefreshState, refreshing: Boolean) {
    val colors = LocalFlickColors.current
    val seat = Modifier.align(Alignment.TopCenter)
    if (rememberReduceMotion()) {
        PullToRefreshDefaults.IndicatorBox(
            state = state,
            isRefreshing = refreshing,
            modifier = seat,
            containerColor = colors.surfaceRaised,
        ) {
            Box(
                Modifier
                    .size(RefreshRestingSize)
                    .clip(CircleShape)
                    .background(colors.primary),
            )
        }
    } else {
        PullToRefreshDefaults.LoadingIndicator(
            state = state,
            isRefreshing = refreshing,
            modifier = seat,
            containerColor = colors.surfaceRaised,
            color = colors.primary,
        )
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
        // Modulated per draw op rather than through a buffer. Under the default strategy an
        // alpha below 1 has the whole tile rasterized offscreen and composited back — a
        // megabyte and a half of RGBA per tile per frame, on up to twelve tiles at once, on
        // the one frame budget in the app's life that is already carrying a MediaStore walk
        // and a 4K frame extract. That buffer is also sized to the tile's bounds, which cuts
        // off the shadow the poster deliberately casts outside them.
        compositingStrategy = CompositingStrategy.ModulateAlpha
        val p = progress.value
        // Clamped: the spatial spring overshoots by design and opacity must not.
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * StaggerRiseDp.toPx()
    }
}

/** Section blocks span both columns and carry the extra 5 dp that widens the 13 dp grid gap. */
private fun LazyGridScope.fullWidth(
    content: @Composable () -> Unit,
) = item(span = { GridItemSpan(maxLineSpan) }, contentType = SectionContent) {
    Box(Modifier.padding(bottom = 5.dp)) { content() }
}

/**
 * The brand lockup, shared by the populated library and its empty state so the mark
 * cannot move when the first video arrives. The 48 dp floor is what the action button
 * imposes on the populated header — that header always carries one, because it is only
 * reached with access granted — so the empty state has to claim the same height even
 * though it carries nothing beside the wordmark.
 */
@Composable
private fun Wordmark(trailing: @Composable RowScope.() -> Unit = {}) {
    val colors = LocalFlickColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
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
        trailing()
    }
}

@Composable
private fun Header(
    mediaAction: MediaLibraryAction,
    onMediaAction: () -> Unit,
) {
    val colors = LocalFlickColors.current
    Wordmark {
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
    }
}

/** Which of the pill's four honest states is showing. */
internal enum class LinkPillState { CASTING, PAIRED, UNPAIRED, OFFLINE }

/**
 * A pairing is a stored credential, not a live link: it survives the network the link
 * runs over going away, and with Wi-Fi off the phone would go on calling an unreachable
 * TV "Ready". [wifiLinkUp] is the one thing this phone actually measured — its own
 * Wi-Fi link — so the fourth face says that and nothing about the TV, which from here
 * is unknowable. A cast in flight outranks it: bytes moving are the better evidence.
 */
internal fun linkPillState(paired: Boolean, casting: Boolean, wifiLinkUp: Boolean): LinkPillState = when {
    !paired -> LinkPillState.UNPAIRED
    casting -> LinkPillState.CASTING
    !wifiLinkUp -> LinkPillState.OFFLINE
    else -> LinkPillState.PAIRED
}

/**
 * Whether this phone's Wi-Fi is up — every value this returns is something that was
 * actually read, which is why [linkPillState] may treat a `false` as a fact.
 *
 * [SignalInfo.hasLink] alone cannot carry that: [rememberSignalState] publishes its
 * record before its first poll has run, so a null band there means both "nothing has
 * looked yet" and "Wi-Fi is down". The pill states the second one, and this screen is
 * rebuilt on every entry, so keying on it would open the library amber on a healthy
 * phone every single time. The first answer is therefore read inline, on the frame the
 * pill first needs it; a `true` from the shared poll is then proof and costs nothing,
 * and only its ambiguous `false` is asked again here.
 */
@Composable
private fun rememberWifiLinkUp(signal: State<SignalInfo>): State<Boolean> {
    val context = LocalContext.current
    val confirmed = remember(context) { mutableStateOf(NetworkUtils.getWifiLinkInfo(context) != null) }
    LaunchedEffect(context, signal) {
        // Collected off composition on purpose: reading the shared record at this
        // function's scope would put the whole grid behind the pill on the 2 s poll.
        snapshotFlow { signal.value.hasLink }.collectLatest { reported ->
            if (reported) {
                confirmed.value = true
                return@collectLatest
            }
            while (true) {
                confirmed.value = withContext(Dispatchers.IO) { NetworkUtils.getWifiLinkInfo(context) != null }
                delay(WifiLinkRecheckMs)
            }
        }
    }
    return confirmed
}

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
 * One slot, four honest states: a cast in flight, paired but idle, paired with no link
 * to reach the TV over, nothing paired. It is a single pill that changes face rather
 * than four pills that replace each other. The wording follows the TV's own reported
 * phase — `castingItem` stays set through pause and end — and the throughput number
 * only replaces the band once the server is actually writing bytes.
 */
@Composable
private fun LinkPill(
    controller: FlickController,
    connectedTv: PairedTv?,
    castingItem: MediaItem?,
    signal: State<SignalInfo>,
    wifiLinkUp: State<Boolean>,
) {
    val colors = LocalFlickColors.current
    val motionScheme = MaterialTheme.motionScheme
    val reduceMotion = rememberReduceMotion()
    // Unwrapped here rather than by the caller: the answer changes only when Wi-Fi does,
    // and this is the deepest scope that needs it, so the grid behind it never rebuilds.
    val state = linkPillState(
        paired = connectedTv != null,
        casting = castingItem != null,
        wifiLinkUp = wifiLinkUp.value,
    )

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
        state == LinkPillState.OFFLINE -> stringResource(R.string.library_offline_pill, tvName)
        else -> stringResource(R.string.library_ready_pill, tvName)
    }
    val model = LinkPillModel(state = state, line = line, playing = phase == PlaybackPhase.PLAYING)

    val restoreLabel = castingItem?.let { stringResource(R.string.a11y_restore_now_playing, it.displayName()) }
    val connectLabel = stringResource(R.string.a11y_open_connect)
    val description = when (state) {
        LinkPillState.CASTING -> restoreLabel
        LinkPillState.PAIRED, LinkPillState.OFFLINE -> null
        LinkPillState.UNPAIRED -> connectLabel
    }
    val action: (() -> Unit)? = when (state) {
        LinkPillState.CASTING -> ({ controller.restoreNowPlaying() })
        // Offline is not a control: nothing in this app can put the phone back on Wi-Fi,
        // and a tap that opened Connect would only start a scan that cannot find anything.
        LinkPillState.PAIRED, LinkPillState.OFFLINE -> null
        LinkPillState.UNPAIRED -> ({ controller.openConnect() })
    }

    val ink = when (state) {
        // The caution hue is a warm mid-tone in both assignments and clears its floor as ink
        // on neither, so the offline face inverts the way every other caution surface in the
        // app does: solid fill, dark ink.
        LinkPillState.OFFLINE -> colors.onCaution
        LinkPillState.UNPAIRED -> colors.onPrimaryContainer
        else -> colors.onPrimary
    }
    val container by animateColorAsState(
        targetValue = when (state) {
            LinkPillState.OFFLINE -> colors.caution
            LinkPillState.UNPAIRED -> colors.primaryContainer
            else -> colors.primary
        },
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
                    when (face.state) {
                        LinkPillState.UNPAIRED -> {
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
                        }
                        // No dot and no telemetry: the dot is the mark of a live link and
                        // there is no band to report while Wi-Fi is not the transport.
                        LinkPillState.OFFLINE -> {
                            Icon(
                                FlickIcons.Warning,
                                contentDescription = null,
                                tint = colors.onCaution,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = face.line,
                                style = FlickText.labelMedium.copy(color = colors.onCaution),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        LinkPillState.CASTING, LinkPillState.PAIRED -> {
                            // The dot sits on the action fill, which is a deep blue in light
                            // and a gold in dark, so the ground picks the mark. Only the
                            // inverse accent survives the gold — the ramp's pale tone is
                            // 1.08:1 there. On the blue both clear the 3:1 a mark needs and
                            // the pale tone is the brighter of the two, 5.24:1 against the
                            // inverse accent's 4.09:1, because a light set's inverse accent
                            // IS its amber and the amber is the darker end of that ramp.
                            val dot = if (colors.isLight) colors.sparkLight else colors.sparkInverse
                            LiveDot(
                                color = dot,
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
}

/**
 * TransferTelemetry only counts bytes this phone's server actually wrote, so a zero is
 * "nothing is moving", never a measured rate. The band is the honest stand-in — and
 * with no Wi-Fi link there is no band either, at which point the slot goes empty rather
 * than falling through to the generic "Wi-Fi" label, which would name a link that is
 * not up.
 */
@Composable
private fun RowScope.PillTelemetry(signal: State<SignalInfo>) {
    val colors = LocalFlickColors.current
    val live = signal.value
    val text = when {
        live.serving -> Format.megabits(live.throughputBitsPerSec)
        live.hasLink -> live.bandLabel()
        else -> null
    }
    if (text != null) {
        Text(
            text = text,
            style = FlickText.monoSmall.copy(color = colors.onPrimaryMuted),
        )
    }
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

/** A content-sized folder choice with search folding into the same, never-scrolling row. */
@Composable
private fun LibraryControls(
    chooserOffered: Boolean,
    scope: LibraryScope,
    searchOpen: Boolean,
    query: String,
    focusPending: Boolean,
    onChooseFolder: () -> Unit,
    onOpenSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
    onFocusHandled: () -> Unit,
    onCloseSearch: () -> Unit,
) {
    val colors = LocalFlickColors.current
    val reduceMotion = rememberReduceMotion()
    val motionScheme = MaterialTheme.motionScheme
    val motion = motionScheme.defaultSpatialSpec<Float>()
    val kickSpec = motionScheme.fastSpatialSpec<Float>()
    val settleSpec = motionScheme.defaultSpatialSpec<Float>()
    val progress = remember { Animatable(if (searchOpen) 1f else 0f) }
    val buttonKick = remember { Animatable(0f) }
    val animationScope = rememberCoroutineScope()
    val haptics = rememberFlickTouchHaptics()
    val searchInteraction = remember { MutableInteractionSource() }
    var openRequestPending by remember { mutableStateOf(false) }
    // The default spatial spring is allowed to ring around its target. Composition is a
    // separate lifetime from that geometry: a closing field stays composed until its own
    // animation reaches rest, and a reopened search cannot be disposed by the cancelled
    // close coroutine.
    var fieldComposed by remember { mutableStateOf(searchOpen) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(searchOpen) {
        if (!searchOpen) openRequestPending = false
    }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) buttonKick.snapTo(0f)
    }
    LaunchedEffect(searchOpen, reduceMotion) {
        val target = if (searchOpen) 1f else 0f
        if (searchOpen) fieldComposed = true
        if (reduceMotion) {
            progress.snapTo(target)
        } else {
            progress.animateTo(target, motion)
        }
        if (!searchOpen && progress.value == 0f) fieldComposed = false
    }
    val fieldPresent = searchOpen || fieldComposed
    // A closing field is still painted for its exit, but it cannot retain focus or become
    // a TalkBack stop after the user has asked to leave search.
    val fieldInteractive = searchOpen
    // Focus is taken once per OPEN, not once per composition of this row: the row is a lazy
    // grid item, so it is disposed and rebuilt by ordinary scrolling, and requesting focus
    // on every rebuild is a keyboard that reappears over the results whenever the user
    // scrolls back to the top of them.
    LaunchedEffect(searchOpen, fieldInteractive, focusPending) {
        if (searchOpen && fieldInteractive && focusPending) {
            focusRequester.requestFocus()
            keyboard?.show()
            onFocusHandled()
        }
    }
    // And released only by a field that was actually there to hold it, so a rebuild with
    // search closed cannot clear focus that belongs to something else on the screen.
    //
    // `fieldPresent` is a condition sampled at that transition and deliberately NOT a key:
    // the value the body reads is the one this composition computed, which is still true on
    // the frame search closes — `fieldComposed` does not drop until the exit animation
    // reaches rest, one composition later. Keying on it would instead run this a second
    // time when the field finally leaves, hiding a keyboard that went down with it.
    LaunchedEffect(searchOpen) {
        if (!searchOpen && fieldPresent) {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }

    val openDescription = stringResource(R.string.library_search_open)
    val closeDescription = stringResource(
        if (query.isNotEmpty()) R.string.library_search_clear else R.string.library_search_close,
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SearchControlSize)
            .clipToBounds()
            .clip(PillShape),
    ) {
        val buttonTravel = (maxWidth - SearchControlSize).coerceAtLeast(0.dp)
        val fieldWidth = (maxWidth - SearchControlSize - SearchControlGap).coerceAtLeast(0.dp)
        val folderWidthCap = libraryFolderWidthCap(maxWidth)

        if (chooserOffered) {
            LibraryFolderChip(
                scope = scope,
                onClick = onChooseFolder,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    // The chip hugs short names but never takes more than three quarters
                    // of the row. Its weighted label reserves the chevron before ellipsizing.
                    .widthIn(max = folderWidthCap)
                    .slideOutToStart { progress.value },
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(SearchControlSize)
                .offset {
                    val p = progress.value.coerceIn(0f, 1f)
                    IntOffset(x = (buttonTravel * (1f - p)).roundToPx(), y = 0)
                }
                // The removed quality chips answered a tap by stretching wider and
                // squashing shorter. Search keeps that exact restraint while its seat
                // travels; this layer never changes measurement or placement bounds.
                .graphicsLayer {
                    val kick = if (reduceMotion) 0f else buttonKick.value
                    scaleX = searchKickScaleX(kick)
                    scaleY = searchKickScaleY(kick)
                    translationY = -SearchKickLift.toPx() * kick.coerceIn(0f, 1f)
                }
                .clip(CircleShape)
                .background(colors.primaryContainer)
                .clickable(
                    enabled = !searchOpen && !openRequestPending,
                    interactionSource = searchInteraction,
                    indication = flickRipple(colors.onPrimaryContainer),
                    role = Role.Button,
                    onClick = {
                        // The local latch closes the tiny interval before the hoisted
                        // open state returns, so even an extreme double tap dispatches
                        // one open request and one haptic answer.
                        if (!searchOpen && !openRequestPending) {
                            openRequestPending = true
                            haptics.toggle(true)
                            if (!reduceMotion) {
                                animationScope.launch {
                                    buttonKick.animateTo(1f, kickSpec)
                                    buttonKick.animateTo(0f, settleSpec)
                                }
                            }
                            onOpenSearch()
                        }
                    },
                )
                .then(
                    if (searchOpen) {
                        Modifier.clearAndSetSemantics { }
                    } else {
                        Modifier.semantics { contentDescription = openDescription }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = FlickIcons.Search,
                contentDescription = null,
                tint = colors.onPrimaryContainer,
                modifier = Modifier
                    .size(21.dp)
                    .graphicsLayer {
                        val p = if (reduceMotion) 0f else progress.value.coerceIn(0f, 1f)
                        val arc = searchTravelArc(p)
                        val kick = if (reduceMotion) 0f else buttonKick.value
                        rotationZ = -SearchTravelTurnDegrees * arc + SearchKickTurnDegrees * kick
                        translationY = -SearchTravelLift.toPx() * arc
                    },
            )
        }

        if (fieldPresent) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                // The grid is already filtering under every character, so the action key has
                // nothing left to submit and is there to put the keyboard down over the
                // results it produced. Autocorrect is off because these are not words: a
                // half-typed title is exactly what this field is for, and an IME that
                // "fixes" it on the space bar retypes the user's query for them.
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                    },
                ),
                textStyle = FlickText.bodyMedium.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                modifier = Modifier
                    // This is always its final width; closed it is placed beyond the row,
                    // so a text field is never measured from zero during the morph.
                    .align(Alignment.CenterStart)
                    .width(fieldWidth)
                    .height(SearchControlSize)
                    .offset {
                        val p = progress.value.coerceIn(0f, 1f)
                        IntOffset(
                            x = (SearchControlSize + SearchControlGap + fieldWidth * (1f - p)).roundToPx(),
                            y = 0,
                        )
                    }
                    .focusRequester(focusRequester)
                    .focusProperties { canFocus = fieldInteractive }
                    .then(
                        if (fieldInteractive) Modifier else Modifier.clearAndSetSemantics {
                            hideFromAccessibility()
                        },
                    ),
                decorationBox = { innerTextField ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(PillShape)
                            .background(colors.surfaceRaised)
                            .padding(start = 16.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.library_search_placeholder),
                                    style = FlickText.bodyMedium.copy(color = colors.onSurfaceFaint),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            innerTextField()
                        }
                        Box(
                            modifier = Modifier
                                .size(SearchControlSize)
                                .clip(CircleShape)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = flickRipple(colors.onSurface),
                                    role = Role.Button,
                                    onClick = {
                                        if (query.isEmpty()) {
                                            onCloseSearch()
                                        } else {
                                            onQueryChange("")
                                            // Clearing is the start of the next query, never
                                            // the end of searching: the tap lands on a
                                            // focusable target of its own, so the field has
                                            // to be handed what it just lost.
                                            focusRequester.requestFocus()
                                            keyboard?.show()
                                        }
                                    },
                                )
                                .semantics { contentDescription = closeDescription },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = FlickIcons.Close,
                                contentDescription = null,
                                tint = colors.onSurface,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                },
            )
        }
    }
}

/** Maximum folder width, leaving the fixed search target and its gap intact on tiny rows. */
internal fun libraryFolderWidthCap(rowWidth: Dp): Dp = minOf(
    rowWidth * FolderClosedWidthFraction,
    (rowWidth - SearchControlSize - SearchControlGap).coerceAtLeast(0.dp),
)

/** The old quality-chip deformation: wide and short at the strike, springing through rest. */
internal fun searchKickScaleX(amount: Float): Float = 1f + amount * SearchKickStretch

internal fun searchKickScaleY(amount: Float): Float = 1f - amount * SearchKickSquash

/** A zero-at-each-end arc for the icon's small turn while the button changes seats. */
internal fun searchTravelArc(progress: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    return 4f * p * (1f - p)
}

/** Places the real child, so hit and accessibility bounds leave with the folder chip. */
private fun Modifier.slideOutToStart(progress: () -> Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        val p = progress().coerceIn(0f, 1f)
        placeable.placeRelative(x = -(placeable.width * p).roundToInt(), y = 0)
    }
}

@Composable
private fun BandAdvisory(onClick: () -> Unit) {
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
private fun SearchEmpty() {
    val colors = LocalFlickColors.current
    Column(Modifier.fillMaxWidth().padding(vertical = 26.dp)) {
        Text(
            text = stringResource(R.string.library_empty_search_title),
            style = FlickText.titleMedium.copy(color = colors.onSurface),
        )
        Text(
            text = stringResource(R.string.library_empty_search_body),
            style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * The chosen folder is not among the ones MediaStore reports any more, and with full
 * access nothing here can say why. It is named rather than quietly dropped: the library
 * the user set up is not the library they are looking at, and only they can decide to
 * give that choice up — which is why the single action here is the only thing that
 * discards it.
 *
 * INFO rather than CAUTION so it can share a screen with the amber band advisory
 * without the two shouting over each other.
 */
@Composable
private fun FolderMissing(name: String, onShowAll: () -> Unit) {
    AdvisoryCard(
        icon = FlickIcons.Warning,
        title = stringResource(R.string.library_folder_missing_title, name),
        body = stringResource(R.string.library_folder_missing_body),
        tone = AdvisoryTone.INFO,
        primaryLabel = stringResource(R.string.library_folder_missing_action),
        onPrimary = onShowAll,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The same absence under a partial grant, where it has exactly one cause: the folder is
 * on the phone and the selection the user gave Flick holds nothing from it. Naming that
 * is the difference between an accusation and an instruction — and the action reopens
 * system selection, which is the only repair that keeps the folder. Giving it up is
 * still offered, in the place it always was: the chip above this card.
 */
@Composable
private fun FolderHidden(name: String, onSelectMore: () -> Unit) {
    AdvisoryCard(
        icon = FlickIcons.Private,
        title = stringResource(R.string.library_folder_hidden_title, name),
        body = stringResource(R.string.library_folder_hidden_body),
        tone = AdvisoryTone.INFO,
        primaryLabel = stringResource(R.string.library_folder_hidden_action),
        onPrimary = onSelectMore,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The folder is there but its scoped list is now empty. */
@Composable
private fun FolderEmpty(name: String) {
    val colors = LocalFlickColors.current
    Column(Modifier.fillMaxWidth().padding(vertical = 26.dp)) {
        Text(
            text = stringResource(R.string.library_empty_folder, name),
            style = FlickText.titleMedium.copy(color = colors.onSurface),
        )
        Text(
            text = stringResource(R.string.library_empty_folder_body),
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
    unplayable: Boolean,
    silentAudio: Boolean,
    progress: State<PlaybackProgressState>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    val context = LocalContext.current
    // This tile's own share of the checkpoint map, and the reason the map itself is
    // threaded down as State: the store republishes during a cast, and a derived read
    // under structural equality invalidates only the tiles whose OWN value moved. The
    // fingerprint is the item's identity in that map and is worth exactly one hash per
    // tile rather than one per checkpoint.
    val fingerprint = remember(item) { PlaybackMediaFingerprint.of(item) }
    val resume by remember(fingerprint, item.durationMs, progress) {
        derivedStateOf(structuralEqualityPolicy()) {
            resumeProgress(progress.value, fingerprint, item.durationMs)
        }
    }
    // The badge is the only thing on this screen that still needs a dynamic range, and
    // it needs it per tile. MediaProbe memoizes by uri, so a tile recomposed on scroll
    // costs a map lookup rather than a second container parse. Null rather than
    // [HdrType.NONE] until it answers: NONE is the probe's verdict, not its starting
    // point, and the tile must not be told the file has no HDR before anyone looked.
    //
    // The memo is read SYNCHRONOUSLY as the initial value, so a tile scrolled back to is
    // composed once already carrying its answer. Seeded from `null` it would recompose at
    // least once more no matter how warm the cache was — forty of those land at forty
    // arbitrary frames during a fling back over ground already covered.
    val seed = remember(item.uri) { MediaProbe.cachedHdr(item.uri) }
    val hdr by produceState(seed, item.uri) {
        if (value == null) value = MediaProbe.detectHdr(context, item.uri)
    }
    VideoTile(
        item = item,
        hdr = hdr,
        imageLoader = imageLoader,
        compact = compact,
        unplayable = unplayable,
        silentAudio = silentAudio,
        resume = resume,
        onClick = onClick,
        modifier = modifier,
        sharedScope = sharedScope,
        animatedScope = animatedScope,
    )
}

/** The answer [libraryEmptyShown] gave last, held across recompositions. */
private class EmptyLatch(var shown: Boolean = false)

/**
 * Whether the library stands down to its [EmptyState].
 *
 * [showing] is the answer the screen is already giving, and it is what keeps the empty
 * state up while a re-query is in flight: refreshing from it raises [loading], and
 * falling through to the grid for the length of that query would replace the whole
 * window with a header, a link pill and the library controls — and then replace it back
 * the moment MediaStore confirms there is still nothing. A library that has never
 * resolved has no answer to hold, which is the one case the grid's loading note is for.
 * Denied access never waits on a query: none is run.
 */
internal fun libraryEmptyShown(
    access: MediaAccess,
    itemCount: Int,
    loading: Boolean,
    showing: Boolean,
): Boolean = when {
    access == MediaAccess.NONE -> true
    loading -> showing && itemCount == 0
    else -> itemCount == 0
}

/**
 * The first screen a new user ever sees, and it stands in for two different situations:
 * a gallery Flick has not been let into ([MediaAccess.NONE]) and a gallery that is
 * genuinely empty. Neither may claim the other's copy — "nothing to flick yet" is a lie
 * about a phone full of films Flick simply cannot read — so the hero, the body and the
 * action are all chosen off the access level.
 *
 * The wordmark is the populated header's, in the populated header's seat: this screen is
 * the whole window while it is up, and without it the brand would be missing from the
 * one surface a new install opens on. That is also why the hero disc no longer carries
 * the mark a second time — the glyph names what is missing instead.
 */
@Composable
private fun EmptyState(
    controller: FlickController,
    connectedTv: PairedTv?,
    castingItem: MediaItem?,
    signal: State<SignalInfo>,
    wifiLinkUp: State<Boolean>,
    mediaAccess: MediaAccess,
    bottomClearance: Dp,
    onChoose: () -> Unit,
    onRefresh: () -> Unit,
    showSupportInvitation: Boolean,
    onOpenSupport: () -> Unit,
    onDismissSupportInvitation: () -> Unit,
) {
    val colors = LocalFlickColors.current
    val locked = mediaAccess == MediaAccess.NONE
    // Mirrors MediaLibraryActionPolicy: full access already sees every video, so the only
    // honest action left there is to look again — re-asking for a granted permission
    // opens no system UI at all and would read as a dead button.
    val actionLabel = when (mediaAccess) {
        MediaAccess.NONE -> R.string.empty_locked_choose
        MediaAccess.PARTIAL -> R.string.empty_choose
        MediaAccess.FULL -> R.string.library_refresh_videos
    }
    val action = if (mediaAccess == MediaAccess.FULL) onRefresh else onChoose

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            // Unconditional, like the grid this screen stands in for. On a short window —
            // split screen, a large type scale — the hero is taller than the room left for
            // it, and a centred column with nowhere to scroll pushes its own button out
            // through the bottom edge: on a phone Flick has not been let into, that button
            // is the only way into the app at all.
            .verticalScroll(rememberScrollState())
            // Top and start match the grid's own content padding, so granting access or
            // adding the first video never moves the mark.
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = bottomClearance),
    ) {
        Wordmark()
        // The grid's 13 dp row gap plus the 5 dp every section block carries: the pill
        // lands in the same place here as it does under the populated header.
        Spacer(Modifier.height(18.dp))
        LinkPill(
            controller = controller,
            connectedTv = connectedTv,
            castingItem = castingItem,
            signal = signal,
            wifiLinkUp = wifiLinkUp,
        )
        if (showSupportInvitation) {
            Spacer(Modifier.height(13.dp))
            SupportInvitationCard(
                onOpenSupport = onOpenSupport,
                onDismiss = onDismissSupportInvitation,
            )
        }
        // Surplus spacers rather than a weighted hero: a weighted child is FIXED to the
        // room that is left over, so a hero taller than that room overflows both of its
        // edges and the column never grows enough to scroll it back. These collapse to
        // nothing the moment the stack outgrows the window, and split the surplus evenly
        // when it does not — which is the same seat Arrangement.Center gave it.
        Spacer(Modifier.weight(1f))
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(104.dp)
                    .shadow(18.dp, CircleShape, clip = false, ambientColor = PrimaryShadow, spotColor = PrimaryShadow)
                    .clip(CircleShape)
                    .background(colors.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (locked) FlickIcons.Private else FlickIcons.GridView,
                    contentDescription = null,
                    tint = colors.onPrimaryContainer,
                    modifier = Modifier.size(46.dp),
                )
            }
            Spacer(Modifier.height(22.dp))
            Text(
                text = stringResource(if (locked) R.string.empty_locked_title else R.string.empty_title),
                style = FlickText.headlineMedium.copy(color = colors.onSurface),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(if (locked) R.string.empty_locked_body else R.string.empty_body),
                style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            FlickPrimaryButton(
                text = stringResource(actionLabel),
                onClick = action,
                modifier = Modifier.width(240.dp),
            )
            // The pill at the top of this same screen names the link when it is gone;
            // "ready when you are" underneath it would contradict it in one window.
            if (connectedTv != null && wifiLinkUp.value) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.empty_tv_ready, connectedTv.name),
                    style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

/**
 * The mock's 412 dp frame lands two 179 dp columns; keeping that as a minimum lets a
 * 360 dp phone still show two and a rotated one reflow to five.
 */
private val TileMinWidth = 150.dp

// What the grid sorts its reuse pool by. Half a dozen structurally unrelated full-span
// sections share this list with the tiles, and a slot is only cheap to reuse for its own
// kind: handed a header's slot, a tile is rebuilt from nothing rather than updated.
private const val TileContent = "tile"
private const val SectionContent = "section"
private const val LibraryGridTestTag = "library_grid"

/** The face arriving grows into place rather than appearing at full size. */
private const val PillSwapScale = 0.9f

// Matches the shared telemetry poll, so the pill and the band chip beside it can never
// be more than one tick apart. Only ever runs while the shared record says there is no
// link — once it says there is, that answer is proof and this stops.
private const val WifiLinkRecheckMs = 2_000L

// How long a pull waits for the read it asked for to raise the loading flag. The flag is
// raised on the calling thread, so this is only ever spent on a read that never ran or
// one that was over before the effect could look; it is not what ends a refresh.
private const val RefreshStartWindowMs = 500L

// The shortest a released pull keeps the indicator up, whatever the read does. Long
// enough for the shape morph to turn once, so the answer to "did that work" is a loader
// that ran rather than something that flickered at the top of the grid.
private const val RefreshFloorMs = 700L

// The resting silhouette the reduce-motion indicator shows instead of the morph, sized
// to the shape the morphing one settles at.
private val RefreshRestingSize = 24.dp

// Entrance stagger: one step per tile up to the twelfth, which is roughly two screens
// on the widest column count — beyond that the sequence reads as loading, not arrival.
private const val StaggerStepMs = 35L
private const val StaggerCapIndex = 12
private val StaggerRiseDp = 18.dp

// Long enough for the capped sequence plus its settle. After this the grid is a grid:
// a tile the lazy list recomposes on scroll has not just arrived.
private const val StaggerWindowMs = 1_200L

private val SearchControlSize = 48.dp
private val SearchControlGap = 8.dp
private const val FolderClosedWidthFraction = 0.75f

// The same deformation the removed quality chips used. The small lift and the icon's
// zero-at-rest travel arc make the seat change legible without turning it into a flourish.
private const val SearchKickStretch = 0.10f
private const val SearchKickSquash = 0.08f
private val SearchKickLift = 2.dp
private const val SearchKickTurnDegrees = 5f
private val SearchTravelLift = 1.dp
private const val SearchTravelTurnDegrees = 10f
