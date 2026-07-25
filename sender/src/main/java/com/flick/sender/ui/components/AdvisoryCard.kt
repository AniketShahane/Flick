package com.flick.sender.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.PillShape
import com.flick.sender.ui.theme.flickRipple
import com.flick.sender.ui.theme.pressScale

enum class AdvisoryTone { CAUTION, INFO }

/**
 * A tinted, actionable advisory (S11) — never a modal, never a toast wall. Names
 * the specific condition at stake and the exact fix. [CAUTION][AdvisoryTone.CAUTION]
 * carries the amber caution fill used by the library band banner;
 * [INFO][AdvisoryTone.INFO] sits on the inverse surface so two stacked cards never
 * shout at each other.
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
    val container = if (tone == AdvisoryTone.CAUTION) colors.caution else colors.inverseSurface
    val ink = if (tone == AdvisoryTone.CAUTION) colors.onCaution else colors.onInverseSurface
    val glyph = if (tone == AdvisoryTone.CAUTION) colors.onCaution else colors.spark
    val primaryPress = remember { MutableInteractionSource() }
    val secondaryPress = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(FlickCorners.warning))
            .background(container)
            .padding(horizontal = 17.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = glyph,
            modifier = Modifier.size(22.dp),
        )
        Column {
            Text(
                text = title,
                style = FlickText.bodySmall.copy(fontWeight = FontWeight.ExtraBold, color = ink),
            )
            Text(
                text = body,
                style = FlickText.bodySmall.copy(color = ink.copy(alpha = 0.86f)),
                modifier = Modifier.padding(top = 3.dp),
            )
            Row(
                Modifier.padding(top = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = primaryLabel,
                    style = FlickText.labelMedium.copy(color = container),
                    modifier = Modifier
                        .pressScale(primaryPress)
                        .clip(PillShape)
                        .background(ink)
                        .clickable(
                            interactionSource = primaryPress,
                            indication = flickRipple(container),
                            onClick = onPrimary,
                        )
                        .semantics { role = Role.Button }
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp, vertical = 15.dp),
                )
                if (secondaryLabel != null && onSecondary != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = secondaryLabel,
                        style = FlickText.labelMedium.copy(color = ink.copy(alpha = 0.78f)),
                        modifier = Modifier
                            .pressScale(secondaryPress)
                            .clip(PillShape)
                            .clickable(
                                interactionSource = secondaryPress,
                                indication = flickRipple(ink),
                                onClick = onSecondary,
                            )
                            .semantics { role = Role.Button }
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 14.dp, vertical = 15.dp),
                    )
                }
            }
        }
    }
}
