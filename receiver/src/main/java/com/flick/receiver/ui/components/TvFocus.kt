package com.flick.receiver.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickMotion
import com.flick.receiver.ui.theme.FlickShape

/**
 * The one TV focus primitive (design-tokens.md §1.7). There is no hover on TV, so
 * this is the whole vocabulary:
 *  - FOCUSED  = scale 1.08 + tonal lift + 2dp Link-cyan border + soft cyan glow.
 *  - SELECTED = Spark tint WITHOUT the glow, so focus and selection never blur.
 *  - DISABLED = 38% opacity.
 *
 * D-pad center fires [onClick] (foundation `clickable` maps DPAD_CENTER/ENTER to
 * click for a focused element). Every screen requests focus on exactly one of
 * these at entry so a focus target is always present.
 */
@Composable
fun FlickTvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    shape: Shape = FlickShape.Pill,
    contentPadding: PaddingValues = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (focused && enabled) 1.08f else 1f,
        animationSpec = FlickMotion.focusPop(),
        label = "focusScale",
    )

    val background: Color = when {
        selected -> FlickColor.Spark.copy(alpha = 0.16f)
        focused -> Color(0xFF221D30) // tonal lift
        else -> FlickColor.SurfaceRaised
    }
    val borderColor: Color = when {
        focused -> FlickColor.Link
        selected -> FlickColor.SparkLight.copy(alpha = 0.5f)
        else -> FlickColor.OutlineHairline
    }
    val borderWidth = if (focused) 2.dp else 1.dp

    Row(
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.38f
            }
            .shadow(
                elevation = if (focused && enabled) 16.dp else 0.dp,
                shape = shape,
                clip = false,
                ambientColor = FlickColor.Link,
                spotColor = FlickColor.Link,
            )
            .clip(shape)
            .background(background)
            .border(borderWidth, borderColor, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** A focusable settings/list row (T10) — full-width, left-aligned, same focus rules. */
@Composable
fun FlickTvRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    content: @Composable RowScope.() -> Unit,
) {
    FlickTvButton(
        onClick = onClick,
        modifier = modifier,
        focusRequester = focusRequester,
        shape = RoundedCornerShape(11.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        content = content,
    )
}
