package com.flick.receiver.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Corner tokens (design-tokens.md §3). Rounded is the resting personality;
 * [Pill] is reserved for LIVE things — scrub tracks, status pills, the connect
 * chip.
 */
object FlickShape {
    val Sm = RoundedCornerShape(8.dp)
    val Md = RoundedCornerShape(12.dp)
    val Lg = RoundedCornerShape(16.dp)
    val Xl = RoundedCornerShape(24.dp)
    val Pill = RoundedCornerShape(percent = 50)
}
