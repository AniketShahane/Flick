package com.flick.sender.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Semantic shape scale from the selected expressive direction. */
object FlickCorners {
    val sm = 12.dp
    val md = 18.dp
    val lg = 24.dp
    val xl = 32.dp
    val hero = 40.dp
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
