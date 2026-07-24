package com.flick.receiver.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickShape
import com.flick.receiver.ui.theme.FlickType

/**
 * The bordered mono spec chip (receiver-expressive-spec.md §5.3 row 1) —
 * `4K DOLBY VISION`, `E-AC-3 · 5.1`, `HEVC`. Uppercase mono at the 16 sp
 * micro-label floor with the design's hairline border.
 *
 * Callers pass only chips built from real telemetry; a chip with nothing to say
 * is omitted, never filled with a placeholder.
 */
@Composable
fun SpecChip(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = FlickColor.OnChrome,
    borderColor: Color = FlickColor.OutlineHairline,
    containerColor: Color = Color.Transparent,
    shape: Shape = FlickShape.Sm,
    style: TextStyle = FlickType.monoEyebrow(trackingEm = 0.12f),
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(containerColor)
            .border(1.dp, borderColor, shape)
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = color,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
