package com.flick.sender.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Corner tokens from design-tokens.md §3. Rounded is the resting personality;
 * full-pill (999) is reserved for *live* things — scrub tracks, status pills,
 * the connect chip.
 */
object FlickCorners {
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val full = 999.dp
}

val FlickShapes = Shapes(
    extraSmall = RoundedCornerShape(FlickCorners.sm),
    small = RoundedCornerShape(FlickCorners.sm),
    medium = RoundedCornerShape(FlickCorners.md),
    large = RoundedCornerShape(FlickCorners.lg),
    extraLarge = RoundedCornerShape(FlickCorners.xl),
)

val PillShape = RoundedCornerShape(FlickCorners.full)
