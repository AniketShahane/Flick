package com.flick.sender.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flick.sender.R
import com.flick.sender.ui.theme.CinemaGlass
import com.flick.sender.ui.theme.CinemaGlassEdge
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.PillShape

/**
 * One fact as a glass pill on the dark detail page. [known] false is a fact the phone could not
 * read: no fill and the dim ink, so the seat stays without making a claim. [mark] sits before
 * the text in a [MarkSize] square.
 */
@Composable
internal fun FactChip(
    text: String,
    known: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = FlickText.monoChip,
    mark: (@Composable () -> Unit)? = null,
) {
    val colors = LocalFlickColors.current
    Row(
        modifier
            .clip(PillShape)
            .background(if (known) CinemaGlass else Color.Transparent)
            .border(1.dp, if (known) CinemaGlassEdge else colors.outline, PillShape)
            .padding(start = if (mark != null) 10.dp else 12.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mark != null) Box(Modifier.height(MarkSize), contentAlignment = Alignment.Center) { mark() }
        Text(
            text = text,
            style = style.copy(color = if (known) Color.White else colors.onSurfaceDim),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The file's own running time, with the clock; the em dash when nothing measured it. */
@Composable
internal fun RuntimeChip(text: String, known: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalFlickColors.current
    val spoken = stringResource(R.string.a11y_detail_runtime, text)
    FactChip(
        text = text,
        known = known,
        modifier = modifier.clearAndSetSemantics { contentDescription = spoken },
        style = FlickText.chipFigure,
        mark = {
            Icon(
                imageVector = FlickIcons.Clock,
                contentDescription = null,
                tint = if (known) Color.White.copy(alpha = MarkInkAlpha) else colors.onSurfaceDim,
                modifier = Modifier.size(MarkSize),
            )
        },
    )
}

private val MarkSize = 14.dp
private const val MarkInkAlpha = 0.82f

