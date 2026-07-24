package com.flick.receiver.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickMotion
import com.flick.receiver.ui.theme.FlickShape
import com.flick.receiver.ui.theme.rememberReducedMotion

/** The detached ring's stroke (receiver-expressive-spec.md §3). */
val FlickFocusRingWidth: Dp = 2.5.dp

/** How far outside the element bounds the ring sits — it never affects layout. */
val FlickFocusRingOffset: Dp = 5.5.dp

/** The hairline a filled affordance carries around its fill. */
val FlickControlBorderWidth: Dp = 1.dp

/**
 * The stroke for an **outline-only** affordance — the END SESSION pill (spec §5.3)
 * is the only one on the playback screen. Double the control hairline: with no
 * fill of its own the border is the whole control, it sits directly on the film,
 * and at its height the top scrim has already thinned out.
 */
val FlickOutlinedChromeBorderWidth: Dp = 2.dp

/**
 * The stroke for a control whose fill is [containerColor]: a transparent fill
 * means the border draws the control on its own and needs
 * [FlickOutlinedChromeBorderWidth]. A caller that gives an outline-only chrome
 * pill a fill must pass that width explicitly to keep it.
 */
fun flickBorderWidth(containerColor: Color?): Dp =
    if (containerColor != null && containerColor.alpha == 0f) {
        FlickOutlinedChromeBorderWidth
    } else {
        FlickControlBorderWidth
    }

/**
 * Grows every corner of a [CornerBasedShape] by [grow], so a detached ring keeps
 * concentric corners with the element it surrounds. Non-corner shapes ring at
 * their own outline.
 */
private data class GrownCornerSize(val base: CornerSize, val grow: Dp) : CornerSize {
    override fun toPx(shapeSize: Size, density: Density): Float =
        base.toPx(shapeSize, density) + with(density) { grow.toPx() }
}

/** Element radius + [offset] (spec §3). Percent corners still resolve as percent. */
fun Shape.grownBy(offset: Dp): Shape =
    if (this is CornerBasedShape) {
        copy(
            topStart = GrownCornerSize(topStart, offset),
            topEnd = GrownCornerSize(topEnd, offset),
            bottomEnd = GrownCornerSize(bottomEnd, offset),
            bottomStart = GrownCornerSize(bottomStart, offset),
        )
    } else {
        this
    }

/**
 * The amber focus ring (spec §3) — a **detached** stroke drawn [offset] outside
 * the element bounds. It is painted, never laid out, so focusing an element can
 * never reflow the row it sits in.
 *
 * Place it AFTER any `graphicsLayer` that scales (so the ring scales with the
 * element) and BEFORE the element's own `clip`/`background` (so the element's
 * clip cannot eat it). [ringColor] must be [FlickColor.FocusRingOnSpark] on an
 * amber fill — amber on amber vanishes.
 */
fun Modifier.flickFocusRing(
    visible: Boolean,
    shape: Shape,
    ringColor: Color = FlickColor.FocusRing,
    offset: Dp = FlickFocusRingOffset,
    width: Dp = FlickFocusRingWidth,
): Modifier {
    val ringShape = shape.grownBy(offset)
    return this.drawWithContent {
        drawContent()
        if (!visible) return@drawWithContent
        val inset = offset.toPx()
        val ringSize = Size(size.width + inset * 2f, size.height + inset * 2f)
        if (ringSize.width <= 0f || ringSize.height <= 0f) return@drawWithContent
        val outline = ringShape.createOutline(ringSize, layoutDirection, this)
        translate(left = -inset, top = -inset) {
            drawOutline(outline = outline, color = ringColor, style = Stroke(width = width.toPx()))
        }
    }
}

/**
 * The one TV focus primitive (spec §3). There is no hover on TV, so this is the
 * whole vocabulary:
 *  - FOCUSED  = detached amber ring + scale [FlickMotion.FOCUS_SCALE]. The fill
 *    does not move, so focus and selection stay separable.
 *  - SELECTED = [FlickColor.SelectedFill] + [FlickColor.SelectedBorder], no ring.
 *  - UNFOCUSED = [FlickColor.ControlFill] + [FlickColor.Outline].
 *  - DISABLED = 38 % opacity.
 *
 * D-pad center fires [onClick] (foundation `clickable` maps DPAD_CENTER/ENTER to
 * click for a focused element). Every screen requests focus on exactly one of
 * these at entry so a focus target is always present.
 *
 * [containerColor] / [borderColor] override the state fills for the inverted
 * amber affordances (the open subtitles / metrics cards, the selected size cell);
 * pass [FlickColor.FocusRingOnSpark] as [ringColor] whenever you do.
 *
 * [borderWidth] defaults per [flickBorderWidth]: the control hairline when there
 * is a fill, the doubled stroke when the border is the whole control.
 */
@Composable
fun FlickTvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    contentDescription: String? = null,
    focusRequester: FocusRequester? = null,
    shape: Shape = FlickShape.Pill,
    ringColor: Color = FlickColor.FocusRing,
    containerColor: Color? = null,
    borderColor: Color? = null,
    borderWidth: Dp = flickBorderWidth(containerColor),
    contentPadding: PaddingValues = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(10.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val reducedMotion = rememberReducedMotion()
    val ringVisible = focused && enabled
    val scale by animateFloatAsState(
        targetValue = if (ringVisible && !reducedMotion) FlickMotion.FOCUS_SCALE else 1f,
        animationSpec = if (reducedMotion) tween(durationMillis = 0) else FlickMotion.focusPop(),
        label = "focusScale",
    )

    val fill = containerColor ?: if (selected) FlickColor.SelectedFill else FlickColor.ControlFill
    val stroke = borderColor ?: if (selected) FlickColor.SelectedBorder else FlickColor.Outline

    Row(
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .semantics(mergeDescendants = true) {
                this.role = Role.Button
                this.selected = selected
                if (!enabled) disabled()
                if (contentDescription != null) this.contentDescription = contentDescription
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.38f
            }
            .flickFocusRing(visible = ringVisible, shape = shape, ringColor = ringColor)
            .clip(shape)
            .background(fill)
            .border(borderWidth, stroke, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = horizontalArrangement,
        content = content,
    )
}

/**
 * A square glyph-only affordance — the panel close buttons (spec §5.4/§5.5).
 * Small by design (D-pad, not touch), but it carries the full focus ring so it
 * is never lost on screen.
 */
@Composable
fun FlickTvIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    side: Dp = 23.dp,
    glyphSize: Dp = 13.dp,
    shape: Shape = FlickShape.Sm,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    tint: Color = FlickColor.OnChrome,
    containerColor: Color = FlickColor.ChromeButtonFill,
    borderColor: Color = Color.Transparent,
    borderWidth: Dp = flickBorderWidth(containerColor),
    ringColor: Color = FlickColor.FocusRing,
) {
    FlickTvButton(
        onClick = onClick,
        modifier = modifier.size(side),
        enabled = enabled,
        contentDescription = contentDescription,
        focusRequester = focusRequester,
        shape = shape,
        ringColor = ringColor,
        containerColor = containerColor,
        borderColor = borderColor,
        borderWidth = borderWidth,
        contentPadding = PaddingValues(0.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(glyphSize),
        )
    }
}

/** A focusable settings/list row — full-width, left-aligned, same focus rules. */
@Composable
fun FlickTvRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    contentDescription: String? = null,
    shape: Shape = FlickShape.Md,
    ringColor: Color = FlickColor.FocusRing,
    containerColor: Color? = null,
    borderColor: Color? = null,
    borderWidth: Dp = flickBorderWidth(containerColor),
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(14.dp),
    content: @Composable RowScope.() -> Unit,
) {
    FlickTvButton(
        onClick = onClick,
        modifier = modifier,
        selected = selected,
        enabled = enabled,
        contentDescription = contentDescription,
        focusRequester = focusRequester,
        shape = shape,
        ringColor = ringColor,
        containerColor = containerColor,
        borderColor = borderColor,
        borderWidth = borderWidth,
        contentPadding = contentPadding,
        horizontalArrangement = horizontalArrangement,
        content = content,
    )
}
