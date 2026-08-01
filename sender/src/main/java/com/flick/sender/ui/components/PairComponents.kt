package com.flick.sender.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
 * A discovered-TV row (design §5.1.4) in one of four states: the [connected] TV wears
 * the filled card and a contrasting accent ring, the featured TV is the same filled
 * action-coloured card without one, other ready TVs are tonal, and a sleeping TV is a flat
 * outline that cannot be tapped — selecting it would only fail at the handshake.
 *
 * [connected] outranks both of the others. Featured is a recommendation of where to
 * go next and a sleeping advertisement is stale the moment the TV answers a control
 * frame; a live link is neither, so it decides the row on its own.
 */
@Composable
fun DeviceRow(
    tv: DiscoveredTv,
    featured: Boolean,
    connected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFlickColors.current
    val shape = RoundedCornerShape(FlickCorners.deviceRow)
    val connectedLabel = stringResource(R.string.connect_device_connected)
    val asleep = tv.state == TvAvailability.SLEEPING && !connected
    val primary = (featured || connected) && !asleep
    // Tapping a known TV does not open the pairing sheet — selectDevice resumes its
    // stored pairing, which closes and re-dials the control link and routes to Library.
    // The one row whose whole claim is that the link is up must not be the control that
    // tears it down mid-cast.
    val interactive = !asleep && !connected

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
            // Ahead of the shadow, fill and outline: the press layer only wraps what
            // follows it, so a later pressScale would shrink the row's contents inside
            // a card that stayed full size.
            .then(if (interactive) Modifier.pressScale(interaction) else Modifier)
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
            .then(
                when {
                    asleep -> Modifier.border(2.dp, colors.outline, shape)
                    // The ring rather than the fill is what separates connected from
                    // featured, both of which wear the action fill. It takes the INVERSE
                    // accent because it is drawn on that fill, and the fill inverts
                    // polarity between the sets — deep blue in light, gold in dark — so the
                    // accent standing on it cannot be one value. The plain accent measures
                    // 1.88:1 on the gold; this holds 3.90:1 there and 4.09:1 on the blue.
                    connected -> Modifier.border(2.dp, colors.sparkInverse, shape)
                    else -> Modifier
                },
            )
            .then(
                if (interactive) {
                    Modifier
                        .clickable(
                            interactionSource = interaction,
                            indication = flickRipple(pressTint),
                            onClick = onClick,
                        )
                        .semantics { role = Role.Button }
                } else {
                    Modifier
                },
            )
            .then(
                when {
                    // Nothing merges this row once it stops being clickable, so without
                    // this the reader walks a name and an address and never says which
                    // TV is the live one.
                    connected -> Modifier.semantics(mergeDescendants = true) {
                        stateDescription = connectedLabel
                    }
                    // The wash that used to say "not now" took the instruction down to
                    // 2.65:1 with it — and said nothing at all to a reader. The flat
                    // outline carries it visually; this is what carries it aloud.
                    asleep -> Modifier.semantics(mergeDescendants = true) { disabled() }
                    else -> Modifier
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
            if (connected) ConnectedBadge(connectedLabel)
            Text(
                // The live endpoint is shown here so the user reads the address off the
                // phone rather than transcribing it from across the room — which means
                // it has to survive the clamp. A sleeping TV's wake instruction plus a
                // host:port does not fit one 12.5 sp line on a 412 dp frame.
                text = listOfNotNull(
                    tv.model,
                    // The badge above already says it, and an advertisement that still
                    // claims READY or SLEEPING is the stale half of the row.
                    if (connected) null else stateLabel(tv.state),
                    "${tv.host}:${tv.port}",
                ).joinToString(" · "),
                style = FlickText.bodyMedium.copy(color = subtitle),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        if (interactive) {
            Icon(
                imageVector = FlickIcons.ChevronRight,
                contentDescription = null,
                tint = if (primary) colors.onPrimary else colors.onPrimaryContainer,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/**
 * The live-link mark, inside the row rather than trailing it: a pill wide enough to
 * carry the word would eat the name's clamp on a 360 dp frame. Amber inverts to dark
 * ink for the same reason the caution status pill does — as ink it never clears its
 * contrast floor.
 */
@Composable
private fun ConnectedBadge(label: String) {
    val colors = LocalFlickColors.current
    Row(
        Modifier
            .padding(top = 7.dp)
            .clip(PillShape)
            .background(colors.spark)
            .padding(horizontal = 9.dp, vertical = 4.dp)
            // Spoken once: the row carries this as its state description, and a reader
            // that also read the pill would say "connected" twice per TV.
            .clearAndSetSemantics {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveDot(color = colors.onSpark, size = 6.dp, pulsing = true)
        Spacer(Modifier.width(6.dp))
        Text(text = label, style = FlickText.labelMedium.copy(color = colors.onSpark))
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
 * renders empty. A discovered TV owns its own code-entry path; this card keeps the two
 * address-finding fallbacks for when discovery is not enough.
 */
@Composable
fun PairQrCard(
    onScan: () -> Unit,
    onEnterAddress: () -> Unit,
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
                    // On the inverse card, which is near-black in light and near-WHITE in
                    // dark, so this reads the accent chosen by the ground: the ramp's own
                    // bright tone measures 1.77:1 there in dark.
                    style = FlickText.monoDisplay.copy(color = colors.sparkInverse),
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
            InverseCardAction(
                text = stringResource(R.string.connect_pair_scan),
                accessibilityLabel = stringResource(R.string.a11y_connect_pair_scan),
                container = colors.primary,
                contentColor = colors.onPrimary,
                onClick = onScan,
                modifier = Modifier.weight(1f),
            )
            InverseCardAction(
                text = stringResource(R.string.connect_pair_manual),
                accessibilityLabel = stringResource(R.string.a11y_connect_pair_manual),
                container = Color.Transparent,
                contentColor = colors.onInverseSurface,
                outline = colors.onInverseSurfaceDim,
                onClick = onEnterAddress,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Shared by inverse feature cards; the caller supplies its card-local palette. */
@Composable
internal fun InverseCardAction(
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
                                // The resting edge is an INK role doing a stroke's job: no
                                // outline role in this palette reaches 3:1 on either theme,
                                // and `outline` measured 1.43:1 on the light sheet and
                                // 1.76:1 on the dark one — a cell with no perceptible edge.
                                // `onSurfaceFaint` is the quietest ink that clears it, at
                                // 4.18:1 / 6.01:1 outside and 3.60:1 / 5.14:1 against the
                                // fill inside. The manual-address form's fields are built
                                // from this same recipe, so the escape hatch reads as these
                                // cells' sibling rather than as a different kind of input.
                                .border(
                                    width = if (focused) 2.dp else 1.dp,
                                    color = if (focused) colors.primary else colors.onSurfaceFaint,
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
