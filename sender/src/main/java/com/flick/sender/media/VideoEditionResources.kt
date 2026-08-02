package com.flick.sender.media

import androidx.annotation.StringRes
import com.flick.sender.R

@StringRes
fun VideoEdition.labelResource(): Int = when (this) {
    VideoEdition.DIRECTORS_CUT -> R.string.media_edition_directors_cut
    VideoEdition.EXTENDED_CUT -> R.string.media_edition_extended_cut
    VideoEdition.FINAL_CUT -> R.string.media_edition_final_cut
    VideoEdition.SPECIAL_EDITION -> R.string.media_edition_special
    VideoEdition.ULTIMATE_EDITION -> R.string.media_edition_ultimate
    VideoEdition.COLLECTORS_EDITION -> R.string.media_edition_collectors
    VideoEdition.THEATRICAL_CUT -> R.string.media_edition_theatrical
    VideoEdition.IMAX_EDITION -> R.string.media_edition_imax
    VideoEdition.UNRATED -> R.string.media_edition_unrated
    VideoEdition.REDUX -> R.string.media_edition_redux
}
