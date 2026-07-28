package com.flick.sender.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.flick.sender.R
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.Motion
import com.flick.sender.ui.theme.PillShape
import com.flick.sender.ui.theme.SheetShape
import com.flick.sender.ui.theme.rememberReduceMotion

/** Padding of a sheet's content column: tight above the grabber, generous below. */
private val SheetPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 24.dp)

/** Clear air between the scrolling region and the first control of a pinned footer. */
private val SheetFooterGap = 16.dp

/** The line a pinned footer draws while it still has content hidden beneath it. */
private val SheetFooterEdge = 1.dp

/**
 * How many sheets are raised UNDERNEATH the floating chrome.
 *
 * The shell's nav pill and now-playing dock float over the route, so a sheet a route
 * raises inside itself is painted underneath them — the pill lands on whatever sits at
 * the foot of the sheet, which on the pairing sheets is the Connect button. Counting
 * rather than flagging, because a sheet may replace another while the first is still
 * running its exit and a boolean would clear the chrome back in under the second one.
 *
 * The shell hands its overlay layer a counter of its own, because a sheet hosted there is
 * drawn OVER the chrome: evicting bars that sheet already covers is motion nobody asked
 * for, played through a scrim that shows it.
 *
 * The default is a detached counter, so a sheet composed outside the shell — a preview,
 * a test — still increments something real and simply moves no chrome.
 */
internal val LocalSheetDepth = staticCompositionLocalOf<MutableIntState> { mutableIntStateOf(0) }

/**
 * A scrimmed bottom sheet used for the pairing code, manual entry, the quality
 * sheet (S10) and diagnostics. Tapping the scrim dismisses; taps on the sheet are
 * swallowed. The surface colour comes from the enclosing theme, so wrapping a caller in
 * `FlickCinematicTheme` turns the sheet cinematic without changing anything here.
 *
 * [footer] is the sheet's terminal action, pinned outside the scrolling region. A form
 * whose fields and submit button together outgrow the space a raised keyboard leaves —
 * manual pairing on a tall phone — must not put that button behind a scroll: it is the
 * only way out of the sheet the user came for. Sheets that carry no action leave it null
 * and are laid out exactly as before.
 */
@Composable
fun BottomSheet(
    onDismiss: () -> Unit,
    contentPadding: PaddingValues = SheetPadding,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalFlickColors.current
    val dismissDescription = stringResource(R.string.a11y_dismiss_sheet)
    val sheetTitle = stringResource(R.string.a11y_sheet)
    // Held for exactly as long as this sheet is composed — including the frames it
    // spends leaving — so the chrome above it comes back only once it is actually gone.
    val sheetDepth = LocalSheetDepth.current
    DisposableEffect(sheetDepth) {
        sheetDepth.intValue++
        onDispose { sheetDepth.intValue-- }
    }
    val scrimSource = remember { MutableInteractionSource() }
    val sheetSource = remember { MutableInteractionSource() }
    val rise = rememberSheetRise()
    // The dim runs on an effects spring and the sheet's geometry on a spatial one, so the
    // room darkens before the surface arrives on it rather than the two moving as one
    // slab. The surface's own fade takes the dim's clock, not the rise's: one animation
    // for every opacity in the entrance, and none of them on a spring that rings.
    val scrim = rememberSheetFade()
    val scrimColor = colors.scrim
    val scrollState = rememberScrollState()
    val layoutDirection = LocalLayoutDirection.current
    // A pinned footer keeps the space under the last field itself, so the region above
    // it sheds a bottom padding that would only ever read as a second gap.
    val scrollPadding = if (footer == null) {
        contentPadding
    } else {
        PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection),
            top = contentPadding.calculateTopPadding(),
            end = contentPadding.calculateEndPadding(layoutDirection),
            bottom = 0.dp,
        )
    }
    // UNION, never sum: a raised IME's inset already spans the navigation bar, so
    // adding the two would reserve a second bar of dead space under the keyboard.
    val safeBottom = WindowInsets.ime.union(WindowInsets.navigationBars)
    // Pairing and diagnostics sheets own Back while visible: a route launch must first
    // dismiss or cancel the in-flight UI rather than closing the Activity underneath it.
    BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind { drawRect(color = scrimColor, alpha = scrim()) }
            .semantics { contentDescription = dismissDescription }
            .clickable(interactionSource = scrimSource, indication = null, onClick = onDismiss),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // OUTSIDE the surface: it reserves height without painting a band or
                // lifting the sheet off the bottom edge. On a window shorter than the
                // cap below — landscape, split screen — that cap stops being the
                // ceiling, and a sheet left free to fill whatever a raised keyboard
                // leaves puts its heading under the status bar, where no amount of
                // scrolling can bring it back.
                .statusBarsPadding()
                .heightIn(max = 640.dp)
                .sheetRiseTransform(rise, scrim)
                .clip(SheetShape)
                .background(colors.surfaceRaised)
                .clickable(interactionSource = sheetSource, indication = null, onClick = {})
                .semantics {
                    paneTitle = sheetTitle
                    isTraversalGroup = true
                }
                // Outside the scroll and above the footer, so the inset shrinks the
                // scrolling VIEWPORT instead of riding inside it: the viewport's bottom
                // edge is then the safe edge in every state, and no scroll offset —
                // including a stale one left behind by a collapsing keyboard — can put
                // the terminal action under the bar. It stays inside the background
                // above, so the surface still reaches the bottom screen edge with no
                // scrim gap under it.
                .windowInsetsPadding(safeBottom),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    // fill = false, or the region would stretch to whatever the window
                    // allows and strand the footer at the foot of a mostly empty sheet.
                    .weight(1f, fill = false)
                    .verticalScroll(scrollState)
                    .padding(scrollPadding),
                content = content,
            )
            if (footer != null) {
                val edge = colors.outlineSoft
                Column(
                    Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            // Read in the draw phase: a scroll must repaint this line
                            // without recomposing the action under it. The line is a
                            // claim that content continues beneath the footer, so it is
                            // drawn only while there is some left to reach.
                            if (scrollState.canScrollForward) {
                                drawRect(
                                    color = edge,
                                    topLeft = Offset.Zero,
                                    size = Size(size.width, SheetFooterEdge.toPx()),
                                )
                            }
                        }
                        .padding(
                            start = contentPadding.calculateStartPadding(layoutDirection),
                            top = SheetFooterGap,
                            end = contentPadding.calculateEndPadding(layoutDirection),
                            bottom = contentPadding.calculateBottomPadding(),
                        ),
                    content = footer,
                )
            }
        }
    }
}

/** The drag handle at the top of a sheet. */
@Composable
fun SheetGrabber(color: Color = LocalFlickColors.current.outlineSoft) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(PillShape)
                .background(color),
        )
    }
}

/**
 * Drives the shared sheet entry. Returned as a lambda so the transform is read in
 * the draw phase and the sheet's content is never recomposed by the animation.
 *
 * The rise is geometry, so it takes the scheme's spatial spring and overshoots — the
 * sheet is the consequence of a flick, and a flick lands with a little more travel
 * than it was aimed with.
 */
@Composable
internal fun rememberSheetRise(): () -> Float =
    rememberEntranceProgress(MaterialTheme.motionScheme.defaultSpatialSpec<Float>())

/** Every alpha in a sheet's entrance: an effects spring, which must not overshoot. */
@Composable
internal fun rememberSheetFade(): () -> Float =
    rememberEntranceProgress(MaterialTheme.motionScheme.fastEffectsSpec<Float>())

@Composable
private fun rememberEntranceProgress(spec: FiniteAnimationSpec<Float>): () -> Float {
    val reduceMotion = rememberReduceMotion()
    val progress = remember { Animatable(0f) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) progress.snapTo(1f) else progress.animateTo(1f, spec)
    }
    return remember(progress) { { progress.value } }
}

/**
 * Translate + scale + fade a sheet up from its own bottom edge, all of it in the draw
 * phase. [progress] is the spatial clock and carries the geometry; [fade] is the effects
 * clock and carries the opacity alone. The two are separate because the spatial spring
 * rings through its end state by design, and a surface whose transparency rings with it
 * flickers against the scrim on the way in.
 */
internal fun Modifier.sheetRiseTransform(progress: () -> Float, fade: () -> Float): Modifier = graphicsLayer {
    val p = progress()
    val scale = Motion.SheetRiseScale + (1f - Motion.SheetRiseScale) * p
    alpha = fade()
    scaleX = scale
    scaleY = scale
    translationY = Motion.SheetRiseOffsetDp.dp.toPx() * (1f - p)
    transformOrigin = TransformOrigin(0.5f, 1f)
}
