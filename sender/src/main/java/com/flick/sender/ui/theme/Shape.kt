package com.flick.sender.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Semantic shape scale from the selected expressive direction. */
object FlickCorners {
    val full = 999.dp
    val sheet = 36.dp
    val qrCard = 34.dp
    val nav = 34.dp
    val poster = 32.dp
    val deviceRow = 30.dp
    val warning = 28.dp
    val fab = 28.dp
    val qualityCard = 26.dp
    val tile = 26.dp
    val seekBtn = 22.dp
    val toast = 22.dp
    val detailPoster = 22.dp
    val statCard = 20.dp
    val rowIcon = 19.dp
    val tuneBtn = 17.dp
    val backBtn = 15.dp
    val previewThumb = 15.dp
}

val FlickShapes = Shapes(
    extraSmall = RoundedCornerShape(FlickCorners.backBtn),
    small = RoundedCornerShape(FlickCorners.tuneBtn),
    medium = RoundedCornerShape(FlickCorners.statCard),
    large = RoundedCornerShape(FlickCorners.tile),
    extraLarge = RoundedCornerShape(FlickCorners.poster),
)

val PillShape = RoundedCornerShape(FlickCorners.full)

/** Bottom sheets round only their leading edge. */
val SheetShape = RoundedCornerShape(topStart = FlickCorners.sheet, topEnd = FlickCorners.sheet)
