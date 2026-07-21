package com.flick.receiver.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Corner tokens (design-tokens.md §3). TV containment is low and wide, with
 * [Pill] reserved for live status and scrub tracks.
 */
object FlickShape {
    val Sm = RoundedCornerShape(12.dp)
    val Md = RoundedCornerShape(18.dp)
    val Lg = RoundedCornerShape(24.dp)
    val Xl = RoundedCornerShape(32.dp)
    val Hero = RoundedCornerShape(40.dp)
    val Pill = RoundedCornerShape(percent = 50)
}
