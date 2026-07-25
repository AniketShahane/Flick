package com.flick.receiver.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.ui.theme.BrandMark
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickType

/**
 * The Flick lockup (receiver-expressive-spec.md §5.1): brand mark + wordmark,
 * with the optional `RECEIVER · <TV NAME>` eyebrow stacked beneath. The eyebrow
 * carries the real device name — there is no version string to invent.
 *
 * The streaks are dropped below ~24 dp, where they stop resolving at 10 ft.
 */
@Composable
fun FlickWordmark(
    modifier: Modifier = Modifier,
    markSize: Dp = 38.dp,
    textSizeSp: Int = 23,
    eyebrow: String? = null,
    tint: Color = FlickColor.OnSurface,
    markTint: Color = FlickColor.OnSurface,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BrandMark(size = markSize, tint = markTint, withStreaks = markSize >= 24.dp)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.brand_wordmark),
                style = FlickType.display(sizeSp = textSizeSp),
                color = tint,
            )
            if (eyebrow != null) {
                Text(
                    text = eyebrow,
                    style = FlickType.monoEyebrow(trackingEm = 0.2f),
                    color = FlickColor.OnSurfaceMuted,
                )
            }
        }
    }
}
