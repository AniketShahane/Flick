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

/**
 * Always the simplified name, whatever the setting: for a heading that stands in for the
 * film's title logo, where a raw release name would be noise rather than information.
 */
@Composable
fun MediaItem.simplifiedName(): String {
    val parsed = remember(name) { VideoNames.parse(name) }
    val editionLabel = parsed.edition?.let { stringResource(it.labelResource()) }
    return VideoNames.format(parsed, editionLabel).ifBlank { stringResource(R.string.media_title_generic) }
}

/**
 * The film's own name with no year and no episode marker, for a heading whose facts line
 * already carries the year from TMDB: one year, from one source. The simplified name when the
 * parser found no title.
 */
@Composable
fun MediaItem.titleWithoutYear(): String {
    val parsed = remember(name) { VideoNames.parse(name) }
    return parsed.title?.takeIf { it.isNotBlank() } ?: simplifiedName()
}
