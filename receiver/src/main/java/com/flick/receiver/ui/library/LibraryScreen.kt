package com.flick.receiver.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.library.LibraryHub
import com.flick.receiver.library.LibraryLink
import com.flick.receiver.library.LibraryPlay
import com.flick.receiver.library.LibraryState
import com.flick.receiver.ui.components.FlickLoader
import com.flick.receiver.ui.components.FlickTvButton
import com.flick.receiver.ui.components.FlickTvIconButton
import com.flick.receiver.ui.components.FlickTvRow
import com.flick.receiver.ui.components.FocusBeaconHost
import com.flick.receiver.ui.components.GlassPanel
import com.flick.receiver.ui.components.GlassPanelTone
import com.flick.receiver.ui.components.LiveDot
import com.flick.receiver.ui.components.LocalShellRetained
import com.flick.receiver.ui.components.landTvFocus
import com.flick.receiver.ui.theme.BrandMark
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickIcons
import com.flick.receiver.ui.theme.FlickMotion
import com.flick.receiver.ui.theme.FlickShape
import com.flick.receiver.ui.theme.FlickSpace
import com.flick.receiver.ui.theme.FlickType
import com.flick.receiver.ui.theme.LocalHouseLit
import com.flick.receiver.ui.theme.LocalReducedMotion

/** The menu scrim: [FlickColor.CanvasPlayback] @ 55 %, as in the sort mockup. */
private val MenuScrim = Color(0x8C02040A)

/** True while a menu covers the screen; see [closedToModal]. */
@Stable
internal class LibraryModal {
    var open by mutableStateOf(false)

    /** A page's menu, drawn by the screen over everything, the top bar included; see [OverTopBar]. */
    var overlay by mutableStateOf<(@Composable () -> Unit)?>(null)
}

internal val LocalLibraryModal = staticCompositionLocalOf<LibraryModal?> { null }

/**
 * Takes this control out of focus while a menu is open, so the D-pad cannot walk out of the
 * menu onto what it covers. Only ever WRITES false: the shell's own gate writes false too,
 * and a true written here would override it.
 */
@Composable
internal fun Modifier.closedToModal(): Modifier {
    val modal = LocalLibraryModal.current ?: return this
    return this.focusProperties { if (modal.open) canFocus = false }
}

/** Holds [modal] open for as long as the caller is composed. */
@Composable
internal fun HoldModal() {
    val modal = LocalLibraryModal.current ?: return
    DisposableEffect(modal) {
        modal.open = true
        onDispose { modal.open = false }
    }
}

/**
 * Draws [content] at the screen's level, above the top bar, for as long as the caller is
 * composed. A page sits under the top bar, so a menu's scrim drawn inside it would leave the
 * bar lit.
 */
@Composable
internal fun OverTopBar(content: @Composable () -> Unit) {
    val modal = LocalLibraryModal.current
    if (modal == null) {
        content()
        return
    }
    val latest = rememberUpdatedState(content)
    DisposableEffect(modal) {
        val slot: @Composable () -> Unit = { latest.value() }
        modal.overlay = slot
        onDispose { if (modal.overlay === slot) modal.overlay = null }
    }
}

/** What a page needs from the screen around it. */
@Stable
internal class PaneContext(
    val memory: ScreenMemory,
    /** True when this page was just opened or returned to and owes D-pad focus a landing. */
    val land: Boolean,
    val onLanded: () -> Unit,
    /** A control the screen is certain to have; where focus goes when nothing better exists. */
    val fallback: FocusRequester,
    val open: (LibraryRoute) -> Unit,
    /** The breadcrumb over a pushed page, naming where Back goes. */
    val crumb: String,
    val phoneName: String,
    /** The route under this page on the stack, if any. */
    val under: LibraryRoute?,
)

/**
 * Lands D-pad focus once when the page is opened or returned to: on the control the viewer
 * left (the screen's [ScreenMemory.focusKey]), else on [preferred], else on the screen's
 * fallback. [focused] reports whether focus has arrived inside the page.
 */
@Composable
internal fun LandFocus(pane: PaneContext, preferred: FocusRequester?, focused: MutableState<Boolean>) {
    val retained = LocalShellRetained.current
    LaunchedEffect(pane.land) {
        if (!pane.land || retained) return@LaunchedEffect
        val entry = preferred ?: pane.fallback
        val first = if (pane.memory.focusKey != null) pane.memory.restore else entry
        landTvFocus(first, entry) { focused.value }
        if (!focused.value) runCatching { pane.fallback.requestFocus() }
        pane.onLanded()
    }
}

/** Tracks whether focus is anywhere inside the modified subtree. */
internal fun Modifier.tracksFocus(focused: MutableState<Boolean>): Modifier =
    this.onFocusChanged { focused.value = it.hasFocus }

/**
 * The phone's video library on the TV: Home, Shows, Films, All videos, Search and the pages
 * they open. Everything comes from [hub]; a pick asks the phone to cast, and the TV starts
 * nothing itself (`docs/design/library-channel.md` §6.1).
 *
 * Drawn in the dark cinema palette even though it is a standby surface: it is a wall of
 * film art, and the lit room's off-white bed would fight every backdrop on it.
 */
@Composable
fun LibraryScreen(
    hub: LibraryHub,
    onPairAnother: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val loader = remember(context) { libraryImageLoader(context) }
    val thumbnails = hub.thumbnails.collectAsState()
    val state = hub.state.collectAsState()
    val epoch = remember(state) { derivedStateOf { state.value.epoch } }
    val ui = remember(hub, loader, thumbnails, epoch) { LibraryUi(hub, loader, thumbnails, epoch) }
    val modal = remember { LibraryModal() }
    CompositionLocalProvider(
        LocalHouseLit provides false,
        LocalLibraryUi provides ui,
        LocalLibraryModal provides modal,
    ) {
        LibraryRoot(ui, onPairAnother, onOpenSettings, modifier)
    }
}

@Composable
private fun LibraryRoot(
    ui: LibraryUi,
    onPairAnother: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier,
) {
    val state by ui.hub.state.collectAsState()
    val retained = LocalShellRetained.current
    val modal = LocalLibraryModal.current
    val phoneName = state.phoneName ?: stringResource(R.string.libui_phone_fallback)

    var stack by remember { mutableStateOf(LibraryNav.root()) }
    val serials = remember { longArrayOf(0L) }
    val memories = remember { HashMap<Long, ScreenMemory>() }
    var landing by remember { mutableStateOf<Long?>(stack.last().serial) }
    val tabFocus = remember { LibraryTab.entries.associateWith { FocusRequester() } }
    var phoneMenu by remember { mutableStateOf(false) }
    val phonePillFocus = remember { FocusRequester() }
    val pillFocused = remember { mutableStateOf(false) }
    // Bumped when the phone menu closes. Focus goes back to the pill a frame later, once the
    // menu's modal hold has been released and the pill can take focus again.
    var pillReturn by remember { mutableIntStateOf(0) }
    LaunchedEffect(pillReturn) {
        if (pillReturn > 0 && !retained) landTvFocus(phonePillFocus, phonePillFocus) { pillFocused.value }
    }

    fun navigate(next: List<LibraryEntry>, land: Boolean) {
        stack = next
        landing = if (land) next.last().serial else null
        // A tab keeps its memory for the session; a page's goes with it.
        memories.keys.retainAll { serial -> serial < 0L || next.any { it.serial == serial } }
    }

    // Once each time the library comes on screen; remote key presses send it after that.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, retained) {
        if (retained) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { ui.hub.browsing() }
    }

    val catalog = state.catalog
    val shelves = state.shelves
    LaunchedEffect(catalog, shelves) {
        if (catalog == null || shelves == null) return@LaunchedEffect
        val pruned = LibraryNav.pruned(stack, catalog, shelves.shows.mapTo(HashSet()) { it.key })
        // A page re-keyed in place keeps its focus; only a page that went away re-lands it.
        if (pruned !== stack) navigate(pruned, land = pruned.last().serial != stack.last().serial)
    }

    // While the catalog is still on its way the top bar is all there is; the remote must
    // still have somewhere to be, or Settings is unreachable until the first catalog lands.
    LaunchedEffect(catalog == null) {
        if (catalog == null && !retained) {
            val home = tabFocus.getValue(LibraryTab.HOME)
            landTvFocus(home, home) { false }
        }
    }

    val top = stack.last()
    // A failure belongs to the page that asked; moving to another page puts it away.
    LaunchedEffect(top.serial) {
        if (ui.hub.state.value.play is LibraryPlay.Failed) ui.hub.clearPlayResult()
    }

    // Composed before every page and menu, so each of theirs is dispatched first.
    BackHandler(enabled = !retained && LibraryNav.back(stack) != null) {
        LibraryNav.back(stack)?.let { navigate(it, land = true) }
    }

    val rootTab = LibraryNav.rootTab(stack)
    val onTab = top.route is LibraryRoute.Tab

    Box(
        modifier
            .fillMaxSize()
            .background(FlickColor.Canvas)
            // A failure message stays until the viewer does anything at all.
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && ui.hub.state.value.play is LibraryPlay.Failed) {
                    ui.hub.clearPlayResult()
                }
                false
            },
    ) {
        when {
            catalog == null || shelves == null -> LoadingState()
            else -> key(top.serial) {
                val memory = memories.getOrPut(top.serial) { ScreenMemory() }
                val pane = PaneContext(
                    memory = memory,
                    land = landing == top.serial,
                    onLanded = { if (landing == top.serial) landing = null },
                    fallback = tabFocus.getValue(rootTab),
                    open = { route -> navigate(LibraryNav.push(stack, route, ++serials[0]), land = true) },
                    crumb = crumbText(stack),
                    phoneName = phoneName,
                    under = stack.getOrNull(stack.size - 2)?.route,
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .arrival(),
                ) {
                    if (catalog.items.isEmpty()) {
                        EmptyLibrary(phoneName)
                    } else {
                        when (val route = top.route) {
                            is LibraryRoute.Tab -> when (route.tab) {
                                LibraryTab.HOME -> HomePane(state, catalog, shelves, pane)
                                LibraryTab.SHOWS -> ShowsPane(shelves, pane)
                                LibraryTab.FILMS -> FilmsPane(shelves, pane)
                                LibraryTab.ALL -> AllVideosPane(catalog, pane)
                            }
                            is LibraryRoute.Show -> LibraryNav.showFor(route, catalog, shelves.shows)?.let {
                                ShowPage(it, state, pane)
                            }
                            is LibraryRoute.Detail -> catalog[route.itemId]?.let { DetailPage(it, state, pane) }
                            LibraryRoute.Search -> SearchPane(catalog, shelves, state, pane)
                        }
                    }
                }
            }
        }

        if (onTab || catalog == null) {
            TopBar(
                state = state,
                phoneName = phoneName,
                selected = rootTab,
                tabFocus = tabFocus,
                phonePillFocus = phonePillFocus,
                pillFocused = pillFocused,
                onSelect = { tab ->
                    // Focus stays on the tab the viewer pressed; only the content below swaps.
                    if (stack.size != 1 || rootTab != tab) navigate(LibraryNav.select(tab), land = false)
                },
                onSearch = { navigate(LibraryNav.push(stack, LibraryRoute.Search, ++serials[0]), land = true) },
                onPhoneMenu = { phoneMenu = true },
                onOpenSettings = onOpenSettings,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        } else if (state.link == LibraryLink.LOST) {
            ReconnectingPill(phoneName, Modifier.align(Alignment.TopEnd))
        }

        PlayStatus(state, modifier = Modifier.align(Alignment.BottomEnd))

        modal?.overlay?.let { slot -> key(slot) { slot() } }

        if (phoneMenu) {
            PhoneMenu(
                phoneName = phoneName,
                onRefresh = {
                    ui.hub.refresh()
                    phoneMenu = false
                    pillReturn++
                },
                onPairAnother = {
                    phoneMenu = false
                    onPairAnother()
                },
                onDismiss = {
                    phoneMenu = false
                    pillReturn++
                },
            )
        }
    }
}

@Composable
private fun crumbText(stack: List<LibraryEntry>): String {
    val under = stack.getOrNull(stack.size - 2)?.route
    return stringResource(
        when (under) {
            LibraryRoute.Search -> R.string.libui_crumb_search
            is LibraryRoute.Show -> R.string.libui_crumb_shows
            is LibraryRoute.Tab -> when (under.tab) {
                LibraryTab.HOME -> R.string.libui_crumb_home
                LibraryTab.SHOWS -> R.string.libui_crumb_shows
                LibraryTab.FILMS -> R.string.libui_crumb_films
                LibraryTab.ALL -> R.string.libui_crumb_all
            }
            else -> R.string.libui_crumb_home
        },
    )
}

/**
 * A page's one-shot fade in. The layer is dropped once it settles, so a library left on
 * screen for an evening composites nothing for an entrance that ended in the first second.
 */
@Composable
private fun Modifier.arrival(): Modifier {
    val reduced = LocalReducedMotion.current
    val spec = FlickMotion.stateEffects<Float>()
    val alpha = remember { Animatable(if (reduced) 1f else 0f) }
    var settled by remember { mutableStateOf(reduced) }
    LaunchedEffect(Unit) {
        if (!settled) {
            alpha.animateTo(1f, spec)
            settled = true
        }
    }
    return if (settled) this else this.graphicsLayer { this.alpha = alpha.value }
}

/** The ambient field behind the grids: a cool pool up and to the right, as in the mockups. */
internal fun Modifier.libraryAmbient(): Modifier = this.drawBehind {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(FlickColor.SurfaceRaised, FlickColor.Canvas),
            center = Offset(size.width * 0.72f, -size.height * 0.1f),
            radius = size.width * 0.62f,
        ),
    )
}

@Composable
private fun TopBar(
    state: LibraryState,
    phoneName: String,
    selected: LibraryTab,
    tabFocus: Map<LibraryTab, FocusRequester>,
    phonePillFocus: FocusRequester,
    pillFocused: MutableState<Boolean>,
    onSelect: (LibraryTab) -> Unit,
    onSearch: () -> Unit,
    onPhoneMenu: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .padding(start = LibraryLayout.Gutter, end = LibraryLayout.Gutter, top = LibraryLayout.TopBarTop),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandMark(size = 32.dp)
        Spacer(Modifier.width(FlickSpace.Lg))
        FocusBeaconHost {
            Row(horizontalArrangement = Arrangement.spacedBy(LibraryLayout.PillGap), verticalAlignment = Alignment.CenterVertically) {
                LibraryTab.entries.forEach { tab ->
                    val isSelected = tab == selected
                    FlickTvButton(
                        onClick = { onSelect(tab) },
                        modifier = Modifier.closedToModal(),
                        selected = isSelected,
                        focusRequester = tabFocus.getValue(tab),
                        containerColor = if (isSelected) FlickColor.OnSurface else FlickColor.ControlFill,
                        borderColor = Color.Transparent,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
                    ) {
                        Text(
                            text = stringResource(tabLabel(tab)),
                            style = FlickType.body(sizeSp = 14, weight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium),
                            color = if (isSelected) FlickColor.Canvas else FlickColor.OnSurfaceSoft,
                        )
                    }
                }
                FlickTvIconButton(
                    imageVector = LibraryIcons.Search,
                    contentDescription = stringResource(R.string.libui_search),
                    onClick = onSearch,
                    modifier = Modifier.closedToModal(),
                    side = 30.dp,
                    glyphSize = 15.dp,
                    shape = FlickShape.Pill,
                    tint = FlickColor.OnSurfaceSoft,
                    containerColor = FlickColor.ControlFill,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        FocusBeaconHost {
            Row(horizontalArrangement = Arrangement.spacedBy(FlickSpace.Md), verticalAlignment = Alignment.CenterVertically) {
                val lost = state.link == LibraryLink.LOST
                val count = state.catalog?.items?.size
                FlickTvButton(
                    onClick = onPhoneMenu,
                    modifier = Modifier
                        .tracksFocus(pillFocused)
                        .closedToModal(),
                    focusRequester = phonePillFocus,
                    contentDescription = stringResource(R.string.libui_phone_menu_a11y, phoneName),
                    containerColor = FlickColor.Surface,
                    borderColor = FlickColor.GlassBorder,
                    contentPadding = PaddingValues(horizontal = 13.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    LiveDot(color = if (lost) FlickColor.Caution else FlickColor.Live, size = 7.dp)
                    Text(
                        text = phoneName,
                        style = FlickType.body(sizeSp = 14, weight = FontWeight.Medium),
                        color = FlickColor.OnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 170.dp),
                    )
                    val trailing = when {
                        lost -> stringResource(R.string.libui_reconnecting_short)
                        count != null -> pluralText(R.plurals.libui_video_count, count, formatCount(count))
                        else -> null
                    }
                    if (trailing != null) {
                        Text(
                            text = trailing,
                            style = FlickType.monoEyebrow(sizeSp = 14, trackingEm = 0.12f),
                            color = if (lost) FlickColor.Caution else FlickColor.OnSurfaceFaint,
                            maxLines = 1,
                        )
                    }
                }
                FlickTvIconButton(
                    imageVector = FlickIcons.Settings,
                    contentDescription = stringResource(R.string.libui_settings),
                    onClick = onOpenSettings,
                    modifier = Modifier.closedToModal(),
                    side = 30.dp,
                    glyphSize = 16.dp,
                    shape = FlickShape.Pill,
                    tint = FlickColor.OnSurfaceSoft,
                    containerColor = FlickColor.ControlFill,
                )
            }
        }
    }
}

private fun tabLabel(tab: LibraryTab): Int = when (tab) {
    LibraryTab.HOME -> R.string.libui_tab_home
    LibraryTab.SHOWS -> R.string.libui_tab_shows
    LibraryTab.FILMS -> R.string.libui_tab_films
    LibraryTab.ALL -> R.string.libui_tab_all
}

/** On a pushed page there is no top bar to carry the link's state, so it rides here. */
@Composable
private fun ReconnectingPill(phoneName: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .padding(top = LibraryLayout.TopInset, end = LibraryLayout.Gutter)
            .background(FlickColor.Surface, FlickShape.Pill)
            .padding(horizontal = 13.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        LiveDot(color = FlickColor.Caution, size = 7.dp)
        Text(
            text = stringResource(R.string.libui_reconnecting, phoneName),
            style = FlickType.body(sizeSp = 14, weight = FontWeight.Medium),
            color = FlickColor.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 320.dp),
        )
    }
}

/**
 * The outcome of a Play, bottom right. A failure names the one thing to change and stays
 * until any key is pressed. "Asking your phone…" shows on every page while any Play is out,
 * so a second press, which the hub ignores until the first settles, visibly waits.
 */
@Composable
private fun PlayStatus(state: LibraryState, modifier: Modifier = Modifier) {
    val play = state.play
    val asking = play is LibraryPlay.Asking || play is LibraryPlay.Starting
    if (play !is LibraryPlay.Failed && !asking) return
    GlassPanel(
        modifier = modifier
            .padding(end = LibraryLayout.Gutter, bottom = LibraryLayout.TopInset + FlickSpace.Sm)
            .width(320.dp),
        shape = FlickShape.Xl,
        tone = GlassPanelTone.Panel,
        borderColor = FlickColor.GlassBorder,
        contentPadding = PaddingValues(horizontal = 15.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            LiveDot(color = if (play is LibraryPlay.Failed) FlickColor.Caution else FlickColor.Spark, size = 7.dp)
            Text(
                text = if (play is LibraryPlay.Failed) stringResource(R.string.libui_failed_title) else stringResource(R.string.libui_asking),
                style = FlickType.body(sizeSp = 15),
                color = FlickColor.OnSurface,
            )
        }
        if (play is LibraryPlay.Failed) {
            Text(
                text = stringResource(failureText(LibraryFormat.failureCopy(play.reason))),
                style = FlickType.body(sizeSp = 14, weight = FontWeight.Medium),
                color = FlickColor.OnSurfaceDim,
                modifier = Modifier.padding(start = 16.dp),
            )
            Text(
                text = stringResource(R.string.libui_failed_dismiss),
                style = FlickType.body(sizeSp = 14, weight = FontWeight.Medium),
                color = FlickColor.OnSurfaceFaint,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}

internal fun failureText(copy: PlayFailureCopy): Int = when (copy) {
    PlayFailureCopy.NOT_ON_PHONE -> R.string.libui_failed_not_on_phone
    PlayFailureCopy.SETTING_OFF -> R.string.libui_failed_setting_off
    PlayFailureCopy.PHONE_BUSY -> R.string.libui_failed_busy
    PlayFailureCopy.CANT_OPEN -> R.string.libui_failed_cant_open
    PlayFailureCopy.PHONE_IN_BACKGROUND -> R.string.libui_failed_background
    PlayFailureCopy.NO_ANSWER -> R.string.libui_failed_no_answer
    PlayFailureCopy.DID_NOT_START -> R.string.libui_failed_did_not_start
    PlayFailureCopy.OFFLINE -> R.string.libui_failed_offline
}

/** The phone pill's menu: refresh the library, or pair another phone. */
@Composable
private fun PhoneMenu(
    phoneName: String,
    onRefresh: () -> Unit,
    onPairAnother: () -> Unit,
    onDismiss: () -> Unit,
) {
    HoldModal()
    val first = remember { FocusRequester() }
    val inside = remember { mutableStateOf(false) }
    val retained = LocalShellRetained.current
    LaunchedEffect(Unit) { if (!retained) landTvFocus(first, first) { inside.value } }
    BackHandler(enabled = !retained) { onDismiss() }
    Box(
        Modifier
            .fillMaxSize()
            .background(MenuScrim),
    ) {
        GlassPanel(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = LibraryLayout.UnderTopBar, end = LibraryLayout.Gutter)
                .width(280.dp)
                .tracksFocus(inside),
            shape = FlickShape.Hero,
            tone = GlassPanelTone.Panel,
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlickSpace.Xs),
            ) {
                Icon(imageVector = LibraryIcons.Phone, contentDescription = null, tint = FlickColor.OnPanelLabel, modifier = Modifier.size(14.dp))
                Text(
                    text = phoneName.uppercase(),
                    style = FlickType.monoEyebrow(sizeSp = 14, trackingEm = 0.16f),
                    color = FlickColor.OnPanelLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FlickTvRow(onClick = onRefresh, focusRequester = first, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.libui_refresh), style = FlickType.body(sizeSp = 15), color = FlickColor.OnSurface)
            }
            FlickTvRow(onClick = onPairAnother, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.libui_pair_another), style = FlickType.body(sizeSp = 15), color = FlickColor.OnSurface)
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FlickSpace.Lg, Alignment.CenterVertically),
    ) {
        FlickLoader()
        Text(
            text = stringResource(R.string.libui_loading),
            style = FlickType.body(sizeSp = 16),
            color = FlickColor.OnSurfaceDim,
        )
    }
}

@Composable
private fun EmptyLibrary(phoneName: String) {
    Column(
        Modifier
            .fillMaxSize()
            .libraryAmbient(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FlickSpace.Sm, Alignment.CenterVertically),
    ) {
        Text(text = stringResource(R.string.libui_empty_title), style = FlickType.display(sizeSp = 27), color = FlickColor.OnSurface)
        Text(
            text = stringResource(R.string.libui_empty_body, phoneName),
            style = FlickType.body(sizeSp = 16),
            color = FlickColor.OnSurfaceDim,
        )
    }
}
