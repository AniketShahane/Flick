package com.flick.sender.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.PillShape

/** Compact library affordance that keeps the active TV session one tap away. */
@Composable
fun MiniNowPlayingBar(
    title: String,
    status: String,
    actionLabel: String,
    accessibilityLabel: String,
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    poster: @Composable () -> Unit = {},
) {
    val colors = LocalFlickColors.current
    val shape = RoundedCornerShape(FlickCorners.lg)
    Row(
        modifier = modifier
            .heightIn(min = 76.dp)
            .clip(shape)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.spark.copy(alpha = 0.28f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = accessibilityLabel }
            .padding(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 80.dp, height = 50.dp)
                .clip(RoundedCornerShape(FlickCorners.sm))
                .background(colors.surfaceTonal),
        ) {
            poster()
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveDot(
                    color = if (playing) colors.live else colors.caution,
                    pulsing = playing,
                    size = 5.dp,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(
                    text = status,
                    style = FlickText.monoLabel.copy(
                        color = if (playing) colors.live else colors.caution,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = title,
                style = FlickText.caption.copy(fontWeight = FontWeight.SemiBold, color = colors.onSurface),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .clip(PillShape)
                .background(colors.spark.copy(alpha = 0.14f))
                .padding(horizontal = 11.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = actionLabel,
                style = FlickText.caption.copy(
                    fontWeight = FontWeight.Bold,
                    color = if (colors.isLight) colors.spark else colors.sparkLight,
                ),
            )
        }
    }
}
