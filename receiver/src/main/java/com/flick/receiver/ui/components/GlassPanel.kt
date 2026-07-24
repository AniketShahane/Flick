package com.flick.receiver.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickMotion
import com.flick.receiver.ui.theme.FlickShape
import com.flick.receiver.ui.theme.glassChrome
import com.flick.receiver.ui.theme.glassPanel
import com.flick.receiver.ui.theme.panelTopHighlightBrush
import com.flick.receiver.ui.theme.rememberReducedMotion

/**
 * Which glass a [GlassPanel] is cut from (receiver-expressive-spec.md §2a):
 *  - [Chrome] — `#163A8C` @ 13 %, the bottom transport panel;
 *  - [Panel]  — `#163A8C` @ 50 %, the subtitles / metrics panels and the
 *    handshake card, which carry dense text and need more body;
 *  - [Solid]  — the opaque `Surface` card with a white hairline, for panels that
 *    do not sit over moving video (the pairing manual-entry card).
 */
enum class GlassPanelTone { Chrome, Panel, Solid }

/**
 * The shared floating panel: glass fill, hairline border, the §2d inner top
 * highlight, and the design's `tvRise` entrance mapped onto
 * [FlickMotion.tvRise] with a [riseDistance] slide-up.
 *
 * The panel deliberately does NOT clip its content — a focused child's detached
 * amber ring extends past its own bounds and must survive.
 *
 * [borderColor] overrides the tone's own hairline — spec §5.2 gives the handshake
 * card the white [FlickColor.GlassBorder] rather than the cool one the side
 * panels wear.
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = FlickShape.Hero,
    tone: GlassPanelTone = GlassPanelTone.Chrome,
    borderColor: Color? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 26.dp, vertical = 22.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(17.dp),
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    riseDistance: Dp = FlickMotion.TvRise,
    animateEntrance: Boolean = true,
    showTopHighlight: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reducedMotion = rememberReducedMotion()
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val progress by animateFloatAsState(
        targetValue = if (entered || !animateEntrance) 1f else 0f,
        animationSpec = if (reducedMotion) tween(durationMillis = 0) else FlickMotion.tvRise(),
        label = "glassPanelRise",
    )
    // Hoisted out of the draw phase: this layer is invalidated by every chrome
    // tick underneath it, and the five-stop gradient would otherwise be rebuilt
    // on each redraw. It is a pure function of fractional stops, so one instance
    // serves every size.
    val topHighlight = remember { panelTopHighlightBrush() }

    Column(
        modifier = modifier
            .graphicsLayer {
                alpha = progress
                translationY = (1f - progress) * riseDistance.toPx()
            }
            .glassTone(tone, shape, borderColor)
            .drawWithContent {
                drawContent()
                if (showTopHighlight) {
                    drawRect(
                        brush = topHighlight,
                        size = Size(size.width, 1.dp.toPx()),
                    )
                }
            }
            .padding(contentPadding),
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        content = content,
    )
}

private fun Modifier.glassTone(
    tone: GlassPanelTone,
    shape: Shape,
    borderColor: Color?,
): Modifier = when (tone) {
    GlassPanelTone.Chrome ->
        if (borderColor == null) glassChrome(shape)
        else background(FlickColor.GlassChrome, shape).border(1.dp, borderColor, shape)
    GlassPanelTone.Panel ->
        if (borderColor == null) glassPanel(shape)
        else background(FlickColor.GlassPanel, shape).border(1.dp, borderColor, shape)
    GlassPanelTone.Solid ->
        background(FlickColor.Surface, shape).border(1.dp, borderColor ?: FlickColor.GlassBorder, shape)
}
