package com.flick.receiver.ui.library

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.DataSource
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import coil.transition.CrossfadeTransition
import coil.transition.Transition
import coil.transition.TransitionTarget
import com.flick.receiver.R
import com.flick.receiver.library.LibraryBlock
import com.flick.receiver.library.LibraryHdr
import com.flick.receiver.library.LibraryHub
import com.flick.receiver.library.LibraryImages
import com.flick.receiver.library.LibraryItem
import com.flick.receiver.library.LibraryShow
import com.flick.receiver.ui.components.FlickTvButton
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickDimens
import com.flick.receiver.ui.theme.FlickIcons
import com.flick.receiver.ui.theme.FlickShape
import com.flick.receiver.ui.theme.FlickSpace
import com.flick.receiver.ui.theme.FlickType
import kotlinx.coroutines.Dispatchers
import java.text.NumberFormat

// ── Layout ──────────────────────────────────────────────────────────────────
// The TV composes at 960 × 540 dp; the approved mockups are drawn at 1920 × 1080 px, so
// every mockup length is halved here. Type is halved too and then held at FlickType's
// ten-foot floor, which is why small mockup labels come out a touch larger.

internal object LibraryLayout {
    /**
     * The side margin: the overscan inset plus the focus ring's reserve, so a lifted control at
     * the edge keeps its ring on the panel. It is also Google TV's 58 dp grid margin. Rows bleed
     * past it to the screen edge.
     */
    val Gutter: Dp = FlickDimens.OverscanHorizontal + FlickDimens.FocusRingReserve

    val TopInset: Dp = FlickDimens.OverscanVertical

    /** The top bar's top edge, inset like [Gutter] so a focused tab's ring stays on the panel. */
    val TopBarTop: Dp = FlickDimens.OverscanVertical + FlickDimens.FocusRingReserve

    /** Where a tab's content starts under the top bar. */
    val UnderTopBar: Dp = 88.dp

    /** Room a lazy row or grid keeps around its items so a lifted control's ring is not clipped. */
    val RingRoom: Dp = FlickDimens.FocusRingReserve

    /** [RingRoom] for pictures, which lift further: it covers a tile up to ~236 dp tall. */
    val TileRingRoom: Dp = 14.dp

    /** Between tiles side by side: Google TV's card spacing, and past a lifted neighbour's ring. */
    val TileGap: Dp = 20.dp

    /** Between the rows of a grid: a lifted tile's ring stays clear of the caption above it. */
    val GridRowGap: Dp = 28.dp

    /** Between Home's rows, and between its hero and the first row. */
    val ShelfGap: Dp = 32.dp

    /** A row's title to its tiles; more than [TileRingRoom], so a lifted tile never meets it. */
    val TitleGap: Dp = FlickSpace.Md

    /** A picture to its caption: past the lifted picture's ring, so focus never touches the text. */
    val CaptionGap: Dp = 12.dp

    /** Between buttons side by side: a lifted button's ring stays off its neighbour. */
    val ActionGap: Dp = FlickSpace.Md

    /** Between pill tabs, which are shorter and narrower than buttons and lift less. */
    val PillGap: Dp = 12.dp

    /** Between spec chips. */
    val ChipGap: Dp = 8.dp

    val RowTileWidth: Dp = 196.dp
    val RowTileHeight: Dp = 110.dp

    val PosterWidth: Dp = 124.dp
    val PosterHeight: Dp = 186.dp
}

/** The Dolby Vision chip's foil and ink: a mark of its own, not a palette role. */
private val DolbyFoil = Brush.linearGradient(listOf(Color(0xFFD6A34E), Color(0xFFFAE7B8), Color(0xFFB9822C)))
private val DolbyInk = Color(0xFF3D2B13)
private val Hdr10Ink = Color(0xFFFAE7B8)
private val Hdr10Edge = Color(0x9ED6A34E)

/** Behind a badge drawn over art: [FlickColor.Canvas] @ 72 %. */
private val BadgePlate = Color(0xB804070F)

/** The "Can't play on this TV" plate: [FlickColor.Trouble] @ 85 %, with a pale crimson ink. */
private val BlockedPlate = Color(0xD9C9314D)
private val BlockedInk = Color(0xFFFFD0C8)

private val TitleCardBrush = Brush.verticalGradient(listOf(FlickColor.SurfaceRaised, FlickColor.SurfaceRaisedAlt))

// ── Shared state ────────────────────────────────────────────────────────────

/** What every library composable reads: the hub, the image loader, the thumbnails and the link epoch. */
@Stable
internal class LibraryUi(
    val hub: LibraryHub,
    val imageLoader: ImageLoader,
    val thumbnails: State<Map<String, ImageBitmap>>,
    /** [com.flick.receiver.library.LibraryState.epoch], on its own so a tile recomposes only when it changes. */
    val epoch: State<Long>,
)

internal val LocalLibraryUi = staticCompositionLocalOf<LibraryUi> { error("LibraryUi is provided by LibraryScreen") }

/**
 * One back-stack entry's scroll and focus, held outside composition so they survive the
 * entry leaving the screen while a page is pushed over it.
 */
@Stable
internal class ScreenMemory {
    /** The focus key of the control that last held focus here; restored on return. */
    var focusKey: String? = null
    val restore = FocusRequester()
    val column = LazyListState()
    val grid = LazyGridState()
    private val rows = HashMap<String, LazyListState>()

    fun row(key: String): LazyListState = rows.getOrPut(key) { LazyListState() }

    var season by mutableStateOf<Int?>(null)

    /** The season tabs have been scrolled to the selected season once. */
    var seasonTabsPlaced = false
    var folder by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
}

/**
 * Records this control as the screen's focus while it holds focus, and wears the screen's
 * restore requester when it is the control to come back to. Must precede the control's
 * own focus target in the chain, which is where a [FlickTvButton]'s `modifier` lands.
 */
internal fun Modifier.remembersFocus(memory: ScreenMemory, key: String): Modifier =
    this
        .then(if (memory.focusKey == key) Modifier.focusRequester(memory.restore) else Modifier)
        .onFocusChanged { if (it.isFocused) memory.focusKey = key }

// ── Text helpers ────────────────────────────────────────────────────────────

@Composable
@ReadOnlyComposable
internal fun pluralText(@PluralsRes id: Int, count: Int, vararg args: Any): String =
    LocalResources.current.getQuantityString(id, count, *args)

internal fun formatCount(count: Int): String = NumberFormat.getIntegerInstance().format(count)

@Composable
@ReadOnlyComposable
internal fun resolutionText(resolution: LibraryResolution): String = stringResource(
    when (resolution) {
        LibraryResolution.UHD -> R.string.libui_res_uhd
        LibraryResolution.FHD -> R.string.libui_res_fhd
        LibraryResolution.HD -> R.string.libui_res_hd
        LibraryResolution.SD -> R.string.libui_res_sd
    },
)

@Composable
@ReadOnlyComposable
internal fun hdrText(hdr: LibraryHdr, short: Boolean = false): String = stringResource(
    when (hdr) {
        LibraryHdr.DOLBY_VISION -> if (short) R.string.libui_hdr_dv_short else R.string.libui_hdr_dv
        LibraryHdr.HDR10 -> R.string.libui_hdr_hdr10
        LibraryHdr.SDR -> R.string.libui_hdr_sdr
    },
)

@Composable
@ReadOnlyComposable
internal fun blockedText(block: LibraryBlock): String = stringResource(
    when (block) {
        LibraryBlock.UNPLAYABLE -> R.string.libui_blocked_unplayable
        LibraryBlock.SILENT -> R.string.libui_blocked_silent
    },
)

/** "52 MIN · TV SHOWS": the running time and the folder, whichever are known. */
@Composable
@ReadOnlyComposable
internal fun itemMeta(item: LibraryItem): String {
    val minutes = LibraryFormat.runtimeMinutes(item.durationMs)?.let { pluralText(R.plurals.libui_minutes, it, it) }
    val folder = LibraryFormat.folderName(item.folder)?.uppercase()
    return when {
        minutes != null && folder != null -> stringResource(R.string.libui_join, minutes, folder)
        else -> minutes ?: folder.orEmpty()
    }
}

// ── Images ──────────────────────────────────────────────────────────────────

@Volatile
private var sharedLoader: ImageLoader? = null
private val loaderLock = Any()

/** Bounded so a library of thousands of posters cannot fill a TV's small flash. */
private const val ART_DISK_CACHE_BYTES = 64L * 1024L * 1024L

/** A share of the app's memory class, below the phone's because the TV's heap is smaller. */
private const val ART_MEMORY_SHARE = 0.15

/**
 * One process-wide loader, so the memory cache survives moving between pages and a
 * backdrop seen on Home is a cache hit on the detail page. TMDB art only: phone
 * thumbnails arrive decoded through the hub and never touch this loader.
 */
internal fun libraryImageLoader(context: Context): ImageLoader {
    val app = context.applicationContext
    return sharedLoader ?: synchronized(loaderLock) {
        sharedLoader ?: ImageLoader.Builder(app)
            .memoryCache { MemoryCache.Builder(app).maxSizePercent(ART_MEMORY_SHARE).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(app.cacheDir.resolve("library_art"))
                    .maxSizeBytes(ART_DISK_CACHE_BYTES)
                    .build()
            }
            // Two decodes at a time: a fast scroll asks for a dozen at once, and the TV's CPU
            // is also the one keeping the UI at frame rate.
            .decoderDispatcher(Dispatchers.IO.limitedParallelism(2))
            .build()
            .also { sharedLoader = it }
    }
}

/**
 * A fade for art that has just arrived over the network only. Art read back from the disk
 * or memory cache lands in a frame or two, and fading it in again would make a picture
 * already seen look as if it were loading.
 */
private object NetworkFade : Transition.Factory {
    private val crossfade = CrossfadeTransition.Factory()

    override fun create(target: TransitionTarget, result: ImageResult): Transition =
        if (result is SuccessResult && result.dataSource != DataSource.NETWORK) {
            Transition.Factory.NONE.create(target, result)
        } else {
            crossfade.create(target, result)
        }
}

/**
 * A TMDB image built by [LibraryImages]. The request takes its size from [modifier]'s
 * constraints, so every caller gives it a fixed or filled size and art is decoded at the size
 * it is drawn, never at TMDB's.
 */
@Composable
internal fun RemoteImage(
    url: String,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    onLoaded: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val request = remember(url) {
        ImageRequest.Builder(context).data(url).transitionFactory(NetworkFade).build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        imageLoader = imageLoader,
        modifier = modifier,
        alignment = alignment,
        contentScale = contentScale,
        onSuccess = if (onLoaded != null) { _ -> onLoaded() } else null,
    )
}

/** A dark card carrying the title, for a video with no art and no phone still yet. */
@Composable
internal fun TitleCard(title: String, modifier: Modifier = Modifier) {
    Box(modifier.background(TitleCardBrush), contentAlignment = Alignment.BottomStart) {
        Text(
            text = title,
            style = FlickType.display(sizeSp = 15, weight = FontWeight.Bold),
            color = FlickColor.OnSurfaceDim,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
        )
    }
}

/**
 * The phone's own still for [id], asked for whenever this composes without it and drawn when
 * it arrives; until then a title card, or nothing when [title] is null. Only for items without
 * TMDB art: the hub never fetches stills for the others.
 */
@Composable
internal fun PhoneThumbnail(id: String, title: String?, modifier: Modifier = Modifier) {
    val ui = LocalLibraryUi.current
    // Per tile, so one arriving thumbnail recomposes its own tile and not every tile on screen.
    val bitmap by remember(id, ui) { derivedStateOf { ui.thumbnails.value[id] } }
    val shown = bitmap
    val epoch by ui.epoch
    // Asked again whenever the still goes missing (the hub keeps only so many, oldest out
    // first) and on each new socket. Asked when shown too: that keeps it in the hub's cache.
    LaunchedEffect(id, shown == null, epoch) { ui.hub.wantThumbnail(id) }
    if (shown != null) {
        Image(bitmap = shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    } else if (title != null) {
        TitleCard(title, modifier)
    }
}

internal enum class ArtKind { LANDSCAPE, POSTER }

/**
 * An item's picture: TMDB art when the phone matched it (a still for an episode when one is
 * known), else the phone's own still, else a title card.
 */
@Composable
internal fun ItemArt(
    item: LibraryItem,
    kind: ArtKind,
    modifier: Modifier = Modifier,
    stillPath: String? = null,
    title: String = item.title,
) {
    val ui = LocalLibraryUi.current
    val art = item.art
    val url = when (kind) {
        ArtKind.LANDSCAPE -> LibraryImages.still(stillPath) ?: LibraryImages.tileBackdrop(art?.backdrop)
        ArtKind.POSTER -> LibraryImages.poster(art?.poster) ?: LibraryImages.tileBackdrop(art?.backdrop)
    }
    val poster = if (url == null && kind == ArtKind.LANDSCAPE) LibraryImages.poster(art?.poster) else null
    when {
        url != null -> RemoteImage(url, ui.imageLoader, modifier.background(FlickColor.SurfaceRaised))
        poster != null -> Box(modifier.background(TitleCardBrush)) {
            RemoteImage(poster, ui.imageLoader, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        art == null -> PhoneThumbnail(item.id, title, modifier)
        else -> TitleCard(title, modifier)
    }
}

/** A show's picture: its TMDB art, else its first episode's phone still, else a title card. */
@Composable
internal fun ShowArt(show: LibraryShow, kind: ArtKind, modifier: Modifier = Modifier) {
    val ui = LocalLibraryUi.current
    val url = when (kind) {
        ArtKind.LANDSCAPE -> LibraryImages.tileBackdrop(show.art?.backdrop) ?: LibraryImages.poster(show.art?.poster)
        ArtKind.POSTER -> LibraryImages.poster(show.art?.poster) ?: LibraryImages.tileBackdrop(show.art?.backdrop)
    }
    val first = show.all.firstOrNull()
    when {
        url != null -> RemoteImage(url, ui.imageLoader, modifier.background(FlickColor.SurfaceRaised))
        show.art == null && first != null && first.art == null -> PhoneThumbnail(first.id, show.title, modifier)
        else -> TitleCard(show.title, modifier)
    }
}

// ── Marks ───────────────────────────────────────────────────────────────────

/** The resume bar along a tile's foot. Static: drawn once, never animated. */
@Composable
internal fun ProgressStrip(fraction: Float, modifier: Modifier = Modifier, height: Dp = 3.5.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                drawRect(FlickColor.TrackBuffered)
                drawRect(FlickColor.Spark, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height))
            },
    )
}

/** The hero's rounded progress bar with its label beside it. */
@Composable
internal fun ProgressLine(fraction: Float, label: String, modifier: Modifier = Modifier, barWidth: Dp = 150.dp) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(
            Modifier
                .width(barWidth)
                .height(4.dp)
                .clip(FlickShape.Pill)
                .drawBehind {
                    drawRect(FlickColor.TrackBase)
                    drawRect(FlickColor.Spark, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height))
                },
        )
        Text(
            text = label,
            style = FlickType.monoEyebrow(sizeSp = 14, trackingEm = 0.06f),
            color = FlickColor.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A spec chip in the mockups' outlined style. */
@Composable
internal fun MetaChip(text: String, modifier: Modifier = Modifier, ink: Color = FlickColor.OnSurfaceSoft, edge: Color = FlickColor.Outline) {
    Box(
        modifier
            .clip(FlickShape.Sm)
            .border(FlickDimens.Hairline, edge, FlickShape.Sm)
            .padding(horizontal = 7.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = FlickType.monoEyebrow(sizeSp = 14, trackingEm = 0.08f),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The dynamic-range chip: foil for Dolby Vision, a gold edge for HDR10, plain for SDR. */
@Composable
internal fun HdrChip(hdr: LibraryHdr, modifier: Modifier = Modifier, short: Boolean = false) {
    when (hdr) {
        LibraryHdr.DOLBY_VISION -> Box(
            modifier
                .clip(FlickShape.Sm)
                .background(DolbyFoil)
                .padding(horizontal = if (short) 5.dp else 7.dp, vertical = if (short) 3.dp else 4.dp),
        ) {
            Text(text = hdrText(hdr, short), style = FlickType.monoEyebrow(sizeSp = 14, trackingEm = 0.06f, weight = FontWeight.SemiBold), color = DolbyInk, maxLines = 1)
        }
        LibraryHdr.HDR10 -> MetaChip(hdrText(hdr), modifier, ink = Hdr10Ink, edge = Hdr10Edge)
        LibraryHdr.SDR -> MetaChip(hdrText(hdr), modifier)
    }
}

/** A small label laid over art: resolution, running time. */
@Composable
internal fun ArtBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(FlickShape.Sm)
            .background(BadgePlate)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Text(text = text, style = FlickType.monoTabular(sizeSp = 14), color = FlickColor.OnSurface, maxLines = 1)
    }
}

@Composable
internal fun BlockedBadge(block: LibraryBlock, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(FlickShape.Sm)
            .background(BlockedPlate)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = blockedText(block),
            style = FlickType.body(sizeSp = 14),
            color = BlockedInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun WatchedMark(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(BadgePlate),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = LibraryIcons.Check, contentDescription = null, tint = FlickColor.Live, modifier = Modifier.size(14.dp))
    }
}

/** The usual marks over an item's art: resume bar, watched tick, blocked plate. */
@Composable
internal fun BoxScope.ItemMarks(item: LibraryItem, showBlocked: Boolean = true) {
    if (item.watched && item.resumeMs == null) {
        WatchedMark(Modifier.align(Alignment.TopEnd).padding(6.dp))
    }
    val block = item.blocked
    if (showBlocked && block != null) {
        BlockedBadge(block, Modifier.align(Alignment.BottomStart).padding(start = 5.dp, bottom = 7.dp, end = 5.dp))
    }
    LibraryFormat.progress(item)?.let { ProgressStrip(it, Modifier.align(Alignment.BottomCenter)) }
}

// ── Tiles and actions ───────────────────────────────────────────────────────

/**
 * A focusable picture with its caption under it. Only the picture lifts and wears the ring,
 * as in the mockups; the caption is plain text, so focus never reflows the row.
 */
@Composable
internal fun LibraryTile(
    width: Dp,
    height: Dp,
    memory: ScreenMemory,
    focusKey: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    shape: androidx.compose.ui.graphics.Shape = FlickShape.Lg,
    picture: @Composable BoxScope.() -> Unit,
    caption: @Composable ColumnScope.() -> Unit = {},
) {
    Column(modifier.width(width)) {
        FlickTvButton(
            onClick = onClick,
            modifier = Modifier
                .remembersFocus(memory, focusKey)
                .closedToModal()
                .size(width, height),
            contentDescription = contentDescription,
            focusRequester = focusRequester,
            shape = shape,
            containerColor = FlickColor.SurfaceRaised,
            borderColor = Color.Transparent,
            contentPadding = PaddingValues(0.dp),
        ) {
            Box(Modifier.fillMaxSize(), content = picture)
        }
        Spacer(Modifier.height(LibraryLayout.CaptionGap))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp), content = caption)
    }
}

@Composable
internal fun TileTitle(text: String, color: Color = FlickColor.OnSurface) {
    Text(
        text = text,
        style = FlickType.body(sizeSp = 14),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun TileMeta(text: String) {
    if (text.isEmpty()) return
    Text(
        text = text,
        style = FlickType.monoEyebrow(sizeSp = 14, trackingEm = 0.06f, weight = FontWeight.Medium),
        color = FlickColor.OnSurfaceFaint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The amber call to action. */
@Composable
internal fun PrimaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    icon: ImageVector? = FlickIcons.Play,
) {
    FlickTvButton(
        onClick = onClick,
        modifier = modifier,
        focusRequester = focusRequester,
        containerColor = FlickColor.Spark,
        borderColor = Color.Transparent,
        ringColor = FlickColor.FocusRingOnSpark,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = FlickColor.OnSpark, modifier = Modifier.size(15.dp))
        }
        Text(text = text, style = FlickType.body(sizeSp = 15, weight = FontWeight.Bold), color = FlickColor.OnSpark, maxLines = 1)
    }
}

@Composable
internal fun SecondaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    FlickTvButton(
        onClick = onClick,
        modifier = modifier,
        focusRequester = focusRequester,
        borderColor = FlickColor.GlassBorder,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(text = text, style = FlickType.body(sizeSp = 15), color = FlickColor.OnSurface, maxLines = 1)
    }
}

/** The breadcrumb over a pushed page: "‹ FILMS". Not focusable — Back is the way out. */
@Composable
internal fun Breadcrumb(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlickSpace.Xs)) {
        Icon(imageVector = LibraryIcons.Back, contentDescription = null, tint = FlickColor.OnSurfaceFaint, modifier = Modifier.size(14.dp))
        Text(text = text, style = FlickType.monoEyebrow(sizeSp = 14, trackingEm = 0.16f), color = FlickColor.OnSurfaceFaint)
    }
}

/** A row heading on Home and the grids. */
@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text = text, style = FlickType.display(sizeSp = 17, weight = FontWeight.Bold), color = FlickColor.OnSurface, modifier = modifier)
}

/** Every chip a detail-style header carries, in the mockups' order. */
@Composable
internal fun ItemChips(item: LibraryItem, modifier: Modifier = Modifier, hdr: LibraryHdr? = item.hdr, dimensions: Boolean = false) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(LibraryLayout.ChipGap), verticalAlignment = Alignment.CenterVertically) {
        item.year?.let { MetaChip(it.toString()) }
        LibraryFormat.runtimeMinutes(item.durationMs)?.let { MetaChip(pluralText(R.plurals.libui_minutes, it, it)) }
        LibraryFormat.resolution(item.width, item.height)?.let { resolution ->
            val label = resolutionText(resolution)
            MetaChip(
                if (dimensions) stringResource(R.string.libui_res_dimensions, label, item.width, item.height) else label,
            )
        }
        hdr?.let { HdrChip(it) }
    }
}
