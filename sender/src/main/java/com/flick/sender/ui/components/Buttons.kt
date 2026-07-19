package com.flick.sender.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flick.sender.ui.theme.FlickGradients
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.PillShape

/** Full-width Spark-gradient pill (primary action). */
@Composable
fun FlickPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalFlickColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(PillShape)
            .alpha(if (enabled) 1f else 0.5f)
            .background(FlickGradients.spark(dark = !colors.isLight))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = FlickText.body.copy(fontWeight = FontWeight.Bold, color = Color.White))
    }
}

/**
 * The "Flick to <TV>" CTA (design S4): gradient pill whose leading motion streaks
 * imply the toss, with the play glyph and label.
 */
@Composable
fun FlickCastButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFlickColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(PillShape)
            .background(FlickGradients.spark(dark = !colors.isLight))
            .clickable(onClick = onClick)
            .padding(vertical = 15.dp, horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Leading streaks.
        Row(
            Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Box(Modifier.size(width = 10.dp, height = 3.dp).clip(PillShape).background(Color.White.copy(alpha = 0.65f)))
            Box(Modifier.size(width = 6.dp, height = 3.dp).clip(PillShape).background(Color.White.copy(alpha = 0.4f)))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(FlickIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(9.dp))
            Text(text, style = FlickText.body.copy(fontWeight = FontWeight.Bold, color = Color.White))
        }
    }
}

/** Quiet secondary action ("play on this phone instead"). */
@Composable
fun FlickSubtleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFlickColors.current
    Text(
        text = text,
        style = FlickText.caption.copy(fontWeight = FontWeight.SemiBold, color = colors.onSurfaceFaint),
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 12.dp),
    )
}
