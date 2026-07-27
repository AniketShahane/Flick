package com.flick.sender.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flick.sender.R
import com.flick.sender.model.DiscoveredTv
import com.flick.sender.model.TvAvailability
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.Ink
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.PillShape
import com.flick.sender.ui.theme.Primary
import com.flick.sender.ui.theme.PrimaryShadow
import com.flick.sender.ui.theme.Spark
import com.flick.sender.ui.theme.flickRipple
import com.flick.sender.ui.theme.pressMorph
import com.flick.sender.ui.theme.pressScale
import com.flick.sender.ui.theme.rememberFlickTouchHaptics

/**
 * A discovered-TV row (design §5.1.4) in one of three states: the featured TV is a
 * filled brand-blue card, other ready TVs are tonal, and a sleeping TV is a flat
 * outline that cannot be tapped — selecting it would only fail at the handshake.
 */
@Composable
fun DeviceRow(
    tv: DiscoveredTv,
    featured: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFlickColors.current
    val shape = RoundedCornerShape(FlickCorners.deviceRow)
    val asleep = tv.state == TvAvailability.SLEEPING
    val primary = featured && !asleep

    val container = when {
        asleep -> Color.Transparent
        primary -> colors.primary
        else -> colors.primaryContainer
    }
    val tile = when {
        asleep -> colors.surfaceDisabled
        primary -> Color.White.copy(alpha = 0.22f)
        else -> colors.primaryFixed
    }
    val title = when {
        asleep -> colors.onSurface
        primary -> colors.onPrimary
        else -> colors.onSurface
    }
    val subtitle = when {
        asleep -> colors.onSurfaceDim
        primary -> colors.onPrimary.copy(alpha = 0.82f)
        else -> colors.onSurfaceDim
    }
    val glyph = when {
        asleep -> colors.onSurfaceDim
        primary -> colors.onPrimary
        else -> colors.onPrimaryFixed
    }
    // The press has to read on the row's own fill, which inverts with the featured state.
    val pressTint = if (primary) colors.onPrimary else colors.primary

    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (asleep) Modifier.alpha(0.6f) else Modifier)
            // Ahead of the shadow, fill and outline: the press layer only wraps what
            // follows it, so a later pressScale would shrink the row's contents inside
            // a card that stayed full size.
            .then(if (asleep) Modifier else Modifier.pressScale(interaction))
            .then(
                if (primary) {
                    Modifier.shadow(14.dp, shape, clip = false, ambientColor = PrimaryShadow, spotColor = PrimaryShadow)
                } else {
                    Modifier
                },
            )
            // Replaces the row's clip: at the resting radius the two are identical, and
            // a clip laid over this one could only ever shrink what it already encloses.
            .pressMorph(interaction, restRadius = FlickCorners.deviceRow, pressedRadius = 22.dp)
            .background(container)
            .then(if (asleep) Modifier.border(2.dp, colors.outline, shape) else Modifier)
            .then(
                if (asleep) {
                    Modifier
                } else {
                    Modifier
                        .clickable(
                            interactionSource = interaction,
                            indication = flickRipple(pressTint),
                            onClick = onClick,
                        )
                        .semantics { role = Role.Button }
                },
            )
            .heightIn(min = 48.dp)
            .padding(horizontal = 19.dp, vertical = 17.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        Box(
            Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(FlickCorners.rowIcon))
                .background(tile),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (asleep) FlickIcons.TvOff else FlickIcons.Tv,
                contentDescription = null,
                tint = glyph,
                modifier = Modifier.size(28.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = tv.name,
                style = FlickText.titleMedium.copy(color = title),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // The live endpoint is shown here so the user reads the address off the
                // phone rather than transcribing it from across the room — which means
                // it has to survive the clamp. A sleeping TV's wake instruction plus a
                // host:port does not fit one 12.5 sp line on a 412 dp frame.
                text = listOfNotNull(tv.model, stateLabel(tv.state), "${tv.host}:${tv.port}")
                    .joinToString(" · "),
                style = FlickText.bodyMedium.copy(color = subtitle),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        if (!asleep) {
            Icon(
                imageVector = FlickIcons.ChevronRight,
                contentDescription = null,
                tint = if (primary) colors.onPrimary else colors.onPrimaryContainer,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun stateLabel(state: TvAvailability): String = when (state) {
    TvAvailability.READY -> stringResource(R.string.connect_device_ready)
    TvAvailability.SLEEPING -> stringResource(R.string.connect_device_asleep)
    TvAvailability.UNKNOWN -> stringResource(R.string.connect_device_found)
}

/**
 * The pairing card (design §5.1.5). The phone never holds the pairing code — it is
 * generated by and displayed on the TV — so the glyph is decorative and the code slot
 * renders empty. Scanning only fills the TV's address in; the code is still typed,
 * which is why the two actions sit side by side rather than one behind the other.
 */
@Composable
fun PairQrCard(
    onScan: () -> Unit,
    onEnterCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFlickColors.current
    val shape = RoundedCornerShape(FlickCorners.qrCard)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.inverseSurface)
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(
            // The blurb is one label; the two buttons below stay separately focusable.
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            QrGlyph()
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.connect_pair_card_title_scan),
                    style = FlickText.bodyLarge.copy(color = colors.onInverseSurface),
                )
                Text(
                    text = stringResource(R.string.connect_pair_card_slots),
                    style = FlickText.monoDisplay.copy(color = colors.sparkBright),
                    modifier = Modifier.padding(top = 7.dp),
                )
                Text(
                    text = stringResource(R.string.connect_pair_card_note_scan),
                    style = FlickText.bodyMedium.copy(color = colors.onInverseSurfaceDim),
                    modifier = Modifier.padding(top = 7.dp),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PairCardAction(
                text = stringResource(R.string.connect_pair_scan),
                accessibilityLabel = stringResource(R.string.a11y_connect_pair_scan),
                container = colors.primary,
                contentColor = colors.onPrimary,
                onClick = onScan,
                modifier = Modifier.weight(1f),
            )
            PairCardAction(
                text = stringResource(R.string.connect_pair_type),
                accessibilityLabel = stringResource(R.string.a11y_connect_pair_card),
                container = Color.Transparent,
                contentColor = colors.onInverseSurface,
                outline = colors.onInverseSurfaceDim,
                onClick = onEnterCode,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** One of the pairing card's two equal actions, sized to the card's own palette. */
@Composable
private fun PairCardAction(
    text: String,
    accessibilityLabel: String,
    container: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    outline: Color? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .pressScale(interaction)
            .clip(PillShape)
            .background(container)
            .then(if (outline != null) Modifier.border(1.dp, outline, PillShape) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = flickRipple(contentColor),
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = accessibilityLabel
            }
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = FlickText.labelLarge.copy(color = contentColor, textAlign = TextAlign.Center),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// Row-major 5×5 glyph: 1 ink, 2 brand blue, 3 amber, 0 empty.
private val QrGlyphRows = listOf("11011", "10201", "02110", "10103", "11011")

@Composable
private fun QrGlyph(modifier: Modifier = Modifier) {
    // A QR is dark-on-white by definition, so the glyph does not follow the palette.
    Column(
        modifier
            .size(92.dp)
            .clip(RoundedCornerShape(FlickCorners.statCard))
            .background(Color.White)
            .padding(9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        QrGlyphRows.forEach { row ->
            Row(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                row.forEach { cell ->
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .then(
                                if (cell == '0') {
                                    Modifier
                                } else {
                                    Modifier
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(
                                            when (cell) {
                                                '2' -> Primary
                                                '3' -> Spark
                                                else -> Ink
                                            },
                                        )
                                },
                            ),
                    )
                }
            }
        }
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
    val haptics = rememberFlickTouchHaptics()
    BasicTextField(
        value = code,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(4)
            // The keyboard still offers characters the code cannot hold and the cells
            // drop them without showing anything, so the rejection is only felt.
            if (digits.length < raw.length) haptics.reject()
            onCodeChange(digits)
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = modifier,
        decorationBox = { innerTextField ->
            Box {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(4) { i ->
                        val ch = code.getOrNull(i)?.toString() ?: ""
                        val focused = i == code.length
                        val cellShape = RoundedCornerShape(FlickCorners.tuneBtn)
                        Box(
                            Modifier
                                .size(width = 52.dp, height = 62.dp)
                                .clip(cellShape)
                                .background(colors.surfaceRaisedAlt)
                                .border(
                                    width = if (focused) 2.dp else 1.dp,
                                    color = if (focused) colors.primary else colors.outline,
                                    shape = cellShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = ch,
                                style = FlickText.monoGauge.copy(
                                    color = colors.onSurface,
                                    textAlign = TextAlign.Center,
                                ),
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
