package com.flick.sender.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import com.flick.sender.R
import com.flick.sender.media.VideoNames
import com.flick.sender.media.labelResource
import com.flick.sender.model.MediaItem

val LocalSimplifiedVideoNames = staticCompositionLocalOf { true }

@Composable
fun MediaItem.displayName(): String {
    val simplify = LocalSimplifiedVideoNames.current
    val parsed = remember(name) { VideoNames.parse(name) }
    val editionLabel = parsed.edition?.let { stringResource(it.labelResource()) }
    val displayed = if (simplify) VideoNames.format(parsed, editionLabel) else VideoNames.safeFileName(name)
    return displayed.ifBlank { stringResource(R.string.media_title_generic) }
}
