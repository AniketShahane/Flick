package com.flick.sender.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.PillShape

enum class AdvisoryTone { CAUTION, INFO }

/**
 * A tinted, actionable advisory (design §7 / S11) — never a modal, never a toast
 * wall. Names the specific number at stake and the exact fix.
 */
@Composable
fun AdvisoryCard(
    icon: ImageVector,
    title: String,
    body: String,
    tone: AdvisoryTone,
    primaryLabel: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val colors = LocalFlickColors.current
    val accent = if (tone == AdvisoryTone.CAUTION) colors.caution else colors.spark
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(accent.copy(alpha = 0.08f))
            .border(1.dp, accent.copy(alpha = 0.28f), shape)
            .padding(14.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp).padding(top = 1.dp),
        )
        Column(Modifier.padding(start = 11.dp)) {
            Text(
                text = title,
                style = FlickText.body.copy(fontWeight = FontWeight.Bold, color = colors.onSurface),
            )
            Text(
                text = body,
                style = FlickText.caption.copy(color = colors.onSurfaceDim),
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(
                Modifier.padding(top = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                Text(
                    text = primaryLabel,
                    style = FlickText.caption.copy(fontWeight = FontWeight.Bold, color = Color.White),
                    modifier = Modifier
                        .clip(PillShape)
                        .background(accent)
                        .clickable(onClick = onPrimary)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                if (secondaryLabel != null && onSecondary != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = secondaryLabel,
                        style = FlickText.caption.copy(fontWeight = FontWeight.SemiBold, color = accent),
                        modifier = Modifier
                            .clickable(onClick = onSecondary)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
