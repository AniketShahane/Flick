package com.flick.receiver.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.ui.theme.BrandMark
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickType

/** The Flick lockup: brand mark + wordmark (§1.1). */
@Composable
fun FlickWordmark(
    modifier: Modifier = Modifier,
    markSize: Dp = 30.dp,
    textSizeSp: Int = 32,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BrandMark(size = markSize, withStreaks = markSize >= 24.dp)
        Text(
            text = stringResource(R.string.brand_wordmark),
            fontFamily = FlickType.Display,
            fontWeight = FontWeight.Bold,
            fontSize = textSizeSp.sp,
            letterSpacing = (-0.03).em,
            color = FlickColor.OnSurface,
        )
    }
}
