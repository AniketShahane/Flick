package com.flick.sender.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flick.sender.model.DiscoveredTv
import com.flick.sender.model.TvAvailability
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.LocalFlickColors

/** A discovered-TV row (design S1). Selected → cyan border + faint glow. */
@Composable
fun DeviceRow(
    tv: DiscoveredTv,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFlickColors.current
    val shape = RoundedCornerShape(FlickCorners.md)
    val ready = tv.state == TvAvailability.READY
    Row(
        modifier = modifier
            .clip(shape)
            .background(colors.surfaceRaised)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) colors.link.copy(alpha = 0.5f) else colors.outlineHairline,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .heightIn(min = 48.dp)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 44.dp, height = 28.dp)
                .clip(RoundedCornerShape(FlickCorners.sm))
                .background(colors.surfaceRaisedAlt)
                .border(1.dp, colors.outlineHairline, RoundedCornerShape(FlickCorners.sm)),
        )
        Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
            Text(
                text = tv.name,
                style = FlickText.caption.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface),
            )
            Text(
                // The live endpoint is shown here so the user reads the address off the
                // phone rather than transcribing it from across the room.
                text = listOfNotNull(tv.model, stateLabel(tv.state), "${tv.host}:${tv.port}").joinToString(" · "),
                style = FlickText.caption.copy(fontSize = 10.sp, color = colors.onSurfaceFaint),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        LiveDot(
            color = if (ready) colors.live else colors.onSurfaceFaint.copy(alpha = 0.4f),
            size = 8.dp,
            pulsing = ready,
        )
    }
}

private fun stateLabel(state: TvAvailability): String = when (state) {
    TvAvailability.READY -> "ready"
    TvAvailability.SLEEPING -> "sleeping"
    TvAvailability.UNKNOWN -> "found"
}

/** A pairing affordance card — "Scan the TV's QR" / "Enter code" (design S1). */
@Composable
fun PairOptionCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    codeHint: String? = null,
) {
    val colors = LocalFlickColors.current
    val shape = RoundedCornerShape(FlickCorners.md)
    Column(
        modifier = modifier
            .clip(shape)
            .background(colors.surfaceTonal)
            .border(1.dp, colors.outlineHairline, shape)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .heightIn(min = 48.dp)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = colors.link, modifier = Modifier.size(20.dp))
        }
        if (codeHint != null) {
            Text(
                text = codeHint,
                style = FlickText.mono.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.link, letterSpacing = 4.sp),
            )
        }
        Text(
            text = title,
            style = FlickText.caption.copy(fontWeight = FontWeight.SemiBold, color = colors.onSurface),
        )
        Text(
            text = subtitle,
            style = FlickText.caption.copy(fontSize = 10.sp, color = colors.onSurfaceFaint),
        )
    }
}

/** 4-cell numeric code entry (the code shown on the TV, design T1 ↔ S1). */
@Composable
fun PairCodeField(
    code: String,
    onCodeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFlickColors.current
    BasicTextField(
        value = code,
        onValueChange = { raw -> onCodeChange(raw.filter { it.isDigit() }.take(4)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = modifier,
        decorationBox = { innerTextField ->
            Box {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(4) { i ->
                        val ch = code.getOrNull(i)?.toString() ?: ""
                        val focused = i == code.length
                        Box(
                            Modifier
                                .size(width = 46.dp, height = 56.dp)
                                .clip(RoundedCornerShape(FlickCorners.sm))
                                .background(colors.surfaceRaised)
                                .border(
                                    width = if (focused) 1.5.dp else 1.dp,
                                    color = if (focused) colors.link else colors.outline,
                                    shape = RoundedCornerShape(FlickCorners.sm),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = ch,
                                style = FlickText.timecode.copy(fontSize = 24.sp, color = colors.onSurface, textAlign = TextAlign.Center),
                            )
                        }
                    }
                }
                // The real editing surface — kept visually collapsed; the cells above
                // are the rendered view of the same text state (focus still attaches).
                Box(Modifier.size(0.dp)) { innerTextField() }
            }
        },
    )
}
