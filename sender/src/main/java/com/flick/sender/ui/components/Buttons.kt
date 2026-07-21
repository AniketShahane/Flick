package com.flick.sender.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flick.sender.ui.theme.FlickGradients
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.PillShape

/** Full-width Material primary action using the selected Spark role. */
@Composable
fun FlickPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalFlickColors.current
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = PillShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.spark,
            contentColor = Color.White,
        ),
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(text, style = FlickText.body.copy(fontWeight = FontWeight.Bold))
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
    accessibilityLabel: String? = null,
) {
    val colors = LocalFlickColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(PillShape)
            .background(FlickGradients.spark(dark = !colors.isLight))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { accessibilityLabel?.let { contentDescription = it } }
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
    TextButton(
        onClick = onClick,
        shape = PillShape,
        colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceDim),
        modifier = modifier.heightIn(min = 48.dp),
    ) { Text(text, style = FlickText.caption.copy(fontWeight = FontWeight.SemiBold)) }
}

/** Compact high-contrast action for persistent library tasks such as media reselection. */
@Composable
fun FlickTonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFlickColors.current
    Button(
        onClick = onClick,
        shape = PillShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.spark.copy(alpha = 0.14f),
            contentColor = if (colors.isLight) colors.spark else colors.sparkLight,
        ),
        border = BorderStroke(1.dp, colors.spark.copy(alpha = 0.34f)),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Text(text, style = FlickText.caption.copy(fontWeight = FontWeight.Bold))
    }
}
